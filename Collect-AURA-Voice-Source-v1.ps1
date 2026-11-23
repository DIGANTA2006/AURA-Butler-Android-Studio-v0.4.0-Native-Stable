[CmdletBinding()]
param(
    [string]$ProjectRoot = "E:\MY PROJECT FOLDER\AURA PROJECT\AURA-Butler-Android-Studio-v0.4.0-Native-Stable"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

Write-Host ""
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "AURA VOICE SOURCE COLLECTOR v1" -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "Read-only: no source file is modified."
Write-Host ""

if (-not (Test-Path -LiteralPath $ProjectRoot -PathType Container)) {
    throw "Project root not found: $ProjectRoot"
}

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$temp = Join-Path $ProjectRoot ("AURA-VOICE-SOURCE-" + $stamp)
New-Item -ItemType Directory -Path $temp -Force | Out-Null

$targets = @(
    "android\app\build.gradle.kts",
    "android\app\src\main\java\in\diganta\aurabutler\background\WakeWordService.kt",
    "android\app\src\main\java\in\diganta\aurabutler\voice\OwnerVoiceGuard.kt",
    "android\app\src\main\java\in\diganta\aurabutler\voice\VoiceManager.kt",
    "android\app\src\main\java\in\diganta\aurabutler\voice\VoiceAudioRecorder.kt",
    "android\app\src\main\java\in\diganta\aurabutler\voice\SpeakerEmbeddingExtractor.kt",
    "android\app\src\main\java\in\diganta\aurabutler\voice\AuraVoiceRuntime.kt",
    "android\app\src\main\java\in\diganta\aurabutler\voice\VoiceDiagnostics.kt",
    "android\app\src\main\java\in\diganta\aurabutler\data\SecureStore.kt",
    "android\app\src\main\assets\voice\kws\aura_keywords.txt"
)

$manifest = New-Object System.Collections.Generic.List[string]

foreach ($relative in $targets) {
    $source = Join-Path $ProjectRoot $relative

    if (Test-Path -LiteralPath $source -PathType Leaf) {
        $dest = Join-Path $temp $relative
        $destDir = Split-Path -Parent $dest
        New-Item -ItemType Directory -Path $destDir -Force | Out-Null
        Copy-Item -LiteralPath $source -Destination $dest -Force

        $hash = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash
        $size = (Get-Item -LiteralPath $source).Length
        $manifest.Add(("FOUND`t{0}`t{1}`t{2}" -f $relative,$size,$hash))
        Write-Host "FOUND: $relative" -ForegroundColor Green
    }
    else {
        $manifest.Add(("MISSING`t{0}" -f $relative))
        Write-Host "MISSING: $relative" -ForegroundColor Yellow
    }
}

# Add small source excerpts useful for calibration without collecting model weights.
$wakePath = Join-Path $ProjectRoot "android\app\src\main\java\in\diganta\aurabutler\background\WakeWordService.kt"
$ownerPath = Join-Path $ProjectRoot "android\app\src\main\java\in\diganta\aurabutler\voice\OwnerVoiceGuard.kt"
$keywordsPath = Join-Path $ProjectRoot "android\app\src\main\assets\voice\kws\aura_keywords.txt"

$analysis = New-Object System.Collections.Generic.List[string]

if (Test-Path -LiteralPath $wakePath) {
    $lines = Get-Content -LiteralPath $wakePath
    $analysis.Add("=== WakeWordService sensitivity / audio lines ===")
    for ($i=0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -match '(?i)keywordsScore|keywordsThreshold|keyword|rms|peak|AudioRecord|SPEECH_RMS|noise|vad|source') {
            $analysis.Add(("{0,5}: {1}" -f ($i+1),$lines[$i]))
        }
    }
    $analysis.Add("")
}

if (Test-Path -LiteralPath $ownerPath) {
    $lines = Get-Content -LiteralPath $ownerPath
    $analysis.Add("=== OwnerVoiceGuard threshold / capture lines ===")
    for ($i=0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -match '(?i)threshold|score|similarity|cosine|capture|sample|duration|rms|noise|vad|verify|accept|reject|embedding') {
            $analysis.Add(("{0,5}: {1}" -f ($i+1),$lines[$i]))
        }
    }
    $analysis.Add("")
}

if (Test-Path -LiteralPath $keywordsPath) {
    $analysis.Add("=== aura_keywords.txt ===")
    foreach ($line in (Get-Content -LiteralPath $keywordsPath)) {
        $analysis.Add($line)
    }
}

[IO.File]::WriteAllLines(
    (Join-Path $temp "VOICE-SOURCE-MANIFEST.txt"),
    $manifest,
    (New-Object Text.UTF8Encoding($false))
)

[IO.File]::WriteAllLines(
    (Join-Path $temp "VOICE-CONFIG-EXCERPTS.txt"),
    $analysis,
    (New-Object Text.UTF8Encoding($false))
)

$zip = Join-Path $ProjectRoot ("AURA-VOICE-SOURCE-" + $stamp + ".zip")
Compress-Archive -Path (Join-Path $temp "*") -DestinationPath $zip -Force

Write-Host ""
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "DONE" -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "ZIP:" -ForegroundColor Green
Write-Host "  $zip"
Write-Host ""
Write-Host "Upload this ZIP to ChatGPT."
Write-Host "It contains source/config only; it does NOT include your voice model weights or saved voice embedding."
