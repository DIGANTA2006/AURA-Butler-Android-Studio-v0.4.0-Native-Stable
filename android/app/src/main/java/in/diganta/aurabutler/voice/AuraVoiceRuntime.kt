package `in`.diganta.aurabutler.voice

import android.content.Context
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Future

object AuraVoiceRuntime {
    private lateinit var appContext: Context

    private val whisperLoader = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "aura-whisper-loader").apply { isDaemon = true }
    }

    private var preparedLanguage = ""
    private var preparedWhisper: Future<OfflineWhisperEngine>? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Begins loading Whisper while the user is still speaking. The wake KWS
     * model must already be released before this method is called.
     */
    @Synchronized
    fun prepareWhisper(languageTag: String = "auto") {
        check(::appContext.isInitialized) { "AuraVoiceRuntime is not initialized" }
        val normalized = normalizeLanguage(languageTag)

        if (preparedWhisper != null && preparedLanguage == normalized) return

        val previous = preparedWhisper
        preparedWhisper = null
        preparedLanguage = ""
        closeFutureLater(previous)

        VoiceDiagnostics.mark(appContext, VoiceDiagnostics.LOADING_WHISPER)
        preparedLanguage = normalized
        preparedWhisper = whisperLoader.submit<OfflineWhisperEngine> {
            OfflineWhisperEngine(appContext, normalized)
        }
    }

    @Synchronized
    fun transcribe(
        samples: FloatArray,
        languageTag: String = "auto"
    ): String {
        check(::appContext.isInitialized) { "AuraVoiceRuntime is not initialized" }
        val normalized = normalizeLanguage(languageTag)
        val future = if (
            preparedWhisper != null &&
            preparedLanguage == normalized
        ) {
            preparedWhisper
        } else {
            VoiceDiagnostics.mark(appContext, VoiceDiagnostics.LOADING_WHISPER)
            whisperLoader.submit<OfflineWhisperEngine> {
                OfflineWhisperEngine(appContext, normalized)
            }
        }

        preparedWhisper = null
        preparedLanguage = ""

        val whisper = checkNotNull(future).get()
        return try {
            VoiceDiagnostics.mark(appContext, VoiceDiagnostics.TRANSCRIBING)
            whisper.transcribe(samples)
        } finally {
            whisper.close()
            VoiceDiagnostics.mark(appContext, VoiceDiagnostics.IDLE)
        }
    }

    @Synchronized
    fun discardPreparedWhisper() {
        if (!::appContext.isInitialized) return
        val future = preparedWhisper
        preparedWhisper = null
        preparedLanguage = ""
        closeFutureLater(future)
        VoiceDiagnostics.mark(appContext, VoiceDiagnostics.IDLE)
    }

    private fun closeFutureLater(future: Future<OfflineWhisperEngine>?) {
        if (future == null) return
        whisperLoader.execute {
            runCatching { future.get().close() }
        }
    }

    private fun normalizeLanguage(tag: String): String {
        val value = tag
            .trim()
            .lowercase(Locale.ROOT)
            .substringBefore('-')

        return value.takeIf { it in setOf("en", "bn", "hi") }.orEmpty()
    }

    val speaker: PiperSpeaker by lazy {
        check(::appContext.isInitialized) { "AuraVoiceRuntime is not initialized" }
        PiperSpeaker(appContext)
    }
}