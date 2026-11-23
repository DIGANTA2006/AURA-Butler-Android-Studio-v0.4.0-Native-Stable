package `in`.diganta.aurabutler.analyzer

import android.content.Context
import androidx.core.app.NotificationManagerCompat

object NotificationAccessState {
    fun isGranted(context: Context): Boolean =
        NotificationManagerCompat
            .getEnabledListenerPackages(context.applicationContext)
            .contains(context.packageName)
}
