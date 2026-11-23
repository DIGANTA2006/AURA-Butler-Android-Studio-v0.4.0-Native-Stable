package `in`.diganta.aurabutler.voice

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Fast Android system speech used for wake-word/background replies.
 *
 * It exposes an interruptible speech session so the wake service can keep a
 * lightweight keyword detector active while AURA talks. The bundled Piper
 * voice remains the private fallback when Android system TTS is unavailable.
 */
class FastSystemSpeaker(context: Context) : TextToSpeech.OnInitListener {
    private val appContext = context.applicationContext
    private val ready = AtomicBoolean(false)
    private val readyLatch = CountDownLatch(1)
    private val sessions = ConcurrentHashMap<String, SpeechSession>()
    private val tts = TextToSpeech(context.applicationContext, this)

    override fun onInit(status: Int) {
        try {
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale.UK
                tts.setSpeechRate(1.02f)
                tts.setPitch(0.82f)
                chooseOfflineMaleVoice()
                tts.setOnUtteranceProgressListener(
                    object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            if (utteranceId != null) {
                                VoiceTimingDiagnostics.markStarted(
                                    appContext,
                                    utteranceId
                                )
                            }
                        }

                        override fun onRangeStart(
                            utteranceId: String?,
                            start: Int,
                            end: Int,
                            frame: Int
                        ) {
                            if (utteranceId == null) return
                            sessions[utteranceId]?.updateProgress(start)
                        }

                        override fun onDone(utteranceId: String?) {
                            finish(utteranceId, completedNormally = true)
                        }

                        override fun onStop(
                            utteranceId: String?,
                            interrupted: Boolean
                        ) {
                            finish(
                                utteranceId,
                                completedNormally = !interrupted
                            )
                        }

                        @Deprecated(
                            "Deprecated by Android but still required by the base class"
                        )
                        override fun onError(utteranceId: String?) {
                            finish(utteranceId, completedNormally = false)
                        }
                    }
                )
                ready.set(true)
                Log.i(TAG, "Fast Android male voice is ready")
            } else {
                Log.w(TAG, "Android TextToSpeech initialization failed: $status")
            }
        } finally {
            readyLatch.countDown()
        }
    }

    fun startSpeech(
        text: String,
        languageTag: String = "auto"
    ): SpeechSession? {
        val clean = SpeechTextSanitizer.clean(text, languageTag)
            .take(MAX_CHARACTERS)

        if (clean.isBlank()) {
            return SpeechSession.completed(this, clean)
        }

        if (!ready.get()) {
            runCatching {
                readyLatch.await(
                    READY_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
                )
            }
        }
        if (!ready.get()) return null

        stop()

        val resolvedLanguage = SpeechLanguageSupport.resolveLanguageTag(
            clean,
            languageTag
        )
        if (!SpeechLanguageSupport.isAvailable(tts, resolvedLanguage)) return null
        applyVoice(resolvedLanguage)

        val utteranceId = "aura-fast-${UUID.randomUUID()}"
        VoiceTimingDiagnostics.begin(
            appContext,
            clean,
            "Android TTS background",
            utteranceId
        )
        val session = SpeechSession(
            owner = this,
            utteranceId = utteranceId,
            text = clean
        )
        sessions[utteranceId] = session

        val result = runCatching {
            tts.speak(
                clean,
                TextToSpeech.QUEUE_FLUSH,
                Bundle(),
                utteranceId
            )
        }.getOrDefault(TextToSpeech.ERROR)

        if (result == TextToSpeech.ERROR) {
            finish(utteranceId, completedNormally = false)
            return null
        }

        return session
    }

    fun speakAndWait(
        text: String,
        languageTag: String = "auto"
    ): Boolean {
        val session = startSpeech(text, languageTag) ?: return false
        if (session.isFinished()) return true

        val timeoutSeconds = (
            MIN_SPEECH_TIMEOUT_SECONDS +
                session.text.length / 8
            )
            .coerceAtMost(MAX_SPEECH_TIMEOUT_SECONDS)
            .toLong()

        val finished = session.await(
            timeoutSeconds,
            TimeUnit.SECONDS
        )

        if (!finished) {
            session.stop()
        }

        return finished && session.completedNormally()
    }

    fun stop() {
        runCatching { tts.stop() }

        sessions.values.toList().forEach { session ->
            session.finish(completedNormally = false)
        }
        sessions.clear()
    }

    fun shutdown() {
        stop()
        runCatching { tts.shutdown() }
    }

    private fun stopSession(session: SpeechSession) {
        if (!sessions.containsKey(session.utteranceId)) {
            session.finish(completedNormally = false)
            return
        }

        runCatching { tts.stop() }
        finish(
            session.utteranceId,
            completedNormally = false
        )
    }

    private fun finish(
        utteranceId: String?,
        completedNormally: Boolean
    ) {
        if (utteranceId == null) return
        sessions.remove(utteranceId)
            ?.let { session ->
                VoiceTimingDiagnostics.complete(
                    appContext,
                    utteranceId,
                    completedNormally = completedNormally
                )
                session.finish(completedNormally)
            }
    }

    private fun chooseOfflineMaleVoice() {
        applyVoice(SpeechLanguageSupport.ENGLISH)
    }

    private fun applyVoice(languageTag: String) {
        val locale = SpeechLanguageSupport.localeFor(languageTag)
        tts.language = locale
        tts.setSpeechRate(
            if (languageTag == SpeechLanguageSupport.ENGLISH) 1.02f else 0.98f
        )
        tts.setPitch(0.84f)
        SpeechLanguageSupport.bestVoice(tts, languageTag)?.let { preferred ->
            tts.voice = preferred
            Log.i(TAG, "Selected AURA speech voice: ${preferred.name}")
        }
    }

    class SpeechSession internal constructor(
        private val owner: FastSystemSpeaker,
        internal val utteranceId: String,
        val text: String
    ) {
        private val done = CountDownLatch(1)
        private val progress = AtomicInteger(0)
        private val completed = AtomicBoolean(false)
        private val normalCompletion = AtomicBoolean(false)
        private val startedAt = SystemClock.elapsedRealtime()

        fun isFinished(): Boolean = done.count == 0L

        fun completedNormally(): Boolean =
            completed.get() && normalCompletion.get()

        fun await(
            timeout: Long,
            unit: TimeUnit
        ): Boolean = done.await(timeout, unit)

        fun stop() {
            owner.stopSession(this)
        }

        fun remainingText(): String =
            SpeechTextSanitizer.remainingAfter(
                text,
                effectiveProgress()
            )

        fun isSpeakingWakeWord(): Boolean =
            SpeechTextSanitizer.containsWakeWordNear(
                text,
                effectiveProgress()
            )

        internal fun updateProgress(index: Int) {
            progress.updateAndGet { current ->
                maxOf(current, index.coerceAtLeast(0))
            }
        }

        internal fun finish(completedNormally: Boolean) {
            if (completed.compareAndSet(false, true)) {
                normalCompletion.set(completedNormally)
                done.countDown()
            }
        }

        private fun effectiveProgress(): Int {
            val reported = progress.get()
            if (reported > 0) return reported

            val elapsedMs = (
                SystemClock.elapsedRealtime() - startedAt
            ).coerceAtLeast(0L)

            val estimated = (
                elapsedMs * ESTIMATED_CHARACTERS_PER_SECOND / 1_000L
            ).toInt()

            return estimated.coerceIn(0, text.length)
        }

        companion object {
            internal fun completed(
                owner: FastSystemSpeaker,
                text: String
            ): SpeechSession = SpeechSession(
                owner = owner,
                utteranceId = "aura-empty",
                text = text
            ).apply {
                finish(completedNormally = true)
            }
        }
    }

    private companion object {
        const val TAG = "AuraFastSystemTts"
        const val MAX_CHARACTERS = 1_500
        const val READY_TIMEOUT_SECONDS = 4L
        const val MIN_SPEECH_TIMEOUT_SECONDS = 8
        const val MAX_SPEECH_TIMEOUT_SECONDS = 90
        const val ESTIMATED_CHARACTERS_PER_SECOND = 13L
    }
}
