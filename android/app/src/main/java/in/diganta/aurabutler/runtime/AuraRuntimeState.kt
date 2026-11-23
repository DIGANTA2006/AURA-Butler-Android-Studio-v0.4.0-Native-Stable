package `in`.diganta.aurabutler.runtime

import android.os.SystemClock

enum class AuraRuntimeState {
    STOPPED,
    IDLE,
    LISTENING,
    THINKING,
    SPEAKING,
    INTERRUPTED,
    ERROR,
    STOPPING
}

data class AuraRuntimeSnapshot(
    val state: AuraRuntimeState,
    val changedAtElapsedMs: Long,
    val reason: String
)

object AuraRuntimeStateMachine {
    private val lock = Any()

    @Volatile
    private var snapshot = AuraRuntimeSnapshot(
        state = AuraRuntimeState.STOPPED,
        changedAtElapsedMs = SystemClock.elapsedRealtime(),
        reason = "created"
    )

    private val allowed = mapOf(
        AuraRuntimeState.STOPPED to setOf(AuraRuntimeState.IDLE),
        AuraRuntimeState.IDLE to setOf(
            AuraRuntimeState.LISTENING,
            AuraRuntimeState.THINKING,
            AuraRuntimeState.SPEAKING,
            AuraRuntimeState.STOPPING,
            AuraRuntimeState.ERROR
        ),
        AuraRuntimeState.LISTENING to setOf(
            AuraRuntimeState.THINKING,
            AuraRuntimeState.IDLE,
            AuraRuntimeState.INTERRUPTED,
            AuraRuntimeState.STOPPING,
            AuraRuntimeState.ERROR
        ),
        AuraRuntimeState.THINKING to setOf(
            AuraRuntimeState.IDLE,
            AuraRuntimeState.SPEAKING,
            AuraRuntimeState.INTERRUPTED,
            AuraRuntimeState.STOPPING,
            AuraRuntimeState.ERROR
        ),
        AuraRuntimeState.SPEAKING to setOf(
            AuraRuntimeState.IDLE,
            AuraRuntimeState.LISTENING,
            AuraRuntimeState.INTERRUPTED,
            AuraRuntimeState.STOPPING,
            AuraRuntimeState.ERROR
        ),
        AuraRuntimeState.INTERRUPTED to setOf(
            AuraRuntimeState.IDLE,
            AuraRuntimeState.LISTENING,
            AuraRuntimeState.THINKING,
            AuraRuntimeState.STOPPING,
            AuraRuntimeState.ERROR
        ),
        AuraRuntimeState.ERROR to setOf(
            AuraRuntimeState.IDLE,
            AuraRuntimeState.STOPPING
        ),
        AuraRuntimeState.STOPPING to setOf(AuraRuntimeState.STOPPED)
    )

    fun current(): AuraRuntimeSnapshot = snapshot

    fun transition(
        target: AuraRuntimeState,
        reason: String = ""
    ): AuraRuntimeSnapshot = synchronized(lock) {
        val current = snapshot.state
        if (current == target) return@synchronized snapshot

        check(target in allowed.getValue(current)) {
            "Invalid AURA state transition: $current -> $target"
        }

        AuraRuntimeSnapshot(
            state = target,
            changedAtElapsedMs = SystemClock.elapsedRealtime(),
            reason = reason.take(180)
        ).also { snapshot = it }
    }

    fun safeTransition(
        target: AuraRuntimeState,
        reason: String = ""
    ): AuraRuntimeSnapshot = synchronized(lock) {
        runCatching { transition(target, reason) }
            .getOrElse {
                AuraRuntimeSnapshot(
                    state = AuraRuntimeState.ERROR,
                    changedAtElapsedMs = SystemClock.elapsedRealtime(),
                    reason = "Recovered from invalid transition: ${it.message.orEmpty().take(120)}"
                ).also { recovered -> snapshot = recovered }
            }
    }

    fun forceIdle(reason: String): AuraRuntimeSnapshot = synchronized(lock) {
        AuraRuntimeSnapshot(
            state = AuraRuntimeState.IDLE,
            changedAtElapsedMs = SystemClock.elapsedRealtime(),
            reason = reason.take(180)
        ).also { snapshot = it }
    }

    /**
     * Finish an older turn without clobbering a newer microphone/listening state.
     * This closes a race where a response ending at the same instant as a new
     * wake event could reset LISTENING back to IDLE.
     */
    fun forceIdleUnlessListening(reason: String): AuraRuntimeSnapshot = synchronized(lock) {
        if (snapshot.state == AuraRuntimeState.LISTENING) return@synchronized snapshot
        AuraRuntimeSnapshot(
            state = AuraRuntimeState.IDLE,
            changedAtElapsedMs = SystemClock.elapsedRealtime(),
            reason = reason.take(180)
        ).also { snapshot = it }
    }

    fun startIfNeeded(reason: String = "runtime started"): AuraRuntimeSnapshot = synchronized(lock) {
        if (snapshot.state == AuraRuntimeState.STOPPED) {
            snapshot = AuraRuntimeSnapshot(
                AuraRuntimeState.IDLE,
                SystemClock.elapsedRealtime(),
                reason.take(180)
            )
        }
        snapshot
    }
}
