package `in`.diganta.aurabutler.runtime

import android.content.Context
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln
import org.json.JSONObject

data class IntentRoute(
    val intent: String,
    val confidence: Double,
    val source: String,
    val normalizedText: String
)

class ProductionIntentRouter(context: Context) {
    private val appContext = context.applicationContext

    private val model: NaiveBayesModel? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        runCatching {
            appContext.assets.open(MODEL_ASSET)
                .bufferedReader()
                .use { NaiveBayesModel(JSONObject(it.readText())) }
        }.getOrNull()
    }

    fun route(rawText: String): IntentRoute {
        val normalized = normalize(
            rawText.replaceFirst(
                Regex("""(?i)^(?:hey\s+)?(?:aura|ora|aw[\s-]?ra)[,:\s]*"""),
                ""
            )
        )
        if (normalized.isBlank()) {
            return IntentRoute("empty", 1.0, "rule", normalized)
        }

        deterministic(normalized)?.let { return it }

        val prediction = model?.predict(normalized)
        if (prediction != null && prediction.second >= MODEL_THRESHOLD) {
            return IntentRoute(
                intent = prediction.first,
                confidence = prediction.second,
                source = "local_model",
                normalizedText = normalized
            )
        }

        return IntentRoute(
            intent = "general_query",
            confidence = prediction?.second ?: 0.0,
            source = "fallback",
            normalizedText = normalized
        )
    }

    fun splitCommands(text: String, maxCommands: Int = 4): List<String> {
        val chunks = text
            .split(
                Regex(
                    """(?i)\s+(?:and\s+then|then)\s+|\s*;\s*(?:then\s+)?"""
                )
            )
            .map(String::trim)
            .filter(String::isNotBlank)

        return if (chunks.size > 1) {
            chunks.take(maxCommands.coerceIn(1, 6))
        } else {
            listOf(text.trim())
        }
    }

    private fun deterministic(normalized: String): IntentRoute? {
        val rules = listOf(
            "interrupt" to Regex("""(?:aura\s+)?(?:stop|wait|pause|quiet|enough|never mind|cancel that)"""),
            "confirm" to Regex("""(?:yes[,.]?\s*)?(?:confirm|do it|go ahead|proceed|yes)"""),
            "cancel" to Regex("""(?:no[,.]?\s*)?(?:cancel|don't do it|do not do it|no)"""),
            "get_time" to Regex("""(?:(?:what(?:'s| is)?\s+)?(?:the\s+)?time(?:\s+is\s+it|\s+now)?|what time is it)"""),
            "get_date" to Regex("""(?:what(?:'s| is)?\s+(?:the\s+)?date(?:\s+today)?|what date is it|what day is it|today'?s date|date(?: today)?)"""),
            "system_vitals" to Regex("""(?:show|check|tell me|run)?\s*(?:my\s+)?(?:aura\s+|system\s+)?(?:doctor|diagnostics|vitals|status|health|cpu and ram)"""),
            "take_screenshot" to Regex("""(?:take|capture|grab|save)?\s*(?:a\s+)?screenshot(?:\s+now)?"""),
            "set_volume" to Regex("""(?:set\s+)?volume\s+.*"""),
            "set_brightness" to Regex("""(?:set\s+)?brightness\s+.*"""),
            "open_app" to Regex("""(?:open|launch|start|run)\s+[\w .+-]{1,80}"""),
            "remember" to Regex("""remember(?:\s+that)?\s+.+"""),
            "recall" to Regex("""(?:what do you remember|recall)(?:\s+about\s+.+)?"""),
            "forget" to Regex("""forget(?:\s+that)?\s+.+"""),
            "get_weather" to Regex(""".*\b(?:weather|temperature|forecast)\b.*"""),
            "get_news" to Regex(""".*\b(?:news|headlines)\b.*"""),
            "set_alarm" to Regex("""set(?:\s+an)?\s+alarm.*"""),
            "generate_code" to Regex(""".*\b(?:write|generate|create|debug|fix)\b.*\b(?:code|program|python|java|kotlin|c\+\+|javascript|typescript|sql)\b.*""")
        )

        val matched = rules.firstOrNull { (_, pattern) ->
            pattern.matches(normalized)
        } ?: return null

        return IntentRoute(
            intent = matched.first,
            confidence = 0.98,
            source = "rule",
            normalizedText = normalized
        )
    }

    private fun normalize(text: String): String =
        text.lowercase(Locale.ROOT)
            .replace('’', '\'')
            .replace(Regex("""[^\p{L}\p{N}\s:'%+.\-]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private class NaiveBayesModel(payload: JSONObject) {
        private val alpha = payload.optDouble("alpha", 0.65)
        private val totalDocs = payload.getInt("total_docs")
        private val intentDocs = payload.getJSONObject("intent_docs").toIntMap()
        private val intentTotals = payload.getJSONObject("intent_feature_totals").toIntMap()
        private val counts = payload.getJSONObject("intent_feature_counts")
        private val vocabularySize: Int

        init {
            val vocabulary = linkedSetOf<String>()
            counts.keys().forEach { intent ->
                counts.getJSONObject(intent).keys().forEach(vocabulary::add)
            }
            vocabularySize = vocabulary.size.coerceAtLeast(1)
        }

        fun predict(text: String): Pair<String, Double> {
            if (totalDocs <= 0 || intentDocs.isEmpty()) {
                return "general_query" to 0.0
            }

            val features = extractFeatures(text)
            val intentCount = intentDocs.size
            val scores = linkedMapOf<String, Double>()

            intentDocs.forEach { (intent, documentCount) ->
                var logScore = ln(
                    (documentCount + alpha) /
                        (totalDocs + alpha * intentCount)
                )
                val denominator = intentTotals.getValue(intent) + alpha * vocabularySize
                val featureCounts = counts.getJSONObject(intent)
                features.forEach { (feature, occurrences) ->
                    val probability = (
                        featureCounts.optInt(feature, 0) + alpha
                        ) / denominator
                    logScore += occurrences * ln(probability)
                }
                scores[intent] = logScore
            }

            val ordered = scores.entries.sortedByDescending { it.value }
            val best = ordered.first()
            val normalizer = ordered.sumOf { exp(it.value - best.value) }
            var probability = 1.0 / normalizer.coerceAtLeast(1.0)

            if (ordered.size > 1) {
                val margin = best.value - ordered[1].value
                val calibrated = 1.0 - exp(-margin.coerceAtLeast(0.0) / 4.0)
                probability = minOf(probability, 0.55 + calibrated * 0.45)
            }

            return best.key to probability.coerceIn(0.0, 1.0)
        }

        private fun JSONObject.toIntMap(): Map<String, Int> =
            buildMap {
                keys().forEach { key ->
                    put(key, optInt(key, 0))
                }
            }

        private fun extractFeatures(value: String): Map<String, Int> {
            val cleaned = clean(value)
            val words = cleaned.split(Regex("""\s+"""))
                .filter(String::isNotBlank)
            val result = linkedMapOf<String, Int>()

            fun add(feature: String) {
                result[feature] = result.getOrDefault(feature, 0) + 1
            }

            words.forEach { add("w:$it") }
            words.zipWithNext().forEach { (first, second) ->
                add("b:${first}_$second")
            }

            val compact = " ${cleaned} ".replace(" ", "_")
            for (size in 3..4) {
                if (compact.length < size) continue
                for (index in 0..compact.length - size) {
                    add("c$size:${compact.substring(index, index + size)}")
                }
            }
            return result
        }

        private fun clean(value: String): String =
            value.lowercase(Locale.ROOT)
                .replace('’', '\'')
                .replace(Regex("""[^\p{L}\p{N}\s']"""), " ")
                .replace(Regex("""\s+"""), " ")
                .trim()
    }

    private companion object {
        const val MODEL_ASSET = "nlu/intent_router.json"
        const val MODEL_THRESHOLD = 0.68
    }
}
