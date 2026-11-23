[CmdletBinding()]
param(
    [string]$ProjectRoot = "E:\MY PROJECT FOLDER\AURA-Butler-Android-Studio-v0.4.0-Native-Stable",
    [switch]$SkipBuild
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Write-Section {
    param([Parameter(Mandatory = $true)][string]$Text)

    Write-Host ""
    Write-Host ("==== " + $Text + " ====") -ForegroundColor Cyan
}

function Find-AndroidStudioJava {
    $candidates = New-Object System.Collections.Generic.List[string]

    foreach ($configured in @(
        $env:STUDIO_JDK,
        $env:JDK_HOME,
        $env:JAVA_HOME
    )) {
        if ($configured) {
            $candidates.Add([string]$configured)
        }
    }

    $javaCommand = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($javaCommand) {
        $javaBin = Split-Path -Parent $javaCommand.Source
        $candidates.Add((Split-Path -Parent $javaBin))
    }

    foreach ($studioProcess in @(
        Get-Process -Name "studio64", "studio" -ErrorAction SilentlyContinue
    )) {
        try {
            if ($studioProcess.Path) {
                $studioBin = Split-Path -Parent $studioProcess.Path
                $studioRoot = Split-Path -Parent $studioBin
                $candidates.Add((Join-Path $studioRoot "jbr"))
                $candidates.Add((Join-Path $studioRoot "jre"))
            }
        }
        catch {
        }
    }

    foreach ($candidate in @(
        "$env:ProgramFiles\Android\Android Studio\jbr",
        "$env:ProgramFiles\Android Studio\jbr",
        "$env:LOCALAPPDATA\Programs\Android Studio\jbr",
        "$env:LOCALAPPDATA\Google\AndroidStudio\jbr",
        "$env:USERPROFILE\AppData\Local\Programs\Android Studio\jbr",
        "E:\Program Files\Android\Android Studio\jbr"
    )) {
        if ($candidate) {
            $candidates.Add($candidate)
        }
    }

    foreach ($drive in @(
        Get-PSDrive -PSProvider FileSystem -ErrorAction SilentlyContinue
    )) {
        foreach ($relativeJavaHome in @(
            "Program Files\Android\Android Studio\jbr",
            "Program Files\Android Studio\jbr",
            "Android\Android Studio\jbr",
            "Android Studio\jbr",
            "Apps\Android Studio\jbr",
            "Programs\Android Studio\jbr"
        )) {
            $candidates.Add(
                (Join-Path $drive.Root $relativeJavaHome)
            )
        }
    }

    return $candidates |
        Where-Object {
            $_ -and
            (
                Test-Path -LiteralPath (
                    Join-Path $_ "bin\java.exe"
                ) -PathType Leaf
            )
        } |
        Select-Object -First 1
}

function Get-JavaMajorVersion {
    param([Parameter(Mandatory = $true)][string]$JavaHome)

    $javaExe = Join-Path $JavaHome "bin\java.exe"

    $startInfo = New-Object System.Diagnostics.ProcessStartInfo
    $startInfo.FileName = $javaExe
    $startInfo.Arguments = "-version"
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true

    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $startInfo

    try {
        [void]$process.Start()
        $stdout = $process.StandardOutput.ReadToEnd()
        $stderr = $process.StandardError.ReadToEnd()
        $process.WaitForExit()

        $versionText = ($stdout + " " + $stderr).Trim()

        if ($process.ExitCode -ne 0) {
            throw "java -version failed: $versionText"
        }

        if ($versionText -notmatch 'version\s+"(\d+)') {
            throw "Java version could not be parsed: $versionText"
        }

        return [int]$Matches[1]
    }
    finally {
        $process.Dispose()
    }
}

if (-not (Test-Path -LiteralPath $ProjectRoot -PathType Container)) {
    throw "AURA project folder was not found: $ProjectRoot"
}

$ProjectRoot = (Resolve-Path -LiteralPath $ProjectRoot).Path
$AndroidRoot = Join-Path $ProjectRoot "android"
$GradleWrapper = Join-Path $AndroidRoot "gradlew.bat"
$ManifestPath = Join-Path $ProjectRoot "android\app\src\main\AndroidManifest.xml"
$BuildGradlePath = Join-Path $ProjectRoot "android\app\build.gradle.kts"
$WakeServicePath = Join-Path $ProjectRoot (
    "android\app\src\main\java\in\diganta\aurabutler\" +
    "background\WakeWordService.kt"
)
$SpeakerPath = Join-Path $ProjectRoot (
    "android\app\src\main\java\in\diganta\aurabutler\" +
    "voice\Speaker.kt"
)

foreach ($requiredPath in @(
    $GradleWrapper,
    $ManifestPath,
    $BuildGradlePath,
    $WakeServicePath,
    $SpeakerPath
)) {
    if (-not (Test-Path -LiteralPath $requiredPath -PathType Leaf)) {
        throw "Required project file is missing: $requiredPath"
    }
}

$buildGradle = Get-Content -LiteralPath $BuildGradlePath -Raw

if (-not $buildGradle.Contains(
    'versionName = "0.8.2-free-news-weather"'
)) {
    throw "This repair requires AURA v0.8.2-free-news-weather."
}

$timestamp = Get-Date -Format "yyyyMMdd_HHmmss"
$BackupRoot = Join-Path $ProjectRoot (
    "_backup_wakelock_permission_fix_v082_" + $timestamp
)
$LogRoot = Join-Path $BackupRoot "logs"

New-Item -ItemType Directory -Path $LogRoot -Force | Out-Null

Write-Host ""
Write-Host "AURA v0.8.2 WAKE-LOCK PERMISSION REPAIR" -ForegroundColor Cyan
Write-Host ("Project: " + $ProjectRoot)
Write-Host ("Backup: " + $BackupRoot)

Write-Section "Backing up AndroidManifest.xml"

$ManifestBackup = Join-Path $BackupRoot (
    "android\app\src\main\AndroidManifest.xml"
)
New-Item -ItemType Directory `
    -Path (Split-Path -Parent $ManifestBackup) `
    -Force | Out-Null

Copy-Item `
    -LiteralPath $ManifestPath `
    -Destination $ManifestBackup `
    -Force

$restoreScript = @'
[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$BackupRoot = $PSScriptRoot
$ProjectRoot = Split-Path -Parent $BackupRoot
$RelativePath = "android\app\src\main\AndroidManifest.xml"
$Source = Join-Path $BackupRoot $RelativePath
$Target = Join-Path $ProjectRoot $RelativePath

if (-not (Test-Path -LiteralPath $Source -PathType Leaf)) {
    throw "The backed-up AndroidManifest.xml was not found."
}

Copy-Item -LiteralPath $Source -Destination $Target -Force

Write-Host ""
Write-Host "AndroidManifest.xml was restored." -ForegroundColor Green
Write-Host "Rebuild and reinstall AURA."
'@

[System.IO.File]::WriteAllText(
    (Join-Path $BackupRoot "RESTORE-AURA-v0.8.2-WakeLock.ps1"),
    $restoreScript,
    (New-Object System.Text.UTF8Encoding($true))
)

Write-Section "Applying WAKE_LOCK permission"

$manifestText = Get-Content -LiteralPath $ManifestPath -Raw

if ($manifestText.Contains("android.permission.WAKE_LOCK")) {
    Write-Host (
        "[PASS] WAKE_LOCK permission was already present"
    ) -ForegroundColor Green
}
else {
    $anchor = (
        '<uses-permission android:name="android.permission.' +
        'FOREGROUND_SERVICE" />'
    )

    if (-not $manifestText.Contains($anchor)) {
        throw "The FOREGROUND_SERVICE permission anchor was not found."
    }

    $wakePermission = (
        '<uses-permission android:name="android.permission.WAKE_LOCK" />'
    )

    $replacement = $anchor + [Environment]::NewLine +
        "    " + $wakePermission

    $manifestText = $manifestText.Replace($anchor, $replacement)

    [System.IO.File]::WriteAllText(
        $ManifestPath,
        $manifestText,
        (New-Object System.Text.UTF8Encoding($false))
    )

    Write-Host (
        "[PASS] Added android.permission.WAKE_LOCK"
    ) -ForegroundColor Green
}

try {
    [void][xml](Get-Content -LiteralPath $ManifestPath -Raw)
    Write-Host (
        "[PASS] AndroidManifest.xml remains valid XML"
    ) -ForegroundColor Green
}
catch {
    throw (
        "AndroidManifest.xml is malformed after repair: " +
        $_.Exception.Message
    )
}

$updatedManifest = Get-Content -LiteralPath $ManifestPath -Raw

if (-not $updatedManifest.Contains("android.permission.WAKE_LOCK")) {
    throw "WAKE_LOCK permission validation failed."
}

Write-Section "Verifying wake-lock and voice implementation"

$wakeSource = Get-Content -LiteralPath $WakeServicePath -Raw

foreach ($marker in @(
    "PowerManager.PARTIAL_WAKE_LOCK",
    ".newWakeLock(",
    "acquireWakeLock()",
    "releaseWakeLock()"
)) {
    if (-not $wakeSource.Contains($marker)) {
        throw "Wake service marker is missing: $marker"
    }
}

Write-Host (
    "[PASS] Wake lock acquisition and release paths are present"
) -ForegroundColor Green

$speakerSource = Get-Content -LiteralPath $SpeakerPath -Raw
$maleMarkers = @(
    "TextToSpeech",
    "chooseMaleFallbackVoice()",
    'listOf("male", "man", "gbb", "eng-gbr")',
    "fallback.voice = preferred"
)

$missingMaleMarkers = @(
    $maleMarkers |
        Where-Object { -not $speakerSource.Contains($_) }
)

if ($missingMaleMarkers.Count -eq 0) {
    Write-Host (
        "[PASS] Android male system-TTS fallback is implemented"
    ) -ForegroundColor Green
}
else {
    Write-Warning (
        "Male system-TTS fallback markers are incomplete: " +
        ($missingMaleMarkers -join ", ")
    )
}

if ($SkipBuild) {
    Write-Host ""
    Write-Host "Build skipped by request." -ForegroundColor Yellow
    exit 0
}

$JavaHome = Find-AndroidStudioJava

if (-not $JavaHome) {
    throw (
        "Java was not found. Keep Android Studio open and rerun this repair."
    )
}

$JavaMajor = Get-JavaMajorVersion -JavaHome $JavaHome

if ($JavaMajor -lt 17) {
    throw (
        "Java " +
        [string]$JavaMajor +
        " was found, but Java 17 or newer is required."
    )
}

Write-Host (
    "[PASS] Java " +
    [string]$JavaMajor +
    " found at " +
    $JavaHome
) -ForegroundColor Green

$env:JAVA_HOME = $JavaHome
$env:Path = (Join-Path $JavaHome "bin") + ";" + $env:Path

Write-Section "Building and testing repaired AURA v0.8.2"

$BuildLog = Join-Path $LogRoot "gradle-v0.8.2-wakelock-repair.log"
$StdoutPath = Join-Path $LogRoot "gradle.stdout.tmp"
$StderrPath = Join-Path $LogRoot "gradle.stderr.tmp"

$GradleArguments = @(
    "--no-daemon",
    "--console=plain",
    ":app:clean",
    ":app:compileDebugKotlin",
    ":app:testDebugUnitTest",
    ":app:lintDebug",
    ":app:assembleDebug",
    ":app:assembleDebugAndroidTest"
)

try {
    $GradleProcess = Start-Process `
        -FilePath $GradleWrapper `
        -ArgumentList $GradleArguments `
        -WorkingDirectory $AndroidRoot `
        -Wait `
        -PassThru `
        -NoNewWindow `
        -RedirectStandardOutput $StdoutPath `
        -RedirectStandardError $StderrPath

    $OutputLines = New-Object System.Collections.Generic.List[string]

    if (Test-Path -LiteralPath $StdoutPath -PathType Leaf) {
        foreach ($line in Get-Content -LiteralPath $StdoutPath) {
            $OutputLines.Add([string]$line)
        }
    }

    if (Test-Path -LiteralPath $StderrPath -PathType Leaf) {
        foreach ($line in Get-Content -LiteralPath $StderrPath) {
            $OutputLines.Add([string]$line)
        }
    }

    [System.IO.File]::WriteAllLines(
        $BuildLog,
        [string[]]$OutputLines,
        (New-Object System.Text.UTF8Encoding($false))
    )

    if ($GradleProcess.ExitCode -ne 0) {
        Write-Host ""
        Write-Host (
            "AURA v0.8.2 repair build failed."
        ) -ForegroundColor Red

        $errors = @(
            $OutputLines |
                Where-Object {
                    $_ -match (
                        "^(e:|error:|FAILURE:|" +
                        "Execution failed|Caused by:)"
                    )
                } |
                Select-Object -First 20
        )

        if ($errors.Count -gt 0) {
            Write-Host "First relevant errors:" -ForegroundColor Yellow

            foreach ($item in $errors) {
                Write-Host ([string]$item)
            }
        }

        Write-Host ("Complete build log: " + $BuildLog)
        Write-Host (
            "Rollback: " +
            (Join-Path $BackupRoot (
                "RESTORE-AURA-v0.8.2-WakeLock.ps1"
            ))
        )
        exit $GradleProcess.ExitCode
    }
}
finally {
    Remove-Item -LiteralPath $StdoutPath `
        -Force `
        -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $StderrPath `
        -Force `
        -ErrorAction SilentlyContinue
}

$ApkPath = Join-Path $AndroidRoot (
    "app\build\outputs\apk\debug\app-debug.apk"
)

if (-not (Test-Path -LiteralPath $ApkPath -PathType Leaf)) {
    throw (
        "Gradle succeeded, but the debug APK was not found: " +
        $ApkPath
    )
}

$ApkSizeMb = [math]::Round(
    (Get-Item -LiteralPath $ApkPath).Length / 1MB,
    2
)

Write-Host ""
Write-Host (
    "AURA v0.8.2 WAKE-LOCK REPAIR SUCCESSFUL"
) -ForegroundColor Green
Write-Host ("APK: " + $ApkPath)
Write-Host (
    "APK size: " +
    [string]$ApkSizeMb +
    " MB"
)
Write-Host ("Build log: " + $BuildLog)
Write-Host ""
Write-Host (
    "Install through Android Studio Run without uninstalling AURA."
) -ForegroundColor Cyan
