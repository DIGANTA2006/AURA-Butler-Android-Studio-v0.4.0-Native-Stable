package `in`.diganta.aurabutler.architecture.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID

/**
 * Explicit 17-state conversation machine required by voice + instruction.md Section 13.
 */
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

data class VoiceEngineConfig(
    val followUpWindowMs: Long = 8_000L,
    val minAsrConfidence: Float = 0.65f,
    val minSpeakerConfidence: Float = 0.72f,
    val minIntentConfidence: Float = 0.60f,
    val speechRate: Float = 1.0f,
    val assistantVolume: Float = 0.85f,
    val muted: Boolean = false,
    val bargeInEnabled: Boolean = true,
    val vadSensitivity: Float = 0.60f
) {
    fun withRateDelta(delta: Float): VoiceEngineConfig =
        copy(speechRate = (speechRate + delta).coerceIn(0.6f, 2.0f))

    fun withVolumeDelta(delta: Float): VoiceEngineConfig =
        copy(assistantVolume = (assistantVolume + delta).coerceIn(0.05f, 1.0f))
}

data class VoiceSessionSnapshot(
    val state: VoiceState = VoiceState.IDLE,
    val sessionId: String? = null,
    val lastReason: String = "Initialized",
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)

/**
 * Thread-safe, observable conversation state machine preventing invalid transitions and task races.
 */
class VoiceConversationStateMachine {
    private val lock = Any()
    private val _snapshot = MutableStateFlow(VoiceSessionSnapshot())
    val snapshot: StateFlow<VoiceSessionSnapshot> = _snapshot.asStateFlow()

    val currentState: VoiceState
        get() = _snapshot.value.state

    private val allowedTransitions: Map<VoiceState, Set<VoiceState>> = mapOf(
        VoiceState.IDLE to setOf(
            VoiceState.WAKE_WORD_DETECTED, VoiceState.AUTHENTICATING_SPEAKER,
            VoiceState.LISTENING, VoiceState.SPEAKING, VoiceState.ERROR
        ),
        VoiceState.WAKE_WORD_DETECTED to setOf(
            VoiceState.AUTHENTICATING_SPEAKER, VoiceState.LISTENING,
            VoiceState.CANCELLED, VoiceState.IDLE, VoiceState.ERROR
        ),
        VoiceState.AUTHENTICATING_SPEAKER to setOf(
            VoiceState.LISTENING, VoiceState.IDLE, VoiceState.CANCELLED, VoiceState.ERROR
        ),
        VoiceState.LISTENING to setOf(
            VoiceState.TRANSCRIBING, VoiceState.WAITING_FOR_UTTERANCE_COMPLETION,
            VoiceState.VALIDATING_COMMAND, VoiceState.SESSION_TIMEOUT,
            VoiceState.CANCELLED, VoiceState.IDLE, VoiceState.ERROR
        ),
        VoiceState.TRANSCRIBING to setOf(
            VoiceState.WAITING_FOR_UTTERANCE_COMPLETION, VoiceState.VALIDATING_COMMAND,
            VoiceState.LISTENING, VoiceState.CANCELLED, VoiceState.ERROR
        ),
        VoiceState.WAITING_FOR_UTTERANCE_COMPLETION to setOf(
            VoiceState.TRANSCRIBING, VoiceState.VALIDATING_COMMAND,
            VoiceState.REQUESTING_CLARIFICATION, VoiceState.SESSION_TIMEOUT,
            VoiceState.CANCELLED, VoiceState.IDLE, VoiceState.ERROR
        ),
        VoiceState.VALIDATING_COMMAND to setOf(
            VoiceState.PROCESSING_COMMAND, VoiceState.REQUESTING_CLARIFICATION,
            VoiceState.RESUMING, VoiceState.PAUSED, VoiceState.CANCELLED,
            VoiceState.LISTENING, VoiceState.IDLE, VoiceState.ERROR
        ),
        VoiceState.REQUESTING_CLARIFICATION to setOf(
            VoiceState.SPEAKING, VoiceState.LISTENING, VoiceState.SESSION_TIMEOUT,
            VoiceState.CANCELLED, VoiceState.IDLE, VoiceState.ERROR
        ),
        VoiceState.PROCESSING_COMMAND to setOf(
            VoiceState.GENERATING_RESPONSE, VoiceState.SPEAKING, VoiceState.PAUSED,
            VoiceState.RESUMING, VoiceState.CANCELLED, VoiceState.LISTENING,
            VoiceState.IDLE, VoiceState.ERROR
        ),
        VoiceState.GENERATING_RESPONSE to setOf(
            VoiceState.SPEAKING, VoiceState.INTERRUPTED, VoiceState.CANCELLED,
            VoiceState.ERROR, VoiceState.IDLE
        ),
        VoiceState.SPEAKING to setOf(
            VoiceState.INTERRUPTED, VoiceState.PAUSED, VoiceState.CANCELLED,
            VoiceState.RESUMING, VoiceState.LISTENING, VoiceState.IDLE, VoiceState.ERROR
        ),
        VoiceState.INTERRUPTED to setOf(
            VoiceState.AUTHENTICATING_SPEAKER, VoiceState.LISTENING,
            VoiceState.VALIDATING_COMMAND, VoiceState.PAUSED, VoiceState.CANCELLED,
            VoiceState.RESUMING, VoiceState.IDLE, VoiceState.ERROR
        ),
        VoiceState.PAUSED to setOf(
            VoiceState.RESUMING, VoiceState.LISTENING, VoiceState.CANCELLED,
            VoiceState.IDLE, VoiceState.ERROR
        ),
        VoiceState.RESUMING to setOf(
            VoiceState.SPEAKING, VoiceState.INTERRUPTED, VoiceState.PAUSED,
            VoiceState.CANCELLED, VoiceState.IDLE, VoiceState.ERROR
        ),
        VoiceState.CANCELLED to setOf(
            VoiceState.LISTENING, VoiceState.IDLE, VoiceState.WAKE_WORD_DETECTED, VoiceState.ERROR
        ),
        VoiceState.SESSION_TIMEOUT to setOf(
            VoiceState.IDLE, VoiceState.WAKE_WORD_DETECTED, VoiceState.LISTENING
        ),
        VoiceState.ERROR to setOf(
            VoiceState.IDLE, VoiceState.LISTENING, VoiceState.WAKE_WORD_DETECTED
        )
    )

    fun transitionTo(
        target: VoiceState,
        reason: String,
        sessionId: String? = _snapshot.value.sessionId
    ): Boolean = synchronized(lock) {
        val current = _snapshot.value.state
        if (current == target) {
            _snapshot.value = _snapshot.value.copy(
                lastReason = reason,
                updatedAtEpochMs = System.currentTimeMillis()
            )
            return true
        }
        val validTargets = allowedTransitions[current].orEmpty()
        if (target !in validTargets) {
            return false
        }
        val effectiveSession = when (target) {
            VoiceState.IDLE -> null
            else -> sessionId ?: UUID.randomUUID().toString()
        }
        _snapshot.value = VoiceSessionSnapshot(
            state = target,
            sessionId = effectiveSession,
            lastReason = reason,
            updatedAtEpochMs = System.currentTimeMillis()
        )
        return true
    }

    fun forceResetToIdle(reason: String = "Force reset") = synchronized(lock) {
        _snapshot.value = VoiceSessionSnapshot(
            state = VoiceState.IDLE,
            sessionId = null,
            lastReason = reason,
            updatedAtEpochMs = System.currentTimeMillis()
        )
    }
}

/**
 * Dedicated high-priority voice control layer (voice + instruction.md Section 7).
 * Evaluated BEFORE general conversational intent routing.
 */
enum class PriorityControlCommand {
    STOP_SPEECH,
    PAUSE_SPEECH,
    WAIT_AND_LISTEN,
    CONTINUE_SPEECH,
    REPEAT_LAST,
    SPEAK_FASTER,
    SPEAK_SLOWER,
    ASSISTANT_VOLUME_UP,
    ASSISTANT_VOLUME_DOWN,
    MUTE_ASSISTANT,
    UNMUTE_ASSISTANT,
    CANCEL_OPERATION
}

object VoicePriorityControlParser {

    fun parse(rawTranscript: String): PriorityControlCommand? {
        val cleaned = rawTranscript
            .trim()
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9\\s']"), " ")
            .replace(Regex("\\s+"), " ")
            .removePrefix("hey aura ")
            .removePrefix("aura ")
            .trim()

        if (cleaned.isEmpty()) return null

        return when (cleaned) {
            "stop", "stop speaking", "stop talking", "be quiet", "shut up" ->
                PriorityControlCommand.STOP_SPEECH
            "pause", "pause speaking", "hold on", "pause that" ->
                PriorityControlCommand.PAUSE_SPEECH
            "wait", "wait a second", "wait a moment", "hold on a second" ->
                PriorityControlCommand.WAIT_AND_LISTEN
            "continue", "resume", "go on", "keep going", "carry on", "continue reading" ->
                PriorityControlCommand.CONTINUE_SPEECH
            "repeat", "repeat that", "say that again", "say again" ->
                PriorityControlCommand.REPEAT_LAST
            "speak faster", "talk faster", "faster", "speed up" ->
                PriorityControlCommand.SPEAK_FASTER
            "speak slower", "talk slower", "slower", "slow down" ->
                PriorityControlCommand.SPEAK_SLOWER
            "volume up", "louder", "speak louder", "increase assistant volume" ->
                PriorityControlCommand.ASSISTANT_VOLUME_UP
            "volume down", "quieter", "speak softer", "softer", "decrease assistant volume" ->
                PriorityControlCommand.ASSISTANT_VOLUME_DOWN
            "mute yourself", "mute assistant", "mute voice" ->
                PriorityControlCommand.MUTE_ASSISTANT
            "unmute", "unmute yourself", "unmute assistant" ->
                PriorityControlCommand.UNMUTE_ASSISTANT
            "cancel", "cancel that", "never mind", "nevermind", "forget it" ->
                PriorityControlCommand.CANCEL_OPERATION
            else -> null
        }
    }
}

data class ConversationTurnContext(
    val isCurrentlySpeaking: Boolean = false,
    val activeTopic: String? = null,
    val lastAssistantUtterance: String? = null,
    val lastSpokenMediaOrNews: Boolean = false
)

data class UtteranceValidationInput(
    val transcript: String,
    val asrConfidence: Float,
    val speakerVerified: Boolean,
    val speakerConfidence: Float,
    val intentConfidence: Float,
    val userStillSpeaking: Boolean = false,
    val isBackgroundAudioSuspected: Boolean = false
)

sealed class CommandValidationDecision {
    data class Execute(val normalizedCommand: String, val resolvedFromContext: Boolean = false) :
        CommandValidationDecision()

    data class WaitOrAskCompletion(val prompt: String) : CommandValidationDecision()
    data class RequestClarification(val prompt: String) : CommandValidationDecision()
    data class RejectUnclear(val prompt: String) : CommandValidationDecision()
    data class RejectUnauthorizedSpeaker(val reason: String) : CommandValidationDecision()
}

/**
 * Intelligent command validation & completeness layer (voice + instruction.md Section 5).
 */
class CommandCompletenessValidator(
    private val config: VoiceEngineConfig = VoiceEngineConfig()
) {
    private val danglingPrefixes = setOf(
        "open", "call", "text", "message", "send", "search", "search for",
        "play", "remind me to", "set a reminder for", "explain", "tell me about"
    )

    private val ambiguousVolumePhrases = setOf(
        "turn it up", "turn it down", "make it louder", "make it quieter"
    )

    fun validate(
        input: UtteranceValidationInput,
        context: ConversationTurnContext
    ): CommandValidationDecision {
        // Case E: Reject unauthorized speaker or suspected TV/background audio
        if (input.isBackgroundAudioSuspected) {
            return CommandValidationDecision.RejectUnauthorizedSpeaker(
                "Suspected background/TV audio rejected."
            )
        }
        if (!input.speakerVerified || input.speakerConfidence < config.minSpeakerConfidence) {
            return CommandValidationDecision.RejectUnauthorizedSpeaker(
                "Speaker verification confidence ${input.speakerConfidence} below threshold ${config.minSpeakerConfidence}."
            )
        }

        val cleaned = input.transcript
            .trim()
            .replace(Regex("\\s+"), " ")
        val lower = cleaned
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9\\s']"), "")
            .removePrefix("hey aura ")
            .removePrefix("aura ")
            .trim()

        // Case D: Unclear recognition
        if (lower.isEmpty() || input.asrConfidence < config.minAsrConfidence) {
            return CommandValidationDecision.RejectUnclear(
                "I didn't catch that clearly. Could you please repeat your instruction?"
            )
        }

        // Case B: User still speaking or dangling incomplete prefix
        if (input.userStillSpeaking || lower in danglingPrefixes || lower.endsWith(" to") || lower.endsWith(" for")) {
            return CommandValidationDecision.WaitOrAskCompletion(
                "Your instruction seems incomplete. Could you please finish your command?"
            )
        }

        // Case F & C: Context-aware follow-up vs ambiguous instruction
        if (lower in ambiguousVolumePhrases) {
            if (context.isCurrentlySpeaking || context.lastSpokenMediaOrNews) {
                val resolved = if (lower.contains("up") || lower.contains("louder")) {
                    "volume up"
                } else {
                    "volume down"
                }
                return CommandValidationDecision.Execute(
                    normalizedCommand = resolved,
                    resolvedFromContext = true
                )
            }
            return CommandValidationDecision.RequestClarification(
                "Do you mean the assistant volume or the system volume?"
            )
        }

        if (input.intentConfidence < config.minIntentConfidence) {
            return CommandValidationDecision.RequestClarification(
                "I'm not sure what action you want. Could you clarify?"
            )
        }

        return CommandValidationDecision.Execute(normalizedCommand = cleaned)
    }
}

data class SpeechSegment(
    val index: Int,
    val text: String
)

data class SpeechTask(
    val taskId: String = UUID.randomUUID().toString(),
    val originalText: String,
    val segments: List<SpeechSegment>,
    val currentSegmentIndex: Int = 0,
    val interrupted: Boolean = false,
    val paused: Boolean = false,
    val cancelled: Boolean = false,
    val topicTag: String? = null
) {
    val completedSegments: List<SpeechSegment>
        get() = segments.take(currentSegmentIndex.coerceIn(0, segments.size))

    val remainingSegments: List<SpeechSegment>
        get() = segments.drop(currentSegmentIndex.coerceIn(0, segments.size))

    val isFinished: Boolean
        get() = cancelled || currentSegmentIndex >= segments.size
}

/**
 * Resumable, segmented speech task manager supporting barge-in checkpoints (voice + instruction.md Section 8)
 * and non-truncating long outputs (Architecture v0.22.0 Section 14.3).
 */
class SpeechTaskManager(
    private val maxSegmentChars: Int = 240
) {
    private val lock = Any()
    private var activeTask: SpeechTask? = null
    private val resumableStack = ArrayDeque<SpeechTask>()
    private var lastCompletedOrActiveText: String? = null

    fun startTask(text: String, topicTag: String? = null): SpeechTask = synchronized(lock) {
        val segments = segmentWithoutTruncation(text, maxSegmentChars)
        val task = SpeechTask(
            originalText = text,
            segments = segments,
            currentSegmentIndex = 0,
            topicTag = topicTag
        )
        activeTask = task
        lastCompletedOrActiveText = text
        task
    }

    fun currentTask(): SpeechTask? = synchronized(lock) { activeTask }

    fun currentSegment(): SpeechSegment? = synchronized(lock) {
        val task = activeTask ?: return null
        if (task.cancelled || task.paused || task.currentSegmentIndex >= task.segments.size) {
            null
        } else {
            task.segments[task.currentSegmentIndex]
        }
    }

    fun markCurrentSegmentSpoken(): SpeechSegment? = synchronized(lock) {
        val task = activeTask ?: return null
        if (task.cancelled) return null
        val nextIndex = task.currentSegmentIndex + 1
        val updated = task.copy(currentSegmentIndex = nextIndex)
        activeTask = if (updated.isFinished) null else updated
        activeTask?.segments?.getOrNull(nextIndex)
    }

    /**
     * Barge-in interruption: pauses active speech at exact current segment checkpoint and
     * preserves it on the resumable stack so a new urgent answer can play first.
     */
    fun interruptForBargeIn(): SpeechTask? = synchronized(lock) {
        val task = activeTask ?: return null
        if (task.isFinished) {
            activeTask = null
            return null
        }
        val saved = task.copy(interrupted = true, paused = true)
        resumableStack.push(saved)
        activeTask = null
        saved
    }

    fun pauseActive(): SpeechTask? = synchronized(lock) {
        val task = activeTask ?: return null
        val paused = task.copy(paused = true)
        activeTask = paused
        paused
    }

    /**
     * Explicit "Stop" / "Cancel": cancels active speech AND prevents unintended auto-resumption.
     */
    fun stopAndCancelAll(): SpeechTask? = synchronized(lock) {
        val cancelledTask = activeTask?.copy(cancelled = true, paused = false)
        activeTask = null
        resumableStack.clear()
        cancelledTask
    }

    /**
     * Resumes paused active task or pops the most recent interrupted task from its exact checkpoint.
     * Never repeats already completed segments.
     */
    fun resumeFromCheckpoint(): SpeechTask? = synchronized(lock) {
        val current = activeTask
        if (current != null && !current.cancelled && current.paused && !current.isFinished) {
            val resumed = current.copy(paused = false, interrupted = false)
            activeTask = resumed
            return resumed
        }
        while (resumableStack.isNotEmpty()) {
            val candidate = resumableStack.pop()
            if (!candidate.cancelled && !candidate.isFinished) {
                val resumed = candidate.copy(paused = false, interrupted = false)
                activeTask = resumed
                return resumed
            }
        }
        null
    }

    fun hasResumableTask(): Boolean = synchronized(lock) {
        val activePaused = activeTask?.let { !it.cancelled && it.paused && !it.isFinished } == true
        activePaused || resumableStack.any { !it.cancelled && !it.isFinished }
    }

    fun repeatLastText(): String? = synchronized(lock) { lastCompletedOrActiveText }

    companion object {
        /**
         * Segments arbitrarily long text into speakable chunks while preserving 100% of characters.
         */
        fun segmentWithoutTruncation(text: String, maxChars: Int = 240): List<SpeechSegment> {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return emptyList()
            val limit = maxChars.coerceAtLeast(40)
            val rawSentences = trimmed.split(Regex("(?<=[.!?\\n])\\s+"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            val chunks = mutableListOf<String>()
            for (sentence in rawSentences) {
                if (sentence.length <= limit) {
                    chunks.add(sentence)
                } else {
                    var start = 0
                    while (start < sentence.length) {
                        val endCandidate = (start + limit).coerceAtMost(sentence.length)
                        val splitAt = if (endCandidate < sentence.length) {
                            val lastSpace = sentence.lastIndexOf(' ', endCandidate)
                            if (lastSpace > start + 20) lastSpace else endCandidate
                        } else {
                            endCandidate
                        }
                        val piece = sentence.substring(start, splitAt).trim()
                        if (piece.isNotEmpty()) chunks.add(piece)
                        start = if (splitAt < sentence.length && sentence[splitAt] == ' ') splitAt + 1 else splitAt
                    }
                }
            }
            return chunks.mapIndexed { idx, chunk -> SpeechSegment(index = idx, text = chunk) }
        }
    }
}
