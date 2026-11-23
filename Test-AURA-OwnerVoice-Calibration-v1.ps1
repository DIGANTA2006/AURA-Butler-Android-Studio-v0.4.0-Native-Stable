[CmdletBinding()]
param(
    [string]$ProjectRoot = "E:\MY PROJECT FOLDER\AURA PROJECT\AURA-Butler-Android-Studio-v0.4.0-Native-Stable",
    [string]$PackageName = "in.diganta.aurabutler",
    [int]$Trials = 5
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Section([string]$t) {
    Write-Host ""
    Write-Host "============================================================" -ForegroundColor Cyan
    Write-Host $t -ForegroundColor Cyan
    Write-Host "============================================================" -ForegroundColor Cyan
}

function Find-Adb {
    $candidates = @(
        "E:\AndroidSDK\platform-tools\adb.exe",
        "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
    )
    if ($env:ANDROID_SDK_ROOT) { $candidates += (Join-Path $env:ANDROID_SDK_ROOT "platform-tools\adb.exe") }
    if ($env:ANDROID_HOME) { $candidates += (Join-Path $env:ANDROID_HOME "platform-tools\adb.exe") }

    $cmd = Get-Command adb.exe -ErrorAction SilentlyContinue
    if ($cmd) { $candidates += $cmd.Source }

    return $candidates |
        Where-Object { $_ -and (Test-Path -LiteralPath $_ -PathType Leaf) } |
        Select-Object -Unique |
        Select-Object -First 1
}

function Adb([string[]]$Args, [switch]$AllowFailure) {
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $script:AdbExe
    $psi.Arguments = (($Args | ForEach-Object {
        if ($_ -match '\s') { '"' + $_ + '"' } else { $_ }
    }) -join ' ')
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true

    $p = New-Object System.Diagnostics.Process
    $p.StartInfo = $psi
    [void]$p.Start()
    $o = $p.StandardOutput.ReadToEnd()
    $e = $p.StandardError.ReadToEnd()
    $p.WaitForExit()

    $text = ($o + "`r`n" + $e).Trim()
    if (($p.ExitCode -ne 0) -and (-not $AllowFailure)) {
        throw "ADB failed: $text"
    }
    return $text
}

function Marker([string]$Text) {
    [void](Adb @("shell","log","-t","AURA_OWNER_CAL",$Text) -AllowFailure)
}

Section "AURA OWNER-VOICE CALIBRATION TEST"
Write-Host "This script DOES NOT modify AURA, your voice profile, or any source file."
Write-Host "It measures the owner verification scores that AURA already produces."
Write-Host ""

if (-not (Test-Path -LiteralPath $ProjectRoot -PathType Container)) {
    throw "Project not found: $ProjectRoot"
}

$script:AdbExe = Find-Adb
if (-not $script:AdbExe) { throw "adb.exe not found." }

Write-Host "ADB: $script:AdbExe" -ForegroundColor Green

$devices = Adb @("devices") -AllowFailure
$connected = @($devices -split "`r?`n" | Where-Object { $_ -match '^\S+\s+device\s*$' })
if ($connected.Count -ne 1) {
    throw "Connect exactly one authorized Android phone."
}

$ownerPath = Join-Path $ProjectRoot "android\app\src\main\java\in\diganta\aurabutler\voice\OwnerVoiceGuard.kt"

Section "OWNER VOICE SOURCE CHECK"
if (Test-Path -LiteralPath $ownerPath) {
    $lines = Get-Content -LiteralPath $ownerPath
    $interesting = @(
        for ($i = 0; $i -lt $lines.Count; $i++) {
            if ($lines[$i] -match '(?i)threshold|similarity|cosine|score|verify|minimum|accept') {
                "{0,4}: {1}" -f ($i + 1), $lines[$i]
            }
        }
    )

    if ($interesting.Count -gt 0) {
        $interesting | ForEach-Object { Write-Host $_ }
    } else {
        Write-Host "No obvious threshold/score lines found by text scan." -ForegroundColor Yellow
    }
} else {
    Write-Host "OwnerVoiceGuard.kt not found." -ForegroundColor Yellow
}

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$reportDir = Join-Path $ProjectRoot ("AURA-OWNER-CAL-" + $stamp)
New-Item -ItemType Directory -Path $reportDir -Force | Out-Null

[void](Adb @("logcat","-c") -AllowFailure)

Section "LIVE CALIBRATION"
Write-Host "Make sure:"
Write-Host " - Wake listening is ON"
Write-Host " - Owner Voice Lock is ON"
Write-Host " - Phone is about 30-50 cm from your mouth"
Write-Host " - Room is reasonably quiet"
Write-Host ""
Write-Host "For every trial, say the SAME phrase after AURA detects the wake word:"
Write-Host '  "What is my battery percentage?"' -ForegroundColor Yellow
Write-Host ""
Read-Host "Press ENTER when ready"

$trialMeta = @()

for ($i = 1; $i -le $Trials; $i++) {
    Marker ("TRIAL_{0}_BEGIN" -f $i)

    Write-Host ""
    Write-Host ("TRIAL {0}/{1}" -f $i, $Trials) -ForegroundColor Yellow
    Write-Host 'Say clearly: "Aura"'
    Start-Sleep -Milliseconds 700
    Write-Host 'Then say: "What is my battery percentage?"'
    Write-Host "Wait until AURA either responds/rejects or about 10 seconds pass."
    Read-Host "Press ENTER when this trial is finished"

    Marker ("TRIAL_{0}_END" -f $i)
    Start-Sleep -Seconds 2
}

$log = Adb @("logcat","-d","-v","threadtime") -AllowFailure
$logPath = Join-Path $reportDir "logcat.txt"
[IO.File]::WriteAllText($logPath, $log, (New-Object Text.UTF8Encoding($false)))

$lines = $log -split "`r?`n"
$results = New-Object System.Collections.Generic.List[object]

for ($i = 1; $i -le $Trials; $i++) {
    $beginPattern = "AURA_OWNER_CAL: TRIAL_${i}_BEGIN"
    $endPattern   = "AURA_OWNER_CAL: TRIAL_${i}_END"

    $begin = -1
    $end = -1

    for ($j=0; $j -lt $lines.Count; $j++) {
        if (($begin -lt 0) -and $lines[$j].Contains($beginPattern)) { $begin = $j }
        if (($begin -ge 0) -and $lines[$j].Contains($endPattern)) { $end = $j; break }
    }

    if ($begin -ge 0) {
        if ($end -lt 0) { $end = $lines.Count - 1 }
        $chunk = ($lines[$begin..$end] -join "`n")

        $wake = [regex]::Match($chunk, 'Wake keyword detected:\s*([^,\r\n]+)')
        $reject = [regex]::Match($chunk, 'Rejected wake command:\s*speakerScore=([0-9.Ee+-]+)')
        $fatal = ($chunk -match 'FATAL EXCEPTION|ANR in\s+in\.diganta\.aurabutler')

        $detected = $wake.Success
        $keyword = if ($wake.Success) { $wake.Groups[1].Value.Trim() } else { "" }
        $rejected = $reject.Success
        $score = $null
        if ($reject.Success) {
            $score = [double]::Parse(
                $reject.Groups[1].Value,
                [Globalization.CultureInfo]::InvariantCulture
            )
        }

        $results.Add([pscustomobject]@{
            Trial = $i
            WakeDetected = $detected
            Keyword = $keyword
            OwnerRejected = $rejected
            SpeakerScore = $score
            CrashOrANR = $fatal
        })
    }
}

Section "RESULTS"
$results | Format-Table -AutoSize

$csv = Join-Path $reportDir "owner-calibration.csv"
$results | Export-Csv -LiteralPath $csv -NoTypeInformation -Encoding UTF8

$detectedCount = @($results | Where-Object WakeDetected).Count
$rejects = @($results | Where-Object OwnerRejected)
$nonZeroScores = @(
    $rejects |
    Where-Object { $null -ne $_.SpeakerScore -and $_.SpeakerScore -gt 0 } |
    ForEach-Object { [double]$_.SpeakerScore }
)
$zeroScores = @(
    $rejects |
    Where-Object { $null -ne $_.SpeakerScore -and $_.SpeakerScore -eq 0 }
).Count

Write-Host ""
Write-Host "Wake detections : $detectedCount / $Trials"
Write-Host "Owner rejections: $($rejects.Count) / $Trials"
Write-Host "Zero scores     : $zeroScores"

if ($nonZeroScores.Count -gt 0) {
    $avg = ($nonZeroScores | Measure-Object -Average).Average
    $min = ($nonZeroScores | Measure-Object -Minimum).Minimum
    $max = ($nonZeroScores | Measure-Object -Maximum).Maximum
    Write-Host ("Non-zero score avg/min/max: {0:N4} / {1:N4} / {2:N4}" -f $avg,$min,$max)
}

Write-Host ""
if ($detectedCount -ge [math]::Ceiling($Trials * 0.8) -and $rejects.Count -ge [math]::Ceiling($Trials * 0.6)) {
    Write-Host "DIAGNOSIS: KWS is working, but OwnerVoiceGuard is rejecting the enrolled owner." -ForegroundColor Red
    Write-Host "Likely causes: poor/mismatched enrollment, overly strict threshold, or command-sample capture/VAD quality."
}
elseif ($detectedCount -lt [math]::Ceiling($Trials * 0.6)) {
    Write-Host "DIAGNOSIS: Wake-word detection itself is inconsistent." -ForegroundColor Red
}
elseif ($zeroScores -ge 2) {
    Write-Host "DIAGNOSIS: Multiple 0.0 scores indicate unusable/too-short owner-verification samples." -ForegroundColor Red
}
else {
    Write-Host "DIAGNOSIS: Mixed result. Send the report folder/ZIP for exact analysis." -ForegroundColor Yellow
}

$summary = @"
AURA Owner Voice Calibration
Generated: $(Get-Date -Format "yyyy-MM-dd HH:mm:ss")
Installed package: $PackageName
Trials: $Trials
Wake detections: $detectedCount
Owner rejections: $($rejects.Count)
Zero scores: $zeroScores
"@

if ($nonZeroScores.Count -gt 0) {
    $avg = ($nonZeroScores | Measure-Object -Average).Average
    $min = ($nonZeroScores | Measure-Object -Minimum).Minimum
    $max = ($nonZeroScores | Measure-Object -Maximum).Maximum
    $summary += "`r`nAverage non-zero score: $avg`r`nMinimum: $min`r`nMaximum: $max"
}

[IO.File]::WriteAllText(
    (Join-Path $reportDir "SUMMARY.txt"),
    $summary,
    (New-Object Text.UTF8Encoding($false))
)

$zip = Join-Path $ProjectRoot ("AURA-OWNER-CAL-" + $stamp + ".zip")
Compress-Archive -Path (Join-Path $reportDir "*") -DestinationPath $zip -Force

Section "DONE"
Write-Host "Report ZIP:" -ForegroundColor Green
Write-Host "  $zip"
Write-Host ""
Write-Host "Send me this ZIP. Do not lower the owner threshold yet; first calibrate it from your real scores." -ForegroundColor Yellow
