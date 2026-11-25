package `in`.diganta.aurabutler.runtime

import `in`.diganta.aurabutler.data.AiResponseFormatter
import `in`.diganta.aurabutler.runtime.OperatingMode
import java.util.Locale

/** Final deterministic quality boundary for model-generated text. */
object ResponseQualityGate {
    /**
     * Prepares text for display.
     * NO fixed character truncation caps (removed 4k/20k/36k limits per ADR-014).
     * Token-aware streaming and chunked continuation are handled upstream.
     * DEVICE_ONLY mode: passes through without any cloud-policy filtering.
     */
    fun forDisplay(raw: String, style: String, operatingMode: OperatingMode = OperatingMode.AUTOMATIC): String {
        val formatted = AiResponseFormatter.plainText(raw)
        if (formatted.isBlank()) return ""

        val codeSafe = if (AiResponseFormatter.containsFencedCode(formatted)) {
            formatted
        } else {
            deduplicate(formatted)
        }

        // Identity/branding normalization (owner-configured form of address handled upstream)
        val cleaned = codeSafe
            .replace(
                Regex("(?i)^(certainly|of course),?\\s+(master diganta|sir)[.!,:-]?\\s*"),
                ""
            )
            .trim()

        // NO hard truncation. Caller handles chunking/continuation for voice/UI.
        return cleaned
    }

    /**
     * Streaming variant — returns text ready for immediate display.
     * No truncation; caller manages chunk boundaries.
     */
    fun forStreamingDisplay(raw: String, operatingMode: OperatingMode = OperatingMode.AUTOMATIC): String {
        // For streaming, we skip deduplication (expensive) and just strip thinking + normalize
        val withoutThinking = AiResponseFormatter.stripHiddenThinkingForStreaming(raw)
        return withoutThinking
            .replace(
                Regex("(?i)^(certainly|of course),?\\s+(master diganta|sir)[.!,:-]?\\s*"),
                ""
            )
            .trim()
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
