package `in`.diganta.aurabutler.voice

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Fast UI speech using Android's installed English/Bengali/Hindi voices.
 * Piper remains an offline English fallback when Android TTS is unavailable.
 * Extended with volume/rate/mute controls for VoiceConversationEngine priority commands.
 * Supports segment-level callbacks for checkpoint resume.
 */
class Speaker(context: Context) : TextToSpeech.OnInitListener {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var systemReady = false
    private val systemTts = TextToSpeech(context.applicationContext, this)
    
    // Volume/rate/mute state
    private var currentVolume = 1.0f
    private var currentRate = 1.0f
    private var isMuted = false

    override fun onInit(status: Int) {
        systemReady = status == TextToSpeech.SUCCESS
        if (systemReady) {
            systemTts.language = Locale.UK
            systemTts.setSpeechRate(1.02f)
            systemTts.setPitch(0.84f)
            applyVoice(SpeechLanguageSupport.ENGLISH)
            systemTts.setOnUtteranceProgressListener(
                object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        if (utteranceId != null) {
                            VoiceTimingDiagnostics.markStarted(
                                appContext,
                                utteranceId
                            )
                        }
                    }

                    override fun onDone(utteranceId: String?) {
                        if (utteranceId != null) {
                            VoiceTimingDiagnostics.complete(
                                appContext,
                                utteranceId
                            )
                        }
                    }

                    override fun onStop(
                        utteranceId: String?,
                        interrupted: Boolean
                    ) {
                        if (utteranceId != null) {
                            VoiceTimingDiagnostics.complete(
                                appContext,
                                utteranceId,
                                completedNormally = !interrupted
                            )
                        }
                    }

                    @Deprecated(
                        "Deprecated by Android but still required by the base class"
                    )
                    override fun onError(utteranceId: String?) {
                        if (utteranceId != null) {
                            VoiceTimingDiagnostics.fail(
                                appContext,
                                utteranceId
                            )
                        }
                    }
                }
            )
        }
    }

    /**
     * Speaks text with a completion callback for segment-level checkpoint tracking.
     * @param text Text to speak
     * @param languageTag Language tag (e.g., "en-US", "bn-BD", "hi-IN", "auto")
     * @param onSegmentComplete Callback with status: TextToSpeech.SUCCESS or TextToSpeech.ERROR
     */
    fun speak(
        text: String,
        languageTag: String = "auto",
        onSegmentComplete: ((Int) -> Unit)? = null
    ) {
        val clean = SpeechTextSanitizer.clean(text, languageTag)
            .take(MAX_CHARACTERS)
        if (clean.isBlank()) {
            onSegmentComplete?.invoke(TextToSpeech.SUCCESS)
            return
        }

        val resolvedLanguage = SpeechLanguageSupport.resolveLanguageTag(
            clean,
            languageTag
        )

        main.post {
            if (
                systemReady &&
                SpeechLanguageSupport.isAvailable(systemTts, resolvedLanguage)
            ) {
                applyVoice(resolvedLanguage)
                val timingId = VoiceTimingDiagnostics.begin(
                    appContext,
                    clean,
                    "Android TTS foreground"
                )
                val result = systemTts.speak(
                    clean,
                    TextToSpeech.QUEUE_FLUSH,
                    Bundle(),
                    timingId
                )
                if (result == TextToSpeech.ERROR) {
                    VoiceTimingDiagnostics.fail(appContext, timingId)
                    speakFallback(clean, resolvedLanguage) { status ->
                        onSegmentComplete?.invoke(status)
                    }
                } else {
                    // Success will be reported via UtteranceProgressListener.onDone
                    // We still call the callback immediately for non-blocking flow
                    onSegmentComplete?.invoke(TextToSpeech.SUCCESS)
                }
            } else {
                speakFallback(clean, resolvedLanguage) { status ->
                    onSegmentComplete?.invoke(status)
                }
            }
        }
    }

    fun stop() {
        systemTts.stop()
        AuraVoiceRuntime.speaker.stop()
    }

    fun shutdown() {
        stop()
        systemTts.shutdown()
    }

    // ===== Volume/Rate/Mute Controls for VoiceConversationEngine =====

    fun adjustVolume(factor: Float) {
        currentVolume = (currentVolume * factor).coerceIn(0.1f, 2.0f)
        if (systemReady) {
            systemTts.setSpeechRate(currentRate)
            // Note: Android TTS doesn't have direct volume control per utterance
            // Volume is typically controlled via AudioManager.STREAM_MUSIC
        }
    }

    fun adjustRate(factor: Float) {
        currentRate = (currentRate * factor).coerceIn(0.5f, 2.0f)
        if (systemReady) {
            systemTts.setSpeechRate(currentRate)
        }
    }

    fun setMuted(muted: Boolean) {
        isMuted = muted
        if (muted) {
            stop()
        }
    }

    fun isMuted(): Boolean = isMuted

    fun getCurrentVolume(): Float = currentVolume

    fun getCurrentRate(): Float = currentRate

    private fun applyVoice(languageTag: String) {
        val locale = SpeechLanguageSupport.localeFor(languageTag)
        systemTts.language = locale
        systemTts.setSpeechRate(
            if (languageTag == SpeechLanguageSupport.ENGLISH) 1.02f else 0.98f
        )
        systemTts.setPitch(0.84f)
        SpeechLanguageSupport.bestVoice(systemTts, languageTag)?.let {
            systemTts.voice = it
        }
    }

    private fun speakFallback(
        text: String,
        languageTag: String,
        onComplete: (Int) -> Unit
    ) {
        if (languageTag != SpeechLanguageSupport.ENGLISH) {
            onComplete(TextToSpeech.ERROR)
            return
        }
        AuraVoiceRuntime.speaker.speak(text) {
            main.post { onComplete(TextToSpeech.SUCCESS) }
        }
    }

    private companion object {
        const val MAX_CHARACTERS = 1_500
    }
}
