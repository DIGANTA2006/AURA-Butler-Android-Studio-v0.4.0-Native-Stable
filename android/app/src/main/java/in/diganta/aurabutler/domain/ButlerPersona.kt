package `in`.diganta.aurabutler.domain

import `in`.diganta.aurabutler.model.ButlerSettings
import java.util.Locale
import kotlin.math.abs

/** Original dry-butler banter: restrained, useful and never added to serious content. */
object ButlerPersona {
    fun style(
        text: String,
        intent: String,
        settings: ButlerSettings,
        request: String
    ): String {
        val clean = text.trim()
        if (
            clean.isBlank() ||
            !settings.butlerBanterEnabled ||
            settings.butlerBanterLevel <= 0 ||
            clean.length > 1_200 ||
            isSerious(intent, request, clean)
        ) {
            return clean
        }

        val line = lineFor(intent, request, settings.formOfAddress) ?: return clean
        val selector = abs((intent + "|" + request.lowercase(Locale.ROOT)).hashCode()) % 4
        val shouldAdd = settings.butlerBanterLevel >= 2 || selector == 0
        return if (shouldAdd && !clean.contains(line)) "$clean $line" else clean
    }

    private fun isSerious(intent: String, request: String, reply: String): Boolean {
        val combined = "$intent $request $reply".lowercase(Locale.ROOT)
        return SERIOUS_TERMS.any(combined::contains)
    }

    private fun lineFor(intent: String, request: String, address: String): String? {
        val key = "$intent ${request.lowercase(Locale.ROOT)}"
        return when {
            key.contains("volume") ->
                "Audio adjusted, $address. The neighbors remain outside the incident radius."
            key.contains("open_app") || key.contains("youtube") || key.contains("play_media") ->
                "Done, $address. A surprisingly cooperative piece of software."
            key.contains("capture_screen") || key.contains("screenshot") ->
                "Captured, $address. The pixels offered minimal resistance."
            key.contains("cache") ->
                "Digital dust addressed. No microscopic broom was required."
            key.contains("cooling") || key.contains("thermal") ->
                "Cooling assist prepared. Even silicon benefits from a dignified pause."
            key.contains("flashlight") ->
                "Illumination handled. Subtlety was apparently not on the agenda."
            key.contains("notification_digest") || key.contains("whatsapp") ->
                "I separated signal from noise. The noise was, as usual, enthusiastic."
            key.contains("analyze_screen") || key.contains("analyze_image") ->
                "Analysis complete, $address. The screen has confessed what it knows."
            key.contains("greet") || key.contains("chitchat") ->
                "All systems civilised and ready, $address."
            else -> null
        }
    }

    private val SERIOUS_TERMS = setOf(
        "critical", "urgent", "emergency", "fraud", "security", "otp", "password",
        "payment failed", "transaction", "medical", "hospital", "exam cancelled",
        "deadline", "research", "news", "source", "error", "failed", "blocked"
    )
}
