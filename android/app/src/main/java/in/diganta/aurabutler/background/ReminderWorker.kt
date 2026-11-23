package `in`.diganta.aurabutler.background

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import `in`.diganta.aurabutler.R
import `in`.diganta.aurabutler.model.ReminderItem
import java.util.concurrent.TimeUnit

class ReminderWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val title = inputData.getString(KEY_TITLE) ?: return Result.failure()
        val reminderId = inputData.getString(KEY_ID) ?: return Result.failure()

        if (
            android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return Result.failure()
        }

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_aura)
            .setContentTitle("AURA reminder")
            .setContentText(title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(title))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(applicationContext)
            .notify(reminderId.hashCode(), notification)
        return Result.success()
    }

    companion object {
        const val CHANNEL_ID = "aura_reminders"
        private const val KEY_TITLE = "title"
        private const val KEY_ID = "id"

        fun createChannel(context: Context) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AURA reminders",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Private reminders created with AURA Butler"
            }
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }

        fun schedule(context: Context, reminder: ReminderItem) {
            val delayMs = (reminder.triggerAtEpochMs - System.currentTimeMillis())
                .coerceAtLeast(1_000)
            val data = Data.Builder()
                .putString(KEY_TITLE, reminder.title)
                .putString(KEY_ID, reminder.id)
                .build()
            val work = OneTimeWorkRequestBuilder<ReminderWorker>()
                .setInputData(data)
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .addTag("aura-reminder-${reminder.id}")
                .build()
            WorkManager.getInstance(context).enqueue(work)
        }

        fun cancel(context: Context, reminderId: String) {
            WorkManager.getInstance(context)
                .cancelAllWorkByTag("aura-reminder-$reminderId")
        }
    }
}

