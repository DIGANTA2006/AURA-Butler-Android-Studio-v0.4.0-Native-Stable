[CmdletBinding()]
param(
    [string]$ProjectRoot = "E:\MY PROJECT FOLDER\AURA PROJECT\AURA-Butler-Android-Studio-v0.4.0-Native-Stable",
    [string]$PackageName = "in.diganta.aurabutler",
    [int]$SettleSeconds = 4,
    [switch]$SkipSourceAudit
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Write-Section {
    param([Parameter(Mandatory = $true)][string]$Text)
    Write-Host ""
    Write-Host ("============================================================") -ForegroundColor Cyan
    Write-Host $Text -ForegroundColor Cyan
    Write-Host ("============================================================") -ForegroundColor Cyan
}

function Write-Pass {
    param([string]$Text)
    Write-Host ("PASS: " + $Text) -ForegroundColor Green
}

function Write-Warn {
    param([string]$Text)
    Write-Host ("WARN: " + $Text) -ForegroundColor Yellow
}

function Write-Fail {
    param([string]$Text)
    Write-Host ("FAIL: " + $Text) -ForegroundColor Red
}

function Save-Text {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [AllowEmptyString()][string]$Content
    )
    $path = Join-Path $ReportRoot $Name
    [System.IO.File]::WriteAllText(
        $path,
        [string]$Content,
        (New-Object System.Text.UTF8Encoding($false))
    )
    return $path
}

function Find-Adb {
    $candidates = New-Object System.Collections.Generic.List[string]

    $localProperties = Join-Path $ProjectRoot "android\local.properties"
    if (Test-Path -LiteralPath $localProperties -PathType Leaf) {
        $sdkLine = Get-Content -LiteralPath $localProperties |
            Where-Object { $_ -match '^\s*sdk\.dir\s*=' } |
            Select-Object -First 1

        if ($sdkLine) {
            $sdkRoot = ($sdkLine -replace '^\s*sdk\.dir\s*=', '').Trim()
            $sdkRoot = $sdkRoot -replace '\\:', ':'
            $sdkRoot = $sdkRoot -replace '\\\\', '\'
            if ($sdkRoot) {
                $candidates.Add((Join-Path $sdkRoot "platform-tools\adb.exe"))
            }
        }
    }

    foreach ($root in @(
        $env:ANDROID_SDK_ROOT,
        $env:ANDROID_HOME,
        "E:\AndroidSDK",
        "$env:LOCALAPPDATA\Android\Sdk"
    )) {
        if ($root) {
            $candidates.Add((Join-Path $root "platform-tools\adb.exe"))
        }
    }

    $adbCommand = Get-Command adb.exe -ErrorAction SilentlyContinue
    if ($adbCommand) {
        $candidates.Add($adbCommand.Source)
    }

    return $candidates |
        Where-Object { $_ -and (Test-Path -LiteralPath $_ -PathType Leaf) } |
        Select-Object -Unique |
        Select-Object -First 1
}

function Invoke-Adb {
    param(
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [switch]$AllowFailure
    )

    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $Adb
    $psi.Arguments = (($Arguments | ForEach-Object {
        if ($_ -match '[\s"]') {
            '"' + ($_ -replace '"', '\"') + '"'
        } else {
            $_
        }
    }) -join ' ')
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true

    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $psi
    [void]$process.Start()
    $stdout = $process.StandardOutput.ReadToEnd()
    $stderr = $process.StandardError.ReadToEnd()
    $process.WaitForExit()

    $text = (($stdout + [Environment]::NewLine + $stderr).Trim())

    if (($process.ExitCode -ne 0) -and (-not $AllowFailure)) {
        throw "ADB failed ($($process.ExitCode)): adb $($psi.Arguments)`r`n$text"
    }

    return [pscustomobject]@{
        ExitCode = $process.ExitCode
        Text = $text
    }
}

function Get-AppPid {
    $result = Invoke-Adb -Arguments @("shell", "pidof", $PackageName) -AllowFailure
    $text = $result.Text.Trim()
    if ($text -match '^\d+(\s+\d+)*$') {
        return ($text -split '\s+')[0]
    }
    return $null
}

function Get-ServiceSnapshot {
    param([string]$Label)

    $services = (Invoke-Adb -Arguments @(
        "shell", "dumpsys", "activity", "services", $PackageName
    ) -AllowFailure).Text

    Save-Text -Name ("services-" + $Label + ".txt") -Content $services | Out-Null

    return [pscustomobject]@{
        Present = ($services -match 'WakeWordService')
        Text = $services
    }
}

function Get-AudioSnapshot {
    param([string]$Label)

    $audio = (Invoke-Adb -Arguments @("shell", "dumpsys", "audio") -AllowFailure).Text
    Save-Text -Name ("audio-" + $Label + ".txt") -Content $audio | Out-Null

    $appops = (Invoke-Adb -Arguments @(
        "shell", "cmd", "appops", "get", $PackageName, "RECORD_AUDIO"
    ) -AllowFailure).Text
    Save-Text -Name ("appops-record-audio-" + $Label + ".txt") -Content $appops | Out-Null
}

function Get-VersionSnapshot {
    $pkg = (Invoke-Adb -Arguments @(
        "shell", "dumpsys", "package", $PackageName
    ) -AllowFailure).Text

    Save-Text -Name "package.txt" -Content $pkg | Out-Null

    $versionName = ""
    $versionCode = ""

    $m1 = [regex]::Match($pkg, 'versionName=([^\r\n\s]+)')
    if ($m1.Success) { $versionName = $m1.Groups[1].Value }

    $m2 = [regex]::Match($pkg, 'versionCode=(\d+)')
    if ($m2.Success) { $versionCode = $m2.Groups[1].Value }

    return [pscustomobject]@{
        VersionName = $versionName
        VersionCode = $versionCode
    }
}

function Start-LogcatCapture {
    param([string]$OutputPath)

    # Clear only the current logcat buffers. This does not alter AURA data.
    [void](Invoke-Adb -Arguments @("logcat", "-c") -AllowFailure)

    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $Adb
    $psi.Arguments = "logcat -v threadtime"
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true

    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $psi
    [void]$process.Start()

    $outTask = $process.StandardOutput.ReadToEndAsync()
    $errTask = $process.StandardError.ReadToEndAsync()

    return [pscustomobject]@{
        Process = $process
        StdOutTask = $outTask
        StdErrTask = $errTask
        OutputPath = $OutputPath
    }
}

function Stop-LogcatCapture {
    param($Capture)

    if (-not $Capture) { return }

    try {
        if (-not $Capture.Process.HasExited) {
            $Capture.Process.Kill()
            $Capture.Process.WaitForExit(3000)
        }
    }
    catch {
    }

    $stdout = ""
    $stderr = ""

    try { $stdout = $Capture.StdOutTask.GetAwaiter().GetResult() } catch {}
    try { $stderr = $Capture.StdErrTask.GetAwaiter().GetResult() } catch {}

    [System.IO.File]::WriteAllText(
        $Capture.OutputPath,
        ($stdout + [Environment]::NewLine + $stderr),
        (New-Object System.Text.UTF8Encoding($false))
    )
}

function New-PhaseMarker {
    param([Parameter(Mandatory = $true)][string]$Text)

    # Android log marker makes the reproduction phases easy to locate.
    [void](Invoke-Adb -Arguments @(
        "shell", "log", "-t", "AURA_REARM_TEST", $Text
    ) -AllowFailure)
}

function Audit-Source {
    Write-Section "STATIC SOURCE AUDIT"

    $kotlinRoot = Join-Path $ProjectRoot "android\app\src\main\java\in\diganta\aurabutler"
    $wakePath = Join-Path $kotlinRoot "background\WakeWordService.kt"
    $voiceManagerPath = Join-Path $kotlinRoot "voice\VoiceManager.kt"
    $ownerPath = Join-Path $kotlinRoot "voice\OwnerVoiceGuard.kt"
    $runtimePath = Join-Path $kotlinRoot "runtime\AuraRuntimeState.kt"

    $sourceReport = New-Object System.Collections.Generic.List[string]

    foreach ($entry in @(
        @("WakeWordService.kt", $wakePath),
        @("VoiceManager.kt", $voiceManagerPath),
        @("OwnerVoiceGuard.kt", $ownerPath),
        @("AuraRuntimeState.kt", $runtimePath)
    )) {
        if (Test-Path -LiteralPath $entry[1]) {
            Write-Pass "$($entry[0]) found"
            $sourceReport.Add("PASS $($entry[0]) $($entry[1])")
        }
        else {
            Write-Warn "$($entry[0]) missing"
            $sourceReport.Add("WARN $($entry[0]) missing: $($entry[1])")
        }
    }

    if (Test-Path -LiteralPath $wakePath) {
        $wake = [System.IO.File]::ReadAllText($wakePath)

        $checks = [ordered]@{
            "Wake loop exists" = ($wake -match 'listeningLoop')
            "Owner voice is wired into wake path" = ($wake -match 'OwnerVoiceGuard\.verify')
            "Recorder release path exists" = ($wake -match 'releaseRecorder')
            "Wake continuation guard exists" = ($wake -match 'shouldKeepListening')
            "Service tracks running state" = ($wake -match 'var\s+running\s*=')
            "Service exposes wake state diagnostics" = (
                ($wake -match 'markServiceState') -or
                ($wake -match 'VoiceDiagnostics\.mark')
            )
        }

        foreach ($name in $checks.Keys) {
            if ($checks[$name]) {
                Write-Pass $name
                $sourceReport.Add("PASS $name")
            }
            else {
                Write-Warn $name
                $sourceReport.Add("WARN $name")
            }
        }

        # This is intentionally only a suspicion marker, not an automatic patch.
        if (
            ($wake -match 'running\s*=\s*false') -and
            ($wake -match 'stopSelf\(\)') -and
            ($wake -match 'shouldKeepListening')
        ) {
            $sourceReport.Add(
                "INFO WakeWordService contains both stop paths and continuation guards. " +
                "The live test must determine whether it exits after turn 1."
            )
        }
    }

    if (Test-Path -LiteralPath $runtimePath) {
        $runtime = [System.IO.File]::ReadAllText($runtimePath)
        if ($runtime -match 'forceIdleUnlessListening') {
            Write-Pass "Runtime has forceIdleUnlessListening protection"
            $sourceReport.Add("PASS forceIdleUnlessListening present")
        }
        else {
            Write-Warn "forceIdleUnlessListening was not found"
            $sourceReport.Add("WARN forceIdleUnlessListening missing")
        }
    }

    Save-Text -Name "source-audit.txt" -Content ($sourceReport -join "`r`n") | Out-Null
}

function Analyze-Log {
    param(
        [Parameter(Mandatory = $true)][string]$LogPath,
        [Parameter(Mandatory = $true)]$AfterFirstService,
        [Parameter(Mandatory = $true)]$AfterSecondService
    )

    Write-Section "AUTOMATED DIAGNOSIS"

    $log = ""
    if (Test-Path -LiteralPath $LogPath) {
        $log = [System.IO.File]::ReadAllText($LogPath)
    }

    $interestingLines = @(
        $log -split "`r?`n" |
        Where-Object {
            $_ -match 'AURA_REARM_TEST|' +
                'in\.diganta\.aurabutler|' +
                'AuraWake|WakeWord|KeywordSpotter|OwnerVoice|' +
                'VoiceDiagnostics|AudioRecord|microphone|' +
                'CAPTUR|TRANSCRIB|LISTEN|SPEAK|IDLE|' +
                'SecurityException|FATAL EXCEPTION|ANR'
        }
    )

    Save-Text -Name "interesting-logcat.txt" -Content ($interestingLines -join "`r`n") | Out-Null

    $findings = New-Object System.Collections.Generic.List[string]

    if (-not $AfterFirstService.Present) {
        $findings.Add(
            "HIGH: WakeWordService is absent after the FIRST voice turn. " +
            "This strongly suggests the service stopped instead of re-arming."
        )
    }
    else {
        $findings.Add(
            "PASS: WakeWordService is still present after the first voice turn."
        )
    }

    if (-not $AfterSecondService.Present) {
        $findings.Add(
            "HIGH: WakeWordService is absent after the SECOND test phase."
        )
    }
    else {
        $findings.Add(
            "PASS: WakeWordService is present after the second test phase."
        )
    }

    $audioErrors = [regex]::Matches(
        $log,
        '(?im)^.*(?:AudioRecord|microphone).*(?:error|fail|denied|dead|invalid|exception).*$'
    )
    if ($audioErrors.Count -gt 0) {
        $findings.Add(
            "HIGH: AudioRecord/microphone error text was detected. " +
            "Suspect microphone handoff from owner verification/Whisper/TTS back to KWS."
        )
    }

    $ownerReject = [regex]::Matches(
        $log,
        '(?im)^.*OwnerVoice.*(?:reject|denied|mismatch|failed|not owner).*$'
    )
    if ($ownerReject.Count -gt 0) {
        $findings.Add(
            "HIGH: OwnerVoice rejection/failure text was detected. " +
            "The second wake may be reaching voice lock but failing owner verification."
        )
    }

    $fatal = [regex]::Matches(
        $log,
        'FATAL EXCEPTION|ANR in\s+in\.diganta\.aurabutler'
    )
    if ($fatal.Count -gt 0) {
        $findings.Add(
            "CRITICAL: AURA crash/ANR marker found in the reproduction log."
        )
    }

    $phase2 = $log.IndexOf("AURA_REARM_TEST: SECOND_WAKE_BEGIN")
    $phase2End = $log.IndexOf("AURA_REARM_TEST: SECOND_WAKE_END")

    $secondChunk = ""
    if ($phase2 -ge 0) {
        if ($phase2End -gt $phase2) {
            $secondChunk = $log.Substring($phase2, $phase2End - $phase2)
        }
        else {
            $secondChunk = $log.Substring($phase2)
        }
    }

    $secondWakeEvidence = (
        $secondChunk -match '(?i)wake.*(?:detect|recogn|trigger)' -or
        $secondChunk -match '(?i)keyword.*(?:detect|recogn|trigger)' -or
        $secondChunk -match '(?i)@AURA|HEY_AURA|OK_AURA'
    )

    if ($AfterFirstService.Present -and (-not $secondWakeEvidence)) {
        $findings.Add(
            "SUSPECT: Service remains alive, but no obvious second wake/keyword event " +
            "was found in logcat. This is consistent with KWS not re-arming or not " +
            "reacquiring AudioRecord after turn 1. Absence of a log marker alone is " +
            "not proof, because the current build may not log every detection event."
        )
    }
    elseif ($secondWakeEvidence) {
        $findings.Add(
            "PASS: Evidence of a second wake/keyword event appears in logcat."
        )
    }

    if ($findings.Count -eq 2 -and
        $AfterFirstService.Present -and
        $AfterSecondService.Present) {
        $findings.Add(
            "INFO: Service-level checks look healthy. Review interesting-logcat.txt " +
            "to determine whether the fault is KWS recognition, owner verification, " +
            "command capture, or UI feedback."
        )
    }

    foreach ($item in $findings) {
        if ($item.StartsWith("CRITICAL") -or $item.StartsWith("HIGH")) {
            Write-Fail $item
        }
        elseif ($item.StartsWith("SUSPECT")) {
            Write-Warn $item
        }
        else {
            Write-Host $item
        }
    }

    Save-Text -Name "DIAGNOSIS.txt" -Content ($findings -join "`r`n`r`n") | Out-Null

    return $findings
}

Write-Section "AURA WAKE-WORD RE-ARM + OWNER VOICE DIAGNOSTIC"
Write-Host "This test DOES NOT patch, uninstall, clear app data, send messages, or place calls."
Write-Host "It only reads source files, uses ADB, captures logs, and asks you to reproduce two wake cycles."
Write-Host ""

if (-not (Test-Path -LiteralPath $ProjectRoot -PathType Container)) {
    throw "Project root was not found: $ProjectRoot"
}

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$ReportRoot = Join-Path $ProjectRoot ("AURA-WAKE-REARM-DIAG-" + $stamp)
New-Item -ItemType Directory -Path $ReportRoot -Force | Out-Null

$Adb = Find-Adb
if (-not $Adb) {
    throw "adb.exe was not found. Expected Android SDK platform-tools."
}
Write-Pass "ADB: $Adb"

[void](Invoke-Adb -Arguments @("start-server") -AllowFailure)

$devicesText = (Invoke-Adb -Arguments @("devices") -AllowFailure).Text
Save-Text -Name "adb-devices.txt" -Content $devicesText | Out-Null

$deviceLines = @(
    $devicesText -split "`r?`n" |
    Where-Object { $_ -match "^\S+\s+device\s*$" }
)
if ($deviceLines.Count -lt 1) {
    throw "No authorized Android device is connected. Connect the Vivo phone and accept USB debugging."
}
if ($deviceLines.Count -gt 1) {
    throw "More than one Android device is connected. Leave only the Vivo phone connected for this test."
}
Write-Pass "One authorized Android device connected"

$version = Get-VersionSnapshot
if (-not $version.VersionName) {
    throw "AURA package '$PackageName' was not found on the connected phone."
}
Write-Host "Installed AURA: versionName=$($version.VersionName) versionCode=$($version.VersionCode)"

$permission = (Invoke-Adb -Arguments @(
    "shell", "dumpsys", "package", $PackageName
) -AllowFailure).Text
if ($permission -match 'android\.permission\.RECORD_AUDIO:\s+granted=true') {
    Write-Pass "RECORD_AUDIO permission granted"
}
else {
    Write-Warn "RECORD_AUDIO grant was not confirmed in package dump"
}

$power = (Invoke-Adb -Arguments @(
    "shell", "dumpsys", "deviceidle", "whitelist"
) -AllowFailure).Text
Save-Text -Name "deviceidle-whitelist.txt" -Content $power | Out-Null

if (-not $SkipSourceAudit) {
    Audit-Source
}

Write-Section "LIVE TWO-WAKE REPRODUCTION"
Write-Host "IMPORTANT:"
Write-Host "  1. In AURA Settings, enable wake-word listening."
Write-Host "  2. Keep owner voice lock enabled exactly as you normally use it."
Write-Host "  3. Keep the phone connected by USB."
Write-Host "  4. Do NOT manually press the microphone button during this test."
Write-Host ""
Read-Host "When ready, press ENTER"

# Bring AURA to foreground without clearing any data.
[void](Invoke-Adb -Arguments @(
    "shell", "am", "start", "-n", "$PackageName/.MainActivity"
) -AllowFailure)
Start-Sleep -Seconds $SettleSeconds

$before = Get-ServiceSnapshot -Label "before"
Get-AudioSnapshot -Label "before"

if ($before.Present) {
    Write-Pass "WakeWordService is running before reproduction"
}
else {
    Write-Warn "WakeWordService is NOT visible before reproduction. Check that wake listening is enabled."
}

$logPath = Join-Path $ReportRoot "logcat-full.txt"
$capture = $null

try {
    $capture = Start-LogcatCapture -OutputPath $logPath
    Start-Sleep -Seconds 1

    New-PhaseMarker "FIRST_WAKE_BEGIN"
    Write-Host ""
    Write-Host "TEST 1" -ForegroundColor Yellow
    Write-Host 'Say: "Aura"'
    Write-Host 'After the ready signal, say: "What is my battery percentage?"'
    Write-Host "Wait until AURA COMPLETELY finishes the reply."
    Read-Host "Then press ENTER here"
    New-PhaseMarker "FIRST_WAKE_END"

    Start-Sleep -Seconds $SettleSeconds
    $afterFirst = Get-ServiceSnapshot -Label "after-first"
    Get-AudioSnapshot -Label "after-first"

    if ($afterFirst.Present) {
        Write-Pass "WakeWordService still present after first turn"
    }
    else {
        Write-Fail "WakeWordService disappeared after first turn"
    }

    New-PhaseMarker "SECOND_WAKE_BEGIN"
    Write-Host ""
    Write-Host "TEST 2 - THIS IS THE IMPORTANT ONE" -ForegroundColor Yellow
    Write-Host 'Without touching the screen or microphone button, say: "Aura" again.'
    Write-Host 'If it wakes, say: "What time is it?"'
    Write-Host "Observe whether the ready tone/listening UI appears."
    Read-Host "After about 10-15 seconds, press ENTER here"
    New-PhaseMarker "SECOND_WAKE_END"

    Start-Sleep -Seconds $SettleSeconds
    $afterSecond = Get-ServiceSnapshot -Label "after-second"
    Get-AudioSnapshot -Label "after-second"

    $appPid = Get-AppPid
    Save-Text -Name "app-pid.txt" -Content ([string]$appPid) | Out-Null

    if ($appPid) {
        Write-Pass "AURA process still alive after second test: pid=$appPid"
    }
    else {
        Write-Fail "AURA process is not alive after second test"
    }
}
finally {
    Stop-LogcatCapture -Capture $capture
}

$findings = Analyze-Log `
    -LogPath $logPath `
    -AfterFirstService $afterFirst `
    -AfterSecondService $afterSecond

Write-Section "REPORT READY"
Write-Host "Diagnostic folder:" -ForegroundColor Green
Write-Host "  $ReportRoot"
Write-Host ""

$zipPath = Join-Path $ProjectRoot ("AURA-WAKE-REARM-DIAG-" + $stamp + ".zip")
try {
    Compress-Archive -Path (Join-Path $ReportRoot "*") -DestinationPath $zipPath -Force
    Write-Pass "Diagnostic ZIP created"
    Write-Host "  $zipPath" -ForegroundColor Green
}
catch {
    Write-Warn "Could not create ZIP: $($_.Exception.Message)"
}

Write-Host ""
Write-Host "Send me the diagnostic ZIP. The most useful files are:" -ForegroundColor Cyan
Write-Host "  DIAGNOSIS.txt"
Write-Host "  interesting-logcat.txt"
Write-Host "  services-after-first.txt"
Write-Host "  services-after-second.txt"
Write-Host "  appops-record-audio-after-first.txt"
Write-Host "  appops-record-audio-after-second.txt"
Write-Host ""
Write-Host "No AURA source file was modified by this diagnostic." -ForegroundColor Green
