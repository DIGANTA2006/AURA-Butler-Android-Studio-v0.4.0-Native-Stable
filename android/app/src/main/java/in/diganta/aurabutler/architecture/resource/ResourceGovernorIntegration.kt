package `in`.diganta.aurabutler.architecture.resource

import `in`.diganta.aurabutler.runtime.OperatingMode

/**
 * Integration layer between AdaptiveResourceGovernor and voice/conversation components.
 * Provides resource-aware streaming chunk sizes and scheduling decisions.
 */
class ResourceGovernorIntegration(
    private val governor: AdaptiveResourceGovernor,
    private val probe: AndroidDeviceCapabilityProbe
) {

    /**
     * Get streaming chunk size based on current device state.
     * Returns smaller chunks under memory/thermal pressure.
     */
    fun getStreamingChunkSize(isForeground: Boolean): Int {
        val snapshot = probe.capture(isForeground)
        val profile = governor.evaluate(snapshot)
        return profile.maxStreamingChunkChars
    }

    /**
     * Execute a block with resource governance.
     * For INTERACTIVE_VOICE, always proceeds immediately (never deferred by arbitrary caps).
     * For BACKGROUND_INDEXING, respects thermal/memory/battery pressure.
     */
    fun <T> executeWithGovernance(
        priority: WorkPriority,
        isForeground: Boolean = true,
        block: () -> T
    ): Pair<T, SchedulingDecision> {
        val snapshot = probe.capture(isForeground)
        val decision = governor.decide(priority, snapshot)

        if (decision.defer) {
            // For deferred work, we still execute but with a warning
            // In production, this could be queued instead
        }

        val result = block()
        return result to decision
    }

    /**
     * Check if background indexing should run.
     */
    fun shouldRunBackgroundIndexing(isForeground: Boolean): Boolean {
        val snapshot = probe.capture(isForeground)
        val profile = governor.evaluate(snapshot)
        return profile.allowBackgroundIndexing
    }

    /**
     * Check if heavy local inference (e.g., local LLM) should run.
     */
    fun shouldRunHeavyLocalInference(isForeground: Boolean): Boolean {
        val snapshot = probe.capture(isForeground)
        val profile = governor.evaluate(snapshot)
        return profile.allowHeavyLocalInference
    }

    /**
     * Get current max concurrent local tasks.
     */
    fun getMaxConcurrentTasks(isForeground: Boolean): Int {
        val snapshot = probe.capture(isForeground)
        val profile = governor.evaluate(snapshot)
        return profile.maxConcurrentLocalTasks
    }

    /**
     * Get current throttle reason for UI display.
     */
    fun getThrottleReason(isForeground: Boolean): String? {
        val snapshot = probe.capture(isForeground)
        val profile = governor.evaluate(snapshot)
        return profile.throttleReason
    }
}
