[CmdletBinding()]
param(
    [switch]$UninstallFirst,
    [switch]$SkipTests
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$AndroidDir = Join-Path $ProjectRoot 'android'
$Package = 'in.diganta.aurabutler'

Push-Location $AndroidDir
try {
    if (-not $SkipTests) {
        & .\gradlew.bat testDebugUnitTest
        if ($LASTEXITCODE -ne 0) { throw 'Unit tests failed.' }
    }

    & .\gradlew.bat assembleDebug
    if ($LASTEXITCODE -ne 0) { throw 'Android debug build failed.' }

    $Apk = Get-ChildItem '.\app\build\outputs\apk\debug\*.apk' |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if (-not $Apk) { throw 'The debug APK was not produced.' }

    $Adb = Get-Command adb.exe -ErrorAction SilentlyContinue
    if (-not $Adb) {
        Write-Host "APK created: $($Apk.FullName)" -ForegroundColor Green
        Write-Warning 'adb.exe is not on PATH. Install it through Android Studio or run the app with the green Run button.'
        exit 0
    }

    & $Adb.Source devices
    if ($UninstallFirst) {
        Write-Warning 'Uninstalling removes AURA private app data and saved memory.'
        & $Adb.Source uninstall $Package | Out-Host
    }
    & $Adb.Source install -r $Apk.FullName
    if ($LASTEXITCODE -ne 0) {
        throw 'APK installation failed. Use -UninstallFirst only if Android reports a signature mismatch.'
    }
    Write-Host 'AURA 0.4 installed successfully.' -ForegroundColor Green
}
finally {
    Pop-Location
}
