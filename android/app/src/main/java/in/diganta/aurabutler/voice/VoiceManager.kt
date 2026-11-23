package `in`.diganta.aurabutler.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioRecord
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import `in`.diganta.aurabutler.data.SecureStore
import java.util.concurrent.Executors
import kotlin.math.sqrt

class VoiceManager(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val store by lazy(LazyThreadSafetyMode.NONE) { SecureStore(context) }
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "aura-whisper-input").apply { isDaemon = true }
    }

    @Volatile
    private var recording = false
    private var audioRecord: AudioRecord? = null

    var onListeningChanged: (Boolean) -> Unit = {}
    var onResult: (String) -> Unit = {}
    var onErrorMessage: (String) -> Unit = {}
    var onStatusMessage: (String) -> Unit = {}

    fun start(localeTag: String) {
        if (recording) return
        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            onErrorMessage("Microphone permission is required.")
            return
        }
        recording = true
        executor.execute {
            runCatching {
                post { onStatusMessage("Listening…") }
                AuraVoiceRuntime.prepareWhisper(localeTag)
                VoiceDiagnostics.mark(context, VoiceDiagnostics.CAPTURING_COMMAND)
                val samples = captureCommand()
                post { onListeningChanged(false) }
                if (samples.size < OfflineWhisperEngine.SAMPLE_RATE / 3) {
                    AuraVoiceRuntime.discardPreparedWhisper()
                    VoiceDiagnostics.mark(context, VoiceDiagnostics.IDLE)
                    post { onErrorMessage("I did not hear enough speech. Please try again.") }
                    return@execute
                }

                val settings = store.loadSettings()
                if (settings.ownerVoiceLockEnabled) {
                    post { onStatusMessage("Verifying owner voice locally…") }
                    val verification = OwnerVoiceGuard.verify(
                        context = context,
                        samples = samples,
                        threshold = settings.ownerVoiceThreshold
                    )
                    if (!verification.accepted) {
                        AuraVoiceRuntime.discardPreparedWhisper()
                        VoiceDiagnostics.mark(context, VoiceDiagnostics.IDLE)
                        Log.w(TAG, "Rejected manual voice command: speakerScore=${verification.score}")
                        post {
                            onErrorMessage(verification.reason + " Command ignored.")
                        }
                        return@execute
                    }
                }

                post { onStatusMessage("Understanding your words privately on this phone…") }
                val result = AuraVoiceRuntime.transcribe(samples, localeTag)
                if (result.isBlank()) {
                    post {
                        onErrorMessage(
                            "I could not understand that. Speak closer to the phone and try again."
                        )
                    }
                } else {
                    post { onResult(result) }
                }
            }.onFailure { error ->
                AuraVoiceRuntime.discardPreparedWhisper()
                Log.e(TAG, "Offline voice input failed", error)
                VoiceDiagnostics.mark(context, VoiceDiagnostics.IDLE)
                post {
                    onListeningChanged(false)
                    val detail = error.message?.take(120)?.takeIf(String::isNotBlank)
                    onErrorMessage(
                        detail?.let { "Offline voice input stopped: $it" }
                            ?: "Offline voice input stopped. Please try again."
                    )
                }
            }
            recording = false
            releaseRecorder()
        }
    }

    fun stop() {
        recording = false
        runCatching { audioRecord?.stop() }
    }

    fun destroy() {
        stop()
        executor.shutdownNow()
        releaseRecorder()
    }

    private fun captureCommand(): FloatArray {
        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("Microphone permission was revoked.")
        }
        val sampleRate = OfflineWhisperEngine.SAMPLE_RATE
        val recorder = VoiceAudioRecorder.create(sampleRate)
        audioRecord = recorder
        recorder.startRecording()
        post { onListeningChanged(true) }

        val chunks = ArrayList<FloatArray>()
        val shorts = ShortArray(1_280)
        var totalSamples = 0
        var readFailures = 0
        val maximumSamples = sampleRate * 25
        val detector = AdaptiveSpeechDetector(
            sampleRate = sampleRate,
            minimumThreshold = 0.0045f,
            startSpeechMs = 80,
            endSilenceMs = 700,
            initialWaitMs = 5_500,
            calibrationMs = 260
        )

        while (recording && totalSamples < maximumSamples) {
            val count = recorder.read(shorts, 0, shorts.size)
            if (count <= 0) {
                if (++readFailures >= 8) error("The microphone stopped returning audio.")
                continue
            }
            readFailures = 0
            val floats = FloatArray(count) { shorts[it] / 32768.0f }
            chunks += floats
            totalSamples += count

            var squareSum = 0.0
            for (sample in floats) squareSum += sample * sample
            val rms = sqrt(squareSum / count).toFloat()
            when (detector.observe(rms, count)) {
                AdaptiveSpeechDetector.Decision.END_OF_SPEECH,
                AdaptiveSpeechDetector.Decision.NO_SPEECH_TIMEOUT -> break
                AdaptiveSpeechDetector.Decision.CONTINUE -> Unit
            }
        }
        recording = false
        runCatching { recorder.stop() }

        if (!detector.heardSpeech) return FloatArray(0)

        val result = FloatArray(totalSamples)
        var offset = 0
        chunks.forEach { chunk ->
            chunk.copyInto(result, offset)
            offset += chunk.size
        }
        return result
    }

    private fun releaseRecorder() {
        audioRecord?.let { recorder ->
            runCatching { recorder.stop() }
            recorder.release()
        }
        audioRecord = null
    }

    private fun post(block: () -> Unit) {
        main.post(block)
    }

    private companion object {
        const val TAG = "AuraVoiceManager"
    }
}
