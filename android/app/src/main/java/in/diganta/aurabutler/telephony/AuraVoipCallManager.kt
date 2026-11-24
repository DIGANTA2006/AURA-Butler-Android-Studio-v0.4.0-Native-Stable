package `in`.diganta.aurabutler.telephony

import android.content.Context
import `in`.diganta.aurabutler.background.WakeWordService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Owns one owner-hosted, audio-only AI VoIP session. Cellular Telecom is never touched here. */
object AuraVoipCallManager {
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(AuraVoipUiState())
    val state: StateFlow<AuraVoipUiState> = mutableState.asStateFlow()
    private var callJob: Job? = null
    private var signaling: AuraVoipSignalingClient? = null
    private var peer: AuraWebRtcAudioPeer? = null
    private var appContext: Context? = null
    private var startedAt: Long = 0L
    private var summary: String? = null
    private var restoreWakeListener = false
    private val ended = AtomicBoolean(false)
    private val ending = AtomicBoolean(false)

    fun start(context: Context, roomId: String = UUID.randomUUID().toString()): Boolean {
        val app = context.applicationContext
        val config = runCatching { AuraVoipConfigStore(app).load() }.getOrElse {
            mutableState.value = AuraVoipUiState(AuraVoipPhase.ERROR, "Encrypted VoIP settings are unavailable.")
            return false
        }
        val validation = config.validate()
        if (validation != null) {
            mutableState.value = AuraVoipUiState(AuraVoipPhase.ERROR, validation)
            return false
        }
        if (roomId.length !in 8..64 || !roomId.matches(Regex("^[A-Za-z0-9_-]+$"))) {
            mutableState.value = AuraVoipUiState(AuraVoipPhase.ERROR, "The private call-room ID is invalid.")
            return false
        }
        synchronized(lock) {
            if (callJob?.isActive == true) return false
            appContext = app
            startedAt = 0L
            summary = null
            restoreWakeListener = runCatching { WakeWordService.isRunning(app) }.getOrDefault(false)
            if (restoreWakeListener) runCatching { WakeWordService.setEnabled(app, false) }
            ended.set(false)
            ending.set(false)
            mutableState.value = AuraVoipUiState(
                phase = AuraVoipPhase.STARTING,
                status = "Preparing your owner-hosted AURA AI call…",
                roomId = roomId
            )
            callJob = scope.launch { runSession(app, config, roomId) }
        }
        return true
    }

    private suspend fun runSession(context: Context, config: AuraVoipConfig, roomId: String) {
        val joined = CompletableDeferred<List<org.webrtc.PeerConnection.IceServer>>()
        val answer = CompletableDeferred<String>()
        val client = AuraVoipSignalingClient(
            config = config,
            roomId = roomId,
            preferredLanguage = config.preferredLanguage,
            onMessage = { message ->
                scope.launch {
                    val payload = message.optJSONObject("payload") ?: message
                    when (message.optString("type")) {
                        "joined" -> {
                            val servers = runCatching {
                                AuraVoipSignalJson.parseIceServers(message.optJSONArray("ice_servers") ?: payload.optJSONArray("ice_servers"))
                            }.getOrElse {
                                joined.completeExceptionally(it)
                                return@launch
                            }
                            if (servers.isEmpty()) {
                                joined.completeExceptionally(IllegalStateException("The owner server did not provide TURN relay credentials."))
                            } else if (!joined.isCompleted) joined.complete(servers)
                        }
                        "answer" -> {
                            val sdp = payload.optString("sdp")
                            if (sdp.isNotBlank() && !answer.isCompleted) answer.complete(sdp)
                        }
                        "transcript" -> {
                            val text = payload.optString("text").trim()
                            if (text.isNotEmpty() && text.length <= 8_000) {
                                val line = AuraVoipTranscriptLine(
                                    speaker = payload.optString("speaker", "caller").take(24),
                                    text = text,
                                    languageTag = payload.optString("language").takeIf { it.isNotBlank() }
                                )
                                mutableState.update { current -> current.copy(
                                    languageTag = line.languageTag ?: current.languageTag,
                                    transcript = (current.transcript + line).takeLast(250)
                                ) }
                            }
                        }
                        "call_summary" -> {
                            summary = payload.optString("summary").take(2_000).takeIf { it.isNotBlank() }
                            mutableState.update { it.copy(status = "AURA generated a local call summary.") }
                        }
                        "status" -> {
                            val text = payload.optString("message").take(300)
                            if (text.isNotBlank()) mutableState.update { it.copy(status = text) }
                        }
                        "error" -> {
                            val text = payload.optString("message", "The AURA server reported a call error.").take(300)
                            if (!answer.isCompleted) answer.completeExceptionally(IllegalStateException(text))
                            if (!joined.isCompleted) joined.completeExceptionally(IllegalStateException(text))
                            mutableState.update { it.copy(phase = AuraVoipPhase.ERROR, status = text) }
                        }
                        "call_ended" -> {
                            val text = payload.optString("reason", "The AURA AI call ended.").take(300)
                            mutableState.update { it.copy(phase = AuraVoipPhase.ENDED, status = text) }
                        }
                    }
                }
            },
            onState = { message -> scope.launch { mutableState.update { it.copy(status = message) } } },
            onFailure = { error ->
                if (!joined.isCompleted) joined.completeExceptionally(error)
                if (!answer.isCompleted) answer.completeExceptionally(error)
                scope.launch { mutableState.update { it.copy(phase = AuraVoipPhase.ERROR, status = safeMessage(error)) } }
            }
        )
        synchronized(lock) { signaling = client }
        var localPeer: AuraWebRtcAudioPeer? = null
        var recoveryJob: Job? = null
        try {
            mutableState.update { it.copy(phase = AuraVoipPhase.SIGNALLING, status = "Connecting to your private WSS signaling endpoint…") }
            client.connect()
            val iceServers = withTimeout(18_000L) { joined.await() }
            mutableState.update { it.copy(phase = AuraVoipPhase.NEGOTIATING, status = "Creating encrypted audio offer through your own TURN relay…") }
            localPeer = AuraWebRtcAudioPeer(
                context = context,
                iceServers = iceServers,
                onState = { text ->
                    scope.launch { mutableState.update { current -> current.copy(status = text) } }
                    when {
                        text.startsWith("Audio path disconnected") -> {
                            recoveryJob?.cancel()
                            recoveryJob = scope.launch {
                                val recovered = localPeer?.awaitRecovery(10_000L) == true
                                if (!recovered) {
                                    mutableState.update { current ->
                                        if (current.phase == AuraVoipPhase.CONNECTED) current.copy(
                                            phase = AuraVoipPhase.ERROR,
                                            status = "The owner-hosted TURN audio path did not recover. Start a new AURA call."
                                        ) else current
                                    }
                                }
                            }
                        }
                        text.startsWith("Audio path failed") || text == "Audio path closed." -> {
                            mutableState.update { current ->
                                if (current.phase == AuraVoipPhase.CONNECTED) current.copy(
                                    phase = AuraVoipPhase.ERROR,
                                    status = "The owner-hosted TURN audio path failed. Start a new AURA call."
                                ) else current
                            }
                        }
                    }
                },
            )
            synchronized(lock) { peer = localPeer }
            val offer = localPeer.createOffer()
            check(client.send(JSONObject().put("type", "offer").put("room", roomId)
                .put("payload", JSONObject().put("sdp", offer)))) {
                "Could not send the audio offer to the owner-hosted AURA server."
            }
            mutableState.update { it.copy(status = "Waiting for your local AI endpoint to answer…") }
            val remoteSdp = withTimeout(45_000L) { answer.await() }
            localPeer.applyAnswer(remoteSdp)
            localPeer.awaitConnected()
            startedAt = System.currentTimeMillis()
            mutableState.update { it.copy(
                phase = AuraVoipPhase.CONNECTED,
                status = "Encrypted AURA AI audio is connected. Speak naturally; the assistant runs on your own server.",
                connectedAtEpochMs = startedAt
            ) }
            // Hold this coroutine while the app owns the foreground call session.
            while (!ended.get() && mutableState.value.phase == AuraVoipPhase.CONNECTED) delay(500)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val message = safeMessage(error)
            mutableState.update { it.copy(phase = AuraVoipPhase.ERROR, status = message) }
        } finally {
            runCatching { client.send(JSONObject().put("type", "hangup").put("room", roomId)) }
            recoveryJob?.cancel()
            runCatching { localPeer?.close() }
            runCatching { client.close() }
            synchronized(lock) {
                if (peer === localPeer) peer = null
                if (signaling === client) signaling = null
                callJob = null
            }
            if (startedAt > 0L) {
                val saved = persistCall(roomId)
                mutableState.update { it.copy(
                    historyRevision = it.historyRevision + 1L,
                    status = if (saved) "AURA AI call report saved to encrypted local history."
                    else "The call ended, but encrypted local history was unavailable."
                ) }
            }
            if (restoreWakeListener) {
                appContext?.let { app -> runCatching { WakeWordService.setEnabled(app, true) } }
                restoreWakeListener = false
            }
            val current = mutableState.value
            if (current.phase == AuraVoipPhase.CONNECTED || current.phase == AuraVoipPhase.STARTING ||
                current.phase == AuraVoipPhase.SIGNALLING || current.phase == AuraVoipPhase.NEGOTIATING) {
                mutableState.update { it.copy(phase = AuraVoipPhase.ENDED, status = "AURA AI call ended.") }
            }
            appContext?.let { app ->
                runCatching { app.stopService(android.content.Intent(app, AuraVoipForegroundService::class.java)) }
            }
        }
    }

    private fun persistCall(roomId: String): Boolean {
        val now = System.currentTimeMillis()
        val snapshot = mutableState.value
        val transcriptText = snapshot.transcript.joinToString("\n") { line ->
            "[${line.speaker}${line.languageTag?.let { ", $it" } ?: ""}] ${line.text}"
        }.take(48_000)
        val record = AuraCallHistoryRecord(
            id = "voip-$roomId",
            phoneNumber = null,
            incoming = false,
            startedAtEpochMs = startedAt,
            endedAtEpochMs = now,
            connectedAtEpochMs = startedAt,
            disconnectCauseCode = null,
            transport = "AURA_VOIP",
            contactName = "AURA local AI",
            languageTag = snapshot.languageTag,
            summary = summary,
            transcript = transcriptText.ifBlank { null }
        )
        val saved = AuraCallRegistry.appendVoipHistory(record)
        if (saved) appContext?.let { context -> AuraCallNotifications.showVoipSummary(context, record) }
        return saved
    }

    fun end() {
        if (ending.compareAndSet(false, true)) scope.launch { endInternal() }
    }

    private suspend fun endInternal() {
        val phaseBeforeEnd = mutableState.value.phase
        val currentRoom = mutableState.value.roomId
        runCatching { signaling?.send(JSONObject().put("type", "hangup").put("room", currentRoom)) }
        // Only a connected session has a conversation summary worth waiting for.
        // A call cancelled while negotiating must release microphone/network resources immediately.
        if (phaseBeforeEnd == AuraVoipPhase.CONNECTED) {
            withTimeoutOrNull(11_000L) {
                mutableState.first { it.phase == AuraVoipPhase.ENDED || it.phase == AuraVoipPhase.ERROR }
            }
        }
        ended.set(true)
        val job = synchronized(lock) { callJob }
        if (job != null) {
            job.cancel()
            runCatching { job.cancelAndJoin() }
        }
        synchronized(lock) {
            peer = null
            signaling = null
            callJob = null
        }
        if (mutableState.value.phase != AuraVoipPhase.ERROR) {
            mutableState.update { it.copy(phase = AuraVoipPhase.ENDED, status = "You ended the AURA AI call.") }
        }
        appContext?.let { app -> runCatching { app.stopService(android.content.Intent(app, AuraVoipForegroundService::class.java)) } }
        ending.set(false)
    }

    private fun safeMessage(error: Throwable): String =
        (error.message ?: error.javaClass.simpleName).replace(Regex("[\\r\\n\\t]+"), " ").take(300)
}
