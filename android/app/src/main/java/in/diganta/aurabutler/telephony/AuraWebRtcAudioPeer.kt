package `in`.diganta.aurabutler.telephony

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.audio.JavaAudioDeviceModule
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Audio-only WebRTC peer. It never interfaces with Android's SIM-call audio path. */
internal class AuraWebRtcAudioPeer(
    context: Context,
    private val iceServers: List<PeerConnection.IceServer>,
    private val onState: (String) -> Unit
) : Closeable {
    private val appContext = context.applicationContext
    private val closed = AtomicBoolean(false)
    private val iceGathering = MutableStateFlow(PeerConnection.IceGatheringState.NEW)
    private val iceConnection = MutableStateFlow(PeerConnection.IceConnectionState.NEW)

    suspend fun awaitConnected(timeoutMs: Long = 45_000L) {
        val result = withTimeout(timeoutMs) {
            iceConnection.first {
                it == PeerConnection.IceConnectionState.CONNECTED ||
                    it == PeerConnection.IceConnectionState.COMPLETED ||
                    it == PeerConnection.IceConnectionState.FAILED ||
                    it == PeerConnection.IceConnectionState.CLOSED
            }
        }
        check(result == PeerConnection.IceConnectionState.CONNECTED ||
            result == PeerConnection.IceConnectionState.COMPLETED) {
            "The owner-hosted TURN/WebRTC media path did not connect (${result.name.lowercase()})."
        }
    }

    /** Waits for ICE recovery after a transient DISCONNECTED state without hiding terminal failure. */
    suspend fun awaitRecovery(timeoutMs: Long = 10_000L): Boolean =
        withTimeoutOrNull(timeoutMs) {
            iceConnection.first {
                it == PeerConnection.IceConnectionState.CONNECTED ||
                    it == PeerConnection.IceConnectionState.COMPLETED ||
                    it == PeerConnection.IceConnectionState.FAILED ||
                    it == PeerConnection.IceConnectionState.CLOSED
            } in setOf(PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED)
        } ?: false
    private var audioDeviceModule: JavaAudioDeviceModule? = null
    private var factory: PeerConnectionFactory? = null
    private var peer: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null

    suspend fun createOffer(): String = withContext(Dispatchers.Default) {
        ensureOpen()
        createFactoryAndPeer()
        val trackSource = factory!!.createAudioSource(MediaConstraints())
        audioSource = trackSource
        val track = factory!!.createAudioTrack("aura-voip-audio", trackSource)
        localAudioTrack = track
        track.setEnabled(true)
        check(peer!!.addTrack(track, listOf("aura-voip-stream")) != null) {
            "WebRTC could not attach the microphone audio track."
        }
        val offer = createDescription(isOffer = true)
        setLocalDescription(offer)
        awaitIceGathering()
        peer?.localDescription?.description ?: error("WebRTC did not produce a gathered local offer.")
    }

    suspend fun applyAnswer(sdp: String) = withContext(Dispatchers.Default) {
        require(sdp.isNotBlank() && sdp.length <= MAX_SDP_CHARS) { "Remote SDP is invalid or too large." }
        val session = SessionDescription(SessionDescription.Type.ANSWER, sdp)
        setRemoteDescription(session)
        onState("Remote answer accepted. Negotiating encrypted audio…")
    }

    private fun createFactoryAndPeer() {
        if (factory != null) return
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext).createInitializationOptions()
        )
        audioDeviceModule = JavaAudioDeviceModule.builder(appContext)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()
        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioDeviceModule)
            .createPeerConnectionFactory()

        val rtcConfiguration = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            // AURA routes media only through the owner's TURN relay. It does not
            // send voice to public STUN/TURN providers or unknown peer IP addresses.
            iceTransportsType = PeerConnection.IceTransportsType.RELAY
        }
        peer = factory!!.createPeerConnection(rtcConfiguration, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) {
                onState("WebRTC signaling: ${state.name.lowercase()}.")
            }
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                iceConnection.value = state
                onState(when (state) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> "Encrypted audio connected."
                    PeerConnection.IceConnectionState.DISCONNECTED -> "Audio path disconnected; attempting ICE recovery."
                    PeerConnection.IceConnectionState.FAILED -> "Audio path failed. Check your owner-hosted STUN/TURN configuration."
                    PeerConnection.IceConnectionState.CLOSED -> "Audio path closed."
                    else -> "Audio path: ${state.name.lowercase()}."
                })
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                iceGathering.value = state
            }
            override fun onIceCandidate(candidate: org.webrtc.IceCandidate) = Unit
            override fun onIceCandidatesRemoved(candidates: Array<out org.webrtc.IceCandidate>) = Unit
            override fun onAddStream(stream: org.webrtc.MediaStream) = Unit
            override fun onRemoveStream(stream: org.webrtc.MediaStream) = Unit
            override fun onDataChannel(channel: org.webrtc.DataChannel) {
                // No data channel is negotiated; all authenticated control uses WSS.
                channel.close()
            }
            override fun onRenegotiationNeeded() = Unit
            override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out org.webrtc.MediaStream>) {
                (receiver.track() as? AudioTrack)?.setEnabled(true)
            }
            override fun onTrack(transceiver: RtpTransceiver) {
                (transceiver.receiver.track() as? AudioTrack)?.setEnabled(true)
            }
        }) ?: error("Android WebRTC could not create a peer connection.")
    }

    private suspend fun createDescription(isOffer: Boolean): SessionDescription =
        suspendCancellableCoroutine { continuation ->
            val observer = object : SdpObserver {
                override fun onCreateSuccess(description: SessionDescription) {
                    if (continuation.isActive) continuation.resume(description)
                }
                override fun onSetSuccess() = Unit
                override fun onCreateFailure(error: String) {
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException(error))
                }
                override fun onSetFailure(error: String) = Unit
            }
            val constraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            }
            if (isOffer) peer!!.createOffer(observer, constraints) else peer!!.createAnswer(observer, constraints)
            continuation.invokeOnCancellation { runCatching { peer?.close() } }
        }

    private suspend fun setLocalDescription(description: SessionDescription) =
        setDescription(local = true, description = description)

    private suspend fun setRemoteDescription(description: SessionDescription) =
        setDescription(local = false, description = description)

    private suspend fun setDescription(local: Boolean, description: SessionDescription) =
        suspendCancellableCoroutine { continuation ->
            val observer = object : SdpObserver {
                override fun onCreateSuccess(description: SessionDescription) = Unit
                override fun onSetSuccess() {
                    if (continuation.isActive) continuation.resume(Unit)
                }
                override fun onCreateFailure(error: String) = Unit
                override fun onSetFailure(error: String) {
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException(error))
                }
            }
            if (local) peer!!.setLocalDescription(observer, description)
            else peer!!.setRemoteDescription(observer, description)
        }

    private suspend fun awaitIceGathering() {
        if (iceGathering.value == PeerConnection.IceGatheringState.COMPLETE) return
        withTimeout(ICE_GATHER_TIMEOUT_MS) {
            iceGathering.first { it == PeerConnection.IceGatheringState.COMPLETE }
        }
    }

    private fun ensureOpen() {
        check(!closed.get()) { "AURA VoIP peer has already been closed." }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { peer?.close() }
        runCatching { peer?.dispose() }
        runCatching { localAudioTrack?.dispose() }
        runCatching { audioSource?.dispose() }
        runCatching { factory?.dispose() }
        runCatching { audioDeviceModule?.release() }
        peer = null
        localAudioTrack = null
        audioSource = null
        factory = null
        audioDeviceModule = null
    }

    private companion object {
        const val MAX_SDP_CHARS = 96 * 1024
        const val ICE_GATHER_TIMEOUT_MS = 20_000L
        const val TAG = "AuraWebRtcPeer"
    }
}
