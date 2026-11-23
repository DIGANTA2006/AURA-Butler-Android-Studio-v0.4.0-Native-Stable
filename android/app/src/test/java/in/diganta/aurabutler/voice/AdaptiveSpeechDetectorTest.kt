package `in`.diganta.aurabutler.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveSpeechDetectorTest {
    @Test
    fun `learns quiet noise and detects a short command`() {
        val sampleRate = 16_000
        val detector = AdaptiveSpeechDetector(
            sampleRate = sampleRate,
            minimumThreshold = 0.0045f,
            startSpeechMs = 80,
            endSilenceMs = 650,
            initialWaitMs = 5_000,
            calibrationMs = 250
        )

        repeat(4) {
            assertEquals(
                AdaptiveSpeechDetector.Decision.CONTINUE,
                detector.observe(0.002f, 1_280)
            )
        }
        assertFalse(detector.heardSpeech)

        repeat(2) {
            detector.observe(0.030f, 1_280)
        }
        assertTrue(detector.heardSpeech)

        var decision = AdaptiveSpeechDetector.Decision.CONTINUE
        repeat(10) {
            decision = detector.observe(0.0015f, 1_280)
            if (decision == AdaptiveSpeechDetector.Decision.END_OF_SPEECH) return@repeat
        }
        assertEquals(AdaptiveSpeechDetector.Decision.END_OF_SPEECH, decision)
    }

    @Test
    fun `times out cleanly when no speech arrives`() {
        val detector = AdaptiveSpeechDetector(
            sampleRate = 16_000,
            initialWaitMs = 500,
            calibrationMs = 160
        )
        var decision = AdaptiveSpeechDetector.Decision.CONTINUE
        repeat(8) {
            decision = detector.observe(0.0015f, 1_280)
            if (decision == AdaptiveSpeechDetector.Decision.NO_SPEECH_TIMEOUT) return@repeat
        }
        assertEquals(AdaptiveSpeechDetector.Decision.NO_SPEECH_TIMEOUT, decision)
        assertFalse(detector.heardSpeech)
    }
}
