package `in`.diganta.aurabutler.data

import org.junit.Assert.*
import org.junit.Test

class AiResponseFormatterTest {

    @Test
    fun testPlainTextPreservesCodeFences() {
        val input = "Here is code:\n```kotlin\nfun main() { println(\"Hello\") }\n```\nEnd."
        val output = AiResponseFormatter.plainText(input)
        assertTrue(output.contains("```kotlin"))
        assertTrue(output.contains("fun main()"))
        assertTrue(output.contains("println"))
    }

    @Test
    fun testPlainTextNormalizesProse() {
        val input = "**Bold** and *italic* and `code` and ~~strike~~ and __underline__"
        val output = AiResponseFormatter.plainText(input)
        assertTrue(output.contains("Bold"))
        assertTrue(output.contains("italic"))
        assertTrue(output.contains("code"))
        assertTrue(output.contains("strike"))
        assertTrue(output.contains("underline"))
        assertFalse(output.contains("**"))
        assertFalse(output.contains("__"))
    }

    @Test
    fun testPlainTextStripsHiddenThinking() {
        val input = "<think>This is hidden reasoningVisible answer."
        val output = AiResponseFormatter.plainText(input)
        assertFalse(output.contains("hidden reasoning"))
        assertTrue(output.contains("Visible answer"))
    }

    @Test
    fun testPlainTextFailsClosedOnUnclosedThinking() {
        val input = "<think>Unclosed thinking block\nVisible answer."
        val output = AiResponseFormatter.plainText(input)
        assertFalse(output.contains("Unclosed"))
        assertTrue(output.contains("Visible answer"))
    }

    @Test
    fun testContainsFencedCode() {
        assertTrue(AiResponseFormatter.containsFencedCode("```code```"))
        assertFalse(AiResponseFormatter.containsFencedCode("no fences here"))
    }

    @Test
    fun testStreamingDeltaPreservesFenceState() {
        var inFence = false
        var result: Pair<String, Boolean>

        // Start fence
        result = AiResponseFormatter.formatStreamingDelta("```kotlin\n", inFence)
        inFence = result.second
        assertTrue(inFence)
        assertEquals("```kotlin\n", result.first)

        // Inside fence - code preserved
        result = AiResponseFormatter.formatStreamingDelta("fun main() {\n", inFence)
        inFence = result.second
        assertTrue(inFence)
        assertEquals("fun main() {\n", result.first)

        // End fence
        result = AiResponseFormatter.formatStreamingDelta("}\n```\n", inFence)
        inFence = result.second
        assertFalse(inFence)
        assertEquals("}\n```\n", result.first)
    }
}
