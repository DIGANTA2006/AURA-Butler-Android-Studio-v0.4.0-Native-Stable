package `in`.diganta.aurabutler.domain

import `in`.diganta.aurabutler.model.ButlerSettings

interface LiveInfoProvider {
    suspend fun weather(settings: ButlerSettings): String
    suspend fun headlines(limit: Int = 5): List<String>

    suspend fun weatherFor(settings: ButlerSettings, location: String?): String =
        weather(settings)

    suspend fun weatherForecast(
        settings: ButlerSettings,
        location: String?,
        days: Int
    ): String = weatherFor(settings, location)

    suspend fun headlinesFor(limit: Int = 5, topic: String?): List<String> =
        headlines(limit)

    suspend fun knowledge(query: String): String? = null

    fun deviceStatus(): String =
        "Detailed phone status is unavailable on this device."
}
