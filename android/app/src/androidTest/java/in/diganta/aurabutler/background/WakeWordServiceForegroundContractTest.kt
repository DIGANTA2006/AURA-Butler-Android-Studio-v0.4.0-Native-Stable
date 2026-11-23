package `in`.diganta.aurabutler.background

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import `in`.diganta.aurabutler.data.SecureStore
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WakeWordServiceForegroundContractTest {
    @Test
    fun foregroundStartWithWakeDisabledCompletesHandshakeWithoutWatchdogCrash() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = SecureStore(context)
        val original = store.loadSettings()
        try {
            store.saveSettings(original.copy(wakeWordEnabled = false))
            WakeWordService.setEnabled(context, true)
            // Android's foreground-service watchdog is approximately five seconds.
            // Surviving beyond this point verifies the early-exit foreground handshake.
            Thread.sleep(6_500L)
            assertFalse(WakeWordService.isRunning(context))
        } finally {
            WakeWordService.setEnabled(context, false)
            store.saveSettings(original)
        }
    }
}
