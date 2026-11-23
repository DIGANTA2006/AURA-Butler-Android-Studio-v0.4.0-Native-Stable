package `in`.diganta.aurabutler.domain

import `in`.diganta.aurabutler.model.ButlerSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ButlerPersonaTest {
    @Test
    fun seriousRepliesRemainUnmodified() {
        val text = "Critical security alert detected."
        val result = ButlerPersona.style(
            text,
            "notification_digest",
            ButlerSettings(butlerBanterEnabled = true, butlerBanterLevel = 2),
            "read critical notifications"
        )
        assertEquals(text, result)
    }

    @Test
    fun playfulDeviceCommandCanAddOriginalBanter() {
        val result = ButlerPersona.style(
            "Opened YouTube.",
            "open_app",
            ButlerSettings(butlerBanterEnabled = true, butlerBanterLevel = 2),
            "open youtube"
        )
        assertTrue(result.length > "Opened YouTube.".length)
    }
}
