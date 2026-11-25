package `in`.diganta.aurabutler.telephony

import android.Manifest
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.telecom.TelecomManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import `in`.diganta.aurabutler.ui.theme.AuraTheme
import java.text.DateFormat
import java.util.Date

class AuraPhoneActivity : ComponentActivity() {
    private var dialNumber by mutableStateOf("")
    private var isDefaultDialer by mutableStateOf(false)
    private var fullScreenAlertsAllowed by mutableStateOf(true)
    private var callNotificationsAllowed by mutableStateOf(true)
    private var pendingCallNumber: String? = null

    private val dialerRoleRequest = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        refreshDialerRole()
    }

    private val notificationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        callNotificationsAllowed = granted
    }

    private val callPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val number = pendingCallNumber
        pendingCallNumber = null
        if (granted && !number.isNullOrBlank()) placeSimCall(number)
        else if (!granted) Toast.makeText(this, "Phone permission was not granted.", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleDialIntent(intent)
        AuraCallNotifications.createChannel(this)
        AuraCallRegistry.initializeStorage(applicationContext)
        setContent {
            AuraTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AuraPhoneScreen(
                        dialNumber = dialNumber,
                        isDefaultDialer = isDefaultDialer,
                        fullScreenAlertsAllowed = fullScreenAlertsAllowed,
                        callNotificationsAllowed = callNotificationsAllowed,
                        onDialNumberChange = { dialNumber = it.filter { char -> char.isDigit() || char in "+*#" }.take(32) },
                        onRequestDialerRole = ::requestDialerRole,
                        onPlaceCall = ::requestAndPlaceCall,
                        onOpenSystemDialerSettings = {
                            startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
                        },
                        onRequestCallNotifications = {
                            if (Build.VERSION.SDK_INT >= 33) {
                                notificationPermissionRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        },
                        onManageFullScreenAlerts = {
                            if (Build.VERSION.SDK_INT >= 34) {
                                startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                                    data = Uri.parse("package:$packageName")
                                })
                            }
                        }
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        refreshDialerRole()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDialIntent(intent)
    }

    private fun handleDialIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_DIAL) {
            dialNumber = intent.data?.schemeSpecificPart.orEmpty()
                .filter { it.isDigit() || it in "+*#" }
                .take(32)
        }
    }

    private fun refreshDialerRole() {
        isDefaultDialer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_DIALER) == true
        } else {
            getSystemService(TelecomManager::class.java)?.defaultDialerPackage == packageName
        }
        callNotificationsAllowed = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        fullScreenAlertsAllowed = if (Build.VERSION.SDK_INT >= 34) {
            getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() == true
        } else {
            true
        }
    }

    private fun requestDialerRole() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
            return
        }
        val roleManager = getSystemService(RoleManager::class.java)
        if (roleManager == null || !roleManager.isRoleAvailable(RoleManager.ROLE_DIALER)) {
            Toast.makeText(this, "Android does not expose the dialer role on this device.", Toast.LENGTH_LONG).show()
            return
        }
        dialerRoleRequest.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER))
    }

    private fun requestAndPlaceCall(number: String) {
        val normalized = number.trim()
        if (normalized.isBlank() || normalized.any { !(it.isDigit() || it in "+*#") }) {
            Toast.makeText(this, "Enter a valid phone number.", Toast.LENGTH_SHORT).show()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            pendingCallNumber = normalized
            callPermissionRequest.launch(Manifest.permission.CALL_PHONE)
            return
        }
        placeSimCall(normalized)
    }

    private fun placeSimCall(number: String) {
        val telecomManager = getSystemService(TelecomManager::class.java)
        if (telecomManager == null) {
            Toast.makeText(this, "Android Telecom is unavailable.", Toast.LENGTH_LONG).show()
            return
        }
        try {
            telecomManager.placeCall(Uri.fromParts("tel", number, null), Bundle())
        } catch (e: SecurityException) {
            Toast.makeText(this, "Could not start the SIM call: ${e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        } catch (e: Throwable) {
            Toast.makeText(this, "Could not start the SIM call: ${e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        const val EXTRA_CALL_ID = "aura_call_id"
        const val EXTRA_SHOW_DIALPAD = "aura_show_dialpad"
    }
}

@Composable
private fun AuraPhoneScreen(
    dialNumber: String,
    isDefaultDialer: Boolean,
    fullScreenAlertsAllowed: Boolean,
    callNotificationsAllowed: Boolean,
    onDialNumberChange: (String) -> Unit,
    onRequestDialerRole: () -> Unit,
    onPlaceCall: (String) -> Unit,
    onOpenSystemDialerSettings: () -> Unit,
    onRequestCallNotifications: () -> Unit,
    onManageFullScreenAlerts: () -> Unit
) {
    val context = LocalContext.current
    val calls by AuraCallRegistry.snapshots.collectAsState()
    val audioEndpoints by AuraCallRegistry.audioEndpoints.collectAsState()
    val voipState by AuraVoipCallManager.state.collectAsState()
    var historyRevision by remember { mutableIntStateOf(0) }
    var retentionDays by remember { mutableIntStateOf(AuraCallRegistry.historyRetentionDays()) }
    var confirmClearHistory by remember { mutableStateOf(false) }
    val history = remember(calls, historyRevision, voipState.historyRevision) { AuraCallRegistry.recentHistory(20) }
    val historyAvailable = remember(calls, historyRevision, voipState.historyRevision) { AuraCallRegistry.historyStorageAvailable() }

    if (confirmClearHistory) {
        AlertDialog(
            onDismissRequest = { confirmClearHistory = false },
            title = { Text("Clear AURA call history?") },
            text = { Text("This permanently deletes all locally stored AURA call metadata. It does not alter your carrier's call log.") },
            confirmButton = {
                TextButton(onClick = {
                    val cleared = AuraCallRegistry.clearHistory()
                    historyRevision++
                    confirmClearHistory = false
                    if (!cleared) Toast.makeText(context, "Encrypted call history is unavailable.", Toast.LENGTH_LONG).show()
                }) { Text("Clear history") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearHistory = false }) { Text("Cancel") }
            }
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("AURA Native Phone", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "SIM call controls using Android Telecom and your existing mobile plan.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Important cellular-call limitation", fontWeight = FontWeight.SemiBold)
                    Text(
                        "AURA can display, answer, reject and manage calls when Android grants the default-phone role. Android does not give ordinary apps the caller's cellular audio stream or a supported way to inject AI speech. This screen does not record calls or run an AI conversation over a SIM call. Full AI conversations use AURA VoIP instead.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        item {
            Card {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (isDefaultDialer) "AURA is the default phone app" else "Default phone app not enabled", fontWeight = FontWeight.SemiBold)
                    Text(
                        if (isDefaultDialer) "Android Telecom has selected AURA for supported call-management UI. Emergency calls continue through the preloaded system dialer."
                        else "To let AURA receive Telecom call-state callbacks and provide its own incoming/ongoing call screen, Android must explicitly select AURA as the default phone app.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (isDefaultDialer) {
                        OutlinedButton(onClick = onOpenSystemDialerSettings, modifier = Modifier.fillMaxWidth()) {
                            Text("Open default phone-app settings")
                        }
                    } else {
                        Button(onClick = onRequestDialerRole, modifier = Modifier.fillMaxWidth()) {
                            Text("Request Android default-phone role")
                        }
                    }
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 33 && !callNotificationsAllowed) {
            item {
                Card {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Call notifications are disabled", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Allow notifications so AURA can show incoming and ongoing call controls. Android may otherwise hide the app's call notification.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        OutlinedButton(onClick = onRequestCallNotifications, modifier = Modifier.fillMaxWidth()) {
                            Text("Allow call notifications")
                        }
                    }
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 34 && !fullScreenAlertsAllowed) {
            item {
                Card {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Full-screen incoming-call alert access is off", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Android 14 and newer can restrict full-screen call alerts. Incoming calls can still appear as notifications, but AURA may not open the call screen automatically.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        OutlinedButton(onClick = onManageFullScreenAlerts, modifier = Modifier.fillMaxWidth()) {
                            Text("Open full-screen alert access")
                        }
                    }
                }
            }
        }
        item { AuraVoipCard(context) }
        item {
            Card {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Place an owner-approved SIM call", fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = dialNumber,
                        onValueChange = onDialNumberChange,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Phone number") }
                    )
                    val keypadRows = listOf(
                        listOf("1", "2", "3"),
                        listOf("4", "5", "6"),
                        listOf("7", "8", "9"),
                        listOf("*", "0", "#")
                    )
                    keypadRows.forEach { rowKeys ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            rowKeys.forEach { key ->
                                OutlinedButton(
                                    onClick = { onDialNumberChange((dialNumber + key).take(32)) },
                                    modifier = Modifier.weight(1f)
                                ) { Text(key) }
                            }
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { onDialNumberChange("") }, modifier = Modifier.weight(1f)) {
                            Text("Clear")
                        }
                        TextButton(onClick = { onDialNumberChange(dialNumber.dropLast(1)) }, modifier = Modifier.weight(1f)) {
                            Text("Backspace")
                        }
                    }
                    Button(
                        onClick = { onPlaceCall(dialNumber) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = dialNumber.isNotBlank()
                    ) {
                        Text("Place SIM call")
                    }
                    Text("A tap on Place SIM call is the user's authorization. Existing SIM/carrier charges still apply.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (calls.isNotEmpty()) {
            item { Text("Current calls", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
            items(calls, key = { it.id }) { call ->
                Card {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(call.phoneNumber ?: "Unknown / private caller", fontWeight = FontWeight.SemiBold)
                        Text(call.stateLabel + if (call.incoming) " · Incoming" else " · Outgoing")
                        if (call.isRinging) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { AuraCallRegistry.answer(call.id) }) { Text("Answer") }
                                OutlinedButton(onClick = { AuraCallRegistry.reject(call.id) }) { Text("Decline") }
                            }
                        } else if (call.isActive || call.isHolding) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (call.isActive && call.canHold) OutlinedButton(onClick = { AuraCallRegistry.hold(call.id) }) { Text("Hold") }
                                if (call.isHolding) OutlinedButton(onClick = { AuraCallRegistry.unhold(call.id) }) { Text("Resume") }
                                Button(onClick = { AuraCallRegistry.disconnect(call.id) }) { Text("End") }
                            }
                            if (audioEndpoints.isNotEmpty()) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    audioEndpoints.forEach { endpoint ->
                                        OutlinedButton(
                                            onClick = { AuraCallRegistry.requestAudioEndpoint(endpoint.id) },
                                            enabled = !endpoint.isCurrent
                                        ) { Text(endpoint.name) }
                                    }
                                }
                            } else {
                                Text("Audio routes are not currently reported by Telecom.", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
        item {
            Card {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        if (historyAvailable) "Encrypted call history · $retentionDays-day retention" else "Encrypted call history unavailable",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        if (historyAvailable) "Metadata only. No cellular call audio or transcript is stored. The local history is capped at 100 records."
                        else "Android Keystore-backed history could not be opened. AURA will not claim that call metadata is being stored securely.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            retentionDays = when (retentionDays) {
                                30 -> 90
                                90 -> 180
                                180 -> 365
                                else -> 30
                            }
                            val updated = AuraCallRegistry.setHistoryRetentionDays(retentionDays)
                            historyRevision++
                            if (!updated) Toast.makeText(context, "Could not update encrypted history retention.", Toast.LENGTH_LONG).show()
                        }) {
                            Text("Change retention")
                        }
                        if (history.isNotEmpty()) {
                            TextButton(onClick = { confirmClearHistory = true }) {
                                Text("Clear all")
                            }
                        }
                    }
                }
            }
        }
        if (history.isEmpty()) {
            item { Text("No AURA-managed calls have ended yet.", style = MaterialTheme.typography.bodySmall) }
        } else {
            items(history, key = { it.id }) { record ->
                Card {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(record.phoneNumber ?: "Unknown / private caller", fontWeight = FontWeight.Medium)
                        Text(
                            (if (record.transport == "AURA_VOIP") "AURA VoIP AI" else if (record.incoming) "Incoming SIM" else "Outgoing SIM") + " · " +
                                DateFormat.getDateTimeInstance().format(Date(record.endedAtEpochMs)) + " · " +
                                (record.durationMs / 1_000L) + " sec connected",
                            style = MaterialTheme.typography.bodySmall
                        )
                        record.languageTag?.let { Text("Language: $it", style = MaterialTheme.typography.bodySmall) }
                        record.summary?.let { Text("Summary: $it", style = MaterialTheme.typography.bodySmall) }
                        record.transcript?.let { transcript ->
                            var expanded by remember(record.id) { mutableStateOf(false) }
                            TextButton(onClick = { expanded = !expanded }) {
                                Text(if (expanded) "Hide transcript" else "View encrypted transcript")
                            }
                            if (expanded) Text(transcript, style = MaterialTheme.typography.bodySmall)
                        }
                        OutlinedButton(onClick = {
                            val deleted = AuraCallRegistry.deleteHistoryRecord(record.id)
                            historyRevision++
                            if (!deleted) Toast.makeText(context, "Could not delete this history record.", Toast.LENGTH_LONG).show()
                        }) {
                            Text("Delete record")
                        }
                    }
                }
            }
        }
    }
}
