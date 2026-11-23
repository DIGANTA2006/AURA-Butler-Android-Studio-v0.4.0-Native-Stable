package `in`.diganta.aurabutler.data

import `in`.diganta.aurabutler.model.ButlerSettings
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

data class NewsStory(
    val title: String,
    val description: String,
    val content: String,
    val url: String,
    val imageUrl: String,
    val sourceName: String,
    val sourceUrl: String,
    val publishedAtEpochMs: Long?
) {
    fun bestSummary(): String = description
        .ifBlank { content }
        .ifBlank { "No summary was supplied by the news feed." }
}

data class NewsBatch(
    val stories: List<NewsStory>,
    val providerLabel: String,
    val delayedFreeTier: Boolean
)

/**
 * Free news source used by AURA.
 *
 * GNews is used when the owner supplies a free API key. Google News RSS is a
 * keyless fallback so headline commands remain useful even when the API quota
 * is unavailable. Article pages are never scraped or copied in full.
 */
class FreeNewsProvider {
    suspend fun fetch(
        settings: ButlerSettings,
        topic: String?,
        limit: Int = 6
    ): NewsBatch = withContext(Dispatchers.IO) {
        val boundedLimit = limit.coerceIn(1, 10)
        val key = settings.gnewsApiKey.trim()

        if (key.isNotBlank()) {
            val apiResult = runCatching {
                fetchFromGNews(
                    apiKey = key,
                    topic = topic,
                    limit = boundedLimit
                )
            }.getOrNull()

            if (apiResult != null && apiResult.isNotEmpty()) {
                return@withContext NewsBatch(
                    stories = apiResult,
                    providerLabel = "GNews free API",
                    delayedFreeTier = true
                )
            }
        }

        NewsBatch(
            stories = fetchFromRss(topic, boundedLimit),
            providerLabel = "Google News RSS fallback",
            delayedFreeTier = false
        )
    }

    private fun fetchFromGNews(
        apiKey: String,
        topic: String?,
        limit: Int
    ): List<NewsStory> {
        val endpoint = if (topic.isNullOrBlank()) {
            buildString {
                append("https://gnews.io/api/v4/top-headlines")
                append("?category=general")
                append("&lang=en&country=in")
                append("&max=$limit")
                append("&apikey=${encode(apiKey)}")
            }
        } else {
            buildString {
                append("https://gnews.io/api/v4/search")
                append("?q=${encode(topic.take(180))}")
                append("&lang=en&country=in")
                append("&max=$limit")
                append("&sortby=publishedAt")
                append("&apikey=${encode(apiKey)}")
            }
        }

        val root = JSONObject(get(endpoint, accept = "application/json"))
        val articles = root.optJSONArray("articles") ?: return emptyList()
        return buildList {
            repeat(articles.length()) { index ->
                val item = articles.optJSONObject(index) ?: return@repeat
                val title = clean(item.optString("title"), 320)
                val url = item.optString("url").trim()
                if (title.isBlank() || url.isBlank()) return@repeat
                val source = item.optJSONObject("source")
                add(
                    NewsStory(
                        title = title,
                        description = clean(item.optString("description"), 1_200),
                        content = clean(item.optString("content"), 1_800),
                        url = url,
                        imageUrl = item.optString("image").trim().take(1_000),
                        sourceName = clean(source?.optString("name").orEmpty(), 180),
                        sourceUrl = source?.optString("url").orEmpty().trim().take(1_000),
                        publishedAtEpochMs = parseIsoTime(item.optString("publishedAt"))
                    )
                )
            }
        }.distinctBy { it.title.lowercase(Locale.ROOT) }
    }

    private fun fetchFromRss(topic: String?, limit: Int): List<NewsStory> {
        val feed = topic
            ?.trim()
            ?.take(180)
            ?.takeIf(String::isNotBlank)
            ?.let {
                "https://news.google.com/rss/search?q=${encode(it)}&hl=en-IN&gl=IN&ceid=IN:en"
            }
            ?: "https://news.google.com/rss?hl=en-IN&gl=IN&ceid=IN:en"

        val parser = XmlPullParserFactory.newInstance().newPullParser().apply {
            setInput(StringReader(get(feed, accept = "application/rss+xml, text/xml")))
        }
        val stories = mutableListOf<NewsStory>()
        var insideItem = false
        var title = ""
        var link = ""
        var source = ""
        var description = ""
        var pubDate = ""
        var event = parser.eventType

        while (event != XmlPullParser.END_DOCUMENT && stories.size < limit) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name.lowercase(Locale.ROOT)) {
                    "item" -> {
                        insideItem = true
                        title = ""
                        link = ""
                        source = ""
                        description = ""
                        pubDate = ""
                    }

                    "title" -> if (insideItem) title = nextText(parser)
                    "link" -> if (insideItem) link = nextText(parser)
                    "source" -> if (insideItem) source = nextText(parser)
                    "description" -> if (insideItem) description = nextText(parser)
                    "pubdate" -> if (insideItem) pubDate = nextText(parser)
                }

                XmlPullParser.END_TAG -> if (
                    insideItem && parser.name.equals("item", true)
                ) {
                    insideItem = false
                    val cleanTitle = clean(title, 320)
                    if (cleanTitle.isNotBlank() && link.isNotBlank()) {
                        stories += NewsStory(
                            title = cleanTitle,
                            description = cleanHtml(description, 1_200),
                            content = "",
                            url = link.trim(),
                            imageUrl = "",
                            sourceName = clean(source, 180),
                            sourceUrl = "",
                            publishedAtEpochMs = parseRssTime(pubDate)
                        )
                    }
                }
            }
            event = parser.next()
        }
        return stories.distinctBy { it.title.lowercase(Locale.ROOT) }
    }

    private fun nextText(parser: XmlPullParser): String = runCatching {
        parser.nextText().trim()
    }.getOrDefault("")

    private fun cleanHtml(value: String, max: Int): String = clean(
        value
            .replace(Regex("""<[^>]+>"""), " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">"),
        max
    )

    private fun clean(value: String, max: Int): String = value
        .replace(Regex("""\s+"""), " ")
        .trim()
        .take(max)

    private fun parseIsoTime(value: String): Long? = runCatching {
        Instant.parse(value).toEpochMilli()
    }.getOrNull()

    private fun parseRssTime(value: String): Long? = runCatching {
        ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
            .toInstant()
            .toEpochMilli()
    }.getOrNull()

    private fun get(rawUrl: String, accept: String): String {
        val connection = URL(rawUrl).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = true
            connection.useCaches = false
            connection.setRequestProperty("Accept", accept)
            connection.setRequestProperty("User-Agent", "AURA-Butler-Android/0.12.1")
            val status = connection.responseCode
            if (status !in 200..299) {
                val errorText = connection.errorStream
                    ?.let { SafeHttpClient.readUtf8(it, 32 * 1024).take(500) }
                    .orEmpty()
                error("News service returned HTTP $status. $errorText")
            }
            connection.inputStream
                .let { SafeHttpClient.readUtf8(it, 2 * 1024 * 1024) }
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}
