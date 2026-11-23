[CmdletBinding()]
param(
    [string]$ProjectRoot = "E:\MY PROJECT FOLDER\AURA PROJECT\AURA-Butler-Android-Studio-v0.4.0-Native-Stable",
    [string]$PackageName = "in.diganta.aurabutler",
    [int]$Trials = 5,
    [int]$WaitForDeviceSeconds = 90
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

function Invoke-Adb([string[]]$Args, [switch]$AllowFailure) {
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $script:AdbExe
    $psi.Arguments = (($Args | ForEach-Object {
        if ($_ -match '[\s"]') { '"' + ($_ -replace '"','\"') + '"' } else { $_ }
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
        throw "ADB failed ($($p.ExitCode)): $text"
    }
    return $text
}

function Get-DeviceRows {
    # Use PowerShell's native command output directly. Each ADB output line is
    # returned as a separate string, avoiding CR/LF parsing differences in
    # Windows PowerShell 5.1.
    $rawLines = @(& $script:AdbExe devices -l 2>&1)

    $list = New-Object System.Collections.ArrayList

    foreach ($lineObject in $rawLines) {
        $line = [string]$lineObject
        if ($line -match '^(\S+)\s+(device|unauthorized|offline)(?:\s+(.*))?$') {
            $extra = ""
            if ($matches.Count -ge 4 -and $matches[3]) {
                $extra = [string]$matches[3]
            }

            [void]$list.Add([pscustomobject]@{
                Serial = [string]$matches[1]
                State  = [string]$matches[2]
                Extra  = $extra.Trim()
            })
        }
    }

    return @($list)
}

function Wait-ForSingleAuthorizedDevice {
    $deadline = (Get-Date).AddSeconds($WaitForDeviceSeconds)

    while ((Get-Date) -lt $deadline) {
        $rows = @(Get-DeviceRows)
        $authorized = @($rows | Where-Object { $_.State -eq "device" })
        $unauthorized = @($rows | Where-Object { $_.State -eq "unauthorized" })
        $offline = @($rows | Where-Object { $_.State -eq "offline" })

        if ($rows.Count -gt 0) {
            foreach ($r in $rows) {
                if ($null -eq $r -or -not ($r.PSObject.Properties.Name -contains "State")) {
                    Write-Host "DEBUG: Unexpected ADB row object:" -ForegroundColor Red
                    $r | Format-List * | Out-Host
                }
            }
        }

        if ($authorized.Count -eq 1 -and $unauthorized.Count -eq 0 -and $offline.Count -eq 0) {
            return $authorized[0]
        }

        Clear-Host
        Section "WAITING FOR VIVO PHONE"
        Write-Host "ADB: $script:AdbExe"
        Write-Host ""

        if ($rows.Count -eq 0) {
            Write-Host "No Android phone detected." -ForegroundColor Yellow
            Write-Host "Do this:"
            Write-Host "  1. Connect the Vivo with a DATA USB cable."
            Write-Host "  2. Set USB mode to File Transfer / MTP."
            Write-Host "  3. Keep Developer options > USB debugging ON."
            Write-Host "  4. Unlock the phone."
        } else {
            foreach ($row in $rows) {
                $color = if ($row.State -eq "device") { "Green" } elseif ($row.State -eq "unauthorized") { "Yellow" } else { "Red" }
                Write-Host ("{0}  {1}  {2}" -f $row.Serial,$row.State,$row.Extra) -ForegroundColor $color
            }

            if ($unauthorized.Count -gt 0) {
                Write-Host ""
                Write-Host "Phone is UNAUTHORIZED." -ForegroundColor Yellow
                Write-Host 'Unlock the Vivo and tap "Allow" on "Allow USB debugging?".'
                Write-Host 'If the popup does not appear: Developer options > Revoke USB debugging authorizations, reconnect USB.'
            }

            if ($offline.Count -gt 0) {
                Write-Host ""
                Write-Host "Phone is OFFLINE. Reconnect USB and restart ADB." -ForegroundColor Yellow
            }

            if ($authorized.Count -gt 1) {
                Write-Host ""
                Write-Host "More than one authorized Android device is connected." -ForegroundColor Yellow
                Write-Host "Disconnect emulator/other phones."
            }
        }

        Write-Host ""
        Write-Host "Waiting... Ctrl+C to cancel."
        Start-Sleep -Seconds 3
    }

    throw "No single authorized Android device became available within $WaitForDeviceSeconds seconds."
}


function Test-InstalledPackage {
    param([Parameter(Mandatory = $true)][string]$Name)

    $lines = @(& $script:AdbExe -s $script:Serial shell pm path $Name 2>&1)
    $joined = ($lines | ForEach-Object { [string]$_ }) -join "`n"

    return (
        ($LASTEXITCODE -eq 0) -and
        ($joined -match '(?m)^package:')
    )
}

function Resolve-AuraPackage {
    param([string]$Preferred)

    Write-Host ""
    Write-Host "Resolving installed AURA package..." -ForegroundColor Cyan

    if ($Preferred -and (Test-InstalledPackage -Name $Preferred)) {
        Write-Host "Found preferred package: $Preferred" -ForegroundColor Green
        return $Preferred
    }

    # Ask Android Package Manager directly. This is much more reliable than
    # assuming the Gradle namespace/applicationId is identical to the installed APK.
    $raw = @(& $script:AdbExe -s $script:Serial shell pm list packages 2>&1)
    $packages = @(
        $raw |
        ForEach-Object { [string]$_ } |
        Where-Object { $_ -match '^package:' } |
        ForEach-Object { ($_ -replace '^package:', '').Trim() } |
        Where-Object {
            $_ -match '(?i)aurabutler|diganta.*aura|aura.*butler'
        } |
        Sort-Object -Unique
    )

    if ($packages.Count -eq 1) {
        Write-Host "Auto-detected AURA package: $($packages[0])" -ForegroundColor Green
        return [string]$packages[0]
    }

    if ($packages.Count -gt 1) {
        Write-Host "Multiple AURA-like packages were found:" -ForegroundColor Yellow
        for ($i = 0; $i -lt $packages.Count; $i++) {
            Write-Host ("  [{0}] {1}" -f ($i + 1), $packages[$i])
        }

        for ($attempt = 0; $attempt -lt 3; $attempt++) {
            $answer = Read-Host "Enter the number for the AURA app you are testing"
            $index = 0
            if ([int]::TryParse($answer, [ref]$index)) {
                if ($index -ge 1 -and $index -le $packages.Count) {
                    return [string]$packages[$index - 1]
                }
            }
            Write-Host "Invalid selection." -ForegroundColor Yellow
        }

        throw "Could not select an installed AURA package."
    }

    # Save the full package list for troubleshooting and show likely user apps.
    $allPackageText = ($raw | ForEach-Object { [string]$_ }) -join "`r`n"
    $packageDumpPath = Join-Path $ProjectRoot "AURA-installed-packages.txt"
    [IO.File]::WriteAllText(
        $packageDumpPath,
        $allPackageText,
        (New-Object Text.UTF8Encoding($false))
    )

    Write-Host ""
    Write-Host "No AURA-like package name was auto-detected." -ForegroundColor Red
    Write-Host "Full package list saved to:" -ForegroundColor Yellow
    Write-Host "  $packageDumpPath"
    Write-Host ""
    Write-Host "Run this command to inspect likely names:" -ForegroundColor Cyan
    Write-Host ('  & "{0}" -s {1} shell pm list packages | Select-String -Pattern "aura|diganta|butler"' -f $script:AdbExe,$script:Serial)
    throw "AURA package could not be auto-detected."
}

function Marker([string]$Text) {
    [void](Invoke-Adb @("-s",$script:Serial,"shell","log","-t","AURA_OWNER_CAL",$Text) -AllowFailure)
}

Section "AURA OWNER-VOICE CALIBRATION v5"
Write-Host "This script does NOT modify AURA, your voice profile, or source code."

if (-not (Test-Path -LiteralPath $ProjectRoot -PathType Container)) {
    throw "Project not found: $ProjectRoot"
}

$script:AdbExe = Find-Adb
if (-not $script:AdbExe) { throw "adb.exe not found." }

[void](Invoke-Adb @("start-server") -AllowFailure)
Start-Sleep -Milliseconds 500

$device = Wait-ForSingleAuthorizedDevice
$script:Serial = $device.Serial

Clear-Host
Section "DEVICE READY"
Write-Host "Serial: $($device.Serial)" -ForegroundColor Green
Write-Host "State : $($device.State)" -ForegroundColor Green
Write-Host "Info  : $($device.Extra)"

$PackageName = Resolve-AuraPackage -Preferred $PackageName

$pkgDumpLines = @(& $script:AdbExe -s $script:Serial shell dumpsys package $PackageName 2>&1)
$pkgDump = ($pkgDumpLines | ForEach-Object { [string]$_ }) -join "`n"

$versionMatch = [regex]::Match($pkgDump,'versionName=([^\s]+)')
if ($versionMatch.Success) {
    $version = $versionMatch.Groups[1].Value
} else {
    $version = "(versionName not exposed by dumpsys)"
}
Write-Host "Package: $PackageName" -ForegroundColor Green
Write-Host "AURA   : $version" -ForegroundColor Green

if ($pkgDump -match 'android\.permission\.RECORD_AUDIO:\s+granted=true') {
    Write-Host "Mic   : granted" -ForegroundColor Green
} else {
    Write-Host "Mic   : RECORD_AUDIO not confirmed as granted" -ForegroundColor Red
    throw "Microphone permission must be granted before calibration."
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
    if ($interesting.Count -gt 0) { $interesting | ForEach-Object { Write-Host $_ } }
    else { Write-Host "No obvious threshold lines found." -ForegroundColor Yellow }
} else {
    Write-Host "OwnerVoiceGuard.kt not found." -ForegroundColor Yellow
}

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$reportDir = Join-Path $ProjectRoot ("AURA-OWNER-CAL-" + $stamp)
New-Item -ItemType Directory -Path $reportDir -Force | Out-Null

[void](Invoke-Adb @("-s",$script:Serial,"logcat","-c") -AllowFailure)

Section "LIVE CALIBRATION"
Write-Host "Keep Wake Word ON and Owner Voice Lock ON."
Write-Host "Keep phone about 30-50 cm from your mouth."
Write-Host ""
Write-Host 'For every trial say: "Aura", then "What is my battery percentage?"' -ForegroundColor Yellow
Read-Host "Press ENTER when ready"

for ($i = 1; $i -le $Trials; $i++) {
    Marker ("TRIAL_{0}_BEGIN" -f $i)
    Write-Host ""
    Write-Host ("TRIAL {0}/{1}" -f $i,$Trials) -ForegroundColor Yellow
    Write-Host 'Say: "Aura"'
    Start-Sleep -Milliseconds 800
    Write-Host 'Then: "What is my battery percentage?"'
    Read-Host "After AURA responds/rejects (or ~10 sec), press ENTER"
    Marker ("TRIAL_{0}_END" -f $i)
    Start-Sleep -Seconds 2
}

$log = Invoke-Adb @("-s",$script:Serial,"logcat","-d","-v","threadtime") -AllowFailure
$logPath = Join-Path $reportDir "logcat.txt"
[IO.File]::WriteAllText($logPath,$log,(New-Object Text.UTF8Encoding($false)))

$lines = $log -split "`r?`n"
$results = @()

for ($i=1; $i -le $Trials; $i++) {
    $beginPattern = "AURA_OWNER_CAL: TRIAL_${i}_BEGIN"
    $endPattern = "AURA_OWNER_CAL: TRIAL_${i}_END"
    $begin = -1
    $end = -1

    for ($j=0; $j -lt $lines.Count; $j++) {
        if ($begin -lt 0 -and $lines[$j].Contains($beginPattern)) { $begin = $j }
        if ($begin -ge 0 -and $lines[$j].Contains($endPattern)) { $end = $j; break }
    }

    if ($begin -ge 0) {
        if ($end -lt 0) { $end = $lines.Count-1 }
        $chunk = ($lines[$begin..$end] -join "`n")
        $wake = [regex]::Match($chunk,'Wake keyword detected:\s*([^,\r\n]+)')
        $reject = [regex]::Match($chunk,'Rejected wake command:\s*speakerScore=([0-9.Ee+-]+)')
        $score = $null
        if ($reject.Success) {
            $score = [double]::Parse($reject.Groups[1].Value,[Globalization.CultureInfo]::InvariantCulture)
        }

        $results += [pscustomobject]@{
            Trial = $i
            WakeDetected = $wake.Success
            Keyword = if ($wake.Success) { $wake.Groups[1].Value.Trim() } else { "" }
            OwnerRejected = $reject.Success
            SpeakerScore = $score
        }
    }
}

Section "RESULTS"
$results | Format-Table -AutoSize

$results | Export-Csv (Join-Path $reportDir "owner-calibration.csv") -NoTypeInformation -Encoding UTF8

$detected = @($results | Where-Object WakeDetected).Count
$rejected = @($results | Where-Object OwnerRejected).Count
$zero = @($results | Where-Object { $_.OwnerRejected -and $_.SpeakerScore -eq 0 }).Count

$summary = @"
AURA Owner Voice Calibration v5
Generated: $(Get-Date -Format "yyyy-MM-dd HH:mm:ss")
Device: $($device.Serial)
AURA: $version
Trials: $Trials
Wake detections: $detected
Owner rejections: $rejected
Zero scores: $zero
"@

[IO.File]::WriteAllText((Join-Path $reportDir "SUMMARY.txt"),$summary,(New-Object Text.UTF8Encoding($false)))

$zip = Join-Path $ProjectRoot ("AURA-OWNER-CAL-" + $stamp + ".zip")
Compress-Archive -Path (Join-Path $reportDir "*") -DestinationPath $zip -Force

Section "DONE"
Write-Host "Report ZIP:" -ForegroundColor Green
Write-Host "  $zip"
Write-Host ""
Write-Host "Send me this ZIP."
