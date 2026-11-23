[CmdletBinding()]
param(
    [string]$ProjectRoot = "E:\MY PROJECT FOLDER\AURA-Butler-Android-Studio-v0.4.0-Native-Stable"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Write-Section {
    param([Parameter(Mandatory = $true)][string]$Text)

    Write-Host ""
    Write-Host ("==== " + $Text + " ====") -ForegroundColor Cyan
}

if (-not (Test-Path -LiteralPath $ProjectRoot -PathType Container)) {
    throw "AURA project folder was not found: $ProjectRoot"
}

$ProjectRoot = (Resolve-Path -LiteralPath $ProjectRoot).Path
$AndroidRoot = Join-Path $ProjectRoot "android"
$Gradle = Join-Path $AndroidRoot "gradlew.bat"
$BuildGradle = Join-Path $AndroidRoot "app\build.gradle.kts"

$TestFile = Join-Path $AndroidRoot (
    "app\src\test\java\in\diganta\aurabutler\" +
    "voice\SpeechTextSanitizerTest.kt"
)

$SanitizerFile = Join-Path $AndroidRoot (
    "app\src\main\java\in\diganta\aurabutler\" +
    "voice\SpeechTextSanitizer.kt"
)

foreach ($required in @(
    $Gradle,
    $BuildGradle,
    $TestFile,
    $SanitizerFile
)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
        throw "Required file is missing: $required"
    }
}

$BuildText = Get-Content -LiteralPath $BuildGradle -Raw

if (-not $BuildText.Contains(
    'versionName = "0.9.0-production-intelligence"'
)) {
    throw (
        "This continuation requires the installed " +
        "AURA v0.9.0-production-intelligence source."
    )
}

$SanitizerText = Get-Content -LiteralPath $SanitizerFile -Raw

if (-not $SanitizerText.Contains(
    '.replace(Regex("""(\d+(?:\.\d+)?)\s*%"""), "$1 percent")'
)) {
    throw (
        "The expected natural percent speech conversion " +
        "was not found in SpeechTextSanitizer.kt."
    )
}

$Timestamp = Get-Date -Format "yyyyMMdd_HHmmss"
$BackupRoot = Join-Path $ProjectRoot (
    "_backup_v090_speech_sanitizer_test_fix_" + $Timestamp
)
$LogRoot = Join-Path $BackupRoot "logs"

New-Item -ItemType Directory -Path $LogRoot -Force | Out-Null

$BackupTest = Join-Path $BackupRoot (
    "android\app\src\test\java\in\diganta\aurabutler\" +
    "voice\SpeechTextSanitizerTest.kt"
)

New-Item `
    -ItemType Directory `
    -Path (Split-Path -Parent $BackupTest) `
    -Force |
    Out-Null

Copy-Item `
    -LiteralPath $TestFile `
    -Destination $BackupTest `
    -Force

$RestoreScript = @'
[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$BackupRoot = $PSScriptRoot
$ProjectRoot = Split-Path -Parent $BackupRoot

$Source = Join-Path $BackupRoot (
    "android\app\src\test\java\in\diganta\aurabutler\" +
    "voice\SpeechTextSanitizerTest.kt"
)

$Target = Join-Path $ProjectRoot (
    "android\app\src\test\java\in\diganta\aurabutler\" +
    "voice\SpeechTextSanitizerTest.kt"
)

Copy-Item -LiteralPath $Source -Destination $Target -Force

Write-Host ""
Write-Host "The original SpeechTextSanitizerTest.kt was restored." `
    -ForegroundColor Green
'@

[System.IO.File]::WriteAllText(
    (Join-Path $BackupRoot "RESTORE-AURA-v0.9.0-SPEECH-TEST.ps1"),
    $RestoreScript,
    (New-Object System.Text.UTF8Encoding($true))
)

Write-Section "Correcting stale speech sanitizer test"

$TestText = Get-Content -LiteralPath $TestFile -Raw

$OldExpected = (
    '"Your battery level is 100 % and healthy.",'
)
$NewExpected = (
    '"Your battery level is 100 percent and healthy.",'
)

if ($TestText.Contains($OldExpected)) {
    $TestText = $TestText.Replace(
        $OldExpected,
        $NewExpected
    )
    Write-Host (
        "[PASS] Updated expected speech from 100 % to 100 percent"
    ) -ForegroundColor Green
}
elseif ($TestText.Contains($NewExpected)) {
    Write-Host (
        "[PASS] Natural percent expectation was already corrected"
    ) -ForegroundColor Green
}
else {
    throw (
        "The expected assertion could not be located. " +
        "The test file was not modified."
    )
}

$PercentAssertion = '        assertFalse(clean.contains("%"))'

if (-not $TestText.Contains($PercentAssertion)) {
    $Anchor = '        assertFalse(clean.contains("_"))'

    if (-not $TestText.Contains($Anchor)) {
        throw "The test assertion insertion point was not found."
    }

    $TestText = $TestText.Replace(
        $Anchor,
        $Anchor + [Environment]::NewLine + $PercentAssertion
    )

    Write-Host (
        "[PASS] Added regression assertion preventing percent-symbol speech"
    ) -ForegroundColor Green
}

[System.IO.File]::WriteAllText(
    $TestFile,
    $TestText,
    (New-Object System.Text.UTF8Encoding($false))
)

$UpdatedTest = Get-Content -LiteralPath $TestFile -Raw

if (-not $UpdatedTest.Contains($NewExpected)) {
    throw "The corrected natural-speech expectation was not saved."
}

if (-not $UpdatedTest.Contains($PercentAssertion)) {
    throw "The percent-symbol regression assertion was not saved."
}

Write-Host "[PASS] Test-file validation completed" -ForegroundColor Green
Write-Host ("Backup: " + $BackupRoot)

Write-Section "Finding Android Studio Java"

$JavaHomeCandidates = @(
    "E:\Program Files\Android\Android Studio\jbr",
    "$env:ProgramFiles\Android\Android Studio\jbr",
    "$env:LOCALAPPDATA\Programs\Android Studio\jbr",
    $env:JAVA_HOME
)

$JavaHome = $JavaHomeCandidates |
    Where-Object {
        $_ -and
        (
            Test-Path -LiteralPath (
                Join-Path $_ "bin\java.exe"
            ) -PathType Leaf
        )
    } |
    Select-Object -First 1

if (-not $JavaHome) {
    throw (
        "Android Studio Java was not found. " +
        "Keep Android Studio open and run this script again."
    )
}

$env:JAVA_HOME = $JavaHome
$env:Path = (Join-Path $JavaHome "bin") + ";" + $env:Path

Write-Host ("[PASS] Using Java: " + $JavaHome) -ForegroundColor Green

Write-Section "Running AURA v0.9.0 verification build"

$BuildLog = Join-Path $LogRoot (
    "gradle-v0.9.0-speech-test-fix.log"
)

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

Push-Location $AndroidRoot
$PreviousPreference = $ErrorActionPreference
$ErrorActionPreference = "Continue"

try {
    & $Gradle @GradleArguments 2>&1 |
        Tee-Object -FilePath $BuildLog

    $GradleExitCode = $LASTEXITCODE
}
finally {
    $ErrorActionPreference = $PreviousPreference
    Pop-Location
}

if ($GradleExitCode -ne 0) {
    Write-Host ""
    Write-Host "AURA v0.9.0 BUILD FAILED" -ForegroundColor Red
    Write-Host ("Build log: " + $BuildLog)
    Write-Host (
        "Rollback: " +
        (Join-Path $BackupRoot "RESTORE-AURA-v0.9.0-SPEECH-TEST.ps1")
    )
    exit $GradleExitCode
}

$Apk = Join-Path $AndroidRoot (
    "app\build\outputs\apk\debug\app-debug.apk"
)

if (-not (Test-Path -LiteralPath $Apk -PathType Leaf)) {
    throw "Gradle succeeded, but app-debug.apk was not found."
}

$ApkSizeMb = [math]::Round(
    (Get-Item -LiteralPath $Apk).Length / 1MB,
    2
)

Write-Host ""
Write-Host (
    "AURA v0.9.0 BUILD AND TESTS SUCCESSFUL"
) -ForegroundColor Green
Write-Host ("APK: " + $Apk)
Write-Host ("APK size: " + [string]$ApkSizeMb + " MB")
Write-Host ("Build log: " + $BuildLog)
Write-Host ""
Write-Host (
    "Install with Android Studio Run without uninstalling AURA."
) -ForegroundColor Cyan
