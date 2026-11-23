package `in`.diganta.aurabutler.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCommandClassifierTest {
    @Test
    fun recognizesHighPrioritySpeechControlsExactly() {
        assertEquals(
            VoiceCommandClassifier.ControlIntent.STOP_SPEECH,
            VoiceCommandClassifier.controlIntent("Hey Aura, please stop speaking!")
        )
        assertEquals(
            VoiceCommandClassifier.ControlIntent.PAUSE_SPEECH,
            VoiceCommandClassifier.controlIntent("wait")
        )
        assertEquals(
            VoiceCommandClassifier.ControlIntent.RESUME_SPEECH,
            VoiceCommandClassifier.controlIntent("continue")
        )
        assertEquals(
            VoiceCommandClassifier.ControlIntent.STOP_LISTENING,
            VoiceCommandClassifier.controlIntent("disable wake word")
        )
    }

    @Test
    fun doesNotTreatOrdinaryMediaCommandsAsSpeechStop() {
        assertEquals(
            VoiceCommandClassifier.ControlIntent.NONE,
            VoiceCommandClassifier.controlIntent("stop the music")
        )
        assertEquals(
            VoiceCommandClassifier.ControlIntent.NONE,
            VoiceCommandClassifier.controlIntent("stop my timer")
        )
    }

    @Test
    fun detectsObviousIncompleteUtterancesWithoutRejectingNormalCommands() {
        assertFalse(VoiceCommandClassifier.assess("open the").isComplete)
        assertFalse(VoiceCommandClassifier.assess("AURA, tell me").isComplete)
        assertFalse(VoiceCommandClassifier.assess("search for").isComplete)
        assertFalse(VoiceCommandClassifier.assess("and").isComplete)

        assertTrue(VoiceCommandClassifier.assess("open Google Chrome").isComplete)
        assertTrue(VoiceCommandClassifier.assess("turn the volume up").isComplete)
        assertTrue(VoiceCommandClassifier.assess("stop").isComplete)
        assertTrue(VoiceCommandClassifier.assess("weather").isComplete)
    }

    @Test
    fun rejectsEmptyTranscriptAsIncomplete() {
        assertFalse(VoiceCommandClassifier.assess("  ").isComplete)
    }
}

class SpeechContinuationStackTest {
    @Test
    fun resumesNestedContinuationsInLastInterruptedFirstOutOrder() {
        val stack = SpeechContinuationStack(capacity = 3)

        assertTrue(stack.push("first response remainder"))
        assertTrue(stack.push("second response remainder"))

        assertEquals("second response remainder", stack.pop())
        assertEquals("first response remainder", stack.pop())
        assertEquals(null, stack.pop())
    }

    @Test
    fun remainsBoundedAndDropsTheOldestContinuation() {
        val stack = SpeechContinuationStack(capacity = 2)

        stack.push("oldest")
        stack.push("middle")
        stack.push("newest")

        assertEquals(2, stack.size())
        assertEquals("newest", stack.pop())
        assertEquals("middle", stack.pop())
        assertEquals(null, stack.pop())
    }

    @Test
    fun ignoresBlankAndImmediateDuplicateContinuations() {
        val stack = SpeechContinuationStack()

        assertFalse(stack.push("  "))
        assertTrue(stack.push("remaining words"))
        assertFalse(stack.push("remaining words"))
        assertEquals(1, stack.size())
    }

    @Test
    fun clearDiscardsAllPendingSpeech() {
        val stack = SpeechContinuationStack()
        stack.push("first")
        stack.push("second")

        stack.clear()

        assertEquals(0, stack.size())
        assertEquals(null, stack.pop())
    }
}
