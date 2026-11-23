[CmdletBinding()]
param(
    [string]$ProjectRoot = "",
    [ValidatePattern('^[A-Za-z0-9._:/-]{1,120}$')]
    [string]$Model = "qwen3:4b",
    [switch]$PullModel
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($ProjectRoot)) {
    $ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
}
$ProjectRoot = [IO.Path]::GetFullPath($ProjectRoot)
if (-not (Test-Path -LiteralPath (Join-Path $ProjectRoot "android\gradlew.bat"))) {
    throw "AURA Android project not found at: $ProjectRoot"
}

function Resolve-Executable([string]$Name, [string[]]$Candidates) {
    $command = Get-Command $Name -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    foreach ($candidate in $Candidates) {
        if ([string]::IsNullOrWhiteSpace($candidate)) { continue }
        if (Test-Path -LiteralPath $candidate) { return $candidate }
    }
    return $null
}

$Ollama = Resolve-Executable "ollama.exe" @(
    (Join-Path $env:LOCALAPPDATA "Programs\Ollama\ollama.exe"),
    (Join-Path $env:ProgramFiles "Ollama\ollama.exe")
)
if (-not $Ollama) {
    throw "Ollama is not installed. Install it from https://ollama.com/download/windows and rerun."
}

$SdkRoot = $env:ANDROID_SDK_ROOT
if ([string]::IsNullOrWhiteSpace($SdkRoot)) { $SdkRoot = $env:ANDROID_HOME }
$LocalProperties = Join-Path $ProjectRoot "android\local.properties"
if ([string]::IsNullOrWhiteSpace($SdkRoot) -and (Test-Path -LiteralPath $LocalProperties)) {
    $SdkLine = Get-Content -LiteralPath $LocalProperties |
        Where-Object { $_ -match '^\s*sdk\.dir\s*=' } |
        Select-Object -First 1
    if ($SdkLine) {
        $SdkRoot = ($SdkLine -replace '^\s*sdk\.dir\s*=', '').Trim()
        $SdkRoot = $SdkRoot -replace '\\:', ':'
        $SdkRoot = $SdkRoot -replace '\\\\', '\'
    }
}
if ([string]::IsNullOrWhiteSpace($SdkRoot) -and (Test-Path -LiteralPath "E:\AndroidSDK")) {
    $SdkRoot = "E:\AndroidSDK"
}
if ([string]::IsNullOrWhiteSpace($SdkRoot)) {
    $SdkRoot = Join-Path $env:LOCALAPPDATA "Android\Sdk"
}
$Adb = Join-Path $SdkRoot "platform-tools\adb.exe"
if (-not (Test-Path -LiteralPath $Adb)) {
    throw "adb.exe was not found. Expected: $Adb"
}

if (-not (Get-Process ollama -ErrorAction SilentlyContinue)) {
    Start-Process -FilePath $Ollama -ArgumentList @("serve") -WindowStyle Hidden
    Start-Sleep -Seconds 3
}

if ($PullModel) {
    & $Ollama pull $Model
    if ($LASTEXITCODE -ne 0) { throw "Ollama could not pull $Model." }
}

$Models = Invoke-RestMethod -Uri "http://127.0.0.1:11434/api/tags" -TimeoutSec 10
$Installed = @($Models.models.name)
if ($Installed -notcontains $Model) {
    throw "$Model is not installed. Rerun this script with -PullModel (one-time model download)."
}

& $Adb start-server | Out-Null
$DeviceRows = @(& $Adb devices) |
    Where-Object { $_ -match "\sdevice$" }
if ($DeviceRows.Count -eq 0) {
    throw "No authorized Android phone was found. Enable USB debugging and accept the phone's authorization prompt."
}
if ($DeviceRows.Count -gt 1) {
    throw "More than one Android device is connected. Keep only the Vivo target connected."
}

& $Adb reverse tcp:11434 tcp:11434
if ($LASTEXITCODE -ne 0) { throw "ADB reverse failed." }

$Probe = Invoke-RestMethod -Uri "http://127.0.0.1:11434/api/show" `
    -Method Post `
    -ContentType "application/json" `
    -Body (@{ model = $Model } | ConvertTo-Json -Compress) `
    -TimeoutSec 20

Write-Host "AURA LOCAL BRAIN READY" -ForegroundColor Green
Write-Host "Phone endpoint: http://127.0.0.1:11434/v1/chat/completions"
Write-Host "Model         : $Model"
Write-Host "Device        : $($DeviceRows[0].Split("`t")[0])"
Write-Host "Budget        : local endpoint; not counted against cloud limits"
Write-Host "Keep this computer awake while using the local brain." -ForegroundColor Yellow
