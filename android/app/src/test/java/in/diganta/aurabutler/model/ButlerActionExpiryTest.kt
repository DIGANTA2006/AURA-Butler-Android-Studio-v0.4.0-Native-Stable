package `in`.diganta.aurabutler.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ButlerActionExpiryTest {
    @Test
    fun highRiskActionExpiresAfterFiveMinutesByDefault() {
        val created = 1_000_000L
        val action = ButlerAction(
            id = "high",
            kind = ActionKind.COMPOSE_EMAIL,
            title = "Send email",
            summary = "test",
            risk = ActionRisk.HIGH,
            payload = emptyMap(),
            createdAtEpochMs = created
        )
        assertFalse(action.isExpired(created + 4 * 60_000L))
        assertTrue(action.isExpired(created + 5 * 60_000L))
    }

    @Test
    fun normalPendingActionExpiresAfterTenMinutes() {
        val created = 2_000_000L
        val action = ButlerAction(
            id = "medium",
            kind = ActionKind.COMPOSE_EMAIL,
            title = "Draft email",
            summary = "test",
            risk = ActionRisk.MEDIUM,
            payload = emptyMap(),
            createdAtEpochMs = created
        )
        assertFalse(action.isExpired(created + 9 * 60_000L))
        assertTrue(action.isExpired(created + 10 * 60_000L))
    }
}
