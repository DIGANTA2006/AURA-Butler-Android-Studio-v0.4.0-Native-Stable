package `in`.diganta.aurabutler.analyzer

import android.app.KeyguardManager
import android.content.Context
import `in`.diganta.aurabutler.data.GeminiAnalyzerProvider
import `in`.diganta.aurabutler.data.HybridAiProvider
import `in`.diganta.aurabutler.model.ButlerSettings
import `in`.diganta.aurabutler.runtime.SecretRedactor
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

class NotificationAnalyzer(
    context: Context,
    private val vault: NotificationVault = NotificationVault(context),
    private val gemini: GeminiAnalyzerProvider = GeminiAnalyzerProvider()
) {
    private val appContext = context.applicationContext

    suspend fun summarize(
        settings: ButlerSettings,
        channel: AnalyzerChannel? = null,
        importantOnly: Boolean = false,
        sinceEpochMs: Long = startOfToday(),
        detailed: Boolean = false,
        limit: Int = 120,
        conversationQuery: String? = null
    ): NotificationDigest {
        val minimum = if (importantOnly) NotificationPriority.IMPORTANT else null
        val rawItems = vault.list(
            channel = channel,
            minimumPriority = minimum,
            sinceEpochMs = sinceEpochMs,
            limit = limit
        )
        val query = conversationQuery?.trim()?.lowercase(Locale.ROOT)
            ?.takeIf(String::isNotBlank)
        val items = if (query == null) {
            rawItems
        } else {
            rawItems.filter { item ->
                listOf(item.title, item.subText, item.sender, item.text)
                    .joinToString(" ")
                    .lowercase(Locale.ROOT)
                    .contains(query)
            }
        }

        if (items.isEmpty()) {
            return NotificationDigest(
                text = if (conversationQuery.isNullOrBlank()) {
                    emptyMessage(channel, importantOnly)
                } else {
                    "I found no matching ${channelLabel(channel)} messages for “${conversationQuery.take(120)}” in the selected period."
                },
                totalCount = 0,
                importantCount = 0,
                sourceCount = 0,
                newestAtEpochMs = null
            )
        }

        val important = items.count {
            it.priority.weight >= NotificationPriority.IMPORTANT.weight
        }
        val sourceCount = items.map(CapturedNotification::appName).distinct().size
        val locked = appContext
            .getSystemService(KeyguardManager::class.java)
            .isDeviceLocked

        val local = localSummary(
            items = items,
            channel = channel,
            importantOnly = importantOnly,
            locked = locked,
            detailed = detailed
        )

        val cloudText = if (
            detailed &&
            !locked &&
            settings.cloudAnalyzerSummaries &&
            settings.aiEnabled &&
            settings.geminiApiKey.isNotBlank() &&
            HybridAiProvider.publicProviderAllowed(
                settings,
                HybridAiProvider.PROVIDER_GEMINI
            )
        ) {
            runCatching {
                val cloudItems = items.map { item ->
                    GeminiAnalyzerProvider.NotificationItem(
                        appName = item.appName,
                        content = buildString {
                            item.title.takeIf(String::isNotBlank)?.let { append("Title: ").append(it).append('\n') }
                            item.sender.takeIf(String::isNotBlank)?.let { append("Sender: ").append(it).append('\n') }
                            item.subText.takeIf(String::isNotBlank)?.let { append("Conversation: ").append(it).append('\n') }
                            append("Message: ").append(item.text)
                        }.take(2_500),
                        channel = item.channel.name.lowercase(Locale.ROOT),
                        timestamp = item.postedAtEpochMs
                    )
                }
                gemini.analyzeNotifications(
                    items = cloudItems,
                    settings = settings,
                    channelLabel = channel?.name?.lowercase(Locale.ROOT) ?: "all apps"
                )
            }.getOrNull()?.takeIf(String::isNotBlank)
        } else {
            null
        }

        return NotificationDigest(
            text = cloudText ?: local,
            totalCount = items.size,
            importantCount = important,
            sourceCount = sourceCount,
            newestAtEpochMs = items.maxOfOrNull(CapturedNotification::postedAtEpochMs)
        )
    }

    fun clear(): Int = vault.clear()

    fun count(): Int = vault.count()

    fun prune(retentionDays: Int): Int = vault.prune(retentionDays)

    private fun localSummary(
        items: List<CapturedNotification>,
        channel: AnalyzerChannel?,
        importantOnly: Boolean,
        locked: Boolean,
        detailed: Boolean
    ): String {
        val channelLabel = channelLabel(channel)
        val important = items.filter {
            it.priority.weight >= NotificationPriority.IMPORTANT.weight
        }

        if (locked) {
            val critical = items.count { it.priority == NotificationPriority.CRITICAL }
            return buildString {
                append("Your phone is locked, so I will keep private content hidden. ")
                append("I found ${items.size} $channelLabel notification")
                if (items.size != 1) append('s')
                append(" from ${items.map { it.appName }.distinct().size} source")
                if (items.map { it.appName }.distinct().size != 1) append('s')
                append(". ")
                append("${important.size} are important")
                if (critical > 0) append(" and $critical are critical")
                append(". Unlock the phone and ask me again for the details.")
            }
        }

        val grouped = items.groupBy { item ->
            val conversation = if (item.isGroupConversation) {
                item.subText.ifBlank { item.title }.ifBlank { "Group conversation" }
            } else {
                item.sender.ifBlank { item.title }.ifBlank { item.appName }
            }
            "${item.appName}|$conversation"
        }
        val sortedGroups = grouped.entries.sortedWith(
            compareByDescending<Map.Entry<String, List<CapturedNotification>>> {
                it.value.maxOfOrNull { item -> item.priority.weight } ?: 0
            }.thenByDescending {
                it.value.maxOfOrNull(CapturedNotification::postedAtEpochMs) ?: 0L
            }
        )
        val displayGroups = sortedGroups.take(if (detailed) 12 else 6)

        return buildString {
            append("I found ${items.size} ")
            if (importantOnly) append("important ")
            append(channelLabel)
            append(" notification")
            if (items.size != 1) append('s')
            append(". ${important.size} are important.\n")

            displayGroups.forEachIndexed { index, entry ->
                val groupItems = entry.value.sortedByDescending {
                    it.postedAtEpochMs
                }
                val newest = groupItems.first()
                val highest = groupItems.maxByOrNull { it.priority.weight } ?: newest
                append(index + 1)
                append(". ")
                append(highest.appName)
                append(" — ")
                val conversationLabel = if (highest.isGroupConversation) {
                    highest.subText.ifBlank { highest.title }.ifBlank { "Group conversation" }
                } else {
                    highest.sender.ifBlank { highest.title.ifBlank { "Update" } }
                }
                append(conversationLabel)
                if (groupItems.size > 1) {
                    append(" (${groupItems.size} notifications)")
                }
                append(" [")
                append(highest.priority.name.lowercase(Locale.ROOT))
                append("]\n")

                val selected = if (detailed) groupItems.take(3) else listOf(highest)
                selected.forEach { item ->
                    val body = item.text
                        .ifBlank { item.subText }
                        .ifBlank { item.title }
                        .replace(Regex("""\s+"""), " ")
                        .trim()
                    append("   • ")
                    if (item.isGroupConversation && item.sender.isNotBlank()) {
                        append(SecretRedactor.redact(item.sender, 80))
                        append(": ")
                    }
                    append(SecretRedactor.redact(body, if (detailed) 420 else 220))
                    append(" — ")
                    append(formatTime(item.postedAtEpochMs))
                    append('\n')
                }
            }

            if (sortedGroups.size > displayGroups.size) {
                append("There are ${sortedGroups.size - displayGroups.size} more conversation or app groups. ")
                append("Ask for a detailed summary to include more.")
            }
        }.trim()
    }

    private fun emptyMessage(
        channel: AnalyzerChannel?,
        importantOnly: Boolean
    ): String = buildString {
        append("I found no ")
        if (importantOnly) append("important ")
        append(channelLabel(channel))
        append(" notifications in the selected period.")
    }

    private fun channelLabel(channel: AnalyzerChannel?): String = when (channel) {
        AnalyzerChannel.WHATSAPP -> "WhatsApp"
        AnalyzerChannel.MAIL -> "mail"
        AnalyzerChannel.SLACK -> "Slack"
        AnalyzerChannel.JIRA -> "Jira"
        AnalyzerChannel.MESSAGING -> "message"
        AnalyzerChannel.NEWS -> "news-app"
        AnalyzerChannel.SYSTEM -> "system"
        AnalyzerChannel.OTHER -> "other-app"
        null -> "recent"
    }

    private fun formatTime(epochMs: Long): String = runCatching {
        DateTimeFormatter
            .ofLocalizedDateTime(FormatStyle.SHORT, FormatStyle.SHORT)
            .withLocale(Locale.getDefault())
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(epochMs))
    }.getOrDefault("unknown time")

    private fun startOfToday(): Long = java.time.ZonedDateTime
        .now()
        .toLocalDate()
        .atStartOfDay(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()
}
