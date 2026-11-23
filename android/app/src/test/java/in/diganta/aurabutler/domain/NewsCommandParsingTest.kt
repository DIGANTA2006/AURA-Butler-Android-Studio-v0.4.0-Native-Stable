package `in`.diganta.aurabutler.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NewsCommandParsingTest {
    @Test
    fun `plain news request returns headline mode`() {
        val parsed = NewsCommandParsing.parse("Tell me the news")
        assertEquals(NewsMode.HEADLINES, parsed.mode)
        assertNull(parsed.topic)
        assertNull(parsed.detailIndex)
    }

    @Test
    fun `brief technology news extracts topic`() {
        val parsed = NewsCommandParsing.parse("Briefly tell me technology news")
        assertEquals(NewsMode.BRIEF, parsed.mode)
        assertEquals("technology", parsed.topic)
        assertTrue(parsed.briefly)
    }

    @Test
    fun `second headline detail is recognized`() {
        val parsed = NewsCommandParsing.parse("Explain the second headline in detail")
        assertEquals(2, parsed.detailIndex)
        assertTrue(parsed.detailRequested)
        assertFalse(parsed.briefly)
    }

    @Test
    fun `numeric headline follow up is recognized`() {
        val parsed = NewsCommandParsing.parse("explain headline 1")
        assertEquals(1, parsed.detailIndex)
        assertTrue(parsed.detailRequested)
    }

    @Test
    fun `news about topic extracts trailing topic`() {
        val parsed = NewsCommandParsing.parse("Tell me news about West Bengal")
        assertEquals("West Bengal", parsed.topic)
    }
}
