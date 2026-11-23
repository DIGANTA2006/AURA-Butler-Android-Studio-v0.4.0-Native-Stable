package `in`.diganta.aurabutler.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForecastDayParserTest {
    @Test
    fun `parses ten day forecast`() {
        assertEquals(10, ForecastDayParser.parse("Tell me the 10 day weather forecast"))
    }

    @Test
    fun `parses word form`() {
        assertEquals(10, ForecastDayParser.parse("Give me a ten days forecast"))
    }

    @Test
    fun `next week maps to seven days`() {
        assertEquals(7, ForecastDayParser.parse("Weather for next week"))
    }

    @Test
    fun `current weather has no extended forecast count`() {
        assertNull(ForecastDayParser.parse("What is the weather now?"))
    }
}
