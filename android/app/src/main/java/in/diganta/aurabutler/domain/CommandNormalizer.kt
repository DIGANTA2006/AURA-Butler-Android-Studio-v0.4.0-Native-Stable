package `in`.diganta.aurabutler.domain

import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

data class CommandNormalization(
    val original: String,
    val corrected: String,
    val corrections: List<String>
) {
    val changed: Boolean
        get() = original.trim() != corrected.trim()
}

/**
 * Conservative correction for short voice/device commands.
 *
 * It deliberately avoids URLs, email addresses, long prose, passwords and
 * arbitrary names. The goal is to recover common speech-to-text and typing
 * mistakes around AURA's command vocabulary without rewriting general chat.
 */
object CommandNormalizer {
    fun normalizeMediaQuery(raw: String): String {
        var value = raw
            .trim()
            .replace(
                Regex("""(?i)\s+on\s+(?:youtube(?:\s+music)?|spotify)\s*$"""),
                ""
            )
            .replace(
                Regex("""(?i)^(?:the\s+)?(?:song|music|video)\s+"""),
                ""
            )
            .replace(Regex("""\s+"""), " ")
            .trim()

        MEDIA_PHRASE_REPLACEMENTS.forEach { (pattern, replacement) ->
            value = value.replace(pattern, replacement)
        }

        val tokens = TOKEN_PATTERN.findAll(value).toList()
        if (tokens.isNotEmpty()) {
            val rebuilt = StringBuilder()
            var cursor = 0
            tokens.forEach { match ->
                rebuilt.append(value.substring(cursor, match.range.first))
                val token = match.value
                val replacement =
                    MEDIA_ALIASES[token.lowercase(Locale.ROOT)] ?: token
                rebuilt.append(matchCase(token, replacement))
                cursor = match.range.last + 1
            }
            rebuilt.append(value.substring(cursor))
            value = rebuilt.toString()
        }

        return value
            .replace(Regex("""\s+"""), " ")
            .trim()
            .take(180)
    }

    fun isLikelyCommand(raw: String): Boolean {
        val value = raw.trim()
        if (value.isBlank() || value.length > 220) return false
        if (
            value.contains("http://", true) ||
            value.contains("https://", true)
        ) {
            return false
        }
        if (EMAIL_PATTERN.containsMatchIn(value)) return false

        val words = TOKEN_PATTERN
            .findAll(value.lowercase(Locale.ROOT))
            .map { it.value }
            .toList()

        if (words.isEmpty() || words.size > 24) return false

        return words.first() in COMMAND_STARTERS ||
            words.any {
                it in COMMAND_HINTS ||
                    ALIASES.containsKey(it) ||
                    MEDIA_ALIASES.containsKey(it)
            }
    }

    fun normalize(raw: String, enabled: Boolean): CommandNormalization {
        val original = raw.trim()
        if (!enabled || original.isBlank() || !isCandidate(original)) {
            return CommandNormalization(original, original, emptyList())
        }

        var working = original
        val corrections = mutableListOf<String>()

        PHRASE_REPLACEMENTS.forEach { (pattern, replacement) ->
            val updated = working.replace(pattern, replacement)
            if (updated != working) {
                corrections += "${pattern.pattern} → $replacement"
                working = updated
            }
        }

        val tokens = TOKEN_PATTERN.findAll(working).toList()
        if (tokens.isNotEmpty()) {
            val rebuilt = StringBuilder()
            var cursor = 0
            tokens.forEach { match ->
                rebuilt.append(working.substring(cursor, match.range.first))
                val token = match.value
                val corrected = correctToken(token)
                if (!corrected.equals(token, ignoreCase = true)) {
                    corrections += "$token → $corrected"
                }
                rebuilt.append(matchCase(token, corrected))
                cursor = match.range.last + 1
            }
            rebuilt.append(working.substring(cursor))
            working = rebuilt.toString()
        }

        working = working
            .replace(Regex("""\s+"""), " ")
            .trim()

        return CommandNormalization(
            original = original,
            corrected = working,
            corrections = corrections.distinct().take(8)
        )
    }

    private fun isCandidate(value: String): Boolean {
        if (value.length > 280) return false
        if (value.contains("http://", true) || value.contains("https://", true)) {
            return false
        }
        if (EMAIL_PATTERN.containsMatchIn(value)) return false
        val words = TOKEN_PATTERN.findAll(value.lowercase(Locale.ROOT))
            .map { it.value }
            .toList()
        if (words.size > 36) return false

        return words.any { word ->
            word in COMMAND_HINTS || ALIASES.containsKey(word) ||
                COMMAND_HINTS.any { candidate ->
                    word.length >= 4 && candidate.length >= 4 &&
                        abs(word.length - candidate.length) <= 2 &&
                        editDistance(word, candidate) <= allowedDistance(word)
                }
        }
    }

    private fun correctToken(raw: String): String {
        val token = raw.lowercase(Locale.ROOT)
        if (
            token.length < 3 ||
            token.any(Char::isDigit) ||
            token in PRESERVE_WORDS
        ) {
            return raw
        }
        ALIASES[token]?.let { return it }
        if (token in VOCABULARY) return token

        val threshold = allowedDistance(token)
        val best = VOCABULARY
            .asSequence()
            .filter { abs(it.length - token.length) <= threshold }
            .map { candidate -> candidate to editDistance(token, candidate) }
            .filter { (_, distance) -> distance <= threshold }
            .sortedWith(
                compareBy<Pair<String, Int>> { it.second }
                    .thenByDescending { commonPrefix(token, it.first) }
                    .thenBy { it.first.length }
            )
            .firstOrNull()
            ?: return raw

        val hasUsefulAnchor = commonPrefix(token, best.first) >= 1 || best.second == 1
        return if (hasUsefulAnchor) best.first else raw
    }

    private fun allowedDistance(word: String): Int = when {
        word.length <= 4 -> 1
        word.length <= 8 -> 2
        else -> 3
    }

    private fun matchCase(original: String, corrected: String): String {
        when (corrected.lowercase(Locale.ROOT)) {
            "youtube" -> return "YouTube"
            "whatsapp" -> return "WhatsApp"
            "gmail" -> return "Gmail"
        }
        return when {
            original.all(Char::isUpperCase) -> corrected.uppercase(Locale.ROOT)
            original.firstOrNull()?.isUpperCase() == true -> corrected.replaceFirstChar {
                it.titlecase(Locale.ROOT)
            }
            else -> corrected
        }
    }

    private fun commonPrefix(left: String, right: String): Int {
        val length = min(left.length, right.length)
        var count = 0
        while (count < length && left[count] == right[count]) count++
        return count
    }

    internal fun editDistance(left: String, right: String): Int {
        if (left == right) return 0
        if (left.isEmpty()) return right.length
        if (right.isEmpty()) return left.length

        val previous = IntArray(right.length + 1) { it }
        val current = IntArray(right.length + 1)
        for (i in 1..left.length) {
            current[0] = i
            for (j in 1..right.length) {
                val cost = if (left[i - 1] == right[j - 1]) 0 else 1
                current[j] = minOf(
                    current[j - 1] + 1,
                    previous[j] + 1,
                    previous[j - 1] + cost
                )
            }
            for (j in previous.indices) previous[j] = current[j]
        }
        return previous[right.length]
    }

    private val PHRASE_REPLACEMENTS = listOf(
        Regex("""(?i)\b(?:you|your|u)\s*tube\b""") to "YouTube",
        Regex("""(?i)\byou\s*toob\b""") to "YouTube",
        Regex("""(?i)\bwhat(?:s)?\s*app\b""") to "WhatsApp",
        Regex("""(?i)\bscreen\s+shot\b""") to "screenshot",
        Regex("""(?i)\bon\s*scren\b""") to "on screen",
        Regex("""(?i)\bon\s*screen\b""") to "on screen",
        Regex("""(?i)\bcatch\s+clean\b""") to "cache clean",
        Regex("""(?i)\bgroup\s+massage\b""") to "group message"
    )

    private val ALIASES = mapOf(
        "wataop" to "whatsapp",
        "watap" to "whatsapp",
        "watapp" to "whatsapp",
        "whatsap" to "whatsapp",
        "whatsaap" to "whatsapp",
        "vollume" to "volume",
        "volum" to "volume",
        "vedio" to "video",
        "vdeo" to "video",
        "sumarize" to "summarize",
        "summrize" to "summarize",
        "summarise" to "summarize",
        "anslise" to "analyze",
        "analyse" to "analyze",
        "analise" to "analyze",
        "translet" to "translate",
        "tranlate" to "translate",
        "onscren" to "screen",
        "scren" to "screen",
        "screan" to "screen",
        "coolinig" to "cooling",
        "coling" to "cooling",
        "cach" to "cache",
        "cashe" to "cache",
        "opep" to "open",
        "opne" to "open",
        "opn" to "open",
        "specipic" to "specific",
        "grop" to "group",
        "worg" to "wrong",
        "thins" to "things",
        "notifcation" to "notification",
        "notifactions" to "notifications",
        "messege" to "message",
        "massage" to "message",
        "docoment" to "document",
        "documant" to "document",
        "brighness" to "brightness",
        "flashligt" to "flashlight",
        "youtub" to "youtube",
        "gogle" to "google",
        "chrom" to "chrome",
        "pasuri" to "pasoori",
        "pasori" to "pasoori",
        "pasoori" to "pasoori",
        "kesaria" to "kesariya",
        "bews" to "news",
        "newz" to "news",
        "headine" to "headline",
        "headlines" to "headlines",
        "weathre" to "weather",
        "wether" to "weather",
        "forcast" to "forecast",
        "forecat" to "forecast",
        "befley" to "briefly",
        "brifly" to "briefly"
    )

    private val MEDIA_PHRASE_REPLACEMENTS = listOf(
        Regex("""(?i)\bpas+u+ri\b""") to "Pasoori",
        Regex("""(?i)\bpasori\b""") to "Pasoori",
        Regex("""(?i)\barijit\s+sing\b""") to "Arijit Singh"
    )

    private val MEDIA_ALIASES = mapOf(
        "pasuri" to "Pasoori",
        "pasori" to "Pasoori",
        "pasoori" to "Pasoori",
        "kesaria" to "Kesariya"
    )

    private val VOCABULARY = setOf(
        "open", "launch", "start", "run", "show", "play", "find", "search",
        "youtube", "whatsapp", "chrome", "google", "gmail", "maps", "spotify",
        "volume", "media", "ring", "ringtone", "alarm", "notification", "call",
        "mute", "unmute", "increase", "decrease", "raise", "lower", "maximum",
        "minimum", "screenshot", "capture", "screen", "image", "document",
        "read", "summarize", "translate", "analyze", "explain", "extract",
        "message", "messages", "group", "mail", "email", "important", "digest",
        "cache", "clean", "clear", "storage", "cooling", "temperature", "thermal",
        "overheating", "brightness", "flashlight", "camera", "news", "headline",
        "headlines", "forecast", "weather", "brief", "briefly", "research",
        "settings", "battery", "song", "identify", "video", "specific"
    )

    private val COMMAND_HINTS = VOCABULARY + setOf(
        "aura", "please", "could", "would", "can", "will", "tell", "give"
    )
    private val COMMAND_STARTERS = setOf(
        "open", "launch", "start", "run", "show", "play", "listen", "put",
        "read", "summarize", "translate", "analyze", "find", "search", "set",
        "turn", "take", "capture", "clean", "clear", "call", "send", "remind"
    )
    private val PRESERVE_WORDS = setOf("pin", "otp", "api", "url", "id")
    private val TOKEN_PATTERN = Regex("""[A-Za-z]+""")
    private val EMAIL_PATTERN = Regex(
        """[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}"""
    )
}
