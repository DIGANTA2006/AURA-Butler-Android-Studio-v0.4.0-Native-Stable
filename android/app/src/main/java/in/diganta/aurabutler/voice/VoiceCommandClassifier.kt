package `in`.diganta.aurabutler.voice

import java.util.Locale

/**
 * Conservative interpretation of high-priority speech controls and obviously
 * incomplete utterances. This deliberately avoids guessing missing parameters.
 */
object VoiceCommandClassifier {
    enum class ControlIntent {
        STOP_LISTENING,
        STOP_SPEECH,
        PAUSE_SPEECH,
        RESUME_SPEECH,
        NONE
    }

    data class Assessment(
        val isComplete: Boolean,
        val reason: String
    )

    private val stopListeningPhrases = setOf(
        "stop listening",
        "disable wake word",
        "turn off wake word",
        "turn wake word off",
        "go to sleep"
    )

    private val stopSpeechPhrases = setOf(
        "stop",
        "stop talking",
        "stop speaking",
        "be quiet",
        "cancel speech",
        "cancel that",
        "enough"
    )

    private val pauseSpeechPhrases = setOf(
        "pause",
        "pause speaking",
        "pause that",
        "wait",
        "hold on"
    )

    private val resumeSpeechPhrases = setOf(
        "continue",
        "continue talking",
        "resume",
        "resume speaking",
        "carry on",
        "go ahead"
    )

    private val trailingContinuationWords = setOf(
        "and", "or", "but", "because", "with", "from",
        "for", "to", "if", "when", "then", "a", "an",
        "the", "my", "your"
    )

    private val incompletePhrases = setOf(
        "tell me",
        "show me",
        "read me",
        "what is",
        "what are",
        "who is",
        "who are",
        "where is",
        "where are",
        "when is",
        "why is",
        "how do",
        "how does",
        "how can",
        "how many",
        "how much",
        "what about",
        "can you",
        "could you",
        "would you",
        "search for",
        "look up",
        "open the",
        "play the",
        "send the",
        "send a",
        "set a",
        "set the",
        "set timer",
        "set alarm",
        "set reminder",
        "turn on",
        "turn off",
        "make it"
    )

    private val commandsRequiringAnObject = setOf(
        "open", "play", "search", "find", "send", "call",
        "set", "turn", "increase", "decrease", "tell",
        "show", "read", "explain", "summarize", "create",
        "delete", "calculate", "make", "write", "check",
        "download"
    )

    fun controlIntent(raw: String): ControlIntent {
        val phrase = normalize(raw)
        if (phrase.isBlank()) return ControlIntent.NONE

        return when {
            phrase in stopListeningPhrases -> ControlIntent.STOP_LISTENING
            phrase in stopSpeechPhrases -> ControlIntent.STOP_SPEECH
            phrase in pauseSpeechPhrases -> ControlIntent.PAUSE_SPEECH
            phrase in resumeSpeechPhrases -> ControlIntent.RESUME_SPEECH
            else -> ControlIntent.NONE
        }
    }

    fun assess(raw: String): Assessment {
        val phrase = normalize(raw)
        if (phrase.isBlank()) {
            return Assessment(false, "The transcript is empty.")
        }

        if (controlIntent(phrase) != ControlIntent.NONE) {
            return Assessment(true, "Recognized a complete voice-control command.")
        }

        val tokens = phrase.split(' ')
        val lastToken = tokens.last()

        if (lastToken in trailingContinuationWords) {
            return Assessment(false, "The utterance ends with a connector or incomplete object.")
        }

        if (phrase in incompletePhrases) {
            return Assessment(false, "The utterance matches a known incomplete command prefix.")
        }

        if (tokens.size == 1 && tokens.first() in commandsRequiringAnObject) {
            return Assessment(false, "The command verb is missing its required target or parameter.")
        }

        return Assessment(true, "No obvious incompleteness was detected.")
    }

    private fun normalize(raw: String): String {
        val tokens = raw.lowercase(Locale.ROOT)
            .split(Regex("\\s+"))
            .map { token -> token.trim { character -> !character.isLetterOrDigit() } }
            .filter(String::isNotBlank)
            .toMutableList()

        if (tokens.size >= 2 && tokens[0] == "hey" &&
            tokens[1] in setOf("aura", "ora", "aurah")
        ) {
            repeat(2) { tokens.removeAt(0) }
        } else if (tokens.firstOrNull()?.let { it in setOf("aura", "ora", "aurah") } == true) {
            tokens.removeAt(0)
        }

        while (tokens.firstOrNull()?.let { it in setOf("please", "kindly") } == true) {
            tokens.removeAt(0)
        }
        while (tokens.lastOrNull()?.let { it in setOf("please", "now") } == true) {
            tokens.removeAt(tokens.lastIndex)
        }

        return tokens.joinToString(" ")
    }
}
