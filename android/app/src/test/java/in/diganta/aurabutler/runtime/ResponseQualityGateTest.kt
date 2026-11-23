package `in`.diganta.aurabutler.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseQualityGateTest {
    @Test
    fun preservesProgrammingSyntaxInsideFencedCode() {
        val raw = """
            Here is the implementation:
            ```c
            int *ptr = &value;
            int my_variable = a*b;
            printf("%d\\n", my_variable);
            ```
        """.trimIndent()

        val value = ResponseQualityGate.forDisplay(raw, "professional")
        assertTrue(value.contains("```c"))
        assertTrue(value.contains("int *ptr = &value;"))
        assertTrue(value.contains("my_variable = a*b"))
        assertTrue(value.contains("```"))
    }

    @Test
    fun removesHiddenThinkingWithoutCorruptingPowerShell() {
        val raw = "<think>private reasoning</think>```powershell\n`${'$'}env:USERPROFILE\\Downloads\n```"
        val value = ResponseQualityGate.forDisplay(raw, "professional")
        assertFalse(value.contains("private reasoning"))
        assertTrue(value.contains("`${'$'}env:USERPROFILE\\Downloads"))
    }

    @Test
    fun normalizesPairedMarkdownInProseOnly() {
        val value = ResponseQualityGate.forDisplay("**Important**: use `my_variable`.", "professional")
        assertEquals("Important: use my_variable.", value)
    }
}
