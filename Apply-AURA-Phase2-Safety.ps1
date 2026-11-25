<#
  AURA Phase 2 - stability and safety

  1. App cache cleaner: clears only Android cache folders (temporary screen captures,
     image copies). Chats, memory, reminders, settings and models are never touched.
  2. Privacy and security center (Settings > Privacy and security):
     - Clear app cache, with the size shown before and after.
     - Link checker: an offline phishing and risk check for a link you paste.
       It is a warning aid, not a guarantee.
     - Installed-app review: apps holding sensitive access (SMS, call log, microphone,
       drawing over other apps, installing apps) and apps installed outside the Play
       Store. Also lists accessibility services and notification readers that are on.
       This is a review, NOT an antivirus scan.
     - Opens Android security settings, where Play Protect runs its scan.
  3. Bengali or Hindi voice missing: AURA now says which voice data to install,
     instead of the generic "could not start either local voice" message.
  4. Adds QUERY_ALL_PACKAGES to the manifest so the app review can see installed apps
     on Android 11 and later. (Play Store builds need a policy declaration for this;
     personal builds are fine.)

  Safety: every touched file is backed up first. An edit stops the script if its
  anchor text is not found exactly once. A second run is refused.

  Run:   powershell -ExecutionPolicy Bypass -File .\Apply-AURA-Phase2-Safety.ps1
  Undo:  powershell -ExecutionPolicy Bypass -File .\Apply-AURA-Phase2-Safety.ps1 -Undo
#>
[CmdletBinding()]
param(
    [string]$ProjectRoot = 'E:\MY PROJECT FOLDER\AURA PROJECT\AURA-Butler-Android-Studio-v0.4.0-Native-Stable',
    [switch]$Undo
)

$ErrorActionPreference = 'Stop'
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$nl = "`n"

$appRoot  = 'android\app\src\main'
$base     = "$appRoot\java\in\diganta\aurabutler"
$testBase = 'android\app\src\test\java\in\diganta\aurabutler'
$manifest = "$appRoot\AndroidManifest.xml"

$editedFiles = @(
    "$base\ui\AuraApp.kt",
    "$base\voice\SpeechLanguageSupport.kt",
    $manifest
)
$newFiles = @(
    "$base\maintenance\CacheCleaner.kt",
    "$base\maintenance\LinkSafetyChecker.kt",
    "$base\maintenance\DeviceSecurityAudit.kt",
    "$base\ui\PrivacyCenter.kt",
    "$testBase\maintenance\LinkSafetyCheckerTest.kt"
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
$appPath = Join-Path $ProjectRoot "$base\ui\AuraApp.kt"
if (-not (Test-Path $appPath)) {
    throw "AURA source not found under: $ProjectRoot`nRe-run with -ProjectRoot 'D:\path\to\AURA-Butler-Android-Studio-v0.4.0-Native-Stable'"
}

# ---------------------------------------------------------------- undo
if ($Undo) {
    Write-Step "Undoing the latest Phase 2 apply"
    $last = Get-ChildItem $backupRoot -Directory -Filter 'phase2-*' -ErrorAction SilentlyContinue |
        Sort-Object Name | Select-Object -Last 1
    if (-not $last) { throw "No Phase 2 backup found in $backupRoot" }
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
$appText = [System.IO.File]::ReadAllText($appPath, [System.Text.Encoding]::UTF8)
if ($appText.Contains('PrivacyCenterEntry()')) {
    Write-Host "Phase 2 is already applied. Nothing changed." -ForegroundColor Yellow
    return
}

# ---------------------------------------------------------------- backup
Write-Step "Backing up the files this phase touches"
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$backupDir = Join-Path $backupRoot "phase2-$stamp"
New-Item -ItemType Directory -Path $backupDir -Force | Out-Null
foreach ($rel in $editedFiles) {
    $dest = Join-Path $backupDir $rel
    New-Item -ItemType Directory -Path (Split-Path $dest -Parent) -Force | Out-Null
    Copy-Item (Join-Path $ProjectRoot $rel) $dest
}
Write-Host "  backup -> $backupDir"

# ---------------------------------------------------------------- 1. new modules
Write-Step "1/4 New safety modules (cache cleaner, link checker, app review)"
Write-NewFile "$base\maintenance\CacheCleaner.kt" @'
package `in`.diganta.aurabutler.maintenance

import android.content.Context
import java.io.File

/**
 * Clears disposable app cache only: Android's cache folders, which include AURA's
 * temporary screen captures and image copies. Chats, memory, reminders, settings,
 * the encrypted stores and the speech and embedding models live in filesDir, the
 * APK or noBackupFilesDir and are never touched here.
 */
object CacheCleaner {
    data class CleanupReport(val freedBytes: Long, val removedItems: Int)

    fun cacheBytes(context: Context): Long =
        cacheRoots(context).sumOf { sizeOf(it) }

    fun clear(context: Context): CleanupReport {
        var freed = 0L
        var removed = 0
        cacheRoots(context).forEach { root ->
            root.listFiles()?.forEach { entry ->
                val size = sizeOf(entry)
                if (runCatching { entry.deleteRecursively() }.getOrDefault(false)) {
                    freed += size
                    removed += 1
                }
            }
        }
        return CleanupReport(freedBytes = freed, removedItems = removed)
    }

    fun formatBytes(bytes: Long): String = when {
        bytes >= 1_048_576L -> "%.2f MB".format(bytes / 1_048_576.0)
        bytes >= 1_024L -> "%.1f KB".format(bytes / 1_024.0)
        else -> "$bytes bytes"
    }

    private fun cacheRoots(context: Context): List<File> =
        listOfNotNull(context.cacheDir, context.externalCacheDir)

    private fun sizeOf(file: File): Long = when {
        file.isFile -> file.length()
        file.isDirectory -> file.listFiles()?.sumOf { sizeOf(it) } ?: 0L
        else -> 0L
    }
}
'@
Write-NewFile "$base\maintenance\LinkSafetyChecker.kt" @'
package `in`.diganta.aurabutler.maintenance

import java.net.URI

/**
 * Offline check for pasted or received links. It looks for common phishing and
 * malware-delivery patterns. It cannot prove that a link is safe, so treat the
 * result as a warning, not a guarantee.
 */
object LinkSafetyChecker {
    enum class Level { SAFE, CAUTION, HIGH_RISK, INVALID }

    data class Verdict(val level: Level, val host: String, val reasons: List<String>)

    private val officialDomains = setOf(
        "google.com", "gmail.com", "youtube.com", "whatsapp.com", "facebook.com",
        "instagram.com", "amazon.com", "amazon.in", "netflix.com", "apple.com",
        "microsoft.com", "paypal.com", "paytm.com", "phonepe.com", "sbi.co.in",
        "hdfcbank.com", "icicibank.com", "axisbank.com", "wikipedia.org",
        "github.com", "linkedin.com", "telegram.org"
    )
    private val shorteners = setOf(
        "bit.ly", "tinyurl.com", "t.co", "goo.gl", "cutt.ly", "rb.gy",
        "is.gd", "shorturl.at", "ow.ly", "tiny.cc", "rebrand.ly"
    )
    private val riskyTlds = setOf(
        "zip", "mov", "xyz", "top", "click", "icu", "tk", "gq", "ml", "cf", "ga",
        "work", "loan", "country", "download"
    )
    private val lureWords = listOf(
        "verify", "secure", "login", "signin", "account", "update", "confirm",
        "wallet", "bonus", "prize", "reward", "kyc", "otp", "unlock", "suspend"
    )
    private val brandWords = listOf(
        "paypal", "google", "gmail", "whatsapp", "facebook", "instagram", "amazon",
        "netflix", "apple", "microsoft", "sbi", "hdfc", "icici", "axis", "paytm", "phonepe"
    )
    private val riskyFileEndings = listOf(
        ".apk", ".exe", ".scr", ".zip", ".rar", ".bat", ".msi", ".vbs", ".ps1"
    )
    private val ipv4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")

    fun check(raw: String): Verdict {
        val input = raw.trim()
        if (input.isEmpty()) {
            return Verdict(Level.INVALID, "", listOf("Paste a link to check."))
        }
        val candidate = if (input.contains("://")) input else "https://$input"
        val uri = runCatching { URI(candidate) }.getOrNull()
            ?: return Verdict(Level.INVALID, "", listOf("This does not look like a web address."))
        val host = uri.host?.lowercase()
            ?: return Verdict(Level.INVALID, "", listOf("No website name was found in this link."))
        val path = (uri.path ?: "").lowercase()
        val scheme = (uri.scheme ?: "").lowercase()
        val labels = host.split('.')
        val official = officialDomains.any { host == it || host.endsWith(".$it") }

        var points = 0
        val reasons = mutableListOf<String>()

        if (scheme != "https" && scheme != "http") {
            points += 4
            reasons += "Uses an unusual protocol ($scheme)."
        } else if (scheme == "http") {
            points += 1
            reasons += "Not encrypted (http instead of https)."
        }
        if (uri.rawUserInfo != null) {
            points += 4
            reasons += "Hides a login inside the address (user@site), a common trick."
        }
        if (uri.port != -1 && uri.port != 80 && uri.port != 443) {
            points += 1
            reasons += "Uses an unusual port (${uri.port})."
        }
        if (ipv4.matches(host) || host.contains(':')) {
            points += 3
            reasons += "Uses a raw IP address instead of a website name."
        }
        if (labels.any { it.startsWith("xn--") }) {
            points += 2
            reasons += "Uses encoded letters that can imitate other sites."
        }
        if (host in shorteners) {
            points += 1
            reasons += "Shortened link: the real destination is hidden."
        }
        if (labels.size >= 5) {
            points += 1
            reasons += "Many nested subdomains."
        }
        if (host.count { it == '-' } >= 3) {
            points += 1
            reasons += "Long hyphenated name, common in fake sites."
        }
        if (!official) {
            val tld = labels.last()
            if (tld in riskyTlds) {
                points += 1
                reasons += "The .$tld ending is often used for throwaway sites."
            }
            val brand = brandWords.firstOrNull { b ->
                labels.any { label -> label.split('-').contains(b) }
            }
            if (brand != null) {
                points += 2
                reasons += "Mentions $brand but is not that company's official domain."
            }
            val lure = lureWords.firstOrNull { w -> host.contains(w) || path.contains(w) }
            if (lure != null) {
                points += 1
                reasons += "Uses urgent or login wording ('$lure')."
            }
            if (riskyFileEndings.any { path.endsWith(it) }) {
                points += 3
                reasons += "Downloads a file type that can install or run software."
            }
        }

        val level = when {
            points >= 4 -> Level.HIGH_RISK
            points >= 1 -> Level.CAUTION
            else -> Level.SAFE
        }
        if (reasons.isEmpty()) {
            reasons += if (official) {
                "Official domain. No warning signs found."
            } else {
                "No common warning signs found. This is not a guarantee."
            }
        }
        return Verdict(level, host, reasons)
    }
}
'@
Write-NewFile "$base\maintenance\DeviceSecurityAudit.kt" @'
package `in`.diganta.aurabutler.maintenance

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

/**
 * Local review of installed apps and sensitive access. It reads package metadata
 * and the system's accessibility and notification-listener settings. It does not
 * inspect app contents and is not an antivirus scan.
 */
object DeviceSecurityAudit {
    data class AppFinding(
        val appName: String,
        val packageName: String,
        val riskyPermissions: List<String>,
        val sideloaded: Boolean
    ) {
        val summary: String
            get() {
                val parts = mutableListOf<String>()
                if (riskyPermissions.isNotEmpty()) parts += riskyPermissions.joinToString(", ")
                if (sideloaded) parts += "installed outside the Play Store"
                return parts.joinToString("; ")
            }
    }

    data class Report(
        val appsScanned: Int,
        val findings: List<AppFinding>,
        val accessibilityServices: List<String>,
        val notificationListeners: List<String>
    )

    private val riskyPermissions = linkedMapOf(
        "android.permission.READ_SMS" to "read SMS",
        "android.permission.RECEIVE_SMS" to "receive SMS",
        "android.permission.SEND_SMS" to "send SMS",
        "android.permission.READ_CALL_LOG" to "call log",
        "android.permission.SYSTEM_ALERT_WINDOW" to "draw over other apps",
        "android.permission.REQUEST_INSTALL_PACKAGES" to "install other apps",
        "android.permission.RECORD_AUDIO" to "microphone",
        "android.permission.CAMERA" to "camera",
        "android.permission.READ_CONTACTS" to "contacts",
        "android.permission.ACCESS_FINE_LOCATION" to "precise location",
        "android.permission.PACKAGE_USAGE_STATS" to "app usage history"
    )

    private val trustedInstallers = setOf(
        "com.android.vending",
        "com.amazon.venezia",
        "com.sec.android.app.samsungapps"
    )

    fun scan(context: Context): Report {
        val pm = context.packageManager
        val self = context.packageName
        val apps = pm.getInstalledApplications(0).filter { app ->
            app.packageName != self &&
                (app.flags and ApplicationInfo.FLAG_SYSTEM) == 0 &&
                (app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
        }
        val findings = apps
            .mapNotNull { app -> findingFor(pm, app) }
            .sortedByDescending { it.riskyPermissions.size + if (it.sideloaded) 1 else 0 }
        return Report(
            appsScanned = apps.size,
            findings = findings,
            accessibilityServices = enabledServices(context, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
            notificationListeners = enabledServices(context, Settings.Secure.ENABLED_NOTIFICATION_LISTENERS)
        )
    }

    @Suppress("DEPRECATION")
    private fun findingFor(pm: PackageManager, app: ApplicationInfo): AppFinding? {
        val info: PackageInfo = runCatching {
            pm.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS)
        }.getOrNull() ?: return null
        val requested: Array<String> = info.requestedPermissions ?: return null
        val flags: IntArray = info.requestedPermissionsFlags ?: IntArray(0)

        val granted = mutableListOf<String>()
        requested.forEachIndexed { index, permission ->
            val label = riskyPermissions[permission] ?: return@forEachIndexed
            val isGranted = index < flags.size &&
                (flags[index] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
            if (isGranted) granted += label
        }

        val installer = installerOf(pm, app.packageName)
        val sideloaded = installer == null || installer !in trustedInstallers
        if (granted.isEmpty() && !sideloaded) return null

        val label = pm.getApplicationLabel(app).toString()
        return AppFinding(
            appName = label,
            packageName = app.packageName,
            riskyPermissions = granted,
            sideloaded = sideloaded
        )
    }

    @Suppress("DEPRECATION")
    private fun installerOf(pm: PackageManager, packageName: String): String? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pm.getInstallSourceInfo(packageName).installingPackageName
        } else {
            pm.getInstallerPackageName(packageName)
        }
    }.getOrNull()

    private fun enabledServices(context: Context, key: String): List<String> {
        val raw = Settings.Secure.getString(context.contentResolver, key).orEmpty()
        return raw.split(':')
            .map { it.substringBefore('/').trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .map { pkg -> if (pkg == context.packageName) "$pkg (AURA)" else pkg }
    }
}
'@

# ---------------------------------------------------------------- 2. privacy center UI
Write-Step "2/4 Privacy and security center and its Settings entry"
Write-NewFile "$base\ui\PrivacyCenter.kt" @'
package `in`.diganta.aurabutler.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import `in`.diganta.aurabutler.maintenance.CacheCleaner
import `in`.diganta.aurabutler.maintenance.DeviceSecurityAudit
import `in`.diganta.aurabutler.maintenance.LinkSafetyChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings entry card. Opens the privacy and security center. */
@Composable
fun PrivacyCenterEntry() {
    var showPrivacyCenter by remember { mutableStateOf(false) }
    Card(
        onClick = { showPrivacyCenter = true },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("Privacy and security", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Clear app cache, check a link before you open it, and review installed apps that hold sensitive access.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
    if (showPrivacyCenter) {
        PrivacyCenterDialog(onDismiss = { showPrivacyCenter = false })
    }
}

@Composable
private fun PrivacyCenterDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var cacheText by remember { mutableStateOf("Calculating...") }
    var cacheStatus by remember { mutableStateOf("") }

    var linkInput by remember { mutableStateOf("") }
    var linkVerdict by remember { mutableStateOf<LinkSafetyChecker.Verdict?>(null) }

    var scanBusy by remember { mutableStateOf(false) }
    var scanReport by remember { mutableStateOf<DeviceSecurityAudit.Report?>(null) }

    LaunchedEffect(Unit) {
        val bytes = withContext(Dispatchers.IO) { CacheCleaner.cacheBytes(context) }
        cacheText = CacheCleaner.formatBytes(bytes)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Privacy and security") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                PrivacySectionHeader("App cache")
                Text("Cache size: $cacheText")
                if (cacheStatus.isNotEmpty()) {
                    Text(cacheStatus, style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = {
                    scope.launch {
                        val report = withContext(Dispatchers.IO) { CacheCleaner.clear(context) }
                        val after = withContext(Dispatchers.IO) { CacheCleaner.cacheBytes(context) }
                        cacheStatus = "Removed ${report.removedItems} item(s) and freed " +
                            "${CacheCleaner.formatBytes(report.freedBytes)}. Chats, memory and settings are kept."
                        cacheText = CacheCleaner.formatBytes(after)
                    }
                }) {
                    Text("Clear app cache")
                }

                PrivacySectionHeader("Check a link")
                OutlinedTextField(
                    value = linkInput,
                    onValueChange = {
                        linkInput = it
                        linkVerdict = null
                    },
                    label = { Text("Paste a link") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(onClick = { linkVerdict = LinkSafetyChecker.check(linkInput) }) {
                    Text("Check link")
                }
                linkVerdict?.let { verdict ->
                    Text(privacyVerdictTitle(verdict.level), style = MaterialTheme.typography.titleSmall)
                    verdict.reasons.forEach { reason ->
                        Text("- $reason", style = MaterialTheme.typography.bodySmall)
                    }
                }

                PrivacySectionHeader("Installed apps and sensitive access")
                Text(
                    "Looks for apps that can read messages, record, or draw over other apps, and apps installed outside the Play Store. This is a review, not an antivirus scan.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedButton(
                    enabled = !scanBusy,
                    onClick = {
                        scanBusy = true
                        scope.launch {
                            scanReport = withContext(Dispatchers.IO) { DeviceSecurityAudit.scan(context) }
                            scanBusy = false
                        }
                    }
                ) {
                    Text(if (scanBusy) "Scanning..." else "Scan installed apps")
                }
                scanReport?.let { report ->
                    Text("Checked ${report.appsScanned} user-installed apps.", style = MaterialTheme.typography.bodySmall)
                    if (report.findings.isEmpty()) {
                        Text("No apps with high-risk access or outside-store installs were found.")
                    }
                    report.findings.take(20).forEach { finding ->
                        Text(
                            "${finding.appName}: ${finding.summary}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    val services = report.accessibilityServices.ifEmpty { listOf("none") }
                    val readers = report.notificationListeners.ifEmpty { listOf("none") }
                    Text("Accessibility services on: ${services.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
                    Text("Notification readers on: ${readers.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }) {
                    Text("Open Android security (Play Protect)")
                }
            }
        }
    )
}

@Composable
private fun PrivacySectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
}

private fun privacyVerdictTitle(level: LinkSafetyChecker.Level): String = when (level) {
    LinkSafetyChecker.Level.SAFE -> "No warning signs found"
    LinkSafetyChecker.Level.CAUTION -> "Caution: check before opening"
    LinkSafetyChecker.Level.HIGH_RISK -> "High risk: do not open or log in"
    LinkSafetyChecker.Level.INVALID -> "Not a usable link"
}
'@

$entryOld = @(
    '        item {',
    '            SwitchRow(',
    '                title = "Speak replies",'
) -join $nl
$entryNew = @(
    '        item { PrivacyCenterEntry() }',
    '        item {',
    '            SwitchRow(',
    '                title = "Speak replies",'
) -join $nl
Edit-File "$base\ui\AuraApp.kt" $entryOld $entryNew

$importOld = 'import `in`.diganta.aurabutler.voice.Speaker'
$importNew = $importOld + $nl + 'import `in`.diganta.aurabutler.voice.SpeechLanguageSupport'
Edit-File "$base\ui\AuraApp.kt" $importOld $importNew

# ---------------------------------------------------------------- 3. voice message for missing Bengali/Hindi data
Write-Step "3/4 Clear message when Bengali or Hindi voice data is missing"
Edit-File "$base\ui\AuraApp.kt" `
    '"AURA could not start either local voice. Raise media volume, then use Test AURA voice in Settings."' `
    'SpeechLanguageSupport.voiceUnavailableMessage(text, state.settings.speechLanguage)'

$supportAnchor = '    fun isAvailable(tts: TextToSpeech, languageTag: String): Boolean ='
$supportNew = @(
    '    /** Explains a missing voice in the language the reply is written in. */',
    '    fun voiceUnavailableMessage(text: String, preferredTag: String = AUTO): String =',
    '        when (resolveLanguageTag(text, preferredTag)) {',
    '            BENGALI -> "Bengali voice data is not installed on this phone. Install it from Google Text-to-speech settings: Install voice data, then Bengali. The reply stays on screen."',
    '            HINDI -> "Hindi voice data is not installed on this phone. Install it from Google Text-to-speech settings. The reply stays on screen."',
    '            else -> "AURA could not start either local voice. Raise media volume, then use Test AURA voice in Settings."',
    '        }',
    '',
    $supportAnchor
) -join $nl
Edit-File "$base\voice\SpeechLanguageSupport.kt" $supportAnchor $supportNew

# ---------------------------------------------------------------- 4. manifest: installed-app review
Write-Step "4/4 Manifest: allow the installed-app review to see all apps"
$manifestOld = '    <uses-permission android:name="android.permission.CALL_PHONE" />'
$manifestNew = $manifestOld + $nl + '    <uses-permission android:name="android.permission.QUERY_ALL_PACKAGES" />'
Edit-File $manifest $manifestOld $manifestNew

# ---------------------------------------------------------------- 5. unit test + record
Write-NewFile "$testBase\maintenance\LinkSafetyCheckerTest.kt" @'
package `in`.diganta.aurabutler.maintenance

import org.junit.Assert.assertEquals
import org.junit.Test

class LinkSafetyCheckerTest {
    private fun levelOf(link: String) = LinkSafetyChecker.check(link).level

    @Test
    fun officialHttpsLinkIsSafe() {
        assertEquals(LinkSafetyChecker.Level.SAFE, levelOf("https://www.google.com/search?q=weather"))
    }

    @Test
    fun officialBankSubdomainIsSafe() {
        assertEquals(LinkSafetyChecker.Level.SAFE, levelOf("https://www.sbi.co.in"))
    }

    @Test
    fun lookalikeLoginLinkIsHighRisk() {
        assertEquals(LinkSafetyChecker.Level.HIGH_RISK, levelOf("http://paypal-secure-login.xyz/verify"))
    }

    @Test
    fun rawIpLoginLinkIsHighRisk() {
        assertEquals(LinkSafetyChecker.Level.HIGH_RISK, levelOf("http://192.168.1.10/login"))
    }

    @Test
    fun credentialsInAddressAreHighRisk() {
        assertEquals(LinkSafetyChecker.Level.HIGH_RISK, levelOf("https://user:pass@evil.com"))
    }

    @Test
    fun shortenedLinkIsCaution() {
        assertEquals(LinkSafetyChecker.Level.CAUTION, levelOf("bit.ly/3abc"))
    }

    @Test
    fun plainTextIsInvalid() {
        assertEquals(LinkSafetyChecker.Level.INVALID, levelOf("not a link"))
    }
}
'@
Set-Content -Path (Join-Path $backupDir 'NEW_FILES.txt') -Value $newFiles -Encoding ASCII

Write-Host ""
Write-Host "Phase 2 applied." -ForegroundColor Green
Write-Host "Next: Android Studio > Build > Rebuild Project, then install."
Write-Host "Check: Settings > Privacy and security. Try Clear app cache, paste a link into Check link, and run Scan installed apps."
Write-Host "Note: the app review is a local permission check. Play Protect (opened from the same screen) is the system malware scanner."
