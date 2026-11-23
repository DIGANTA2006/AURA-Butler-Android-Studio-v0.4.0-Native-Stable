package `in`.diganta.aurabutler.analyzer

import java.util.UUID

enum class AnalyzerChannel {
    WHATSAPP,
    MAIL,
    SLACK,
    JIRA,
    MESSAGING,
    NEWS,
    SYSTEM,
    OTHER
}

enum class NotificationPriority(val weight: Int) {
    LOW(0),
    NORMAL(1),
    IMPORTANT(2),
    CRITICAL(3)
}

data class CapturedNotification(
    val id: String = UUID.randomUUID().toString(),
    val notificationKey: String,
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val subText: String,
    val sender: String,
    val channel: AnalyzerChannel,
    val priority: NotificationPriority,
    val postedAtEpochMs: Long,
    val capturedAtEpochMs: Long = System.currentTimeMillis(),
    val isConversation: Boolean = false,
    val isGroupConversation: Boolean = false
)

data class NotificationDigest(
    val text: String,
    val totalCount: Int,
    val importantCount: Int,
    val sourceCount: Int,
    val newestAtEpochMs: Long?
)

data class ResearchSource(
    val index: Int,
    val title: String,
    val url: String
)

data class ResearchResult(
    val answer: String,
    val sources: List<ResearchSource>,
    val queries: List<String> = emptyList()
) {
    fun formatted(): String = buildString {
        append(answer.trim())
        if (sources.isNotEmpty()) {
            append("\n\nSources:\n")
            sources.forEach { source ->
                append('[')
                append(source.index)
                append("] ")
                append(source.title.ifBlank { source.url })
                append(" — ")
                append(source.url)
                append('\n')
            }
        }
    }.trim()
}

data class NewsEntry(
    val title: String,
    val source: String,
    val publishedAtEpochMs: Long?,
    val url: String
)
