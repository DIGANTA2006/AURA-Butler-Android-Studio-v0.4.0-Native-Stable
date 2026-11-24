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
    fun `rejects isolated bursts before accepting sustained barge-in speech`() {
        val detector = AdaptiveSpeechDetector(
            sampleRate = 16_000,
            minimumThreshold = 0.0085f,
            maximumThreshold = 0.065f,
            noiseMultiplier = 3.1f,
            startSpeechMs = 360,
            endSilenceMs = 720,
            initialWaitMs = 90_000,
            calibrationMs = 320
        )

        // Calibrate against the active speaker path after AEC is attached.
        repeat(4) { detector.observe(0.003f, 1_280) }

        // A short echo/transient must not cross the 360ms speech-start window.
        detector.observe(0.030f, 1_280)
        repeat(2) { detector.observe(0.003f, 1_280) }
        assertFalse(detector.heardSpeech)

        // Five sustained 80ms windows meet the configured start duration.
        repeat(5) { detector.observe(0.030f, 1_280) }
        assertTrue(detector.heardSpeech)

        var decision = AdaptiveSpeechDetector.Decision.CONTINUE
        repeat(9) {
            decision = detector.observe(0.002f, 1_280)
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
