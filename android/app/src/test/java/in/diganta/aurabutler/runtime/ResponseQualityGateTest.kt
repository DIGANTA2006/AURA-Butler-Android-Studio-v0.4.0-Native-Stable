package `in`.diganta.aurabutler.runtime

import `in`.diganta.aurabutler.data.AiResponseFormatter
import org.junit.Assert.*
import org.junit.Test

class ResponseQualityGateTest {

    @Test
    fun testForDisplayNoTruncation() {
        // Create a very long response (100k chars)
        val longText = "A".repeat(100_000)
        val output = ResponseQualityGate.forDisplay(longText, "normal", OperatingMode.AUTOMATIC)
        assertEquals(100_000, output.length)
    }

    @Test
    fun testForDisplayDeviceOnlyMode() {
        val text = "Test response"
        val output = ResponseQualityGate.forDisplay(text, "normal", OperatingMode.DEVICE_ONLY)
        assertEquals(text, output)
    }

    @Test
    fun testForDisplayStripsIdentityMarkers() {
        val input = "Certainly, master Diganta. Here is the answer."
        val output = ResponseQualityGate.forDisplay(input, "normal", OperatingMode.AUTOMATIC)
        assertFalse(output.contains("master Diganta"))
        assertTrue(output.contains("Here is the answer"))
    }

    @Test
    fun testForDisplayPreservesCodeFences() {
        val input = "Code:\n```python\ndef hello():\n    print('world')\n```"
        val output = ResponseQualityGate.forDisplay(input, "normal", OperatingMode.AUTOMATIC)
        assertTrue(output.contains("```python"))
        assertTrue(output.contains("def hello()"))
    }

    @Test
    fun testForStreamingDisplay() {
        val input = "<think>hiddenStreaming delta"
        val output = ResponseQualityGate.forStreamingDisplay(input, OperatingMode.AUTOMATIC)
        assertFalse(output.contains("hidden"))
        assertTrue(output.contains("Streaming delta"))
    }

    @Test
    fun testDeduplication() {
        val input = "First paragraph.\n\nFirst paragraph.\n\nSecond paragraph."
        val output = ResponseQualityGate.deduplicate(input)
        assertEquals("First paragraph.\n\nSecond paragraph.", output)
    }
}
