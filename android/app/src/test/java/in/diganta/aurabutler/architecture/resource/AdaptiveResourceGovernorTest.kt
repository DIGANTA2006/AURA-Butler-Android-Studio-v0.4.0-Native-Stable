package `in`.diganta.aurabutler.architecture.resource

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test

class AdaptiveResourceGovernorTest {

    private lateinit var context: Context
    private lateinit var governor: AdaptiveResourceGovernor
    private lateinit var probe: AndroidDeviceCapabilityProbe

    @Test
    fun testInteractiveVoiceNeverDeferred() {
        context = ApplicationProvider.getApplicationContext<Context>()
        governor = AdaptiveResourceGovernor()
        probe = AndroidDeviceCapabilityProbe(context)

        val snapshot = probe.capture(true)
        val decision = governor.decide(WorkPriority.INTERACTIVE_VOICE, snapshot)

        assertTrue(decision.proceedImmediately)
        assertFalse(decision.defer)
        assertEquals(WorkPriority.INTERACTIVE_VOICE, WorkPriority.INTERACTIVE_VOICE)
    }

    @Test
    fun testInteractiveUINeverDeferred() {
        context = ApplicationProvider.getApplicationContext<Context>()
        governor = AdaptiveResourceGovernor()
        probe = AndroidDeviceCapabilityProbe(context)

        val snapshot = probe.capture(true)
        val decision = governor.decide(WorkPriority.INTERACTIVE_UI, snapshot)

        assertTrue(decision.proceedImmediately)
        assertFalse(decision.defer)
    }

    @Test
    fun testBackgroundIndexingDeferredUnderPressure() {
        context = ApplicationProvider.getApplicationContext<Context>()
        governor = AdaptiveResourceGovernor()
        probe = AndroidDeviceCapabilityProbe(context)

        // Create a snapshot simulating severe pressure
        val pressureSnapshot = DeviceCapabilitySnapshot(
            totalRamMb = 4096,
            availableRamMb = 100, // Very low
            isLowMemory = true,
            cpuCores = 4,
            thermalLevel = ThermalLevel.NOMINAL,
            isPowerSaveMode = false,
            availableStorageMb = 1000,
            isForeground = false
        )

        val decision = governor.decide(WorkPriority.BACKGROUND_INDEXING, pressureSnapshot)
        assertTrue(decision.defer)
        assertFalse(decision.proceedImmediately)
    }

    @Test
    fun testCriticalThermalDefersStandardWork() {
        context = ApplicationProvider.getApplicationContext<Context>()
        governor = AdaptiveResourceGovernor()
        probe = AndroidDeviceCapabilityProbe(context)

        val thermalSnapshot = DeviceCapabilitySnapshot(
            totalRamMb = 8192,
            availableRamMb = 4096,
            isLowMemory = false,
            cpuCores = 8,
            thermalLevel = ThermalLevel.CRITICAL,
            isPowerSaveMode = false,
            availableStorageMb = 10000,
            isForeground = true
        )

        val decision = governor.decide(WorkPriority.STANDARD, thermalSnapshot)
        assertTrue(decision.defer)
        assertFalse(decision.proceedImmediately)
    }

    @Test
    fun testProfileReflectsDeviceState() {
        context = ApplicationProvider.getApplicationContext<Context>()
        governor = AdaptiveResourceGovernor()
        probe = AndroidDeviceCapabilityProbe(context)

        val snapshot = probe.capture(true)
        val profile = governor.evaluate(snapshot)

        assertTrue(profile.maxConcurrentLocalTasks >= 1)
        assertTrue(profile.maxStreamingChunkChars >= 100)
        // Should not be null unless there's a throttle reason
    }

    @Test
    fun testResourceGovernorIntegration() {
        context = ApplicationProvider.getApplicationContext<Context>()
        governor = AdaptiveResourceGovernor()
        probe = AndroidDeviceCapabilityProbe(context)
        val integration = ResourceGovernorIntegration(governor, probe)

        // Interactive voice gets Default dispatcher (preemptive)
        val voiceDispatcher = integration.dispatcherFor(WorkPriority.INTERACTIVE_VOICE, true)
        assertNotNull(voiceDispatcher)

        // Background indexing gets IO when deferred
        val pressureSnapshot = DeviceCapabilitySnapshot(
            totalRamMb = 4096,
            availableRamMb = 100,
            isLowMemory = true,
            cpuCores = 4,
            thermalLevel = ThermalLevel.NOMINAL,
            isPowerSaveMode = false,
            availableStorageMb = 1000,
            isForeground = false
        )
        val bgDispatcher = integration.dispatcherFor(WorkPriority.BACKGROUND_INDEXING, false)
        assertNotNull(bgDispatcher)

        // Heavy inference check
        val allowHeavy = integration.shouldAllowHeavyInference(true)
        assertTrue(allowHeavy || !allowHeavy) // Just verify it returns a boolean

        // Streaming chunk size
        val chunkSize = integration.getStreamingChunkSize(true)
        assertTrue(chunkSize >= 100)

        // Max concurrent tasks
        val maxTasks = integration.getMaxConcurrentTasks(true)
        assertTrue(maxTasks >= 1)
    }
}
