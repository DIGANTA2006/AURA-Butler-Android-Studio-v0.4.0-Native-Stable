param(
    [string]$ProjectRoot = "E:\MY PROJECT FOLDER\AURA PROJECT\AURA-Butler-Android-Studio-v0.4.0-Native-Stable",
    [string]$CloudRoot = "E:\MY PROJECT FOLDER\AURA CLOUD"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "AURA v0.16.1 Safe Source Snapshot" -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "Project: $ProjectRoot"
Write-Host "Cloud  : $CloudRoot"
Write-Host ""
Write-Host "Creates a small source-only ZIP for the next AURA intelligence upgrade." -ForegroundColor Yellow
Write-Host "It does NOT include models, APKs, build output, app data, .env, local.properties,"
Write-Host "keystores, API secrets, Gradle caches, backups, or the cloud virtual environment."
Write-Host ""

if (-not (Test-Path -LiteralPath $ProjectRoot -PathType Container)) {
    throw "Project root not found: $ProjectRoot"
}
if (-not (Test-Path -LiteralPath $CloudRoot -PathType Container)) {
    throw "Cloud root not found: $CloudRoot"
}

$AndroidRoot = Join-Path $ProjectRoot "android"
$AppRoot = Join-Path $AndroidRoot "app"
$KotlinRoot = Join-Path $AppRoot "src"

if (-not (Test-Path -LiteralPath $KotlinRoot -PathType Container)) {
    throw "Android source tree not found: $KotlinRoot"
}

$Stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$WorkRoot = Join-Path $env:TEMP ("AURA-v0.16.1-source-" + $Stamp)
$SnapshotRoot = Join-Path $WorkRoot "AURA-v0.16.1-SOURCE"
$ZipPath = Join-Path $ProjectRoot ("AURA-v0.16.1-SOURCE-SNAPSHOT-" + $Stamp + ".zip")

New-Item -ItemType Directory -Force -Path $SnapshotRoot | Out-Null

$Included = New-Object System.Collections.Generic.List[object]
$Skipped = New-Object System.Collections.Generic.List[string]

function Add-SourceFile {
    param(
        [Parameter(Mandatory=$true)][string]$Source,
        [Parameter(Mandatory=$true)][string]$Relative
    )

    if (-not (Test-Path -LiteralPath $Source -PathType Leaf)) {
        return
    }

    $item = Get-Item -LiteralPath $Source -Force

    # Source/config only; reject unexpectedly large files.
    if ($item.Length -gt 5MB) {
        $Skipped.Add("$Relative :: skipped because file is larger than 5 MB")
        return
    }

    $lowerName = $item.Name.ToLowerInvariant()
    $forbiddenNames = @(
        ".env", "local.properties", "secrets.properties", "google-services.json",
        "key.properties", "credentials.json", "service-account.json"
    )
    if ($forbiddenNames -contains $lowerName -or
        $lowerName.EndsWith(".jks") -or
        $lowerName.EndsWith(".keystore") -or
        $lowerName.EndsWith(".p12") -or
        $lowerName.EndsWith(".pem") -or
        $lowerName.EndsWith(".key")) {
        $Skipped.Add("$Relative :: protected secret/signing file")
        return
    }

    $dest = Join-Path $SnapshotRoot $Relative
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $dest) | Out-Null
    Copy-Item -LiteralPath $Source -Destination $dest -Force

    $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $dest).Hash.ToUpperInvariant()
    $Included.Add([pscustomobject]@{
        RelativePath = $Relative
        Bytes = [int64]$item.Length
        SHA256 = $hash
    })
}

# Kotlin/Java production + unit/instrumentation tests.
foreach ($sourceSet in @("main", "test", "androidTest")) {
    $setRoot = Join-Path $AppRoot ("src\" + $sourceSet)
    if (-not (Test-Path -LiteralPath $setRoot -PathType Container)) { continue }

    Get-ChildItem -LiteralPath $setRoot -Recurse -File -Force -ErrorAction SilentlyContinue |
        Where-Object {
            $_.Extension -in @(".kt", ".java", ".xml", ".pro", ".txt", ".json") -and
            $_.FullName -notmatch '\\assets\\voice\\' -and
            $_.FullName -notmatch '\\assets\\.*\.(onnx|bin|wav|mp3|aar)$'
        } |
        ForEach-Object {
            $relInside = $_.FullName.Substring($ProjectRoot.Length).TrimStart("\")
            Add-SourceFile -Source $_.FullName -Relative $relInside
        }
}

# Small text metadata in assets is useful for wake words / model configuration.
$AssetsRoot = Join-Path $AppRoot "src\main\assets"
if (Test-Path -LiteralPath $AssetsRoot) {
    Get-ChildItem -LiteralPath $AssetsRoot -Recurse -File -Force -ErrorAction SilentlyContinue |
        Where-Object {
            $_.Extension -in @(".txt", ".json", ".yaml", ".yml", ".conf") -and $_.Length -le 1MB
        } |
        ForEach-Object {
            $relInside = $_.FullName.Substring($ProjectRoot.Length).TrimStart("\")
            Add-SourceFile -Source $_.FullName -Relative $relInside
        }
}

# Gradle/project source configuration. local.properties and signing files are deliberately excluded.
foreach ($candidate in @(
    (Join-Path $ProjectRoot "android\app\build.gradle.kts"),
    (Join-Path $ProjectRoot "android\build.gradle.kts"),
    (Join-Path $ProjectRoot "android\settings.gradle.kts"),
    (Join-Path $ProjectRoot "android\settings.gradle"),
    (Join-Path $ProjectRoot "android\gradle\libs.versions.toml"),
    (Join-Path $ProjectRoot "android\gradle\wrapper\gradle-wrapper.properties"),
    (Join-Path $ProjectRoot "android\app\proguard-rules.pro")
)) {
    if (Test-Path -LiteralPath $candidate -PathType Leaf) {
        $relInside = $candidate.Substring($ProjectRoot.Length).TrimStart("\")
        Add-SourceFile -Source $candidate -Relative $relInside
    }
}

# Current cloud source only. Never include .venv, .env or Modal credentials.
foreach ($cloudName in @("aura_models.py", "aura_student_config.py")) {
    $candidate = Join-Path $CloudRoot $cloudName
    if (Test-Path -LiteralPath $candidate -PathType Leaf) {
        Add-SourceFile -Source $candidate -Relative ("cloud\" + $cloudName)
    }
}

# Include current QA/audit scripts because they define the regression contract for the next release.
foreach ($pattern in @("Test-AURA-AutoQA-v10.ps1", "AURA-v0.16-OWNER-VOICE-ATTENTION-AUDIT.md")) {
    $candidate = Join-Path $ProjectRoot $pattern
    if (Test-Path -LiteralPath $candidate -PathType Leaf) {
        Add-SourceFile -Source $candidate -Relative ("project-meta\" + $pattern)
    }
}

# Add a source-tree inventory without copying excluded binaries.
$inventory = New-Object System.Collections.Generic.List[string]
$inventory.Add("# AURA source snapshot inventory")
$inventory.Add("")
$inventory.Add("Generated: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')")
$inventory.Add("Project root: $ProjectRoot")
$inventory.Add("")
$inventory.Add("## Important excluded runtime assets")
$inventory.Add("")
$inventory.Add("The following are intentionally NOT copied into this ZIP:")
$inventory.Add("- Whisper/Piper/wake-word/speaker-verification ONNX models")
$inventory.Add("- sherpa-onnx AAR/native binaries")
$inventory.Add("- APK/AAB/build output and Gradle caches")
$inventory.Add("- local.properties, .env, keystores, credentials and API secrets")
$inventory.Add("- app databases, encrypted SharedPreferences and personal memory")
$inventory.Add("- cloud .venv and model cache")
$inventory.Add("- backup/AutoQA report directories")
$inventory.Add("")
$inventory.Add("## Included files")
$inventory.Add("")
foreach ($entry in ($Included | Sort-Object RelativePath)) {
    $inventory.Add("- $($entry.RelativePath) | $($entry.Bytes) bytes | SHA256 $($entry.SHA256)")
}
if ($Skipped.Count -gt 0) {
    $inventory.Add("")
    $inventory.Add("## Skipped by safety guard")
    $inventory.Add("")
    foreach ($s in $Skipped) { $inventory.Add("- $s") }
}

$ManifestPath = Join-Path $SnapshotRoot "SOURCE-SNAPSHOT-MANIFEST.md"
[System.IO.File]::WriteAllLines($ManifestPath, $inventory, [System.Text.UTF8Encoding]::new($false))

# Defensive secret-value scan. Variable NAMES are allowed; value-like credentials are not.
$secretPatterns = @(
    'AIza[0-9A-Za-z\-_]{30,}',
    'sk-[A-Za-z0-9_\-]{20,}',
    'gsk_[A-Za-z0-9]{20,}',
    'hf_[A-Za-z0-9]{20,}',
    '-----BEGIN (RSA |EC |OPENSSH |PRIVATE )?PRIVATE KEY-----'
)

$secretHits = New-Object System.Collections.Generic.List[string]
Get-ChildItem -LiteralPath $SnapshotRoot -Recurse -File |
    Where-Object { $_.Extension -in @(".kt",".java",".xml",".kts",".gradle",".py",".json",".txt",".md",".toml",".pro") } |
    ForEach-Object {
        $content = [System.IO.File]::ReadAllText($_.FullName)
        foreach ($pattern in $secretPatterns) {
            if ([regex]::IsMatch($content, $pattern)) {
                $rel = $_.FullName.Substring($SnapshotRoot.Length).TrimStart("\")
                $secretHits.Add($rel)
                break
            }
        }
    }

if ($secretHits.Count -gt 0) {
    Remove-Item -LiteralPath $WorkRoot -Recurse -Force -ErrorAction SilentlyContinue
    throw ("Safety stop: possible credential VALUE found in source snapshot: " + (($secretHits | Sort-Object -Unique) -join ", "))
}

if (Test-Path -LiteralPath $ZipPath) {
    Remove-Item -LiteralPath $ZipPath -Force
}

Compress-Archive -LiteralPath (Join-Path $SnapshotRoot "*") -DestinationPath $ZipPath -CompressionLevel Optimal

$zipHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $ZipPath).Hash.ToUpperInvariant()
$zipSize = (Get-Item -LiteralPath $ZipPath).Length

Write-Host ""
Write-Host "============================================================" -ForegroundColor Green
Write-Host "AURA SOURCE SNAPSHOT CREATED" -ForegroundColor Green
Write-Host "============================================================" -ForegroundColor Green
Write-Host "Files : $($Included.Count)"
Write-Host ("Size  : {0:N2} MB" -f ($zipSize / 1MB))
Write-Host "SHA256: $zipHash"
Write-Host "ZIP   : $ZipPath"
Write-Host ""
Write-Host "Upload ONLY this source snapshot ZIP for the next upgrade." -ForegroundColor Yellow
Write-Host "Do not upload .env, local.properties, keystores or your whole model/build folders."

Remove-Item -LiteralPath $WorkRoot -Recurse -Force -ErrorAction SilentlyContinue
