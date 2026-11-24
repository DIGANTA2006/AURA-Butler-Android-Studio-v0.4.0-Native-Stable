package `in`.diganta.aurabutler.voice

import org.junit.Assert.assertFalse
import org.junit.Test

class AudioCaptureEffectsTest {
    @Test
    fun invalidAudioSessionFallsBackWithoutEffects() {
        val effects = AudioCaptureEffects.attach(0)
        try {
            assertFalse(effects.echoCancellationEnabled)
            assertFalse(effects.noiseSuppressionEnabled)
        } finally {
            effects.close()
        }
    }

    @Test
    fun closingNoEffectSessionIsIdempotent() {
        val effects = AudioCaptureEffects.attach(-1)
        effects.close()
        effects.close()
        assertFalse(effects.echoCancellationEnabled)
    }
}
