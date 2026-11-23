package `in`.diganta.aurabutler.data

import `in`.diganta.aurabutler.analyzer.NewsEntry
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

class DetailedNewsProvider {
    suspend fun keylessDigest(
        topic: String?,
        limit: Int = 6
    ): String = withContext(Dispatchers.IO) {
        val entries = load(topic, limit)
        if (entries.isEmpty()) {
            return@withContext "The current news feed returned no readable stories."
        }

        buildString {
            append("Current news")
            topic?.takeIf(String::isNotBlank)?.let { append(" about ${it.trim()}") }
            append(":\n\n")
            entries.forEachIndexed { index, item ->
                append(index + 1)
                append(". ")
                append(item.title)
                append('\n')
                append("   Source: ")
                append(item.source.ifBlank { "Google News" })
                item.publishedAtEpochMs?.let {
                    append(" • ")
                    append(formatTime(it))
                }
                append('\n')
                append("   Open: ")
                append(item.url)
                append("\n\n")
            }
            append(
                "This keyless mode can verify headlines, sources and publication times. " +
                    "Enable Gemini and ask for detailed news when you want researched context, " +
                    "why each story matters and cited sources."
            )
        }.trim()
    }

    suspend fun load(
        topic: String?,
        limit: Int = 6
    ): List<NewsEntry> = withContext(Dispatchers.IO) {
        val feed = topic
            ?.trim()
            ?.take(120)
            ?.takeIf(String::isNotBlank)
            ?.let {
                "https://news.google.com/rss/search?q=${encode(it)}&hl=en-IN&gl=IN&ceid=IN:en"
            }
            ?: "https://news.google.com/rss?hl=en-IN&gl=IN&ceid=IN:en"
        parse(get(feed), limit.coerceIn(1, 12))
    }

    private fun parse(xml: String, limit: Int): List<NewsEntry> {
        val parser = XmlPullParserFactory.newInstance().newPullParser().apply {
            setInput(StringReader(xml))
        }
        val entries = mutableListOf<NewsEntry>()
        var insideItem = false
        var title = ""
        var link = ""
        var source = ""
        var pubDate = ""
        var event = parser.eventType

        while (event != XmlPullParser.END_DOCUMENT && entries.size < limit) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name.lowercase(Locale.ROOT)) {
                    "item" -> {
                        insideItem = true
                        title = ""
                        link = ""
                        source = ""
                        pubDate = ""
                    }

                    "title" -> if (insideItem) title = nextText(parser)
                    "link" -> if (insideItem) link = nextText(parser)
                    "source" -> if (insideItem) source = nextText(parser)
                    "pubdate" -> if (insideItem) pubDate = nextText(parser)
                }

                XmlPullParser.END_TAG -> if (
                    insideItem && parser.name.equals("item", true)
                ) {
                    insideItem = false
                    if (title.isNotBlank() && link.isNotBlank()) {
                        entries += NewsEntry(
                            title = cleanHeadline(title),
                            source = source.trim(),
                            publishedAtEpochMs = parseDate(pubDate),
                            url = link.trim()
                        )
                    }
                }
            }
            event = parser.next()
        }
        return entries.distinctBy { it.title.lowercase(Locale.ROOT) }
    }

    private fun nextText(parser: XmlPullParser): String = runCatching {
        parser.nextText().trim()
    }.getOrDefault("")

    private fun cleanHeadline(value: String): String = value
        .replace(Regex("""\s+"""), " ")
        .trim()
        .take(300)

    private fun parseDate(value: String): Long? = runCatching {
        ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
            .toInstant()
            .toEpochMilli()
    }.getOrNull()

    private fun formatTime(epochMs: Long): String = runCatching {
        java.time.Instant.ofEpochMilli(epochMs)
            .atZone(java.time.ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("dd MMM, h:mm a"))
    }.getOrDefault("time unavailable")

    private fun get(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 12_000
            instanceFollowRedirects = true
            useCaches = false
            setRequestProperty("Accept", "application/rss+xml, application/xml, text/xml")
            setRequestProperty("User-Agent", "AURA-Butler-Android/0.12.1")
        }
        try {
            check(connection.responseCode in 200..299) {
                "News request failed with HTTP ${connection.responseCode}."
            }
            return connection.inputStream
                .let { SafeHttpClient.readUtf8(it, 2 * 1024 * 1024) }
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}
