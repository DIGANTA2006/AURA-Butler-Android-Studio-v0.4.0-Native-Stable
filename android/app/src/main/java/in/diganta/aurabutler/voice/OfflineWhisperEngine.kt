package `in`.diganta.aurabutler.voice

import android.app.ActivityManager
import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import java.util.Locale

class OfflineWhisperEngine(
    context: Context,
    private val languageTag: String = "auto"
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val assets = appContext.assets
    private val language = normalizeLanguage(languageTag)
    private var recognizer: OfflineRecognizer? = createRecognizer()

    @Synchronized
    fun transcribe(samples: FloatArray): String {
        if (samples.isEmpty()) return ""
        val active = checkNotNull(recognizer) { "Offline Whisper has already been released." }
        val stream = active.createStream()
        return try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            active.decode(stream)
            TranscriptGuard.sanitize(
                raw = active.getResult(stream).text,
                languageTag = languageTag
            )
        } finally {
            stream.release()
        }
    }

    @Synchronized
    override fun close() {
        recognizer?.release()
        recognizer = null
    }

    private fun createRecognizer(): OfflineRecognizer {
        val modelDirectory = "voice/whisper"
        val lowRam = appContext.getSystemService(ActivityManager::class.java)?.isLowRamDevice == true
        val model = OfflineModelConfig(
            whisper = OfflineWhisperModelConfig(
                encoder = "$modelDirectory/encoder.int8.onnx",
                decoder = "$modelDirectory/decoder.int8.onnx",
                // Empty enables Whisper language detection for mixed speech.
                language = language,
                task = "transcribe",
                tailPaddings = 300
            ),
            tokens = "$modelDirectory/tokens.txt",
            numThreads = if (lowRam) 1 else 2,
            debug = false,
            provider = "cpu",
            modelType = "whisper"
        )
        return OfflineRecognizer(
            assetManager = assets,
            config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = model,
                decodingMethod = "greedy_search"
            )
        )
    }

    private fun normalizeLanguage(tag: String): String {
        val value = tag.trim().lowercase(Locale.ROOT).substringBefore('-')
        return value.takeIf { it in setOf("en", "bn", "hi") }.orEmpty()
    }

    companion object {
        const val SAMPLE_RATE = 16_000
    }
}
