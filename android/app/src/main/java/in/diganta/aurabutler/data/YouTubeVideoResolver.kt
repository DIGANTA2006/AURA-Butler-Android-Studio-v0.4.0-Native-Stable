package `in`.diganta.aurabutler.data

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class ResolvedYouTubeVideo(
    val videoId: String,
    val title: String,
    val channelTitle: String,
    val query: String,
    val score: Int
) {
    val watchUrl: String
        get() = "https://www.youtube.com/watch?v=$videoId"
}

/**
 * Resolves a spoken title to a concrete YouTube video through the official
 * YouTube Data API. The final watch URL is opened by Android's YouTube app.
 */
class YouTubeVideoResolver {
    suspend fun resolve(
        query: String,
        apiKey: String
    ): ResolvedYouTubeVideo? = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim().take(MAX_QUERY_CHARS)
        val key = apiKey.trim()
        if (cleanQuery.isBlank() || key.isBlank()) {
            return@withContext null
        }

        val endpoint = buildString {
            append(ENDPOINT)
            append("?part=snippet")
            append("&type=video")
            append("&maxResults=8")
            append("&order=relevance")
            append("&safeSearch=moderate")
            append("&videoEmbeddable=true")
            append("&regionCode=IN")
            append("&relevanceLanguage=en")
            append("&q=")
            append(encode(cleanQuery))
            append("&key=")
            append(encode(key))
        }

        val connection = (
            URL(endpoint).openConnection() as HttpURLConnection
            ).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            useCaches = false
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty(
                "User-Agent",
                "AURA-Butler-Android/0.12.1"
            )
        }

        try {
            val status = connection.responseCode
            val responseText = (
                if (status in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }
                ).let { SafeHttpClient.readUtf8(it, 1024 * 1024) }

            if (status !in 200..299) {
                Log.w(
                    TAG,
                    "YouTube search failed with HTTP $status; key redacted"
                )
                return@withContext null
            }

            val items = JSONObject(responseText).optJSONArray("items")
                ?: return@withContext null
            val candidates = buildList {
                repeat(items.length()) { index ->
                    val item = items.optJSONObject(index)
                        ?: return@repeat
                    val videoId = item
                        .optJSONObject("id")
                        ?.optString("videoId")
                        .orEmpty()
                    val snippet = item.optJSONObject("snippet")
                        ?: return@repeat
                    val title = decodeHtml(
                        snippet.optString("title")
                    ).trim()
                    val channel = decodeHtml(
                        snippet.optString("channelTitle")
                    ).trim()

                    if (videoId.isBlank() || title.isBlank()) {
                        return@repeat
                    }

                    add(
                        ResolvedYouTubeVideo(
                            videoId = videoId,
                            title = title,
                            channelTitle = channel,
                            query = cleanQuery,
                            score = score(
                                query = cleanQuery,
                                title = title,
                                channel = channel,
                                index = index
                            )
                        )
                    )
                }
            }

            candidates
                .maxByOrNull { it.score }
                ?.takeIf { it.score >= MIN_ACCEPTABLE_SCORE }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(
                TAG,
                "YouTube search failed: ${error.javaClass.simpleName}"
            )
            null
        } finally {
            connection.disconnect()
        }
    }

    internal fun score(
        query: String,
        title: String,
        channel: String,
        index: Int
    ): Int {
        val queryNorm = normalize(query)
        val titleNorm = normalize(title)
        val channelNorm = normalize(channel)
        val queryTokens = tokens(queryNorm)
        val titleTokens = tokens(titleNorm)

        var score = 100 - index * 5

        if (titleNorm == queryNorm) score += 140
        if (titleNorm.startsWith(queryNorm)) score += 80
        if (titleNorm.contains(queryNorm)) score += 70

        val overlap = queryTokens.count { it in titleTokens }
        score += overlap * 24
        if (
            queryTokens.isNotEmpty() &&
            overlap == queryTokens.size
        ) {
            score += 55
        }

        if (
            titleNorm.contains("official video") ||
            titleNorm.contains("official music video") ||
            titleNorm.contains("official audio") ||
            titleNorm.contains("lyrics")
        ) {
            score += 26
        }

        if (
            channelNorm.contains("official") ||
            channelNorm.contains("vevo") ||
            channelNorm.contains("coke studio") ||
            channelNorm.contains("t series") ||
            channelNorm.contains("sony music") ||
            channelNorm.contains("saregama")
        ) {
            score += 28
        }

        val queryRequestsVariant = VARIANT_TERMS.any(
            queryNorm::contains
        )
        if (!queryRequestsVariant) {
            if (titleNorm.contains("cover")) score -= 55
            if (titleNorm.contains("reaction")) score -= 70
            if (titleNorm.contains("karaoke")) score -= 55
            if (titleNorm.contains("slowed")) score -= 45
            if (titleNorm.contains("reverb")) score -= 45
            if (titleNorm.contains("remix")) score -= 40
            if (titleNorm.contains("live")) score -= 22
            if (titleNorm.contains("#shorts")) score -= 70
        }

        return score
    }

    private fun normalize(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace("&amp;", " and ")
        .replace(
            Regex("""[^a-z0-9\u0900-\u097f\u0980-\u09ff]+"""),
            " "
        )
        .replace(Regex("""\s+"""), " ")
        .trim()

    private fun tokens(value: String): Set<String> = value
        .split(' ')
        .filter {
            it.length >= 2 &&
                it !in STOP_WORDS
        }
        .toSet()

    private fun decodeHtml(value: String): String = value
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")

    private fun encode(value: String): String = URLEncoder.encode(
        value,
        StandardCharsets.UTF_8.name()
    )

    private companion object {
        const val TAG = "AuraYouTubeResolver"
        const val ENDPOINT =
            "https://www.googleapis.com/youtube/v3/search"
        const val CONNECT_TIMEOUT_MS = 8_000
        const val READ_TIMEOUT_MS = 12_000
        const val MAX_QUERY_CHARS = 180
        const val MIN_ACCEPTABLE_SCORE = 85

        val STOP_WORDS = setOf(
            "the",
            "a",
            "an",
            "song",
            "video",
            "music",
            "official",
            "on",
            "by"
        )
        val VARIANT_TERMS = setOf(
            "cover",
            "reaction",
            "karaoke",
            "slowed",
            "reverb",
            "remix",
            "live"
        )
    }
}
