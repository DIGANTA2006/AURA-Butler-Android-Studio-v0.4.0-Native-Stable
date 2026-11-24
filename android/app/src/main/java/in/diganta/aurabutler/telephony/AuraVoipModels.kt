package `in`.diganta.aurabutler.telephony

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Only owner-configured, self-hosted WSS endpoints are accepted. */
data class AuraVoipConfig(
    val signalingUrl: String,
    val clientId: String,
    val accessToken: String,
    val preferredLanguage: String = "auto"
) {
    fun validate(): String? {
        val uri = runCatching { Uri.parse(signalingUrl.trim()) }.getOrNull()
            ?: return "Enter the self-hosted signaling URL."
        if (!signalingUrl.startsWith("wss://", ignoreCase = true)) {
            return "Signaling must use WSS (TLS). Plain ws:// is not accepted."
        }
        if (uri.host.isNullOrBlank() || !uri.userInfo.isNullOrBlank() || !uri.fragment.isNullOrBlank()) {
            return "The WSS URL is malformed."
        }
        if (!clientId.matches(Regex("^[A-Za-z0-9_-]{3,48}$"))) {
            return "Client ID must contain 3–48 letters, digits, underscores or hyphens."
        }
        if (accessToken.length !in 32..768 || accessToken.any(Char::isWhitespace)) {
            return "The signed device token is missing or malformed."
        }
        val language = preferredLanguage.lowercase(Locale.ROOT)
        if (language !in setOf("auto", "en", "bn", "hi")) {
            return "Choose auto, English, Bengali or Hindi."
        }
        return null
    }
}

data class AuraVoipTranscriptLine(
    val speaker: String,
    val text: String,
    val languageTag: String? = null,
    val atEpochMs: Long = System.currentTimeMillis()
)

enum class AuraVoipPhase {
    IDLE, STARTING, SIGNALLING, NEGOTIATING, CONNECTED, RECONNECT_REQUIRED, ENDED, ERROR
}

data class AuraVoipUiState(
    val phase: AuraVoipPhase = AuraVoipPhase.IDLE,
    val status: String = "AURA VoIP is disconnected.",
    val roomId: String? = null,
    val connectedAtEpochMs: Long? = null,
    val languageTag: String? = null,
    val transcript: List<AuraVoipTranscriptLine> = emptyList(),
    val historyRevision: Long = 0L
)

internal object AuraVoipSignalJson {
    fun parseIceServers(value: Any?): List<org.webrtc.PeerConnection.IceServer> {
        val array = value as? JSONArray ?: return emptyList()
        require(array.length() in 1..8) { "The server did not provide a valid ICE server list." }
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val urls = when (val raw = item.opt("urls")) {
                is JSONArray -> (0 until raw.length()).map { raw.getString(it) }
                is String -> listOf(raw)
                else -> emptyList()
            }
            require(urls.isNotEmpty() && urls.size <= 8)
            require(urls.all { it.startsWith("stun:") || it.startsWith("stuns:") || it.startsWith("turn:") || it.startsWith("turns:") })
            val username = item.optString("username")
            val credential = item.optString("credential")
            val builder = org.webrtc.PeerConnection.IceServer.builder(urls)
            if (username.isNotBlank()) builder.setUsername(username)
            if (credential.isNotBlank()) builder.setPassword(credential)
            builder.createIceServer()
        }
    }

    fun signal(type: String, roomId: String, payload: JSONObject = JSONObject()): JSONObject =
        JSONObject().put("type", type).put("room", roomId).put("payload", payload)
}
