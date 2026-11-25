package `in`.diganta.aurabutler.architecture.voice

import `in`.diganta.aurabutler.runtime.OperatingMode
import `in`.diganta.aurabutler.voice.Speaker
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class VoiceConversationEngineTest {

    private lateinit var context: Context
    private lateinit var engine: VoiceConversationEngine
    private val mockSpeaker = object : Speaker {
        override suspend fun speak(text: String, locale: java.util.Locale, onComplete: (Int) -> Unit) {
            onComplete(android.speech.tts.TextToSpeech.SUCCESS)
        }
        override fun adjustVolume(factor: Float) {}
        override fun adjustRate(factor: Float) {}
        override fun setMuted(muted: Boolean) {}
    }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext<Context>()
        engine = VoiceConversationEngine(context, mockSpeaker)
    }

    @Test
    fun testPriorityControlParsing() {
        val parser = VoicePriorityControlParser()

        assertEquals(VoicePriorityControlParser.PriorityControlCommand.STOP_SPEECH, parser.parse("Aura, stop"))
        assertEquals(VoicePriorityControlParser.PriorityControlCommand.STOP_SPEECH, parser.parse("stop"))
        assertEquals(VoicePriorityControlParser.PriorityControlCommand.PAUSE_SPEECH, parser.parse("pause"))
        assertEquals(VoicePriorityControlParser.PriorityControlCommand.RESUME_SPEECH, parser.parse("continue"))
        assertEquals(VoicePriorityControlParser.PriorityControlCommand.STOP_LISTENING, parser.parse("stop listening"))
        assertEquals(VoicePriorityControlParser.PriorityControlCommand.VOLUME_UP, parser.parse("volume up"))
        assertEquals(VoicePriorityControlParser.PriorityControlCommand.VOLUME_DOWN, parser.parse("volume down"))
        assertEquals(VoicePriorityControlParser.PriorityControlCommand.RATE_FASTER, parser.parse("speak faster"))
        assertEquals(VoicePriorityControlParser.PriorityControlCommand.RATE_SLOWER, parser.parse("speak slower"))
        assertEquals(VoicePriorityControlParser.PriorityControlCommand.MUTE, parser.parse("mute yourself"))
        assertEquals(VoicePriorityControlParser.PriorityControlCommand.UNMUTE, parser.parse("unmute"))
        assertEquals(VoicePriorityControlParser.PriorityControlCommand.REPEAT_LAST, parser.parse("repeat that"))
        assertEquals(VoicePriorityControlParser.PriorityControlCommand.NONE, parser.parse("what is the weather"))
    }

    @Test
    fun testCommandCompletenessValidation() {
        val validator = CommandCompletenessValidator()

        // Incomplete command (Case A)
        var input = CommandCompletenessValidator.UtteranceValidationInput(
            transcript = "Aura open",
            speakerVerified = true,
            speakerConfidence = 0.9f,
            currentState = VoiceConversationEngine.VoiceState.LISTENING,
            isSpeaking = false
        )
        var result = validator.validate(input)
        assertTrue(result is CommandCompletenessValidator.CommandValidationDecision.WaitOrAskCompletion)

        // Ambiguous when idle (Case B)
        input = CommandCompletenessValidator.UtteranceValidationInput(
            transcript = "turn it up",
            speakerVerified = true,
            speakerConfidence = 0.9f,
            currentState = VoiceConversationEngine.VoiceState.LISTENING,
            isSpeaking = false
        )
        result = validator.validate(input)
        assertTrue(result is CommandCompletenessValidator.CommandValidationDecision.RequestClarification)

        // Contextual follow-up while speaking (Case C)
        input = CommandCompletenessValidator.UtteranceValidationInput(
            transcript = "make it louder",
            speakerVerified = true,
            speakerConfidence = 0.9f,
            currentState = VoiceConversationEngine.VoiceState.LISTENING,
            isSpeaking = true
        )
        result = validator.validate(input)
        assertTrue(result is CommandCompletenessValidator.CommandValidationDecision.Proceed)

        // Unauthorized speaker
        input = CommandCompletenessValidator.UtteranceValidationInput(
            transcript = "set a timer",
            speakerVerified = false,
            speakerConfidence = 0.3f,
            currentState = VoiceConversationEngine.VoiceState.LISTENING,
            isSpeaking = false
        )
        result = validator.validate(input)
        assertTrue(result is CommandCompletenessValidator.CommandValidationDecision.RejectUnauthorizedSpeaker)
    }

    @Test
    fun testStateMachineTransitions() {
        val sm = VoiceConversationStateMachine()

        assertTrue(sm.canTransition(VoiceConversationEngine.VoiceState.IDLE, VoiceConversationEngine.VoiceState.WAKE_WORD_DETECTED))
        assertTrue(sm.canTransition(VoiceConversationEngine.VoiceState.WAKE_WORD_DETECTED, VoiceConversationEngine.VoiceState.AUTHENTICATING_SPEAKER))
        assertTrue(sm.canTransition(VoiceConversationEngine.VoiceState.SPEAKING, VoiceConversationEngine.VoiceState.INTERRUPTED))
        assertTrue(sm.canTransition(VoiceConversationEngine.VoiceState.INTERRUPTED, VoiceConversationEngine.VoiceState.PROCESSING_COMMAND))
        assertTrue(sm.canTransition(VoiceConversationEngine.VoiceState.PAUSED, VoiceConversationEngine.VoiceState.RESUMING))
        assertTrue(sm.canTransition(VoiceConversationEngine.VoiceState.RESUMING, VoiceConversationEngine.VoiceState.SPEAKING))
        assertFalse(sm.canTransition(VoiceConversationEngine.VoiceState.IDLE, VoiceConversationEngine.VoiceState.SPEAKING))
    }

    @Test
    fun testSpeechTaskManagerQueueOrder() {
        val manager = VoiceConversationEngine.SpeechTaskManager()

        // Create mock tasks
        val task1 = createMockTask("Task 1")
        val task2 = createMockTask("Task 2")
        val task3 = createMockTask("Task 3")

        manager.start(task1)
        val interrupted1 = manager.interruptForBargeIn()
        assertNotNull(interrupted1)
        assertEquals("Task 1", interrupted1?.fullText)

        manager.start(task2)
        val interrupted2 = manager.interruptForBargeIn()
        assertNotNull(interrupted2)
        assertEquals("Task 2", interrupted2?.fullText)

        manager.start(task3)
        val interrupted3 = manager.interruptForBargeIn()
        assertNotNull(interrupted3)
        assertEquals("Task 3", interrupted3?.fullText)

        // Resume should be LIFO (newest first)
        val resumed1 = manager.resumeFromCheckpoint()
        assertNotNull(resumed1)
        assertEquals("Task 3", resumed1?.fullText)

        val resumed2 = manager.resumeFromCheckpoint()
        assertNotNull(resumed2)
        assertEquals("Task 2", resumed2?.fullText)

        val resumed3 = manager.resumeFromCheckpoint()
        assertNotNull(resumed3)
        assertEquals("Task 1", resumed3?.fullText)

        // Stack empty
        assertNull(manager.resumeFromCheckpoint())
    }

    @Test
    fun testSpeechTaskManagerCapacityLimit() {
        val manager = VoiceConversationEngine.SpeechTaskManager()

        // Fill beyond capacity (6)
        repeat(8) { i ->
            val task = createMockTask("Task $i")
            manager.start(task)
            manager.interruptForBargeIn()
        }

        // Should only have 6 (newest)
        val resumed = manager.resumeFromCheckpoint()
        assertNotNull(resumed)
        assertEquals("Task 7", resumed?.fullText) // 0-indexed, so 7 is the 8th

        // Drain all
        repeat(6) { manager.resumeFromCheckpoint() }
        assertNull(manager.resumeFromCheckpoint())
    }

    @Test
    fun testStopClearsContinuations() {
        val manager = VoiceConversationEngine.SpeechTaskManager()

        repeat(3) { i ->
            val task = createMockTask("Task $i")
            manager.start(task)
            manager.interruptForBargeIn()
        }

        manager.stopAndCancelAll()

        assertNull(manager.resumeFromCheckpoint())
    }

    private fun createMockTask(text: String): VoiceConversationEngine.SpeechTask {
        return VoiceConversationEngine.SpeechTask(
            id = java.util.UUID.randomUUID().toString(),
            fullText = text,
            segments = listOf(VoiceConversationEngine.SpeechSegment(text, 320)),
            currentSegmentIndex = 0,
            onSegmentComplete = { _, _ -> },
            onInterrupted = { _ -> },
            onCancelled = { },
            onError = { _ -> }
        )
    }
}
