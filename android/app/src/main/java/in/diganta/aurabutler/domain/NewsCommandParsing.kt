package `in`.diganta.aurabutler.domain

import java.util.Locale

internal enum class NewsMode {
    HEADLINES,
    BRIEF,
    DETAILED
}

internal data class ParsedNewsCommand(
    val normalized: String,
    val topic: String?,
    val mode: NewsMode,
    val detailIndex: Int?,
    val detailRequested: Boolean,
    val briefly: Boolean
)

internal object NewsCommandParsing {
    fun parse(message: String): ParsedNewsCommand {
        val normalized = message
            .lowercase(Locale.ROOT)
            .replace(Regex("""\s+"""), " ")
            .trim()
        val detailIndex = storyIndex(normalized)
        val briefly = normalized.containsAny("brief", "briefly", "short", "quick")
        val detailRequested = normalized.containsAny(
            "detail", "details", "full", "explain", "tell me more",
            "what happened", "why it matters", "briefly explain",
            "describe", "complete story"
        )
        val mode = when {
            normalized.containsAny(
                "detailed news", "news in detail", "full news",
                "news with details", "news with sources",
                "explain the news", "why it matters"
            ) || (
                detailIndex == null &&
                    normalized.containsAny("full", "detail", "details")
            ) -> NewsMode.DETAILED

            briefly -> NewsMode.BRIEF
            else -> NewsMode.HEADLINES
        }
        return ParsedNewsCommand(
            normalized = normalized,
            topic = topic(message),
            mode = mode,
            detailIndex = detailIndex,
            detailRequested = detailRequested,
            briefly = briefly
        )
    }

    private fun topic(message: String): String? {
        Regex(
            """(?i)\b(?:news|headlines)\s+(?:about|on|for|from)\s+(.+?)\s*[?.!]*$"""
        ).find(message)?.groupValues?.getOrNull(1)?.trim()?.let {
            return it.take(180).takeIf(String::isNotBlank)
        }

        Regex(
            """(?i)^\s*(?:(?:briefly|quickly|in\s+brief)\s+)?(?:tell|give|read|show)?\s*(?:me\s+)?(?:the\s+)?(.+?)\s+(?:news|headlines)\s*[?.!]*$"""
        ).find(message)?.groupValues?.getOrNull(1)?.trim()?.let { candidate ->
            val cleaned = candidate.replace(
                Regex("""(?i)\b(?:latest|today'?s|current|brief|briefly|quick|detailed|full|any|new|some)\b"""),
                " "
            ).replace(Regex("""\s+"""), " ").trim()
            if (cleaned.isNotBlank() && cleaned !in setOf("the", "some", "any")) {
                return cleaned.take(180)
            }
        }
        return null
    }

    private fun storyIndex(value: String): Int? {
        val ordinalWords = mapOf(
            "first" to 1,
            "second" to 2,
            "third" to 3,
            "fourth" to 4,
            "fifth" to 5,
            "sixth" to 6
        )
        ordinalWords.forEach { (word, index) ->
            if (Regex("""\b$word\b""").containsMatchIn(value)) return index
        }
        return Regex(
            """\b(?:headline|news|story|number|no\.?\s*)?\s*([1-6])(?:st|nd|rd|th)?\b"""
        ).find(value)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun String.containsAny(vararg values: String): Boolean =
        values.any { contains(it, ignoreCase = true) }
}
