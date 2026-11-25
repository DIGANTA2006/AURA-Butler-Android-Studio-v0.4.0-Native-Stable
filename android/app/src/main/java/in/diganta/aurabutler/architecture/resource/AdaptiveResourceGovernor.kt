package `in`.diganta.aurabutler.architecture.resource

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import kotlin.math.max
import kotlin.math.min

/**
 * Priority lanes for local device scheduling (Architecture v0.22.0 Mandatory Full Local Capability Directive).
 * Interactive voice and UI always preempt deferrable background indexing.
 */
enum class WorkPriority(val rank: Int) {
    INTERACTIVE_VOICE(0),
    INTERACTIVE_UI(1),
    STANDARD(2),
    BACKGROUND_INDEXING(3)
}

enum class ThermalLevel {
    NOMINAL,
    MODERATE,
    SEVERE,
    CRITICAL
}

data class DeviceCapabilitySnapshot(
    val totalRamMb: Long,
    val availableRamMb: Long,
    val isLowMemory: Boolean,
    val cpuCores: Int,
    val thermalLevel: ThermalLevel,
    val isPowerSaveMode: Boolean,
    val availableStorageMb: Long,
    val isForeground: Boolean
) {
    val availableRamRatio: Float
        get() = if (totalRamMb <= 0L) 0f else (availableRamMb.toFloat() / totalRamMb.toFloat()).coerceIn(0f, 1f)
}

data class LocalExecutionProfile(
    val maxConcurrentLocalTasks: Int,
    val maxStreamingChunkChars: Int,
    val allowBackgroundIndexing: Boolean,
    val allowHeavyLocalInference: Boolean,
    val throttleReason: String?
)

data class SchedulingDecision(
    val proceedImmediately: Boolean,
    val defer: Boolean,
    val reason: String,
    val profile: LocalExecutionProfile
)

/**
 * Hardware-aware local resource governor.
 * Never imposes per-day local request caps or hard response-character truncation limits.
 * Responds purely to real device memory, thermal, battery, CPU, and storage signals.
 */
class AdaptiveResourceGovernor {

    fun evaluate(snapshot: DeviceCapabilitySnapshot): LocalExecutionProfile {
        val cores = max(1, snapshot.cpuCores)
        val reasons = mutableListOf<String>()

        var concurrency = min(4, max(1, cores / 2))
        var allowBackground = true
        var allowHeavy = true
        var streamingChunkChars = 320

        if (snapshot.thermalLevel == ThermalLevel.CRITICAL) {
            concurrency = 1
            allowBackground = false
            allowHeavy = false
            reasons.add("Device thermal state is CRITICAL")
        } else if (snapshot.thermalLevel == ThermalLevel.SEVERE) {
            concurrency = 1
            allowBackground = false
            reasons.add("Device thermal state is SEVERE")
        }

        if (snapshot.isLowMemory || snapshot.availableRamMb < 256L || snapshot.availableRamRatio < 0.12f) {
            concurrency = 1
            allowBackground = false
            if (snapshot.availableRamMb < 160L) {
                allowHeavy = false
            }
            streamingChunkChars = 200
            reasons.add("Device RAM pressure (${snapshot.availableRamMb} MB available)")
        }

        if (snapshot.isPowerSaveMode && !snapshot.isForeground) {
            allowBackground = false
            concurrency = 1
            reasons.add("Battery saver active in background")
        }

        if (snapshot.availableStorageMb in 0L..149L) {
            allowBackground = false
            reasons.add("Low local storage (${snapshot.availableStorageMb} MB free)")
        }

        return LocalExecutionProfile(
            maxConcurrentLocalTasks = max(1, concurrency),
            maxStreamingChunkChars = streamingChunkChars,
            allowBackgroundIndexing = allowBackground,
            allowHeavyLocalInference = allowHeavy,
            throttleReason = if (reasons.isEmpty()) null else reasons.joinToString("; ")
        )
    }

    fun decide(priority: WorkPriority, snapshot: DeviceCapabilitySnapshot): SchedulingDecision {
        val profile = evaluate(snapshot)

        // Interactive voice & UI are never blocked by arbitrary caps; only critical thermal/OOM defers heavy work.
        if (priority == WorkPriority.INTERACTIVE_VOICE || priority == WorkPriority.INTERACTIVE_UI) {
            return SchedulingDecision(
                proceedImmediately = true,
                defer = false,
                reason = profile.throttleReason ?: "Full local capability available.",
                profile = profile
            )
        }

        if (priority == WorkPriority.BACKGROUND_INDEXING && !profile.allowBackgroundIndexing) {
            return SchedulingDecision(
                proceedImmediately = false,
                defer = true,
                reason = profile.throttleReason ?: "Background work deferred under device pressure.",
                profile = profile
            )
        }

        if (priority == WorkPriority.STANDARD && snapshot.thermalLevel == ThermalLevel.CRITICAL) {
            return SchedulingDecision(
                proceedImmediately = false,
                defer = true,
                reason = "Deferred due to critical thermal load.",
                profile = profile
            )
        }

        return SchedulingDecision(
            proceedImmediately = true,
            defer = false,
            reason = profile.throttleReason ?: "Scheduled normally within device capacity.",
            profile = profile
        )
    }
}

/**
 * Live Android device capability probe requiring zero dangerous runtime permissions.
 */
class AndroidDeviceCapabilityProbe(context: Context) {
    private val appContext = context.applicationContext

    fun capture(isForeground: Boolean = true): DeviceCapabilitySnapshot {
        val memInfo = ActivityManager.MemoryInfo()
        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        am?.getMemoryInfo(memInfo)

        val totalMb = memInfo.totalMem / (1024L * 1024L)
        val availMb = memInfo.availMem / (1024L * 1024L)

        val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val powerSave = pm?.isPowerSaveMode == true
        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && pm != null) {
            when (pm.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE,
                PowerManager.THERMAL_STATUS_LIGHT -> ThermalLevel.NOMINAL
                PowerManager.THERMAL_STATUS_MODERATE -> ThermalLevel.MODERATE
                PowerManager.THERMAL_STATUS_SEVERE -> ThermalLevel.SEVERE
                else -> ThermalLevel.CRITICAL
            }
        } else {
            ThermalLevel.NOMINAL
        }

        val freeStorageMb = runCatching {
            val stat = StatFs(appContext.filesDir.absolutePath)
            stat.availableBytes / (1024L * 1024L)
        }.getOrDefault(512L)

        return DeviceCapabilitySnapshot(
            totalRamMb = totalMb,
            availableRamMb = availMb,
            isLowMemory = memInfo.lowMemory,
            cpuCores = Runtime.getRuntime().availableProcessors(),
            thermalLevel = thermal,
            isPowerSaveMode = powerSave,
            availableStorageMb = freeStorageMb,
            isForeground = isForeground
        )
    }
}
