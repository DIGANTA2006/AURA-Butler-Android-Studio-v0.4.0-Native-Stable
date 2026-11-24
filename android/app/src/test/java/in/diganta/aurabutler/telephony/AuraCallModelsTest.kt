package `in`.diganta.aurabutler.telephony

import org.junit.Assert.assertEquals
import org.junit.Test

class AuraCallModelsTest {
    @Test
    fun connectedDurationUsesConnectAndEndTimes() {
        val record = record(connectedAt = 2_000L, endedAt = 12_500L)
        assertEquals(10_500L, record.durationMs)
    }

    @Test
    fun missedOrNeverConnectedCallHasZeroConnectedDuration() {
        val record = record(connectedAt = 0L, endedAt = 12_500L)
        assertEquals(0L, record.durationMs)
    }

    @Test
    fun clockOrTimestampAnomalyNeverProducesNegativeDuration() {
        val record = record(connectedAt = 15_000L, endedAt = 12_500L)
        assertEquals(0L, record.durationMs)
    }

    private fun record(connectedAt: Long, endedAt: Long) = AuraCallHistoryRecord(
        id = "test-call",
        phoneNumber = null,
        incoming = true,
        startedAtEpochMs = 1_000L,
        endedAtEpochMs = endedAt,
        connectedAtEpochMs = connectedAt,
        disconnectCauseCode = null
    )
}
