package `in`.diganta.aurabutler.telephony

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.util.UUID

@Composable
internal fun AuraVoipCard(context: Context) {
    val store = remember(context.applicationContext) { AuraVoipConfigStore(context.applicationContext) }
    val saved = remember(store) { runCatching { store.load() }.getOrDefault(AuraVoipConfig("", "", "", "auto")) }
    var url by remember { mutableStateOf(saved.signalingUrl) }
    var clientId by remember { mutableStateOf(saved.clientId) }
    var token by remember { mutableStateOf(saved.accessToken) }
    var language by remember { mutableStateOf(saved.preferredLanguage) }
    var roomId by remember { mutableStateOf(UUID.randomUUID().toString().replace("-", "").take(24)) }
    val callState by AuraVoipCallManager.state.collectAsState()
    var showProtocolHelp by remember { mutableStateOf(false) }

    fun startCall() {
        val config = AuraVoipConfig(url.trim(), clientId.trim(), token.trim(), language.trim().lowercase())
        val error = config.validate()
        if (error != null) {
            Toast.makeText(context, error, Toast.LENGTH_LONG).show()
            return
        }
        runCatching { store.save(config) }.onFailure {
            Toast.makeText(context, "Encrypted settings could not be saved: ${it.message}", Toast.LENGTH_LONG).show()
            return
        }
        // Reserve the single-call slot first. If another call is already active,
        // never stop its foreground service as a side effect of a second tap.
        if (!AuraVoipCallManager.start(context, roomId)) {
            Toast.makeText(context, "AURA already has an active or starting VoIP session.", Toast.LENGTH_LONG).show()
            return
        }
        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, AuraVoipForegroundService::class.java)
            )
        }.onFailure {
            AuraVoipCallManager.end()
            Toast.makeText(context, "Android could not start the visible VoIP call service: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    val microphoneRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCall()
        else Toast.makeText(context, "Microphone permission is required for a VoIP conversation.", Toast.LENGTH_LONG).show()
    }

    fun requestMicrophoneThenStart() {
        when {
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> startCall()
            else -> microphoneRequest.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val notificationPermissionRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) requestMicrophoneThenStart()
        else Toast.makeText(
            context,
            "Allow notifications so Android can show AURA's ongoing microphone-use call indicator.",
            Toast.LENGTH_LONG
        ).show()
    }

    fun requestNotificationsThenStart() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            requestMicrophoneThenStart()
        }
    }

    if (showProtocolHelp) {
        AlertDialog(
            onDismissRequest = { showProtocolHelp = false },
            title = { Text("Owner-hosted AURA VoIP") },
            text = { Text("This connects only to the WSS endpoint you configure. The server must authenticate this device, return short-lived credentials for your own TURN server, and host the local AI service with local speech-recognition, language-model and English/Bengali/Hindi Piper voices. The app forces media through that TURN relay; it does not use a public telephony or AI provider. A self-hosted server and domain/network connectivity may still have infrastructure or electricity costs. This feature does not call normal phone numbers.") },
            confirmButton = { TextButton(onClick = { showProtocolHelp = false }) { Text("Understood") } }
        )
    }

    Card {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("AURA Independent AI Call", style = MaterialTheme.typography.titleMedium)
            Text("A separate WebRTC voice session to your own local-AI server. This is not a SIM call and cannot dial ordinary telephone numbers.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = url,
                onValueChange = { url = it.take(512) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Self-hosted WSS URL") },
                placeholder = { Text("wss://aura.example.net/ws/signaling") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )
            OutlinedTextField(
                value = clientId,
                onValueChange = { clientId = it.filter { c -> c.isLetterOrDigit() || c == '_' || c == '-' }.take(48) },
                modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Device client ID") }
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it.filterNot(Char::isWhitespace).take(768) },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                label = { Text("Device access token") }
            )
            OutlinedTextField(
                value = language,
                onValueChange = { language = it.lowercase().take(8) },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Preferred language (auto / en / bn / hi)") }
            )
            OutlinedTextField(
                value = roomId,
                onValueChange = { roomId = it.filter { c -> c.isLetterOrDigit() || c == '_' || c == '-' }.take(64) },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Private session room") }
            )
            Text("Status: ${callState.status}", style = MaterialTheme.typography.bodySmall)
            callState.languageTag?.let { Text("Detected language: $it", style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (callState.phase == AuraVoipPhase.CONNECTED || callState.phase == AuraVoipPhase.STARTING ||
                    callState.phase == AuraVoipPhase.SIGNALLING || callState.phase == AuraVoipPhase.NEGOTIATING) {
                    Button(onClick = { AuraVoipCallManager.end() }, modifier = Modifier.weight(1f)) { Text("End AI call") }
                } else {
                    Button(onClick = { requestNotificationsThenStart() }, modifier = Modifier.weight(1f)) {
                        Text("Start AURA AI call")
                    }
                }
                OutlinedButton(onClick = { showProtocolHelp = true }) { Text("How it works") }
            }
            if (callState.transcript.isNotEmpty()) {
                Text("Encrypted transcript preview (saved after the call ends)", style = MaterialTheme.typography.labelLarge)
                callState.transcript.takeLast(12).forEach { line ->
                    Text("${line.speaker}: ${line.text}", style = MaterialTheme.typography.bodySmall)
                }
            }
            Text("Audio is not recorded to disk. Transcripts and summaries are encrypted in AURA's local call history after the session ends.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
