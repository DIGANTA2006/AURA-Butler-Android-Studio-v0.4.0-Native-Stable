package `in`.diganta.aurabutler.analyzer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import `in`.diganta.aurabutler.MainActivity
import `in`.diganta.aurabutler.model.ButlerSettings

object ProactiveAlertManager {
    private const val CHANNEL_ID = "aura_important_analyzer"
    private const val CHANNEL_NAME = "AURA important analyzer"

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Private alerts for locally classified important notifications"
            }
        )
    }

    fun maybeAlert(
        context: Context,
        item: CapturedNotification,
        settings: ButlerSettings
    ) {
        if (!settings.proactiveAnalyzerAlerts) return
        if (item.priority.weight < NotificationPriority.IMPORTANT.weight) return

        createChannel(context)
        val openIntent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("aura_open_analyzer", true)
        val pending = PendingIntent.getActivity(
            context,
            7001,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val summary = when (item.priority) {
            NotificationPriority.CRITICAL ->
                "Critical notification from ${item.appName}. Open AURA for the private details."

            else ->
                "Important notification from ${item.appName}. Open AURA for the private summary."
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(`in`.diganta.aurabutler.R.drawable.ic_aura)
            .setContentTitle("AURA found something important")
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        context.getSystemService(NotificationManager::class.java)
            .notify((item.id.hashCode() and 0x7fffffff).coerceAtLeast(1), notification)
    }
}
