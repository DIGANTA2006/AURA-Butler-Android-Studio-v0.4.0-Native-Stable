package `in`.diganta.aurabutler.voice

import kotlin.math.max

/**
 * Tiny adaptive voice-activity detector for AURA command capture.
 *
 * It learns the current room noise during the opening fraction of a second,
 * then requires a short run of speech before declaring speech present. This
 * is more reliable than one fixed RMS threshold in a quiet hostel room,
 * outdoors, or near a fan.
 */
class AdaptiveSpeechDetector(
    private val sampleRate: Int,
    private val minimumThreshold: Float = 0.0045f,
    private val maximumThreshold: Float = 0.040f,
    private val noiseMultiplier: Float = 2.7f,
    startSpeechMs: Int = 90,
    endSilenceMs: Int = 650,
    initialWaitMs: Int = 5_000,
    calibrationMs: Int = 280
) {
    private val startSpeechSamples = sampleRate * startSpeechMs / 1_000
    private val endSilenceSamples = sampleRate * endSilenceMs / 1_000
    private val initialWaitSamples = sampleRate * initialWaitMs / 1_000
    private val calibrationSamples = sampleRate * calibrationMs / 1_000

    private var totalSamples = 0
    private var speechCandidateSamples = 0
    private var silentSamples = 0
    private var noiseFloor = 0.0025f
    private var noiseObservations = 0

    var heardSpeech: Boolean = false
        private set

    val threshold: Float
        get() = max(minimumThreshold, noiseFloor * noiseMultiplier)
            .coerceAtMost(maximumThreshold)

    fun observe(rms: Float, sampleCount: Int): Decision {
        if (sampleCount <= 0) return Decision.CONTINUE
        totalSamples += sampleCount

        if (!heardSpeech) {
            // Learn background sound aggressively at the beginning and slowly
            // afterwards, but never let a loud speech burst redefine "noise".
            val canLearnNoise = totalSamples <= calibrationSamples || rms < threshold * 0.85f
            if (canLearnNoise) {
                val alpha = if (noiseObservations < 8) 0.28f else 0.08f
                noiseFloor = if (noiseObservations == 0) {
                    rms.coerceAtLeast(0.001f)
                } else {
                    noiseFloor * (1f - alpha) + rms * alpha
                }
                noiseObservations += 1
            }

            if (rms >= threshold) {
                speechCandidateSamples += sampleCount
                if (speechCandidateSamples >= startSpeechSamples) {
                    heardSpeech = true
                    silentSamples = 0
                }
            } else {
                speechCandidateSamples = 0
            }

            if (!heardSpeech && totalSamples >= initialWaitSamples) {
                return Decision.NO_SPEECH_TIMEOUT
            }
            return Decision.CONTINUE
        }

        val silenceThreshold = max(minimumThreshold * 0.85f, noiseFloor * 1.75f)
            .coerceAtMost(maximumThreshold)
        if (rms < silenceThreshold) {
            silentSamples += sampleCount
        } else {
            silentSamples = 0
        }
        return if (silentSamples >= endSilenceSamples) {
            Decision.END_OF_SPEECH
        } else {
            Decision.CONTINUE
        }
    }

    enum class Decision {
        CONTINUE,
        END_OF_SPEECH,
        NO_SPEECH_TIMEOUT
    }
}
