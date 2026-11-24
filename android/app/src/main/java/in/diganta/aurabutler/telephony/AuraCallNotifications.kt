package `in`.diganta.aurabutler.telephony

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat

internal object AuraCallNotifications {
    const val CHANNEL_ID = "aura_native_calls"
    private const val CHANNEL_NAME = "AURA phone calls"

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Incoming and ongoing calls managed by Android Telecom"
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
        )
    }

    fun show(context: Context, snapshot: AuraCallSnapshot, notificationId: Int) {
        createChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val activityIntent = Intent(context, AuraPhoneActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("aura://native-call/${snapshot.id}")
            putExtra(AuraPhoneActivity.EXTRA_CALL_ID, snapshot.id)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            notificationId,
            activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val publicVersion = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle("Phone call")
            .setContentText(if (snapshot.isRinging) "Incoming call" else snapshot.stateLabel)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .build()
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle(snapshot.phoneNumber ?: "Unknown caller")
            .setContentText(if (snapshot.isRinging) "Incoming call — tap to answer or decline" else snapshot.stateLabel)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(snapshot.state != android.telecom.Call.STATE_DISCONNECTED)
            .setAutoCancel(false)
            .setContentIntent(contentIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)

        if (snapshot.isRinging) {
            val fullScreenAllowed = Build.VERSION.SDK_INT < 34 ||
                manager.canUseFullScreenIntent()
            if (fullScreenAllowed) builder.setFullScreenIntent(contentIntent, true)
            builder.addAction(
                android.R.drawable.sym_action_call,
                "Answer",
                actionIntent(context, AuraCallActionReceiver.ACTION_ANSWER, snapshot.id)
            )
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Decline",
                actionIntent(context, AuraCallActionReceiver.ACTION_REJECT, snapshot.id)
            )
        } else if (snapshot.isActive || snapshot.isHolding) {
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "End call",
                actionIntent(context, AuraCallActionReceiver.ACTION_DISCONNECT, snapshot.id)
            )
        }
        manager.notify(notificationId, builder.build())
    }


    fun showVoipSummary(context: Context, record: AuraCallHistoryRecord) {
        val channelId = "aura_voip_call_reports"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(channelId, "AURA call reports", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Private summaries of AURA self-hosted AI calls"
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                }
            )
        }
        val activityIntent = Intent(context, AuraPhoneActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("aura://voip-report/${record.id}")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            context, 21_042, activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val generic = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle("AURA call report")
            .setContentText("A private AURA AI call report is available.")
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        val summary = record.summary?.takeIf { it.isNotBlank() }
        val hasTranscript = !record.transcript.isNullOrBlank()
        val detail = summary
            ?: if (hasTranscript) {
                "Your encrypted call transcript is available in AURA call history."
            } else {
                "No transcript or summary is available for this call."
            }
        val title = if (summary != null || hasTranscript) {
            "AURA AI call report"
        } else {
            "AURA AI call completed"
        }
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle(title)
            .setContentText(detail.take(180))
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setPublicVersion(generic)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java)?.notify(21_042, notification) }
    }

    fun cancel(context: Context, notificationId: Int) {
        context.getSystemService(NotificationManager::class.java)?.cancel(notificationId)
    }

    private fun actionIntent(context: Context, action: String, callId: String): PendingIntent {
        val intent = Intent(context, AuraCallActionReceiver::class.java).apply {
            this.action = action
            data = Uri.parse("aura://native-call/$callId/${action.substringAfterLast('.')}")
            putExtra(AuraCallActionReceiver.EXTRA_CALL_ID, callId)
        }
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
