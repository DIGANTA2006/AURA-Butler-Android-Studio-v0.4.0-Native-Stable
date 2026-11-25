package `in`.diganta.aurabutler.maintenance

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

/**
 * Local review of installed apps and sensitive access. It reads package metadata
 * and the system's accessibility and notification-listener settings. It does not
 * inspect app contents and is not an antivirus scan.
 */
object DeviceSecurityAudit {
    data class AppFinding(
        val appName: String,
        val packageName: String,
        val riskyPermissions: List<String>,
        val sideloaded: Boolean
    ) {
        val summary: String
            get() {
                val parts = mutableListOf<String>()
                if (riskyPermissions.isNotEmpty()) parts += riskyPermissions.joinToString(", ")
                if (sideloaded) parts += "installed outside the Play Store"
                return parts.joinToString("; ")
            }
    }

    data class Report(
        val appsScanned: Int,
        val findings: List<AppFinding>,
        val accessibilityServices: List<String>,
        val notificationListeners: List<String>
    )

    private val riskyPermissions = linkedMapOf(
        "android.permission.READ_SMS" to "read SMS",
        "android.permission.RECEIVE_SMS" to "receive SMS",
        "android.permission.SEND_SMS" to "send SMS",
        "android.permission.READ_CALL_LOG" to "call log",
        "android.permission.SYSTEM_ALERT_WINDOW" to "draw over other apps",
        "android.permission.REQUEST_INSTALL_PACKAGES" to "install other apps",
        "android.permission.RECORD_AUDIO" to "microphone",
        "android.permission.CAMERA" to "camera",
        "android.permission.READ_CONTACTS" to "contacts",
        "android.permission.ACCESS_FINE_LOCATION" to "precise location",
        "android.permission.PACKAGE_USAGE_STATS" to "app usage history"
    )

    private val trustedInstallers = setOf(
        "com.android.vending",
        "com.amazon.venezia",
        "com.sec.android.app.samsungapps"
    )

    fun scan(context: Context): Report {
        val pm = context.packageManager
        val self = context.packageName
        val apps = pm.getInstalledApplications(0).filter { app ->
            app.packageName != self &&
                (app.flags and ApplicationInfo.FLAG_SYSTEM) == 0 &&
                (app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
        }
        val findings = apps
            .mapNotNull { app -> findingFor(pm, app) }
            .sortedByDescending { it.riskyPermissions.size + if (it.sideloaded) 1 else 0 }
        return Report(
            appsScanned = apps.size,
            findings = findings,
            accessibilityServices = enabledServices(context, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
            notificationListeners = enabledServices(context, Settings.Secure.ENABLED_NOTIFICATION_LISTENERS)
        )
    }

    @Suppress("DEPRECATION")
    private fun findingFor(pm: PackageManager, app: ApplicationInfo): AppFinding? {
        val info: PackageInfo = runCatching {
            pm.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS)
        }.getOrNull() ?: return null
        val requested: Array<String> = info.requestedPermissions ?: return null
        val flags: IntArray = info.requestedPermissionsFlags ?: IntArray(0)

        val granted = mutableListOf<String>()
        requested.forEachIndexed { index, permission ->
            val label = riskyPermissions[permission] ?: return@forEachIndexed
            val isGranted = index < flags.size &&
                (flags[index] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
            if (isGranted) granted += label
        }

        val installer = installerOf(pm, app.packageName)
        val sideloaded = installer == null || installer !in trustedInstallers
        if (granted.isEmpty() && !sideloaded) return null

        val label = pm.getApplicationLabel(app).toString()
        return AppFinding(
            appName = label,
            packageName = app.packageName,
            riskyPermissions = granted,
            sideloaded = sideloaded
        )
    }

    @Suppress("DEPRECATION")
    private fun installerOf(pm: PackageManager, packageName: String): String? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pm.getInstallSourceInfo(packageName).installingPackageName
        } else {
            pm.getInstallerPackageName(packageName)
        }
    }.getOrNull()

    private fun enabledServices(context: Context, key: String): List<String> {
        val raw = Settings.Secure.getString(context.contentResolver, key).orEmpty()
        return raw.split(':')
            .map { it.substringBefore('/').trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .map { pkg -> if (pkg == context.packageName) "$pkg (AURA)" else pkg }
    }
}