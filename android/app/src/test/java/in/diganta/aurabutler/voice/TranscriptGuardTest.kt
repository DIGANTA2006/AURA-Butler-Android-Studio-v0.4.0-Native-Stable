package `in`.diganta.aurabutler.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptGuardTest {
    @Test
    fun rejectsUnsupportedKoreanAndTamilHallucinations() {
        assertEquals(
            "",
            TranscriptGuard.sanitize("의 의", "auto")
        )
        assertEquals(
            "",
            TranscriptGuard.sanitize(
                "பார்த்தும் பார்த்தும் பார்த்த",
                "auto"
            )
        )
    }

    @Test
    fun keepsSupportedEnglishBengaliAndHindi() {
        assertEquals(
            "open YouTube and play Pasoori",
            TranscriptGuard.sanitize(
                "open YouTube and play Pasoori",
                "auto"
            )
        )
        assertEquals(
            "আজকের খবর বলো",
            TranscriptGuard.sanitize("আজকের খবর বলো", "auto")
        )
        assertEquals(
            "आज का मौसम बताओ",
            TranscriptGuard.sanitize("आज का मौसम बताओ", "auto")
        )
    }

    @Test
    fun collapsesRepeatedTranscript() {
        assertEquals(
            "my battery level",
            TranscriptGuard.sanitize(
                "my battery level is my battery level",
                "en"
            )
        )
        assertEquals(
            "play Pasoori",
            TranscriptGuard.sanitize(
                "play Pasoori play Pasoori",
                "en"
            )
        )
    }
}
