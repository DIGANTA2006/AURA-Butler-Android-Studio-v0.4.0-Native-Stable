package `in`.diganta.aurabutler.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RomanizedBengaliTest {
    @Test
    fun detectsBanglishSentences() {
        assertTrue(RomanizedBengali.isRomanizedBengali("Bolchi kichu habe nato"))
        assertTrue(RomanizedBengali.isRomanizedBengali("Vai Current ki asbe na??"))
    }

    @Test
    fun convertsBanglishToBengaliScript() {
        assertEquals(
            "\u09AC\u09B2\u099B\u09BF \u0995\u09BF\u099B\u09C1 \u09B9\u09AC\u09C7 \u09A8\u09BE\u09A4\u09CB",
            RomanizedBengali.prepareForSpeech("Bolchi kichu habe nato")
        )
        assertEquals(
            "\u098F\u099F\u09BE \u0995\u09C7 \u09AA\u09BE\u09AC\u09B2\u09BF\u0995 \u0995\u09B0\u09C7 \u09A6\u09BF\u09AF\u09BC\u09C7\u099B\u09BF",
            RomanizedBengali.prepareForSpeech("ata ke public kore diyechi")
        )
        assertEquals(
            "\u0986\u09AE\u09BF \u0995\u09BE\u09B2 \u09AF\u09BE\u09AC\u09CB",
            RomanizedBengali.prepareForSpeech("Ami kal jabo")
        )
    }

    @Test
    fun leavesEnglishTextUnchanged() {
        val text = "Your battery level is 100 percent and healthy."
        assertFalse(RomanizedBengali.isRomanizedBengali(text))
        assertEquals(text, RomanizedBengali.prepareForSpeech(text))
        val mixed = "I am Ami, your assistant."
        assertEquals(mixed, RomanizedBengali.prepareForSpeech(mixed))
    }

    @Test
    fun conversionIsIdempotent() {
        val once = RomanizedBengali.prepareForSpeech("Bolchi kichu habe nato")
        assertEquals(once, RomanizedBengali.prepareForSpeech(once))
    }
}