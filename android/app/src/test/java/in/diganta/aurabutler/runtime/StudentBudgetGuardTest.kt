package `in`.diganta.aurabutler.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class StudentBudgetGuardTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext<Context>()
        StudentBudgetGuard.initialize(context)
        // Wait for initialization
        Thread.sleep(100)
    }

    @Test
    fun testDeviceOnlyModeNeverMeters() {
        val lease = StudentBudgetGuard.tryReserve(
            AiWorkload.TEXT,
            "https://cloud.example.com/v1/chat/completions",
            OperatingMode.DEVICE_ONLY
        )
        assertTrue(lease.allowed)
        assertFalse(lease.metered)
        assertNull(lease.remainingToday)
        assertTrue(lease.message.contains("DEVICE_ONLY"))
    }

    @Test
    fun testLocalEndpointNeverMeters() {
        val lease = StudentBudgetGuard.tryReserve(
            AiWorkload.TEXT,
            "http://localhost:11434/v1/chat/completions",
            OperatingMode.AUTOMATIC
        )
        assertTrue(lease.allowed)
        assertFalse(lease.metered)
        assertNull(lease.remainingToday)
        assertTrue(lease.message.contains("Local endpoint"))
    }

    @Test
    fun testMeteredEndpointReservesBudget() {
        // With default unlimited config (JARVIS MODE), should always allow
        val lease = StudentBudgetGuard.tryReserve(
            AiWorkload.TEXT,
            "https://cloud.example.com/v1/chat/completions",
            OperatingMode.AUTOMATIC
        )
        assertTrue(lease.allowed)
        assertTrue(lease.metered)
        // remainingToday is null when unlimited
    }

    @Test
    fun testCommitAndRelease() {
        val lease = StudentBudgetGuard.tryReserve(
            AiWorkload.TEXT,
            "https://cloud.example.com/v1/chat/completions",
            OperatingMode.AUTOMATIC
        )
        assertTrue(lease.allowed)

        StudentBudgetGuard.commit(lease)
        // Should not throw

        StudentBudgetGuard.release(lease)
        // Should not throw
    }

    @Test
    fun testStatusText() {
        val status = StudentBudgetGuard.statusText()
        assertTrue(status.contains("Student budget"))
        assertTrue(status.contains("Local phone features are not counted"))
    }
}
