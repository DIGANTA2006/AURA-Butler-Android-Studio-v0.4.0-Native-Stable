<#
  AURA Phase 1b - notification speech filter

  Important notifications are spoken ONLY from:
    - WhatsApp
    - mail (Gmail, Outlook and similar)
    - college sources (classroom apps, or college/exam/notice wording from any app)
  Other apps (SMS, Slack, Jira, Telegram, shopping, banks) stay on screen,
  even when they are marked CRITICAL.
  The voice assistant (mic button and the word "Aura") is not changed.
  The "Tell me important messages immediately" switch is set ON by default.

  Works whether or not Phase 1 was already applied.

  Run:   powershell -ExecutionPolicy Bypass -File .\Apply-AURA-Phase1b-Alert-Filter.ps1
  Undo:  powershell -ExecutionPolicy Bypass -File .\Apply-AURA-Phase1b-Alert-Filter.ps1 -Undo
#>
[CmdletBinding()]
param(
    [string]$ProjectRoot = 'E:\MY PROJECT FOLDER\AURA PROJECT\AURA-Butler-Android-Studio-v0.4.0-Native-Stable',
    [switch]$Undo
)

$ErrorActionPreference = 'Stop'
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$nl = "`n"

$base     = 'android\app\src\main\java\in\diganta\aurabutler'
$testBase = 'android\app\src\test\java\in\diganta\aurabutler\analyzer'

$editedFiles = @(
    "$base\analyzer\ImportantMessageSentinel.kt",
    "$base\model\Models.kt",
    "$base\ui\AuraApp.kt"
)
$newFiles = @(
    "$testBase\ImportantMessageSentinelFilterTest.kt"
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
$sentinelPath = Join-Path $ProjectRoot "$base\analyzer\ImportantMessageSentinel.kt"
if (-not (Test-Path $sentinelPath)) {
    throw "AURA source not found under: $ProjectRoot`nRe-run with -ProjectRoot 'D:\path\to\AURA-Butler-Android-Studio-v0.4.0-Native-Stable'"
}

# ---------------------------------------------------------------- undo
if ($Undo) {
    Write-Step "Undoing the latest Phase 1b apply"
    $last = Get-ChildItem $backupRoot -Directory -Filter 'phase1b-*' -ErrorAction SilentlyContinue |
        Sort-Object Name | Select-Object -Last 1
    if (-not $last) { throw "No Phase 1b backup found in $backupRoot" }
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
$sentinelText = [System.IO.File]::ReadAllText($sentinelPath, [System.Text.Encoding]::UTF8)
if ($sentinelText.Contains('isSpeakableSource')) {
    Write-Host "Phase 1b is already applied. Nothing changed." -ForegroundColor Yellow
    return
}

# ---------------------------------------------------------------- backup
Write-Step "Backing up the files this phase touches"
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$backupDir = Join-Path $backupRoot "phase1b-$stamp"
New-Item -ItemType Directory -Path $backupDir -Force | Out-Null
foreach ($rel in $editedFiles) {
    $dest = Join-Path $backupDir $rel
    New-Item -ItemType Directory -Path (Split-Path $dest -Parent) -Force | Out-Null
    Copy-Item (Join-Path $ProjectRoot $rel) $dest
}
Write-Host "  backup -> $backupDir"

# ---------------------------------------------------------------- 1. speak only WhatsApp, mail and college
Write-Step "1/3 Speak only WhatsApp, mail and college notifications"

$gateOld = @(
    '        if (item.priority.weight < NotificationPriority.IMPORTANT.weight) return',
    '        if (',
    '            item.priority != NotificationPriority.CRITICAL &&',
    '            item.channel !in SPEAKABLE_CHANNELS',
    '        ) return'
) -join $nl
$gateNew = @(
    '        if (item.priority.weight < NotificationPriority.IMPORTANT.weight) return',
    '        if (!isSpeakableSource(item)) return'
) -join $nl
Edit-File "$base\analyzer\ImportantMessageSentinel.kt" $gateOld $gateNew

$setOld = @(
    '    private val SPEAKABLE_CHANNELS = setOf(',
    '        AnalyzerChannel.WHATSAPP,',
    '        AnalyzerChannel.MAIL,',
    '        AnalyzerChannel.SLACK,',
    '        AnalyzerChannel.JIRA,',
    '        AnalyzerChannel.MESSAGING',
    '    )'
) -join $nl
$setNew = @(
    '    private val SPEAKABLE_CHANNELS = setOf(',
    '        AnalyzerChannel.WHATSAPP,',
    '        AnalyzerChannel.MAIL',
    '    )',
    '',
    '    private val COLLEGE_APP_HINTS = listOf("classroom", "moodle", "college", "university", "campus")',
    '',
    '    private val COLLEGE_TERMS = Regex(',
    '        """\b(?:college|cemk|kolaghat|makaut|university|classroom|class|exam|exams|lab|labs|attendance|assignment|hostel|semester|professor|principal|viva|notice|admit card)\b""",',
    '        RegexOption.IGNORE_CASE',
    '    )',
    '',
    '    /**',
    '     * Speaks only WhatsApp, mail and college-related notifications.',
    '     * Other apps (SMS, Slack, Jira, Telegram, shopping, banks) stay on screen,',
    '     * even when the classifier marks them CRITICAL.',
    '     */',
    '    internal fun isSpeakableSource(item: CapturedNotification): Boolean {',
    '        if (item.channel in SPEAKABLE_CHANNELS) return true',
    '        return isCollegeRelated(item)',
    '    }',
    '',
    '    internal fun isCollegeRelated(item: CapturedNotification): Boolean {',
    '        val app = "${item.packageName} ${item.appName}".lowercase()',
    '        if (COLLEGE_APP_HINTS.any { app.contains(it) }) return true',
    '        val text = "${item.title} ${item.sender} ${item.text} ${item.subText}"',
    '        return COLLEGE_TERMS.containsMatchIn(text)',
    '    }'
) -join $nl
Edit-File "$base\analyzer\ImportantMessageSentinel.kt" $setOld $setNew

# ---------------------------------------------------------------- 2. switch ON by default (either Phase 1 state)
Write-Step "2/3 'Tell me important messages immediately' is ON by default"
$modelsRel = "$base\model\Models.kt"
$modelsText = [System.IO.File]::ReadAllText((Join-Path $ProjectRoot $modelsRel), [System.Text.Encoding]::UTF8)
if ($modelsText.Contains('val importantMessageVoiceAlerts: Boolean = false,')) {
    Edit-File $modelsRel 'val importantMessageVoiceAlerts: Boolean = false,' 'val importantMessageVoiceAlerts: Boolean = true,'
} elseif ($modelsText.Contains('val importantMessageVoiceAlerts: Boolean = true,')) {
    Write-Host "  default already ON in Models.kt"
} else {
    throw "Could not find the importantMessageVoiceAlerts default in Models.kt"
}

# ---------------------------------------------------------------- 3. settings text + unit tests
Write-Step "3/3 Settings description and unit tests"
Edit-File "$base\ui\AuraApp.kt" `
    'For locally classified important WhatsApp, mail, SMS, Slack, Jira and messaging notifications, AURA speaks an alert immediately. This classifier does not require cloud AI.' `
    'AURA speaks an alert only for important WhatsApp, mail and college notifications (college, classroom, exam, notice and similar). Other apps stay on screen. Runs fully on the phone.'

Write-NewFile "$testBase\ImportantMessageSentinelFilterTest.kt" @'
package `in`.diganta.aurabutler.analyzer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportantMessageSentinelFilterTest {
    private fun notification(
        packageName: String,
        appName: String,
        channel: AnalyzerChannel,
        title: String,
        text: String
    ) = CapturedNotification(
        notificationKey = "key-$packageName-$text",
        packageName = packageName,
        appName = appName,
        title = title,
        text = text,
        subText = "",
        sender = title,
        channel = channel,
        priority = NotificationPriority.IMPORTANT,
        postedAtEpochMs = 0L
    )

    @Test
    fun speaksWhatsAppAndMail() {
        assertTrue(
            ImportantMessageSentinel.isSpeakableSource(
                notification("com.whatsapp", "WhatsApp", AnalyzerChannel.WHATSAPP, "Ravi", "hi")
            )
        )
        assertTrue(
            ImportantMessageSentinel.isSpeakableSource(
                notification("com.google.android.gm", "Gmail", AnalyzerChannel.MAIL, "Prof", "Assignment due")
            )
        )
    }

    @Test
    fun speaksCollegeNotificationsFromAnyApp() {
        assertTrue(
            ImportantMessageSentinel.isSpeakableSource(
                notification("com.google.android.apps.classroom", "Classroom", AnalyzerChannel.OTHER, "Teacher", "New post")
            )
        )
        assertTrue(
            ImportantMessageSentinel.isSpeakableSource(
                notification("com.example.news", "Daily", AnalyzerChannel.OTHER, "Notice", "College exam schedule")
            )
        )
    }

    @Test
    fun keepsOtherAppsSilentEvenWhenCritical() {
        assertFalse(
            ImportantMessageSentinel.isSpeakableSource(
                notification("com.example.shop", "Shop", AnalyzerChannel.OTHER, "Sale", "Big discount")
            )
        )
        assertFalse(
            ImportantMessageSentinel.isSpeakableSource(
                notification("com.slack", "Slack", AnalyzerChannel.SLACK, "Team", "deploy done")
            )
        )
        assertFalse(
            ImportantMessageSentinel.isSpeakableSource(
                notification(
                    "com.google.android.apps.messaging",
                    "Messages",
                    AnalyzerChannel.MESSAGING,
                    "HDFC",
                    "Account debited Rs 500"
                ).copy(priority = NotificationPriority.CRITICAL)
            )
        )
    }

    @Test
    fun wordBoundariesStopLabelMatchingLab() {
        assertFalse(
            ImportantMessageSentinel.isSpeakableSource(
                notification("com.example.x", "X", AnalyzerChannel.OTHER, "Label", "new label printed")
            )
        )
    }
}
'@

Set-Content -Path (Join-Path $backupDir 'NEW_FILES.txt') -Value $newFiles -Encoding ASCII

Write-Host ""
Write-Host "Phase 1b applied." -ForegroundColor Green
Write-Host "Next: Android Studio > Build > Rebuild Project, install, then check Settings > Tell me important messages immediately."
Write-Host "If an older install still speaks other apps, the saved switch may be stale: turn it OFF and back ON once."
