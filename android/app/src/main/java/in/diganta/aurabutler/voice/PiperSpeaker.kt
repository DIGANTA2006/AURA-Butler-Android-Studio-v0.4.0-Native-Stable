package `in`.diganta.aurabutler.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class PiperSpeaker(context: Context) {
    private val appContext = context.applicationContext
    private val assets = appContext.assets
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "aura-piper-voice").apply { isDaemon = true }
    }
    private val generation = AtomicInteger(0)
    private val activeTrack = AtomicReference<AudioTrack?>()

    /**
     * The lightweight Java wrapper remains available, but its native model is
     * allocated only during an utterance and freed afterwards.
     */
    private val tts: OfflineTts by lazy {
        val modelDirectory = "voice/piper"
        OfflineTts(
            assetManager = assets,
            config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = "$modelDirectory/model.onnx",
                        tokens = "$modelDirectory/tokens.txt",
                        dataDir = "$modelDirectory/espeak-ng-data",
                        lengthScale = 1.0f
                    ),
                    numThreads = 1,
                    debug = false,
                    provider = "cpu"
                ),
                maxNumSentences = 2,
                silenceScale = 0.18f
            )
        ).also {
            // Constructor allocation is released immediately. Each utterance
            // explicitly allocates/frees the native weights below.
            runCatching { it.free() }
        }
    }

    fun speak(text: String, onFailure: (() -> Unit)? = null) {
        if (text.isBlank()) return
        val myGeneration = generation.incrementAndGet()
        stopTrack()
        executor.execute {
            val ok = runCatching { speakInternal(text, myGeneration) }
                .onFailure { VoiceDiagnostics.mark(appContext, VoiceDiagnostics.IDLE) }
                .isSuccess
            if (!ok) onFailure?.invoke()
        }
    }

    fun speakAndWait(text: String): Boolean {
        if (text.isBlank()) return true
        val myGeneration = generation.incrementAndGet()
        stopTrack()
        val future: Future<Boolean> = executor.submit<Boolean> {
            runCatching {
                speakInternal(text, myGeneration)
                true
            }.onFailure {
                VoiceDiagnostics.mark(appContext, VoiceDiagnostics.IDLE)
            }.getOrDefault(false)
        }
        return runCatching { future.get() }.getOrDefault(false)
    }

    fun stop() {
        generation.incrementAndGet()
        stopTrack()
    }

    private fun speakInternal(text: String, myGeneration: Int) {
        val clean = SpeechTextSanitizer.clean(text)
            .take(MAX_SPEECH_CHARACTERS)
        val timingId = VoiceTimingDiagnostics.begin(
            appContext,
            clean,
            "Piper offline"
        )
        var timingCompleted = false
        var modelLoadMs = 0L
        var generationMs = 0L
        var audioDurationMs = 0L

        VoiceDiagnostics.mark(appContext, VoiceDiagnostics.LOADING_VOICE)
        val loadStarted = SystemClock.elapsedRealtime()
        tts.allocate(assets)
        modelLoadMs = (
            SystemClock.elapsedRealtime() - loadStarted
        ).coerceAtLeast(0L)

        try {
            VoiceDiagnostics.mark(appContext, VoiceDiagnostics.SPEAKING)
            val generationStarted = SystemClock.elapsedRealtime()
            val generated = tts.generate(text = clean, sid = 0, speed = 1.0f)
            generationMs = (
                SystemClock.elapsedRealtime() - generationStarted
            ).coerceAtLeast(0L)

            audioDurationMs = if (generated.sampleRate > 0) {
                generated.samples.size.toLong() * 1_000L /
                    generated.sampleRate.toLong()
            } else {
                0L
            }

            if (
                myGeneration != generation.get() ||
                generated.samples.isEmpty()
            ) {
                VoiceTimingDiagnostics.complete(
                    appContext,
                    timingId,
                    completedNormally = false,
                    modelLoadMs = modelLoadMs,
                    generationMs = generationMs,
                    audioDurationMs = audioDurationMs
                )
                timingCompleted = true
                return
            }

            val pcm = ShortArray(generated.samples.size) { index ->
                (generated.samples[index].coerceIn(-1.0f, 1.0f) * Short.MAX_VALUE)
                    .toInt()
                    .toShort()
            }
            val minimum = AudioTrack.getMinBufferSize(
                generated.sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            check(minimum > 0) { "Android rejected AURA's speech audio format." }
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setSampleRate(generated.sampleRate)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minimum, 8192))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            check(track.state == AudioTrack.STATE_INITIALIZED) {
                "Android could not initialize AURA's media audio output."
            }
            activeTrack.set(track)
            try {
                track.play()
                VoiceTimingDiagnostics.markStarted(
                    appContext,
                    timingId
                )
                var offset = 0
                while (offset < pcm.size && myGeneration == generation.get()) {
                    val count = minOf(4096, pcm.size - offset)
                    val written = track.write(
                        pcm,
                        offset,
                        count,
                        AudioTrack.WRITE_BLOCKING
                    )
                    if (written <= 0) break
                    offset += written
                }
            } finally {
                activeTrack.compareAndSet(track, null)
                runCatching { track.stop() }
                runCatching { track.release() }
            }

            VoiceTimingDiagnostics.complete(
                appContext,
                timingId,
                completedNormally = myGeneration == generation.get(),
                modelLoadMs = modelLoadMs,
                generationMs = generationMs,
                audioDurationMs = audioDurationMs
            )
            timingCompleted = true
        } finally {
            if (!timingCompleted) {
                VoiceTimingDiagnostics.complete(
                    appContext,
                    timingId,
                    completedNormally = false,
                    modelLoadMs = modelLoadMs,
                    generationMs = generationMs,
                    audioDurationMs = audioDurationMs
                )
            }
            runCatching { tts.free() }
            VoiceDiagnostics.mark(appContext, VoiceDiagnostics.IDLE)
        }
    }

    private fun stopTrack() {
        activeTrack.getAndSet(null)?.let { track ->
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.stop() }
            runCatching { track.release() }
        }
    }

    private companion object {
        const val MAX_SPEECH_CHARACTERS = 1_500
    }
}
