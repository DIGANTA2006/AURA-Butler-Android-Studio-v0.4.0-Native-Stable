package `in`.diganta.aurabutler.architecture.voice

import java.util.Locale

/**
 * High-priority voice control parser for immediate commands during TTS playback.
 * These commands bypass normal NLU and are handled directly by VoiceConversationEngine.
 */
object VoicePriorityControlParser {
    enum class PriorityControlCommand {
        NONE,
        STOP_SPEECH,
        PAUSE_SPEECH,
        RESUME_SPEECH,
        STOP_LISTENING,
        VOLUME_UP,
        VOLUME_DOWN,
        RATE_FASTER,
        RATE_SLOWER,
        MUTE,
        UNMUTE,
        REPEAT_LAST
    }

    fun parse(transcript: String): PriorityControlCommand {
        val normalized = transcript.trim().lowercase(Locale.ROOT)
            .replace(Regex("""^(?:hey\s+)?aura[,:\s]*"""), "")
            .trim()

        return when {
            normalized.matches(Regex("""(?i)^(stop|cancel|shut up|be quiet|halt)$""")) -> PriorityControlCommand.STOP_SPEECH
            normalized.matches(Regex("""(?i)^(pause|wait|hold on|hold up)$""")) -> PriorityControlCommand.PAUSE_SPEECH
            normalized.matches(Regex("""(?i)^(continue|resume|go on|carry on)$""")) -> PriorityControlCommand.RESUME_SPEECH
            normalized.matches(Regex("""(?i)^(stop listening|sleep|go to sleep|shutdown listening)$""")) -> PriorityControlCommand.STOP_LISTENING
            normalized.matches(Regex("""(?i)^(volume up|louder|turn it up|increase volume)$""")) -> PriorityControlCommand.VOLUME_UP
            normalized.matches(Regex("""(?i)^(volume down|quieter|turn it down|decrease volume|lower volume)$""")) -> PriorityControlCommand.VOLUME_DOWN
            normalized.matches(Regex("""(?i)^(speak faster|talk faster|faster|speed up)$""")) -> PriorityControlCommand.RATE_FASTER
            normalized.matches(Regex("""(?i)^(speak slower|talk slower|slower|slow down)$""")) -> PriorityControlCommand.RATE_SLOWER
            normalized.matches(Regex("""(?i)^(mute yourself|mute assistant|mute voice|silence)$""")) -> PriorityControlCommand.MUTE
            normalized.matches(Regex("""(?i)^(unmute|unmute yourself|unmute assistant|unmute voice)$""")) -> PriorityControlCommand.UNMUTE
            normalized.matches(Regex("""(?i)^(repeat that|say that again|repeat last|say again)$""")) -> PriorityControlCommand.REPEAT_LAST
            else -> PriorityControlCommand.NONE
        }
    }
}
