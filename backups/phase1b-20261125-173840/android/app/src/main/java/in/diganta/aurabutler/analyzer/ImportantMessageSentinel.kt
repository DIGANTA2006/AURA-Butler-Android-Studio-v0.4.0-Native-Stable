package `in`.diganta.aurabutler.analyzer

import android.app.KeyguardManager
import android.content.Context
import android.media.AudioManager
import android.util.Log
import `in`.diganta.aurabutler.background.WakeWordService
import `in`.diganta.aurabutler.model.ButlerSettings
import `in`.diganta.aurabutler.runtime.SecretRedactor
import java.util.concurrent.Executors
import kotlin.math.roundToLong

/**
 * Proactive local attention layer for messages AURA classifies as important.
 *
 * No cloud model is required. Notification text is already captured by Android's
 * user-granted Notification Access, classified locally, and passed here only
 * after NotificationVault deduplication. On a locked phone the default is to
 * announce sender/app only, keeping private message content off the speaker.
 */
object ImportantMessageSentinel {
    private const val TAG = "AuraImportantSentinel"
    private const val MAX_BODY_CHARS = 220
    private const val MIN_QUEUE_GAP_MS = 900L
    private const val ALERT_TTL_MS = 60_000L
    private const val DEDUP_WINDOW_MS = 90_000L
    private const val MAX_QUEUE_SIZE = 4

    private data class QueuedAlert(
        val context: Context,
        val speech: String,
        val key: String,
        val enqueuedAtEpochMs: Long
    )

    private val queueLock = Any()
    private val pending = ArrayDeque<QueuedAlert>()
    private val recentlyQueued = LinkedHashMap<String, Long>()
    private var workerScheduled = false

    private val speakerScheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "aura-important-message-speaker").apply { isDaemon = true }
    }

    fun maybeSpeak(
        context: Context,
        item: CapturedNotification,
        settings: ButlerSettings
    ) {
        if (!settings.importantMessageVoiceAlerts) return
        if (item.priority.weight < NotificationPriority.IMPORTANT.weight) return
        if (
            item.priority != NotificationPriority.CRITICAL &&
            item.channel !in SPEAKABLE_CHANNELS
        ) return

        val audioManager = context.getSystemService(AudioManager::class.java)
        if (audioManager.mode != AudioManager.MODE_NORMAL) {
            Log.i(TAG, "Important message kept visual-only because Android audio mode=${audioManager.mode}")
            return
        }

        val locked = context.getSystemService(KeyguardManager::class.java).isDeviceLocked
        val speech = buildSpeech(item, settings, locked)
        if (speech.isBlank()) return

        val now = System.currentTimeMillis()
        val key = buildString {
            append(item.packageName)
            append('|')
            append(item.sender)
            append('|')
            append(item.title)
            append('|')
            append(item.text)
        }.hashCode().toString()

        synchronized(queueLock) {
            pruneLocked(now)
            val previous = recentlyQueued[key]
            if (previous != null && now - previous < DEDUP_WINDOW_MS) {
                Log.i(TAG, "Suppressed duplicate important-message speech")
                return
            }

            recentlyQueued[key] = now
            while (pending.size >= MAX_QUEUE_SIZE && pending.isNotEmpty()) {
                pending.removeFirst()
            }
            pending.addLast(
                QueuedAlert(
                    context = context.applicationContext,
                    speech = speech,
                    key = key,
                    enqueuedAtEpochMs = now
                )
            )
            if (!workerScheduled) {
                workerScheduled = true
                speakerScheduler.execute(::speakNext)
            }
        }
    }

    private fun speakNext() {
        val alert = synchronized(queueLock) {
            val now = System.currentTimeMillis()
            pruneLocked(now)
            var next: QueuedAlert? = null
            while (pending.isNotEmpty() && next == null) {
                val candidate = pending.removeFirst()
                if (now - candidate.enqueuedAtEpochMs <= ALERT_TTL_MS) {
                    next = candidate
                }
            }
            if (next == null) workerScheduled = false
            next
        } ?: return

        runCatching {
            WakeWordService.speak(alert.context, alert.speech)
        }.onFailure { error ->
            Log.w(TAG, "Could not speak proactive important-message alert", error)
        }

        val estimatedSpeechMs = ((alert.speech.length / 13.0) * 1_000.0)
            .roundToLong()
            .coerceIn(1_500L, 18_000L)

        speakerScheduler.schedule(
            { speakNext() },
            estimatedSpeechMs + MIN_QUEUE_GAP_MS,
            java.util.concurrent.TimeUnit.MILLISECONDS
        )
    }

    private fun pruneLocked(nowEpochMs: Long) {
        while (
            pending.firstOrNull()?.let {
                nowEpochMs - it.enqueuedAtEpochMs > ALERT_TTL_MS
            } == true
        ) {
            pending.removeFirst()
        }

        val iterator = recentlyQueued.entries.iterator()
        while (iterator.hasNext()) {
            if (nowEpochMs - iterator.next().value > DEDUP_WINDOW_MS) {
                iterator.remove()
            }
        }
    }

    private fun buildSpeech(
        item: CapturedNotification,
        settings: ButlerSettings,
        locked: Boolean
    ): String {
        val app = item.appName.ifBlank { channelLabel(item.channel) }.take(60)
        val sender = item.sender
            .ifBlank { item.title }
            .ifBlank { "a sender" }
            .replace(Regex("""\s+"""), " ")
            .trim()
            .take(80)

        val urgency = if (item.priority == NotificationPriority.CRITICAL) {
            "Critical"
        } else {
            "Important"
        }

        if (locked && !settings.importantMessageSpeakDetailsWhenLocked) {
            return "$urgency $app message from $sender. Check your phone when you can."
        }

        val body = item.text
            .ifBlank { item.subText }
            .ifBlank { item.title }
            .replace(Regex("""\s+"""), " ")
            .trim()
            .let { SecretRedactor.redact(it, MAX_BODY_CHARS) }
            .take(MAX_BODY_CHARS)

        return if (body.isBlank()) {
            "$urgency $app message from $sender. Check your phone."
        } else {
            "$urgency $app message from $sender: $body"
        }
    }

    private fun channelLabel(channel: AnalyzerChannel): String = when (channel) {
        AnalyzerChannel.WHATSAPP -> "WhatsApp"
        AnalyzerChannel.MAIL -> "mail"
        AnalyzerChannel.SLACK -> "Slack"
        AnalyzerChannel.JIRA -> "Jira"
        AnalyzerChannel.MESSAGING -> "message"
        AnalyzerChannel.NEWS -> "news"
        AnalyzerChannel.SYSTEM -> "system"
        AnalyzerChannel.OTHER -> "app"
    }

    private val SPEAKABLE_CHANNELS = setOf(
        AnalyzerChannel.WHATSAPP,
        AnalyzerChannel.MAIL,
        AnalyzerChannel.SLACK,
        AnalyzerChannel.JIRA,
        AnalyzerChannel.MESSAGING
    )
}
