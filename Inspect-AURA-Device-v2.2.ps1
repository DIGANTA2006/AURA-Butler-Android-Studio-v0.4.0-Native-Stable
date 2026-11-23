[CmdletBinding()]
param(
    [string]$ProjectRoot = "E:\MY PROJECT FOLDER\AURA-Butler-Android-Studio-v0.4.0-Native-Stable",
    [switch]$OpenNotificationSettings
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Invoke-ProcessCapture {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [Parameter(Mandatory = $true)][string]$WorkingDirectory,
        [int]$TimeoutSeconds = 30
    )

    $token = [Guid]::NewGuid().ToString("N")
    $stdout = Join-Path $env:TEMP ("aura_inspect_" + $token + ".out")
    $stderr = Join-Path $env:TEMP ("aura_inspect_" + $token + ".err")

    try {
        $process = Start-Process `
            -FilePath $FilePath `
            -ArgumentList $Arguments `
            -WorkingDirectory $WorkingDirectory `
            -PassThru `
            -NoNewWindow `
            -RedirectStandardOutput $stdout `
            -RedirectStandardError $stderr

        if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
            try { $process.Kill() } catch {}
            return [pscustomobject]@{
                ExitCode = 124
                Output = ""
                Error = "Command timed out."
            }
        }

        $output = ""
        $errorText = ""

        if (Test-Path -LiteralPath $stdout -PathType Leaf) {
            $output = Get-Content -LiteralPath $stdout -Raw
        }

        if (Test-Path -LiteralPath $stderr -PathType Leaf) {
            $errorText = Get-Content -LiteralPath $stderr -Raw
        }

        return [pscustomobject]@{
            ExitCode = $process.ExitCode
            Output = [string]$output
            Error = [string]$errorText
        }
    }
    finally {
        Remove-Item -LiteralPath $stdout -Force -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath $stderr -Force -ErrorAction SilentlyContinue
    }
}

function Add-Result {
    param(
        [string]$Status,
        [string]$Check,
        [string]$Detail
    )

    $script:Results.Add(
        [pscustomobject]@{
            Status = $Status
            Check = $Check
            Detail = $Detail
        }
    ) | Out-Null

    $color = switch ($Status) {
        "PASS" { "Green" }
        "WARN" { "Yellow" }
        "FAIL" { "Red" }
        default { "Cyan" }
    }

    Write-Host (
        "[" + $Status + "] " +
        $Check +
        ": " +
        $Detail
    ) -ForegroundColor $color
}

if (-not (Test-Path -LiteralPath $ProjectRoot -PathType Container)) {
    throw "Project folder was not found: $ProjectRoot"
}

$ProjectRoot = (Resolve-Path -LiteralPath $ProjectRoot).Path
$LocalProperties = Join-Path $ProjectRoot "android\local.properties"
$ReportRoot = Join-Path $ProjectRoot "_inspection_reports"
$PackageName = "in.diganta.aurabutler"

if (-not (Test-Path -LiteralPath $LocalProperties -PathType Leaf)) {
    throw "android\local.properties was not found."
}

$sdkLine = Get-Content -LiteralPath $LocalProperties |
    Where-Object { $_ -match "^\s*sdk\.dir\s*=" } |
    Select-Object -First 1

if (-not $sdkLine) {
    throw "sdk.dir was not found in android\local.properties."
}

$sdkRoot = ($sdkLine -replace "^\s*sdk\.dir\s*=", "").Trim()
$sdkRoot = $sdkRoot -replace "\\:", ":"
$sdkRoot = $sdkRoot -replace "\\\\", "\"
$Adb = Join-Path $sdkRoot "platform-tools\adb.exe"

if (-not (Test-Path -LiteralPath $Adb -PathType Leaf)) {
    throw "adb.exe was not found: $Adb"
}

New-Item -ItemType Directory -Path $ReportRoot -Force | Out-Null
$timestamp = Get-Date -Format "yyyyMMdd_HHmmss"
$ReportPath = Join-Path $ReportRoot (
    "AURA-Device-Inspection-v2.2-" + $timestamp + ".txt"
)
$Results = New-Object System.Collections.Generic.List[object]

Write-Host ""
Write-Host "AURA DEVICE INSPECTION v2.2" -ForegroundColor Cyan
Write-Host ("ADB: " + $Adb)

$start = Invoke-ProcessCapture `
    -FilePath $Adb `
    -Arguments @("start-server") `
    -WorkingDirectory $ProjectRoot `
    -TimeoutSeconds 20

if ($start.ExitCode -eq 0) {
    Add-Result "PASS" "ADB server" "ADB is available."
}
else {
    Add-Result "FAIL" "ADB server" (
        ($start.Output + " " + $start.Error).Trim()
    )
}

$devices = Invoke-ProcessCapture `
    -FilePath $Adb `
    -Arguments @("devices", "-l") `
    -WorkingDirectory $ProjectRoot `
    -TimeoutSeconds 20

$deviceLines = @(
    ($devices.Output -split "\r?\n") |
        Where-Object { $_ -match "^\S+\s+device(\s|$)" }
)

if ($deviceLines.Count -eq 0) {
    Add-Result "FAIL" "Connected phone" "No authorized Android phone was found."
}
else {
    Add-Result "PASS" "Connected phone" (
        [string]$deviceLines.Count + " authorized phone(s) detected."
    )

    $Serial = ($deviceLines[0] -split "\s+")[0]

    $modelResult = Invoke-ProcessCapture `
        -FilePath $Adb `
        -Arguments @(
            "-s", $Serial, "shell", "getprop", "ro.product.model"
        ) `
        -WorkingDirectory $ProjectRoot

    $Model = $modelResult.Output.Trim()
    if (-not $Model) { $Model = "Unknown" }

    Add-Result "INFO" "Phone model" $Model

    $packageResult = Invoke-ProcessCapture `
        -FilePath $Adb `
        -Arguments @(
            "-s",
            $Serial,
            "shell",
            "dumpsys",
            "package",
            $PackageName
        ) `
        -WorkingDirectory $ProjectRoot

    $packageText = $packageResult.Output

    if (
        -not $packageText -or
        $packageText -match "Unable to find package|Unknown package"
    ) {
        Add-Result "FAIL" "AURA installed" "AURA is not installed."
    }
    else {
        Add-Result "PASS" "AURA installed" "The AURA package is installed."

        $versionMatch = [regex]::Match(
            $packageText,
            "versionName=([^\s]+)"
        )

        if ($versionMatch.Success) {
            $Version = $versionMatch.Groups[1].Value

            if ($Version -eq "0.8.2-free-news-weather") {
                Add-Result "PASS" "Installed version" $Version
            }
            else {
                Add-Result "FAIL" "Installed version" (
                    $Version +
                    " is installed; 0.8.2-free-news-weather is required."
                )
            }
        }
        else {
            Add-Result "WARN" "Installed version" "Version could not be read."
        }

        foreach ($permission in @(
            "android.permission.RECORD_AUDIO",
            "android.permission.CAMERA"
        )) {
            $permissionPattern = (
                [regex]::Escape($permission) +
                ".*granted=(true|false)"
            )
            $permissionMatch = [regex]::Match(
                $packageText,
                $permissionPattern
            )

            if (
                $permissionMatch.Success -and
                $permissionMatch.Groups[1].Value -eq "true"
            ) {
                Add-Result "PASS" $permission "Granted."
            }
            else {
                Add-Result "WARN" $permission "Not confirmed as granted."
            }
        }

        $listenerResult = Invoke-ProcessCapture `
            -FilePath $Adb `
            -Arguments @(
                "-s",
                $Serial,
                "shell",
                "settings",
                "get",
                "secure",
                "enabled_notification_listeners"
            ) `
            -WorkingDirectory $ProjectRoot

        if ($listenerResult.Output -match [regex]::Escape($PackageName)) {
            Add-Result "PASS" "Notification access" "Enabled."
        }
        else {
            Add-Result "WARN" "Notification access" "Not enabled."

            if ($OpenNotificationSettings) {
                [void](Invoke-ProcessCapture `
                    -FilePath $Adb `
                    -Arguments @(
                        "-s",
                        $Serial,
                        "shell",
                        "am",
                        "start",
                        "-a",
                        "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"
                    ) `
                    -WorkingDirectory $ProjectRoot)
            }
        }

        $idleResult = Invoke-ProcessCapture `
            -FilePath $Adb `
            -Arguments @(
                "-s",
                $Serial,
                "shell",
                "dumpsys",
                "deviceidle",
                "whitelist"
            ) `
            -WorkingDirectory $ProjectRoot

        if ($idleResult.Output -match [regex]::Escape($PackageName)) {
            Add-Result "PASS" "Battery optimization" "AURA is whitelisted."
        }
        else {
            Add-Result "WARN" "Battery optimization" (
                "AURA is not confirmed as unrestricted."
            )
        }

        $serviceResult = Invoke-ProcessCapture `
            -FilePath $Adb `
            -Arguments @(
                "-s",
                $Serial,
                "shell",
                "dumpsys",
                "activity",
                "services",
                $PackageName
            ) `
            -WorkingDirectory $ProjectRoot

        if ($serviceResult.Output -match "WakeWordService") {
            Add-Result "PASS" "WakeWordService" "Service is running."
        }
        else {
            Add-Result "WARN" "WakeWordService" "Service is not running."
        }

        $pidResult = Invoke-ProcessCapture `
            -FilePath $Adb `
            -Arguments @(
                "-s",
                $Serial,
                "shell",
                "pidof",
                $PackageName
            ) `
            -WorkingDirectory $ProjectRoot

        $PidText = [string]$pidResult.Output
        $PidText = $PidText.Trim()

        if ($PidText) {
            Add-Result "PASS" "AURA process" ("Running, PID " + $PidText)

            $logResult = Invoke-ProcessCapture `
                -FilePath $Adb `
                -Arguments @(
                    "-s",
                    $Serial,
                    "logcat",
                    "-d",
                    "-v",
                    "threadtime",
                    "--pid",
                    $PidText,
                    "-t",
                    "800"
                ) `
                -WorkingDirectory $ProjectRoot `
                -TimeoutSeconds 40

            $fatalMatches = [regex]::Matches(
                $logResult.Output,
                "FATAL EXCEPTION|Fatal signal|SecurityException|" +
                "ForegroundServiceStartNotAllowedException|" +
                "MissingForegroundServiceTypeException"
            )

            if ($fatalMatches.Count -eq 0) {
                Add-Result "PASS" "Crash scan" (
                    "No selected fatal or permission signature was found."
                )
            }
            else {
                Add-Result "FAIL" "Crash scan" (
                    [string]$fatalMatches.Count +
                    " relevant error signature(s) found."
                )
            }
        }
        else {
            Add-Result "WARN" "AURA process" (
                "AURA is not running; no process Logcat scan was possible."
            )
        }
    }
}

$summary = New-Object System.Collections.Generic.List[string]
$summary.Add("AURA DEVICE INSPECTION v2.2") | Out-Null
$summary.Add("Generated: " + (Get-Date).ToString("yyyy-MM-dd HH:mm:ss")) | Out-Null
$summary.Add("") | Out-Null

foreach ($result in $Results) {
    $summary.Add(
        "[" +
        $result.Status +
        "] " +
        $result.Check +
        ": " +
        $result.Detail
    ) | Out-Null
}

[System.IO.File]::WriteAllLines(
    $ReportPath,
    [string[]]$summary,
    (New-Object System.Text.UTF8Encoding($false))
)

$failCount = @($Results | Where-Object { $_.Status -eq "FAIL" }).Count
$warnCount = @($Results | Where-Object { $_.Status -eq "WARN" }).Count
$passCount = @($Results | Where-Object { $_.Status -eq "PASS" }).Count

Write-Host ""
Write-Host "AURA DEVICE INSPECTION COMPLETE" -ForegroundColor Cyan
Write-Host (
    "PASS=" +
    [string]$passCount +
    " WARN=" +
    [string]$warnCount +
    " FAIL=" +
    [string]$failCount
)
Write-Host ("Report: " + $ReportPath)
