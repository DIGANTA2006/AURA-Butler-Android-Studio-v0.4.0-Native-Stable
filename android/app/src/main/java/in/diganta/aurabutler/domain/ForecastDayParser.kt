package `in`.diganta.aurabutler.domain

import java.util.Locale

internal object ForecastDayParser {
    fun parse(request: String): Int? {
        val lower = request.lowercase(Locale.ROOT)
        val numeric = Regex(
            """(?i)\b(3|4|5|6|7|8|9|10|11|12|13|14|15|16)\s*[- ]?days?\b"""
        ).find(lower)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (numeric != null) return numeric.coerceIn(3, 16)

        val wordDays = mapOf(
            "three" to 3,
            "four" to 4,
            "five" to 5,
            "six" to 6,
            "seven" to 7,
            "eight" to 8,
            "nine" to 9,
            "ten" to 10,
            "eleven" to 11,
            "twelve" to 12,
            "thirteen" to 13,
            "fourteen" to 14,
            "fifteen" to 15,
            "sixteen" to 16
        )
        wordDays.forEach { (word, days) ->
            if (Regex("""\b$word\s*[- ]?days?\b""").containsMatchIn(lower)) {
                return days
            }
        }
        if (
            lower.contains("next week") ||
            lower.contains("weekly forecast") ||
            lower.contains("7 day forecast")
        ) {
            return 7
        }
        if (
            lower.contains("forecast") &&
            (lower.contains("long") || lower.contains("extended"))
        ) {
            return 10
        }
        return null
    }
}
