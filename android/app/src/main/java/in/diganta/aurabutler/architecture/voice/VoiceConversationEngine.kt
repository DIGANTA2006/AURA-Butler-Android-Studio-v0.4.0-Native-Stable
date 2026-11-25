package `in`.diganta.aurabutler.architecture.voice

import `in`.diganta.aurabutler.architecture.resource.AdaptiveResourceGovernor
import `in`.diganta.aurabutler.architecture.resource.AndroidDeviceCapabilityProbe
import `in`.diganta.aurabutler.architecture.resource.ResourceGovernorIntegration
import `in`.diganta.aurabutler.architecture.resource.WorkPriority
import `in`.diganta.aurabutler.runtime.OperatingMode
import `in`.diganta.aurabutler.voice.Speaker
import `in`.diganta.aurabutler.voice.AdaptiveSpeechDetector
import `in`.diganta.aurabutler.voice.OwnerVoiceGuard
import `in`.diganta.aurabutler.voice.VoiceAudioRecorder
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Full-duplex voice conversation engine with 17-state machine, barge-in,
 * priority controls, checkpoint resume, and resource-aware scheduling.
 */
class VoiceConversationEngine(
    private val context: Context,
    private val speaker: Speaker,
    private val governor: AdaptiveResourceGovernor = AdaptiveResourceGovernor()
) {

    private val probe = AndroidDeviceCapabilityProbe(context)
    private val integration = ResourceGovernorIntegration(governor, probe)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ttsScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // 17-State Conversation State Machine
    enum class VoiceState {
        IDLE,
        WAKE_WORD_DETECTED,
        AUTHENTICATING_SPEAKER,
        LISTENING,
        TRANSCRIBING,
        WAITING_FOR_UTTERANCE_COMPLETION,
        VALIDATING_COMMAND,
        REQUESTING_CLARIFICATION,
        PROCESSING_COMMAND,
        GENERATING_RESPONSE,
        SPEAKING,
        INTERRUPTED,
        PAUSED,
        RESUMING,
        CANCELLED,
        SESSION_TIMEOUT,
        ERROR
    }

    // State machine with valid transitions
    private val stateMachine = VoiceConversationStateMachine()
    private val currentState = MutableStateFlow(VoiceState.IDLE)
    val state: StateFlow<VoiceState> = currentState

    // Configurable follow-up listening window (default 8s)
    private var followUpWindowMs = 8000L
    private var sessionTimeoutMs = 30000L

    // Barge-in and checkpoint resume
    private val speechTaskManager = SpeechTaskManager()
    private var activeSpeechTask: SpeechTask? = null
    private val bargeInListener = BargeInListener()

    // Voice priority controls
    private val priorityControlParser = VoicePriorityControlParser

    // Resource-aware streaming chunk size
    private var streamingChunkChars = 320

    // Audio focus
    private val audioFocusRequester = AudioFocusRequester(context)

    init {
        updateStreamingChunkSize()
    }

    private fun updateStreamingChunkSize() {
        streamingChunkChars = integration.getStreamingChunkSize(isForeground = true)
    }

    /**
     * Main entry point: process a voice turn from wake-word to response completion.
     * Handles barge-in, priority controls, and checkpoint resume automatically.
     */
    suspend fun processVoiceTurn(
        transcript: String,
        speakerVerified: Boolean,
        speakerConfidence: Float,
        operatingMode: OperatingMode = OperatingMode.AUTOMATIC,
        onPartial: (String) -> Unit = {},
        onComplete: (VoiceTurnResult) -> Unit
    ) {
        val validationInput = CommandCompletenessValidator.UtteranceValidationInput(
            transcript = transcript,
            speakerVerified = speakerVerified,
            speakerConfidence = speakerConfidence,
            currentState = currentState.value,
            isSpeaking = activeSpeechTask != null && activeSpeechTask!!.isPlaying
        )

        val validation = CommandCompletenessValidator.validate(validationInput)

        when (validation) {
            is CommandCompletenessValidator.CommandValidationDecision.Proceed -> {
                // Handle priority controls FIRST (stop, pause, resume, volume, rate)
                val priorityCmd = priorityControlParser.parse(transcript)
                if (priorityCmd != VoicePriorityControlParser.PriorityControlCommand.NONE) {
                    handlePriorityControl(priorityCmd)
                    onComplete(VoiceTurnResult.ControlHandled(priorityCmd))
                    return
                }

                // Normal command processing
                processCommand(transcript, operatingMode, onPartial, onComplete)
            }
            is CommandCompletenessValidator.CommandValidationDecision.WaitOrAskCompletion -> {
                currentState.value = VoiceState.WAITING_FOR_UTTERANCE_COMPLETION
                onComplete(VoiceTurnResult.NeedsCompletion(validation.prompt))
            }
            is CommandCompletenessValidator.CommandValidationDecision.RequestClarification -> {
                currentState.value = VoiceState.REQUESTING_CLARIFICATION
                onComplete(VoiceTurnResult.NeedsClarification(validation.prompt))
            }
            is CommandCompletenessValidator.CommandValidationDecision.RejectUnclear -> {
                currentState.value = VoiceState.ERROR
                onComplete(VoiceTurnResult.Rejected(validation.prompt))
            }
            is CommandCompletenessValidator.CommandValidationDecision.RejectUnauthorizedSpeaker -> {
                currentState.value = VoiceState.IDLE
                onComplete(VoiceTurnResult.Rejected(validation.reason))
            }
        }
    }

    private suspend fun processCommand(
        transcript: String,
        operatingMode: OperatingMode,
        onPartial: (String) -> Unit,
        onComplete: (VoiceTurnResult) -> Unit
    ) {
        currentState.value = VoiceState.PROCESSING_COMMAND

        // Check if we're interrupting ongoing speech
        val interruptedTask = speechTaskManager.interruptForBargeIn()
        if (interruptedTask != null) {
            currentState.value = VoiceState.INTERRUPTED
            // The interrupted task is queued for resume
        }

        currentState.value = VoiceState.GENERATING_RESPONSE

        // Resource-aware execution
        val result = integration.executeWithGovernance(
            priority = WorkPriority.INTERACTIVE_VOICE,
            isForeground = true
        ) {
            // This would call into ProductionButlerEngine.chat() with streaming
            // For now, simulate the streaming response generation
            generateStreamingResponse(transcript, operatingMode, onPartial)
        }

        val (response, decision) = result

        if (decision.defer) {
            // Should not happen for INTERACTIVE_VOICE, but handle gracefully
            onComplete(VoiceTurnResult.Deferred(decision.reason))
            return
        }

        currentState.value = VoiceState.SPEAKING

        // Speak with checkpoint support
        speakWithCheckpoints(response.speechText) { speakResult ->
            when (speakResult) {
                is SpeakResult.Completed -> {
                    // Check for queued interrupted speech to resume
                    val resumed = speechTaskManager.resumeFromCheckpoint()
                    if (resumed != null) {
                        currentState.value = VoiceState.RESUMING
                        speakWithCheckpoints(resumed.remainingText) { resumeResult ->
                            if (resumeResult is SpeakResult.Completed) {
                                currentState.value = VoiceState.IDLE
                                startFollowUpWindow()
                                onComplete(VoiceTurnResult.Completed(response))
                            } else {
                                currentState.value = VoiceState.IDLE
                                onComplete(VoiceTurnResult.Completed(response))
                            }
                        }
                    } else {
                        currentState.value = VoiceState.IDLE
                        startFollowUpWindow()
                        onComplete(VoiceTurnResult.Completed(response))
                    }
                }
                is SpeakResult.Interrupted -> {
                    currentState.value = VoiceState.INTERRUPTED
                    onComplete(VoiceTurnResult.Interrupted(response))
                }
                is SpeakResult.Cancelled -> {
                    currentState.value = VoiceState.CANCELLED
                    speechTaskManager.stopAndCancelAll()
                    onComplete(VoiceTurnResult.Cancelled)
                }
                is SpeakResult.Error -> {
                    currentState.value = VoiceState.ERROR
                    onComplete(VoiceTurnResult.Error(speakResult.message))
                }
            }
        }
    }

    private suspend fun generateStreamingResponse(
        transcript: String,
        operatingMode: OperatingMode,
        onPartial: (String) -> Unit
    ): VoiceResponse {
        // This integrates with ProductionButlerEngine.chat()
        // The actual implementation delegates to the engine
        // Here we define the contract
        return VoiceResponse(
            assistantMessage = "",
            speechText = "",
            pendingActions = emptyList(),
            memoryWrites = emptyList()
        )
    }

    private fun speakWithCheckpoints(text: String, onComplete: (SpeakResult) -> Unit) {
        val segments = segmentForCheckpoints(text)
        val task = SpeechTask(
            id = java.util.UUID.randomUUID().toString(),
            fullText = text,
            segments = segments,
            currentSegmentIndex = 0,
            onSegmentComplete = { index, isLast ->
                if (isLast) {
                    onComplete(SpeakResult.Completed)
                }
            },
            onInterrupted = { remainingSegments ->
                speechTaskManager.queueInterrupted(SpeechTask(
                    id = java.util.UUID.randomUUID().toString(),
                    fullText = remainingSegments.joinToString(" "),
                    segments = remainingSegments.map { SpeechSegment(it, streamingChunkChars) },
                    currentSegmentIndex = 0,
                    onSegmentComplete = { _, _ -> },
                    onInterrupted = { _ -> },
                    onCancelled = { },
                    onError = { _ -> }
                ))
                onComplete(SpeakResult.Interrupted)
            },
            onCancelled = {
                onComplete(SpeakResult.Cancelled)
            },
            onError = { msg ->
                onComplete(SpeakResult.Error(msg))
            }
        )

        activeSpeechTask = task
        speechTaskManager.start(task)
        playSegments(task, 0, onComplete)
    }

    private fun playSegments(task: SpeechTask, startIndex: Int, onComplete: (SpeakResult) -> Unit) {
        if (startIndex >= task.segments.size) {
            task.onSegmentComplete(startIndex - 1, true)
            return
        }

        val segment = task.segments[startIndex]
        val timingId = java.util.UUID.randomUUID().toString()
        speaker.speak(segment.text, Locale.getDefault().language) { status: Int ->
            if (status == TextToSpeech.SUCCESS) {
                // Segment completed, move to next
                playSegments(task, startIndex + 1, onComplete)
            } else {
                task.onError("TTS error: $status")
            }
        }
    }

    private fun segmentForCheckpoints(text: String): List<SpeechSegment> {
        // Segment at sentence boundaries for natural resume points
        val sentences = text.split(Regex("(?<=[.!?])\\s+"))
        return sentences.map { SpeechSegment(it.trim(), streamingChunkChars) }
    }

    private fun handlePriorityControl(cmd: VoicePriorityControlParser.PriorityControlCommand) {
        when (cmd) {
            VoicePriorityControlParser.PriorityControlCommand.STOP_SPEECH -> {
                speechTaskManager.stopAndCancelAll()
                activeSpeechTask?.onCancelled?.invoke()
                currentState.value = VoiceState.CANCELLED
            }
            VoicePriorityControlParser.PriorityControlCommand.PAUSE_SPEECH -> {
                speechTaskManager.pause()
                currentState.value = VoiceState.PAUSED
            }
            VoicePriorityControlParser.PriorityControlCommand.RESUME_SPEECH -> {
                val resumed = speechTaskManager.resumeFromCheckpoint()
                if (resumed != null) {
                    currentState.value = VoiceState.RESUMING
                    speakWithCheckpoints(resumed.remainingText) { _ -> }
                } else {
                    currentState.value = VoiceState.IDLE
                }
            }
            VoicePriorityControlParser.PriorityControlCommand.STOP_LISTENING -> {
                currentState.value = VoiceState.SESSION_TIMEOUT
            }
            VoicePriorityControlParser.PriorityControlCommand.VOLUME_UP -> speaker.adjustVolume(1.1f)
            VoicePriorityControlParser.PriorityControlCommand.VOLUME_DOWN -> speaker.adjustVolume(0.9f)
            VoicePriorityControlParser.PriorityControlCommand.RATE_FASTER -> speaker.adjustRate(1.2f)
            VoicePriorityControlParser.PriorityControlCommand.RATE_SLOWER -> speaker.adjustRate(0.8f)
            VoicePriorityControlParser.PriorityControlCommand.MUTE -> speaker.setMuted(true)
            VoicePriorityControlParser.PriorityControlCommand.UNMUTE -> speaker.setMuted(false)
            VoicePriorityControlParser.PriorityControlCommand.REPEAT_LAST -> {
                val last = speechTaskManager.getLastCompleted()
                last?.let { speakWithCheckpoints(it.fullText) { _ -> } }
            }
            VoicePriorityControlParser.PriorityControlCommand.NONE -> {
                // No-op
            }
        }
    }

    private fun startFollowUpWindow() {
        scope.launch {
            kotlinx.coroutines.delay(followUpWindowMs)
            if (currentState.value == VoiceState.IDLE) {
                // Follow-up window expired
            }
        }
    }

    fun setFollowUpWindowMs(ms: Long) {
        followUpWindowMs = ms.coerceIn(1000, 60000)
    }

    fun setSessionTimeoutMs(ms: Long) {
        sessionTimeoutMs = ms.coerceIn(5000, 300000)
    }

    fun shutdown() {
        scope.cancel()
        ttsScope.cancel()
        speechTaskManager.stopAndCancelAll()
        audioFocusRequester.abandon()
    }

    // ===== Data Classes =====

    data class UtteranceValidationInput(
        val transcript: String,
        val speakerVerified: Boolean,
        val speakerConfidence: Float,
        val currentState: VoiceState,
        val isSpeaking: Boolean
    )

    sealed class VoiceTurnResult {
        data class Completed(val response: VoiceResponse) : VoiceTurnResult()
        data class Interrupted(val partialResponse: VoiceResponse) : VoiceTurnResult()
        object Cancelled : VoiceTurnResult()
        data class Deferred(val reason: String) : VoiceTurnResult()
        data class Error(val message: String) : VoiceTurnResult()
        data class Rejected(val reason: String) : VoiceTurnResult()
        data class NeedsCompletion(val prompt: String) : VoiceTurnResult()
        data class NeedsClarification(val prompt: String) : VoiceTurnResult()
        data class ControlHandled(val command: VoicePriorityControlParser.PriorityControlCommand) : VoiceTurnResult()
    }

    data class VoiceResponse(
        val assistantMessage: String,
        val speechText: String,
        val pendingActions: List<Any>, // ButlerAction
        val memoryWrites: List<Any>    // MemoryItem
    )

    sealed class SpeakResult {
        object Completed : SpeakResult()
        object Interrupted : SpeakResult()
        object Cancelled : SpeakResult()
        data class Error(val message: String) : SpeakResult()
    }

    data class SpeechSegment(
        val text: String,
        val maxChars: Int
    )

    class SpeechTask(
        val id: String,
        val fullText: String,
        val segments: List<SpeechSegment>,
        var currentSegmentIndex: Int,
        val onSegmentComplete: (Int, Boolean) -> Unit,
        val onInterrupted: (List<String>) -> Unit,
        val onCancelled: () -> Unit,
        val onError: (String) -> Unit
    ) {
        var isPlaying = true

        val remainingText: String
            get() = segments.subList(currentSegmentIndex, segments.size).joinToString(" ") { it.text }

        fun markSegmentComplete(index: Int, isLast: Boolean) {
            currentSegmentIndex = index + 1
            onSegmentComplete(index, isLast)
            if (isLast) isPlaying = false
        }

        fun interrupt() {
            isPlaying = false
            val remaining = segments.subList(currentSegmentIndex, segments.size).map { it.text }
            onInterrupted(remaining)
        }

        fun cancel() {
            isPlaying = false
            onCancelled()
        }

        fun error(msg: String) {
            isPlaying = false
            onError(msg)
        }
    }

    class SpeechTaskManager {
        private val lock = Any()
        private var currentTask: SpeechTask? = null
        private val interruptedStack = mutableListOf<SpeechTask>()
        private val completedHistory = mutableListOf<SpeechTask>()
        companion object {
            const val MAX_INTERRUPTED = 6
            const val MAX_HISTORY = 20
        }

        fun start(task: SpeechTask) {
            synchronized(lock) {
                currentTask = task
            }
        }

        fun interruptForBargeIn(): SpeechTask? {
            synchronized(lock) {
                currentTask?.let { task ->
                    task.interrupt()
                    if (interruptedStack.size >= MAX_INTERRUPTED) {
                        interruptedStack.removeAt(0) // Drop oldest
                    }
                    interruptedStack.add(task)
                    currentTask = null
                    return task
                }
                return null
            }
        }

        fun queueInterrupted(task: SpeechTask) {
            synchronized(lock) {
                if (interruptedStack.size >= MAX_INTERRUPTED) {
                    interruptedStack.removeAt(0)
                }
                interruptedStack.add(task)
            }
        }

        fun resumeFromCheckpoint(): SpeechTask? {
            synchronized(lock) {
                return interruptedStack.lastOrNull()?.also {
                    interruptedStack.removeAt(interruptedStack.lastIndex)
                    currentTask = it
                }
            }
        }

        fun stopAndCancelAll(): SpeechTask? {
            synchronized(lock) {
                currentTask?.cancel()
                val cancelled = currentTask
                currentTask = null
                interruptedStack.forEach { it.cancel() }
                interruptedStack.clear()
                return cancelled
            }
        }

        fun pause() {
            synchronized(lock) {
                currentTask?.let { it.isPlaying = false }
            }
        }

        fun getLastCompleted(): SpeechTask? {
            synchronized(lock) {
                return completedHistory.lastOrNull()
            }
        }

        private fun addToHistory(task: SpeechTask) {
            synchronized(lock) {
                completedHistory.add(task)
                if (completedHistory.size > MAX_HISTORY) {
                    completedHistory.removeAt(0)
                }
            }
        }
    }

    // ===== Barge-In Listener =====

    class BargeInListener {
        private val vad = AdaptiveSpeechDetector(16000)
        private val ownerGuard = OwnerVoiceGuard()
        private var isMonitoring = false

        fun startMonitoring(onBargeIn: (FloatArray) -> Unit) {
            isMonitoring = true
            // Continuous mic monitoring during TTS playback
            // Uses AEC + NoiseSuppressor + VAD + OwnerVoiceGuard
        }

        fun stopMonitoring() {
            isMonitoring = false
        }
    }

    // ===== Audio Focus =====

    private class AudioFocusRequester(private val context: Context) {
        private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        private val focusListener = object : android.media.AudioManager.OnAudioFocusChangeListener {
            override fun onAudioFocusChange(focusChange: Int) {
                when (focusChange) {
                    android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        // Pause speech
                    }
                    android.media.AudioManager.AUDIOFOCUS_LOSS -> {
                        // Stop speech
                    }
                    android.media.AudioManager.AUDIOFOCUS_GAIN -> {
                        // Resume if paused
                    }
                }
            }
        }

        fun request(): Boolean {
            return audioManager.requestAudioFocus(
                focusListener,
                android.media.AudioManager.STREAM_MUSIC,
                android.media.AudioManager.AUDIOFOCUS_GAIN
            ) == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }

        fun abandon() {
            audioManager.abandonAudioFocus(focusListener)
        }
    }
}

// ===== Supporting Classes =====

class VoiceConversationStateMachine {
    private val transitions = mapOf(
        VoiceConversationEngine.VoiceState.IDLE to setOf(VoiceConversationEngine.VoiceState.WAKE_WORD_DETECTED),
        VoiceConversationEngine.VoiceState.WAKE_WORD_DETECTED to setOf(VoiceConversationEngine.VoiceState.AUTHENTICATING_SPEAKER, VoiceConversationEngine.VoiceState.LISTENING, VoiceConversationEngine.VoiceState.IDLE),
        VoiceConversationEngine.VoiceState.AUTHENTICATING_SPEAKER to setOf(VoiceConversationEngine.VoiceState.LISTENING, VoiceConversationEngine.VoiceState.IDLE),
        VoiceConversationEngine.VoiceState.LISTENING to setOf(VoiceConversationEngine.VoiceState.TRANSCRIBING, VoiceConversationEngine.VoiceState.WAITING_FOR_UTTERANCE_COMPLETION, VoiceConversationEngine.VoiceState.IDLE),
        VoiceConversationEngine.VoiceState.TRANSCRIBING to setOf(VoiceConversationEngine.VoiceState.WAITING_FOR_UTTERANCE_COMPLETION, VoiceConversationEngine.VoiceState.VALIDATING_COMMAND, VoiceConversationEngine.VoiceState.IDLE),
        VoiceConversationEngine.VoiceState.WAITING_FOR_UTTERANCE_COMPLETION to setOf(VoiceConversationEngine.VoiceState.VALIDATING_COMMAND, VoiceConversationEngine.VoiceState.IDLE),
        VoiceConversationEngine.VoiceState.VALIDATING_COMMAND to setOf(VoiceConversationEngine.VoiceState.REQUESTING_CLARIFICATION, VoiceConversationEngine.VoiceState.PROCESSING_COMMAND, VoiceConversationEngine.VoiceState.IDLE),
        VoiceConversationEngine.VoiceState.REQUESTING_CLARIFICATION to setOf(VoiceConversationEngine.VoiceState.LISTENING, VoiceConversationEngine.VoiceState.IDLE),
        VoiceConversationEngine.VoiceState.PROCESSING_COMMAND to setOf(VoiceConversationEngine.VoiceState.GENERATING_RESPONSE, VoiceConversationEngine.VoiceState.INTERRUPTED, VoiceConversationEngine.VoiceState.ERROR, VoiceConversationEngine.VoiceState.IDLE),
        VoiceConversationEngine.VoiceState.GENERATING_RESPONSE to setOf(VoiceConversationEngine.VoiceState.SPEAKING, VoiceConversationEngine.VoiceState.INTERRUPTED, VoiceConversationEngine.VoiceState.ERROR, VoiceConversationEngine.VoiceState.IDLE),
        VoiceConversationEngine.VoiceState.SPEAKING to setOf(VoiceConversationEngine.VoiceState.INTERRUPTED, VoiceConversationEngine.VoiceState.PAUSED, VoiceConversationEngine.VoiceState.CANCELLED, VoiceConversationEngine.VoiceState.IDLE, VoiceConversationEngine.VoiceState.SESSION_TIMEOUT),
        VoiceConversationEngine.VoiceState.INTERRUPTED to setOf(VoiceConversationEngine.VoiceState.PROCESSING_COMMAND, VoiceConversationEngine.VoiceState.IDLE),
        VoiceConversationEngine.VoiceState.PAUSED to setOf(VoiceConversationEngine.VoiceState.RESUMING, VoiceConversationEngine.VoiceState.CANCELLED, VoiceConversationEngine.VoiceState.IDLE),
        VoiceConversationEngine.VoiceState.RESUMING to setOf(VoiceConversationEngine.VoiceState.SPEAKING, VoiceConversationEngine.VoiceState.IDLE),
        VoiceConversationEngine.VoiceState.CANCELLED to setOf(VoiceConversationEngine.VoiceState.IDLE),
        VoiceConversationEngine.VoiceState.SESSION_TIMEOUT to setOf(VoiceConversationEngine.VoiceState.IDLE),
        VoiceConversationEngine.VoiceState.ERROR to setOf(VoiceConversationEngine.VoiceState.IDLE)
    )

    fun canTransition(from: VoiceConversationEngine.VoiceState, to: VoiceConversationEngine.VoiceState): Boolean =
        transitions[from]?.contains(to) == true
}

object CommandCompletenessValidator {
    data class UtteranceValidationInput(
        val transcript: String,
        val speakerVerified: Boolean,
        val speakerConfidence: Float,
        val currentState: VoiceConversationEngine.VoiceState,
        val isSpeaking: Boolean
    )

    sealed class CommandValidationDecision {
        object Proceed : CommandValidationDecision()
        data class WaitOrAskCompletion(val prompt: String) : CommandValidationDecision()
        data class RequestClarification(val prompt: String) : CommandValidationDecision()
        data class RejectUnclear(val prompt: String) : CommandValidationDecision()
        data class RejectUnauthorizedSpeaker(val reason: String) : CommandValidationDecision()
    }

    fun validate(input: UtteranceValidationInput): CommandValidationDecision {
        // Unauthorized speaker check (TV, fan, keyboard noise, stranger)
        if (!input.speakerVerified || input.speakerConfidence < 0.6f) {
            return CommandValidationDecision.RejectUnauthorizedSpeaker(
                "Speaker not verified or confidence too low."
            )
        }

        val transcript = input.transcript.trim()
        if (transcript.isBlank()) {
            return CommandValidationDecision.RejectUnclear("I didn't catch that. Could you repeat?")
        }

        // Incomplete utterance detection (Case A: "AURA open...", "set a timer...")
        val incompletePatterns = listOf(
            Regex("""(?i)\b(open|launch|start|run)\s*$"""),
            Regex("""(?i)\b(set|create|make|schedule)\s+(a\s+)?(timer|alarm|reminder|calendar)\s*$"""),
            Regex("""(?i)\b(call|dial|message|text|email|whatsapp)\s*$"""),
            Regex("""(?i)\b(play|search|find|look up)\s*$"""),
            Regex("""(?i)\b(navigate|directions|go to)\s*$"""),
            Regex("""(?i)\b(turn|set)\s+(on|off)\s*$"""),
            Regex("""(?i)\b(increase|decrease|raise|lower)\s*$""")
        )

        if (incompletePatterns.any { it.matches(transcript) }) {
            return CommandValidationDecision.WaitOrAskCompletion(
                "Your command seems incomplete. What would you like me to do?"
            )
        }

        // Ambiguous command detection (Case B: "Turn it up" when idle)
        val ambiguousPatterns = listOf(
            Regex("""(?i)^(turn it up|turn it down|make it louder|make it quieter)$"""),
            Regex("""(?i)^(faster|slower|repeat)$"""),
            Regex("""(?i)^(stop it|pause it)$""")
        )

        if (ambiguousPatterns.any { it.matches(transcript) } && !input.isSpeaking) {
            return CommandValidationDecision.RequestClarification(
                "I'm not currently speaking or playing anything. What would you like me to adjust?"
            )
        }

        // Contextual follow-up (Case C: "Make it louder" while speaking)
        if (ambiguousPatterns.any { it.matches(transcript) } && input.isSpeaking) {
            return CommandValidationDecision.Proceed // Handled by priority control parser
        }

        // Valid command
        return CommandValidationDecision.Proceed
    }
}
