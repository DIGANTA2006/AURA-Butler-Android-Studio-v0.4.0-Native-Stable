package `in`.diganta.aurabutler.telephony

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.Closeable
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** WSS-only signaling client. It carries SDP/control metadata, never media or recordings. */
internal class AuraVoipSignalingClient(
    private val config: AuraVoipConfig,
    private val roomId: String,
    private val preferredLanguage: String,
    private val onMessage: (JSONObject) -> Unit,
    private val onState: (String) -> Unit,
    private val onFailure: (Throwable) -> Unit
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val remoteEnded = AtomicBoolean(false)
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()
    @Volatile private var socket: WebSocket? = null

    fun connect() {
        require(config.validate() == null) { config.validate() ?: "Invalid VoIP configuration." }
        val request = Request.Builder()
            .url(config.signalingUrl.trim())
            .header("Authorization", "Bearer ${config.accessToken}")
            .header("X-AURA-Client", config.clientId)
            .build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (closed.get()) {
                    webSocket.close(1000, "call already ended")
                    return
                }
                onState("Authenticated to owner-hosted signaling. Joining an ephemeral room…")
                val joined = JSONObject()
                    .put("type", "join")
                    .put("room", roomId)
                    .put("client_id", config.clientId)
                    .put("role", "caller")
                    .put("preferred_language", preferredLanguage)
                if (!webSocket.send(joined.toString())) {
                    onFailure(IllegalStateException("Signaling socket refused the join request."))
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (closed.get()) return
                if (text.length > MAX_SIGNAL_BYTES) {
                    onFailure(IllegalStateException("Signaling message exceeded the safety limit."))
                    close()
                    return
                }
                val message = runCatching { JSONObject(text) }.getOrElse {
                    onFailure(IllegalArgumentException("The signaling server sent invalid JSON."))
                    return
                }
                if (message.optString("type") == "call_ended") remoteEnded.set(true)
                onMessage(message)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                onState("Signaling server is closing the session.")
                webSocket.close(code, reason.take(120))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!closed.get() && !remoteEnded.get()) {
                    onFailure(IllegalStateException("The owner-hosted signaling connection closed unexpectedly ($code)."))
                } else if (!closed.get()) {
                    onState("The owner-hosted AURA session has ended.")
                }
            }

            override fun onFailure(webSocket: WebSocket, error: Throwable, response: Response?) {
                if (!closed.get()) onFailure(error)
            }
        })
    }

    fun send(message: JSONObject): Boolean {
        if (closed.get()) return false
        val encoded = message.toString()
        if (encoded.length > MAX_SIGNAL_BYTES) return false
        return socket?.send(encoded) == true
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        socket?.close(1000, "owner ended AURA call")
        socket = null
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    private companion object {
        const val MAX_SIGNAL_BYTES = 96 * 1024
    }
}
