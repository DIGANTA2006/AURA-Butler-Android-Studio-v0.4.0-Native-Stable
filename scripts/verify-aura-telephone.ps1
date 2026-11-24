$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$AndroidRoot = Join-Path $ProjectRoot 'android'
$VoipRoot = Join-Path $ProjectRoot 'server\voip'

function Invoke-CheckedNative {
    param(
        [Parameter(Mandatory = $true)][string]$Command,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [Parameter(Mandatory = $true)][string]$Description
    )

    Write-Host "`n== $Description ==" -ForegroundColor Cyan
    & $Command @Arguments
    $Code = $LASTEXITCODE
    if ($Code -ne 0) {
        throw "$Description failed with exit code $Code."
    }
}

if (-not (Test-Path (Join-Path $AndroidRoot 'gradlew.bat'))) {
    throw "Android Gradle wrapper not found under: $AndroidRoot"
}
if (-not (Test-Path $VoipRoot)) {
    throw "Self-hosted VoIP source directory not found under: $VoipRoot"
}

Push-Location $AndroidRoot
try {
    Invoke-CheckedNative -Command (Join-Path $AndroidRoot 'gradlew.bat') `
        -Arguments @('--no-daemon', 'testDebugUnitTest', 'lintDebug', 'assembleDebug') `
        -Description 'Android unit tests, lint and debug APK'
} finally {
    Pop-Location
}

$Python = Get-Command 'python' -ErrorAction SilentlyContinue
if (-not $Python) {
    throw 'Python 3.10+ is required for the self-hosted VoIP verification.'
}
Invoke-CheckedNative -Command $Python.Source `
    -Arguments @('-c', 'import sys; assert sys.version_info >= (3, 10), "Python 3.10+ required"') `
    -Description 'Check Python 3.10 or newer'

Push-Location $VoipRoot
try {
    Invoke-CheckedNative -Command $Python.Source `
        -Arguments @('-m', 'compileall', '-q', '.') `
        -Description 'Compile local VoIP Python source'
    Invoke-CheckedNative -Command $Python.Source `
        -Arguments @('-m', 'unittest', '-v', 'test_aura_security.py', 'test_aura_privacy.py', 'test_aura_asr_quality.py') `
        -Description 'Run VoIP security, consent and ASR-quality tests'

    $Docker = Get-Command 'docker' -ErrorAction SilentlyContinue
    if ($Docker) {
        $CreatedExampleEnv = $false
        if (-not (Test-Path '.env')) {
            Copy-Item '.env.example' '.env'
            $CreatedExampleEnv = $true
        }
        try {
            Invoke-CheckedNative -Command $Docker.Source `
                -Arguments @('compose', 'config', '--quiet') `
                -Description 'Validate owner-hosted Docker Compose configuration'
        } finally {
            if ($CreatedExampleEnv -and (Test-Path '.env')) {
                Remove-Item '.env' -Force
            }
        }
    } else {
        Write-Warning 'Docker CLI not found; Compose validation skipped. This does not validate container startup or media networking.'
    }
} finally {
    Pop-Location
}

Write-Host "`nAll available AURA telephone verification steps passed." -ForegroundColor Green
Write-Host 'This script does not test real SIM calls, a live WebRTC/TURN session, local model quality or Vivo device behaviour.' -ForegroundColor Yellow
