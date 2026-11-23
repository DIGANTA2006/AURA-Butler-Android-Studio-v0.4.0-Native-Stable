[CmdletBinding()]
param(
    [string]$ProjectRoot = "E:\MY PROJECT FOLDER\AURA-Butler-Android-Studio-v0.4.0-Native-Stable",
    [switch]$SkipGradle,
    [switch]$SkipDevice,
    [switch]$StrictHashes,
    [switch]$OpenReport
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# AURA project inspector
# - Read-only for source files
# - Creates reports only under _inspection_reports
# - Does not install, uninstall, patch, or modify the Android project
# - Masks any possible secret found during static scanning

$startedAt = Get-Date
$timestamp = $startedAt.ToString("yyyyMMdd_HHmmss")
$script:Findings = New-Object System.Collections.Generic.List[object]
$script:GradleResults = New-Object System.Collections.Generic.List[object]
$script:GeneratedFiles = New-Object System.Collections.Generic.List[string]

function Add-Finding {
    param(
        [Parameter(Mandatory = $true)]
        [ValidateSet("PASS", "WARN", "FAIL", "INFO")]
        [string]$Status,

        [Parameter(Mandatory = $true)]
        [string]$Category,

        [Parameter(Mandatory = $true)]
        [string]$Check,

        [Parameter(Mandatory = $true)]
        [string]$Message,

        [string]$Evidence = "",
        [string]$Recommendation = ""
    )

    $script:Findings.Add([pscustomobject]@{
        Status         = $Status
        Category       = $Category
        Check          = $Check
        Message        = $Message
        Evidence       = $Evidence
        Recommendation = $Recommendation
    }) | Out-Null

    $color = switch ($Status) {
        "PASS" { "Green" }
        "WARN" { "Yellow" }
        "FAIL" { "Red" }
        default { "Gray" }
    }

    Write-Host ("[{0}] {1} / {2}: {3}" -f $Status, $Category, $Check, $Message) -ForegroundColor $color
}

function Write-Section {
    param([Parameter(Mandatory = $true)][string]$Title)
    Write-Host ""
    Write-Host ("==== {0} ====" -f $Title) -ForegroundColor Cyan
}

function Get-Sha256 {
    param([Parameter(Mandatory = $true)][string]$Path)
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Get-RelativeProjectPath {
    param([Parameter(Mandatory = $true)][string]$Path)

    $full = [System.IO.Path]::GetFullPath($Path)
    $prefix = [System.IO.Path]::GetFullPath($ProjectRoot).TrimEnd("\") + "\"

    if ($full.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        return $full.Substring($prefix.Length)
    }

    return $full
}

function Read-Text {
    param([Parameter(Mandatory = $true)][string]$Path)

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        return ""
    }

    return Get-Content -LiteralPath $Path -Raw
}

function Test-TextMarker {
    param(
        [Parameter(Mandatory = $true)][string]$RelativePath,
        [Parameter(Mandatory = $true)][string]$Marker,
        [Parameter(Mandatory = $true)][string]$Label,
        [string]$Category = "Update verification",
        [switch]$Regex,
        [string]$Recommendation = ""
    )

    $path = Join-Path $ProjectRoot $RelativePath

    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        Add-Finding -Status "FAIL" -Category $Category -Check $Label `
            -Message "Required file is missing." `
            -Evidence $RelativePath `
            -Recommendation "Reapply the updater that owns this file."
        return $false
    }

    $content = Read-Text -Path $path
    $matched = if ($Regex) {
        $content -match $Marker
    }
    else {
        $content.Contains($Marker)
    }

    if ($matched) {
        Add-Finding -Status "PASS" -Category $Category -Check $Label `
            -Message "Required implementation marker is present." `
            -Evidence $RelativePath
        return $true
    }

    Add-Finding -Status "FAIL" -Category $Category -Check $Label `
        -Message "Required implementation marker is absent." `
        -Evidence ("{0} -> {1}" -f $RelativePath, $Marker) `
        -Recommendation $Recommendation
    return $false
}

function Get-JavaHome {
    $candidates = New-Object System.Collections.Generic.List[string]

    if ($env:JAVA_HOME) {
        $candidates.Add($env:JAVA_HOME)
    }

    $javaCommand = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($javaCommand) {
        $javaBin = Split-Path -Parent $javaCommand.Source
        $candidates.Add((Split-Path -Parent $javaBin))
    }

    foreach ($candidate in @(
        "$env:ProgramFiles\Android\Android Studio\jbr",
        "$env:ProgramFiles\Android Studio\jbr",
        "$env:LOCALAPPDATA\Programs\Android Studio\jbr",
        "$env:LOCALAPPDATA\Google\AndroidStudio\jbr",
        "$env:USERPROFILE\AppData\Local\Programs\Android Studio\jbr"
    )) {
        if ($candidate) {
            $candidates.Add($candidate)
        }
    }

    foreach ($root in @(
        "$env:LOCALAPPDATA\Google",
        "$env:LOCALAPPDATA\JetBrains\Toolbox\apps\AndroidStudio",
        "$env:USERPROFILE\AppData\Local\JetBrains\Toolbox\apps\AndroidStudio",
        "$env:ProgramFiles\JetBrains\Toolbox\apps\AndroidStudio"
    )) {
        if (-not $root -or -not (Test-Path -LiteralPath $root -PathType Container)) {
            continue
        }

        Get-ChildItem -LiteralPath $root -Directory -Recurse -ErrorAction SilentlyContinue |
            Where-Object {
                $_.Name -eq "jbr" -and
                (Test-Path -LiteralPath (Join-Path $_.FullName "bin\java.exe"))
            } |
            ForEach-Object {
                $candidates.Add($_.FullName)
            }
    }

    return $candidates |
        Where-Object {
            $_ -and (Test-Path -LiteralPath (Join-Path $_ "bin\java.exe") -PathType Leaf)
        } |
        Select-Object -First 1
}

function Get-AndroidSdk {
    $localProperties = Join-Path $ProjectRoot "android\local.properties"

    if (Test-Path -LiteralPath $localProperties -PathType Leaf) {
        foreach ($line in Get-Content -LiteralPath $localProperties) {
            if ($line -match "^\s*sdk\.dir\s*=(.+?)\s*$") {
                $value = $Matches[1]
                $value = $value -replace "\\:", ":"
                $value = $value -replace "\\\\", "\"
                if (Test-Path -LiteralPath $value -PathType Container) {
                    return $value
                }
            }
        }
    }

    foreach ($candidate in @(
        $env:ANDROID_SDK_ROOT,
        $env:ANDROID_HOME,
        "$env:LOCALAPPDATA\Android\Sdk"
    )) {
        if ($candidate -and (Test-Path -LiteralPath $candidate -PathType Container)) {
            return $candidate
        }
    }

    return $null
}

function Find-Adb {
    param([string]$AndroidSdk)

    $adbCommand = Get-Command adb.exe -ErrorAction SilentlyContinue
    if ($adbCommand) {
        return $adbCommand.Source
    }

    if ($AndroidSdk) {
        $sdkAdb = Join-Path $AndroidSdk "platform-tools\adb.exe"
        if (Test-Path -LiteralPath $sdkAdb -PathType Leaf) {
            return $sdkAdb
        }
    }

    return $null
}

function Invoke-GradleCheck {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [Parameter(Mandatory = $true)][string]$AndroidRoot,
        [Parameter(Mandatory = $true)][string]$GradleWrapper,
        [Parameter(Mandatory = $true)][string]$LogDirectory
    )

    $safeName = ($Name -replace "[^A-Za-z0-9._-]", "_")
    $logPath = Join-Path $LogDirectory ("gradle_{0}.log" -f $safeName)

    Write-Host ""
    Write-Host ("Running Gradle check: {0}" -f $Name) -ForegroundColor Cyan

    Push-Location $AndroidRoot
    try {
        $allArguments = @("--no-daemon", "--console=plain") + $Arguments
        $output = & $GradleWrapper @allArguments 2>&1 |
            ForEach-Object { $_.ToString() }
        $exitCode = $LASTEXITCODE
    }
    catch {
        $output = @($_.Exception.ToString())
        $exitCode = 9009
    }
    finally {
        Pop-Location
    }

    [System.IO.File]::WriteAllLines(
        $logPath,
        [string[]]$output,
        (New-Object System.Text.UTF8Encoding($false))
    )
    $script:GeneratedFiles.Add($logPath) | Out-Null

    $durationLine = $output |
        Where-Object { $_ -match "BUILD (SUCCESSFUL|FAILED)" } |
        Select-Object -Last 1

    $success = ($exitCode -eq 0)
    $script:GradleResults.Add([pscustomobject]@{
        Name     = $Name
        Success  = $success
        ExitCode = $exitCode
        Log      = $logPath
        Summary  = if ($durationLine) { $durationLine } else { "" }
    }) | Out-Null

    if ($success) {
        Add-Finding -Status "PASS" -Category "Gradle" -Check $Name `
            -Message "Gradle task completed successfully." `
            -Evidence (Get-RelativeProjectPath -Path $logPath)
    }
    else {
        $firstCompilerError = $output |
            Where-Object {
                $_ -match "^(e:|error:|FAILURE:|Execution failed|Caused by:)"
            } |
            Select-Object -First 1

        Add-Finding -Status "FAIL" -Category "Gradle" -Check $Name `
            -Message "Gradle task failed with exit code $exitCode." `
            -Evidence (($firstCompilerError, (Get-RelativeProjectPath -Path $logPath)) -join " | ") `
            -Recommendation "Open the attached Gradle log and correct the first compiler, test, lint, or packaging error."
    }
}

function Mask-Secret {
    param([Parameter(Mandatory = $true)][string]$Value)

    if ($Value.Length -le 10) {
        return "***"
    }

    return "{0}...{1}" -f $Value.Substring(0, 5), $Value.Substring($Value.Length - 4)
}

function HtmlEncode {
    param([AllowNull()][string]$Value)
    if ($null -eq $Value) {
        return ""
    }
    return [System.Net.WebUtility]::HtmlEncode($Value)
}

if (-not (Test-Path -LiteralPath $ProjectRoot -PathType Container)) {
    throw "AURA project folder was not found: $ProjectRoot"
}

$ProjectRoot = (Resolve-Path -LiteralPath $ProjectRoot).Path
$AndroidRoot = Join-Path $ProjectRoot "android"
$ReportParent = Join-Path $ProjectRoot "_inspection_reports"
$ReportRoot = Join-Path $ReportParent ("AURA_Inspection_{0}" -f $timestamp)
$LogRoot = Join-Path $ReportRoot "logs"

New-Item -ItemType Directory -Path $LogRoot -Force | Out-Null

$SummaryPath = Join-Path $ReportRoot "AURA-Inspection-Summary.txt"
$JsonPath = Join-Path $ReportRoot "AURA-Inspection-Report.json"
$HtmlPath = Join-Path $ReportRoot "AURA-Inspection-Report.html"
$HashesPath = Join-Path $ReportRoot "Project-File-Hashes.txt"
$SecretScanPath = Join-Path $ReportRoot "Secret-Scan.txt"
$DevicePath = Join-Path $ReportRoot "Device-Inspection.txt"

Write-Host ""
Write-Host "AURA FULL PROJECT INSPECTION" -ForegroundColor Cyan
Write-Host "Project: $ProjectRoot"
Write-Host "Reports: $ReportRoot"
Write-Host "Mode: read-only project inspection"
Write-Host ""

Write-Section "Environment and project structure"

if (Test-Path -LiteralPath $AndroidRoot -PathType Container) {
    Add-Finding -Status "PASS" -Category "Structure" -Check "Android project root" `
        -Message "The Android project folder exists." `
        -Evidence "android"
}
else {
    Add-Finding -Status "FAIL" -Category "Structure" -Check "Android project root" `
        -Message "The android folder is missing." `
        -Recommendation "Open the correct AURA project root."
}

$requiredStructure = @(
    "android\settings.gradle.kts",
    "android\build.gradle.kts",
    "android\gradlew.bat",
    "android\gradle\wrapper\gradle-wrapper.properties",
    "android\app\build.gradle.kts",
    "android\app\src\main\AndroidManifest.xml",
    "android\app\src\main\java\in\diganta\aurabutler\MainActivity.kt",
    "android\app\src\main\java\in\diganta\aurabutler\AuraApplication.kt",
    "android\app\libs\sherpa-onnx-v1.13.4.aar"
)

foreach ($relative in $requiredStructure) {
    $path = Join-Path $ProjectRoot $relative
    if (Test-Path -LiteralPath $path -PathType Leaf) {
        Add-Finding -Status "PASS" -Category "Structure" -Check $relative `
            -Message "Required project file is present."
    }
    else {
        Add-Finding -Status "FAIL" -Category "Structure" -Check $relative `
            -Message "Required project file is missing." `
            -Recommendation "Restore it from the latest updater backup or original project."
    }
}

$javaHome = Get-JavaHome
if ($javaHome) {
    $javaExe = Join-Path $javaHome "bin\java.exe"
    $javaVersionOutput = & $javaExe -version 2>&1 | ForEach-Object { $_.ToString() }
    $javaVersionText = ($javaVersionOutput -join " ")
    $major = $null

    if ($javaVersionText -match 'version\s+"(\d+)') {
        $major = [int]$Matches[1]
    }

    if ($major -ge 17) {
        Add-Finding -Status "PASS" -Category "Environment" -Check "Java runtime" `
            -Message "Java $major is available for Android Gradle Plugin." `
            -Evidence $javaHome
    }
    else {
        Add-Finding -Status "FAIL" -Category "Environment" -Check "Java runtime" `
            -Message "A Java runtime was found, but Java 17 or newer is required." `
            -Evidence $javaVersionText `
            -Recommendation "Select Android Studio Embedded JDK 17+ for Gradle."
    }
}
else {
    Add-Finding -Status "WARN" -Category "Environment" -Check "Java runtime" `
        -Message "PowerShell could not locate Java or Android Studio's embedded JBR." `
        -Recommendation "The script can still perform static checks. Build inside Android Studio."
}

$androidSdk = Get-AndroidSdk
if ($androidSdk) {
    Add-Finding -Status "PASS" -Category "Environment" -Check "Android SDK" `
        -Message "Android SDK was located." `
        -Evidence $androidSdk
}
else {
    Add-Finding -Status "WARN" -Category "Environment" -Check "Android SDK" `
        -Message "Android SDK was not found from local.properties or environment variables." `
        -Recommendation "Open the project in Android Studio and verify SDK Location."
}

$projectDrive = Get-PSDrive -Name ([System.IO.Path]::GetPathRoot($ProjectRoot).Substring(0, 1)) -ErrorAction SilentlyContinue
if ($projectDrive) {
    $freeGb = [math]::Round($projectDrive.Free / 1GB, 2)
    if ($freeGb -ge 5) {
        Add-Finding -Status "PASS" -Category "Environment" -Check "Free disk space" `
            -Message "$freeGb GB is free on the project drive."
    }
    else {
        Add-Finding -Status "WARN" -Category "Environment" -Check "Free disk space" `
            -Message "Only $freeGb GB is free on the project drive." `
            -Recommendation "Keep at least 5 GB free for Gradle, APKs, and native model packaging."
    }
}

Write-Section "Update-chain verification"

$buildGradleRelative = "android\app\build.gradle.kts"
$buildGradlePath = Join-Path $ProjectRoot $buildGradleRelative
$buildGradle = Read-Text -Path $buildGradlePath

if ($buildGradle -match 'versionName\s*=\s*"([^"]+)"') {
    $versionName = $Matches[1]
    if ($versionName -eq "0.6.1-gemini-hybrid") {
        Add-Finding -Status "PASS" -Category "Update verification" -Check "Application version" `
            -Message "The latest Gemini hybrid version marker is active." `
            -Evidence $versionName
    }
    else {
        Add-Finding -Status "FAIL" -Category "Update verification" -Check "Application version" `
            -Message "The expected version 0.6.1-gemini-hybrid is not active." `
            -Evidence $versionName `
            -Recommendation "Apply the v0.6 production update, validation continuation, and v0.6.1 Gemini updater in order."
    }
}
else {
    Add-Finding -Status "FAIL" -Category "Update verification" -Check "Application version" `
        -Message "versionName could not be parsed from app/build.gradle.kts."
}

if ($buildGradle -match 'versionCode\s*=\s*(\d+)') {
    $versionCode = [int]$Matches[1]
    if ($versionCode -ge 7) {
        Add-Finding -Status "PASS" -Category "Update verification" -Check "Version code" `
            -Message "Version code is $versionCode."
    }
    else {
        Add-Finding -Status "WARN" -Category "Update verification" -Check "Version code" `
            -Message "Version code $versionCode is lower than the expected v0.6.1 value." `
            -Recommendation "Use versionCode 7 or greater before installing over a newer build."
    }
}

$markerChecks = @(
    @{
        Path = "android\app\src\main\java\in\diganta\aurabutler\background\WakeWordService.kt"
        Marker = "private suspend fun listeningLoop()"
        Label = "Suspend wake listener fix"
        Recommendation = "Restore the suspend declaration to permit suspend command processing."
    },
    @{
        Path = "android\app\src\main\java\in\diganta\aurabutler\background\WakeWordService.kt"
        Marker = "VoiceAudioRecorder.createForWake(SAMPLE_RATE)"
        Label = "Dedicated wake microphone"
        Recommendation = "Reapply the v0.6 mobile-production updater."
    },
    @{
        Path = "android\app\src\main\java\in\diganta\aurabutler\background\WakeWordService.kt"
        Marker = "keywordsScore = 5.5f"
        Label = "Wake keyword score"
        Recommendation = "Reapply the v0.6 wake-word implementation."
    },
    @{
        Path = "android\app\src\main\java\in\diganta\aurabutler\background\WakeWordService.kt"
        Marker = "keywordsThreshold = 0.025f"
        Label = "Wake threshold"
        Recommendation = "Reapply the v0.6 wake-word implementation."
    },
    @{
        Path = "android\app\src\main\java\in\diganta\aurabutler\background\WakeWordService.kt"
        Marker = 'addAction(0, "Talk now", captureIntent)'
        Label = "Talk-now notification action"
        Recommendation = "Reapply the v0.6 wake service."
    },
    @{
        Path = "android\app\src\main\java\in\diganta\aurabutler\voice\PiperSpeaker.kt"
        Marker = "ensureEspeakDataDir()"
        Label = "Piper eSpeak crash repair"
        Recommendation = "Reapply the Piper/eSpeak crash patch."
    },
    @{
        Path = "android\app\src\main\java\in\diganta\aurabutler\voice\PiperSpeaker.kt"
        Marker = "ESPEAK_RUNTIME_DIRECTORY"
        Label = "Runtime eSpeak directory"
        Recommendation = "Piper must copy eSpeak data out of APK assets before native initialization."
    },
    @{
        Path = "android\app\src\main\java\in\diganta\aurabutler\voice\FastSystemSpeaker.kt"
        Marker = "class FastSystemSpeaker"
        Label = "Fast response voice"
        Recommendation = "Restore the fast Android TTS layer to avoid 35-second Piper reload latency."
    },
    @{
        Path = "android\app\src\main\java\in\diganta\aurabutler\domain\ProductionButlerEngine.kt"
        Marker = "class ProductionButlerEngine"
        Label = "Production command engine"
        Recommendation = "Reapply the v0.6 production-core updater."
    },
    @{
        Path = "android\app\src\main\java\in\diganta\aurabutler\runtime\ProductionIntentRouter.kt"
        Marker = "class ProductionIntentRouter"
        Label = "Trained intent router"
        Recommendation = "Reapply the v0.6 production-core updater."
    },
    @{
        Path = "android\app\src\main\java\in\diganta\aurabutler\memory\PrivateMemoryStore.kt"
        Marker = "class PrivateMemoryStore"
        Label = "Private searchable memory"
        Recommendation = "Reapply the v0.6 production-core updater."
    },
    @{
        Path = "android\app\src\main\java\in\diganta\aurabutler\runtime\SecretRedactor.kt"
        Marker = "class SecretRedactor"
        Label = "Secret redaction"
        Recommendation = "Reapply the v0.6 production-core updater."
    },
    @{
        Path = "android\app\src\main\java\in\diganta\aurabutler\maintenance\AuraDoctor.kt"
        Marker = "class AuraDoctor"
        Label = "AURA Doctor diagnostics"
        Recommendation = "Reapply the v0.6 production-core updater."
    },
    @{
        Path = "android\app\src\main\java\in\diganta\aurabutler\data\GeminiAiProvider.kt"
        Marker = "x-goog-api-key"
        Label = "Gemini authentication header"
        Recommendation = "Reapply the v0.6.1 Gemini hybrid updater."
    },
    @{
        Path = "android\app\src\main\java\in\diganta\aurabutler\data\GeminiAiProvider.kt"
        Marker = "generativelanguage.googleapis.com/v1beta/models/"
        Label = "Gemini endpoint"
        Recommendation = "Reapply the v0.6.1 Gemini hybrid updater."
    },
    @{
        Path = "android\app\src\main\java\in\diganta\aurabutler\data\HybridAiProvider.kt"
        Marker = "listOf(PROVIDER_GEMINI, PROVIDER_GROQ)"
        Label = "Gemini-to-Groq fallback order"
        Recommendation = "Reapply the v0.6.1 Gemini hybrid updater."
    },
    @{
        Path = "android\app\src\main\java\in\diganta\aurabutler\ButlerViewModel.kt"
        Marker = "generalAi = HybridAiProvider()"
        Label = "Foreground hybrid AI wiring"
        Recommendation = "Reapply the v0.6.1 Gemini hybrid updater."
    },
    @{
        Path = "android\app\src\main\java\in\diganta\aurabutler\background\BackgroundCommandProcessor.kt"
        Marker = "generalAi = HybridAiProvider()"
        Label = "Background hybrid AI wiring"
        Recommendation = "Reapply the v0.6.1 Gemini hybrid updater."
    },
    @{
        Path = "android\app\src\main\java\in\diganta\aurabutler\ui\AuraApp.kt"
        Marker = 'label = { Text("Gemini API key") }'
        Label = "Gemini settings field"
        Recommendation = "Reapply the v0.6.1 Gemini hybrid updater."
    }
)

foreach ($check in $markerChecks) {
    Test-TextMarker -RelativePath $check.Path -Marker $check.Marker `
        -Label $check.Label -Recommendation $check.Recommendation | Out-Null
}

$ExpectedHashes = @{
    "AURA-v0.6-MOBILE-PRODUCTION-PORT.txt" = "c301506342a08bd1b50d1b7c34248c6e5ee24b0440d3f0efb1e030ce7832d98f"
    "AURA-v0.6.1-GEMINI-HYBRID.txt" = "39310bd210a63a9ebc973cc71d4f88d420f0110e3548bc09bff73aa8a6d361bd"
    "android\app\build.gradle.kts" = "90225bda0aa725df4b3eabce1cb909ac67d293136560eadb0be453ab11b711df"
    "android\app\src\main\assets\nlu\intent_router.json" = "2e429888e87b17c6e0d7fd72cd1d62f17ee7a41c88f81ce67edd9cf87246c93a"
    "android\app\src\main\assets\voice\kws\aura_keywords.txt" = "96e83ba893abd14430d640d7fbc0cb7acdff745c2a7598946041858aa69e7739"
    "android\app\src\main\java\in\diganta\aurabutler\AuraApplication.kt" = "a9fe915acac4e06c5cea54210e38aa90c6e4dcebcec96af980d4f10f396b5171"
    "android\app\src\main\java\in\diganta\aurabutler\ButlerViewModel.kt" = "ec4d52f4fc759ef36a7de034df1f678a8d8995c98e5328561f6c332cefb1dcde"
    "android\app\src\main\java\in\diganta\aurabutler\background\BackgroundCommandProcessor.kt" = "4b7844ed2e1494d4d42f18346e3757096b4f90d237a61e81f14dc69efffdbce7"
    "android\app\src\main\java\in\diganta\aurabutler\background\WakeWordService.kt" = "3c3499d94adb294be73ea97d412baceb1d1e84fe3cf594dc42902f8b15deca0c"
    "android\app\src\main\java\in\diganta\aurabutler\data\AiProvider.kt" = "f7747adc89642ebf0c2b7fb33bb65b7af03a27131b7db70d523b6dab22bee03e"
    "android\app\src\main\java\in\diganta\aurabutler\data\GeminiAiProvider.kt" = "a1dfa6e7c2c2b6159366ad036a88c12ef8eb1334a7a87d2579578daba716ffd3"
    "android\app\src\main\java\in\diganta\aurabutler\data\GroqAiProvider.kt" = "db76393c145eeb82151df5cf3391e59fcb3c2a9fb4adc046469273bd17698b14"
    "android\app\src\main\java\in\diganta\aurabutler\data\HybridAiProvider.kt" = "435fcc4306b81ee1beff7c272a53cca3dcbaa7d4e3ee31ab9a6865a99660ec51"
    "android\app\src\main\java\in\diganta\aurabutler\data\SecureStore.kt" = "1c5626b9befc72b28f069e3001cc26e33469b4677358dc7e8164179e75111094"
    "android\app\src\main\java\in\diganta\aurabutler\domain\LocalButlerEngine.kt" = "ec9d68acce3539d32733e98d16351c38f3e014e3552db1c132adebfa4485a6ef"
    "android\app\src\main\java\in\diganta\aurabutler\domain\ProductionButlerEngine.kt" = "934896f463e221d7c694d7d74d49a563d748f2088f5601f979b9203e00716cbe"
    "android\app\src\main\java\in\diganta\aurabutler\maintenance\AuraDoctor.kt" = "fac0c4e467628491fe418c7cf646ddb61c190db4fbeeb5717b8ab20292d99789"
    "android\app\src\main\java\in\diganta\aurabutler\memory\PrivateMemoryStore.kt" = "006aaef78d3eab122db6184d3f86f0348f61fa2e2520e1bdd448328377674640"
    "android\app\src\main\java\in\diganta\aurabutler\model\Models.kt" = "aa035f8b61fd0e522cf1dae48474232a267b5beb987b226d5b58a9647e0d3a38"
    "android\app\src\main\java\in\diganta\aurabutler\runtime\ActionAuditLog.kt" = "09f6151002c925eba0e3ddd136ddafc25613007c00ccbf3b349a7a97c3934a20"
    "android\app\src\main\java\in\diganta\aurabutler\runtime\AuraRuntimeState.kt" = "8be9aefe5b72549bba5e1fffee78fa3f7f5eea7a9b15c2b3d3ec478e364574c5"
    "android\app\src\main\java\in\diganta\aurabutler\runtime\CapabilityPolicy.kt" = "b3a613cbb8c45e341a3b22a09aa52d56e1c1a0eee3fcbb3d3fd2cf8e32d8301c"
    "android\app\src\main\java\in\diganta\aurabutler\runtime\ProductionIntentRouter.kt" = "46cfa6f5de363e4d3539c2cdf3d3a1910697093284ba41e4b5b0a78eeaaa727e"
    "android\app\src\main\java\in\diganta\aurabutler\runtime\SecretRedactor.kt" = "f50ef6b07ec7aad3631838bd5f99c2c5baf198896dccc00b6273150af2c28424"
    "android\app\src\main\java\in\diganta\aurabutler\ui\AuraApp.kt" = "18febcde54898e3aceceebaa809bb6e3a511f69545326c8e1725e7906dc5377b"
    "android\app\src\main\java\in\diganta\aurabutler\voice\VoiceAudioRecorder.kt" = "6db2ab158f04a6a1e72ee48c465052f9616cc20eea88a2c4076795d4a7f4f2d2"
    "android\app\src\main\java\in\diganta\aurabutler\voice\VoiceDiagnostics.kt" = "8f244ff418b5c5c5f2117649cfbff6d12c35772ef5ed52105840dcfb77694cc7"
}

$exactMatches = 0
$hashMismatches = 0
$hashMissing = 0

foreach ($entry in $ExpectedHashes.GetEnumerator()) {
    $path = Join-Path $ProjectRoot $entry.Key

    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        $hashMissing++
        Add-Finding -Status "FAIL" -Category "Release integrity" -Check $entry.Key `
            -Message "A file from the released updater payload is missing." `
            -Recommendation "Restore or reapply the corresponding update."
        continue
    }

    $actual = Get-Sha256 -Path $path
    if ($actual -eq $entry.Value) {
        $exactMatches++
    }
    else {
        $hashMismatches++
        $status = if ($StrictHashes) { "FAIL" } else { "WARN" }
        Add-Finding -Status $status -Category "Release integrity" -Check $entry.Key `
            -Message "This file differs from the exact released updater payload." `
            -Evidence ("Expected {0}; actual {1}" -f $entry.Value, $actual) `
            -Recommendation "A difference may be a later manual fix. Review it; use -StrictHashes only when exact release identity is required."
    }
}

if ($exactMatches -gt 0) {
    Add-Finding -Status "PASS" -Category "Release integrity" -Check "Exact payload matches" `
        -Message "$exactMatches updater-managed files exactly match the released v0.6/v0.6.1 payload."
}

Write-Section "Manifest, permissions, and Android security"

$manifestRelative = "android\app\src\main\AndroidManifest.xml"
$manifestPath = Join-Path $ProjectRoot $manifestRelative
$manifest = Read-Text -Path $manifestPath

$manifestMarkers = @(
    @{ Marker = 'android:name=".AuraApplication"'; Label = "Application bootstrap" },
    @{ Marker = 'android.permission.INTERNET'; Label = "Internet permission" },
    @{ Marker = 'android.permission.ACCESS_NETWORK_STATE'; Label = "Network-state permission" },
    @{ Marker = 'android.permission.RECORD_AUDIO'; Label = "Microphone permission" },
    @{ Marker = 'android.permission.POST_NOTIFICATIONS'; Label = "Notification permission" },
    @{ Marker = 'android.permission.FOREGROUND_SERVICE'; Label = "Foreground-service permission" },
    @{ Marker = 'android.permission.FOREGROUND_SERVICE_MICROPHONE'; Label = "Microphone FGS permission" },
    @{ Marker = 'android.permission.WAKE_LOCK'; Label = "Wake-lock permission" },
    @{ Marker = 'android:foregroundServiceType="microphone"'; Label = "Microphone service type" },
    @{ Marker = 'android:stopWithTask="false"'; Label = "Background service survives task removal" },
    @{ Marker = 'android:exported="false"'; Label = "Wake service is not externally exported" },
    @{ Marker = 'android:allowBackup="false"'; Label = "Backup disabled for private data" },
    @{ Marker = 'android:usesCleartextTraffic="${usesCleartextTraffic}"'; Label = "Cleartext controlled by build placeholder" }
)

foreach ($item in $manifestMarkers) {
    if ($manifest.Contains($item.Marker)) {
        Add-Finding -Status "PASS" -Category "Android security" -Check $item.Label `
            -Message "Required manifest declaration is present."
    }
    else {
        Add-Finding -Status "FAIL" -Category "Android security" -Check $item.Label `
            -Message "Required manifest declaration is absent." `
            -Evidence $item.Marker
    }
}

if ($buildGradle.Contains('manifestPlaceholders["usesCleartextTraffic"] = "false"')) {
    Add-Finding -Status "PASS" -Category "Android security" -Check "Cleartext disabled" `
        -Message "HTTP cleartext traffic is disabled by the app build."
}
else {
    Add-Finding -Status "FAIL" -Category "Android security" -Check "Cleartext disabled" `
        -Message "The build does not force usesCleartextTraffic=false." `
        -Recommendation "Keep Gemini, Groq, weather, news, and Wikipedia traffic on HTTPS."
}

if ($manifest -match "BOOT_COMPLETED") {
    Add-Finding -Status "PASS" -Category "Background operation" -Check "Restart after reboot" `
        -Message "A boot-completed receiver is declared."
}
else {
    Add-Finding -Status "WARN" -Category "Background operation" -Check "Restart after reboot" `
        -Message "No BOOT_COMPLETED receiver is declared." `
        -Recommendation "After a phone restart, open AURA and re-enable wake listening. Add a carefully gated boot receiver only if automatic restart is required."
}

$exportedComponents = [regex]::Matches(
    $manifest,
    '<(service|receiver|provider)[\s\S]*?android:exported="true"[\s\S]*?>',
    [System.Text.RegularExpressions.RegexOptions]::IgnoreCase
)

if ($exportedComponents.Count -eq 0) {
    Add-Finding -Status "PASS" -Category "Android security" -Check "Exported background components" `
        -Message "No service, receiver, or provider is explicitly exported."
}
else {
    Add-Finding -Status "WARN" -Category "Android security" -Check "Exported background components" `
        -Message "$($exportedComponents.Count) exported background component declaration(s) were found." `
        -Recommendation "Verify every exported component has a strict permission or validated intent contract."
}

Write-Section "Voice model and wake-word assets"

$assetChecks = @(
    @{ Path = "android\app\src\main\assets\voice\kws\encoder.int8.onnx"; Minimum = 1000000; Label = "Wake encoder" },
    @{ Path = "android\app\src\main\assets\voice\kws\decoder.int8.onnx"; Minimum = 100000; Label = "Wake decoder" },
    @{ Path = "android\app\src\main\assets\voice\kws\joiner.int8.onnx"; Minimum = 100000; Label = "Wake joiner" },
    @{ Path = "android\app\src\main\assets\voice\kws\tokens.txt"; Minimum = 1000; Label = "Wake tokens" },
    @{ Path = "android\app\src\main\assets\voice\kws\aura_keywords.txt"; Minimum = 50; Label = "Wake keywords" },
    @{ Path = "android\app\src\main\assets\voice\whisper\encoder.int8.onnx"; Minimum = 5000000; Label = "Whisper encoder" },
    @{ Path = "android\app\src\main\assets\voice\whisper\decoder.int8.onnx"; Minimum = 50000000; Label = "Whisper decoder" },
    @{ Path = "android\app\src\main\assets\voice\whisper\tokens.txt"; Minimum = 100000; Label = "Whisper tokens" },
    @{ Path = "android\app\src\main\assets\voice\piper\model.onnx"; Minimum = 50000000; Label = "Piper male model" },
    @{ Path = "android\app\src\main\assets\voice\piper\tokens.txt"; Minimum = 500; Label = "Piper tokens" },
    @{ Path = "android\app\src\main\assets\voice\piper\espeak-ng-data\phondata"; Minimum = 100000; Label = "eSpeak phondata" },
    @{ Path = "android\app\src\main\assets\voice\piper\espeak-ng-data\phontab"; Minimum = 10000; Label = "eSpeak phontab" },
    @{ Path = "android\app\src\main\assets\voice\piper\espeak-ng-data\phonindex"; Minimum = 10000; Label = "eSpeak phonindex" },
    @{ Path = "android\app\src\main\assets\voice\piper\espeak-ng-data\intonations"; Minimum = 1000; Label = "eSpeak intonations" }
)

$modelHashLines = New-Object System.Collections.Generic.List[string]

foreach ($asset in $assetChecks) {
    $path = Join-Path $ProjectRoot $asset.Path

    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        Add-Finding -Status "FAIL" -Category "Voice assets" -Check $asset.Label `
            -Message "Required model or data file is missing." `
            -Evidence $asset.Path
        continue
    }

    $file = Get-Item -LiteralPath $path
    if ($file.Length -lt $asset.Minimum) {
        Add-Finding -Status "FAIL" -Category "Voice assets" -Check $asset.Label `
            -Message "The file is unexpectedly small and may be incomplete." `
            -Evidence ("{0} bytes" -f $file.Length)
    }
    else {
        Add-Finding -Status "PASS" -Category "Voice assets" -Check $asset.Label `
            -Message ("Present ({0:N2} MB)." -f ($file.Length / 1MB))
    }

    $modelHashLines.Add(
        "{0}`t{1}`t{2}" -f $asset.Path, $file.Length, (Get-Sha256 -Path $path)
    ) | Out-Null
}

$tokensPath = Join-Path $ProjectRoot "android\app\src\main\assets\voice\kws\tokens.txt"
$keywordsPath = Join-Path $ProjectRoot "android\app\src\main\assets\voice\kws\aura_keywords.txt"

if (
    (Test-Path -LiteralPath $tokensPath -PathType Leaf) -and
    (Test-Path -LiteralPath $keywordsPath -PathType Leaf)
) {
    $tokenSet = New-Object "System.Collections.Generic.HashSet[string]" ([System.StringComparer]::Ordinal)

    foreach ($line in Get-Content -LiteralPath $tokensPath) {
        if ($line -match "^(.*?)\s+\d+\s*$") {
            [void]$tokenSet.Add($Matches[1])
        }
    }

    $missingTokens = New-Object System.Collections.Generic.List[string]
    $keywordCount = 0

    foreach ($line in Get-Content -LiteralPath $keywordsPath) {
        $trimmed = $line.Trim()
        if (-not $trimmed -or $trimmed.StartsWith("#")) {
            continue
        }

        $keywordCount++
        $encoded = ($trimmed -split ":", 2)[0].Trim()

        foreach ($token in ($encoded -split "\s+")) {
            if ($token -and -not $tokenSet.Contains($token)) {
                $missingTokens.Add($token)
            }
        }
    }

    if ($missingTokens.Count -eq 0) {
        Add-Finding -Status "PASS" -Category "Wake word" -Check "Keyword token compatibility" `
            -Message "All $keywordCount wake phrases use tokens supported by the bundled KWS model."
    }
    else {
        $uniqueMissing = $missingTokens | Sort-Object -Unique
        Add-Finding -Status "FAIL" -Category "Wake word" -Check "Keyword token compatibility" `
            -Message "The wake keyword file contains unsupported model tokens." `
            -Evidence ($uniqueMissing -join ", ") `
            -Recommendation "Use only tokens present in voice/kws/tokens.txt."
    }

    if ($keywordCount -ge 8) {
        Add-Finding -Status "PASS" -Category "Wake word" -Check "Pronunciation coverage" `
            -Message "$keywordCount wake-word pronunciations are installed."
    }
    else {
        Add-Finding -Status "WARN" -Category "Wake word" -Check "Pronunciation coverage" `
            -Message "Only $keywordCount wake-word pronunciations are installed."
    }
}

[System.IO.File]::WriteAllLines(
    $HashesPath,
    [string[]]$modelHashLines,
    (New-Object System.Text.UTF8Encoding($false))
)
$script:GeneratedFiles.Add($HashesPath) | Out-Null

$espeakDirectory = Join-Path $ProjectRoot "android\app\src\main\assets\voice\piper\espeak-ng-data"
if (Test-Path -LiteralPath $espeakDirectory -PathType Container) {
    $espeakFileCount = @(
        Get-ChildItem -LiteralPath $espeakDirectory -File -Recurse
    ).Count

    if ($espeakFileCount -ge 50) {
        Add-Finding -Status "PASS" -Category "Voice assets" -Check "eSpeak data tree" `
            -Message "$espeakFileCount eSpeak data files are bundled."
    }
    else {
        Add-Finding -Status "WARN" -Category "Voice assets" -Check "eSpeak data tree" `
            -Message "Only $espeakFileCount eSpeak data files are bundled."
    }
}

Write-Section "NLU, memory, policy, and AI provider checks"

$intentPath = Join-Path $ProjectRoot "android\app\src\main\assets\nlu\intent_router.json"
if (Test-Path -LiteralPath $intentPath -PathType Leaf) {
    try {
        $intentModel = Get-Content -LiteralPath $intentPath -Raw | ConvertFrom-Json
        $intentCount = @($intentModel.intent_docs.PSObject.Properties).Count
        $totalDocs = [int]$intentModel.total_docs

        if ($totalDocs -ge 250 -and $intentCount -ge 30) {
            Add-Finding -Status "PASS" -Category "NLU" -Check "Intent model completeness" `
                -Message "$totalDocs examples across $intentCount intent classes were parsed successfully."
        }
        else {
            Add-Finding -Status "WARN" -Category "NLU" -Check "Intent model completeness" `
                -Message "The model contains $totalDocs examples across $intentCount intents." `
                -Recommendation "The v0.6 production model is expected to contain about 287 examples and 33 intents."
        }

        if ($intentModel.class_counts) {
            $countSum = 0
            foreach ($property in $intentModel.class_counts.PSObject.Properties) {
                $countSum += [int]$property.Value
            }

            if ($countSum -eq $totalDocs) {
                Add-Finding -Status "PASS" -Category "NLU" -Check "Intent class totals" `
                    -Message "Class counts sum exactly to total_docs."
            }
            else {
                Add-Finding -Status "FAIL" -Category "NLU" -Check "Intent class totals" `
                    -Message "class_counts sum to $countSum but total_docs is $totalDocs."
            }
        }
    }
    catch {
        Add-Finding -Status "FAIL" -Category "NLU" -Check "Intent model JSON" `
            -Message "intent_router.json could not be parsed." `
            -Evidence $_.Exception.Message
    }
}
else {
    Add-Finding -Status "FAIL" -Category "NLU" -Check "Intent model JSON" `
        -Message "intent_router.json is missing."
}

Test-TextMarker `
    -RelativePath "android\app\src\main\java\in\diganta\aurabutler\data\SecureStore.kt" `
    -Marker "EncryptedSharedPreferences.create" `
    -Label "Encrypted settings storage" `
    -Category "Privacy" `
    -Recommendation "API keys and user settings must not be stored in plain SharedPreferences." | Out-Null

Test-TextMarker `
    -RelativePath "android\app\src\main\java\in\diganta\aurabutler\data\SecureStore.kt" `
    -Marker "KEY_GEMINI_API_KEY" `
    -Label "Gemini key persistence" `
    -Category "AI providers" | Out-Null

Test-TextMarker `
    -RelativePath "android\app\src\main\java\in\diganta\aurabutler\data\SecureStore.kt" `
    -Marker "KEY_GROQ_API_KEY" `
    -Label "Groq key persistence" `
    -Category "AI providers" | Out-Null

Test-TextMarker `
    -RelativePath "android\app\src\main\java\in\diganta\aurabutler\data\GeminiAiProvider.kt" `
    -Marker "gemini-3.1-flash-lite" `
    -Label "Gemini fast model" `
    -Category "AI providers" | Out-Null

Test-TextMarker `
    -RelativePath "android\app\src\main\java\in\diganta\aurabutler\data\GeminiAiProvider.kt" `
    -Marker "gemini-2.5-pro" `
    -Label "Gemini Pro model" `
    -Category "AI providers" | Out-Null

Test-TextMarker `
    -RelativePath "android\app\src\main\java\in\diganta\aurabutler\data\GroqAiProvider.kt" `
    -Marker "api.groq.com/openai/v1/chat/completions" `
    -Label "Groq endpoint" `
    -Category "AI providers" | Out-Null

Test-TextMarker `
    -RelativePath "android\app\src\main\java\in\diganta\aurabutler\domain\LocalButlerEngine.kt" `
    -Marker "generalAi?.answer(request, context)" `
    -Label "Local-first optional AI fallback" `
    -Category "AI providers" `
    -Recommendation "Local memory and command routing should run before optional cloud AI." | Out-Null

$allKotlinPaths = @(
    Get-ChildItem -LiteralPath (Join-Path $AndroidRoot "app\src\main\java") `
        -Filter "*.kt" -File -Recurse -ErrorAction SilentlyContinue
)

Write-Section "Kotlin source quality and static security scan"

$duplicateImports = New-Object System.Collections.Generic.List[string]
$packageMismatches = New-Object System.Collections.Generic.List[string]
$todoLocations = New-Object System.Collections.Generic.List[string]
$riskyLocations = New-Object System.Collections.Generic.List[string]
$secretHits = New-Object System.Collections.Generic.List[string]
$httpHits = New-Object System.Collections.Generic.List[string]

$secretPatterns = @(
    @{ Name = "Google API key"; Regex = 'AIza[0-9A-Za-z_-]{25,}' },
    @{ Name = "Groq API key"; Regex = 'gsk_[0-9A-Za-z]{20,}' },
    @{ Name = "OpenAI API key"; Regex = 'sk-(?:proj-)?[0-9A-Za-z_-]{20,}' },
    @{ Name = "GitHub token"; Regex = 'gh[pousr]_[0-9A-Za-z]{20,}' },
    @{ Name = "AWS access key"; Regex = 'AKIA[0-9A-Z]{16}' }
)

foreach ($file in $allKotlinPaths) {
    $relative = Get-RelativeProjectPath -Path $file.FullName
    $lines = @(Get-Content -LiteralPath $file.FullName)
    $imports = @(
        $lines |
            ForEach-Object { $_.Trim() } |
            Where-Object { $_.StartsWith("import ") }
    )

    $duplicates = $imports |
        Group-Object |
        Where-Object { $_.Count -gt 1 }

    foreach ($duplicate in $duplicates) {
        $duplicateImports.Add("$relative -> $($duplicate.Name)")
    }

    $packageLine = $lines |
        Where-Object { $_.Trim().StartsWith("package ") } |
        Select-Object -First 1

    if ($packageLine -and $file.FullName -match "[\\/]java[\\/]") {
        $packageName = $packageLine.Trim().Substring(8).Replace("`", "")
        $afterJava = ($file.FullName -split "[\\/]java[\\/]", 2)[1]
        $actualDirectory = Split-Path -Parent $afterJava
        $expectedDirectory = $packageName.Replace(".", "\")

        if ($actualDirectory -ne $expectedDirectory) {
            $packageMismatches.Add(
                "$relative -> package $packageName; directory $actualDirectory"
            )
        }
    }

    for ($index = 0; $index -lt $lines.Count; $index++) {
        $lineNumber = $index + 1
        $line = $lines[$index]

        if ($line -match '\b(TODO|FIXME|HACK|XXX)\b') {
            $todoLocations.Add("$relative`:$lineNumber")
        }

        if ($line -match '\b(GlobalScope|runBlocking|allowMainThreadQueries|setJavaScriptEnabled\(true\)|MODE_WORLD_READABLE|MODE_WORLD_WRITEABLE)\b') {
            $riskyLocations.Add("$relative`:$lineNumber -> $($Matches[1])")
        }

        if ($line -match 'http://') {
            $httpHits.Add("$relative`:$lineNumber")
        }

        foreach ($pattern in $secretPatterns) {
            $matches = [regex]::Matches($line, $pattern.Regex)
            foreach ($match in $matches) {
                $secretHits.Add(
                    "$relative`:$lineNumber -> $($pattern.Name): $(Mask-Secret -Value $match.Value)"
                )
            }
        }
    }
}

if ($duplicateImports.Count -eq 0) {
    Add-Finding -Status "PASS" -Category "Kotlin" -Check "Duplicate imports" `
        -Message "No duplicate Kotlin import lines were found."
}
else {
    Add-Finding -Status "FAIL" -Category "Kotlin" -Check "Duplicate imports" `
        -Message "$($duplicateImports.Count) duplicate import declaration(s) were found." `
        -Evidence (($duplicateImports | Select-Object -First 10) -join "; ") `
        -Recommendation "Remove repeated imports before compiling."
}

if ($packageMismatches.Count -eq 0) {
    Add-Finding -Status "PASS" -Category "Kotlin" -Check "Package/path alignment" `
        -Message "Kotlin package declarations match their source directories."
}
else {
    Add-Finding -Status "WARN" -Category "Kotlin" -Check "Package/path alignment" `
        -Message "$($packageMismatches.Count) package/path mismatch(es) were found." `
        -Evidence (($packageMismatches | Select-Object -First 10) -join "; ")
}

if ($todoLocations.Count -eq 0) {
    Add-Finding -Status "PASS" -Category "Code quality" -Check "Unfinished markers" `
        -Message "No TODO, FIXME, HACK, or XXX markers were found in Kotlin source."
}
else {
    Add-Finding -Status "WARN" -Category "Code quality" -Check "Unfinished markers" `
        -Message "$($todoLocations.Count) unfinished-work marker(s) were found." `
        -Evidence (($todoLocations | Select-Object -First 15) -join "; ")
}

if ($riskyLocations.Count -eq 0) {
    Add-Finding -Status "PASS" -Category "Code quality" -Check "Known risky APIs" `
        -Message "No selected high-risk Android/Kotlin API patterns were found."
}
else {
    Add-Finding -Status "WARN" -Category "Code quality" -Check "Known risky APIs" `
        -Message "$($riskyLocations.Count) selected risky API occurrence(s) were found." `
        -Evidence (($riskyLocations | Select-Object -First 15) -join "; ") `
        -Recommendation "Review each occurrence for lifecycle, blocking, storage, or WebView risk."
}

if ($httpHits.Count -eq 0) {
    Add-Finding -Status "PASS" -Category "Network security" -Check "Plain HTTP source URLs" `
        -Message "No http:// URL was found in Kotlin source."
}
else {
    Add-Finding -Status "FAIL" -Category "Network security" -Check "Plain HTTP source URLs" `
        -Message "$($httpHits.Count) plain HTTP occurrence(s) were found." `
        -Evidence (($httpHits | Select-Object -First 15) -join "; ") `
        -Recommendation "Use HTTPS or an explicit, tightly scoped network security exception."
}

if ($secretHits.Count -eq 0) {
    Add-Finding -Status "PASS" -Category "Secrets" -Check "Hard-coded credentials" `
        -Message "No recognized literal API key or access-token pattern was found in Kotlin source."
}
else {
    Add-Finding -Status "FAIL" -Category "Secrets" -Check "Hard-coded credentials" `
        -Message "$($secretHits.Count) possible hard-coded credential(s) were found." `
        -Evidence (($secretHits | Select-Object -First 20) -join "; ") `
        -Recommendation "Revoke exposed keys, remove them from source/history, and enter keys only through AURA's encrypted settings."
}

[System.IO.File]::WriteAllLines(
    $SecretScanPath,
    [string[]](
        @(
            "AURA static secret scan",
            "Generated: $(Get-Date -Format o)",
            ""
        ) +
        $(if ($secretHits.Count -eq 0) {
            @("No recognized hard-coded key patterns were found.")
        }
        else {
            @($secretHits)
        })
    ),
    (New-Object System.Text.UTF8Encoding($false))
)
$script:GeneratedFiles.Add($SecretScanPath) | Out-Null

$encryptedStoreText = Read-Text -Path (
    Join-Path $ProjectRoot "android\app\src\main\java\in\diganta\aurabutler\data\SecureStore.kt"
)

if (
    $encryptedStoreText.Contains("EncryptedSharedPreferences") -and
    $encryptedStoreText.Contains("MasterKey")
) {
    Add-Finding -Status "INFO" -Category "Maintenance" -Check "Deprecated AndroidX crypto API" `
        -Message "EncryptedSharedPreferences/MasterKey are still used. They compile, but Android Studio may report deprecation warnings." `
        -Recommendation "Plan a controlled future migration; do not remove encrypted storage without a data migration."
}

Write-Section "Tests and project coverage"

$unitTestFiles = @(
    Get-ChildItem -LiteralPath (Join-Path $AndroidRoot "app\src\test") `
        -Filter "*.kt" -File -Recurse -ErrorAction SilentlyContinue
)

$androidTestFiles = @(
    Get-ChildItem -LiteralPath (Join-Path $AndroidRoot "app\src\androidTest") `
        -Filter "*.kt" -File -Recurse -ErrorAction SilentlyContinue
)

if ($unitTestFiles.Count -gt 0) {
    Add-Finding -Status "PASS" -Category "Tests" -Check "Unit test sources" `
        -Message "$($unitTestFiles.Count) unit test source file(s) are present." `
        -Evidence (($unitTestFiles | ForEach-Object { Get-RelativeProjectPath -Path $_.FullName }) -join ", ")
}
else {
    Add-Finding -Status "WARN" -Category "Tests" -Check "Unit test sources" `
        -Message "No unit test source files were found."
}

if ($androidTestFiles.Count -gt 0) {
    Add-Finding -Status "PASS" -Category "Tests" -Check "Instrumentation test sources" `
        -Message "$($androidTestFiles.Count) Android instrumentation test file(s) are present."
}
else {
    Add-Finding -Status "WARN" -Category "Tests" -Check "Instrumentation test sources" `
        -Message "No device instrumentation test source files were found." `
        -Recommendation "Production wake, microphone, background-service, and encrypted-storage flows benefit from real-device tests."
}

$productionCoreTests = @(
    $unitTestFiles |
        Where-Object {
            $_.Name -match "(Production|Intent|Memory|Secret|Gemini|Groq|Hybrid|Wake|Doctor)"
        }
)

if ($productionCoreTests.Count -gt 0) {
    Add-Finding -Status "PASS" -Category "Tests" -Check "Production-core test coverage" `
        -Message "$($productionCoreTests.Count) production-feature-focused test file(s) were detected."
}
else {
    Add-Finding -Status "WARN" -Category "Tests" -Check "Production-core test coverage" `
        -Message "No clearly named tests cover the new production intent, memory, redaction, provider, or wake modules." `
        -Recommendation "Add unit tests for routing, secret redaction, Gemini/Groq fallback, memory ranking, and wake lifecycle."
}

Write-Section "Gradle build, unit tests, lint, and APK packaging"

if ($SkipGradle) {
    Add-Finding -Status "INFO" -Category "Gradle" -Check "Gradle execution" `
        -Message "Gradle checks were skipped by command-line option."
}
elseif (-not $javaHome) {
    Add-Finding -Status "WARN" -Category "Gradle" -Check "Gradle execution" `
        -Message "Gradle checks were not run because Java was not located." `
        -Recommendation "Run this script from a terminal launched by Android Studio or build inside Android Studio."
}
else {
    $env:JAVA_HOME = $javaHome
    $env:Path = "$(Join-Path $javaHome 'bin');$env:Path"

    if ($androidSdk) {
        $env:ANDROID_SDK_ROOT = $androidSdk
        $env:ANDROID_HOME = $androidSdk
    }

    $gradleWrapper = Join-Path $AndroidRoot "gradlew.bat"

    if (-not (Test-Path -LiteralPath $gradleWrapper -PathType Leaf)) {
        Add-Finding -Status "FAIL" -Category "Gradle" -Check "Gradle wrapper" `
            -Message "gradlew.bat is missing."
    }
    else {
        Invoke-GradleCheck `
            -Name "Clean" `
            -Arguments @(":app:clean") `
            -AndroidRoot $AndroidRoot `
            -GradleWrapper $gradleWrapper `
            -LogDirectory $LogRoot

        Invoke-GradleCheck `
            -Name "Kotlin compile" `
            -Arguments @(":app:compileDebugKotlin") `
            -AndroidRoot $AndroidRoot `
            -GradleWrapper $gradleWrapper `
            -LogDirectory $LogRoot

        Invoke-GradleCheck `
            -Name "Unit tests" `
            -Arguments @(":app:testDebugUnitTest") `
            -AndroidRoot $AndroidRoot `
            -GradleWrapper $gradleWrapper `
            -LogDirectory $LogRoot

        Invoke-GradleCheck `
            -Name "Android lint" `
            -Arguments @(":app:lintDebug") `
            -AndroidRoot $AndroidRoot `
            -GradleWrapper $gradleWrapper `
            -LogDirectory $LogRoot

        Invoke-GradleCheck `
            -Name "Debug APK" `
            -Arguments @(":app:assembleDebug") `
            -AndroidRoot $AndroidRoot `
            -GradleWrapper $gradleWrapper `
            -LogDirectory $LogRoot

        Invoke-GradleCheck `
            -Name "Android-test APK compile" `
            -Arguments @(":app:assembleDebugAndroidTest") `
            -AndroidRoot $AndroidRoot `
            -GradleWrapper $gradleWrapper `
            -LogDirectory $LogRoot
    }
}

$unitTestReportDirectory = Join-Path $AndroidRoot "app\build\test-results\testDebugUnitTest"
if (Test-Path -LiteralPath $unitTestReportDirectory -PathType Container) {
    $testSuites = @(
        Get-ChildItem -LiteralPath $unitTestReportDirectory -Filter "*.xml" -File
    )

    $tests = 0
    $failures = 0
    $errors = 0
    $skipped = 0

    foreach ($suiteFile in $testSuites) {
        try {
            [xml]$suiteXml = Get-Content -LiteralPath $suiteFile.FullName -Raw
            $suiteNodes = @($suiteXml.testsuite)
            foreach ($suite in $suiteNodes) {
                $tests += [int]$suite.tests
                $failures += [int]$suite.failures
                $errors += [int]$suite.errors
                $skipped += [int]$suite.skipped
            }
        }
        catch {
            Add-Finding -Status "WARN" -Category "Tests" -Check "JUnit XML parsing" `
                -Message "Could not parse $($suiteFile.Name)." `
                -Evidence $_.Exception.Message
        }
    }

    if ($tests -gt 0 -and ($failures + $errors) -eq 0) {
        Add-Finding -Status "PASS" -Category "Tests" -Check "JUnit result summary" `
            -Message "$tests tests passed; $skipped skipped."
    }
    elseif (($failures + $errors) -gt 0) {
        Add-Finding -Status "FAIL" -Category "Tests" -Check "JUnit result summary" `
            -Message "$tests tests ran with $failures failure(s) and $errors error(s)."
    }
}

$lintXmlPath = Join-Path $AndroidRoot "app\build\reports\lint-results-debug.xml"
if (Test-Path -LiteralPath $lintXmlPath -PathType Leaf) {
    try {
        [xml]$lintXml = Get-Content -LiteralPath $lintXmlPath -Raw
        $issues = @($lintXml.issues.issue)
        $lintErrors = @($issues | Where-Object { $_.severity -eq "Error" }).Count
        $lintWarnings = @($issues | Where-Object { $_.severity -eq "Warning" }).Count

        if ($lintErrors -gt 0) {
            Add-Finding -Status "FAIL" -Category "Lint" -Check "Android lint issues" `
                -Message "$lintErrors lint error(s) and $lintWarnings warning(s) were reported." `
                -Evidence (Get-RelativeProjectPath -Path $lintXmlPath)
        }
        elseif ($lintWarnings -gt 0) {
            Add-Finding -Status "WARN" -Category "Lint" -Check "Android lint issues" `
                -Message "No lint errors; $lintWarnings warning(s) were reported." `
                -Evidence (Get-RelativeProjectPath -Path $lintXmlPath)
        }
        else {
            Add-Finding -Status "PASS" -Category "Lint" -Check "Android lint issues" `
                -Message "No lint errors or warnings were reported."
        }
    }
    catch {
        Add-Finding -Status "WARN" -Category "Lint" -Check "Lint report parsing" `
            -Message "The lint XML report could not be parsed." `
            -Evidence $_.Exception.Message
    }
}

$apkPath = Join-Path $AndroidRoot "app\build\outputs\apk\debug\app-debug.apk"
if (Test-Path -LiteralPath $apkPath -PathType Leaf) {
    $apk = Get-Item -LiteralPath $apkPath
    $apkSizeMb = [math]::Round($apk.Length / 1MB, 2)

    Add-Finding -Status "PASS" -Category "APK" -Check "Debug APK exists" `
        -Message "A debug APK was produced ($apkSizeMb MB)." `
        -Evidence (Get-RelativeProjectPath -Path $apkPath)

    try {
        Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction SilentlyContinue
        $archive = [System.IO.Compression.ZipFile]::OpenRead($apkPath)
        try {
            $entryNames = @($archive.Entries | ForEach-Object { $_.FullName })

            foreach ($requiredEntry in @(
                "classes.dex",
                "AndroidManifest.xml",
                "resources.arsc",
                "lib/arm64-v8a/libsherpa-onnx-jni.so",
                "assets/voice/kws/encoder.int8.onnx",
                "assets/voice/whisper/encoder.int8.onnx",
                "assets/voice/piper/model.onnx"
            )) {
                if ($entryNames -contains $requiredEntry) {
                    Add-Finding -Status "PASS" -Category "APK" -Check $requiredEntry `
                        -Message "Required APK entry is packaged."
                }
                else {
                    Add-Finding -Status "FAIL" -Category "APK" -Check $requiredEntry `
                        -Message "Required APK entry is missing."
                }
            }

            $unexpectedAbis = @(
                $entryNames |
                    Where-Object { $_ -match "^lib/([^/]+)/" } |
                    ForEach-Object {
                        if ($_ -match "^lib/([^/]+)/") {
                            $Matches[1]
                        }
                    } |
                    Sort-Object -Unique |
                    Where-Object { $_ -ne "arm64-v8a" }
            )

            if ($unexpectedAbis.Count -eq 0) {
                Add-Finding -Status "PASS" -Category "APK" -Check "Native ABI packaging" `
                    -Message "Only arm64-v8a native libraries are packaged, matching the configured Vivo target."
            }
            else {
                Add-Finding -Status "INFO" -Category "APK" -Check "Native ABI packaging" `
                    -Message "Additional ABI folders are present." `
                    -Evidence ($unexpectedAbis -join ", ")
            }
        }
        finally {
            $archive.Dispose()
        }
    }
    catch {
        Add-Finding -Status "FAIL" -Category "APK" -Check "APK ZIP integrity" `
            -Message "The APK could not be opened as a valid ZIP archive." `
            -Evidence $_.Exception.Message
    }
}
else {
    Add-Finding -Status "WARN" -Category "APK" -Check "Debug APK exists" `
        -Message "No debug APK is currently present." `
        -Recommendation "Run Build > Assemble Project or allow the Gradle checks to complete."
}

Write-Section "Connected Android device inspection"

if ($SkipDevice) {
    Add-Finding -Status "INFO" -Category "Device" -Check "Device inspection" `
        -Message "Connected-device checks were skipped."
}
else {
    $adb = Find-Adb -AndroidSdk $androidSdk

    if (-not $adb) {
        Add-Finding -Status "INFO" -Category "Device" -Check "ADB" `
            -Message "adb.exe was not found, so no phone inspection was run."
    }
    else {
        $adbVersion = & $adb version 2>&1 | ForEach-Object { $_.ToString() }
        $deviceLines = & $adb devices 2>&1 | ForEach-Object { $_.ToString() }
        $onlineDevices = @(
            $deviceLines |
                Where-Object { $_ -match "^\S+\s+device$" }
        )

        if ($onlineDevices.Count -eq 0) {
            Add-Finding -Status "INFO" -Category "Device" -Check "Connected phone" `
                -Message "No authorized online Android device was detected."
        }
        else {
            Add-Finding -Status "PASS" -Category "Device" -Check "Connected phone" `
                -Message "$($onlineDevices.Count) authorized Android device(s) detected."

            $serial = ($onlineDevices[0] -split "\s+")[0]
            $packageName = "in.diganta.aurabutler"

            $deviceOutput = New-Object System.Collections.Generic.List[string]
            $deviceOutput.Add("ADB: $adb") | Out-Null
            $deviceOutput.Add("Serial: $serial") | Out-Null
            $deviceOutput.Add("") | Out-Null

            $deviceProps = @{
                Model = (& $adb -s $serial shell getprop ro.product.model 2>$null | Select-Object -First 1).Trim()
                ABI = (& $adb -s $serial shell getprop ro.product.cpu.abi 2>$null | Select-Object -First 1).Trim()
                SDK = (& $adb -s $serial shell getprop ro.build.version.sdk 2>$null | Select-Object -First 1).Trim()
                Android = (& $adb -s $serial shell getprop ro.build.version.release 2>$null | Select-Object -First 1).Trim()
            }

            $deviceOutput.Add("Model: $($deviceProps.Model)") | Out-Null
            $deviceOutput.Add("ABI: $($deviceProps.ABI)") | Out-Null
            $deviceOutput.Add("Android: $($deviceProps.Android) / SDK $($deviceProps.SDK)") | Out-Null

            if ($deviceProps.ABI -eq "arm64-v8a") {
                Add-Finding -Status "PASS" -Category "Device" -Check "ABI compatibility" `
                    -Message "The connected device ABI is arm64-v8a."
            }
            else {
                Add-Finding -Status "FAIL" -Category "Device" -Check "ABI compatibility" `
                    -Message "The APK is configured for arm64-v8a, but the device reports $($deviceProps.ABI)."
            }

            $packageDump = & $adb -s $serial shell dumpsys package $packageName 2>&1 |
                ForEach-Object { $_.ToString() }

            $installed = -not (
                ($packageDump -join "`n") -match "Unable to find package|Unknown package"
            )

            if ($installed) {
                Add-Finding -Status "PASS" -Category "Device" -Check "AURA installed" `
                    -Message "The AURA package is installed on the connected device."

                $installedVersion = $packageDump |
                    Where-Object { $_ -match "versionName=" } |
                    Select-Object -First 1

                if ($installedVersion -match "versionName=([^\s]+)") {
                    $deviceVersion = $Matches[1]
                    if ($deviceVersion -eq "0.6.1-gemini-hybrid") {
                        Add-Finding -Status "PASS" -Category "Device" -Check "Installed version" `
                            -Message "The connected phone has v0.6.1-gemini-hybrid installed."
                    }
                    else {
                        Add-Finding -Status "WARN" -Category "Device" -Check "Installed version" `
                            -Message "The phone has $deviceVersion installed, not v0.6.1-gemini-hybrid." `
                            -Recommendation "Press Run in Android Studio to install the current debug build."
                    }
                }

                foreach ($permission in @(
                    "android.permission.RECORD_AUDIO",
                    "android.permission.POST_NOTIFICATIONS"
                )) {
                    $permissionLine = $packageDump |
                        Where-Object {
                            $_ -match [regex]::Escape($permission) -and
                            $_ -match "granted=(true|false)"
                        } |
                        Select-Object -First 1

                    if ($permissionLine -match "granted=true") {
                        Add-Finding -Status "PASS" -Category "Device" -Check $permission `
                            -Message "Permission is granted on the connected phone."
                    }
                    elseif ($permissionLine) {
                        Add-Finding -Status "WARN" -Category "Device" -Check $permission `
                            -Message "Permission is not granted on the connected phone." `
                            -Recommendation "Open App Info > Permissions and allow it."
                    }
                }

                $serviceDump = & $adb -s $serial shell dumpsys activity services $packageName 2>&1 |
                    ForEach-Object { $_.ToString() }

                if (($serviceDump -join "`n") -match "WakeWordService") {
                    Add-Finding -Status "PASS" -Category "Device" -Check "Wake service runtime" `
                        -Message "WakeWordService appears in the device service dump."
                }
                else {
                    Add-Finding -Status "WARN" -Category "Device" -Check "Wake service runtime" `
                        -Message "WakeWordService is not currently visible in the service dump." `
                        -Recommendation "Open AURA, enable wake listening while the app is visible, save, and confirm the persistent notification."
                }

                $pidOutput = (& $adb -s $serial shell pidof $packageName 2>$null |
                    Select-Object -First 1).Trim()

                if ($pidOutput) {
                    Add-Finding -Status "PASS" -Category "Device" -Check "AURA process" `
                        -Message "The AURA process is currently running." `
                        -Evidence "PID $pidOutput"

                    $logcat = & $adb -s $serial logcat -d -v threadtime --pid $pidOutput -t 800 2>&1 |
                        ForEach-Object { $_.ToString() }

                    $logcatPath = Join-Path $LogRoot "device_logcat_current_process.log"
                    [System.IO.File]::WriteAllLines(
                        $logcatPath,
                        [string[]]$logcat,
                        (New-Object System.Text.UTF8Encoding($false))
                    )
                    $script:GeneratedFiles.Add($logcatPath) | Out-Null

                    $fatalLines = @(
                        $logcat |
                            Where-Object {
                                $_ -match "FATAL EXCEPTION|Fatal signal|SIGABRT|AndroidRuntime.*E"
                            }
                    )

                    if ($fatalLines.Count -eq 0) {
                        Add-Finding -Status "PASS" -Category "Device logs" -Check "Fatal crash scan" `
                            -Message "No fatal crash signature was found in the current process log sample."
                    }
                    else {
                        Add-Finding -Status "FAIL" -Category "Device logs" -Check "Fatal crash scan" `
                            -Message "$($fatalLines.Count) fatal crash signature line(s) were found." `
                            -Evidence (($fatalLines | Select-Object -First 5) -join " | ") `
                            -Recommendation "Review the saved device logcat file."
                    }

                    $importantErrors = @(
                        $logcat |
                            Where-Object {
                                $_ -match "AuraWakeService.*\sE\s|AuraPiperSpeaker.*\sE\s|sherpa-onnx.*\sE\s|SecurityException|ForegroundServiceStartNotAllowedException"
                            }
                    )

                    if ($importantErrors.Count -eq 0) {
                        Add-Finding -Status "PASS" -Category "Device logs" -Check "AURA voice errors" `
                            -Message "No selected wake/Piper/sherpa/permission error was found in the current process log sample."
                    }
                    else {
                        Add-Finding -Status "WARN" -Category "Device logs" -Check "AURA voice errors" `
                            -Message "$($importantErrors.Count) relevant error line(s) were found." `
                            -Evidence (($importantErrors | Select-Object -First 8) -join " | ")
                    }
                }
                else {
                    Add-Finding -Status "INFO" -Category "Device" -Check "AURA process" `
                        -Message "The AURA process is not currently running."
                }

                $deviceOutput.Add("") | Out-Null
                $deviceOutput.Add("PACKAGE DUMP") | Out-Null
                foreach ($line in $packageDump) {
                    $deviceOutput.Add($line) | Out-Null
                }

                $deviceOutput.Add("") | Out-Null
                $deviceOutput.Add("SERVICE DUMP") | Out-Null
                foreach ($line in $serviceDump) {
                    $deviceOutput.Add($line) | Out-Null
                }
            }
            else {
                Add-Finding -Status "INFO" -Category "Device" -Check "AURA installed" `
                    -Message "The AURA package is not installed on the connected device."
            }

            [System.IO.File]::WriteAllLines(
                $DevicePath,
                [string[]]$deviceOutput,
                (New-Object System.Text.UTF8Encoding($false))
            )
            $script:GeneratedFiles.Add($DevicePath) | Out-Null
        }
    }
}

Write-Section "Generating reports"

$finishedAt = Get-Date
$durationSeconds = [math]::Round(($finishedAt - $startedAt).TotalSeconds, 2)

$passCount = @($script:Findings | Where-Object { $_.Status -eq "PASS" }).Count
$warnCount = @($script:Findings | Where-Object { $_.Status -eq "WARN" }).Count
$failCount = @($script:Findings | Where-Object { $_.Status -eq "FAIL" }).Count
$infoCount = @($script:Findings | Where-Object { $_.Status -eq "INFO" }).Count

$overall = if ($failCount -gt 0) {
    "FAILED"
}
elseif ($warnCount -gt 0) {
    "PASSED WITH WARNINGS"
}
else {
    "PASSED"
}

$reportObject = [ordered]@{
    reportVersion = "1.0"
    projectRoot = $ProjectRoot
    startedAt = $startedAt.ToString("o")
    finishedAt = $finishedAt.ToString("o")
    durationSeconds = $durationSeconds
    overall = $overall
    counts = [ordered]@{
        pass = $passCount
        warning = $warnCount
        fail = $failCount
        info = $infoCount
    }
    options = [ordered]@{
        skipGradle = [bool]$SkipGradle
        skipDevice = [bool]$SkipDevice
        strictHashes = [bool]$StrictHashes
    }
    environment = [ordered]@{
        powershell = $PSVersionTable.PSVersion.ToString()
        javaHome = $javaHome
        androidSdk = $androidSdk
    }
    releaseIntegrity = [ordered]@{
        exactHashMatches = $exactMatches
        hashMismatches = $hashMismatches
        missingHashedFiles = $hashMissing
    }
    gradle = @($script:GradleResults)
    findings = @($script:Findings)
}

$reportObject |
    ConvertTo-Json -Depth 8 |
    Set-Content -LiteralPath $JsonPath -Encoding UTF8
$script:GeneratedFiles.Add($JsonPath) | Out-Null

$summaryLines = New-Object System.Collections.Generic.List[string]
$summaryLines.Add("AURA FULL PROJECT INSPECTION") | Out-Null
$summaryLines.Add("============================") | Out-Null
$summaryLines.Add("Overall: $overall") | Out-Null
$summaryLines.Add("Project: $ProjectRoot") | Out-Null
$summaryLines.Add("Started: $($startedAt.ToString('yyyy-MM-dd HH:mm:ss'))") | Out-Null
$summaryLines.Add("Finished: $($finishedAt.ToString('yyyy-MM-dd HH:mm:ss'))") | Out-Null
$summaryLines.Add("Duration: $durationSeconds seconds") | Out-Null
$summaryLines.Add("") | Out-Null
$summaryLines.Add("PASS: $passCount") | Out-Null
$summaryLines.Add("WARN: $warnCount") | Out-Null
$summaryLines.Add("FAIL: $failCount") | Out-Null
$summaryLines.Add("INFO: $infoCount") | Out-Null
$summaryLines.Add("") | Out-Null
$summaryLines.Add("Exact updater hash matches: $exactMatches") | Out-Null
$summaryLines.Add("Modified updater-managed files: $hashMismatches") | Out-Null
$summaryLines.Add("Missing updater-managed files: $hashMissing") | Out-Null
$summaryLines.Add("") | Out-Null

foreach ($finding in $script:Findings) {
    $summaryLines.Add(
        "[{0}] {1} / {2}: {3}" -f
            $finding.Status,
            $finding.Category,
            $finding.Check,
            $finding.Message
    ) | Out-Null

    if ($finding.Evidence) {
        $summaryLines.Add("    Evidence: $($finding.Evidence)") | Out-Null
    }

    if ($finding.Recommendation) {
        $summaryLines.Add("    Recommendation: $($finding.Recommendation)") | Out-Null
    }
}

[System.IO.File]::WriteAllLines(
    $SummaryPath,
    [string[]]$summaryLines,
    (New-Object System.Text.UTF8Encoding($false))
)
$script:GeneratedFiles.Add($SummaryPath) | Out-Null

$rows = New-Object System.Text.StringBuilder
foreach ($finding in $script:Findings) {
    $cssClass = $finding.Status.ToLowerInvariant()
    [void]$rows.AppendLine(@"
<tr class="$cssClass">
  <td><span class="badge $cssClass">$(HtmlEncode $finding.Status)</span></td>
  <td>$(HtmlEncode $finding.Category)</td>
  <td>$(HtmlEncode $finding.Check)</td>
  <td>$(HtmlEncode $finding.Message)</td>
  <td><code>$(HtmlEncode $finding.Evidence)</code></td>
  <td>$(HtmlEncode $finding.Recommendation)</td>
</tr>
"@)
}

$overallClass = if ($failCount -gt 0) {
    "fail"
}
elseif ($warnCount -gt 0) {
    "warn"
}
else {
    "pass"
}

$html = @"
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>AURA Inspection Report</title>
<style>
:root { color-scheme: light dark; }
body { font-family: Segoe UI, Arial, sans-serif; margin: 0; background: #111827; color: #e5e7eb; }
header { padding: 28px 34px; background: #0b1220; border-bottom: 1px solid #334155; }
main { padding: 24px 34px 48px; }
h1 { margin: 0 0 8px; font-size: 28px; }
.meta { color: #94a3b8; line-height: 1.6; }
.cards { display: grid; grid-template-columns: repeat(5, minmax(120px, 1fr)); gap: 12px; margin: 22px 0; }
.card { background: #1f2937; border: 1px solid #374151; border-radius: 10px; padding: 15px; }
.card strong { display: block; font-size: 24px; margin-top: 5px; }
table { width: 100%; border-collapse: collapse; background: #111827; }
th, td { text-align: left; vertical-align: top; padding: 10px; border-bottom: 1px solid #334155; font-size: 13px; }
th { position: sticky; top: 0; background: #1e293b; z-index: 2; }
tr.pass { background: rgba(16,185,129,.045); }
tr.warn { background: rgba(245,158,11,.065); }
tr.fail { background: rgba(239,68,68,.075); }
.badge { display: inline-block; padding: 3px 8px; border-radius: 999px; font-weight: 700; font-size: 11px; }
.badge.pass { background: #065f46; color: #d1fae5; }
.badge.warn { background: #92400e; color: #fef3c7; }
.badge.fail { background: #991b1b; color: #fee2e2; }
.badge.info { background: #1e40af; color: #dbeafe; }
code { white-space: pre-wrap; word-break: break-word; color: #cbd5e1; }
@media (max-width: 1000px) { .cards { grid-template-columns: repeat(2, 1fr); } main, header { padding-left: 16px; padding-right: 16px; } }
</style>
</head>
<body>
<header>
  <h1>AURA Full Project Inspection</h1>
  <div class="meta">
    Project: $(HtmlEncode $ProjectRoot)<br>
    Generated: $(HtmlEncode $finishedAt.ToString("yyyy-MM-dd HH:mm:ss"))<br>
    Duration: $durationSeconds seconds
  </div>
</header>
<main>
  <div class="cards">
    <div class="card"><span>Overall</span><strong><span class="badge $overallClass">$(HtmlEncode $overall)</span></strong></div>
    <div class="card"><span>Pass</span><strong>$passCount</strong></div>
    <div class="card"><span>Warnings</span><strong>$warnCount</strong></div>
    <div class="card"><span>Failures</span><strong>$failCount</strong></div>
    <div class="card"><span>Exact release files</span><strong>$exactMatches</strong></div>
  </div>
  <table>
    <thead>
      <tr>
        <th>Status</th>
        <th>Category</th>
        <th>Check</th>
        <th>Result</th>
        <th>Evidence</th>
        <th>Recommendation</th>
      </tr>
    </thead>
    <tbody>
      $rows
    </tbody>
  </table>
</main>
</body>
</html>
"@

[System.IO.File]::WriteAllText(
    $HtmlPath,
    $html,
    (New-Object System.Text.UTF8Encoding($false))
)
$script:GeneratedFiles.Add($HtmlPath) | Out-Null

Write-Host ""
Write-Host "Inspection complete." -ForegroundColor Cyan
Write-Host "Overall: $overall"
Write-Host "PASS=$passCount  WARN=$warnCount  FAIL=$failCount  INFO=$infoCount"
Write-Host ""
Write-Host "Reports:" -ForegroundColor Cyan
Write-Host "  HTML: $HtmlPath"
Write-Host "  TXT:  $SummaryPath"
Write-Host "  JSON: $JsonPath"
Write-Host "  Logs: $LogRoot"

if ($OpenReport) {
    Start-Process $HtmlPath
}

if ($failCount -gt 0) {
    exit 2
}

exit 0
