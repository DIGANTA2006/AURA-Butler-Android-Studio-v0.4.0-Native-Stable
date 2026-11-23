package `in`.diganta.aurabutler.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechTextSanitizerTest {
    @Test
    fun removesMarkdownCharactersBeforeSpeech() {
        val clean = SpeechTextSanitizer.clean(
            "Your battery level is **100 %** and __healthy__."
        )

        assertEquals(
            "Your battery level is 100 percent and healthy.",
            clean
        )
        assertFalse(clean.contains("*"))
        assertFalse(clean.contains("_"))
        assertFalse(clean.contains("%"))
    }

    @Test
    fun convertsMarkdownLinksAndBulletsToNaturalSpeech() {
        val clean = SpeechTextSanitizer.clean(
            """
            # Results
            - Open [YouTube](https://youtube.com)
            - Visit https://example.com/test
            """.trimIndent()
        )

        assertEquals(
            "Results. Open YouTube. Visit the link",
            clean
        )
    }

    @Test
    fun resumesAfterTheCurrentPartiallySpokenWord() {
        val text = "One two three four five."

        assertEquals(
            "four five.",
            SpeechTextSanitizer.remainingAfter(
                text,
                text.indexOf("three") + 2
            )
        )
    }

    @Test
    fun detectsWakeWordOnlyCommands() {
        assertTrue(
            SpeechTextSanitizer.isWakeWordOnly(
                "Hey Aura!"
            )
        )
        assertTrue(
            SpeechTextSanitizer.isWakeWordOnly(
                "Aura"
            )
        )
        assertFalse(
            SpeechTextSanitizer.isWakeWordOnly(
                "Aura open YouTube"
            )
        )
    }

    @Test
    fun recognizesWhenCurrentSpeechContainsAurasName() {
        val text = "I am AURA, your private assistant."
        val index = text.indexOf("AURA")

        assertTrue(
            SpeechTextSanitizer.containsWakeWordNear(
                text,
                index
            )
        )
    }
    @Test
    fun usesBengaliPronunciationForTechnicalTerms() {
        val clean = SpeechTextSanitizer.clean(
            "AURA GPU 50%",
            "bn"
        )
        assertTrue(clean.contains("অরা"))
        assertTrue(clean.contains("জি পি ইউ"))
        assertTrue(clean.contains("50 শতাংশ"))
    }

    @Test
    fun usesHindiPronunciationForTechnicalTerms() {
        val clean = SpeechTextSanitizer.clean(
            "AURA API 25%",
            "hi"
        )
        assertTrue(clean.contains("ऑरा"))
        assertTrue(clean.contains("ए पी आई"))
        assertTrue(clean.contains("25 प्रतिशत"))
    }

}
