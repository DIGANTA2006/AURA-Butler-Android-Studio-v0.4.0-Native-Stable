package `in`.diganta.aurabutler.runtime

import `in`.diganta.aurabutler.data.AiResponseFormatter
import java.util.Locale

/** Final deterministic quality boundary for model-generated text. */
object ResponseQualityGate {
    fun forDisplay(raw: String, style: String): String {
        val formatted = AiResponseFormatter.plainText(raw)
        if (formatted.isBlank()) return ""

        val codeSafe = if (AiResponseFormatter.containsFencedCode(formatted)) {
            formatted
        } else {
            deduplicate(formatted)
        }

        val maxChars = when (style.lowercase(Locale.ROOT)) {
            "concise" -> 50_000
            "detailed" -> 500_000
            else -> 200_000
        }

        return codeSafe
            .replace(
                Regex("(?i)^(certainly|of course),?\\s+(master diganta|sir)[.!,:-]?\\s*"),
                ""
            )
            .trim()
            .take(maxChars)
    }

    internal fun deduplicate(raw: String): String {
        val paragraphs = raw.split(Regex("\\n{2,}"))
        val seen = linkedSetOf<String>()
        return paragraphs.mapNotNull { paragraph ->
            val normalized = paragraph
                .lowercase(Locale.ROOT)
                .replace(Regex("\\s+"), " ")
                .trim()
            if (normalized.isBlank() || !seen.add(normalized)) null
            else paragraph.trim()
        }.joinToString("\n\n")
    }
}
