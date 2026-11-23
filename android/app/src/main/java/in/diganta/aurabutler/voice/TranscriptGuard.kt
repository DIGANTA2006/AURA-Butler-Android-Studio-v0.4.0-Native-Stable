package `in`.diganta.aurabutler.voice

import java.util.Locale

/**
 * Rejects common Whisper hallucinations caused by silence or unsupported
 * automatic language detection on low-power phones.
 *
 * AURA's bundled speech model is configured for English, Bengali and Hindi.
 * Korean, Tamil and other unsupported-script transcripts are rejected instead
 * of being forwarded to the command engine or an optional cloud provider.
 */
object TranscriptGuard {
    fun sanitize(
        raw: String,
        languageTag: String = "auto"
    ): String {
        var value = raw
            .replace(Regex("""\s+"""), " ")
            .trim()
            .trim('"', '\'', '“', '”')
            .trim()

        if (value.isBlank()) return ""

        value = collapseRepeatedTranscript(value)

        val selected = languageTag
            .trim()
            .lowercase(Locale.ROOT)
            .substringBefore('-')
            .takeIf { it in setOf("en", "bn", "hi") }
            ?: "auto"

        var letterCount = 0
        var unsupportedLetters = 0

        value.forEach { character ->
            if (!character.isLetter()) return@forEach
            letterCount += 1

            if (!isAllowedLetter(character, selected)) {
                unsupportedLetters += 1
            }
        }

        if (letterCount == 0) return ""

        val unsupportedLimit = maxOf(1, letterCount / 8)
        if (unsupportedLetters > unsupportedLimit) return ""

        if (
            value.length <= 2 &&
            value.none(Char::isDigit)
        ) {
            return ""
        }

        return value.take(MAX_TRANSCRIPT_CHARS)
    }

    internal fun collapseRepeatedTranscript(
        raw: String
    ): String {
        val cleaned = raw
            .replace(Regex("""\s+"""), " ")
            .trim()
            .trimEnd('.', '!', '?', ',', ';', ':')

        val tokens = cleaned.split(' ')
            .filter(String::isNotBlank)

        for (parts in 4 downTo 2) {
            if (
                tokens.size >= parts &&
                tokens.size % parts == 0
            ) {
                val chunkSize = tokens.size / parts
                val first = tokens.take(chunkSize)
                val allSame = (1 until parts).all { part ->
                    tokens.subList(
                        part * chunkSize,
                        (part + 1) * chunkSize
                    ).map { it.lowercase(Locale.ROOT) } ==
                        first.map { it.lowercase(Locale.ROOT) }
                }

                if (allSame) {
                    return first.joinToString(" ")
                }
            }
        }

        val duplicatedWithLink = Regex(
            """(?i)^(.{3,80}?)\s+(?:is|and|then)\s+\1$"""
        ).find(cleaned)

        return duplicatedWithLink
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: cleaned
    }

    private fun isAllowedLetter(
        character: Char,
        selected: String
    ): Boolean {
        val code = character.code

        val latin =
            code in 0x0041..0x005A ||
                code in 0x0061..0x007A ||
                code in 0x00C0..0x024F

        val bengali = code in 0x0980..0x09FF
        val devanagari = code in 0x0900..0x097F

        return when (selected) {
            "en" -> latin
            "bn" -> latin || bengali
            "hi" -> latin || devanagari
            else -> latin || bengali || devanagari
        }
    }

    private const val MAX_TRANSCRIPT_CHARS = 500
}
