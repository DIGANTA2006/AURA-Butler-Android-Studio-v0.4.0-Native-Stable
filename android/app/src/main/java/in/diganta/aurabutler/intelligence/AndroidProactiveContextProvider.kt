package `in`.diganta.aurabutler.intelligence

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import android.os.StatFs
import `in`.diganta.aurabutler.model.ConversationContext
import kotlin.math.roundToInt

/** Read-only device state adapter; it never requests foreground-app or location access. */
class AndroidProactiveContextProvider(
    context: Context,
    private val contextSnapshot: () -> ConversationContext
) : ProactiveEngine.ContextProvider {
    private val appContext = context.applicationContext

    override fun getCurrentContext(): ConversationContext = contextSnapshot()

    override fun getDeviceState(): ProactiveEngine.DeviceState {
        val battery = appContext.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) {
            (level * 100f / scale).roundToInt().coerceIn(0, 100)
        } else {
            100
        }
        val status = battery?.getIntExtra(
            BatteryManager.EXTRA_STATUS,
            BatteryManager.BATTERY_STATUS_UNKNOWN
        ) ?: BatteryManager.BATTERY_STATUS_UNKNOWN
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
        val network = connectivity?.activeNetwork
        val capabilities = network?.let(connectivity::getNetworkCapabilities)
        val networkType = when {
            capabilities == null -> "offline"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "bluetooth"
            else -> "other"
        }

        val storage = StatFs(appContext.filesDir.absolutePath)
        val total = storage.totalBytes.coerceAtLeast(1L)
        val freePercent = (
            storage.availableBytes.toDouble() * 100.0 / total.toDouble()
        ).coerceIn(0.0, 100.0)

        val power = appContext.getSystemService(PowerManager::class.java)
        return ProactiveEngine.DeviceState(
            batteryLevel = percent,
            isCharging = charging,
            networkType = networkType,
            storageFreePercent = freePercent,
            screenOn = power?.isInteractive == true,
            foregroundApp = null
        )
    }
}
