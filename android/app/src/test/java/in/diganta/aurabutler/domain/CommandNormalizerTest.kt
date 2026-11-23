package `in`.diganta.aurabutler.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandNormalizerTest {
    @Test
    fun repairsCommonDeviceCommandTypos() {
        val result = CommandNormalizer.normalize(
            "opep you tube and sumarize wataop group messege",
            enabled = true
        )
        assertTrue(result.changed)
        assertEquals(
            "open YouTube and summarize WhatsApp group message",
            result.corrected
        )
    }

    @Test
    fun repairsNewsAndWeatherTypos() {
        val news = CommandNormalizer.normalize(
            "befley tell me bews headine",
            enabled = true
        )
        assertEquals(
            "briefly tell me news headline",
            news.corrected
        )

        val weather = CommandNormalizer.normalize(
            "tell me ten days weathre forcast",
            enabled = true
        )
        assertEquals(
            "tell me ten days weather forecast",
            weather.corrected
        )
    }

    @Test
    fun repairsYourTubeCompoundCommand() {
        val result = CommandNormalizer.normalize(
            "open your tube and play patar basori",
            enabled = true
        )
        assertEquals(
            "open YouTube and play patar basori",
            result.corrected
        )
    }

    @Test
    fun leavesUrlsUntouched() {
        val raw = "open https://www.youtube.com/watch?v=abc123"
        val result = CommandNormalizer.normalize(raw, enabled = true)
        assertFalse(result.changed)
        assertEquals(raw, result.corrected)
    }

    @Test
    fun leavesLongGeneralTextUntouchedWhenDisabled() {
        val raw = "Please explain the relationship between operating systems and hardware."
        assertEquals(raw, CommandNormalizer.normalize(raw, false).corrected)
    }
}
