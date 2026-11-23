package `in`.diganta.aurabutler.analyzer

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import `in`.diganta.aurabutler.data.SecureStore

class AuraNotificationListenerService : NotificationListenerService() {
    private val vault by lazy(LazyThreadSafetyMode.NONE) {
        NotificationVault(applicationContext)
    }
    private val store by lazy(LazyThreadSafetyMode.NONE) {
        SecureStore(applicationContext)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "Notification listener connected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val posted = sbn ?: return
        if (posted.packageName == packageName) return

        val settings = store.loadSettings()
        if (!settings.analyzerEnabled || !settings.notificationCaptureEnabled) return

        val notification = posted.notification ?: return
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        if ((notification.flags and Notification.FLAG_ONGOING_EVENT) != 0) return

        val appName = runCatching {
            val info = packageManager.getApplicationInfo(posted.packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(posted.packageName)
        val channel = NotificationClassifier.channelFor(posted.packageName, appName)

        val messagingStyle = NotificationCompat.MessagingStyle
            .extractMessagingStyleFromNotification(notification)
        val styleMessages = messagingStyle
            ?.let { style -> (style.historicMessages + style.messages) }
            .orEmpty()
            .distinctBy { message ->
                "${message.timestamp}|${message.person?.name}|${message.text}"
            }
            .takeLast(MAX_MESSAGES_PER_NOTIFICATION)

        if (styleMessages.isNotEmpty() && channel in CONVERSATION_CHANNELS) {
            val conversationTitle = messagingStyle?.conversationTitle
                ?.toString()
                .orEmpty()
                .trim()
            val isGroup = messagingStyle?.isGroupConversation == true ||
                conversationTitle.isNotBlank()

            styleMessages.forEachIndexed { index, message ->
                val body = message.text?.toString().orEmpty().trim()
                if (body.isBlank()) return@forEachIndexed
                val sender = message.person?.name?.toString()
                    .orEmpty()
                    .trim()
                    .ifBlank { "Unknown sender" }
                val title = if (isGroup) conversationTitle.ifBlank { appName } else sender
                capture(
                    posted = posted,
                    appName = appName,
                    title = title,
                    text = body,
                    subText = conversationTitle,
                    sender = sender,
                    channel = channel,
                    isConversation = true,
                    isGroupConversation = isGroup,
                    notificationKey = "${posted.key}:${message.timestamp}:$index",
                    postedAt = message.timestamp.takeIf { it > 0L } ?: posted.postTime,
                    settings = settings
                )
            }
            return
        }

        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)
            ?.toString().orEmpty().trim()
        val text = listOfNotNull(
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString()
        ).map(String::trim).firstOrNull(String::isNotBlank).orEmpty()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)
            ?.toString().orEmpty().trim()
        if (title.isBlank() && text.isBlank() && subText.isBlank()) return

        val isConversation = channel in CONVERSATION_CHANNELS || notification.shortcutId != null
        capture(
            posted = posted,
            appName = appName,
            title = title,
            text = text,
            subText = subText,
            sender = NotificationClassifier.senderFrom(title, subText),
            channel = channel,
            isConversation = isConversation,
            isGroupConversation = notification.group != null && isConversation,
            notificationKey = posted.key,
            postedAt = posted.postTime,
            settings = settings
        )
    }

    private fun capture(
        posted: StatusBarNotification,
        appName: String,
        title: String,
        text: String,
        subText: String,
        sender: String,
        channel: AnalyzerChannel,
        isConversation: Boolean,
        isGroupConversation: Boolean,
        notificationKey: String,
        postedAt: Long,
        settings: `in`.diganta.aurabutler.model.ButlerSettings
    ) {
        val priority = NotificationClassifier.priorityFor(
            title = title,
            text = text,
            subText = subText,
            channel = channel
        )
        val captured = CapturedNotification(
            notificationKey = notificationKey,
            packageName = posted.packageName,
            appName = appName,
            title = title,
            text = text,
            subText = subText,
            sender = sender,
            channel = channel,
            priority = priority,
            postedAtEpochMs = postedAt,
            isConversation = isConversation,
            isGroupConversation = isGroupConversation
        )
        val inserted = runCatching {
            vault.add(captured, settings.notificationRetentionDays)
        }.getOrElse { error ->
            Log.e(TAG, "Notification vault write failed", error)
            false
        }
        if (inserted) {
            Log.i(
                TAG,
                "Captured app=${appName.take(60)}, channel=$channel, " +
                    "priority=$priority, group=$isGroupConversation"
            )
            ProactiveAlertManager.maybeAlert(
                context = applicationContext,
                item = captured,
                settings = settings
            )
            ImportantMessageSentinel.maybeSpeak(
                context = applicationContext,
                item = captured,
                settings = settings
            )
        }
    }

    private companion object {
        const val TAG = "AuraNotificationListener"
        const val MAX_MESSAGES_PER_NOTIFICATION = 20
        val CONVERSATION_CHANNELS = setOf(
            AnalyzerChannel.WHATSAPP,
            AnalyzerChannel.MESSAGING,
            AnalyzerChannel.SLACK
        )
    }
}
