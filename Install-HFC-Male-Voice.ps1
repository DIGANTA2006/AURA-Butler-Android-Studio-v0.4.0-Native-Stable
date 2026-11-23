[CmdletBinding()]
param(
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$VoiceDir = Join-Path $ProjectRoot 'android\app\src\main\assets\voice\piper'
$AndroidDir = Join-Path $ProjectRoot 'android'
$Stamp = Get-Date -Format 'yyyyMMdd_HHmmss'
$BackupDir = Join-Path $ProjectRoot "_backup_voice_lessac_$Stamp"
$WorkDir = Join-Path $env:TEMP "aura_hfc_male_$Stamp"
$Archive = Join-Path $WorkDir 'hfc_male.tar.bz2'
$ExtractDir = Join-Path $WorkDir 'extract'
$Url = 'https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-hfc_male-medium.tar.bz2'

Write-Host 'AURA HFC Male voice installer' -ForegroundColor Cyan
Write-Host "Project: $ProjectRoot"

if (-not (Test-Path $VoiceDir)) {
    throw "Voice asset directory was not found: $VoiceDir"
}

New-Item -ItemType Directory -Path $BackupDir -Force | Out-Null
Copy-Item -Path (Join-Path $VoiceDir '*') -Destination $BackupDir -Recurse -Force
Write-Host "Backup created: $BackupDir" -ForegroundColor Yellow

New-Item -ItemType Directory -Path $ExtractDir -Force | Out-Null
Write-Host 'Downloading the official sherpa-onnx converted HFC Male voice...'
Invoke-WebRequest -Uri $Url -OutFile $Archive -UseBasicParsing

if ((Get-Item $Archive).Length -lt 20MB) {
    throw 'The downloaded archive is unexpectedly small. The original voice was not changed.'
}

$tar = Get-Command tar.exe -ErrorAction SilentlyContinue
if (-not $tar) {
    throw 'tar.exe is required. Install the Windows optional OpenSSH/bsdtar tools or extract the archive manually.'
}
& $tar.Source -xjf $Archive -C $ExtractDir
if ($LASTEXITCODE -ne 0) {
    throw "Voice archive extraction failed with exit code $LASTEXITCODE."
}

$Model = Get-ChildItem $ExtractDir -Recurse -File -Filter '*.onnx' |
    Sort-Object Length -Descending |
    Select-Object -First 1
$Tokens = Get-ChildItem $ExtractDir -Recurse -File -Filter 'tokens.txt' |
    Select-Object -First 1
$Espeak = Get-ChildItem $ExtractDir -Recurse -Directory -Filter 'espeak-ng-data' |
    Select-Object -First 1

if (-not $Model -or $Model.Length -lt 20MB) {
    throw 'A valid converted ONNX voice model was not found in the archive.'
}
if (-not $Tokens) {
    throw 'tokens.txt was not found in the converted voice archive.'
}
if (-not $Espeak) {
    throw 'espeak-ng-data was not found in the converted voice archive.'
}

Remove-Item (Join-Path $VoiceDir 'model.onnx') -Force -ErrorAction SilentlyContinue
Remove-Item (Join-Path $VoiceDir 'tokens.txt') -Force -ErrorAction SilentlyContinue
Remove-Item (Join-Path $VoiceDir 'espeak-ng-data') -Recurse -Force -ErrorAction SilentlyContinue

Copy-Item $Model.FullName (Join-Path $VoiceDir 'model.onnx') -Force
Copy-Item $Tokens.FullName (Join-Path $VoiceDir 'tokens.txt') -Force
Copy-Item $Espeak.FullName (Join-Path $VoiceDir 'espeak-ng-data') -Recurse -Force
Set-Content -Path (Join-Path $VoiceDir 'VOICE_NAME.txt') -Value 'HFC Male medium (offline Piper voice)' -Encoding UTF8

Write-Host 'HFC Male voice installed into the Android assets.' -ForegroundColor Green
Write-Host "Model size: $([math]::Round((Get-Item (Join-Path $VoiceDir 'model.onnx')).Length / 1MB, 1)) MB"

if (-not $SkipBuild) {
    Push-Location $AndroidDir
    try {
        & .\gradlew.bat testDebugUnitTest assembleDebug
        if ($LASTEXITCODE -ne 0) {
            throw "Gradle verification failed with exit code $LASTEXITCODE."
        }
        Write-Host 'Build completed. APK:' -ForegroundColor Green
        Get-ChildItem '.\app\build\outputs\apk\debug\*.apk' | Select-Object -ExpandProperty FullName
    }
    finally {
        Pop-Location
    }
}

Remove-Item $WorkDir -Recurse -Force -ErrorAction SilentlyContinue
