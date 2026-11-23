package `in`.diganta.aurabutler.background

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.AudioRecord
import android.media.ToneGenerator
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import `in`.diganta.aurabutler.MainActivity
import `in`.diganta.aurabutler.R
import `in`.diganta.aurabutler.data.SecureStore
import `in`.diganta.aurabutler.runtime.AuraRuntimeState
import `in`.diganta.aurabutler.runtime.AuraRuntimeStateMachine
import `in`.diganta.aurabutler.voice.AdaptiveSpeechDetector
import `in`.diganta.aurabutler.voice.AuraVoiceRuntime
import `in`.diganta.aurabutler.voice.FastSystemSpeaker
import `in`.diganta.aurabutler.voice.OfflineWhisperEngine
import `in`.diganta.aurabutler.voice.OwnerVoiceGuard
import `in`.diganta.aurabutler.voice.SpeechTextSanitizer
import `in`.diganta.aurabutler.voice.VoiceAudioRecorder
import `in`.diganta.aurabutler.voice.VoiceDiagnostics
import `in`.diganta.aurabutler.voice.VoiceCommandClassifier
import `in`.diganta.aurabutler.voice.SpeechContinuationStack
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Always-visible foreground microphone service for local wake-word detection.
 *
 * Native KWS and Whisper remain serialized. Android system TTS may run beside
 * a lightweight wake detector so the owner can say “Aura”, “Hey Aura”, or “OK Aura” to
 * interrupt a reply. Piper remains a serialized fallback, preserving the
 * native use-after-free protection from the original Android build.
 */
class WakeWordService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var processor: BackgroundCommandProcessor
    private lateinit var notifications: NotificationManager
    private lateinit var fastSpeaker: FastSystemSpeaker
    private val store by lazy(LazyThreadSafetyMode.NONE) { SecureStore(this) }

    @Volatile
    private var running = false

    @Volatile
    private var manualCaptureRequested = false

    private val pendingSpeech = AtomicReference<String?>(null)
    private val speechContinuations = SpeechContinuationStack(MAX_PENDING_SPEECH_CONTINUATIONS)

    @Volatile
    private var speechPaused = false

    @Volatile
    private var activeRecorder: AudioRecord? = null

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        processor = BackgroundCommandProcessor(this)
        notifications = getSystemService(NotificationManager::class.java)
        fastSpeaker = FastSystemSpeaker(this)
        AuraRuntimeStateMachine.startIfNeeded("wake service created")
        createChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                store.saveSettings(store.loadSettings().copy(wakeWordEnabled = false))
                stopListening()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_CAPTURE_NOW -> manualCaptureRequested = true

            ACTION_SPEAK_TEXT -> {
                intent?.getStringExtra(EXTRA_SPEECH)
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
                    ?.let(pendingSpeech::set)
            }
        }

        val settings = store.loadSettings()
        val wakeEnabled = settings.wakeWordEnabled
        val hasManualTask = manualCaptureRequested || pendingSpeech.get() != null

        if (!wakeEnabled && !hasManualTask) {
            completeForegroundHandshakeAndStop(
                status = "Wake listening is disabled",
                diagnosticState = "disabled"
            )
            return START_NOT_STICKY
        }

        val needsMicrophone = wakeEnabled || manualCaptureRequested
        if (
            needsMicrophone &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            updateRecoveryNotice("Microphone permission is required for wake listening.")
            completeForegroundHandshakeAndStop(
                status = "Microphone permission is required",
                diagnosticState = "microphone_permission_missing"
            )
            return START_NOT_STICKY
        }

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification("Starting AURA's private wake listener…"),
            if (needsMicrophone && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                0
            }
        )

        if (wakeEnabled) acquireWakeLock()
        markServiceState(true, if (wakeEnabled) "starting" else "manual_task")

        if (!running) {
            running = true
            scope.launch { listeningLoop() }
        } else if (hasManualTask) {
            // Interrupt a blocking read. The IO coroutine remains the only
            // owner that releases AudioRecord and native KWS objects.
            runCatching { activeRecorder?.stop() }
        }

        return if (wakeEnabled) START_STICKY else START_NOT_STICKY
    }

    override fun onDestroy() {
        stopListening()
        releaseWakeLock()
        if (::fastSpeaker.isInitialized) fastSpeaker.shutdown()
        scope.cancel()
        markServiceState(false, "stopped")
        VoiceDiagnostics.mark(this, VoiceDiagnostics.IDLE)
        AuraRuntimeStateMachine.forceIdle("wake service destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun listeningLoop() {
        var consecutiveFailures = 0

        try {
            while (running) {
                try {
                    val speechToSay = pendingSpeech.getAndSet(null)
                    if (speechToSay != null) {
                        updateNotification("AURA is speaking")
                        AuraRuntimeStateMachine.safeTransition(
                            AuraRuntimeState.SPEAKING,
                            "speaking queued reply"
                        )
                        speakFast(speechToSay)
                        AuraRuntimeStateMachine.forceIdleUnlessListening("speech finished")
                        if (!shouldKeepListening()) {
                            running = false
                            break
                        }
                    }

                    if (!running) break

                    val wakeEnabled = store.loadSettings().wakeWordEnabled
                    if (!wakeEnabled && !manualCaptureRequested) {
                        running = false
                        break
                    }

                    val woke = if (manualCaptureRequested) {
                        true
                    } else {
                        listenForOneWakeCycle()
                    }

                    if (!running) break
                    if (!woke) continue

                    manualCaptureRequested = false
                    consecutiveFailures = 0
                    markServiceState(true, "wake_detected")
                    updateNotification("Aura heard you • speak after the tone")
                    playReadyTone()
                    delay(105)

                    VoiceDiagnostics.mark(this, VoiceDiagnostics.CAPTURING_COMMAND)
                    AuraRuntimeStateMachine.safeTransition(
                        AuraRuntimeState.LISTENING,
                        "capturing command"
                    )
                    val speechSettings = store.loadSettings()

                    // The KWS native object is already released. Load Whisper
                    // while the user is speaking to reduce perceived latency.
                    AuraVoiceRuntime.prepareWhisper(speechSettings.speechLanguage)
                    val samples = captureCommand()

                    if (!running) {
                        AuraVoiceRuntime.discardPreparedWhisper()
                        break
                    }
                    if (pendingSpeech.get() != null) {
                        AuraVoiceRuntime.discardPreparedWhisper()
                        continue
                    }
                    if (samples.size < SAMPLE_RATE / 3) {
                        AuraVoiceRuntime.discardPreparedWhisper()
                        speakIfEnabled("I did not hear a command.")
                        continue
                    }

                    if (speechSettings.ownerVoiceLockEnabled) {
                        updateNotification("Verifying owner voice locally…")
                        val verification = OwnerVoiceGuard.verify(
                            context = this,
                            samples = samples,
                            threshold = speechSettings.ownerVoiceThreshold
                        )
                        if (!verification.accepted) {
                            AuraVoiceRuntime.discardPreparedWhisper()
                            Log.w(TAG, "Rejected wake command: speakerScore=${verification.score}")
                            markServiceState(true, "owner_voice_rejected")
                            updateNotification(
                                "${verification.reason} • listening for Aura / Hey Aura / OK Aura"
                            )
                            playRejectTone()
                            AuraRuntimeStateMachine.forceIdleUnlessListening(
                                "unrecognized speaker rejected"
                            )
                            continue
                        }
                    }

                    updateNotification("Understanding your command privately…")
                    AuraRuntimeStateMachine.safeTransition(
                        AuraRuntimeState.THINKING,
                        "transcribing command"
                    )
                    val transcript = AuraVoiceRuntime.transcribe(
                        samples = samples,
                        languageTag = speechSettings.speechLanguage
                    )
                    Log.i(TAG, "Command transcript length=${transcript.length}")

                    when (VoiceCommandClassifier.controlIntent(transcript)) {
                        VoiceCommandClassifier.ControlIntent.STOP_LISTENING -> {
                            speechContinuations.clear()
                            speechPaused = false
                            store.saveSettings(store.loadSettings().copy(wakeWordEnabled = false))
                            speakIfEnabled("Wake listening is now off.")
                            sendBroadcast(
                                Intent(ACTION_STATE_CHANGED).setPackage(packageName)
                            )
                            running = false
                        }

                        VoiceCommandClassifier.ControlIntent.STOP_SPEECH -> {
                            speechContinuations.clear()
                            speechPaused = false
                            updateNotification("Speech queue cleared")
                        }

                        VoiceCommandClassifier.ControlIntent.PAUSE_SPEECH -> {
                            if (speechContinuations.size() > 0) {
                                speechPaused = true
                                updateNotification("Speech paused • say continue to resume")
                            } else {
                                speakIfEnabled("There is no paused reply to pause.")
                            }
                        }

                        VoiceCommandClassifier.ControlIntent.RESUME_SPEECH -> {
                            speechPaused = false
                            val continuation = speechContinuations.pop()
                            if (continuation != null) {
                                AuraRuntimeStateMachine.safeTransition(
                                    AuraRuntimeState.SPEAKING,
                                    "resuming paused speech"
                                )
                                speakFast(continuation)
                            } else {
                                speakIfEnabled("There is no paused reply to resume.")
                            }
                        }

                        VoiceCommandClassifier.ControlIntent.NONE -> {
                            val assessment = VoiceCommandClassifier.assess(transcript)
                            if (!assessment.isComplete) {
                                Log.i(TAG, "Deferred incomplete voice command: ${assessment.reason}")
                                speakIfEnabled(
                                    "Your instruction seems incomplete. Please finish your command."
                                )
                            } else {
                                updateNotification("Handling: ${transcript.take(70)}")
                                val result = processor.process(transcript)
                                if (result.shouldSpeak) {
                                    AuraRuntimeStateMachine.safeTransition(
                                        AuraRuntimeState.SPEAKING,
                                        "speaking command result"
                                    )
                                    speakFast(result.speech)
                                }
                            }
                        }
                    }

                    AuraRuntimeStateMachine.forceIdleUnlessListening("wake turn complete")
                    if (running && store.loadSettings().wakeWordEnabled) {
                        markServiceState(true, "listening")
                    }
                } catch (error: SecurityException) {
                    Log.e(TAG, "Wake listener lost microphone permission", error)
                    store.saveSettings(store.loadSettings().copy(wakeWordEnabled = false))
                    updateRecoveryNotice(
                        "AURA lost microphone permission. Open AURA and enable wake listening again."
                    )
                    sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(packageName))
                    running = false
                } catch (error: Exception) {
                    consecutiveFailures += 1
                    Log.e(TAG, "Wake listening cycle failed ($consecutiveFailures)", error)
                    releaseRecorder()
                    AuraVoiceRuntime.discardPreparedWhisper()

                    if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                        updateRecoveryNotice(
                            "Wake listening paused after repeated errors: " +
                                (error.message?.take(120) ?: "voice runtime error") +
                                ". Open AURA and switch wake listening off and on."
                        )
                        updateNotification("Wake listener paused • open AURA to restart")
                        markServiceState(false, "error")
                        running = false
                    } else {
                        updateNotification("Wake listener recovering…")
                        markServiceState(true, "recovering")
                        delay(RETRY_DELAY_MS)
                    }
                }
            }
        } finally {
            releaseRecorder()
            AuraVoiceRuntime.discardPreparedWhisper()
            releaseWakeLock()
            VoiceDiagnostics.mark(this, VoiceDiagnostics.IDLE)
            running = false
            markServiceState(false, "stopped")
            stopSelf()
        }
    }

    /** Loads and releases KWS on the same IO coroutine. */
    private fun listenForOneWakeCycle(): Boolean {
        VoiceDiagnostics.mark(this, VoiceDiagnostics.LOADING_WAKE_MODEL)
        val spotter = createKeywordSpotter()

        return try {
            updateNotification("Listening for Aura / Hey Aura / OK Aura • tap Talk now if needed")
            markServiceState(true, "listening")
            VoiceDiagnostics.mark(this, VoiceDiagnostics.WAKE_LISTENING)
            AuraRuntimeStateMachine.safeTransition(
                AuraRuntimeState.LISTENING,
                "waiting for wake word"
            )
            listenForWakeWord(spotter)
        } finally {
            spotter.release()
            VoiceDiagnostics.mark(this, VoiceDiagnostics.IDLE)
        }
    }

    private fun createKeywordSpotter(): KeywordSpotter {
        val directory = "voice/kws"

        return KeywordSpotter(
            assetManager = assets,
            config = KeywordSpotterConfig(
                featConfig = FeatureConfig(
                    sampleRate = SAMPLE_RATE,
                    featureDim = 80
                ),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = "$directory/encoder.int8.onnx",
                        decoder = "$directory/decoder.int8.onnx",
                        joiner = "$directory/joiner.int8.onnx"
                    ),
                    tokens = "$directory/tokens.txt",
                    numThreads = KWS_THREADS,
                    debug = false,
                    provider = "cpu"
                ),
                maxActivePaths = 16,
                keywordsFile = "$directory/aura_keywords.txt",
                // Per-keyword values in the file override these defaults.
                keywordsScore = 6.0f,
                keywordsThreshold = 0.024f,
                numTrailingBlanks = 1
            )
        )
    }

    private fun listenForWakeWord(spotter: KeywordSpotter): Boolean {
        val recorder = createWakeRecorder()
        val stream = spotter.createStream()
        val buffer = ShortArray(WAKE_BUFFER_SAMPLES)
        var readFailures = 0
        var lastHeartbeat = 0L
        var recentSpeechEvidenceUntil = 0L
        val source = VoiceAudioRecorder.sourceName(recorder.audioSource)

        return try {
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "Android did not start AURA's wake microphone."
            }
            Log.i(TAG, "Wake microphone started with source=$source")

            while (running) {
                if (manualCaptureRequested) return true
                if (pendingSpeech.get() != null) return false

                val count = recorder.read(buffer, 0, buffer.size)
                if (count <= 0) {
                    if (manualCaptureRequested) return true
                    if (pendingSpeech.get() != null || !running) return false
                    if (++readFailures >= 8) {
                        error("The microphone stopped returning wake audio.")
                    }
                    continue
                }

                readFailures = 0
                var squareSum = 0.0
                var peak = 0f
                for (index in 0 until count) {
                    val raw = buffer[index] / 32768.0f
                    squareSum += raw * raw
                    peak = maxOf(peak, abs(raw))
                }
                val rms = sqrt(squareSum / count).toFloat()
                val gain = adaptiveWakeGain(rms)
                val samples = FloatArray(count) { index ->
                    (buffer[index] / 32768.0f * gain).coerceIn(-1.0f, 1.0f)
                }

                val now = SystemClock.elapsedRealtime()
                if (rms >= 0.0022f && peak >= 0.012f) {
                    recentSpeechEvidenceUntil = now + WAKE_SPEECH_EVIDENCE_MS
                }
                if (now - lastHeartbeat >= HEARTBEAT_INTERVAL_MS) {
                    lastHeartbeat = now
                    VoiceDiagnostics.updateWakeAudio(
                        this,
                        rms = rms,
                        peak = peak,
                        source = source
                    )
                    markServiceState(true, "listening")
                }

                stream.acceptWaveform(samples, SAMPLE_RATE)
                while (spotter.isReady(stream)) {
                    spotter.decode(stream)
                    val keyword = spotter.getResult(stream).keyword
                    if (keyword.isNotBlank()) {
                        if (now > recentSpeechEvidenceUntil) {
                            Log.d(
                                TAG,
                                "Ignored low-energy wake candidate: $keyword, rms=$rms, peak=$peak"
                            )
                            spotter.reset(stream)
                            continue
                        }
                        Log.i(
                            TAG,
                            "Wake keyword detected: $keyword, rms=$rms, peak=$peak, source=$source"
                        )
                        spotter.reset(stream)
                        return true
                    }
                }
            }

            false
        } finally {
            stream.release()
            releaseRecorder()
        }
    }

    private fun adaptiveWakeGain(rms: Float): Float = when {
        // Avoid turning quiet room noise into a keyword-like signal. Real speech
        // still receives moderate gain so "Aura" remains usable at arm's length.
        rms < 0.0020f -> 1.25f
        rms < 0.0045f -> 1.90f
        rms < 0.0100f -> 1.55f
        rms < 0.0200f -> 1.25f
        else -> 1.00f
    }

    private fun captureCommand(): FloatArray {
        val recorder = createCommandRecorder()
        val chunks = ArrayList<FloatArray>()
        val buffer = ShortArray(COMMAND_BUFFER_SAMPLES)
        var total = 0
        var readFailures = 0
        val maxSamples = SAMPLE_RATE * 25
        val detector = AdaptiveSpeechDetector(
            sampleRate = SAMPLE_RATE,
            minimumThreshold = 0.0042f,
            startSpeechMs = 75,
            endSilenceMs = 650,
            initialWaitMs = 5_000,
            calibrationMs = 240
        )

        try {
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "Android did not start AURA's command microphone."
            }

            while (running && total < maxSamples && pendingSpeech.get() == null) {
                val count = recorder.read(buffer, 0, buffer.size)
                if (count <= 0) {
                    if (pendingSpeech.get() != null || !running) break
                    if (++readFailures >= 8) {
                        error("The microphone stopped returning command audio.")
                    }
                    continue
                }

                readFailures = 0
                val samples = FloatArray(count) { buffer[it] / 32768.0f }
                chunks += samples
                total += count

                var squareSum = 0.0
                for (sample in samples) squareSum += sample * sample
                val rms = sqrt(squareSum / count).toFloat()
                when (detector.observe(rms, count)) {
                    AdaptiveSpeechDetector.Decision.END_OF_SPEECH,
                    AdaptiveSpeechDetector.Decision.NO_SPEECH_TIMEOUT -> break
                    AdaptiveSpeechDetector.Decision.CONTINUE -> Unit
                }
            }
        } finally {
            releaseRecorder()
        }

        if (!detector.heardSpeech) return FloatArray(0)

        val result = FloatArray(total)
        var offset = 0
        chunks.forEach { chunk ->
            chunk.copyInto(result, offset)
            offset += chunk.size
        }
        return result
    }

    private fun createWakeRecorder(): AudioRecord {
        checkMicrophonePermission()
        return VoiceAudioRecorder.createForWake(SAMPLE_RATE).also {
            activeRecorder = it
        }
    }

    private fun createCommandRecorder(): AudioRecord {
        checkMicrophonePermission()
        return VoiceAudioRecorder.createForCommand(SAMPLE_RATE).also {
            activeRecorder = it
        }
    }

    private fun checkMicrophonePermission() {
        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("Microphone permission was revoked.")
        }
    }

    private fun releaseRecorder() {
        activeRecorder?.let { recorder ->
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
        }
        activeRecorder = null
    }

    private fun stopListening() {
        running = false
        manualCaptureRequested = false
        pendingSpeech.set(null)
        speechContinuations.clear()
        speechPaused = false
        runCatching { activeRecorder?.stop() }
        if (::fastSpeaker.isInitialized) fastSpeaker.stop()
        AuraVoiceRuntime.speaker.stop()
    }

    @Suppress("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return

        val powerManager = getSystemService(PowerManager::class.java)
        wakeLock = powerManager
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:AuraWake")
            .apply {
                setReferenceCounted(false)
                acquire()
            }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock ->
            if (lock.isHeld) runCatching { lock.release() }
        }
        wakeLock = null
    }

    private fun shouldKeepListening(): Boolean =
        running && (
            manualCaptureRequested ||
                pendingSpeech.get() != null ||
                store.loadSettings().wakeWordEnabled
            )

    private suspend fun speakFast(
        text: String,
        bargeInDepth: Int = 0
    ) {
        val speechSettings = store.loadSettings()
        val clean = SpeechTextSanitizer.clean(
            text,
            speechSettings.speechLanguage
        ).take(MAX_SPEECH_CHARS)

        if (clean.isBlank()) return

        val wakeEnabled = speechSettings.wakeWordEnabled

        if (!wakeEnabled || bargeInDepth >= MAX_BARGE_IN_DEPTH) {
            speakWithoutBargeIn(clean)
            resumePendingSpeechIfAny(bargeInDepth)
            return
        }

        val session = fastSpeaker.startSpeech(
            clean,
            speechSettings.speechLanguage
        )
        if (session == null) {
            // Piper remains a private fallback. It cannot safely share the
            // native runtime with the wake detector, so barge-in is disabled
            // only for this fallback utterance.
            AuraVoiceRuntime.speaker.speakAndWait(clean)
            resumePendingSpeechIfAny(bargeInDepth)
            return
        }

        val interrupted = listenForWakeDuringSpeech(session)
        if (!interrupted) {
            // The wake monitor may return early after an audio-device error.
            // Do not resume a queued answer while this utterance is still playing.
            val expectedSpeechSeconds = (
                MIN_SPEECH_COMPLETION_WAIT_SECONDS + clean.length / 8
                ).coerceAtMost(MAX_SPEECH_COMPLETION_WAIT_SECONDS).toLong()
            val completed = session.await(
                expectedSpeechSeconds,
                TimeUnit.SECONDS
            )
            if (!completed) {
                Log.w(TAG, "Stopping TTS session after its bounded completion deadline")
                session.stop()
            }
            resumePendingSpeechIfAny(bargeInDepth)
            return
        }

        val remainingSpeech = session.remainingText()
        val command = captureBargeInCommand()

        if (
            command.isNullOrBlank() ||
            SpeechTextSanitizer.isWakeWordOnly(command)
        ) {
            if (remainingSpeech.isNotBlank() && running) {
                updateNotification("No command heard • continuing the previous reply")
                AuraRuntimeStateMachine.safeTransition(
                    AuraRuntimeState.SPEAKING,
                    "resuming interrupted reply"
                )
                speakFast(remainingSpeech, bargeInDepth + 1)
            } else {
                resumePendingSpeechIfAny(bargeInDepth)
            }
            return
        }

        when (VoiceCommandClassifier.controlIntent(command)) {
            VoiceCommandClassifier.ControlIntent.STOP_LISTENING -> {
                speechContinuations.clear()
                speechPaused = false
                store.saveSettings(
                    store.loadSettings().copy(wakeWordEnabled = false)
                )
                sendBroadcast(
                    Intent(ACTION_STATE_CHANGED).setPackage(packageName)
                )
                running = false
                speakWithoutBargeIn("Wake listening is now off.")
            }

            VoiceCommandClassifier.ControlIntent.STOP_SPEECH -> {
                speechContinuations.clear()
                speechPaused = false
                updateNotification("Previous reply stopped")
                AuraRuntimeStateMachine.forceIdle("owner stopped current speech")
            }

            VoiceCommandClassifier.ControlIntent.PAUSE_SPEECH -> {
                if (remainingSpeech.isNotBlank()) {
                    speechContinuations.push(remainingSpeech)
                }
                speechPaused = true
                updateNotification("Reply paused • say Aura, then continue to resume")
                AuraRuntimeStateMachine.forceIdle("owner paused current speech")
            }

            VoiceCommandClassifier.ControlIntent.RESUME_SPEECH -> {
                speechPaused = false
                val continuation = remainingSpeech
                    .takeIf(String::isNotBlank)
                    ?: speechContinuations.pop()
                if (continuation != null && running) {
                    AuraRuntimeStateMachine.safeTransition(
                        AuraRuntimeState.SPEAKING,
                        "owner resumed interrupted reply"
                    )
                    speakFast(continuation, bargeInDepth + 1)
                } else {
                    resumePendingSpeechIfAny(bargeInDepth)
                }
            }

            VoiceCommandClassifier.ControlIntent.NONE -> {
                val assessment = VoiceCommandClassifier.assess(command)
                if (remainingSpeech.isNotBlank()) {
                    speechContinuations.push(remainingSpeech)
                }

                if (!assessment.isComplete) {
                    Log.i(TAG, "Deferred incomplete barge-in command: ${assessment.reason}")
                    updateNotification("The command seems incomplete • please finish it")
                    AuraRuntimeStateMachine.safeTransition(
                        AuraRuntimeState.SPEAKING,
                        "asking user to complete interrupted command"
                    )
                    speakFast(
                        "Your instruction seems incomplete. Please finish your command.",
                        bargeInDepth + 1
                    )
                    return
                }

                updateNotification(
                    "Handling interrupted command: " + command.take(60)
                )
                AuraRuntimeStateMachine.safeTransition(
                    AuraRuntimeState.THINKING,
                    "handling barge-in command"
                )
                val result = try {
                    processor.process(command)
                } catch (error: Exception) {
                    Log.e(TAG, "Interrupted command processing failed", error)
                    BackgroundCommandResult(
                        speech = "I could not complete that command. I will continue the previous response.",
                        shouldSpeak = store.loadSettings().spokenReplies
                    )
                }

                if (result.shouldSpeak && result.speech.isNotBlank() && running) {
                    AuraRuntimeStateMachine.safeTransition(
                        AuraRuntimeState.SPEAKING,
                        "speaking barge-in result"
                    )
                    // When this answer finishes, speakFast drains the most
                    // recently interrupted continuation before older ones.
                    speakFast(result.speech, bargeInDepth + 1)
                } else {
                    resumePendingSpeechIfAny(bargeInDepth + 1)
                }
            }
        }
    }

    /**
     * Drains interrupted responses in LIFO order after the current answer.
     * A pause deliberately holds the queue; stop/cancel clears it.
     */
    private suspend fun resumePendingSpeechIfAny(bargeInDepth: Int) {
        if (!running || speechPaused) return
        val continuation = speechContinuations.pop() ?: return
        if (continuation.isBlank()) {
            resumePendingSpeechIfAny(bargeInDepth)
            return
        }

        AuraRuntimeStateMachine.safeTransition(
            AuraRuntimeState.SPEAKING,
            "resuming queued speech continuation"
        )
        if (bargeInDepth >= MAX_BARGE_IN_DEPTH) {
            speakWithoutBargeIn(continuation)
            resumePendingSpeechIfAny(bargeInDepth)
        } else {
            speakFast(continuation, bargeInDepth + 1)
        }
    }

    private fun speakWithoutBargeIn(text: String) {
        val languageTag = store.loadSettings().speechLanguage
        val clean = SpeechTextSanitizer.clean(text, languageTag)
            .take(MAX_SPEECH_CHARS)

        if (clean.isBlank()) return

        if (!fastSpeaker.speakAndWait(clean, languageTag)) {
            AuraVoiceRuntime.speaker.speakAndWait(clean)
        }
    }

    private fun listenForWakeDuringSpeech(
        session: FastSystemSpeaker.SpeechSession
    ): Boolean {
        val spotter = createKeywordSpotter()
        checkMicrophonePermission()
        val recorder = VoiceAudioRecorder
            .createForBargeIn(SAMPLE_RATE)
            .also { activeRecorder = it }
        val stream = spotter.createStream()
        val buffer = ShortArray(WAKE_BUFFER_SAMPLES)
        var readFailures = 0
        val startedAt = SystemClock.elapsedRealtime()
        val source = VoiceAudioRecorder.sourceName(
            recorder.audioSource
        )

        return try {
            recorder.startRecording()
            check(
                recorder.recordingState ==
                    AudioRecord.RECORDSTATE_RECORDING
            ) {
                "Android did not start AURA's barge-in microphone."
            }

            Log.i(
                TAG,
                "Barge-in wake microphone started with source=$source"
            )

            while (
                running &&
                !session.isFinished()
            ) {
                val count = recorder.read(
                    buffer,
                    0,
                    buffer.size
                )

                if (count <= 0) {
                    if (
                        session.isFinished() ||
                        !running
                    ) {
                        return false
                    }
                    if (++readFailures >= 8) {
                        Log.w(
                            TAG,
                            "Barge-in microphone stopped returning audio"
                        )
                        return false
                    }
                    continue
                }

                readFailures = 0
                var squareSum = 0.0
                for (index in 0 until count) {
                    val sample = buffer[index] / 32768.0f
                    squareSum += sample * sample
                }
                val rms = sqrt(squareSum / count).toFloat()
                val gain = adaptiveWakeGain(rms)
                val samples = FloatArray(count) { index ->
                    (
                        buffer[index] / 32768.0f * gain
                    ).coerceIn(-1.0f, 1.0f)
                }

                stream.acceptWaveform(samples, SAMPLE_RATE)

                while (spotter.isReady(stream)) {
                    spotter.decode(stream)
                    val keyword = spotter
                        .getResult(stream)
                        .keyword

                    if (keyword.isBlank()) continue

                    val withinStartupGrace =
                        SystemClock.elapsedRealtime() -
                            startedAt <
                            BARGE_IN_STARTUP_GRACE_MS
                    val likelySelfEcho =
                        session.isSpeakingWakeWord()

                    spotter.reset(stream)

                    if (
                        withinStartupGrace ||
                        likelySelfEcho
                    ) {
                        Log.i(
                            TAG,
                            "Ignored probable self-echo wake during speech"
                        )
                        continue
                    }

                    Log.i(
                        TAG,
                        "Owner interrupted speech with wake word: " +
                            "$keyword, rms=$rms, source=$source"
                    )
                    session.stop()
                    return true
                }
            }

            false
        } catch (error: Exception) {
            Log.w(
                TAG,
                "Barge-in listening was unavailable; " +
                    "continuing the current reply",
                error
            )
            false
        } finally {
            runCatching { stream.release() }
            runCatching { spotter.release() }
            releaseRecorder()
        }
    }

    private suspend fun captureBargeInCommand(): String? {
        markServiceState(true, "speech_interrupted")
        updateNotification(
            "Aura heard you • speak the new command after the tone"
        )
        playReadyTone()
        delay(BARGE_IN_COMMAND_DELAY_MS)

        VoiceDiagnostics.mark(
            this,
            VoiceDiagnostics.CAPTURING_COMMAND
        )
        AuraRuntimeStateMachine.safeTransition(
            AuraRuntimeState.LISTENING,
            "capturing barge-in command"
        )

        val settings = store.loadSettings()
        AuraVoiceRuntime.prepareWhisper(
            settings.speechLanguage
        )

        val samples = captureCommand()
        if (!running) {
            AuraVoiceRuntime.discardPreparedWhisper()
            return null
        }
        if (samples.size < SAMPLE_RATE / 3) {
            AuraVoiceRuntime.discardPreparedWhisper()
            return null
        }

        if (settings.ownerVoiceLockEnabled) {
            val verification = OwnerVoiceGuard.verify(
                context = this,
                samples = samples,
                threshold = settings.ownerVoiceThreshold
            )
            if (!verification.accepted) {
                AuraVoiceRuntime.discardPreparedWhisper()
                Log.w(TAG, "Rejected barge-in voice: speakerScore=${verification.score}")
                playRejectTone()
                return null
            }
        }

        updateNotification(
            "Understanding the interrupted command privately…"
        )
        AuraRuntimeStateMachine.safeTransition(
            AuraRuntimeState.THINKING,
            "transcribing barge-in command"
        )

        val transcript = AuraVoiceRuntime.transcribe(
            samples = samples,
            languageTag = settings.speechLanguage
        ).trim()

        Log.i(
            TAG,
            "Barge-in transcript length=${transcript.length}"
        )

        return transcript.takeIf(String::isNotBlank)
    }

    private suspend fun speakIfEnabled(text: String) {
        if (
            store.loadSettings().spokenReplies
        ) {
            speakFast(text)
        }
    }

    private fun playReadyTone() {
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 72)
            tone.startTone(ToneGenerator.TONE_PROP_ACK, 80)
            Handler(Looper.getMainLooper()).postDelayed(
                { runCatching { tone.release() } },
                105L
            )
        }
    }

    private fun playRejectTone() {
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 55)
            tone.startTone(ToneGenerator.TONE_PROP_NACK, 90)
            Handler(Looper.getMainLooper()).postDelayed(
                { runCatching { tone.release() } },
                125L
            )
        }
    }

    /**
     * Android requires every startForegroundService() launch to promote itself
     * promptly even when AURA discovers that there is no work to keep running.
     * The old early-return paths could violate that contract and crash the app
     * with RemoteServiceException.
     */
    private fun completeForegroundHandshakeAndStop(
        status: String,
        diagnosticState: String
    ) {
        runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification(status),
                0
            )
        }.onFailure { error ->
            Log.w(TAG, "Could not complete transient foreground handshake", error)
        }
        markServiceState(false, diagnosticState)
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    private fun updateRecoveryNotice(message: String) {
        getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString("recovery_message", message)
            .apply()
    }

    private fun markServiceState(active: Boolean, status: String) {
        VoiceDiagnostics.markWakeService(this, active, status)
    }

    private fun updateNotification(status: String) {
        notifications.notify(NOTIFICATION_ID, notification(status))
    }

    private fun notification(status: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val captureIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, WakeWordService::class.java).setAction(ACTION_CAPTURE_NOW),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, WakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_aura)
            .setContentTitle("AURA private wake listening")
            .setContentText(status)
            .setStyle(NotificationCompat.BigTextStyle().bigText(status))
            .setContentIntent(openIntent)
            .addAction(0, "Talk now", captureIntent)
            .addAction(0, "Turn off", stopIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun String.containsAny(vararg values: String): Boolean =
        values.any { contains(it, ignoreCase = true) }

    companion object {
        const val ACTION_STATE_CHANGED = "in.diganta.aurabutler.STATE_CHANGED"
        private const val ACTION_START = "in.diganta.aurabutler.START_WAKE"
        private const val ACTION_STOP = "in.diganta.aurabutler.STOP_WAKE"
        private const val ACTION_CAPTURE_NOW = "in.diganta.aurabutler.CAPTURE_NOW"
        private const val ACTION_SPEAK_TEXT = "in.diganta.aurabutler.SPEAK_TEXT"
        private const val EXTRA_SPEECH = "speech"

        private const val CHANNEL_ID = "aura_wake_listening"
        private const val NOTIFICATION_ID = 2201
        private const val STATE_PREFS = "aura_voice_diagnostics"

        private const val SAMPLE_RATE = OfflineWhisperEngine.SAMPLE_RATE
        private const val KWS_THREADS = 2
        private const val WAKE_BUFFER_SAMPLES = 480
        private const val COMMAND_BUFFER_SAMPLES = 1_280
        private const val HEARTBEAT_INTERVAL_MS = 1_000L
        private const val WAKE_SPEECH_EVIDENCE_MS = 700L
        private const val MAX_CONSECUTIVE_FAILURES = 5
        private const val RETRY_DELAY_MS = 1_200L
        private const val MAX_SPEECH_CHARS = 1_500
        private const val MAX_BARGE_IN_DEPTH = 6
        private const val MAX_PENDING_SPEECH_CONTINUATIONS = 6
        private const val BARGE_IN_STARTUP_GRACE_MS = 650L
        private const val BARGE_IN_COMMAND_DELAY_MS = 105L
        private const val MIN_SPEECH_COMPLETION_WAIT_SECONDS = 8
        private const val MAX_SPEECH_COMPLETION_WAIT_SECONDS = 90
        private const val TAG = "AuraWakeService"

        fun setEnabled(context: Context, enabled: Boolean) {
            if (enabled) {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, WakeWordService::class.java).setAction(ACTION_START)
                )
            } else {
                context.stopService(Intent(context, WakeWordService::class.java))
            }
        }

        fun captureNow(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, WakeWordService::class.java).setAction(ACTION_CAPTURE_NOW)
            )
        }

        fun speak(context: Context, text: String) {
            if (text.isBlank()) return
            ContextCompat.startForegroundService(
                context,
                Intent(context, WakeWordService::class.java)
                    .setAction(ACTION_SPEAK_TEXT)
                    .putExtra(EXTRA_SPEECH, text.take(MAX_SPEECH_CHARS))
            )
        }

        fun isRunning(context: Context): Boolean =
            VoiceDiagnostics.wakeStatus(context).active

        fun createChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "AURA wake listening",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Visible while AURA listens locally for its wake word."
                    setSound(null, null)
                }
            )
        }
    }
}
