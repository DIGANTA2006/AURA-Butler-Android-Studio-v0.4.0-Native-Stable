package `in`.diganta.aurabutler.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeAutoplayParsingTest {
    @Test
    fun pasuriIsCorrectedLocally() {
        assertEquals(
            "Pasoori",
            CommandNormalizer.normalizeMediaQuery("pasuri")
        )
    }

    @Test
    fun extractsCompoundOpenYouTubeAndPlayCommand() {
        val engine = YouTubeAutoplayEngine()
        assertEquals(
            "patar basori",
            engine.extractQuery(
                "open YouTube and play patar basori"
            )
        )
    }

    @Test
    fun commandDetectorRecognizesPlaybackRequest() {
        assertTrue(
            CommandNormalizer.isLikelyCommand("play pasuri")
        )
    }
}
