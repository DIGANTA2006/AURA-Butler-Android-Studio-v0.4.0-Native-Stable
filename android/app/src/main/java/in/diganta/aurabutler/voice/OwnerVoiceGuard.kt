package `in`.diganta.aurabutler.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioRecord
import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import `in`.diganta.aurabutler.data.SecureStore
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.sqrt
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Local owner-speaker verification for AURA voice control.
 *
 * Only a compact speaker embedding is stored, inside AURA's encrypted store.
 * Raw enrollment audio is never persisted. Verification runs fully on-device.
 *
 * This is a convenience/security gate, not a bank-grade biometric. A high-quality
 * replay/deepfake may fool speaker-verification models, so consequential Android
 * actions still go through AURA's existing confirmation/safety policies.
 */
object OwnerVoiceGuard {
    const val MODEL_ASSET =
        "voice/speaker/3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx"
    const val SAMPLE_RATE = 16_000
    const val DEFAULT_THRESHOLD = 0.60f
    private const val MIN_SAMPLE_SECONDS = 1.25f
    private const val MAX_CAPTURE_SECONDS = 7
    private const val ENROLLMENT_SAMPLES = 5
    private const val MIN_VOICED_SECONDS = 0.95f
    private const val PROFILE_SCHEMA_VERSION = 2
    private const val PROFILE_META_PREFS = "aura_owner_voice_meta"
    private const val PROFILE_META_KEY = "schema_version"
    private const val TAG = "AuraOwnerVoiceGuard"

    private val lock = Any()
    private val extractorRef = AtomicReference<SpeakerEmbeddingExtractor?>(null)

    data class VerificationResult(
        val accepted: Boolean,
        val score: Float,
        val reason: String
    )

    data class EnrollmentResult(
        val success: Boolean,
        val score: Float = 0f,
        val message: String
    )

    fun modelInstalled(context: Context): Boolean = runCatching {
        context.assets.open(MODEL_ASSET).use { true }
    }.getOrDefault(false)

    fun isEnrolled(context: Context): Boolean =
        profileSchemaReady(context) &&
            SecureStore(context).loadOwnerVoiceEmbedding()?.isNotEmpty() == true

    fun clearEnrollment(context: Context) {
        SecureStore(context).clearOwnerVoiceEmbedding()
        context.applicationContext
            .getSharedPreferences(PROFILE_META_PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(PROFILE_META_KEY)
            .apply()
    }

    fun verify(
        context: Context,
        samples: FloatArray,
        threshold: Float = DEFAULT_THRESHOLD
    ): VerificationResult {
        if (!profileSchemaReady(context)) {
            return VerificationResult(
                false,
                0f,
                "Owner voice profile needs to be registered again after the voice-reliability update."
            )
        }
        val reference = SecureStore(context).loadOwnerVoiceEmbedding()
            ?: return VerificationResult(false, 0f, "Owner voice is not enrolled.")
        return verifyAgainst(reference, context, samples, threshold)
    }

    fun verifyAgainst(
        reference: FloatArray,
        context: Context,
        samples: FloatArray,
        threshold: Float = DEFAULT_THRESHOLD
    ): VerificationResult {
        val prepared = prepareSpeakerSample(samples)
            ?: return VerificationResult(
                false,
                0f,
                "Owner verification needs a clearer 1–4 second speech sample with less background noise."
            )
        val query = runCatching { computeEmbedding(context, prepared) }
            .getOrElse { error ->
                Log.e(TAG, "Speaker verification failed", error)
                return VerificationResult(false, 0f, "Speaker verification failed locally.")
            }
        if (query.isEmpty() || query.size != reference.size) {
            return VerificationResult(false, 0f, "Owner voice profile is incompatible with this model.")
        }
        val score = cosine(reference, query)
        val effectiveThreshold = maxOf(
            DEFAULT_THRESHOLD,
            threshold.coerceIn(DEFAULT_THRESHOLD, 0.85f)
        )
        return VerificationResult(
            accepted = score >= effectiveThreshold,
            score = score,
            reason = if (score >= effectiveThreshold) {
                "Owner voice matched."
            } else {
                "Voice did not match the enrolled owner."
            }
        )
    }

    /**
     * Records three short samples and stores only their averaged normalized embedding.
     * Progress text is safe to surface directly in the Settings UI.
     */
    suspend fun enrollFromMicrophone(
        context: Context,
        onProgress: (String) -> Unit = {}
    ): EnrollmentResult = withContext(Dispatchers.IO) {
        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return@withContext EnrollmentResult(false, message = "Microphone permission is required to register your voice.")
        }
        if (!modelInstalled(context)) {
            return@withContext EnrollmentResult(false, message = "Owner voice model is missing from the APK.")
        }

        val enrollmentPhrases = listOf(
            "Aura, this is Diganta. Only accept my voice.",
            "Hey Aura, listen to my voice and follow only my commands.",
            "OK Aura, this is my private voice profile.",
            "Aura, remember the natural sound of my voice.",
            "Hey Aura, verify that this command is really spoken by me."
        )
        val embeddings = mutableListOf<FloatArray>()
        repeat(ENROLLMENT_SAMPLES) { index ->
            onProgress(
                "Owner voice sample ${index + 1}/$ENROLLMENT_SAMPLES: after the tone say, “${enrollmentPhrases[index]}”"
            )
            playEnrollmentTone()
            delay(280L)
            val audio = captureSpeechSample(context)
            val prepared = prepareSpeakerSample(audio)
                ?: return@withContext EnrollmentResult(
                    false,
                    message = "Sample ${index + 1} was too short or noisy. Register again in a quieter place and speak clearly for about 2–4 seconds."
                )
            val embedding = runCatching { computeEmbedding(context, prepared) }
                .getOrElse { error ->
                    Log.e(TAG, "Enrollment embedding failed", error)
                    return@withContext EnrollmentResult(false, message = "Could not create the owner voice profile on this phone.")
                }
            embeddings += normalize(embedding)
            onProgress("Captured owner voice sample ${index + 1}/$ENROLLMENT_SAMPLES.")
            delay(450L)
        }

        // Reject obviously inconsistent enrollment samples instead of saving a weak profile.
        val pairScores = buildList {
            for (i in embeddings.indices) {
                for (j in i + 1 until embeddings.size) add(cosine(embeddings[i], embeddings[j]))
            }
        }
        val consistency = pairScores.average().toFloat()
        if (pairScores.isNotEmpty() && consistency < 0.55f) {
            return@withContext EnrollmentResult(
                false,
                score = consistency,
                message = "The three samples did not sound consistent enough. Register again in a quieter place."
            )
        }

        val averaged = FloatArray(embeddings.first().size)
        embeddings.forEach { embedding ->
            embedding.indices.forEach { index -> averaged[index] += embedding[index] }
        }
        for (index in averaged.indices) averaged[index] /= embeddings.size.toFloat()
        val profile = normalize(averaged)
        SecureStore(context).saveOwnerVoiceEmbedding(profile)
        context.applicationContext
            .getSharedPreferences(PROFILE_META_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(PROFILE_META_KEY, PROFILE_SCHEMA_VERSION)
            .apply()

        EnrollmentResult(
            success = true,
            score = consistency,
            message = "Owner voice registered locally. AURA will now reject spoken commands that do not match you."
        )
    }

    private fun computeEmbedding(context: Context, samples: FloatArray): FloatArray = synchronized(lock) {
        val extractor = extractorRef.get() ?: SpeakerEmbeddingExtractor(
            assetManager = context.assets,
            config = SpeakerEmbeddingExtractorConfig(
                model = MODEL_ASSET,
                numThreads = 2,
                debug = false,
                provider = "cpu"
            )
        ).also(extractorRef::set)

        val stream = extractor.createStream()
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            stream.inputFinished()
            check(extractor.isReady(stream)) { "Speaker embedding model was not ready for this audio sample." }
            return@synchronized normalize(extractor.compute(stream))
        } finally {
            stream.release()
        }
    }

    private fun captureSpeechSample(context: Context): FloatArray {
        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("Microphone permission is required for owner voice enrollment.")
        }

        val recorder = try {
            VoiceAudioRecorder.createForCommand(SAMPLE_RATE)
        } catch (error: SecurityException) {
            throw SecurityException(
                "Android denied microphone access during owner voice enrollment.",
                error
            )
        }
        val chunks = ArrayList<FloatArray>()
        val shorts = ShortArray(1_280)
        var totalSamples = 0
        var readFailures = 0
        val maxSamples = SAMPLE_RATE * MAX_CAPTURE_SECONDS
        val detector = AdaptiveSpeechDetector(
            sampleRate = SAMPLE_RATE,
            minimumThreshold = 0.0045f,
            startSpeechMs = 100,
            endSilenceMs = 850,
            initialWaitMs = 5_000,
            calibrationMs = 260
        )

        try {
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "Android did not start the enrollment microphone."
            }

            while (totalSamples < maxSamples) {
                val count = recorder.read(shorts, 0, shorts.size)
                if (count <= 0) {
                    if (++readFailures >= 8) break
                    continue
                }
                readFailures = 0
                val floats = FloatArray(count) { shorts[it] / 32768.0f }
                chunks += floats
                totalSamples += count

                var square = 0.0
                for (sample in floats) square += sample * sample
                val rms = sqrt(square / count).toFloat()
                when (detector.observe(rms, count)) {
                    AdaptiveSpeechDetector.Decision.END_OF_SPEECH,
                    AdaptiveSpeechDetector.Decision.NO_SPEECH_TIMEOUT -> break
                    AdaptiveSpeechDetector.Decision.CONTINUE -> Unit
                }
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }

        if (!detector.heardSpeech) return FloatArray(0)
        val merged = FloatArray(totalSamples)
        var offset = 0
        chunks.forEach { chunk ->
            chunk.copyInto(merged, offset)
            offset += chunk.size
        }
        return merged
    }

    private fun profileSchemaReady(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PROFILE_META_PREFS, Context.MODE_PRIVATE)
            .getInt(PROFILE_META_KEY, 0) == PROFILE_SCHEMA_VERSION

    /**
     * Speaker embeddings are unreliable when a sample is mostly silence/noise or is
     * extremely short. Estimate the local noise floor, require enough voiced audio,
     * trim excess silence, and normalize level without preserving any raw audio.
     */
    private fun prepareSpeakerSample(samples: FloatArray): FloatArray? {
        if (samples.size < (SAMPLE_RATE * MIN_SAMPLE_SECONDS).toInt()) return null

        val frameSize = 320 // 20 ms at 16 kHz
        val frameCount = samples.size / frameSize
        if (frameCount < 10) return null

        val rmsValues = FloatArray(frameCount)
        val peakValues = FloatArray(frameCount)
        for (frameIndex in 0 until frameCount) {
            val start = frameIndex * frameSize
            var square = 0.0
            var peak = 0f
            for (i in 0 until frameSize) {
                val value = samples[start + i]
                square += value * value
                peak = maxOf(peak, kotlin.math.abs(value))
            }
            rmsValues[frameIndex] = sqrt(square / frameSize).toFloat()
            peakValues[frameIndex] = peak
        }

        val sorted = rmsValues.sorted()
        val floorIndex = ((sorted.size - 1) * 0.15f).toInt().coerceIn(0, sorted.lastIndex)
        val noiseFloor = sorted[floorIndex].coerceAtLeast(0.0015f)
        val voicedThreshold = maxOf(0.0060f, noiseFloor * 2.15f)

        val voicedFrames = buildList {
            for (i in rmsValues.indices) {
                if (rmsValues[i] >= voicedThreshold && peakValues[i] >= 0.018f) add(i)
            }
        }
        val minimumVoicedFrames =
            ((MIN_VOICED_SECONDS * SAMPLE_RATE) / frameSize).toInt()
        if (voicedFrames.size < minimumVoicedFrames) return null

        val firstFrame = (voicedFrames.first() - 2).coerceAtLeast(0)
        val lastFrame = (voicedFrames.last() + 2).coerceAtMost(frameCount - 1)
        val startSample = firstFrame * frameSize
        val endSample = minOf(samples.size, (lastFrame + 1) * frameSize)
        if (endSample - startSample < (SAMPLE_RATE * MIN_SAMPLE_SECONDS).toInt()) return null

        val trimmed = samples.copyOfRange(startSample, endSample)
        var square = 0.0
        var peak = 0f
        var clipped = 0
        for (value in trimmed) {
            square += value * value
            peak = maxOf(peak, kotlin.math.abs(value))
            if (kotlin.math.abs(value) >= 0.985f) clipped += 1
        }
        val rms = sqrt(square / trimmed.size).toFloat()
        if (rms < 0.0060f || peak < 0.030f) return null
        if (clipped > trimmed.size / 100) return null

        val gain = (0.055f / rms).coerceIn(0.70f, 2.20f)
        return FloatArray(trimmed.size) { index ->
            (trimmed[index] * gain).coerceIn(-1.0f, 1.0f)
        }
    }

    private fun playEnrollmentTone() {
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 72)
            try {
                tone.startTone(ToneGenerator.TONE_PROP_ACK, 100)
                Thread.sleep(125L)
            } finally {
                // Release the native tone generator even if startTone() throws.
                tone.release()
            }
        }
    }

    private fun normalize(vector: FloatArray): FloatArray {
        var sum = 0.0
        for (value in vector) sum += value * value
        val norm = sqrt(sum).toFloat().takeIf { it > 1e-8f } ?: return vector.copyOf()
        return FloatArray(vector.size) { index -> vector[index] / norm }
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0.0
        var aa = 0.0
        var bb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            aa += a[i] * a[i]
            bb += b[i] * b[i]
        }
        val denom = sqrt(aa) * sqrt(bb)
        return if (denom <= 1e-10) 0f else (dot / denom).toFloat().coerceIn(-1f, 1f)
    }
}
