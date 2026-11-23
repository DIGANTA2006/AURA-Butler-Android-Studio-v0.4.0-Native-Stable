param(
    [string]$ProjectRoot = "E:\MY PROJECT FOLDER\AURA PROJECT\AURA-Butler-Android-Studio-v0.4.0-Native-Stable",
    [string]$CloudRoot = "E:\MY PROJECT FOLDER\AURA CLOUD"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "AURA v0.16.1 Safe Source Snapshot v2" -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "Project: $ProjectRoot"
Write-Host "Cloud  : $CloudRoot"
Write-Host ""
Write-Host "Creates a small source-only ZIP for the next AURA intelligence upgrade." -ForegroundColor Yellow
Write-Host "Test files may intentionally contain fake API-key strings to test SecretRedactor."
Write-Host "v2 sanitizes those test fixtures in the exported ZIP instead of treating them as real leaks."
Write-Host "Any credential-like value found in production/cloud/config source still causes a HARD STOP."
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
$WorkRoot = Join-Path $env:TEMP ("AURA-v0.16.1-source-v2-" + $Stamp)
$SnapshotRoot = Join-Path $WorkRoot "AURA-v0.16.1-SOURCE"
$ZipPath = Join-Path $ProjectRoot ("AURA-v0.16.1-SOURCE-SNAPSHOT-" + $Stamp + ".zip")

New-Item -ItemType Directory -Force -Path $SnapshotRoot | Out-Null

$Included = New-Object System.Collections.Generic.List[object]
$Skipped = New-Object System.Collections.Generic.List[string]
$Sanitized = New-Object System.Collections.Generic.List[string]

function Test-IsTestSource {
    param([Parameter(Mandatory=$true)][string]$RelativePath)
    $normalized = $RelativePath.Replace("/", "\").ToLowerInvariant()
    return (
        $normalized.Contains("\src\test\") -or
        $normalized.Contains("\src\androidtest\")
    )
}

function Add-SourceFile {
    param(
        [Parameter(Mandatory=$true)][string]$Source,
        [Parameter(Mandatory=$true)][string]$Relative
    )

    if (-not (Test-Path -LiteralPath $Source -PathType Leaf)) {
        return
    }

    $item = Get-Item -LiteralPath $Source -Force

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

    $Included.Add([pscustomobject]@{
        RelativePath = $Relative
        SourcePath = $Source
        SourceBytes = [int64]$item.Length
        SourceSHA256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $Source).Hash.ToUpperInvariant()
        Sanitized = $false
        SnapshotSHA256 = ""
        SnapshotBytes = [int64]0
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

# Small text metadata in assets.
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

# Gradle/project source configuration.
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

# Current cloud source only.
foreach ($cloudName in @("aura_models.py", "aura_student_config.py")) {
    $candidate = Join-Path $CloudRoot $cloudName
    if (Test-Path -LiteralPath $candidate -PathType Leaf) {
        Add-SourceFile -Source $candidate -Relative ("cloud\" + $cloudName)
    }
}

# Current QA/audit contract.
foreach ($pattern in @("Test-AURA-AutoQA-v10.ps1", "AURA-v0.16-OWNER-VOICE-ATTENTION-AUDIT.md")) {
    $candidate = Join-Path $ProjectRoot $pattern
    if (Test-Path -LiteralPath $candidate -PathType Leaf) {
        Add-SourceFile -Source $candidate -Relative ("project-meta\" + $pattern)
    }
}

# Credential-like VALUE patterns. Variable names such as AURA_API_KEY are safe and allowed.
$secretPatterns = @(
    'AIza[0-9A-Za-z\-_]{30,}',
    'sk-[A-Za-z0-9_\-]{20,}',
    'gsk_[A-Za-z0-9]{20,}',
    'hf_[A-Za-z0-9]{20,}',
    '-----BEGIN (RSA |EC |OPENSSH |PRIVATE )?PRIVATE KEY-----'
)

Write-Host "==> Credential safety scan" -ForegroundColor Cyan

$productionHits = New-Object System.Collections.Generic.List[string]

foreach ($entry in $Included) {
    $dest = Join-Path $SnapshotRoot $entry.RelativePath
    if (-not (Test-Path -LiteralPath $dest -PathType Leaf)) { continue }

    $ext = [System.IO.Path]::GetExtension($dest).ToLowerInvariant()
    if ($ext -notin @(".kt",".java",".xml",".kts",".gradle",".py",".json",".txt",".md",".toml",".pro")) {
        continue
    }

    $content = [System.IO.File]::ReadAllText($dest)
    $hadHit = $false

    foreach ($pattern in $secretPatterns) {
        if ([regex]::IsMatch($content, $pattern)) {
            $hadHit = $true

            if (Test-IsTestSource -RelativePath $entry.RelativePath) {
                # Unit tests often need realistic fake credentials to verify SecretRedactor.
                # Never export the value itself; preserve the surrounding test logic.
                $content = [regex]::Replace(
                    $content,
                    $pattern,
                    '<REDACTED_TEST_CREDENTIAL>'
                )
            }
            else {
                $productionHits.Add($entry.RelativePath)
            }
        }
    }

    if ($hadHit -and (Test-IsTestSource -RelativePath $entry.RelativePath)) {
        [System.IO.File]::WriteAllText(
            $dest,
            $content,
            [System.Text.UTF8Encoding]::new($false)
        )
        $entry.Sanitized = $true
        $Sanitized.Add($entry.RelativePath)
        Write-Host "SANITIZED [test fixture]: $($entry.RelativePath)" -ForegroundColor Yellow
    }
}

if ($productionHits.Count -gt 0) {
    Remove-Item -LiteralPath $WorkRoot -Recurse -Force -ErrorAction SilentlyContinue
    throw (
        "Safety stop: possible credential VALUE found in production/cloud/config source: " +
        (($productionHits | Sort-Object -Unique) -join ", ")
    )
}

# Re-scan the final snapshot. Nothing credential-like may remain anywhere.
$remainingHits = New-Object System.Collections.Generic.List[string]
Get-ChildItem -LiteralPath $SnapshotRoot -Recurse -File |
    Where-Object {
        $_.Extension.ToLowerInvariant() -in @(".kt",".java",".xml",".kts",".gradle",".py",".json",".txt",".md",".toml",".pro")
    } |
    ForEach-Object {
        $content = [System.IO.File]::ReadAllText($_.FullName)
        foreach ($pattern in $secretPatterns) {
            if ([regex]::IsMatch($content, $pattern)) {
                $rel = $_.FullName.Substring($SnapshotRoot.Length).TrimStart("\")
                $remainingHits.Add($rel)
                break
            }
        }
    }

if ($remainingHits.Count -gt 0) {
    Remove-Item -LiteralPath $WorkRoot -Recurse -Force -ErrorAction SilentlyContinue
    throw (
        "Safety stop: credential-like value still remains after sanitization: " +
        (($remainingHits | Sort-Object -Unique) -join ", ")
    )
}

Write-Host "PASS: no credential-like values remain in exported snapshot." -ForegroundColor Green

# Final hashes after any test-fixture sanitization.
foreach ($entry in $Included) {
    $dest = Join-Path $SnapshotRoot $entry.RelativePath
    if (Test-Path -LiteralPath $dest -PathType Leaf) {
        $snapItem = Get-Item -LiteralPath $dest
        $entry.SnapshotBytes = [int64]$snapItem.Length
        $entry.SnapshotSHA256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $dest).Hash.ToUpperInvariant()
    }
}

# Manifest.
$inventory = New-Object System.Collections.Generic.List[string]
$inventory.Add("# AURA v0.16.1 source snapshot inventory")
$inventory.Add("")
$inventory.Add("Generated: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')")
$inventory.Add("Project root: $ProjectRoot")
$inventory.Add("")
$inventory.Add("## Safety behavior")
$inventory.Add("")
$inventory.Add("- Production/cloud/config files are copied exactly and retain their source SHA256.")
$inventory.Add("- Credential-like values in production/cloud/config cause a hard stop.")
$inventory.Add("- Credential-like strings inside test/androidTest fixtures are replaced with <REDACTED_TEST_CREDENTIAL> in the exported ZIP.")
$inventory.Add("- For sanitized test files, both original source SHA256 and sanitized snapshot SHA256 are recorded.")
$inventory.Add("")
$inventory.Add("## Important excluded runtime assets")
$inventory.Add("")
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
    $status = if ($entry.Sanitized) { "SANITIZED_TEST_FIXTURE" } else { "EXACT" }
    $inventory.Add(
        "- $($entry.RelativePath) | $status | source SHA256 $($entry.SourceSHA256) | snapshot SHA256 $($entry.SnapshotSHA256) | $($entry.SnapshotBytes) bytes"
    )
}

if ($Sanitized.Count -gt 0) {
    $inventory.Add("")
    $inventory.Add("## Sanitized test fixtures")
    $inventory.Add("")
    foreach ($s in ($Sanitized | Sort-Object -Unique)) {
        $inventory.Add("- $s")
    }
}

if ($Skipped.Count -gt 0) {
    $inventory.Add("")
    $inventory.Add("## Skipped by safety guard")
    $inventory.Add("")
    foreach ($s in $Skipped) {
        $inventory.Add("- $s")
    }
}

$ManifestPath = Join-Path $SnapshotRoot "SOURCE-SNAPSHOT-MANIFEST.md"
[System.IO.File]::WriteAllLines(
    $ManifestPath,
    $inventory,
    [System.Text.UTF8Encoding]::new($false)
)

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
Write-Host "Files          : $($Included.Count)"
Write-Host "Sanitized tests: $($Sanitized.Count)"
Write-Host ("Size           : {0:N2} MB" -f ($zipSize / 1MB))
Write-Host "SHA256         : $zipHash"
Write-Host "ZIP            : $ZipPath"
Write-Host ""
Write-Host "Upload ONLY this generated source snapshot ZIP." -ForegroundColor Yellow
Write-Host "Do not upload .env, local.properties, keystores, app data or the full model/build folders."

Remove-Item -LiteralPath $WorkRoot -Recurse -Force -ErrorAction SilentlyContinue
