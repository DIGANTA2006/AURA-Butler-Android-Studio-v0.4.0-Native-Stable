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
 */
class Speaker(context: Context) : TextToSpeech.OnInitListener {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var systemReady = false
    private val systemTts = TextToSpeech(context.applicationContext, this)

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

    fun speak(
        text: String,
        languageTag: String = "auto",
        onUnavailable: (() -> Unit)? = null
    ) {
        val clean = SpeechTextSanitizer.clean(text, languageTag)
            .take(MAX_CHARACTERS)
        if (clean.isBlank()) return

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
                    speakFallback(clean, resolvedLanguage, onUnavailable)
                }
            } else {
                speakFallback(clean, resolvedLanguage, onUnavailable)
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
        onUnavailable: (() -> Unit)?
    ) {
        if (languageTag != SpeechLanguageSupport.ENGLISH) {
            onUnavailable?.invoke()
            return
        }
        AuraVoiceRuntime.speaker.speak(text) {
            main.post { onUnavailable?.invoke() }
        }
    }

    private companion object {
        const val MAX_CHARACTERS = 1_500
    }
}
