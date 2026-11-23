package `in`.diganta.aurabutler.device

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import kotlin.math.roundToInt

data class DeviceHealthSnapshot(
    val batteryPercent: Int?,
    val batteryTemperatureC: Double?,
    val charging: Boolean,
    val thermalStatus: Int?,
    val powerSaveMode: Boolean
)

class DeviceHealthManager(context: Context) {
    private val appContext = context.applicationContext

    fun snapshot(): DeviceHealthSnapshot {
        val battery = appContext.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) {
            (level * 100.0 / scale).roundToInt().coerceIn(0, 100)
        } else {
            null
        }
        val rawTemperature = battery?.getIntExtra(
            BatteryManager.EXTRA_TEMPERATURE,
            Int.MIN_VALUE
        ) ?: Int.MIN_VALUE
        val temperature = rawTemperature
            .takeIf { it != Int.MIN_VALUE }
            ?.div(10.0)
        val status = battery?.getIntExtra(
            BatteryManager.EXTRA_STATUS,
            BatteryManager.BATTERY_STATUS_UNKNOWN
        ) ?: BatteryManager.BATTERY_STATUS_UNKNOWN
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val power = appContext.getSystemService(PowerManager::class.java)
        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            power.currentThermalStatus
        } else {
            null
        }
        return DeviceHealthSnapshot(
            batteryPercent = percent,
            batteryTemperatureC = temperature,
            charging = charging,
            thermalStatus = thermal,
            powerSaveMode = power.isPowerSaveMode
        )
    }

    fun report(): String {
        val state = snapshot()
        return buildString {
            append("Device health: ")
            state.batteryPercent?.let { append("battery $it%, ") }
            state.batteryTemperatureC?.let {
                append("battery sensor ${"%.1f".format(it)}°C, ")
            }
            append("thermal state ${thermalLabel(state.thermalStatus)}, ")
            append(if (state.charging) "charging, " else "not charging, ")
            append(if (state.powerSaveMode) "Battery Saver is on." else "Battery Saver is off.")
            append(" Android and the phone hardware control the actual cooling system.")
        }
    }

    fun applyCoolingAssist(): String {
        val before = snapshot()
        var brightnessChanged = false
        if (Settings.System.canWrite(appContext)) {
            val resolver = appContext.contentResolver
            val current = runCatching {
                Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS)
            }.getOrDefault(128)
            val target = minOf(current, 64).coerceAtLeast(8)
            brightnessChanged = Settings.System.putInt(
                resolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            ) && Settings.System.putInt(
                resolver,
                Settings.System.SCREEN_BRIGHTNESS,
                target
            )
        }

        return buildString {
            append("Cooling assist prepared. ")
            if (brightnessChanged) {
                append("Screen brightness was reduced to lower power use. ")
            } else {
                append("AURA could not change brightness without Android system-settings permission. ")
            }
            append("Battery Saver settings were opened. ")
            before.batteryTemperatureC?.let {
                append("The battery sensor reported ${"%.1f".format(it)}°C. ")
            }
            append("For real cooling, unplug charging, remove a thick case and stop heavy games or camera use.")
        }
    }

    private fun thermalLabel(status: Int?): String = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "normal"
        PowerManager.THERMAL_STATUS_LIGHT -> "light throttling"
        PowerManager.THERMAL_STATUS_MODERATE -> "moderate throttling"
        PowerManager.THERMAL_STATUS_SEVERE -> "severe throttling"
        PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown risk"
        null -> "not available on this Android version"
        else -> "unknown"
    }
}
