<#
  AURA Phase 1 - voice behaviour and Bengali speech

  1. Chat replies are spoken only for voice turns (the mic button or the word "Aura").
     Typed chat, screen, document and plan results stay silent.
  2. Notification voice alerts default to OFF. A phone that already saved the old
     setting keeps it until you switch it off once in AURA Settings.
  3. Bengali written in English letters (for example "Bolchi kichu habe nato") is
     converted to Bengali script before speech, so the Bengali voice pronounces it.

  Safety: every file is backed up first. Each edit stops the script if its anchor
  text is not found exactly once. Running it twice is refused.

  Run:    powershell -ExecutionPolicy Bypass -File .\Apply-AURA-Phase1-Voice-Bengali.ps1
  Test:   add -RunTests to also run the unit tests (needs android\gradlew.bat)
  Undo:   powershell -ExecutionPolicy Bypass -File .\Apply-AURA-Phase1-Voice-Bengali.ps1 -Undo
#>
[CmdletBinding()]
param(
    [string]$ProjectRoot = 'E:\MY PROJECT FOLDER\AURA PROJECT\AURA-Butler-Android-Studio-v0.4.0-Native-Stable',
    [switch]$Undo,
    [switch]$RunTests
)

$ErrorActionPreference = 'Stop'
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$nl = "`n"

$base     = 'android\app\src\main\java\in\diganta\aurabutler'
$testBase = 'android\app\src\test\java\in\diganta\aurabutler\voice'

$editedFiles = @(
    "$base\model\Models.kt",
    "$base\ButlerViewModel.kt",
    "$base\ui\AuraApp.kt",
    "$base\voice\SpeechTextSanitizer.kt"
)
$newFiles = @(
    "$base\voice\RomanizedBengali.kt",
    "$testBase\RomanizedBengaliTest.kt"
)
$backupRoot = Join-Path $ProjectRoot 'backups'

function Write-Step([string]$text) { Write-Host ""; Write-Host "== $text" -ForegroundColor Cyan }

function Edit-File {
    param([string]$RelPath, [string]$Old, [string]$New)
    $path = Join-Path $ProjectRoot $RelPath
    $raw = [System.IO.File]::ReadAllText($path, [System.Text.Encoding]::UTF8)
    $crlf = $raw.Contains("`r`n")
    $text = $raw.Replace("`r`n", "`n")
    $oldLf = $Old.Replace("`r`n", "`n")
    $newLf = $New.Replace("`r`n", "`n")

    $count = 0
    $idx = 0
    while (($idx = $text.IndexOf($oldLf, $idx, [System.StringComparison]::Ordinal)) -ge 0) {
        $count++
        $idx += $oldLf.Length
    }
    if ($count -ne 1) {
        $preview = $oldLf.Substring(0, [Math]::Min(70, $oldLf.Length))
        throw "Anchor found $count time(s) in $RelPath (expected exactly 1). Anchor starts: $preview"
    }

    $text = $text.Replace($oldLf, $newLf)
    if ($crlf) { $text = $text.Replace("`n", "`r`n") }
    [System.IO.File]::WriteAllText($path, $text, $utf8NoBom)
    Write-Host "  edited  $RelPath"
}

function Write-NewFile {
    param([string]$RelPath, [string]$Content)
    $path = Join-Path $ProjectRoot $RelPath
    $dir = Split-Path $path -Parent
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
    [System.IO.File]::WriteAllText($path, $Content, $utf8NoBom)
    Write-Host "  created $RelPath"
}

# ---------------------------------------------------------------- sanity
if (-not (Test-Path (Join-Path $ProjectRoot "$base\ButlerViewModel.kt"))) {
    throw "AURA source not found under: $ProjectRoot`nRe-run with -ProjectRoot 'D:\path\to\AURA-Butler-Android-Studio-v0.4.0-Native-Stable'"
}

# ---------------------------------------------------------------- undo
if ($Undo) {
    Write-Step "Undoing the latest Phase 1 apply"
    $last = Get-ChildItem $backupRoot -Directory -Filter 'phase1-*' -ErrorAction SilentlyContinue |
        Sort-Object Name | Select-Object -Last 1
    if (-not $last) { throw "No Phase 1 backup found in $backupRoot" }
    foreach ($rel in $editedFiles) {
        $src = Join-Path $last.FullName $rel
        if (Test-Path $src) {
            Copy-Item $src (Join-Path $ProjectRoot $rel) -Force
            Write-Host "  restored $rel"
        }
    }
    $list = Join-Path $last.FullName 'NEW_FILES.txt'
    if (Test-Path $list) {
        Get-Content $list | ForEach-Object {
            $p = Join-Path $ProjectRoot $_
            if (Test-Path $p) { Remove-Item $p -Force; Write-Host "  removed $_" }
        }
    }
    Write-Host "Undo complete (backup: $($last.Name))." -ForegroundColor Green
    return
}

# ---------------------------------------------------------------- already applied?
$vmText = [System.IO.File]::ReadAllText((Join-Path $ProjectRoot "$base\ButlerViewModel.kt"), [System.Text.Encoding]::UTF8)
if ($vmText.Contains('voiceSpeechSequence')) {
    Write-Host "Phase 1 is already applied. Nothing changed." -ForegroundColor Yellow
    return
}

# ---------------------------------------------------------------- backup
Write-Step "Backing up the files this phase touches"
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$backupDir = Join-Path $backupRoot "phase1-$stamp"
New-Item -ItemType Directory -Path $backupDir -Force | Out-Null
foreach ($rel in $editedFiles) {
    $dest = Join-Path $backupDir $rel
    New-Item -ItemType Directory -Path (Split-Path $dest -Parent) -Force | Out-Null
    Copy-Item (Join-Path $ProjectRoot $rel) $dest
}
Write-Host "  backup -> $backupDir"

# ---------------------------------------------------------------- 1. voice gate: only voice turns get spoken
Write-Step "1/3 Speak only voice-initiated replies"

$seqOld = '    val speechSequence: Long = 0,'
$seqNew = $seqOld + $nl + '    val voiceSpeechSequence: Long = -1,'
Edit-File "$base\model\Models.kt" $seqOld $seqNew

$voiceOld = '        setInput(text)' + $nl + '        submitText(text)'
$voiceNew = '        setInput(text)' + $nl + '        submitText(text, fromVoice = true)'
Edit-File "$base\ButlerViewModel.kt" $voiceOld $voiceNew

Edit-File "$base\ButlerViewModel.kt" `
    'fun submitText(text: String) {' `
    'fun submitText(text: String, fromVoice: Boolean = false) {'

$vmSpeechOld = 'speechText = if (current.settings.spokenReplies) speechText else null,'
$vmSpeechNew = $vmSpeechOld + $nl + '                        voiceSpeechSequence = if (fromVoice) current.speechSequence + 1 else current.voiceSpeechSequence,'
Edit-File "$base\ButlerViewModel.kt" $vmSpeechOld $vmSpeechNew

Edit-File "$base\ui\AuraApp.kt" `
    'state.speechText?.let { text ->' `
    'state.speechText?.takeIf { state.speechSequence == state.voiceSpeechSequence }?.let { text ->'

Edit-File "$base\ui\AuraApp.kt" `
    'Android TTS automatically selects English, Bengali or Hindi. Piper remains the private offline English fallback.' `
    'Only voice turns are spoken: the mic button or the word Aura. Typed chats stay silent. Bengali, including romanized Bengali, uses the Bengali voice.'

# ---------------------------------------------------------------- 2. notification voice alerts default OFF
Write-Step "2/3 Notification voice alerts default to OFF"
Edit-File "$base\model\Models.kt" `
    'val importantMessageVoiceAlerts: Boolean = true,' `
    'val importantMessageVoiceAlerts: Boolean = false,'

# ---------------------------------------------------------------- 3. romanized Bengali pronunciation
Write-Step "3/3 Romanized Bengali pronunciation"

Write-NewFile "$base\voice\RomanizedBengali.kt" @'
package `in`.diganta.aurabutler.voice

import java.util.Locale

/**
 * Converts Bengali written in English letters (for example "Bolchi kichu habe nato")
 * into Bengali script, so the Bengali text-to-speech voice pronounces it correctly
 * instead of applying English letter-to-sound rules.
 *
 * Offline and heuristic. A curated word list covers common chat, news and name words;
 * other words use simple phonetic rules. Text that is not romanized Bengali is returned unchanged.
 */
object RomanizedBengali {
    private val TOKEN = Regex("[A-Za-z]+")

    private const val ANUSVARA = "\u0982"
    private const val HASANT = "\u09CD"

    private enum class Prev { NONE, CONSONANT, VOWEL }

    private class Vowel(val latin: String, val independent: String, val matra: String)

    // Longest match first: order matters.
    private val CONSONANTS: List<Pair<String, String>> = listOf(
        "chh" to "\u099B",
        "ch" to "\u099A",
        "kh" to "\u0996",
        "gh" to "\u0998",
        "jh" to "\u099D",
        "th" to "\u09A5",
        "dh" to "\u09A7",
        "ph" to "\u09AB",
        "bh" to "\u09AD",
        "sh" to "\u09B6",
        "ng" to "\u0982",
        "k" to "\u0995",
        "g" to "\u0997",
        "j" to "\u099C",
        "t" to "\u09A4",
        "d" to "\u09A6",
        "n" to "\u09A8",
        "p" to "\u09AA",
        "b" to "\u09AC",
        "m" to "\u09AE",
        "r" to "\u09B0",
        "l" to "\u09B2",
        "s" to "\u09B8",
        "h" to "\u09B9",
        "y" to "\u09AF",
        "w" to "\u0993",
        "v" to "\u09AD",
        "f" to "\u09AB",
        "z" to "\u099C",
        "c" to "\u0995",
        "q" to "\u0995",
        "x" to "\u0995\u09CD\u09B8",
    )

    // Longest match first: order matters.
    private val VOWELS: List<Vowel> = listOf(
        Vowel("aa", "\u0986", "\u09BE"),
        Vowel("ai", "\u0986\u0987", "\u09BE\u0987"),
        Vowel("au", "\u0994", "\u09CC"),
        Vowel("ee", "\u0988", "\u09C0"),
        Vowel("ii", "\u0988", "\u09C0"),
        Vowel("oo", "\u098A", "\u09C2"),
        Vowel("uu", "\u098A", "\u09C2"),
        Vowel("ou", "\u0994", "\u09CC"),
        Vowel("ei", "\u098F\u0987", "\u09C7\u0987"),
        Vowel("oi", "\u0993\u0987", "\u09CB\u0987"),
        Vowel("a", "\u0986", ""),
        Vowel("e", "\u098F", "\u09C7"),
        Vowel("i", "\u0987", "\u09BF"),
        Vowel("o", "\u0993", ""),
        Vowel("u", "\u0989", "\u09C1"),
    )

    private val BENGALI_WORDS: Map<String, String> = mapOf(
        "ami" to "\u0986\u09AE\u09BF",
        "tumi" to "\u09A4\u09C1\u09AE\u09BF",
        "tui" to "\u09A4\u09C1\u0987",
        "apni" to "\u0986\u09AA\u09A8\u09BF",
        "se" to "\u09B8\u09C7",
        "tara" to "\u09A4\u09BE\u09B0\u09BE",
        "ora" to "\u0993\u09B0\u09BE",
        "amra" to "\u0986\u09AE\u09B0\u09BE",
        "tomra" to "\u09A4\u09CB\u09AE\u09B0\u09BE",
        "amar" to "\u0986\u09AE\u09BE\u09B0",
        "tomar" to "\u09A4\u09CB\u09AE\u09BE\u09B0",
        "tar" to "\u09A4\u09BE\u09B0",
        "kichu" to "\u0995\u09BF\u099B\u09C1",
        "kono" to "\u0995\u09CB\u09A8\u09CB",
        "keu" to "\u0995\u09C7\u0989",
        "ke" to "\u0995\u09C7",
        "ki" to "\u0995\u09BF",
        "keno" to "\u0995\u09C7\u09A8",
        "kothay" to "\u0995\u09CB\u09A5\u09BE\u09AF\u09BC",
        "kokhon" to "\u0995\u0996\u09A8",
        "kemon" to "\u0995\u09C7\u09AE\u09A8",
        "ekhon" to "\u098F\u0996\u09A8",
        "ekta" to "\u098F\u0995\u099F\u09BE",
        "ekti" to "\u098F\u0995\u099F\u09BF",
        "ektu" to "\u098F\u0995\u099F\u09C1",
        "eta" to "\u098F\u099F\u09BE",
        "ota" to "\u0993\u099F\u09BE",
        "ei" to "\u098F\u0987",
        "oi" to "\u0993\u0987",
        "ata" to "\u098F\u099F\u09BE",
        "kal" to "\u0995\u09BE\u09B2",
        "aj" to "\u0986\u099C",
        "aaj" to "\u0986\u099C",
        "ebar" to "\u098F\u09AC\u09BE\u09B0",
        "tarpor" to "\u09A4\u09BE\u09B0\u09AA\u09B0",
        "kintu" to "\u0995\u09BF\u09A8\u09CD\u09A4\u09C1",
        "ar" to "\u0986\u09B0",
        "na" to "\u09A8\u09BE",
        "nai" to "\u09A8\u09C7\u0987",
        "nei" to "\u09A8\u09C7\u0987",
        "nato" to "\u09A8\u09BE\u09A4\u09CB",
        "naki" to "\u09A8\u09BE\u0995\u09BF",
        "achhe" to "\u0986\u099B\u09C7",
        "acche" to "\u0986\u099B\u09C7",
        "achi" to "\u0986\u099B\u09BF",
        "acho" to "\u0986\u099B\u09CB",
        "ache" to "\u0986\u099B\u09C7",
        "bhalo" to "\u09AD\u09BE\u09B2\u09CB",
        "valo" to "\u09AD\u09BE\u09B2\u09CB",
        "khub" to "\u0996\u09C1\u09AC",
        "habe" to "\u09B9\u09AC\u09C7",
        "hobe" to "\u09B9\u09AC\u09C7",
        "hbe" to "\u09B9\u09AC\u09C7",
        "hoy" to "\u09B9\u09AF\u09BC",
        "hoye" to "\u09B9\u09AF\u09BC\u09C7",
        "holo" to "\u09B9\u09B2\u09CB",
        "hoyeche" to "\u09B9\u09AF\u09BC\u09C7\u099B\u09C7",
        "bolchi" to "\u09AC\u09B2\u099B\u09BF",
        "bolchhi" to "\u09AC\u09B2\u099B\u09BF",
        "bolcho" to "\u09AC\u09B2\u099B\u09CB",
        "bolbo" to "\u09AC\u09B2\u09AC\u09CB",
        "bolbe" to "\u09AC\u09B2\u09AC\u09C7",
        "bolo" to "\u09AC\u09B2\u09CB",
        "bole" to "\u09AC\u09B2\u09C7",
        "bolche" to "\u09AC\u09B2\u099B\u09C7",
        "diyechi" to "\u09A6\u09BF\u09AF\u09BC\u09C7\u099B\u09BF",
        "diyechhi" to "\u09A6\u09BF\u09AF\u09BC\u09C7\u099B\u09BF",
        "dilam" to "\u09A6\u09BF\u09B2\u09BE\u09AE",
        "dilo" to "\u09A6\u09BF\u09B2\u09CB",
        "kore" to "\u0995\u09B0\u09C7",
        "korchi" to "\u0995\u09B0\u099B\u09BF",
        "korchhi" to "\u0995\u09B0\u099B\u09BF",
        "korbo" to "\u0995\u09B0\u09AC\u09CB",
        "korbe" to "\u0995\u09B0\u09AC\u09C7",
        "korlam" to "\u0995\u09B0\u09B2\u09BE\u09AE",
        "korte" to "\u0995\u09B0\u09A4\u09C7",
        "kora" to "\u0995\u09B0\u09BE",
        "asbe" to "\u0986\u09B8\u09AC\u09C7",
        "ashbe" to "\u0986\u09B8\u09AC\u09C7",
        "asbo" to "\u0986\u09B8\u09AC\u09CB",
        "asche" to "\u0986\u09B8\u099B\u09C7",
        "esechi" to "\u098F\u09B8\u09C7\u099B\u09BF",
        "esho" to "\u098F\u09B8\u09CB",
        "ashi" to "\u0986\u09B8\u09BF",
        "jabe" to "\u09AF\u09BE\u09AC\u09C7",
        "jabo" to "\u09AF\u09BE\u09AC\u09CB",
        "jachhi" to "\u09AF\u09BE\u099A\u09CD\u099B\u09BF",
        "gelam" to "\u0997\u09C7\u09B2\u09BE\u09AE",
        "gechi" to "\u0997\u09C7\u099B\u09BF",
        "gele" to "\u0997\u09C7\u09B2\u09C7",
        "khabo" to "\u0996\u09BE\u09AC\u09CB",
        "khabe" to "\u0996\u09BE\u09AC\u09C7",
        "khacchi" to "\u0996\u09BE\u099A\u09CD\u099B\u09BF",
        "dekho" to "\u09A6\u09C7\u0996\u09CB",
        "dekhi" to "\u09A6\u09C7\u0996\u09BF",
        "dekhbo" to "\u09A6\u09C7\u0996\u09AC\u09CB",
        "shono" to "\u09B6\u09CB\u09A8\u09CB",
        "suno" to "\u09B6\u09CB\u09A8\u09CB",
        "shunchi" to "\u09B6\u09C1\u09A8\u099B\u09BF",
        "bhai" to "\u09AD\u09BE\u0987",
        "vai" to "\u09AD\u09BE\u0987",
        "bon" to "\u09AC\u09CB\u09A8",
        "didi" to "\u09A6\u09BF\u09A6\u09BF",
        "dada" to "\u09A6\u09BE\u09A6\u09BE",
        "baba" to "\u09AC\u09BE\u09AC\u09BE",
        "maa" to "\u09AE\u09BE",
        "khobor" to "\u0996\u09AC\u09B0",
        "gorom" to "\u0997\u09B0\u09AE",
        "thik" to "\u09A0\u09BF\u0995",
        "gare" to "\u0997\u09BE\u09B0\u09C7",
        "chure" to "\u099B\u09C1\u09B0\u09C7",
        "maityr" to "\u09AE\u09BE\u0987\u09A4\u09BF\u09B0",
        "maity" to "\u09AE\u09BE\u0987\u09A4\u09BF",
        "alok" to "\u0986\u09B2\u09CB\u0995",
        "kadra" to "\u0995\u09BE\u09A6\u09B0\u09BE",
        "kolkata" to "\u0995\u09B2\u0995\u09BE\u09A4\u09BE",
        "kolkatar" to "\u0995\u09B2\u0995\u09BE\u09A4\u09BE\u09B0",
        "bangla" to "\u09AC\u09BE\u0982\u09B2\u09BE",
        "banglay" to "\u09AC\u09BE\u0982\u09B2\u09BE\u09AF\u09BC",
        "bangali" to "\u09AC\u09BE\u0982\u09BE\u09B2\u09BF",
        "bengali" to "\u09AC\u09BE\u0982\u09BE\u09B2\u09BF",
        "ebhabe" to "\u098F\u09AD\u09BE\u09AC\u09C7",
        "ekbar" to "\u098F\u0995\u09AC\u09BE\u09B0",
        "tomake" to "\u09A4\u09CB\u09AE\u09BE\u0995\u09C7",
        "amake" to "\u0986\u09AE\u09BE\u0995\u09C7",
        "kothai" to "\u0995\u09CB\u09A5\u09BE\u09AF\u09BC",
        "ekhane" to "\u098F\u0996\u09BE\u09A8\u09C7",
        "okhane" to "\u0993\u0996\u09BE\u09A8\u09C7",
        "bhalobasha" to "\u09AD\u09BE\u09B2\u09CB\u09AC\u09BE\u09B8\u09BE",
        "dhonyobad" to "\u09A7\u09A8\u09CD\u09AF\u09AC\u09BE\u09A6",
        "shuvo" to "\u09B6\u09C1\u09AD",
        "raat" to "\u09B0\u09BE\u09A4",
        "din" to "\u09A6\u09BF\u09A8",
        "mane" to "\u09AE\u09BE\u09A8\u09C7",
        "manush" to "\u09AE\u09BE\u09A8\u09C1\u09B7",
        "ghor" to "\u0998\u09B0",
        "ghore" to "\u0998\u09B0\u09C7",
        "ghar" to "\u0998\u09B0",
        "jol" to "\u099C\u09B2",
        "khabar" to "\u0996\u09BE\u09AC\u09BE\u09B0",
        "cha" to "\u099A\u09BE",
        "kaj" to "\u0995\u09BE\u099C",
        "bhaloi" to "\u09AD\u09BE\u09B2\u09CB\u0987",
    )

    private val LOANWORDS: Map<String, String> = mapOf(
        "current" to "\u0995\u09BE\u09B0\u09C7\u09A8\u09CD\u099F",
        "public" to "\u09AA\u09BE\u09AC\u09B2\u09BF\u0995",
        "news" to "\u09A8\u09BF\u0989\u099C",
        "police" to "\u09AA\u09C1\u09B2\u09BF\u09B6",
        "college" to "\u0995\u09B2\u09C7\u099C",
        "message" to "\u09AE\u09C7\u09B8\u09C7\u099C",
        "group" to "\u0997\u09CD\u09B0\u09C1\u09AA",
        "study" to "\u09B8\u09CD\u099F\u09BE\u09A1\u09BF",
        "exam" to "\u098F\u0995\u09CD\u09B8\u09BE\u09AE",
        "result" to "\u09B0\u09C7\u099C\u09BE\u09B2\u09CD\u099F",
        "online" to "\u0985\u09A8\u09BE\u0987\u09A8",
        "call" to "\u0995\u09B2",
        "ok" to "\u0993\u0995\u09C7",
        "sir" to "\u09B8\u09CD\u09AF\u09BE\u09B0",
        "madam" to "\u09AE\u09CD\u09AF\u09BE\u09A1\u09BE\u09AE",
        "class" to "\u0995\u09CD\u09B2\u09BE\u09B8",
        "teacher" to "\u099F\u09BF\u099A\u09BE\u09B0",
        "hostel" to "\u09B9\u09B8\u09CD\u099F\u09C7\u09B2",
        "signature" to "\u09B8\u09BF\u0997\u09A8\u09C7\u099A\u09BE\u09B0",
        "sign" to "\u09B8\u09BE\u0987\u09A8",
        "bus" to "\u09AC\u09BE\u09B8",
        "train" to "\u099F\u09CD\u09B0\u09C7\u09A8",
        "phone" to "\u09AB\u09CB\u09A8",
        "mobile" to "\u09AE\u09CB\u09AC\u09BE\u0987\u09B2",
        "app" to "\u0985\u09CD\u09AF\u09BE\u09AA",
        "video" to "\u09AD\u09BF\u09A1\u09BF\u0993",
        "song" to "\u0997\u09BE\u09A8",
        "movie" to "\u09AE\u09C1\u09AD\u09BF",
        "time" to "\u099F\u09BE\u0987\u09AE",
        "today" to "\u099F\u09C1\u09A1\u09C7",
        "tomorrow" to "\u099F\u09AE\u09B0\u09CB",
        "update" to "\u0986\u09AA\u09A1\u09C7\u099F",
        "file" to "\u09AB\u09BE\u0987\u09B2",
        "market" to "\u09AE\u09BE\u09B0\u09CD\u0995\u09C7\u099F",
    )

    private val DETECT_EXCLUDE: Set<String> = setOf("ache", "ar", "bon", "cha", "chure", "din", "ei", "eta", "gare", "ghar", "ghor", "hoy", "jol", "kal", "ke", "na", "oi", "ora", "ota", "raat", "se", "tar")

    private val DETECT: Set<String> = BENGALI_WORDS.keys
        .filter { it !in DETECT_EXCLUDE && it.length >= 3 }
        .toSet() + "ki"

    /** Returns Bengali script for romanized Bengali text; any other text is returned as-is. */
    fun prepareForSpeech(text: String): String {
        if (!isRomanizedBengali(text)) return text
        return TOKEN.replace(text) { match -> convertWord(match.value) }
    }

    /**
     * Two or more Bengali-specific words, making up at least a quarter of the Latin words.
     * A single word counts only when it is the whole text, so English sentences stay English.
     */
    fun isRomanizedBengali(text: String): Boolean {
        val words = TOKEN.findAll(text).map { it.value.lowercase(Locale.ROOT) }.toList()
        if (words.isEmpty()) return false
        val hits = words.count { it in DETECT }
        return if (words.size == 1) hits == 1 else hits >= 2 && hits * 4 >= words.size
    }

    private fun convertWord(word: String): String {
        val key = word.lowercase(Locale.ROOT)
        return BENGALI_WORDS[key] ?: LOANWORDS[key] ?: phonetic(key)
    }

    private fun phonetic(word: String): String {
        val out = StringBuilder()
        var index = 0
        var prev = Prev.NONE
        while (index < word.length) {
            val vowel = VOWELS.firstOrNull { word.startsWith(it.latin, index) }
            if (vowel != null) {
                out.append(if (prev == Prev.CONSONANT) vowel.matra else vowel.independent)
                index += vowel.latin.length
                prev = Prev.VOWEL
                continue
            }
            val consonant = CONSONANTS.firstOrNull { word.startsWith(it.first, index) }
            if (consonant != null) {
                val latin = consonant.first
                if (latin == "ng") {
                    out.append(ANUSVARA)
                    prev = Prev.VOWEL
                } else {
                    out.append(consonant.second)
                    val doubled = latin.length == 1 && index > 0 &&
                        index + 1 < word.length && word[index + 1] == latin[0]
                    if (doubled) out.append(HASANT)
                    prev = Prev.CONSONANT
                }
                index += latin.length
                continue
            }
            out.append(word[index])
            prev = Prev.NONE
            index += 1
        }
        return out.toString()
    }
}
'@

Write-NewFile "$testBase\RomanizedBengaliTest.kt" @'
package `in`.diganta.aurabutler.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RomanizedBengaliTest {
    @Test
    fun detectsBanglishSentences() {
        assertTrue(RomanizedBengali.isRomanizedBengali("Bolchi kichu habe nato"))
        assertTrue(RomanizedBengali.isRomanizedBengali("Vai Current ki asbe na??"))
    }

    @Test
    fun convertsBanglishToBengaliScript() {
        assertEquals(
            "\u09AC\u09B2\u099B\u09BF \u0995\u09BF\u099B\u09C1 \u09B9\u09AC\u09C7 \u09A8\u09BE\u09A4\u09CB",
            RomanizedBengali.prepareForSpeech("Bolchi kichu habe nato")
        )
        assertEquals(
            "\u098F\u099F\u09BE \u0995\u09C7 \u09AA\u09BE\u09AC\u09B2\u09BF\u0995 \u0995\u09B0\u09C7 \u09A6\u09BF\u09AF\u09BC\u09C7\u099B\u09BF",
            RomanizedBengali.prepareForSpeech("ata ke public kore diyechi")
        )
        assertEquals(
            "\u0986\u09AE\u09BF \u0995\u09BE\u09B2 \u09AF\u09BE\u09AC\u09CB",
            RomanizedBengali.prepareForSpeech("Ami kal jabo")
        )
    }

    @Test
    fun leavesEnglishTextUnchanged() {
        val text = "Your battery level is 100 percent and healthy."
        assertFalse(RomanizedBengali.isRomanizedBengali(text))
        assertEquals(text, RomanizedBengali.prepareForSpeech(text))
        val mixed = "I am Ami, your assistant."
        assertEquals(mixed, RomanizedBengali.prepareForSpeech(mixed))
    }

    @Test
    fun conversionIsIdempotent() {
        val once = RomanizedBengali.prepareForSpeech("Bolchi kichu habe nato")
        assertEquals(once, RomanizedBengali.prepareForSpeech(once))
    }
}
'@

$sanOld = '        val language = SpeechLanguageSupport.resolveLanguageTag(raw, languageTag)'
$sanLine1 = '        // Romanized Bengali (Bengali in English letters) becomes Bengali script first,'
$sanLine2 = '        // so the Bengali voice pronounces it instead of English letter-to-sound rules.'
$sanLine3 = '        val source = RomanizedBengali.prepareForSpeech(raw)'
$sanLine4 = '        val language = SpeechLanguageSupport.resolveLanguageTag(source, languageTag)'
$sanNew = $sanLine1 + $nl + $sanLine2 + $nl + $sanLine3 + $nl + $sanLine4
Edit-File "$base\voice\SpeechTextSanitizer.kt" $sanOld $sanNew

Edit-File "$base\voice\SpeechTextSanitizer.kt" `
    'return applyPronunciation(raw, language)' `
    'return applyPronunciation(source, language)'

# ---------------------------------------------------------------- record created files (for -Undo)
Set-Content -Path (Join-Path $backupDir 'NEW_FILES.txt') -Value $newFiles -Encoding ASCII

# ---------------------------------------------------------------- optional unit tests
if ($RunTests) {
    Write-Step "Running unit tests"
    $gradlew = Join-Path $ProjectRoot 'android\gradlew.bat'
    if (Test-Path $gradlew) {
        Push-Location (Join-Path $ProjectRoot 'android')
        try {
            & $gradlew ':app:testDebugUnitTest' '--tests' '*RomanizedBengali*' '--tests' '*SpeechTextSanitizer*'
            if ($LASTEXITCODE -ne 0) {
                throw "Unit tests failed (exit $LASTEXITCODE). Changes are still applied. Send me the first error block, or run with -Undo."
            }
        } finally { Pop-Location }
    } else {
        Write-Host "  android\gradlew.bat not found. Open the android folder in Android Studio and run RomanizedBengaliTest." -ForegroundColor Yellow
    }
}

Write-Host ""
Write-Host "Phase 1 applied." -ForegroundColor Green
Write-Host "Next: open the android folder in Android Studio, Build > Rebuild Project, then install the debug build."
Write-Host "Phone: install the Bengali (India) voice: Settings > Google Text-to-speech (or Speech) > Install voice data > Bengali."
Write-Host "AURA Settings: if the old notification-alert switch was already ON on this phone, switch 'Important message voice alerts' OFF once."
