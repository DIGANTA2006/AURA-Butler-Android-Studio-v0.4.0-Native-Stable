package `in`.diganta.aurabutler.voice

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

/** Language selection and installed-voice selection for AURA speech. */
object SpeechLanguageSupport {
    const val AUTO = "auto"
    const val ENGLISH = "en"
    const val BENGALI = "bn"
    const val HINDI = "hi"

    fun resolveLanguageTag(text: String, preferredTag: String = AUTO): String {
        val bengali = text.count { it.code in 0x0980..0x09FF }
        val devanagari = text.count { it.code in 0x0900..0x097F }

        if (bengali > 0 && bengali >= devanagari) return BENGALI
        if (devanagari > 0) return HINDI

        return when (preferredTag.trim().lowercase(Locale.ROOT).substringBefore('-')) {
            BENGALI -> BENGALI
            HINDI -> HINDI
            ENGLISH -> ENGLISH
            else -> ENGLISH
        }
    }

    fun localeFor(languageTag: String): Locale = when (
        languageTag.trim().lowercase(Locale.ROOT).substringBefore('-')
    ) {
        BENGALI -> Locale.forLanguageTag("bn-IN")
        HINDI -> Locale.forLanguageTag("hi-IN")
        else -> Locale.UK
    }

    fun isAvailable(tts: TextToSpeech, languageTag: String): Boolean =
        tts.isLanguageAvailable(localeFor(languageTag)) >= TextToSpeech.LANG_AVAILABLE

    fun bestVoice(tts: TextToSpeech, languageTag: String): Voice? {
        val language = localeFor(languageTag).language
        val candidates = tts.voices.orEmpty()
            .filter { it.locale.language.equals(language, ignoreCase = true) }

        if (candidates.isEmpty()) return null

        return candidates.sortedWith(
            compareByDescending<Voice> { !it.isNetworkConnectionRequired }
                .thenByDescending { voice ->
                    val name = voice.name.lowercase(Locale.ROOT)
                    listOf(
                        "male", "man", "gbb", "eng-gbr", "en-gb-x-gbb",
                        "bn-in", "hi-in"
                    ).any(name::contains)
                }
                .thenByDescending { it.quality }
                .thenBy { it.latency }
        ).firstOrNull()
    }
}
