package `in`.diganta.aurabutler.intelligence

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** OS-owned scheduling for proactive agents across process death and reboot. */
object AndroidProactiveScheduler {
    const val PERIODIC_WORK_NAME = "aura-proactive-agent-due-check-v1"
    const val INITIAL_WORK_NAME = "aura-proactive-agent-initial-run-v1"

    fun ensureScheduled(context: Context) {
        val appContext = context.applicationContext
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .build()
        val periodic = PeriodicWorkRequestBuilder<ProactiveAgentWorker>(
            MIN_WORK_INTERVAL_MINUTES,
            TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            periodic
        )
        val initial = OneTimeWorkRequestBuilder<ProactiveAgentWorker>()
            .setConstraints(constraints)
            .setInitialDelay(5, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            INITIAL_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            initial
        )
    }

    fun cancel(context: Context) {
        val workManager = WorkManager.getInstance(context.applicationContext)
        workManager.cancelUniqueWork(INITIAL_WORK_NAME)
        workManager.cancelUniqueWork(PERIODIC_WORK_NAME)
    }

    private const val MIN_WORK_INTERVAL_MINUTES = 15L
}
