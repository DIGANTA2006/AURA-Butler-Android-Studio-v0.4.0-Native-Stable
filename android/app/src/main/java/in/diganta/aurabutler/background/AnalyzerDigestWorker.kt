package `in`.diganta.aurabutler.background

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import `in`.diganta.aurabutler.MainActivity
import `in`.diganta.aurabutler.R
import `in`.diganta.aurabutler.analyzer.NotificationAnalyzer
import `in`.diganta.aurabutler.data.SecureStore
import java.util.concurrent.TimeUnit

class AnalyzerDigestWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val store = SecureStore(applicationContext)
        val settings = store.loadSettings()
        if (
            !settings.analyzerEnabled ||
            !settings.scheduledAnalyzerDigests
        ) {
            return Result.success()
        }

        val analyzer = NotificationAnalyzer(applicationContext)
        analyzer.prune(settings.notificationRetentionDays)
        val digest = analyzer.summarize(
            settings = settings,
            importantOnly = true,
            sinceEpochMs = System.currentTimeMillis() - DIGEST_WINDOW_MS,
            detailed = false,
            limit = 80
        )

        if (digest.totalCount == 0) return Result.success()
        createChannel(applicationContext)

        val openIntent = Intent(applicationContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("aura_open_analyzer", true)
        val pending = PendingIntent.getActivity(
            applicationContext,
            7100,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val compact = buildString {
            append(digest.importantCount)
            append(" important notification")
            if (digest.importantCount != 1) append('s')
            append(" from ")
            append(digest.sourceCount)
            append(" source")
            if (digest.sourceCount != 1) append('s')
            append(". Open AURA for the private digest.")
        }

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_aura)
            .setContentTitle("AURA notification digest")
            .setContentText(compact)
            .setStyle(NotificationCompat.BigTextStyle().bigText(compact))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        applicationContext.getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification)
        return Result.success()
    }

    companion object {
        private const val UNIQUE_WORK = "aura_analyzer_digest_v1"
        private const val CHANNEL_ID = "aura_analyzer_digest"
        private const val CHANNEL_NAME = "AURA private digests"
        private const val NOTIFICATION_ID = 7101
        private const val DIGEST_WINDOW_MS = 12 * 60 * 60 * 1_000L

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<AnalyzerDigestWorker>(
                12,
                TimeUnit.HOURS
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(
                    UNIQUE_WORK,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    request
                )
        }

        fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        CHANNEL_NAME,
                        NotificationManager.IMPORTANCE_LOW
                    ).apply {
                        description = "Counts-only notification and message digests from AURA"
                    }
                )
        }
    }
}
