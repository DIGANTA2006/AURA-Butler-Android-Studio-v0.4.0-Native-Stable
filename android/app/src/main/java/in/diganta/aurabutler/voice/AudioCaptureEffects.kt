package `in`.diganta.aurabutler.voice

import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor

/**
 * Optional platform audio effects attached to one AudioRecord session.
 *
 * The effects are never assumed to exist. Callers must inspect
 * [echoCancellationEnabled] before relying on echo-reduced speech detection.
 * The owner must close this object before releasing its AudioRecord.
 */
class AudioCaptureEffects private constructor(
    private val echoCanceler: AcousticEchoCanceler?,
    private val noiseSuppressor: NoiseSuppressor?
) : AutoCloseable {

    val echoCancellationEnabled: Boolean
        get() = echoCanceler?.enabled == true

    val noiseSuppressionEnabled: Boolean
        get() = noiseSuppressor?.enabled == true

    override fun close() {
        runCatching { echoCanceler?.release() }
        runCatching { noiseSuppressor?.release() }
    }

    companion object {
        /**
         * Attach the best effects available for this exact recording session.
         * A null/invalid session or unsupported device returns a no-effects
         * instance; callers must retain a safe non-AEC fallback.
         */
        fun attach(audioSessionId: Int): AudioCaptureEffects {
            if (audioSessionId <= 0) return AudioCaptureEffects(null, null)

            val aec = createEnabled {
                if (AcousticEchoCanceler.isAvailable()) {
                    AcousticEchoCanceler.create(audioSessionId)
                } else {
                    null
                }
            }
            val ns = createEnabled {
                if (NoiseSuppressor.isAvailable()) {
                    NoiseSuppressor.create(audioSessionId)
                } else {
                    null
                }
            }
            return AudioCaptureEffects(aec, ns)
        }

        private inline fun <T : android.media.audiofx.AudioEffect> createEnabled(
            factory: () -> T?
        ): T? {
            val effect = runCatching(factory).getOrNull() ?: return null
            val enabled = runCatching {
                effect.enabled = true
                effect.enabled
            }.getOrDefault(false)
            if (enabled) return effect
            runCatching { effect.release() }
            return null
        }
    }
}
