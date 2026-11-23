package `in`.diganta.aurabutler.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StudentBudgetPolicyTest {
    @Test
    fun localhostAndAdbReverseAreNotMetered() {
        assertFalse(
            StudentBudgetPolicy.isMeteredEndpoint(
                "http://127.0.0.1:8000/v1/chat/completions"
            )
        )
        assertFalse(
            StudentBudgetPolicy.isMeteredEndpoint(
                "http://localhost:8000/v1/chat/completions"
            )
        )
        assertTrue(
            StudentBudgetPolicy.isMeteredEndpoint(
                "https://example.modal.run/v1/chat/completions"
            )
        )
    }

    @Test
    fun configuredTextLimitIsEnforcedOrExplicitlyUnlimited() {
        val limit = StudentBudgetPolicy.DAILY_TEXT_LIMIT
        val snapshot = BudgetSnapshot(
            utcDay = "2026-08-15",
            text = if (limit == 0) 500 else limit
        )

        val decision = StudentBudgetPolicy.evaluate(AiWorkload.TEXT, snapshot)

        if (limit == 0) {
            assertTrue(decision.allowed)
            assertNull(decision.remainingToday)
        } else {
            assertFalse(decision.allowed)
            assertEquals(0, decision.remainingToday)
            assertTrue(decision.message.contains("student-budget"))
        }
    }

    @Test
    fun workloadsHaveIndependentCounters() {
        val snapshot = BudgetSnapshot(
            utcDay = "2026-08-15",
            text = StudentBudgetPolicy.DAILY_TEXT_LIMIT,
            vision = 0
        )

        val vision = StudentBudgetPolicy.evaluate(AiWorkload.VISION, snapshot)

        assertTrue(vision.allowed)
        val expectedRemaining = StudentBudgetPolicy.DAILY_VISION_LIMIT
            .takeUnless { it == 0 }
            ?.minus(1)
        assertEquals(expectedRemaining, vision.remainingToday)
    }

    @Test
    fun quotaMessagesDoNotRequireLaptopOrOllama() {
        val limit = StudentBudgetPolicy.DAILY_RESEARCH_LIMIT
        if (limit == 0) return
        val decision = StudentBudgetPolicy.evaluate(
            AiWorkload.RESEARCH,
            BudgetSnapshot(utcDay = "2026-08-16", research = limit)
        )
        val lower = decision.message.lowercase()
        assertFalse(lower.contains("ollama"))
        assertFalse(lower.contains("laptop"))
        assertTrue(lower.contains("lite search"))
    }

    @Test
    fun aNewUtcDayStartsWithZeroCounters() {
        val yesterday = BudgetSnapshot(
            utcDay = "2026-08-14",
            text = 7,
            research = 2
        )

        val today = StudentBudgetGuard.currentSnapshotForTest(
            utcDay = "2026-08-15",
            stored = yesterday
        )

        assertEquals("2026-08-15", today.utcDay)
        assertEquals(0, today.text)
        assertEquals(0, today.research)
        assertTrue(StudentBudgetPolicy.evaluate(AiWorkload.TEXT, today).allowed)
    }
}
