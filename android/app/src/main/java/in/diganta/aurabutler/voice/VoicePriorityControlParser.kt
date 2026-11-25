package `in`.diganta.aurabutler.voice

/**
 * High-priority voice control parser for dedicated voice control layer.
 * Handles: stop, pause, wait, continue, resume, repeat that, speak faster,
 * speak slower, volume up, volume down, mute yourself, unmute, cancel that
 * 
 * These commands are processed BEFORE general NLU and have highest voice priority.
 */
object VoicePriorityControlParser {

    enum class PriorityControlCommand {
        STOP_SPEECH,           // "stop talking", "be quiet", "cancel speech"
        PAUSE_SPEECH,          // "pause", "wait", "hold on"
        RESUME_SPEECH,         // "continue", "resume", "carry on"
        STOP_LISTENING,        // "stop listening", "go to sleep", "shut down"
        VOLUME_UP,             // "volume up", "louder", "turn it up"
        VOLUME_DOWN,           // "volume down", "quieter", "turn it down"
        RATE_FASTER,           // "speak faster", "faster", "speed up"
        RATE_SLOWER,           // "speak slower", "slower", "slow down"
        MUTE,                  // "mute yourself", "mute", "silence"
        UNMUTE,                // "unmute", "unmute yourself"
        REPEAT_LAST,           // "repeat that", "say that again", "repeat"
        NONE                   // Not a priority control command
    }

    private val stopSpeechPatterns = listOf(
        Regex("""(?i)^(stop talking|be quiet|cancel speech|shut up|stop it)$"""),
        Regex("""(?i)^(stop|halt|end)\s+(speaking|talking|the speech)$""")
    )

    private val pauseSpeechPatterns = listOf(
        Regex("""(?i)^(pause|wait|hold on|hold up|just a moment|one moment)$"""),
        Regex("""(?i)^(pause|wait)\s+(the\s+)?(speech|talking|speaking)$""")
    )

    private val resumeSpeechPatterns = listOf(
        Regex("""(?i)^(continue|resume|carry on|go on|keep going)$"""),
        Regex("""(?i)^(continue|resume)\s+(the\s+)?(speech|talking|speaking)$""")
    )

    private val stopListeningPatterns = listOf(
        Regex("""(?i)^(stop listening|go to sleep|shut down|sleep mode|standby)$"""),
        Regex("""(?i)^(disable|turn off)\s+(wake|listening|voice)$""")
    )

    private val volumeUpPatterns = listOf(
        Regex("""(?i)^(volume up|louder|turn it up|turn up the volume|increase volume)$"""),
        Regex("""(?i)^(make it louder|turn the volume up)$""")
    )

    private val volumeDownPatterns = listOf(
        Regex("""(?i)^(volume down|quieter|turn it down|turn down the volume|decrease volume)$"""),
        Regex("""(?i)^(make it quieter|turn the volume down)$""")
    )

    private val rateFasterPatterns = listOf(
        Regex("""(?i)^(speak faster|faster|speed up|talk faster)$"""),
        Regex("""(?i)^(increase speed|speak more quickly)$""")
    )

    private val rateSlowerPatterns = listOf(
        Regex("""(?i)^(speak slower|slower|slow down|talk slower)$"""),
        Regex("""(?i)^(decrease speed|speak more slowly)$""")
    )

    private val mutePatterns = listOf(
        Regex("""(?i)^(mute yourself|mute|silence|be silent)$"""),
        Regex("""(?i)^(turn off the voice|disable voice)$""")
    )

    private val unmutePatterns = listOf(
        Regex("""(?i)^(unmute|unmute yourself|turn on voice|enable voice)$"""),
        Regex("""(?i)^(speak again|start speaking)$""")
    )

    private val repeatLastPatterns = listOf(
        Regex("""(?i)^(repeat that|say that again|repeat|say it again)$"""),
        Regex("""(?i)^(what did you say|repeat the last)$""")
    )

    fun parse(transcript: String): PriorityControlCommand {
        val text = transcript.trim().lowercase()

        // Check in priority order (most specific first)
        if (stopSpeechPatterns.any { it.matches(text) }) return PriorityControlCommand.STOP_SPEECH
        if (pauseSpeechPatterns.any { it.matches(text) }) return PriorityControlCommand.PAUSE_SPEECH
        if (resumeSpeechPatterns.any { it.matches(text) }) return PriorityControlCommand.RESUME_SPEECH
        if (stopListeningPatterns.any { it.matches(text) }) return PriorityControlCommand.STOP_LISTENING
        if (volumeUpPatterns.any { it.matches(text) }) return PriorityControlCommand.VOLUME_UP
        if (volumeDownPatterns.any { it.matches(text) }) return PriorityControlCommand.VOLUME_DOWN
        if (rateFasterPatterns.any { it.matches(text) }) return PriorityControlCommand.RATE_FASTER
        if (rateSlowerPatterns.any { it.matches(text) }) return PriorityControlCommand.RATE_SLOWER
        if (mutePatterns.any { it.matches(text) }) return PriorityControlCommand.MUTE
        if (unmutePatterns.any { it.matches(text) }) return PriorityControlCommand.UNMUTE
        if (repeatLastPatterns.any { it.matches(text) }) return PriorityControlCommand.REPEAT_LAST

        return PriorityControlCommand.NONE
    }

    /**
     * Checks if a transcript contains a priority control command anywhere in it.
     * Useful for detecting controls embedded in longer utterances.
     */
    fun containsPriorityControl(transcript: String): PriorityControlCommand? {
        val text = transcript.trim().lowercase()
        
        val allPatterns = listOf(
            stopSpeechPatterns to PriorityControlCommand.STOP_SPEECH,
            pauseSpeechPatterns to PriorityControlCommand.PAUSE_SPEECH,
            resumeSpeechPatterns to PriorityControlCommand.RESUME_SPEECH,
            stopListeningPatterns to PriorityControlCommand.STOP_LISTENING,
            volumeUpPatterns to PriorityControlCommand.VOLUME_UP,
            volumeDownPatterns to PriorityControlCommand.VOLUME_DOWN,
            rateFasterPatterns to PriorityControlCommand.RATE_FASTER,
            rateSlowerPatterns to PriorityControlCommand.RATE_SLOWER,
            mutePatterns to PriorityControlCommand.MUTE,
            unmutePatterns to PriorityControlCommand.UNMUTE,
            repeatLastPatterns to PriorityControlCommand.REPEAT_LAST
        )

        for ((patterns, command) in allPatterns) {
            if (patterns.any { it.matches(text) }) {
                return command
            }
        }
        return null
    }
}
