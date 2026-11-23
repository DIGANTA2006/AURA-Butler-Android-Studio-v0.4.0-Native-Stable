package `in`.diganta.aurabutler.data

import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeVideoResolverTest {
    private val resolver = YouTubeVideoResolver()

    @Test
    fun officialVersionScoresAboveCover() {
        val official = resolver.score(
            query = "Pasoori",
            title =
                "Pasoori | Ali Sethi x Shae Gill | Coke Studio Season 14",
            channel = "Coke Studio Pakistan",
            index = 0
        )
        val cover = resolver.score(
            query = "Pasoori",
            title = "Pasoori acoustic cover",
            channel = "Random Covers",
            index = 0
        )

        assertTrue(official > cover)
    }

    @Test
    fun completeTitleOverlapScoresHighly() {
        val score = resolver.score(
            query = "Shape of You Ed Sheeran",
            title =
                "Ed Sheeran - Shape of You Official Music Video",
            channel = "Ed Sheeran",
            index = 1
        )

        assertTrue(score >= 150)
    }
}
