package `in`.diganta.aurabutler.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SafeCalculatorTest {
    @Test
    fun followsArithmeticPrecedence() {
        assertEquals("14", SafeCalculator.calculate("2 + 3 * 4"))
    }

    @Test
    fun supportsPercentOf() {
        assertEquals("20", SafeCalculator.calculate("10 percent of 200"))
    }

    @Test
    fun rejectsCodeAndDivisionByZero() {
        assertThrows(IllegalArgumentException::class.java) {
            SafeCalculator.calculate("system(exit)")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SafeCalculator.calculate("5 / 0")
        }
    }
}
