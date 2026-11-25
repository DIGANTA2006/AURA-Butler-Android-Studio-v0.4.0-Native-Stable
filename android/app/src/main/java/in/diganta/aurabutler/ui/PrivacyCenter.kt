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