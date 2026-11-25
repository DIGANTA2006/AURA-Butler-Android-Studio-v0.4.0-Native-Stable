package `in`.diganta.aurabutler.voice

import java.util.Locale

/** Converts rich chat text into natural, language-aware speech. */
object SpeechTextSanitizer {
    fun clean(raw: String, languageTag: String = "auto"): String {
        if (raw.isBlank()) return ""

        // Romanized Bengali (Bengali in English letters) becomes Bengali script first,
        // so the Bengali voice pronounces it instead of English letter-to-sound rules.
        val source = RomanizedBengali.prepareForSpeech(raw)
        val language = SpeechLanguageSupport.resolveLanguageTag(source, languageTag)
        val thenWord = when (language) {
            SpeechLanguageSupport.BENGALI -> " তারপর "
            SpeechLanguageSupport.HINDI -> " फिर "
            else -> " then "
        }
        val andWord = when (language) {
            SpeechLanguageSupport.BENGALI -> " এবং "
            SpeechLanguageSupport.HINDI -> " और "
            else -> " and "
        }
        val percentWord = when (language) {
            SpeechLanguageSupport.BENGALI -> " শতাংশ"
            SpeechLanguageSupport.HINDI -> " प्रतिशत"
            else -> " percent"
        }

        return applyPronunciation(source, language)
            .replace(
                Regex("""\[([^\]]+)]\((?:https?://|mailto:)[^)]+\)"""),
                "$1"
            )
            .replace(Regex("""<[^>]+>"""), " ")
            .replace(Regex("""(?m)^\s{0,3}#{1,6}\s+"""), "")
            .replace(Regex("""(?m)^\s*>\s?"""), "")
            .replace(Regex("""(?m)^\s*[-+*]\s+"""), ". ")
            .replace("•", ". ")
            .replace("→", thenWord)
            .replace("⇒", thenWord)
            .replace("&", andWord)
            .replace(Regex("""https?://\S+"""), "the link")
            .replace(Regex("""[*_`~]+"""), "")
            .replace(Regex("""(?i)\b(asterisk|underscore|backtick)\b"""), "")
            .replace(Regex("""(\d+(?:\.\d+)?)\s*%"""), "$1$percentWord")
            .replace(Regex("""\s+([,.;:!?।])"""), "$1")
            .replace(Regex("""([,.;:!?।]){2,}"""), "$1")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    fun remainingAfter(raw: String, progressIndex: Int): String {
        val text = clean(raw)
        if (text.isBlank()) return ""
        if (progressIndex <= 0) return text
        if (progressIndex >= text.length) return ""

        var index = progressIndex.coerceIn(0, text.length)
        while (index < text.length && !text[index].isWhitespace()) index += 1
        while (index < text.length && text[index].isWhitespace()) index += 1
        return text.substring(index).trim()
    }

    fun containsWakeWordNear(raw: String, progressIndex: Int): Boolean {
        val text = clean(raw).lowercase(Locale.ROOT)
        if (text.isBlank()) return false

        val center = progressIndex.coerceIn(0, text.length)
        val from = (center - 18).coerceAtLeast(0)
        val to = (center + 34).coerceAtMost(text.length)
        return WAKE_WORD.find(text.substring(from, to)) != null
    }

    fun isWakeWordOnly(raw: String): Boolean {
        val normalized = clean(raw)
            .lowercase(Locale.ROOT)
            .replace(Regex("""[.!?,;:]+"""), "")
            .trim()

        return normalized.matches(Regex("""^(?:hey\s+)?(?:aura|ora|aurah)$"""))
    }

    private fun applyPronunciation(raw: String, language: String): String {
        var value = raw.replace(
            Regex("""\bAURA\b"""),
            when (language) {
                SpeechLanguageSupport.BENGALI -> "অরা"
                SpeechLanguageSupport.HINDI -> "ऑरा"
                else -> "Aura"
            }
        )

        val replacements = when (language) {
            SpeechLanguageSupport.BENGALI -> listOf(
                Regex("""\bAPI\b""") to "এ পি আই",
                Regex("""\bGPU\b""") to "জি পি ইউ",
                Regex("""\bCPU\b""") to "সি পি ইউ",
                Regex("""\bUI\b""") to "ইউ আই",
                Regex("""\bOCR\b""") to "ও সি আর",
                Regex("""\bAI\b""") to "এ আই",
                Regex("""\bLLM\b""") to "এল এল এম"
            )
            SpeechLanguageSupport.HINDI -> listOf(
                Regex("""\bAPI\b""") to "ए पी आई",
                Regex("""\bGPU\b""") to "जी पी यू",
                Regex("""\bCPU\b""") to "सी पी यू",
                Regex("""\bUI\b""") to "यू आई",
                Regex("""\bOCR\b""") to "ओ सी आर",
                Regex("""\bAI\b""") to "ए आई",
                Regex("""\bLLM\b""") to "एल एल एम"
            )
            else -> listOf(
                Regex("""\bAPI\b""") to "A P I",
                Regex("""\bGPU\b""") to "G P U",
                Regex("""\bCPU\b""") to "C P U",
                Regex("""\bUI\b""") to "U I",
                Regex("""\bOCR\b""") to "O C R",
                Regex("""\bAI\b""") to "A I",
                Regex("""\bLLM\b""") to "L L M"
            )
        }

        replacements.forEach { (pattern, replacement) ->
            value = value.replace(pattern, replacement)
        }

        val gigabytes = when (language) {
            SpeechLanguageSupport.BENGALI -> " গিগাবাইট"
            SpeechLanguageSupport.HINDI -> " गीगाबाइट"
            else -> " gigabytes"
        }
        val megabytes = when (language) {
            SpeechLanguageSupport.BENGALI -> " মেগাবাইট"
            SpeechLanguageSupport.HINDI -> " मेगाबाइट"
            else -> " megabytes"
        }

        return value
            .replace(Regex("""\b(\d+(?:\.\d+)?)\s*GB\b""", RegexOption.IGNORE_CASE), "$1$gigabytes")
            .replace(Regex("""\b(\d+(?:\.\d+)?)\s*MB\b""", RegexOption.IGNORE_CASE), "$1$megabytes")
    }

    private val WAKE_WORD = Regex(
        """\b(?:hey\s+)?(?:aura|ora|aurah)\b""",
        RegexOption.IGNORE_CASE
    )
}
