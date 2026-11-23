param(
    [string]$ProjectRoot = (Split-Path -Parent $MyInvocation.MyCommand.Path),
    [string]$CloudRoot = "E:\MY PROJECT FOLDER\AURA CLOUD",
    [int]$TargetMB = 480,
    [switch]$SkipCloud
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Write-Section([string]$Text) {
    Write-Host "" 
    Write-Host "============================================================" -ForegroundColor Cyan
    Write-Host $Text -ForegroundColor Cyan
    Write-Host "============================================================" -ForegroundColor Cyan
}

function Get-SizeMB([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) { return 0.0 }
    $item = Get-Item -LiteralPath $Path
    if (-not $item.PSIsContainer) {
        return [math]::Round($item.Length / 1MB, 2)
    }
    $bytes = (Get-ChildItem -LiteralPath $Path -Recurse -Force -File -ErrorAction SilentlyContinue |
        Measure-Object -Property Length -Sum).Sum
    if ($null -eq $bytes) { $bytes = 0 }
    return [math]::Round($bytes / 1MB, 2)
}

function Copy-TreeForReview {
    param(
        [Parameter(Mandatory=$true)][string]$Source,
        [Parameter(Mandatory=$true)][string]$Destination
    )

    if (-not (Test-Path -LiteralPath $Source)) {
        throw "Source folder not found: $Source"
    }

    New-Item -ItemType Directory -Path $Destination -Force | Out-Null

    # Keep all source code and runtime assets, but omit generated/rebuildable content,
    # caches, local secrets, backups, installers, and already-built packages.
    $excludeDirs = @(
        '.git', '.gradle', '.idea', '.kotlin', '.cxx', '.externalNativeBuild',
        'build', 'out', 'dist', 'tmp', 'temp', 'node_modules', '__pycache__',
        '.pytest_cache', '.mypy_cache', '.ruff_cache', '.venv', 'venv',
        '.aura-upgrade-backup', '.aura-deep-research-backup',
        '.aura-v12-backup', '.aura-backup', 'captures'
    )

    $excludeFiles = @(
        'local.properties',
        '*.apk', '*.aab', '*.apks', '*.zip', '*.7z', '*.rar', '*.tar', '*.gz',
        '*.log', '*.hprof', '*.tmp', '*.bak', '*.old', '*.keystore', '*.jks',
        '*.pem', '*.p12', '*.pfx', '*.key'
    )

    $roboArgs = @(
        $Source,
        $Destination,
        '/E', '/COPY:DAT', '/DCOPY:DAT', '/R:1', '/W:1',
        '/NFL', '/NDL', '/NJH', '/NJS', '/NP'
    )

    if ($excludeDirs.Count -gt 0) {
        $roboArgs += '/XD'
        $roboArgs += $excludeDirs
    }
    if ($excludeFiles.Count -gt 0) {
        $roboArgs += '/XF'
        $roboArgs += $excludeFiles
    }

    & robocopy @roboArgs | Out-Null
    $code = $LASTEXITCODE
    if ($code -ge 8) {
        throw "Robocopy failed with exit code $code while copying $Source"
    }
}

function Remove-SensitiveFiles([string]$Root) {
    $exactNames = @(
        '.env', '.env.local', '.env.production', '.env.development',
        'secrets.json', 'secret.json', 'credentials.json',
        'service-account.json', 'google-services.json'
    )

    foreach ($name in $exactNames) {
        Get-ChildItem -LiteralPath $Root -Recurse -Force -File -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -ieq $name } |
            Remove-Item -Force -ErrorAction SilentlyContinue
    }
}

function Write-ShareReadme([string]$Root, [bool]$IncludedCloud) {
    $text = @"
AURA REVIEW PACKAGE
===================

This ZIP is intended for source-code review and debugging.

Included:
- Android/Kotlin source code
- Gradle project files required to understand/build the app
- Runtime assets stored under source folders (including bundled voice/model assets if present)
- Tests and project documentation
- AURA cloud source code and test scripts: $IncludedCloud

Intentionally excluded because they are rebuildable, machine-specific, very large, or sensitive:
- .git history
- Android/Gradle/IDE build caches
- build/ output directories
- previously built APK/AAB files
- virtual environments (.venv/venv)
- backup folders created by AURA upgrade scripts
- existing ZIP/RAR/7z archives
- local.properties
- keystores/private keys
- common .env/credential files

Modal model weights are NOT inside this package. Gemma/Qwen model files remain in the Modal volume.
Modal/Hugging Face/AURA API secret VALUES should remain in Modal Secrets and must not be included here.

This is still the full REVIEWABLE project source. Generated files can be recreated by Gradle/Python tooling.
"@
    Set-Content -LiteralPath (Join-Path $Root 'AURA-SHARE-README.txt') -Value $text -Encoding UTF8
}

$ProjectRoot = (Resolve-Path -LiteralPath $ProjectRoot).Path
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$outParent = Split-Path -Parent $ProjectRoot
$stage = Join-Path $outParent "AURA-FULL-SHARE-$stamp"
$zip = Join-Path $outParent "AURA-FULL-SHARE-UNDER-500MB-$stamp.zip"

Write-Section "AURA full-project share packager"
Write-Host "Android project : $ProjectRoot"
Write-Host "Cloud project   : $CloudRoot"
Write-Host "Target ZIP size : <= $TargetMB MB"
Write-Host "Output ZIP      : $zip"

if (Test-Path -LiteralPath $stage) { Remove-Item -LiteralPath $stage -Recurse -Force }
if (Test-Path -LiteralPath $zip) { Remove-Item -LiteralPath $zip -Force }
New-Item -ItemType Directory -Path $stage -Force | Out-Null

Write-Section "Copying Android project source"
$androidDest = Join-Path $stage 'AURA-ANDROID'
Copy-TreeForReview -Source $ProjectRoot -Destination $androidDest
Remove-SensitiveFiles -Root $androidDest
Write-Host ("Android review source: {0} MB" -f (Get-SizeMB $androidDest)) -ForegroundColor Green

$includedCloud = $false
if (-not $SkipCloud -and (Test-Path -LiteralPath $CloudRoot)) {
    Write-Section "Copying AURA cloud source"
    $cloudDest = Join-Path $stage 'AURA-CLOUD'
    Copy-TreeForReview -Source $CloudRoot -Destination $cloudDest
    Remove-SensitiveFiles -Root $cloudDest
    $includedCloud = $true
    Write-Host ("Cloud review source: {0} MB" -f (Get-SizeMB $cloudDest)) -ForegroundColor Green
} elseif (-not $SkipCloud) {
    Write-Warning "Cloud folder was not found, so only the Android project will be packaged: $CloudRoot"
}

Write-ShareReadme -Root $stage -IncludedCloud $includedCloud

Write-Section "Largest files retained in review package"
Get-ChildItem -LiteralPath $stage -Recurse -File -Force |
    Sort-Object Length -Descending |
    Select-Object -First 20 @{N='SizeMB';E={[math]::Round($_.Length/1MB,2)}}, FullName |
    Format-Table -AutoSize

$stageSize = Get-SizeMB $stage
Write-Host "Staging size: $stageSize MB"

Write-Section "Creating ZIP"
Add-Type -AssemblyName System.IO.Compression.FileSystem
[System.IO.Compression.ZipFile]::CreateFromDirectory(
    $stage,
    $zip,
    [System.IO.Compression.CompressionLevel]::Optimal,
    $false
)

$zipSize = Get-SizeMB $zip
Write-Host "ZIP created: $zip" -ForegroundColor Green
Write-Host "ZIP size   : $zipSize MB" -ForegroundColor Green

if ($zipSize -gt $TargetMB) {
    Write-Warning "The ZIP is still larger than the requested target ($TargetMB MB)."
    Write-Host "The largest retained files are shown above. Do NOT delete source files blindly." -ForegroundColor Yellow
    Write-Host "Tell ChatGPT the final ZIP size and paste the largest-file table so it can safely reduce it further." -ForegroundColor Yellow
    exit 2
}

Write-Section "SUCCESS"
Write-Host "Your full reviewable AURA project package is under $TargetMB MB." -ForegroundColor Green
Write-Host "Share this file:" -ForegroundColor Green
Write-Host $zip -ForegroundColor White
Write-Host ""
Write-Host "You can delete the temporary staging folder after confirming the ZIP opens:" -ForegroundColor DarkGray
Write-Host $stage -ForegroundColor DarkGray
