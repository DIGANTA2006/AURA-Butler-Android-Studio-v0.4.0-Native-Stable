package `in`.diganta.aurabutler.data

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.StatFs
import `in`.diganta.aurabutler.domain.LiveInfoProvider
import `in`.diganta.aurabutler.model.ButlerSettings
import `in`.diganta.aurabutler.runtime.runCatchingCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class KeylessLiveInfoProvider(context: Context? = null) : LiveInfoProvider {
    private val appContext = context?.applicationContext

    override suspend fun weather(settings: ButlerSettings): String =
        weatherFor(settings, null)

    override suspend fun weatherFor(
        settings: ButlerSettings,
        location: String?
    ): String = withContext(Dispatchers.IO) {
        val place = if (location.isNullOrBlank()) {
            Place(
                name = settings.homeCity,
                latitude = settings.homeLatitude,
                longitude = settings.homeLongitude
            )
        } else {
            geocode(location)
        }
        val url = buildString {
            append("https://api.open-meteo.com/v1/forecast")
            append("?latitude=${place.latitude}")
            append("&longitude=${place.longitude}")
            append("&current=temperature_2m,apparent_temperature,relative_humidity_2m,")
            append("weather_code,wind_speed_10m")
            append("&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max")
            append("&timezone=auto&forecast_days=2")
        }
        val root = JSONObject(get(url))
        val current = root.getJSONObject("current")
        val daily = root.getJSONObject("daily")
        val temperature = current.getDouble("temperature_2m")
        val feelsLike = current.getDouble("apparent_temperature")
        val humidity = current.getInt("relative_humidity_2m")
        val wind = current.getDouble("wind_speed_10m")
        val code = current.getInt("weather_code")
        val high = daily.getJSONArray("temperature_2m_max").getDouble(0)
        val low = daily.getJSONArray("temperature_2m_min").getDouble(0)
        val rain = daily.getJSONArray("precipitation_probability_max").optInt(0, 0)

        "${place.name}: ${weatherDescription(code)}, " +
            "${temperature.oneDecimal()}°C, feels like ${feelsLike.oneDecimal()}°C. " +
            "Today’s range is ${low.oneDecimal()} to ${high.oneDecimal()}°C, " +
            "humidity $humidity%, rain chance $rain%, and wind ${wind.oneDecimal()} km/h."
    }

    override suspend fun weatherForecast(
        settings: ButlerSettings,
        location: String?,
        days: Int
    ): String = withContext(Dispatchers.IO) {
        val boundedDays = days.coerceIn(3, 16)
        val place = if (location.isNullOrBlank()) {
            Place(
                name = settings.homeCity,
                latitude = settings.homeLatitude,
                longitude = settings.homeLongitude
            )
        } else {
            geocode(location)
        }
        val url = buildString {
            append("https://api.open-meteo.com/v1/forecast")
            append("?latitude=${place.latitude}")
            append("&longitude=${place.longitude}")
            append("&daily=weather_code,temperature_2m_max,temperature_2m_min,")
            append("apparent_temperature_max,apparent_temperature_min,")
            append("precipitation_probability_max,precipitation_sum,")
            append("wind_speed_10m_max,sunrise,sunset")
            append("&timezone=auto&forecast_days=$boundedDays")
        }
        val root = JSONObject(get(url))
        val daily = root.getJSONObject("daily")
        val dates = daily.getJSONArray("time")
        val codes = daily.getJSONArray("weather_code")
        val highs = daily.getJSONArray("temperature_2m_max")
        val lows = daily.getJSONArray("temperature_2m_min")
        val feelsHigh = daily.getJSONArray("apparent_temperature_max")
        val feelsLow = daily.getJSONArray("apparent_temperature_min")
        val rainChance = daily.getJSONArray("precipitation_probability_max")
        val rainTotal = daily.getJSONArray("precipitation_sum")
        val windMax = daily.getJSONArray("wind_speed_10m_max")

        buildString {
            append("${place.name}: $boundedDays-day weather forecast")
            append("\n\n")
            repeat(minOf(boundedDays, dates.length())) { index ->
                val date = LocalDate.parse(dates.getString(index))
                append(index + 1)
                append(". ")
                append(date.format(DateTimeFormatter.ofPattern("EEE, dd MMM", Locale.ENGLISH)))
                append(": ")
                append(weatherDescription(codes.getInt(index)))
                append(", ")
                append(lows.getDouble(index).oneDecimal())
                append(" to ")
                append(highs.getDouble(index).oneDecimal())
                append("°C")
                append("; feels ")
                append(feelsLow.getDouble(index).oneDecimal())
                append(" to ")
                append(feelsHigh.getDouble(index).oneDecimal())
                append("°C")
                append("; rain ")
                append(rainChance.optInt(index, 0))
                append("%")
                val precipitation = rainTotal.optDouble(index, 0.0)
                if (precipitation > 0.0) {
                    append(" (")
                    append(precipitation.oneDecimal())
                    append(" mm)")
                }
                append("; wind up to ")
                append(windMax.getDouble(index).oneDecimal())
                append(" km/h.")
                append('\n')
            }
            append("\nForecast source: Open-Meteo. Later forecast days naturally have more uncertainty.")
        }.trim()
    }

    override suspend fun headlines(limit: Int): List<String> =
        headlinesFor(limit, null)

    override suspend fun headlinesFor(
        limit: Int,
        topic: String?
    ): List<String> = withContext(Dispatchers.IO) {
        val feed = topic
            ?.trim()
            ?.take(80)
            ?.takeIf(String::isNotBlank)
            ?.let {
                "https://news.google.com/rss/search?q=${encode(it)}&hl=en-IN&gl=IN&ceid=IN:en"
            }
            ?: "https://news.google.com/rss?hl=en-IN&gl=IN&ceid=IN:en"
        val xml = get(feed)
        val parser = XmlPullParserFactory.newInstance().newPullParser().apply {
            setInput(StringReader(xml))
        }
        val titles = mutableListOf<String>()
        var insideItem = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT && titles.size < limit) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.name.lowercase(Locale.ROOT)) {
                        "item" -> insideItem = true
                        "title" -> if (insideItem) {
                            parser.next()
                            if (parser.eventType == XmlPullParser.TEXT) {
                                parser.text
                                    ?.trim()
                                    ?.takeIf(String::isNotBlank)
                                    ?.let(titles::add)
                            }
                        }
                    }
                }

                XmlPullParser.END_TAG -> if (parser.name.equals("item", true)) {
                    insideItem = false
                }
            }
            event = parser.next()
        }
        titles
    }

    override suspend fun knowledge(query: String): String? = withContext(Dispatchers.IO) {
        val cleaned = query.trim().take(180)
        if (cleaned.isBlank()) return@withContext null

        runCatchingCancellable { wikipediaSummary(cleaned) }
            .getOrNull()
            ?.takeIf(String::isNotBlank)
            ?: runCatchingCancellable { duckDuckGoAnswer(cleaned) }
                .getOrNull()
                ?.takeIf(String::isNotBlank)
    }

    override fun deviceStatus(): String {
        val context = appContext ?: return "Device status is unavailable in this session."
        val battery = context.getSystemService(BatteryManager::class.java)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            .takeIf { it in 0..100 }
        val activity = context.getSystemService(ActivityManager::class.java)
        val memory = ActivityManager.MemoryInfo().also(activity::getMemoryInfo)
        val storage = StatFs(context.filesDir.absolutePath)
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val network = connectivity.activeNetwork
        val capabilities = network?.let(connectivity::getNetworkCapabilities)
        val online = capabilities?.hasCapability(
            NetworkCapabilities.NET_CAPABILITY_INTERNET
        ) == true
        val networkName = when {
            !online -> "offline"
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "Wi-Fi"
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true ->
                "mobile data"
            else -> "internet"
        }
        val availableRamGb = memory.availMem.toDouble() / GIB
        val totalRamGb = memory.totalMem.toDouble() / GIB
        val freeStorageGb = storage.availableBytes.toDouble() / GIB
        return buildString {
            append("${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}. ")
            if (battery != null) append("Battery $battery%. ")
            append(
                "Available memory ${availableRamGb.oneDecimal()} of " +
                    "${totalRamGb.oneDecimal()} GB. "
            )
            append("Free app storage ${freeStorageGb.oneDecimal()} GB. ")
            append("Connection: $networkName.")
        }
    }

    private fun geocode(rawLocation: String): Place {
        val root = JSONObject(
            get(
                "https://geocoding-api.open-meteo.com/v1/search" +
                    "?name=${encode(rawLocation)}&count=1&language=en&format=json"
            )
        )
        val results = root.optJSONArray("results")
        check(results != null && results.length() > 0) {
            "I could not find that place."
        }
        val item = results.getJSONObject(0)
        val label = listOf(
            item.optString("name"),
            item.optString("admin1"),
            item.optString("country")
        ).filter(String::isNotBlank).distinct().joinToString(", ")
        return Place(
            name = label.ifBlank { rawLocation.take(120) },
            latitude = item.getDouble("latitude"),
            longitude = item.getDouble("longitude")
        )
    }

    private fun wikipediaSummary(query: String): String? {
        val url = "https://en.wikipedia.org/w/api.php" +
            "?action=query&generator=search&gsrsearch=${encode(query)}&gsrlimit=1" +
            "&prop=extracts&exintro=1&explaintext=1&format=json"
        val root = JSONObject(get(url))
        val pages = root.optJSONObject("query")?.optJSONObject("pages") ?: return null
        val keys = pages.keys()
        if (!keys.hasNext()) return null
        val page = pages.getJSONObject(keys.next())
        val title = page.optString("title").trim()
        val extract = page.optString("extract")
            .replace(Regex("""\s+"""), " ")
            .trim()
        if (extract.isBlank()) return null
        return "${title.ifBlank { query }}: ${shortenForSpeech(extract)}"
    }

    private fun duckDuckGoAnswer(query: String): String? {
        val root = JSONObject(
            get(
                "https://api.duckduckgo.com/" +
                    "?q=${encode(query)}&format=json&no_html=1&skip_disambig=0"
            )
        )
        val direct = listOf(
            root.optString("Answer"),
            root.optString("AbstractText"),
            root.optString("Definition")
        ).firstOrNull(String::isNotBlank)
        if (direct != null) return shortenForSpeech(direct)
        return firstRelatedText(root.optJSONArray("RelatedTopics"))
            ?.let(::shortenForSpeech)
    }

    private fun firstRelatedText(items: JSONArray?): String? {
        if (items == null) return null
        repeat(items.length()) { index ->
            val item = items.optJSONObject(index) ?: return@repeat
            item.optString("Text").takeIf(String::isNotBlank)?.let { return it }
            firstRelatedText(item.optJSONArray("Topics"))?.let { return it }
        }
        return null
    }

    private fun shortenForSpeech(value: String): String {
        val compact = value.replace(Regex("""\s+"""), " ").trim()
        if (compact.length <= MAX_KNOWLEDGE_CHARS) return compact
        val prefix = compact.take(MAX_KNOWLEDGE_CHARS)
        val sentenceEnd = prefix.lastIndexOf(". ")
        return if (sentenceEnd >= 300) prefix.take(sentenceEnd + 1) else "$prefix…"
    }

    private fun get(rawUrl: String): String {
        val connection = URL(rawUrl).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 12_000
            connection.setRequestProperty("User-Agent", "AURA-Butler-Android/0.12.1")
            connection.setRequestProperty("Accept", "application/json, application/rss+xml, text/xml")
            val status = connection.responseCode
            if (status !in 200..299) error("Live information returned HTTP $status")
            connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    check(total <= MAX_RESPONSE_BYTES) {
                        "Live information response was too large."
                    }
                    output.write(buffer, 0, count)
                }
                output.toString(StandardCharsets.UTF_8.name())
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun weatherDescription(code: Int): String = when (code) {
        0 -> "clear skies"
        1 -> "mainly clear"
        2 -> "partly cloudy"
        3 -> "overcast"
        45, 48 -> "fog"
        51, 53, 55, 56, 57 -> "drizzle"
        61, 63, 65, 66, 67 -> "rain"
        71, 73, 75, 77 -> "snow"
        80, 81, 82 -> "rain showers"
        85, 86 -> "snow showers"
        95, 96, 99 -> "thunderstorms"
        else -> "mixed conditions"
    }

    private fun Double.oneDecimal(): String = String.format(Locale.US, "%.1f", this)

    private data class Place(
        val name: String,
        val latitude: Double,
        val longitude: Double
    )

    private companion object {
        const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
        const val MAX_KNOWLEDGE_CHARS = 900
        const val GIB = 1024.0 * 1024.0 * 1024.0
    }
}
