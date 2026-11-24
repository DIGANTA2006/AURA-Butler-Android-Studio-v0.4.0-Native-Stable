package `in`.diganta.aurabutler.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoroutineSafetyTest {
    @Test
    fun ordinaryFailureIsCapturedAsResult() = runBlocking {
        val result = runCatchingCancellable<String> {
            throw IllegalStateException("expected failure")
        }
        assertTrue(result.isFailure)
        assertEquals("expected failure", result.exceptionOrNull()?.message)
    }

    @Test
    fun cancellationIsRethrownInsteadOfCaptured() = runBlocking {
        var propagated = false
        try {
            runCatchingCancellable<String> {
                throw CancellationException("caller cancelled")
            }
        } catch (cancelled: CancellationException) {
            propagated = true
            assertEquals("caller cancelled", cancelled.message)
        }
        assertTrue("Cancellation must propagate to the caller.", propagated)
    }
}
