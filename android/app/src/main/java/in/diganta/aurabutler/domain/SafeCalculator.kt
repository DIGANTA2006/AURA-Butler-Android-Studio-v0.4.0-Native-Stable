package `in`.diganta.aurabutler.domain

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * A bounded arithmetic parser. It deliberately supports maths only and never
 * evaluates code, scripts, variables, file paths, or Android expressions.
 */
object SafeCalculator {
    fun calculate(raw: String): String {
        val normalized = raw
            .replace("×", "*")
            .replace("÷", "/")
            .replace(Regex("""(?i)\bplus\b"""), "+")
            .replace(Regex("""(?i)\bminus\b"""), "-")
            .replace(Regex("""(?i)\b(?:times|multiplied by)\b"""), "*")
            .replace(Regex("""(?i)\bdivided by\b"""), "/")
            .replace(Regex("""(?i)\bpercent of\b"""), "%*")
            .replace(Regex("""\s+"""), "")
            .take(MAX_EXPRESSION_LENGTH)
        require(normalized.isNotBlank()) { "The calculation is empty." }
        val value = Parser(normalized).parse()
        require(value.isFinite()) { "The result is outside the supported range." }
        return format(value)
    }

    private fun format(value: Double): String {
        if (abs(value) < 1e-12) return "0"
        return BigDecimal.valueOf(value)
            .setScale(10, RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString()
    }

    private class Parser(private val text: String) {
        private var index = 0

        fun parse(): Double {
            val result = expression()
            require(index == text.length) {
                "Unsupported calculation near “${text.drop(index).take(12)}”."
            }
            return result
        }

        private fun expression(): Double {
            var value = term()
            while (true) {
                value = when {
                    take('+') -> value + term()
                    take('-') -> value - term()
                    else -> return value
                }
            }
        }

        private fun term(): Double {
            var value = power()
            while (true) {
                value = when {
                    take('*') -> value * power()
                    take('/') -> {
                        val divisor = power()
                        require(abs(divisor) > 1e-15) { "Division by zero is not allowed." }
                        value / divisor
                    }
                    take('%') -> value / 100.0
                    else -> return value
                }
            }
        }

        private fun power(): Double {
            var value = unary()
            if (take('^')) value = value.pow(power())
            return value
        }

        private fun unary(): Double = when {
            take('+') -> unary()
            take('-') -> -unary()
            else -> primary()
        }

        private fun primary(): Double {
            if (take('(')) {
                val value = expression()
                require(take(')')) { "A closing parenthesis is missing." }
                return value
            }
            val function = readLetters()
            if (function.isNotEmpty()) {
                if (function == "pi") return Math.PI
                if (function == "e") return Math.E
                require(take('(')) { "Use $function(value)." }
                val argument = expression()
                require(take(')')) { "A closing parenthesis is missing." }
                return when (function) {
                    "sqrt" -> {
                        require(argument >= 0) { "Square root needs a non-negative value." }
                        sqrt(argument)
                    }
                    "abs" -> abs(argument)
                    else -> throw IllegalArgumentException(
                        "The function $function is not supported."
                    )
                }
            }
            return readNumber()
        }

        private fun readNumber(): Double {
            val start = index
            var dots = 0
            while (index < text.length) {
                val character = text[index]
                if (character == '.') {
                    dots++
                    if (dots > 1) break
                } else if (!character.isDigit()) {
                    break
                }
                index++
            }
            require(index > start) { "A number was expected." }
            return text.substring(start, index).toDouble()
        }

        private fun readLetters(): String {
            val start = index
            while (index < text.length && text[index].isLetter()) index++
            return text.substring(start, index).lowercase()
        }

        private fun take(character: Char): Boolean {
            if (index >= text.length || text[index] != character) return false
            index++
            return true
        }
    }

    private const val MAX_EXPRESSION_LENGTH = 160
}
