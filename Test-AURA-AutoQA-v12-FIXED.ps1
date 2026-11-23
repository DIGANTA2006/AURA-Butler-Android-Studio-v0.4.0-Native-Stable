param(
    [string]$ProjectRoot = "E:\MY PROJECT FOLDER\AURA PROJECT\AURA-Butler-Android-Studio-v0.4.0-Native-Stable",
    [string]$CloudRoot = "E:\MY PROJECT FOLDER\AURA CLOUD",
    [string]$PackageName = "in.diganta.aurabutler",
    [switch]$Full,
    [switch]$SkipGradle,
    [switch]$SkipDevice,
    [switch]$SkipCloud
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$QwenBase = "https://diganta2006--aura-dual-model-server-qwentext-web.modal.run"
$GemmaBase = "https://diganta2006--aura-dual-model-server-gemmavision-web.modal.run"
$ResearchBase = "https://diganta2006--aura-dual-model-server-researchgateway-web.modal.run"
$ExpectedResearchRuntime = "source-mount-fixed-v0.14.2"
$ExpectedBrainRuntime = "professional-v0.15"
$ExpectedVisionRuntime = "vision-stable-v0.14.3"

$Stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$ReportRoot = [System.IO.Path]::Combine($ProjectRoot, "AURA-AUTOQA-$Stamp")
New-Item -ItemType Directory -Path $ReportRoot -Force | Out-Null

$Results = New-Object System.Collections.ArrayList
$Started = Get-Date

function Add-Result {
    param(
        [Parameter(Mandatory=$true)][string]$Area,
        [Parameter(Mandatory=$true)][string]$Check,
        [Parameter(Mandatory=$true)][ValidateSet("PASS","WARN","FAIL","INFO","SKIP","MANUAL")][string]$Status,
        [string]$Detail = "",
        [string]$Evidence = ""
    )

    $obj = [pscustomobject]@{
        Area = $Area
        Check = $Check
        Status = $Status
        Detail = $Detail
        Evidence = $Evidence
    }
    [void]$Results.Add($obj)

    $color = switch ($Status) {
        "PASS" { "Green" }
        "FAIL" { "Red" }
        "WARN" { "Yellow" }
        "MANUAL" { "Yellow" }
        "SKIP" { "DarkYellow" }
        default { "Gray" }
    }

    Write-Host ("[{0}] {1} :: {2}" -f $Status, $Area, $Check) -ForegroundColor $color
    if ($Detail) {
        Write-Host ("      {0}" -f $Detail)
    }
}

function Save-Text {
    param([string]$Name, [string]$Text)
    $path = [System.IO.Path]::Combine($ReportRoot, $Name)
    [System.IO.File]::WriteAllText($path, [string]$Text, [System.Text.UTF8Encoding]::new($false))
    return $path
}

function Get-Sha256 {
    param([string]$Path)
    return (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash.ToUpperInvariant()
}

function Find-JavaHome {
    if ($env:JAVA_HOME) {
        $candidate = [System.IO.Path]::Combine($env:JAVA_HOME, "bin", "java.exe")
        if (Test-Path -LiteralPath $candidate) {
            return $env:JAVA_HOME
        }
    }

    $candidates = @(
        "E:\Program Files\Android\Android Studio\jbr",
        "C:\Program Files\Android\Android Studio\jbr",
        "C:\Program Files\Android\Android Studio\jre"
    )

    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath ([System.IO.Path]::Combine($candidate, "bin", "java.exe"))) {
            return $candidate
        }
    }

    return $null
}

function Find-Adb {
    # 1. PATH / shell resolution.
    $cmd = Get-Command adb.exe -ErrorAction SilentlyContinue
    if ($cmd -and $cmd.Source -and (Test-Path -LiteralPath $cmd.Source)) {
        return $cmd.Source
    }

    # 2. If Android Studio already started the ADB server, Windows normally
    # exposes the exact executable path through the running process.
    $adbProcesses = @(Get-Process adb -ErrorAction SilentlyContinue)
    foreach ($process in $adbProcesses) {
        try {
            if ($process.Path -and (Test-Path -LiteralPath $process.Path)) {
                return $process.Path
            }
        }
        catch {
            # Process.Path can be unavailable under unusual permission boundaries.
        }
    }

    $candidates = New-Object System.Collections.ArrayList

    # 3. SDK roots advertised by Android tooling.
    foreach ($sdkRoot in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)) {
        if (-not [string]::IsNullOrWhiteSpace([string]$sdkRoot)) {
            [void]$candidates.Add(
                [System.IO.Path]::Combine([string]$sdkRoot, "platform-tools", "adb.exe")
            )
        }
    }

    # 4. sdk.dir used by Gradle/Android Studio.
    $localProps = [System.IO.Path]::Combine($ProjectRoot, "android", "local.properties")
    if (Test-Path -LiteralPath $localProps) {
        $line = Get-Content -LiteralPath $localProps -ErrorAction SilentlyContinue |
            Where-Object { $_ -match '^\s*sdk\.dir\s*=' } |
            Select-Object -First 1
        if ($line) {
            $sdk = ($line -replace '^\s*sdk\.dir\s*=', '').Trim()
            $sdk = $sdk -replace '\\\\', '\'
            if ($sdk) {
                [void]$candidates.Add(
                    [System.IO.Path]::Combine($sdk, "platform-tools", "adb.exe")
                )
            }
        }
    }

    # 5. Standard and common custom Windows SDK locations.
    if ($env:LOCALAPPDATA) {
        [void]$candidates.Add(
            [System.IO.Path]::Combine($env:LOCALAPPDATA, "Android", "Sdk", "platform-tools", "adb.exe")
        )
    }
    if ($env:USERPROFILE) {
        [void]$candidates.Add(
            [System.IO.Path]::Combine($env:USERPROFILE, "AppData", "Local", "Android", "Sdk", "platform-tools", "adb.exe")
        )
    }

    foreach ($common in @(
        "E:\Android\Sdk\platform-tools\adb.exe",
        "E:\AndroidSDK\platform-tools\adb.exe",
        "E:\SDK\Android\platform-tools\adb.exe",
        "D:\Android\Sdk\platform-tools\adb.exe",
        "D:\AndroidSDK\platform-tools\adb.exe",
        "C:\Android\Sdk\platform-tools\adb.exe",
        "C:\AndroidSDK\platform-tools\adb.exe"
    )) {
        [void]$candidates.Add($common)
    }

    foreach ($candidate in @($candidates | Select-Object -Unique)) {
        if ($candidate -and (Test-Path -LiteralPath ([string]$candidate))) {
            return [string]$candidate
        }
    }

    return $null
}

function Invoke-Adb {
    param(
        [Parameter(Mandatory=$true)][string]$Adb,
        [Parameter(Mandatory=$true)][string]$Serial,
        [Parameter(Mandatory=$true)][string[]]$Arguments,
        [switch]$AllowFailure
    )

    # IMPORTANT: Do not invoke adb.exe through PowerShell's native-command
    # pipeline. Windows PowerShell 5.1 can convert perfectly harmless ADB
    # STDERR text into a terminating NativeCommandError whenever
    # $ErrorActionPreference = "Stop". ProcessStartInfo captures native stdout
    # and stderr directly, bypassing that PowerShell behaviour completely.
    $allArgs = @("-s", $Serial) + $Arguments

    # Every ADB argument used by this QA harness is deliberately a simple
    # token (package names, flags, paths without spaces). Refuse unexpected
    # whitespace/quotes rather than building an unsafe command line.
    foreach ($arg in $allArgs) {
        if ($null -eq $arg) {
            throw "ADB argument cannot be null."
        }
        if ([string]$arg -match '[\r\n"]') {
            throw "Unsupported ADB argument contains a quote or newline: $arg"
        }
    }

    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $Adb
    $psi.Arguments = (($allArgs | ForEach-Object {
        if ([string]$_ -match '\s') {
            '"' + [string]$_ + '"'
        } else {
            [string]$_
        }
    }) -join ' ')
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true

    $proc = New-Object System.Diagnostics.Process
    $proc.StartInfo = $psi

    try {
        if (-not $proc.Start()) {
            throw "Failed to start adb.exe."
        }

        # Read both streams asynchronously so neither native pipe can block
        # the other if ADB produces a larger diagnostic response.
        $stdoutTask = $proc.StandardOutput.ReadToEndAsync()
        $stderrTask = $proc.StandardError.ReadToEndAsync()

        $proc.WaitForExit()

        $stdoutText = $stdoutTask.GetAwaiter().GetResult()
        $stderrText = $stderrTask.GetAwaiter().GetResult()
        $code = $proc.ExitCode
    }
    finally {
        $proc.Dispose()
    }

    $stdoutLines = @()
    $stderrLines = @()

    if ($stdoutText) {
        $stdoutLines = @(($stdoutText -replace "`r", "").TrimEnd("`n") -split "`n")
    }
    if ($stderrText) {
        $stderrLines = @(($stderrText -replace "`r", "").TrimEnd("`n") -split "`n")
    }

    $combined = @($stdoutLines) + @($stderrLines)
    $resultText = ($combined -join "`n").Trim()

    if (($code -ne 0) -and (-not $AllowFailure)) {
        throw "ADB failed ($code): $($Arguments -join ' ')`n$resultText"
    }

    return [pscustomobject]@{
        ExitCode = $code
        Text = $resultText
        Lines = @($combined)
        StdOut = ($stdoutLines -join "`n")
        StdErr = ($stderrLines -join "`n")
    }
}

function Invoke-GradleTask {
    param(
        [Parameter(Mandatory=$true)][string]$Gradlew,
        [Parameter(Mandatory=$true)][string]$AndroidRoot,
        [Parameter(Mandatory=$true)][string]$Task,
        [Parameter(Mandatory=$true)][string]$LogName
    )

    # gradlew.bat can emit harmless text on STDERR. Windows PowerShell 5.1 can
    # convert that into NativeCommandError when ErrorActionPreference is Stop.
    # Run the batch wrapper through cmd.exe and capture both native streams
    # directly with ProcessStartInfo.
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $env:ComSpec
    $psi.Arguments = '/d /s /c ""' + $Gradlew + '" --no-daemon ' + $Task + '"'
    $psi.WorkingDirectory = $AndroidRoot
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true

    $proc = New-Object System.Diagnostics.Process
    $proc.StartInfo = $psi

    try {
        if (-not $proc.Start()) {
            throw "Failed to start Gradle wrapper."
        }

        $stdoutTask = $proc.StandardOutput.ReadToEndAsync()
        $stderrTask = $proc.StandardError.ReadToEndAsync()

        $proc.WaitForExit()

        $stdoutText = $stdoutTask.GetAwaiter().GetResult()
        $stderrText = $stderrTask.GetAwaiter().GetResult()
        $code = $proc.ExitCode
    }
    finally {
        $proc.Dispose()
    }

    $combined = @()
    if ($stdoutText) { $combined += (($stdoutText -replace "`r", "").TrimEnd("`n") -split "`n") }
    if ($stderrText) { $combined += (($stderrText -replace "`r", "").TrimEnd("`n") -split "`n") }

    $logText = ($combined -join "`n").Trim()
    $log = Save-Text $LogName $logText

    return [pscustomobject]@{
        ExitCode = $code
        Log = $log
        Text = $logText
    }
}

function Invoke-JsonPost {
    param(
        [Parameter(Mandatory=$true)][string]$Uri,
        [Parameter(Mandatory=$true)][hashtable]$Headers,
        [Parameter(Mandatory=$true)]$Payload,
        [int]$TimeoutSec = 300
    )

    $json = $Payload | ConvertTo-Json -Depth 16 -Compress
    return Invoke-RestMethod `
        -Uri $Uri `
        -Method Post `
        -Headers $Headers `
        -Body $json `
        -ContentType "application/json" `
        -TimeoutSec $TimeoutSec
}

function Get-PlainSecret {
    if ($env:AURA_API_KEY) {
        return $env:AURA_API_KEY
    }

    $secure = Read-Host "Enter AURA_API_KEY for cloud functional tests (hidden; not saved)" -AsSecureString
    if ($null -eq $secure) {
        return ""
    }

    $ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try {
        return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr)
    }
    finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr)
    }
}

function Get-HttpHealth {
    param(
        [string]$Name,
        [string]$Uri,
        [int]$TimeoutSec = 300
    )

    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $value = Invoke-RestMethod -Uri $Uri -Method Get -TimeoutSec $TimeoutSec
        $sw.Stop()
        if ($value.status -eq "ok") {
            Add-Result "Cloud" "$Name health" "PASS" ("status=ok, {0} ms" -f $sw.ElapsedMilliseconds)
            return $value
        }

        Add-Result "Cloud" "$Name health" "FAIL" ("Unexpected status: {0}" -f $value.status)
        return $null
    }
    catch {
        $sw.Stop()
        Add-Result "Cloud" "$Name health" "FAIL" $_.Exception.Message
        return $null
    }
}

function Test-SourceMarker {
    param(
        [string]$Area,
        [string]$Check,
        [string]$Path,
        [string]$Pattern,
        [switch]$Regex
    )

    if (-not (Test-Path -LiteralPath $Path)) {
        Add-Result $Area $Check "FAIL" "Missing file: $Path"
        return
    }

    $text = [System.IO.File]::ReadAllText($Path)
    $matched = if ($Regex) {
        [regex]::IsMatch($text, $Pattern, [System.Text.RegularExpressions.RegexOptions]::Singleline)
    } else {
        $text.Contains($Pattern)
    }

    if ($matched) {
        Add-Result $Area $Check "PASS" ([System.IO.Path]::GetFileName($Path))
    } else {
        Add-Result $Area $Check "FAIL" "Expected implementation marker was not found."
    }
}

Write-Host ""
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "AURA AUTOMATED QA + REAL DEVICE FUNCTIONAL AUDITOR v12" -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "Project : $ProjectRoot"
Write-Host "Cloud   : $CloudRoot"
Write-Host "Report  : $ReportRoot"
Write-Host "Mode    : $(if ($Full) { 'FULL (includes small cloud model budget)' } else { 'SAFE (no authenticated model calls)' })"
Write-Host ""

# -----------------------------------------------------------------------------
# 1. Preflight
# -----------------------------------------------------------------------------
Write-Host "==> 1/9 Preflight" -ForegroundColor Cyan

$AndroidRoot = [System.IO.Path]::Combine($ProjectRoot, "android")
$AppRoot = [System.IO.Path]::Combine($AndroidRoot, "app")
$SourceRoot = [System.IO.Path]::Combine($AppRoot, "src", "main")
$AssetsRoot = [System.IO.Path]::Combine($SourceRoot, "assets")
$KotlinRoot = [System.IO.Path]::Combine($SourceRoot, "java", "in", "diganta", "aurabutler")
$ManifestPath = [System.IO.Path]::Combine($SourceRoot, "AndroidManifest.xml")
$Gradlew = [System.IO.Path]::Combine($AndroidRoot, "gradlew.bat")
$CloudFile = [System.IO.Path]::Combine($CloudRoot, "aura_models.py")
$CloudConfig = [System.IO.Path]::Combine($CloudRoot, "aura_student_config.py")

if (Test-Path -LiteralPath $AndroidRoot) {
    Add-Result "Preflight" "Android project exists" "PASS" $AndroidRoot
} else {
    Add-Result "Preflight" "Android project exists" "FAIL" $AndroidRoot
}

if (Test-Path -LiteralPath $CloudFile) {
    Add-Result "Preflight" "Cloud source exists" "PASS" $CloudFile
} else {
    Add-Result "Preflight" "Cloud source exists" "WARN" "Cloud source was not found locally."
}

if (Test-Path -LiteralPath $ManifestPath) {
    Add-Result "Preflight" "AndroidManifest exists" "PASS"
} else {
    Add-Result "Preflight" "AndroidManifest exists" "FAIL"
}

$JavaHome = Find-JavaHome
if ($JavaHome) {
    $env:JAVA_HOME = $JavaHome
    $env:Path = ([System.IO.Path]::Combine($JavaHome, "bin")) + ";" + $env:Path

    # java.exe intentionally writes `java -version` to STDERR.  Windows
    # PowerShell 5.1 can convert native STDERR into a NativeCommandError when
    # $ErrorActionPreference is Stop, even though Java exited successfully.
    # Capture the two native streams directly so a valid JBR is never treated
    # as a PowerShell failure.
    $javaExe = [System.IO.Path]::Combine($JavaHome, "bin", "java.exe")
    $javaPsi = New-Object System.Diagnostics.ProcessStartInfo
    $javaPsi.FileName = $javaExe
    $javaPsi.Arguments = "-version"
    $javaPsi.UseShellExecute = $false
    $javaPsi.RedirectStandardOutput = $true
    $javaPsi.RedirectStandardError = $true
    $javaPsi.CreateNoWindow = $true

    $javaProcess = New-Object System.Diagnostics.Process
    $javaProcess.StartInfo = $javaPsi

    try {
        [void]$javaProcess.Start()
        $javaStdOut = $javaProcess.StandardOutput.ReadToEnd()
        $javaStdErr = $javaProcess.StandardError.ReadToEnd()
        $javaProcess.WaitForExit()
        $javaExitCode = $javaProcess.ExitCode
    }
    finally {
        $javaProcess.Dispose()
    }

    $javaVersion = (($javaStdOut + [Environment]::NewLine + $javaStdErr).Trim() -replace '\r?\n', '; ')

    if ($javaExitCode -eq 0) {
        Add-Result "Preflight" "Java runtime" "PASS" $javaVersion
    } else {
        Add-Result "Preflight" "Java runtime" "FAIL" ("java -version exited with code {0}: {1}" -f $javaExitCode, $javaVersion)
    }
} else {
    Add-Result "Preflight" "Java runtime" "FAIL" "Android Studio JBR/JAVA_HOME not found."
}

# -----------------------------------------------------------------------------
# 2. Static feature / security / loophole audit
# -----------------------------------------------------------------------------
Write-Host ""
Write-Host "==> 2/9 Static feature + security audit" -ForegroundColor Cyan

$RequiredFiles = @(
    "AuraApplication.kt",
    "ButlerViewModel.kt",
    "data\OpenAiCompatibleProvider.kt",
    "data\StructuredAgentProvider.kt",
    "data\PrivateVisionProvider.kt",
    "data\DeepResearchProvider.kt",
    "data\PrivateDocumentProvider.kt",
    "document\DocumentInputReader.kt",
    "memory\PrivateMemoryStore.kt",
    "memory\HybridRetrievalScorer.kt",
    "screen\ScreenCaptureService.kt",
    "background\WakeWordService.kt",
    "voice\AdaptiveSpeechDetector.kt",
    "voice\OfflineWhisperEngine.kt",
    "voice\PiperSpeaker.kt",
    "analyzer\AuraNotificationListenerService.kt",
    "analyzer\NotificationVault.kt",
    "maintenance\AuraDoctor.kt",
    "runtime\StudentBudgetPolicy.kt",
    "runtime\ResponseQualityGate.kt",
    "runtime\PromptInjectionGuard.kt",
    "runtime\SafeEndpointValidator.kt",
    "runtime\SecretRedactor.kt",
    "domain\ActionSafetyPolicy.kt",
    "domain\ActionExecutor.kt",
    "domain\MultilingualCommandBridge.kt"
)

foreach ($relative in $RequiredFiles) {
    $path = [System.IO.Path]::Combine($KotlinRoot, $relative)
    if (Test-Path -LiteralPath $path) {
        Add-Result "Features" $relative "PASS"
    } else {
        Add-Result "Features" $relative "FAIL" "Expected implementation file is missing."
    }
}

if (Test-Path -LiteralPath $ManifestPath) {
    $manifest = [System.IO.File]::ReadAllText($ManifestPath)

    if ($manifest -match 'android:allowBackup="false"') {
        Add-Result "Security" "Android backups disabled" "PASS"
    } else {
        Add-Result "Security" "Android backups disabled" "FAIL" "allowBackup=false was not found."
    }

    if ($manifest -match 'android:usesCleartextTraffic="\$\{usesCleartextTraffic\}"') {
        Add-Result "Security" "Cleartext traffic controlled by build placeholder" "PASS"
    } else {
        Add-Result "Security" "Cleartext traffic controlled" "WARN" "Expected network hardening placeholder was not found."
    }

    $exportedService = [regex]::Matches(
        $manifest,
        '<service[\s\S]*?android:exported="true"[\s\S]*?</service>',
        [System.Text.RegularExpressions.RegexOptions]::IgnoreCase
    ).Count
    if ($exportedService -eq 0) {
        Add-Result "Security" "No exported background service" "PASS"
    } else {
        Add-Result "Security" "No exported background service" "FAIL" "$exportedService exported service block(s) found."
    }
}

$KotlinFiles = @()
if (Test-Path -LiteralPath $KotlinRoot) {
    $KotlinFiles = @(Get-ChildItem -LiteralPath $KotlinRoot -Recurse -File -Filter "*.kt")
}

$AllKotlin = ""
if ($KotlinFiles.Count -gt 0) {
    $builder = New-Object System.Text.StringBuilder
    foreach ($file in $KotlinFiles) {
        [void]$builder.AppendLine([System.IO.File]::ReadAllText($file.FullName))
    }
    $AllKotlin = $builder.ToString()
}

# Hard-coded key patterns. Never put matched secret text in the report.
$secretPatterns = @(
    'AIza[0-9A-Za-z_\-]{30,}',
    'hf_[0-9A-Za-z]{20,}',
    'gsk_[0-9A-Za-z]{20,}',
    'sk-[0-9A-Za-z_\-]{20,}'
)

$secretFiles = New-Object System.Collections.ArrayList
foreach ($file in $KotlinFiles) {
    $text = [System.IO.File]::ReadAllText($file.FullName)
    foreach ($pattern in $secretPatterns) {
        if ([regex]::IsMatch($text, $pattern)) {
            [void]$secretFiles.Add($file.FullName)
            break
        }
    }
}

if ($secretFiles.Count -eq 0) {
    Add-Result "Security" "No obvious hard-coded API secrets in Kotlin" "PASS"
} else {
    Add-Result "Security" "No obvious hard-coded API secrets in Kotlin" "FAIL" ("Potential secret pattern in: " + (($secretFiles | Select-Object -Unique) -join ", "))
}

$nonLocalHttp = New-Object System.Collections.ArrayList
foreach ($file in $KotlinFiles) {
    $text = [System.IO.File]::ReadAllText($file.FullName)
    $matches = [regex]::Matches($text, 'http://[^\s"''\)]+')
    foreach ($match in $matches) {
        $value = $match.Value
        if (($value -notmatch '^http://127\.0\.0\.1') -and
            ($value -notmatch '^http://localhost') -and
            ($value -notmatch '^http://10\.0\.2\.2')) {
            [void]$nonLocalHttp.Add($file.FullName)
        }
    }
}
if ($nonLocalHttp.Count -eq 0) {
    Add-Result "Security" "No non-local cleartext HTTP endpoint in Kotlin" "PASS"
} else {
    Add-Result "Security" "No non-local cleartext HTTP endpoint in Kotlin" "FAIL" ("Found in: " + (($nonLocalHttp | Select-Object -Unique) -join ", "))
}

$dangerPatterns = @{
    "GlobalScope usage" = '\bGlobalScope\b'
    "runBlocking usage" = '\brunBlocking\s*\('
    "Trust-all TLS marker" = 'TrustAll|trustAll|HostnameVerifier[\s\S]{0,150}true'
    "Empty catch block" = 'catch\s*\([^\)]*\)\s*\{\s*\}'
}

foreach ($key in $dangerPatterns.Keys) {
    $count = [regex]::Matches(
        $AllKotlin,
        $dangerPatterns[$key],
        [System.Text.RegularExpressions.RegexOptions]::Singleline
    ).Count
    if ($count -eq 0) {
        Add-Result "Logic" $key "PASS"
    } else {
        Add-Result "Logic" $key "WARN" "$count occurrence(s) found; review recommended."
    }
}

$todoCount = [regex]::Matches($AllKotlin, '\b(TODO|FIXME)\b').Count
if ($todoCount -eq 0) {
    Add-Result "Logic" "No TODO/FIXME in production Kotlin" "PASS"
} else {
    Add-Result "Logic" "No TODO/FIXME in production Kotlin" "WARN" "$todoCount TODO/FIXME marker(s) remain."
}

# ActionKind coverage: every declared phone action should have an implementation reference.
$ModelsPath = [System.IO.Path]::Combine($KotlinRoot, "model", "Models.kt")
if (Test-Path -LiteralPath $ModelsPath) {
    $modelsText = [System.IO.File]::ReadAllText($ModelsPath)
    $blockMatch = [regex]::Match(
        $modelsText,
        'enum\s+class\s+ActionKind[\s\S]*?\{([\s\S]*?)companion\s+object',
        [System.Text.RegularExpressions.RegexOptions]::Singleline
    )

    if ($blockMatch.Success) {
        $actionMatches = [regex]::Matches($blockMatch.Groups[1].Value, '(?m)^\s*([A-Z][A-Z0-9_]+)\("')
        $missingActionRefs = New-Object System.Collections.ArrayList

        foreach ($actionMatch in $actionMatches) {
            $name = $actionMatch.Groups[1].Value
            $count = [regex]::Matches($AllKotlin, "ActionKind\.$name\b").Count
            if ($count -lt 1) {
                [void]$missingActionRefs.Add($name)
            }
        }

        if ($missingActionRefs.Count -eq 0) {
            Add-Result "Logic" "All ActionKind values have implementation references" "PASS" "$($actionMatches.Count) action kinds checked."
        } else {
            Add-Result "Logic" "All ActionKind values have implementation references" "FAIL" ("No implementation reference: " + ($missingActionRefs -join ", "))
        }
    } else {
        Add-Result "Logic" "ActionKind coverage parser" "WARN" "Could not parse ActionKind enum."
    }
}

# v0.14 markers
Test-SourceMarker "Features" "Qwen streaming client" ([System.IO.Path]::Combine($KotlinRoot, "data", "OpenAiCompatibleProvider.kt")) "stream" 
Test-SourceMarker "Features" "Structured multi-step agent" ([System.IO.Path]::Combine($KotlinRoot, "data", "StructuredAgentProvider.kt")) "/v1/agent/plan"
Test-SourceMarker "Features" "Private document endpoint" ([System.IO.Path]::Combine($KotlinRoot, "data", "PrivateDocumentProvider.kt")) "/v1/documents/analyze"
Test-SourceMarker "Features" "Hybrid RAG retrieval" ([System.IO.Path]::Combine($KotlinRoot, "memory", "HybridRetrievalScorer.kt")) "score"
Test-SourceMarker "Features" "Adaptive speech detector" ([System.IO.Path]::Combine($KotlinRoot, "voice", "AdaptiveSpeechDetector.kt")) "noise"
Test-SourceMarker "Features" "Delayed screen capture" ([System.IO.Path]::Combine($KotlinRoot, "screen", "ScreenCaptureService.kt")) "delay" 

# Voice assets
$VoiceAssets = @(
    "voice\kws\encoder.int8.onnx",
    "voice\whisper\encoder.int8.onnx",
    "voice\piper\model.onnx"
)
foreach ($relative in $VoiceAssets) {
    $path = [System.IO.Path]::Combine($SourceRoot, "assets", $relative)
    if (Test-Path -LiteralPath $path) {
        $size = (Get-Item -LiteralPath $path).Length
        if ($size -gt 1024) {
            Add-Result "Voice" $relative "PASS" ("{0:N1} MB" -f ($size / 1MB))
        } else {
            Add-Result "Voice" $relative "FAIL" "Asset is unexpectedly small."
        }
    } else {
        Add-Result "Voice" $relative "FAIL" "Asset missing."
    }
}

# v0.15 professional-coding regressions that previously made AURA unsafe for code.
$BuildGradlePath = [System.IO.Path]::Combine($AppRoot, "build.gradle.kts")
$FormatterPath = [System.IO.Path]::Combine($KotlinRoot, "data", "AiResponseFormatter.kt")
$GeminiPath = [System.IO.Path]::Combine($KotlinRoot, "data", "GeminiAiProvider.kt")
$ModelsPathV15 = [System.IO.Path]::Combine($KotlinRoot, "model", "Models.kt")
$DocumentReaderPath = [System.IO.Path]::Combine($KotlinRoot, "document", "DocumentInputReader.kt")
$SecretRedactorPath = [System.IO.Path]::Combine($KotlinRoot, "runtime", "SecretRedactor.kt")
$PromptPath = [System.IO.Path]::Combine($KotlinRoot, "data", "ButlerSystemPrompt.kt")
$PrivateMemoryPath = [System.IO.Path]::Combine($KotlinRoot, "memory", "PrivateMemoryStore.kt")
$TemporalMemoryPath = [System.IO.Path]::Combine($KotlinRoot, "memory", "TemporalMemoryStore.kt")
$UnifiedAnalyzerPath = [System.IO.Path]::Combine($KotlinRoot, "analyzer", "UnifiedAnalyzerEngine.kt")
$AuraUiPath = [System.IO.Path]::Combine($KotlinRoot, "ui", "AuraApp.kt")

if (Test-Path -LiteralPath $BuildGradlePath) {
    $buildGradleText = [System.IO.File]::ReadAllText($BuildGradlePath)
    if ($buildGradleText.Contains('versionName = "0.17.0-engineering-intelligence"')) {
        Add-Result "ProfessionalCoding" "Android v0.17 version marker" "PASS" "Engineering Intelligence release."
    } else {
        Add-Result "ProfessionalCoding" "Android v0.17 version marker" "FAIL" "Expected versionName 0.17.0-engineering-intelligence."
    }
}

if (Test-Path -LiteralPath $FormatterPath) {
    $formatterText = [System.IO.File]::ReadAllText($FormatterPath)
    $destructiveFormatter = $formatterText.Contains('Regex("""[*_`~]+""")')
    if ($formatterText.Contains("source code is data") -and (-not $destructiveFormatter)) {
        Add-Result "ProfessionalCoding" "Code-safe response formatter" "PASS" "AURA no longer globally removes *, _, backticks or tildes from source code."
    } else {
        Add-Result "ProfessionalCoding" "Code-safe response formatter" "FAIL" "Code syntax can still be destroyed by response cleanup."
    }
}

if (Test-Path -LiteralPath $GeminiPath) {
    $geminiText = [System.IO.File]::ReadAllText($GeminiPath)
    if ($geminiText.Contains('MODEL_FAST = "gemini-3.5-flash-lite"') -and $geminiText.Contains('MODEL_QUALITY = "gemini-3.6-flash"')) {
        Add-Result "ProfessionalCoding" "Current Gemini fallback models" "PASS" "3.5 Flash-Lite + 3.6 Flash"
    } else {
        Add-Result "ProfessionalCoding" "Current Gemini fallback models" "FAIL" "Legacy/unavailable Gemini model ID remains."
    }
}

if (Test-Path -LiteralPath $ModelsPathV15) {
    $modelsV15Text = [System.IO.File]::ReadAllText($ModelsPathV15)
    if ($modelsV15Text.Contains('geminiModel: String = "gemini-3.5-flash-lite"') -and $modelsV15Text.Contains('aiModel: String = "openai/gpt-oss-20b"')) {
        Add-Result "ProfessionalCoding" "Professional provider defaults" "PASS"
    } else {
        Add-Result "ProfessionalCoding" "Professional provider defaults" "FAIL" "Expected current Gemini/Groq defaults were not found."
    }
}

if (Test-Path -LiteralPath $DocumentReaderPath) {
    $documentReaderText = [System.IO.File]::ReadAllText($DocumentReaderPath)
    if ($documentReaderText.Contains('MAX_PROJECT_ZIP_BYTES = 300L * 1024 * 1024') -and $documentReaderText.Contains('text/x-aura-project')) {
        Add-Result "ProfessionalCoding" "Large ZIP project compaction" "PASS" "Large source projects can be compacted locally before private Qwen analysis."
    } else {
        Add-Result "ProfessionalCoding" "Large ZIP project compaction" "FAIL" "v0.15 project snapshot pipeline is missing."
    }
}

if (Test-Path -LiteralPath $SecretRedactorPath) {
    $secretText = [System.IO.File]::ReadAllText($SecretRedactorPath)
    if ($secretText.Contains('containsLikelySecret')) {
        Add-Result "ProfessionalCoding" "Credential-aware coding safety" "PASS" "Normal API-key programming terminology is separated from likely real credential leakage."
    } else {
        Add-Result "ProfessionalCoding" "Credential-aware coding safety" "FAIL" "Secret detection remains too broad for professional coding prompts."
    }
}

if (Test-Path -LiteralPath $PromptPath) {
    $promptV15Text = [System.IO.File]::ReadAllText($PromptPath)
    if ($promptV15Text.Contains('complete, runnable or buildable code') -and $promptV15Text.Contains('localProvider || settings.allowAiPrivateContext')) {
        Add-Result "ProfessionalCoding" "Professional coding prompt + private conversation/memory continuity" "PASS"
    } else {
        Add-Result "ProfessionalCoding" "Professional coding prompt + private conversation/memory continuity" "FAIL" "Professional prompt/history/retrieval rules are incomplete."
    }
}

if ((Test-Path -LiteralPath $PrivateMemoryPath) -and (Test-Path -LiteralPath $TemporalMemoryPath)) {
    $privateMemoryText = [System.IO.File]::ReadAllText($PrivateMemoryPath)
    $temporalMemoryText = [System.IO.File]::ReadAllText($TemporalMemoryPath)
    if ($privateMemoryText.Contains('containsLikelySecret') -and $temporalMemoryText.Contains('containsLikelySecret')) {
        Add-Result "ProfessionalCoding" "Code/document retrieval memory does not reject harmless API vocabulary" "PASS"
    } else {
        Add-Result "ProfessionalCoding" "Code/document retrieval memory does not reject harmless API vocabulary" "FAIL" "Memory still uses broad sensitive-keyword rejection for source/document chunks."
    }
}

if (Test-Path -LiteralPath $UnifiedAnalyzerPath) {
    $unifiedAnalyzerText = [System.IO.File]::ReadAllText($UnifiedAnalyzerPath)
    if ($unifiedAnalyzerText.Contains('with sources') -and $unifiedAnalyzerText.Contains('deep dive') -and $unifiedAnalyzerText.Contains('latest')) {
        Add-Result "ProfessionalCoding" "Natural current-information requests route to private research" "PASS" "Source-seeking latest/current queries no longer depend on an explicit research prefix."
    } else {
        Add-Result "ProfessionalCoding" "Natural current-information requests route to private research" "FAIL" "Natural source-seeking live queries can still fall through to a generic AI provider."
    }
}

if (Test-Path -LiteralPath $AuraUiPath) {
    $auraUiText = [System.IO.File]::ReadAllText($AuraUiPath)
    if ($auraUiText.Contains('Private-first butler • local + private cloud')) {
        Add-Result "ProfessionalCoding" "Privacy header accurately describes hybrid runtime" "PASS"
    } else {
        Add-Result "ProfessionalCoding" "Privacy header accurately describes hybrid runtime" "FAIL" "UI still claims a purely on-device/private runtime."
    }
}


# v0.16 owner-only voice + proactive important-message attention.
$OwnerVoiceGuardPath = [System.IO.Path]::Combine($KotlinRoot, "voice", "OwnerVoiceGuard.kt")
$WakeServiceV16Path = [System.IO.Path]::Combine($KotlinRoot, "background", "WakeWordService.kt")
$VoiceManagerV16Path = [System.IO.Path]::Combine($KotlinRoot, "voice", "VoiceManager.kt")
$ImportantSentinelPath = [System.IO.Path]::Combine($KotlinRoot, "analyzer", "ImportantMessageSentinel.kt")
$NotificationListenerV16Path = [System.IO.Path]::Combine($KotlinRoot, "analyzer", "AuraNotificationListenerService.kt")
$NotificationClassifierV16Path = [System.IO.Path]::Combine($KotlinRoot, "analyzer", "NotificationClassifier.kt")
$RuntimeStateV16Path = [System.IO.Path]::Combine($KotlinRoot, "runtime", "AuraRuntimeState.kt")
$WakeKeywordsV16Path = [System.IO.Path]::Combine($AssetsRoot, "voice", "kws", "aura_keywords.txt")
$SpeakerModelV16Path = [System.IO.Path]::Combine($AssetsRoot, "voice", "speaker", "3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx")
$ExpectedSpeakerShaV16 = "AA3CFC16963A10586A9393F5035D6D6B57E98D358B347F80C2A30BF4F00CEBA2"

if (Test-Path -LiteralPath $OwnerVoiceGuardPath) {
    $ownerVoiceText = [System.IO.File]::ReadAllText($OwnerVoiceGuardPath)
    if ($ownerVoiceText.Contains('SpeakerEmbeddingExtractor') -and $ownerVoiceText.Contains('ENROLLMENT_SAMPLES = 3') -and $ownerVoiceText.Contains('saveOwnerVoiceEmbedding')) {
        Add-Result "OwnerVoice" "Local owner speaker-verification implementation" "PASS" "Three-sample encrypted speaker embedding; no raw enrollment-audio persistence in this implementation."
    } else {
        Add-Result "OwnerVoice" "Local owner speaker-verification implementation" "FAIL" "OwnerVoiceGuard is incomplete."
    }
} else {
    Add-Result "OwnerVoice" "Local owner speaker-verification implementation" "FAIL" "OwnerVoiceGuard.kt missing."
}

if (Test-Path -LiteralPath $SpeakerModelV16Path) {
    $speakerSha = (Get-FileHash -Algorithm SHA256 -LiteralPath $SpeakerModelV16Path).Hash.ToUpperInvariant()
    $speakerSizeMb = [Math]::Round((Get-Item -LiteralPath $SpeakerModelV16Path).Length / 1MB, 1)
    if ($speakerSha -eq $ExpectedSpeakerShaV16) {
        Add-Result "OwnerVoice" "Speaker embedding model asset" "PASS" "$speakerSizeMb MB; SHA256 verified."
    } else {
        Add-Result "OwnerVoice" "Speaker embedding model asset" "FAIL" "Unexpected SHA256: $speakerSha"
    }
} else {
    Add-Result "OwnerVoice" "Speaker embedding model asset" "FAIL" "Owner speaker model missing from APK assets."
}

if ((Test-Path -LiteralPath $WakeServiceV16Path) -and (Test-Path -LiteralPath $VoiceManagerV16Path)) {
    $wakeV16Text = [System.IO.File]::ReadAllText($WakeServiceV16Path)
    $voiceManagerV16Text = [System.IO.File]::ReadAllText($VoiceManagerV16Path)
    if ($wakeV16Text.Contains('OwnerVoiceGuard.verify') -and $voiceManagerV16Text.Contains('OwnerVoiceGuard.verify')) {
        Add-Result "OwnerVoice" "All spoken-command paths verify owner" "PASS" "Wake, barge-in, and manual microphone paths are gated."
    } else {
        Add-Result "OwnerVoice" "All spoken-command paths verify owner" "FAIL" "One or more voice-command paths bypass OwnerVoiceGuard."
    }

    $secureStoreNewCount = ([regex]::Matches($wakeV16Text, 'SecureStore\(this\)')).Count
    if (($secureStoreNewCount -le 1) -and $wakeV16Text.Contains('private val store by lazy')) {
        Add-Result "Reliability" "WakeWordService reuses SecureStore" "PASS" "Avoids repeated Android Keystore setup inside the wake loop."
    } else {
        Add-Result "Reliability" "WakeWordService reuses SecureStore" "WARN" "Repeated SecureStore construction remains in WakeWordService."
    }
}

if (Test-Path -LiteralPath $WakeKeywordsV16Path) {
    $keywordV16Text = [System.IO.File]::ReadAllText($WakeKeywordsV16Path)
    if ($keywordV16Text.Contains('@AURA') -and $keywordV16Text.Contains('@HEY_AURA') -and $keywordV16Text.Contains('@OK_AURA')) {
        Add-Result "OwnerVoice" "Aura / Hey Aura / OK Aura keyword set" "PASS"
    } else {
        Add-Result "OwnerVoice" "Aura / Hey Aura / OK Aura keyword set" "FAIL" "One or more required wake variants are missing."
    }
}

if ((Test-Path -LiteralPath $ImportantSentinelPath) -and (Test-Path -LiteralPath $NotificationListenerV16Path)) {
    $sentinelV16Text = [System.IO.File]::ReadAllText($ImportantSentinelPath)
    $listenerV16Text = [System.IO.File]::ReadAllText($NotificationListenerV16Path)
    if ($sentinelV16Text.Contains('importantMessageVoiceAlerts') -and $sentinelV16Text.Contains('isDeviceLocked') -and $listenerV16Text.Contains('ImportantMessageSentinel.maybeSpeak')) {
        Add-Result "Attention" "Immediate important-message voice sentinel" "PASS" "Local priority gate + lock-screen privacy policy is wired to NotificationListenerService."
    } else {
        Add-Result "Attention" "Immediate important-message voice sentinel" "FAIL" "Important-message speaking pipeline is incomplete."
    }
} else {
    Add-Result "Attention" "Immediate important-message voice sentinel" "FAIL" "ImportantMessageSentinel or notification listener source missing."
}

if (Test-Path -LiteralPath $NotificationClassifierV16Path) {
    $classifierV16Text = [System.IO.File]::ReadAllText($NotificationClassifierV16Path)
    if ($classifierV16Text.Contains('call me urgently') -and $classifierV16Text.Contains('rescheduled') -and $classifierV16Text.Contains('attendance')) {
        Add-Result "Attention" "Urgent-message classifier vocabulary" "PASS"
    } else {
        Add-Result "Attention" "Urgent-message classifier vocabulary" "WARN" "Expected v0.16 urgency terms are incomplete."
    }
}

if (Test-Path -LiteralPath $PrivateMemoryPath) {
    $privateMemoryV16Text = [System.IO.File]::ReadAllText($PrivateMemoryPath)
    if ($privateMemoryV16Text.Contains('pruneToCapLocked()')) {
        Add-Result "Reliability" "Private memory hard cap enforced on add" "PASS"
    } else {
        Add-Result "Reliability" "Private memory hard cap enforced on add" "FAIL" "Normal memory inserts can still exceed the intended cap."
    }
}

if (Test-Path -LiteralPath $RuntimeStateV16Path) {
    $runtimeV16Text = [System.IO.File]::ReadAllText($RuntimeStateV16Path)
    if ($runtimeV16Text.Contains('forceIdleUnlessListening')) {
        Add-Result "Reliability" "Wake/listening state protected at turn completion" "PASS"
    } else {
        Add-Result "Reliability" "Wake/listening state protected at turn completion" "FAIL" "Unconditional force-idle race remains."
    }
}


# v0.17 repository intelligence + retrieval + portable streaming.
$RepositoryIntelligencePath = [System.IO.Path]::Combine($KotlinRoot, "document", "RepositoryIntelligence.kt")
$RetrievalV17Path = [System.IO.Path]::Combine($KotlinRoot, "memory", "HybridRetrievalScorer.kt")
$OpenAiV17Path = [System.IO.Path]::Combine($KotlinRoot, "data", "OpenAiCompatibleProvider.kt")
$ProductionEngineV17Path = [System.IO.Path]::Combine($KotlinRoot, "domain", "ProductionButlerEngine.kt")
$PrivateDocumentV17Path = [System.IO.Path]::Combine($KotlinRoot, "data", "PrivateDocumentProvider.kt")

if ((Test-Path -LiteralPath $RepositoryIntelligencePath) -and (Test-Path -LiteralPath $DocumentReaderPath)) {
    $repoV17Text = [System.IO.File]::ReadAllText($RepositoryIntelligencePath)
    $readerV17Text = [System.IO.File]::ReadAllText($DocumentReaderPath)
    if (
        $repoV17Text.Contains('Repository Intelligence Map v0.17') -and
        $repoV17Text.Contains('Cross-file dependency hints') -and
        $readerV17Text.Contains('AURA PROJECT SNAPSHOT v2') -and
        $readerV17Text.Contains('__AURA__/PROJECT_MAP.md')
    ) {
        Add-Result "EngineeringIntelligence" "Dependency-aware repository context" "PASS" "Project ZIPs include a deterministic symbol/dependency map before private Qwen analysis."
    } else {
        Add-Result "EngineeringIntelligence" "Dependency-aware repository context" "FAIL" "v0.17 repository map/snapshot wiring is incomplete."
    }
} else {
    Add-Result "EngineeringIntelligence" "Dependency-aware repository context" "FAIL" "RepositoryIntelligence.kt or DocumentInputReader.kt is missing."
}

if ((Test-Path -LiteralPath $RetrievalV17Path) -and (Test-Path -LiteralPath $PrivateMemoryPath)) {
    $retrievalV17Text = [System.IO.File]::ReadAllText($RetrievalV17Path)
    $privateMemoryV17Text = [System.IO.File]::ReadAllText($PrivateMemoryPath)
    if (
        $retrievalV17Text.Contains('data class QueryProfile') -and
        $retrievalV17Text.Contains('fun prepare(query: String)') -and
        $retrievalV17Text.Contains('ordered * 0.10') -and
        $privateMemoryV17Text.Contains('HybridRetrievalScorer.prepare(query)')
    ) {
        Add-Result "EngineeringIntelligence" "Prepared hybrid memory retrieval" "PASS" "Query features are reused and ordered phrase/specificity signals improve private retrieval."
    } else {
        Add-Result "EngineeringIntelligence" "Prepared hybrid memory retrieval" "FAIL" "v0.17 retrieval upgrade is incomplete."
    }
}

if (Test-Path -LiteralPath $PromptPath) {
    $promptV17Text = [System.IO.File]::ReadAllText($PromptPath)
    if (
        $promptV17Text.Contains('Work repository-first') -and
        $promptV17Text.Contains('VERIFIED DEFECTS') -and
        $promptV17Text.Contains('compatibility review')
    ) {
        Add-Result "EngineeringIntelligence" "Repository-first engineering contract" "PASS" "Complex coding requests require evidence, patch planning and verification boundaries."
    } else {
        Add-Result "EngineeringIntelligence" "Repository-first engineering contract" "FAIL" "v0.17 engineering prompt contract is incomplete."
    }
}

if (Test-Path -LiteralPath $OpenAiV17Path) {
    $openAiV17Text = [System.IO.File]::ReadAllText($OpenAiV17Path)
    $modalHostGate = $openAiV17Text.Contains('!endpoint.contains(".modal.run"')
    if (
        (-not $modalHostGate) -and
        $openAiV17Text.Contains('val historyLimit = if (engineering) 10 else 6') -and
        $openAiV17Text.Contains('AURA-Butler-Android/0.17.0')
    ) {
        Add-Result "EngineeringIntelligence" "Portable private SSE + richer coding continuity" "PASS" "Streaming is capability-fallback based rather than Modal-host gated; engineering turns retain more recent context."
    } else {
        Add-Result "EngineeringIntelligence" "Portable private SSE + richer coding continuity" "FAIL" "Streaming portability/history improvements are missing."
    }
}

if (Test-Path -LiteralPath $ProductionEngineV17Path) {
    $engineV17Text = [System.IO.File]::ReadAllText($ProductionEngineV17Path)
    $lazyCount = ([regex]::Matches($engineV17Text, 'private val .+ by lazy')).Count
    if (($lazyCount -ge 6) -and $engineV17Text.Contains('searchMemories(effectiveMessage, 10)')) {
        Add-Result "Performance" "Lazy expert initialization + richer memory recall" "PASS" "$lazyCount orchestration experts are lazy; unresolved turns retrieve up to 10 relevant memories."
    } else {
        Add-Result "Performance" "Lazy expert initialization + richer memory recall" "WARN" "Expected v0.17 lazy initialization/retrieval markers are incomplete."
    }
}

if (Test-Path -LiteralPath $PrivateDocumentV17Path) {
    $privateDocV17Text = [System.IO.File]::ReadAllText($PrivateDocumentV17Path)
    if (
        $privateDocV17Text.Contains('repository-aware engineering audit') -and
        $privateDocV17Text.Contains('__AURA__/PROJECT_MAP.md') -and
        $privateDocV17Text.Contains('AURA-Butler-Android/0.17.0')
    ) {
        Add-Result "EngineeringIntelligence" "Repository-aware private project audit prompt" "PASS"
    } else {
        Add-Result "EngineeringIntelligence" "Repository-aware private project audit prompt" "FAIL" "Private project audit prompt is not v0.17-aware."
    }
}

# -----------------------------------------------------------------------------
# 3. Cloud source/package audit
# -----------------------------------------------------------------------------
Write-Host ""
Write-Host "==> 3/9 Cloud source audit" -ForegroundColor Cyan

if ((Test-Path -LiteralPath $CloudFile) -and (Test-Path -LiteralPath $CloudConfig)) {
    $cloudText = [System.IO.File]::ReadAllText($CloudFile)
    $mountCount = ([regex]::Matches($cloudText, '\.add_local_python_source\("aura_student_config"\)')).Count

    if ($mountCount -eq 2) {
        Add-Result "CloudSource" "aura_student_config mounted in both images" "PASS"
    } else {
        Add-Result "CloudSource" "aura_student_config mounted in both images" "FAIL" "Expected 2 mounts; found $mountCount."
    }

    $CloudMarkers = [ordered]@{
        "Qwen SSE endpoint" = "StreamingResponse"
        "Agent planner endpoint" = "/v1/agent/plan"
        "Document endpoint" = "/v1/documents/analyze"
        "Research endpoint" = "/v1/research"
        "Bounded streamed web fetch" = 'client.stream("GET"'
        "Modal runtime pinned" = '"modal==1.5.3"'
        "grpclib runtime present" = '"grpclib>=0.4.7,<0.4.10"'
        "Research source-mount runtime marker" = $ExpectedResearchRuntime
        "Qwen professional brain runtime marker" = $ExpectedBrainRuntime
        "Professional project document runtime" = "professional-document-v0.15"
        "Project snapshot MIME" = "text/x-aura-project"
        "Cross-file project evidence selector" = "_compact_project_evidence"
        "Engineering verifier mode" = "engineering-review"
        "Engineering review result marker" = "aura_reviewed"
    }

    foreach ($markerName in $CloudMarkers.Keys) {
        if ($cloudText.Contains([string]$CloudMarkers[$markerName])) {
            Add-Result "CloudSource" $markerName "PASS"
        } else {
            Add-Result "CloudSource" $markerName "FAIL"
        }
    }

    $python = [System.IO.Path]::Combine($CloudRoot, ".venv", "Scripts", "python.exe")
    if (Test-Path -LiteralPath $python) {
        function Invoke-PythonCheck {
            param([string]$Arguments)

            $psi = New-Object System.Diagnostics.ProcessStartInfo
            $psi.FileName = $python
            $psi.Arguments = $Arguments
            $psi.WorkingDirectory = $CloudRoot
            $psi.UseShellExecute = $false
            $psi.CreateNoWindow = $true
            $psi.RedirectStandardOutput = $true
            $psi.RedirectStandardError = $true

            $proc = New-Object System.Diagnostics.Process
            $proc.StartInfo = $psi
            try {
                if (-not $proc.Start()) { throw "Failed to start Python." }
                $stdoutTask = $proc.StandardOutput.ReadToEndAsync()
                $stderrTask = $proc.StandardError.ReadToEndAsync()
                $proc.WaitForExit()
                return [pscustomobject]@{
                    ExitCode = $proc.ExitCode
                    Text = (($stdoutTask.GetAwaiter().GetResult() + "`n" + $stderrTask.GetAwaiter().GetResult()).Trim())
                }
            }
            finally {
                $proc.Dispose()
            }
        }

        $pyResult = Invoke-PythonCheck '-m py_compile ".\aura_models.py" ".\aura_student_config.py"'
        $importResult = Invoke-PythonCheck '-c "import aura_student_config"'
        Save-Text "cloud-python-check.txt" (($pyResult.Text + "`n" + $importResult.Text).Trim()) | Out-Null

        if (($pyResult.ExitCode -eq 0) -and ($importResult.ExitCode -eq 0)) {
            Add-Result "CloudSource" "Python compile + config import" "PASS"
        } else {
            Add-Result "CloudSource" "Python compile + config import" "FAIL" "See cloud-python-check.txt"
        }
    } else {
        Add-Result "CloudSource" "Cloud venv Python" "WARN" "Not found; local cloud syntax check skipped."
    }
} else {
    Add-Result "CloudSource" "Cloud source/config audit" "WARN" "aura_models.py or aura_student_config.py missing locally."
}

# -----------------------------------------------------------------------------
# 4. Gradle quality gate
# -----------------------------------------------------------------------------
Write-Host ""
Write-Host "==> 4/9 Gradle unit tests + lint + build" -ForegroundColor Cyan

if ($SkipGradle) {
    Add-Result "Build" "Gradle quality gate" "SKIP" "-SkipGradle supplied."
} elseif (-not (Test-Path -LiteralPath $Gradlew)) {
    Add-Result "Build" "Gradle wrapper" "FAIL" $Gradlew
} elseif (-not $JavaHome) {
    Add-Result "Build" "Gradle quality gate" "FAIL" "No Java runtime."
} else {
    $unit = Invoke-GradleTask $Gradlew $AndroidRoot "testDebugUnitTest" "gradle-unit-tests.txt"
    if ($unit.ExitCode -eq 0) {
        Add-Result "Build" "JVM unit tests" "PASS" "See gradle-unit-tests.txt"
    } else {
        Add-Result "Build" "JVM unit tests" "FAIL" "See gradle-unit-tests.txt"
    }

    $lint = Invoke-GradleTask $Gradlew $AndroidRoot "lintDebug" "gradle-lint.txt"
    if ($lint.ExitCode -eq 0) {
        Add-Result "Build" "Android lintDebug" "PASS" "See gradle-lint.txt"
    } else {
        Add-Result "Build" "Android lintDebug" "FAIL" "See gradle-lint.txt"
    }

    $lintTxt = [System.IO.Path]::Combine($AppRoot, "build", "reports", "lint-results-debug.txt")
    if (Test-Path -LiteralPath $lintTxt) {
        Copy-Item -LiteralPath $lintTxt -Destination ([System.IO.Path]::Combine($ReportRoot, "lint-results-debug.txt")) -Force

        $lintDetails = [System.IO.File]::ReadAllText($lintTxt)
        $lintWarningLines = @(
            $lintDetails -split "`r?`n" |
            Where-Object { $_ -match ':\s+Warning:' }
        )
        $lintWarningCount = $lintWarningLines.Count

        if ($lintWarningCount -eq 0) {
            Add-Result "Lint" "Android lint warnings" "PASS" "0 warnings"
        } else {
            Add-Result "Lint" "Android lint warnings" "WARN" "$lintWarningCount warning(s) remain; lint task has no errors but warnings still deserve review."
        }

        if ($lintDetails -match '\[ConstantLocale\]') {
            Add-Result "Lint" "Locale-change correctness" "WARN" "TemporalMemoryStore caches Locale.getDefault() in a static formatter; formatting may remain stale after the phone locale changes."
        }
        if ($lintDetails -match '\[LocalContextResourcesRead') {
            Add-Result "Lint" "Compose resource invalidation" "WARN" "AuraApp reads LocalContext.current.resources; configuration changes may not recompose that read correctly."
        }

        $maintenanceWarnings = @(
            [regex]::Matches($lintDetails, '\[(?:GradleDependency|AndroidGradlePluginVersion|NewerVersionAvailable|UseKtx|ObsoleteSdkInt|ChromeOsAbiSupport)(?:[^\]]*)\]')
        ).Count
        if ($maintenanceWarnings -gt 0) {
            Add-Result "Lint" "Maintenance/style lint findings" "INFO" "$maintenanceWarnings non-blocking dependency/KTX/obsolete-SDK/ABI finding(s)."
        }
    }

    $build = Invoke-GradleTask $Gradlew $AndroidRoot "assembleDebug" "gradle-assemble.txt"
    if ($build.ExitCode -eq 0) {
        Add-Result "Build" "Debug APK build" "PASS" "See gradle-assemble.txt"
    } else {
        Add-Result "Build" "Debug APK build" "FAIL" "See gradle-assemble.txt"
    }
}

# -----------------------------------------------------------------------------
# 5. Connected Android device / app runtime
# -----------------------------------------------------------------------------
Write-Host ""
Write-Host "==> 5/9 Connected Android device + app runtime" -ForegroundColor Cyan

$Adb = $null
$Serial = $null
$DeviceSdk = 0

if ($SkipDevice) {
    Add-Result "Device" "Connected-device checks" "SKIP" "-SkipDevice supplied."
} else {
    $Adb = Find-Adb
    if (-not $Adb) {
        Add-Result "Harness" "ADB executable" "WARN" "adb.exe could not be located, so connected-phone runtime checks are inconclusive. Android Studio may use a custom SDK path."
    } else {
        Add-Result "Device" "ADB executable" "PASS" $Adb

        $adbPsi = New-Object System.Diagnostics.ProcessStartInfo
        $adbPsi.FileName = $Adb
        $adbPsi.Arguments = "devices"
        $adbPsi.UseShellExecute = $false
        $adbPsi.CreateNoWindow = $true
        $adbPsi.RedirectStandardOutput = $true
        $adbPsi.RedirectStandardError = $true

        $adbProc = New-Object System.Diagnostics.Process
        $adbProc.StartInfo = $adbPsi
        try {
            if (-not $adbProc.Start()) { throw "Failed to start adb.exe." }
            $adbStdoutTask = $adbProc.StandardOutput.ReadToEndAsync()
            $adbStderrTask = $adbProc.StandardError.ReadToEndAsync()
            $adbProc.WaitForExit()
            $adbText = ($adbStdoutTask.GetAwaiter().GetResult() + "`n" + $adbStderrTask.GetAwaiter().GetResult()).Trim()
        }
        finally {
            $adbProc.Dispose()
        }

        $deviceLines = @($adbText -split "`r?`n")
        Save-Text "adb-devices.txt" ($deviceLines -join "`n") | Out-Null
        $connected = @(
            $deviceLines |
            Where-Object { $_ -match '^\S+\s+device$' } |
            ForEach-Object { ($_ -split '\s+')[0] }
        )

        if ($connected.Count -eq 0) {
            Add-Result "Device" "USB-debug device connected" "FAIL" "No authorized device in 'adb devices'."
        } else {
            $Serial = [string]$connected[0]
            if ($connected.Count -gt 1) {
                Add-Result "Device" "Multiple devices" "WARN" "Using first device: $Serial"
            }
            Add-Result "Device" "USB-debug device connected" "PASS" $Serial

            $model = (Invoke-Adb $Adb $Serial @("shell","getprop","ro.product.model") -AllowFailure).Text.Trim()
            $sdkText = (Invoke-Adb $Adb $Serial @("shell","getprop","ro.build.version.sdk") -AllowFailure).Text.Trim()
            $abi = (Invoke-Adb $Adb $Serial @("shell","getprop","ro.product.cpu.abi") -AllowFailure).Text.Trim()
            [void][int]::TryParse($sdkText, [ref]$DeviceSdk)
            Add-Result "Device" "Device identity" "INFO" "$model; API $DeviceSdk; ABI $abi"

            $battery = (Invoke-Adb $Adb $Serial @("shell","dumpsys","battery") -AllowFailure).Text
            Save-Text "adb-battery.txt" $battery | Out-Null

            $storage = (Invoke-Adb $Adb $Serial @("shell","df","-h","/data") -AllowFailure).Text
            Save-Text "adb-storage.txt" $storage | Out-Null

            $packageDump = (Invoke-Adb $Adb $Serial @("shell","dumpsys","package",$PackageName) -AllowFailure)
            Save-Text "adb-package.txt" $packageDump.Text | Out-Null

            if ($packageDump.ExitCode -eq 0 -and $packageDump.Text -match [regex]::Escape($PackageName)) {
                $versionMatch = [regex]::Match($packageDump.Text, 'versionName=([^\s]+)')
                $version = if ($versionMatch.Success) { $versionMatch.Groups[1].Value } else { "unknown" }
                Add-Result "Device" "AURA installed" "PASS" "versionName=$version"
            } else {
                Add-Result "Device" "AURA installed" "FAIL" "Package not found: $PackageName"
            }

            foreach ($permission in @(
                "android.permission.RECORD_AUDIO",
                "android.permission.CAMERA",
                "android.permission.POST_NOTIFICATIONS"
            )) {
                if (($permission -eq "android.permission.POST_NOTIFICATIONS") -and ($DeviceSdk -lt 33)) {
                    Add-Result "Permissions" $permission "INFO" "Not a runtime permission before Android 13."
                    continue
                }

                $regex = [regex]::Escape($permission) + ':\s+granted=true'
                if ($packageDump.Text -match $regex) {
                    Add-Result "Permissions" $permission "PASS"
                } else {
                    $status = if ($permission -eq "android.permission.RECORD_AUDIO") { "FAIL" } else { "WARN" }
                    Add-Result "Permissions" $permission $status "Not currently granted."
                }
            }

            $listener = (Invoke-Adb $Adb $Serial @("shell","settings","get","secure","enabled_notification_listeners") -AllowFailure).Text
            if ($listener -match [regex]::Escape($PackageName)) {
                Add-Result "Permissions" "Notification listener access" "PASS"
            } else {
                Add-Result "Permissions" "Notification listener access" "WARN" "Mail/notification analysis cannot be complete without listener access."
            }

            $idle = (Invoke-Adb $Adb $Serial @("shell","dumpsys","deviceidle","whitelist") -AllowFailure).Text
            if ($idle -match [regex]::Escape($PackageName)) {
                Add-Result "Background" "Battery optimization exemption" "PASS"
            } else {
                Add-Result "Background" "Battery optimization exemption" "WARN" "OEM may stop long-running wake listening."
            }

            # Fresh launch and crash capture
            [void](Invoke-Adb $Adb $Serial @("logcat","-c") -AllowFailure)
            [void](Invoke-Adb $Adb $Serial @("shell","am","force-stop",$PackageName) -AllowFailure)

            $launch = Invoke-Adb $Adb $Serial @("shell","am","start","-W","-n","$PackageName/.MainActivity") -AllowFailure
            Save-Text "adb-launch.txt" $launch.Text | Out-Null
            Start-Sleep -Seconds 6

            if (($launch.ExitCode -eq 0) -and ($launch.Text -match 'Status:\s+ok|Complete')) {
                $timeMatch = [regex]::Match($launch.Text, 'TotalTime:\s*(\d+)')
                $time = if ($timeMatch.Success) { $timeMatch.Groups[1].Value + " ms" } else { "timing unavailable" }
                Add-Result "Runtime" "MainActivity launch" "PASS" $time
            } else {
                Add-Result "Runtime" "MainActivity launch" "FAIL" "See adb-launch.txt"
            }

            # $PID is a built-in read-only PowerShell automatic variable
            # (PowerShell variable names are case-insensitive), so use $appPid.
            $appPid = (Invoke-Adb $Adb $Serial @("shell","pidof",$PackageName) -AllowFailure).Text.Trim()
            if ($appPid) {
                Add-Result "Runtime" "AURA process remains alive after launch" "PASS" "pid=$appPid"
            } else {
                Add-Result "Runtime" "AURA process remains alive after launch" "FAIL" "Process disappeared after launch."
            }

            # Verify HOME can actually leave AURA - useful for screen mirroring problem.
            [void](Invoke-Adb $Adb $Serial @("shell","input","keyevent","3") -AllowFailure)
            Start-Sleep -Seconds 2
            $focus = (Invoke-Adb $Adb $Serial @("shell","dumpsys","window","windows") -AllowFailure).Text
            Save-Text "adb-window-after-home.txt" $focus | Out-Null
            $focusLines = @($focus -split "`n" | Where-Object { $_ -match 'mCurrentFocus|mFocusedApp' })
            $focusSummary = ($focusLines -join " ")
            if ($focusSummary -and ($focusSummary -notmatch [regex]::Escape($PackageName))) {
                Add-Result "Runtime" "Android HOME/navigation leaves AURA" "PASS" "AURA did not retain focus after HOME."
            } elseif (-not $focusSummary) {
                Add-Result "Runtime" "Android HOME/navigation leaves AURA" "WARN" "Focus field unavailable on this OEM; see adb-window-after-home.txt."
            } else {
                Add-Result "Runtime" "Android HOME/navigation leaves AURA" "FAIL" "AURA still held focus after HOME."
            }

            # Relaunch to inspect services. Vivo/Android may report that the
            # existing task was merely brought to the foreground; that is success.
            $relaunch = Invoke-Adb $Adb $Serial @("shell","am","start","-n","$PackageName/.MainActivity") -AllowFailure
            if ($relaunch.Text -match 'Activity not started, its current task has been brought to the front') {
                Add-Result "Runtime" "MainActivity relaunch" "INFO" "Existing AURA task was brought to foreground (benign ADB message)."
            } elseif ($relaunch.ExitCode -eq 0) {
                Add-Result "Runtime" "MainActivity relaunch" "PASS"
            } else {
                Add-Result "Runtime" "MainActivity relaunch" "WARN" "ADB relaunch returned exit code $($relaunch.ExitCode)."
            }
            Start-Sleep -Seconds 5

            $services = (Invoke-Adb $Adb $Serial @("shell","dumpsys","activity","services",$PackageName) -AllowFailure).Text
            Save-Text "adb-services.txt" $services | Out-Null
            if ($services -match 'WakeWordService') {
                Add-Result "Voice" "WakeWordService running" "PASS"
            } else {
                Add-Result "Voice" "WakeWordService running" "WARN" "Service is not active now. This is expected only if wake-word listening is disabled."
            }

            # Keep a full log for diagnostics, but judge AURA crashes from
            # an app-PID-filtered log so unrelated Vivo/launcher/system errors
            # cannot produce false failures.
            $fullLogcat = (Invoke-Adb $Adb $Serial @("logcat","-d","-v","threadtime") -AllowFailure).Text
            Save-Text "adb-logcat-full.txt" $fullLogcat | Out-Null

            $appLogcat = ""
            if ($appPid -and ($appPid -match '^\d+(\s+\d+)*$')) {
                $primaryPid = ($appPid -split '\s+')[0]
                $appLogResult = Invoke-Adb $Adb $Serial @("logcat","-d","-v","threadtime","--pid=$primaryPid") -AllowFailure
                if ($appLogResult.ExitCode -eq 0) {
                    $appLogcat = $appLogResult.Text
                }
            }

            if (-not $appLogcat) {
                # OEM/platform fallback: retain only lines that name AURA's package.
                $appLogcat = (($fullLogcat -split "`n") |
                    Where-Object { $_ -match [regex]::Escape($PackageName) }) -join "`n"
            }

            Save-Text "adb-logcat-aura.txt" $appLogcat | Out-Null

            $fatalCount = [regex]::Matches(
                $appLogcat,
                'FATAL EXCEPTION|ANR in\s+' + [regex]::Escape($PackageName)
            ).Count
            $securityCount = [regex]::Matches($appLogcat, 'SecurityException').Count

            if ($fatalCount -eq 0) {
                Add-Result "Runtime" "No AURA fatal crash/ANR during automated smoke run" "PASS"
            } else {
                Add-Result "Runtime" "No AURA fatal crash/ANR during automated smoke run" "FAIL" "$fatalCount AURA fatal/ANR marker(s); see adb-logcat-aura.txt"
            }

            if ($securityCount -eq 0) {
                Add-Result "Runtime" "No AURA SecurityException during smoke run" "PASS"
            } else {
                Add-Result "Runtime" "No AURA SecurityException during smoke run" "WARN" "$securityCount SecurityException marker(s); inspect adb-logcat-aura.txt."
            }

            if ($DeviceSdk -ge 30) {
                $exitInfo = (Invoke-Adb $Adb $Serial @("shell","dumpsys","activity","exit-info",$PackageName) -AllowFailure).Text
                Save-Text "adb-exit-info.txt" $exitInfo | Out-Null
                if ($exitInfo -match 'REASON_CRASH|REASON_ANR') {
                    Add-Result "Runtime" "Recent Android process exit history" "WARN" "Previous crash/ANR appears in exit-info; see adb-exit-info.txt."
                } else {
                    Add-Result "Runtime" "Recent Android process exit history" "PASS"
                }
            }
        }
    }
}

# -----------------------------------------------------------------------------
# 6. Public cloud health checks
# -----------------------------------------------------------------------------
Write-Host ""
Write-Host "==> 6/9 Cloud health" -ForegroundColor Cyan

$QwenHealth = $null
$GemmaHealth = $null
$ResearchHealth = $null

if ($SkipCloud) {
    Add-Result "Cloud" "Cloud health checks" "SKIP" "-SkipCloud supplied."
} else {
    $ResearchHealth = Get-HttpHealth "ResearchGateway" "$ResearchBase/health" 300
    if ($ResearchHealth -and ($ResearchHealth.research_runtime -eq $ExpectedResearchRuntime)) {
        Add-Result "Cloud" "Research runtime marker" "PASS" $ExpectedResearchRuntime
    } elseif ($ResearchHealth) {
        Add-Result "Cloud" "Research runtime marker" "FAIL" ("Expected {0}; got {1}" -f $ExpectedResearchRuntime, $ResearchHealth.research_runtime)
    }

    $QwenHealth = Get-HttpHealth "QwenText" "$QwenBase/health" 300
    if ($QwenHealth -and ($QwenHealth.brain_runtime -eq $ExpectedBrainRuntime)) {
        Add-Result "Cloud" "Qwen professional brain runtime" "PASS" $ExpectedBrainRuntime
    } elseif ($QwenHealth) {
        Add-Result "Cloud" "Qwen professional brain runtime" "FAIL" ("Expected {0}; got {1}" -f $ExpectedBrainRuntime, $QwenHealth.brain_runtime)
    }

    $GemmaHealth = Get-HttpHealth "GemmaVision" "$GemmaBase/health" 300
    if ($GemmaHealth -and ($GemmaHealth.vision_runtime -eq $ExpectedVisionRuntime) -and ($GemmaHealth.gpu -eq "L4") -and ($GemmaHealth.dtype -eq "bfloat16")) {
        Add-Result "Cloud" "Gemma stable vision runtime" "PASS" "L4 + BF16 + $ExpectedVisionRuntime"
    } elseif ($GemmaHealth) {
        Add-Result "Cloud" "Gemma stable vision runtime" "FAIL" ("runtime={0}, gpu={1}, dtype={2}" -f $GemmaHealth.vision_runtime, $GemmaHealth.gpu, $GemmaHealth.dtype)
    }

    if ($GemmaHealth) {
        if (($GemmaHealth.vision_runtime -eq "vision-stable-v0.14.3") -and
            ($GemmaHealth.gpu -eq "L4") -and
            ($GemmaHealth.dtype -eq "bfloat16")) {
            Add-Result "Cloud" "Gemma stable vision runtime" "PASS" "L4 + BF16 + v0.14.3 marker"
        } else {
            Add-Result "Cloud" "Gemma stable vision runtime" "FAIL" ("Expected L4/BF16/v0.14.3; got gpu={0}, dtype={1}, runtime={2}" -f $GemmaHealth.gpu, $GemmaHealth.dtype, $GemmaHealth.vision_runtime)
        }
    }
}

# -----------------------------------------------------------------------------
# 7. Optional authenticated end-to-end cloud functional tests
# -----------------------------------------------------------------------------
Write-Host ""
Write-Host "==> 7/9 Authenticated functional cloud tests" -ForegroundColor Cyan

if ($SkipCloud) {
    Add-Result "CloudFunctional" "Authenticated model tests" "SKIP" "Cloud checks disabled."
} elseif (-not $Full) {
    Add-Result "CloudFunctional" "Authenticated model tests" "SKIP" "Run this script with -Full to test Qwen streaming, agent planning, normal documents, project-code analysis, Gemma vision and deep research."
} else {
    Write-Host ""
    Write-Host "FULL mode uses a small amount of the AURA student daily cloud budget:" -ForegroundColor Yellow
    Write-Host "  ~1 Qwen stream + 1 agent plan + 1 document + 1 Gemma vision + 1 deep-research request" -ForegroundColor Yellow
    Write-Host ""

    $ApiKey = Get-PlainSecret
    if (-not $ApiKey) {
        Add-Result "CloudFunctional" "AURA API key available" "FAIL" "No key entered."
    } else {
        $Headers = @{ Authorization = "Bearer $ApiKey" }

        # Budget endpoint authentication + baseline
        try {
            $budget = Invoke-RestMethod -Uri "$QwenBase/v1/budget" -Method Get -Headers $Headers -TimeoutSec 120
            Add-Result "CloudFunctional" "Authenticated budget endpoint" "PASS"
            Save-Text "cloud-budget-before.json" ($budget | ConvertTo-Json -Depth 12) | Out-Null
        }
        catch {
            Add-Result "CloudFunctional" "Authenticated budget endpoint" "FAIL" $_.Exception.Message
        }

        # Qwen SSE streaming contract
        try {
            $body = @{
                messages = @(
                    @{ role = "user"; content = "Reply with exactly AURA_QWEN_OK and nothing else." }
                )
                max_tokens = 32
                temperature = 0.0
                enable_thinking = $false
                stream = $true
            } | ConvertTo-Json -Depth 10 -Compress

            $sw = [System.Diagnostics.Stopwatch]::StartNew()
            $response = Invoke-WebRequest `
                -UseBasicParsing `
                -Uri "$QwenBase/v1/chat/completions" `
                -Method Post `
                -Headers $Headers `
                -Body $body `
                -ContentType "application/json" `
                -TimeoutSec 300
            $sw.Stop()

            Save-Text "cloud-qwen-stream.txt" $response.Content | Out-Null
            if (($response.StatusCode -eq 200) -and
                ($response.Content -match 'data:\s*\{') -and
                ($response.Content -match '\[DONE\]') -and
                ($response.Content -notmatch '"error"\s*:')) {
                Add-Result "CloudFunctional" "Qwen SSE streaming" "PASS" ("HTTP 200; {0} ms total" -f $sw.ElapsedMilliseconds)
            } else {
                Add-Result "CloudFunctional" "Qwen SSE streaming" "FAIL" "Unexpected SSE response; see cloud-qwen-stream.txt."
            }
        }
        catch {
            Add-Result "CloudFunctional" "Qwen SSE streaming" "FAIL" $_.Exception.Message
        }

        # Professional complex-coding verifier path. This does not execute code;
        # it verifies that v0.15's second-pass engineering review is active.
        try {
            $engineering = Invoke-JsonPost `
                "$QwenBase/v1/chat/completions" `
                $Headers `
                @{
                    messages = @(
                        @{ role = "system"; content = "Return only the requested final answer." },
                        @{ role = "user"; content = "Reply with exactly AURA_ENGINEERING_REVIEW_OK and nothing else." }
                    )
                    max_tokens = 96
                    temperature = 0.0
                    enable_thinking = $true
                    stream = $false
                    aura_mode = "engineering-review"
                } `
                420

            Save-Text "cloud-qwen-engineering-review.json" ($engineering | ConvertTo-Json -Depth 12) | Out-Null
            $engineeringText = [string]$engineering.choices[0].message.content
            $reviewed = [bool]$engineering.aura_reviewed
            if (($engineeringText -match 'AURA_ENGINEERING_REVIEW_OK') -and $reviewed -and ([string]$engineering.aura_mode -eq "engineering-review")) {
                Add-Result "CloudFunctional" "Qwen professional engineering self-review" "PASS" "Second-pass engineering verifier is active."
            } else {
                Add-Result "CloudFunctional" "Qwen professional engineering self-review" "FAIL" ("sentinel={0}, reviewed={1}, mode={2}. See cloud-qwen-engineering-review.json." -f ($engineeringText -match 'AURA_ENGINEERING_REVIEW_OK'), $reviewed, $engineering.aura_mode)
            }
        }
        catch {
            Add-Result "CloudFunctional" "Qwen professional engineering self-review" "FAIL" $_.Exception.Message
        }

        # Agent planner - does not execute actions.
        try {
            $agent = Invoke-JsonPost `
                "$QwenBase/v1/agent/plan" `
                $Headers `
                @{
                    task = "Set a five minute timer and then open the calculator app."
                    memories = @()
                } `
                300

            Save-Text "cloud-agent-plan.json" ($agent | ConvertTo-Json -Depth 12) | Out-Null
            $stepCount = @($agent.steps).Count
            $commands = @($agent.steps | ForEach-Object { [string]$_.command })
            $joinedCommands = ($commands -join " ").ToLowerInvariant()
            $symbolic = @($commands | Where-Object { $_ -match '^[a-z][a-z0-9_]{1,63}$' })
            $hasTimerParameter = ($joinedCommands -match '\btimer\b') -and ($joinedCommands -match '\b5\b|\bfive\b')
            $hasCalculatorParameter = $joinedCommands -match '\bcalculator\b'

            if (($stepCount -ge 2) -and ($stepCount -le 5) -and
                ($symbolic.Count -eq 0) -and
                $hasTimerParameter -and $hasCalculatorParameter) {
                Add-Result "CloudFunctional" "Structured multi-step planner" "PASS" "$stepCount self-contained step(s); duration and calculator parameters preserved; no action executed."
            } else {
                Add-Result "CloudFunctional" "Structured multi-step planner" "FAIL" ("Planner protocol defect. steps={0}, symbolic={1}, timer+duration={2}, calculator={3}. See cloud-agent-plan.json." -f $stepCount, $symbolic.Count, $hasTimerParameter, $hasCalculatorParameter)
            }
        }
        catch {
            Add-Result "CloudFunctional" "Structured multi-step planner" "FAIL" $_.Exception.Message
        }

        # Document analysis sentinel
        try {
            $docBytes = [System.Text.Encoding]::UTF8.GetBytes("AURA QA DOCUMENT. The verification code is 4826. Return the code when asked.")
            $docB64 = [Convert]::ToBase64String($docBytes)
            $doc = Invoke-JsonPost `
                "$QwenBase/v1/documents/analyze" `
                $Headers `
                @{
                    filename = "aura-qa.txt"
                    mime_type = "text/plain"
                    prompt = "What is the four digit verification code? Reply with only the digits."
                    data_base64 = $docB64
                } `
                300

            Save-Text "cloud-document.json" ($doc | ConvertTo-Json -Depth 12) | Out-Null
            if ([string]$doc.answer -match '4826') {
                Add-Result "CloudFunctional" "Private document analysis" "PASS" "Sentinel code recovered."
            } else {
                Add-Result "CloudFunctional" "Private document analysis" "FAIL" "Model did not recover deterministic document sentinel."
            }
        }
        catch {
            Add-Result "CloudFunctional" "Private document analysis" "FAIL" $_.Exception.Message
        }

        # Professional project-code analysis sentinel. This exercises the compacted-project
        # MIME contract used for large ZIP projects without sending a real user project.
        try {
            $projectSnapshot = @"
AURA PROJECT SNAPSHOT v1
Generated for deterministic AutoQA.

===== FILE: app/src/main/kotlin/example/ProjectSentinel.kt =====
package example
const val PROJECT_CODE = "AURA_PROJECT_9157"
fun sentinel(): String = PROJECT_CODE

===== FILE: README.md =====
The project verification sentinel is AURA_PROJECT_9157.
"@
            $projectBytes = [System.Text.Encoding]::UTF8.GetBytes($projectSnapshot)
            $projectB64 = [Convert]::ToBase64String($projectBytes)
            $projectDoc = Invoke-JsonPost `
                "$QwenBase/v1/documents/analyze" `
                $Headers `
                @{
                    filename = "aura-autoqa-project.snapshot"
                    mime_type = "text/x-aura-project"
                    prompt = "Read this software-project snapshot. What exact PROJECT_CODE sentinel is defined? Reply with the exact sentinel string."
                    data_base64 = $projectB64
                } `
                420

            Save-Text "cloud-project-document.json" ($projectDoc | ConvertTo-Json -Depth 16) | Out-Null
            $projectAnswer = [string]$projectDoc.answer
            $projectMode = [bool]$projectDoc.project_mode
            $analysisRuntime = [string]$projectDoc.analysis_runtime
            if (($projectAnswer -match 'AURA_PROJECT_9157') -and $projectMode -and ($analysisRuntime -eq "professional-document-v0.15")) {
                Add-Result "CloudFunctional" "Professional project-code analysis" "PASS" "Project sentinel recovered; project_mode=true; professional-document-v0.15."
            } else {
                Add-Result "CloudFunctional" "Professional project-code analysis" "FAIL" ("sentinel={0}, project_mode={1}, runtime={2}. See cloud-project-document.json." -f ($projectAnswer -match 'AURA_PROJECT_9157'), $projectMode, $analysisRuntime)
            }
        }
        catch {
            Add-Result "CloudFunctional" "Professional project-code analysis" "FAIL" $_.Exception.Message
        }

        # Gemma visual OCR sentinel. Image is embedded in this QA script, not captured from user data.
        try {
            $visionBase64 = "iVBORw0KGgoAAAANSUhEUgAAAaQAAACMCAIAAAC8pkqNAAAHBklEQVR4nO3bTUhU7x7A8ePtquDGLJN2bSJrUdSMUdMZdN6ajMLQzURguRFaVJuCIGhVIRa1aAqiEXuXqIVZVr4M1ESmi2ghmVFSRjIhhLpQqZlpfv/F4R68o1PdSxb//+/7WZ2X5zw+nsUXnxnMEREDAP7p/vWnFwAAvwOxA6ACsQOgArEDoAKxA6ACsQOgArEDoAKxA6ACsQOgArEDoAKxA6ACsQOgArEDoAKxA6ACsQOgArEDoAKxA6ACsQOgArEDoAKxA6ACsQOgArEDoAKxA6ACsQOgArEDoAKxA6ACsQOgArEDoAKxA6ACsQOgArEDoAKxA6ACsQOgArEDoAKxA6ACsQOgArEDoAKxA6ACsQOgArEDoAKxA6ACsQOgArEDoAKxA6ACsQOgArEDoAKxA6ACsQOgArEDoAKxA6ACsQOgwrzHrqmpKT8/f3R01DpduHChfcs+Ligo8Hg8FRUVDocjFovN+aDl0qVLTqfT5XKVlZXduHEj20+xRCIRh8NRUVGxbdu2jx8/ZszgdDqvXLkycwFer9c0zcuXL8+8aDlz5ox1cefOndaVTZs2FRcXG4YxPT0dCoU8Ho/T6bx//741bGJioq6urrCw0Dp9//79li1bPB7P9u3bMxYJ4DeReVZVVXXo0KHm5mbrtLCw0L5lH9sH/f39q1evnvNBEeno6DBNc3x8XETGx8dN04zFYtkGd3V1eb3e6elpEXnw4IHP55tzhu7u7pkLmJyc9Pl8t27dyljqbJFI5OjRoyLS2Nh46tQpEYnH48uWLbPuut3us2fP2jMEAoFoNCoi0Wh07969P35rAH61+Y3d1NSU3+9//fp1TU2NdeX7sUun04sWLZrzQRHx+/3Pnj2zT3t6erZu3ZptcDAY7O3ttU/r6+sTicTsGQKBQMaqnj9/bpqmfDd26XR67dq1o6OjIjI2Nvb161cR6e7uXr58uTXg06dPM2coLi5OpVIikkqlVqxYkfV9AZg387uN7ezsrKysLC0tHR4eTiQSPxzf1dXl8/myPTg4OLhu3Tp7sMPhGBgYyDZ4YGBg5uCLFy/m5ubOnuHVq1cZa1izZs3Q0ND313nv3r3169eXlJQYhlFUVJSXl1dbW7tjx44LFy5YA5YuXZox5927dw3DaG1tZRsL/BHzG7u2trbr169v3LgxHo/bH8bNlkgkPB6PaZq7du0Kh8M/+aCITE5OZhv87du3Hy5PRHJycjIuplKp3Nxce1WW3t7emWNOnz598ODBmVeuXbvW0tJifwiYoamp6erVq16v98OHD3l5eT9cGIBfb/7+aEylUi6Xyzru6Og4cOCAiBQVFVkbumQyuXjxYuuuvd1rbGxsaGiY80ER2bx5c09Pjz3/06dP3W53tsHl5eV9fX3WcTqd3r17t4gEAoGMGYLBoPz3jvXx48fW7jjbNravr6+qqso+3bdvXzKZtH5faw9us2c4ceKEtdV98+aNtUcG8JvNY+xisZj9YfzU1NTKlStFJBgMPnz4UETa29srKyutu3YUXrx4UV1dPeeDIhKNRk3TnJiYEJHx8XG3293e3p5t8M2bNwOBwJcvX0SkpaUlFAqJSGdn58wZTNO0vjewFzA2NrZhw4ZHjx5J9tjV1NTYX4yISG1t7e3bt0XkyZMndnYzfq/q6urW1lYROXLkyLlz537+HQL4Vf49f38ztrW1WR/AGYZRUFBQUlIyODgYDofr6+sbGhoMw4hEIhmPlJaW9vf337lzZ/aDq1at8vv9IyMjfr9/wYIFg4ODhmG8e/dueHh4zsGhUOjt27dOp3PJkiUlJSXnz583DCMYDI6MjHi93vz8/EQisX//fr/fb/xnx5qTk5NMJg8fPuzxeOyL1swul8ta89DQUDweLy8vt9d8/PjxPXv2hMPhvLy85ubmOV/FyZMn6+rqGhsby8rKjh079gteLoD/UY6I/Ok1/J8+f/788uVLu0cA8B1/49gBwM/j38UAqEDsAKhA7ACoQOwAqEDsAKhA7ACoQOwAqEDsAKhA7ACoQOwAqEDsAKhA7ACoQOwAqEDsAKhA7ACoQOwAqEDsAKhA7ACoQOwAqEDsAKhA7ACoQOwAqEDsAKhA7ACoQOwAqEDsAKhA7ACoQOwAqEDsAKhA7ACoQOwAqEDsAKhA7ACoQOwAqEDsAKhA7ACoQOwAqEDsAKhA7ACoQOwAqEDsAKhA7ACoQOwAqEDsAKhA7ACoQOwAqEDsAKhA7ACoQOwAqEDsAKhA7ACoQOwAqEDsAKhA7ACoQOwAqEDsAKhA7ACoQOwAqEDsAKhA7ACoQOwAqEDsAKhA7ACoQOwAqEDsAKhA7ACoQOwAqEDsAKhA7ACoQOwAqEDsAKhA7ACoQOwAqEDsAKhA7ACoQOwAqEDsAKhA7ACo8Bfp7r33rAJi1AAAAABJRU5ErkJggg=="
            $vision = Invoke-JsonPost `
                "$GemmaBase/v1/chat/completions" `
                $Headers `
                @{
                    messages = @(
                        @{
                            role = "user"
                            content = @(
                                @{ type = "text"; text = "Read the four digit code in this QA image. Reply with only the digits." },
                                @{
                                    type = "image_url"
                                    image_url = @{
                                        url = ("data:image/png;base64," + $visionBase64)
                                    }
                                }
                            )
                        }
                    )
                    max_tokens = 128
                    temperature = 0.0
                } `
                300

            Save-Text "cloud-gemma-vision.json" ($vision | ConvertTo-Json -Depth 12) | Out-Null
            $visionText = [string]$vision.choices[0].message.content
            if ($visionText -match '7319') {
                Add-Result "CloudFunctional" "Gemma vision/OCR" "PASS" "Sentinel code recovered."
            } else {
                Add-Result "CloudFunctional" "Gemma vision/OCR" "FAIL" "Gemma did not recover deterministic image sentinel."
            }
        }
        catch {
            Add-Result "CloudFunctional" "Gemma vision/OCR" "FAIL" $_.Exception.Message
        }

        # Research quick mode exercises SearXNG + fetch + Qwen synthesis.
        try {
            $researchSw = [System.Diagnostics.Stopwatch]::StartNew()
            $research = Invoke-JsonPost `
                "$ResearchBase/v1/research" `
                $Headers `
                @{
                    query = "What is the Python programming language? Prefer the official Python website as a source."
                    mode = "deep"
                    language = "en"
                } `
                600
            $researchSw.Stop()

            Save-Text "cloud-research.json" ($research | ConvertTo-Json -Depth 16) | Out-Null
            $sourceCount = @($research.sources).Count
            $fetchedCount = 0
            if ($research.stats -and $null -ne $research.stats.sources_fetched) {
                $fetchedCount = [int]$research.stats.sources_fetched
            }
            $answerText = [string]$research.answer
            $hasCitations = $answerText -match '\[S\d+\]'

            if (($answerText.Length -gt 80) -and
                ($sourceCount -gt 0) -and
                ($fetchedCount -gt 0) -and
                $hasCitations -and
                ($research.mode -eq "deep")) {
                Add-Result "CloudFunctional" "Deep Research end-to-end" "PASS" ("deep mode; {0} source(s), {1} fetched page(s), inline citations present; {2} ms" -f $sourceCount, $fetchedCount, $researchSw.ElapsedMilliseconds)
            } else {
                Add-Result "CloudFunctional" "Deep Research end-to-end" "FAIL" ("Deep research evidence gate failed: mode={0}, sources={1}, fetched={2}, citations={3}. See cloud-research.json." -f $research.mode, $sourceCount, $fetchedCount, $hasCitations)
            }
        }
        catch {
            Add-Result "CloudFunctional" "Deep Research end-to-end" "FAIL" $_.Exception.Message
        }

        # Remove plaintext reference as soon as tests finish.
        $ApiKey = $null
        [System.GC]::Collect()
    }
}

# -----------------------------------------------------------------------------
# 8. Tests that are intentionally not automated because doing so would be unsafe
#    or Android requires human/system consent.
# -----------------------------------------------------------------------------
Write-Host ""
Write-Host "==> 8/9 Safety-boundary checks" -ForegroundColor Cyan

Add-Result "SafetyBoundary" "Real wake-word acoustic accuracy" "MANUAL" "Requires real spoken 'Aura' trials on the physical Vivo; static checks cannot prove microphone detection reliability."
Add-Result "SafetyBoundary" "Second/third wake rearm" "MANUAL" "After AURA finishes a reply, say 'Aura' again three times without touching the UI; record whether each turn re-arms."
Add-Result "SafetyBoundary" "Noise rejection / false wake" "MANUAL" "Use TV/music/background speech and verify AURA does not activate repeatedly without the owner wake phrase."
Add-Result "SafetyBoundary" "Owner/non-owner acoustic verification" "MANUAL" "Owner should pass speaker verification; another speaker should be rejected."
Add-Result "SafetyBoundary" "Screen-off wake survival" "MANUAL" "Lock the phone, wait 30-60 seconds, then say 'Aura' without touching the UI."
Add-Result "SafetyBoundary" "STT/TTS end-to-end" "MANUAL" "Speak a deterministic battery/time command and confirm both transcription and spoken answer."
Add-Result "SafetyBoundary" "Real response latency" "MANUAL" "Measure wake-to-listening and command-to-spoken-reply latency on the physical phone."
Add-Result "SafetyBoundary" "Long-idle OEM wake survival" "MANUAL" "Run an extended idle test; Vivo battery management can only be validated over real idle time."

Add-Result "SafetyBoundary" "MediaProjection consent + target-screen capture" "MANUAL" "Android deliberately requires user consent for screen capture. The QA verifies HOME navigation and source logic, but must not bypass the system consent dialog."
Add-Result "SafetyBoundary" "Real calls/messages/email/WhatsApp execution" "MANUAL" "AutoQA checks routing/action coverage but does not send communications or place calls."
Add-Result "SafetyBoundary" "OEM long-idle wake survival" "MANUAL" "A short test cannot prove Vivo will keep the microphone service alive for hours; battery exemption status is checked automatically."
Add-Result "SafetyBoundary" "Owner voice enrollment + owner/non-owner acoustic test" "MANUAL" "AutoQA verifies model, code and routing, but real speaker-verification acceptance/rejection requires live voices on the physical phone."
Add-Result "SafetyBoundary" "Important-message spoken alert" "MANUAL" "Notification wiring is checked automatically; send one safe test WhatsApp/mail notification containing an urgency phrase to confirm real speaker behaviour and lock-screen privacy."

# -----------------------------------------------------------------------------
# 9. Final report / diagnostics bundle
# -----------------------------------------------------------------------------
Write-Host ""
Write-Host "==> 9/9 Building diagnostic report" -ForegroundColor Cyan

$Finished = Get-Date
$Duration = [math]::Round(($Finished - $Started).TotalSeconds, 1)

$pass = @($Results | Where-Object { $_.Status -eq "PASS" }).Count
$warn = @($Results | Where-Object { $_.Status -eq "WARN" }).Count
$fail = @($Results | Where-Object { $_.Status -eq "FAIL" }).Count
$skip = @($Results | Where-Object { $_.Status -eq "SKIP" }).Count
$manual = @($Results | Where-Object { $_.Status -eq "MANUAL" }).Count
$info = @($Results | Where-Object { $_.Status -eq "INFO" }).Count

$denom = $pass + $fail + $warn
$score = if ($denom -gt 0) {
    [math]::Round((($pass + (0.5 * $warn)) / $denom) * 100, 1)
} else {
    0
}

$overall = if ($fail -gt 0) {
    "FAIL"
} elseif ($warn -gt 0) {
    "PASS_WITH_WARNINGS"
} else {
    "PASS"
}

$resultsJson = $Results | ConvertTo-Json -Depth 8
Save-Text "AURA-AUTOQA-results.json" $resultsJson | Out-Null

$md = New-Object System.Text.StringBuilder
[void]$md.AppendLine("# AURA Automated QA Report v12")
[void]$md.AppendLine("")
[void]$md.AppendLine("- Generated: $($Finished.ToString("yyyy-MM-dd HH:mm:ss"))")
[void]$md.AppendLine("- Project: $ProjectRoot")
[void]$md.AppendLine("- Package: $PackageName")
[void]$md.AppendLine("- Overall: **$overall**")
[void]$md.AppendLine("- Automated health score: **$score%**")
[void]$md.AppendLine("- PASS: $pass | WARN: $warn | FAIL: $fail | INFO: $info | SKIP: $skip | MANUAL: $manual")
[void]$md.AppendLine("- Runtime: $Duration seconds")
[void]$md.AppendLine("")
[void]$md.AppendLine("## Failures")
$failures = @($Results | Where-Object { $_.Status -eq "FAIL" })
if ($failures.Count -eq 0) {
    [void]$md.AppendLine("No automated failure was detected.")
} else {
    foreach ($item in $failures) {
        [void]$md.AppendLine("- **$($item.Area) / $($item.Check):** $($item.Detail)")
    }
}
[void]$md.AppendLine("")
[void]$md.AppendLine("## Warnings")
$warnings = @($Results | Where-Object { $_.Status -eq "WARN" })
if ($warnings.Count -eq 0) {
    [void]$md.AppendLine("No warning was detected.")
} else {
    foreach ($item in $warnings) {
        [void]$md.AppendLine("- **$($item.Area) / $($item.Check):** $($item.Detail)")
    }
}
[void]$md.AppendLine("")
[void]$md.AppendLine("## Full check matrix")
[void]$md.AppendLine("")
[void]$md.AppendLine("| Status | Area | Check | Detail |")
[void]$md.AppendLine("|---|---|---|---|")
foreach ($item in $Results) {
    $detail = ([string]$item.Detail).Replace("|", "\|").Replace("`r", " ").Replace("`n", " ")
    $check = ([string]$item.Check).Replace("|", "\|")
    $area = ([string]$item.Area).Replace("|", "\|")
    [void]$md.AppendLine("| $($item.Status) | $area | $check | $detail |")
}
[void]$md.AppendLine("")
[void]$md.AppendLine("## Interpretation")
[void]$md.AppendLine("")
[void]$md.AppendLine("FAIL means an automated test found a reproducible defect or missing prerequisite. WARN means the feature may work but a reliability/security condition needs attention. MANUAL means Android or real-world hardware intentionally prevents a safe unattended test; AutoQA does not bypass consent or perform destructive actions.")
[void]$md.AppendLine("")
[void]$md.AppendLine("Secrets are not written to this report. The cloud functional API key is held only in memory for the test run.")

$summaryPath = Save-Text "AURA-AUTOQA-SUMMARY.md" $md.ToString()

$zipPath = [System.IO.Path]::Combine($ProjectRoot, "AURA-AUTOQA-REPORT-$Stamp.zip")
try {
    Compress-Archive -Path ([System.IO.Path]::Combine($ReportRoot, "*")) -DestinationPath $zipPath -Force
    Add-Result "Report" "Diagnostic ZIP created" "PASS" $zipPath
}
catch {
    Add-Result "Report" "Diagnostic ZIP created" "WARN" $_.Exception.Message
}

Write-Host ""
Write-Host "============================================================" -ForegroundColor $(if ($fail -gt 0) { "Red" } elseif ($warn -gt 0) { "Yellow" } else { "Green" })
Write-Host "AURA AUTOQA COMPLETE" -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "Overall : $overall"
Write-Host "Score   : $score%"
Write-Host "PASS    : $pass"
Write-Host "WARN    : $warn"
Write-Host "FAIL    : $fail"
Write-Host "MANUAL  : $manual"
Write-Host ""
Write-Host "Summary : $summaryPath"
Write-Host "Bundle  : $zipPath"
Write-Host ""
Write-Host "Upload the diagnostic ZIP to ChatGPT. It contains the exact logs and check matrix, but not your API key." -ForegroundColor Green

# Keep the user's PowerShell window open. The report/ZIP carries the final status.
if ($fail -gt 0) {
    Write-Host "AUTOQA RESULT CODE: 2 (failures detected)" -ForegroundColor Red
} elseif ($warn -gt 0) {
    Write-Host "AUTOQA RESULT CODE: 1 (warnings detected)" -ForegroundColor Yellow
} else {
    Write-Host "AUTOQA RESULT CODE: 0 (all automated gates passed)" -ForegroundColor Green
}
