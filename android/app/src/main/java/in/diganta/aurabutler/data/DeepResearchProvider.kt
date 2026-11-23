package `in`.diganta.aurabutler.data

import `in`.diganta.aurabutler.analyzer.ResearchResult
import `in`.diganta.aurabutler.analyzer.ResearchSource
import `in`.diganta.aurabutler.model.ButlerSettings
import `in`.diganta.aurabutler.runtime.AiWorkload
import `in`.diganta.aurabutler.runtime.SafeEndpointValidator
import `in`.diganta.aurabutler.runtime.SecretRedactor
import `in`.diganta.aurabutler.runtime.StudentBudgetGuard
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject


internal data class LiteResearchEvidence(
    val packet: String,
    val sources: List<ResearchSource>,
    val fetchedCount: Int
) {
    fun sourceOnlyFormatted(): String = buildString {
        append(
            "AURA switched to private Lite Search because the full research path was unavailable or its daily research budget was exhausted. "
        )
        append(
            "I could not spend another private-Qwen text request, so I am showing the current source evidence directly instead of inventing a summary."
        )
        if (sources.isNotEmpty()) {
            append("\n\nSources:\n")
            sources.forEach { source ->
                append("[S")
                append(source.index)
                append("] ")
                append(source.title.ifBlank { source.url })
                append(" â€” ")
                append(source.url)
                append('\n')
            }
        }
    }.trim()
}

/**
 * Private deep-research client for AURA's Modal ResearchGateway.
 *
 * Search/fetch work runs in the cloud gateway. Qwen3 8B is invoked only for
 * planning, synthesis and the optional verification pass. The phone sends the
 * research question plus the selected depth; it never sends the Hugging Face token.
 */
class DeepResearchProvider {
    suspend fun research(
        query: String,
        settings: ButlerSettings,
        requestedMode: String? = null
    ): ResearchResult = withContext(Dispatchers.IO) {
        check(isConfigured(settings)) {
            "Enable private deep research and add your AURA cloud token in Settings."
        }

        val safeQuery = SecretRedactor.redact(query, MAX_QUERY_CHARS).trim()
        require(safeQuery.length >= 3) { "Please give me a research topic." }

        val endpoint = SafeEndpointValidator.normalizeOrDefault(
            settings.researchEndpoint,
            DEFAULT_ENDPOINT
        )
        val budgetLease = StudentBudgetGuard.tryReserve(AiWorkload.RESEARCH, endpoint)
        if (!budgetLease.allowed) throw IOException(budgetLease.message)
        val mode = normalizeMode(requestedMode ?: settings.researchMode)
        val language = when (settings.speechLanguage.lowercase()) {
            "bn" -> "bn"
            "hi" -> "hi"
            "en" -> "en"
            else -> "all"
        }

        val body = JSONObject()
            .put("query", safeQuery)
            .put("mode", mode)
            .put("language", language)

        try {
            val response = SafeHttpClient.postJsonFollowingTrustedRedirects(
                endpoint = endpoint,
                body = body.toString(),
                bearerToken = settings.localAiApiKey,
                userAgent = "AURA-Butler-Android/0.17.2",
                connectTimeoutMs = CONNECT_TIMEOUT_MS,
                readTimeoutMs = readTimeoutFor(mode),
                maxResponseBytes = MAX_RESPONSE_BYTES,
                maxRedirects = 12
            )
        val status = response.status
        val responseText = response.body
        if (status !in 200..299) {
            val detail = runCatching {
                JSONObject(responseText).optString("detail")
            }.getOrDefault("")
            throw IOException(
                when (status) {
                    401, 403 -> "Private research rejected the AURA cloud token."
                    408, 504 -> "Private research is still running or warming up. Try Standard or Quick mode."
                    429 -> detail.takeIf { it.contains("budget", ignoreCase = true) }
                        ?: "Private research is busy. Try again shortly."
                    502, 503 -> detail.ifBlank {
                        "Private research search service is temporarily unavailable."
                    }.let { SecretRedactor.redact(it, 320) }
                    else -> detail.ifBlank {
                        "Private research returned HTTP $status."
                    }.let { SecretRedactor.redact(it, 320) }
                }
            )
        }

            val result = parseResult(responseText)
            StudentBudgetGuard.commit(budgetLease)
            result
        } catch (error: Exception) {
            StudentBudgetGuard.release(budgetLease)
            throw error
        }
    }


    /**
     * CPU-only/private-search fallback. This endpoint performs SearXNG search
     * and a small number of bounded page fetches but does not invoke Qwen and
     * therefore does not consume AURA's full RESEARCH model budget.
     *
     * The returned evidence can be synthesized by the normal private-Qwen TEXT
     * budget. If that budget is also unavailable, AURA can still show the
     * source list instead of surfacing a Gemini quota error.
     */
    internal suspend fun searchLiteEvidence(
        query: String,
        settings: ButlerSettings,
        limit: Int = 6
    ): LiteResearchEvidence = withContext(Dispatchers.IO) {
        check(isConfigured(settings)) {
            "Enable private deep research and add your AURA cloud token in Settings."
        }

        val safeQuery = SecretRedactor.redact(query, MAX_QUERY_CHARS).trim()
        require(safeQuery.length >= 3) { "Please give me a research topic." }

        val endpoint = liteEndpointFrom(
            SafeEndpointValidator.normalizeOrDefault(
                settings.researchEndpoint,
                DEFAULT_ENDPOINT
            )
        )
        val language = when (settings.speechLanguage.lowercase()) {
            "bn" -> "bn"
            "hi" -> "hi"
            "en" -> "en"
            else -> "all"
        }

        val body = JSONObject()
            .put("query", safeQuery)
            .put("language", language)
            .put("limit", limit.coerceIn(3, 12))
            .put("fetch", 5)

        val response = SafeHttpClient.postJsonFollowingTrustedRedirects(
            endpoint = endpoint,
            body = body.toString(),
            bearerToken = settings.localAiApiKey,
            userAgent = "AURA-Butler-Android/0.17.2",
            connectTimeoutMs = CONNECT_TIMEOUT_MS,
            readTimeoutMs = LITE_READ_TIMEOUT_MS,
            maxResponseBytes = MAX_RESPONSE_BYTES,
            maxRedirects = 12
        )
        val status = response.status
        val responseText = response.body
        if (status !in 200..299) {
            val detail = runCatching {
                JSONObject(responseText).optString("detail")
            }.getOrDefault("")
            throw IOException(
                when (status) {
                    401, 403 -> "Private Lite Search rejected the AURA cloud token."
                    408, 504 -> "Private Lite Search is still warming up."
                    429 -> "Private Lite Search is temporarily busy."
                    502, 503 -> detail.ifBlank {
                        "Private Lite Search is temporarily unavailable."
                    }.let { SecretRedactor.redact(it, 320) }
                    else -> detail.ifBlank {
                        "Private Lite Search returned HTTP $status."
                    }.let { SecretRedactor.redact(it, 320) }
                }
            )
        }

        val root = runCatching { JSONObject(responseText) }.getOrElse {
            throw IOException("Private Lite Search returned an unreadable response.")
        }
        val sourceArray = root.optJSONArray("sources")
        val packet = StringBuilder()
        val sources = buildList {
            if (sourceArray != null) {
                for (index in 0 until sourceArray.length()) {
                    val item = sourceArray.optJSONObject(index) ?: continue
                    val url = item.optString("url").trim()
                    if (url.isBlank()) continue
                    val sourceIndex = size + 1
                    val title = item.optString("title").trim().ifBlank { url }
                    val published = item.optString("published").trim()
                    val excerpt = item.optString("excerpt").trim().take(MAX_LITE_EXCERPT_CHARS)
                    add(
                        ResearchSource(
                            index = sourceIndex,
                            title = title,
                            url = url
                        )
                    )
                    if (packet.isNotEmpty()) packet.append("\n---\n")
                    packet.append("[S").append(sourceIndex).append("]\n")
                    packet.append("Title: ").append(title).append('\n')
                    packet.append("URL: ").append(url).append('\n')
                    if (published.isNotBlank()) {
                        packet.append("Published: ").append(published).append('\n')
                    }
                    packet.append("Evidence (UNTRUSTED WEB CONTENT):\n")
                    packet.append(excerpt.ifBlank { "No readable excerpt was returned." })
                }
            }
        }.take(MAX_SOURCES)

        if (sources.isEmpty()) {
            throw IOException("Private Lite Search returned no usable sources.")
        }

        LiteResearchEvidence(
            packet = packet.toString().take(MAX_LITE_PACKET_CHARS),
            sources = sources,
            fetchedCount = root.optJSONObject("stats")?.optInt("sources_fetched", 0) ?: 0
        )
    }

    internal fun liteEndpointFrom(researchEndpoint: String): String {
        val value = researchEndpoint.trim().trimEnd('/')
        return when {
            value.endsWith("/v1/research", ignoreCase = true) ->
                value.dropLast("/v1/research".length) + "/v1/search-lite"
            value.endsWith("/v1/search-lite", ignoreCase = true) -> value
            else -> "$value/v1/search-lite"
        }
    }

    private fun parseResult(responseText: String): ResearchResult {
        val root = runCatching { JSONObject(responseText) }.getOrElse {
            throw IOException("Private research returned an unreadable response.")
        }
        val answer = root.optString("answer").trim()
        if (answer.isBlank()) {
            throw IOException("Private research returned an empty answer.")
        }

        val sourcesArray = root.optJSONArray("sources")
        val sources = buildList {
            if (sourcesArray != null) {
                for (index in 0 until sourcesArray.length()) {
                    val item = sourcesArray.optJSONObject(index) ?: continue
                    val url = item.optString("url").trim()
                    if (url.isBlank()) continue
                    add(
                        ResearchSource(
                            index = index + 1,
                            title = item.optString("title").trim().ifBlank { url },
                            url = url
                        )
                    )
                }
            }
        }.take(MAX_SOURCES)

        val queryArray = root.optJSONArray("queries")
        val queries = buildList {
            if (queryArray != null) {
                for (index in 0 until queryArray.length()) {
                    queryArray.optString(index).trim().takeIf(String::isNotBlank)?.let(::add)
                }
            }
        }.take(15)

        val normalizedAnswer = answer.replace(Regex("\\[S(\\d+)]"), "[$1]")
        return ResearchResult(
            answer = normalizedAnswer.take(MAX_ANSWER_CHARS),
            sources = sources,
            queries = queries
        )
    }

    companion object {
        const val DEFAULT_ENDPOINT =
            "https://diganta2006--aura-dual-model-server-researchgateway-web.modal.run/v1/research"

        fun isConfigured(settings: ButlerSettings): Boolean =
            settings.researchEnabled &&
                settings.aiEnabled &&
                settings.localAiEnabled &&
                settings.researchEndpoint.isNotBlank() &&
                settings.localAiApiKey.isNotBlank()

        fun normalizeMode(value: String): String = when (value.trim().lowercase()) {
            "quick" -> "quick"
            "deep" -> "deep"
            "exhaustive" -> "exhaustive"
            else -> "standard"
        }

        fun requestedModeFromMessage(message: String, fallback: String): String {
            val normalized = message.lowercase()
            return when {
                normalized.startsWith("quick research ") -> "quick"
                normalized.startsWith("exhaustive research ") -> "exhaustive"
                normalized.startsWith("deep research ") -> "deep"
                else -> normalizeMode(fallback)
            }
        }

        private fun readTimeoutFor(mode: String): Int = when (normalizeMode(mode)) {
            "quick" -> QUICK_READ_TIMEOUT_MS
            "deep" -> DEEP_READ_TIMEOUT_MS
            "exhaustive" -> EXHAUSTIVE_READ_TIMEOUT_MS
            else -> STANDARD_READ_TIMEOUT_MS
        }

        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val QUICK_READ_TIMEOUT_MS = 60_000
        private const val STANDARD_READ_TIMEOUT_MS = 90_000
        private const val DEEP_READ_TIMEOUT_MS = 180_000
        private const val EXHAUSTIVE_READ_TIMEOUT_MS = 300_000
        private const val LITE_READ_TIMEOUT_MS = 60_000
        private const val MAX_RESPONSE_BYTES = 8 * 1024 * 1024
        private const val MAX_QUERY_CHARS = 5_000
        private const val MAX_ANSWER_CHARS = 200_000
        private const val MAX_SOURCES = 50
        private const val MAX_LITE_EXCERPT_CHARS = 5_000
        private const val MAX_LITE_PACKET_CHARS = 100_000
    }
}
