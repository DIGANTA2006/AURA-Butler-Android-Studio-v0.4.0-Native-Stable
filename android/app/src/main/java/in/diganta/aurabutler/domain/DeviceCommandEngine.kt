package `in`.diganta.aurabutler.domain

import android.content.Context
import `in`.diganta.aurabutler.device.DeviceHealthManager
import `in`.diganta.aurabutler.model.ActionKind
import `in`.diganta.aurabutler.model.ActionRisk
import `in`.diganta.aurabutler.model.ButlerAction
import `in`.diganta.aurabutler.model.ButlerReply
import `in`.diganta.aurabutler.model.ConversationContext
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID

class DeviceCommandEngine(context: Context) {
    private val health = DeviceHealthManager(context)

    fun handle(message: String, context: ConversationContext): ButlerReply? {
        val normalized = message.lowercase(Locale.ROOT)
            .replace(Regex("""\s+"""), " ")
            .trim()

        parseScreenshot(normalized)?.let { return it }
        parseCache(message, normalized)?.let { return it }
        parseCooling(normalized)?.let { return it }
        parseVolume(message, normalized)?.let { return it }
        parseYouTube(message, normalized)?.let { return it }
        return null
    }

    private fun parseScreenshot(normalized: String): ButlerReply? {
        if (!normalized.containsAny(
                "take screenshot", "take a screenshot", "capture screenshot",
                "save screenshot", "screenshot this", "capture my screen",
                "save this screen", "screenshot", "screen shot"
            )
        ) {
            return null
        }
        return actionReply(
            text = "I prepared a one-time screenshot. Android will ask what screen or app you want to share.",
            action = action(
                kind = ActionKind.CAPTURE_SCREEN,
                title = "Save a screenshot",
                summary = "Capture one user-approved screen frame and save it in Pictures/AURA.",
                risk = ActionRisk.MEDIUM,
                payload = mapOf("mode" to "save")
            ),
            intent = "capture_screen"
        )
    }

    private fun parseCache(message: String, normalized: String): ButlerReply? {
        if (!normalized.contains("cache") || !normalized.containsAny("clean", "clear", "remove")) {
            return null
        }

        if (
            normalized.containsAny("aura cache", "assistant cache", "your cache") ||
            normalized in setOf(
                "cache clean", "clean cache", "clear cache", "remove cache"
            )
        ) {
            return actionReply(
                text = "I prepared a safe AURA-cache cleanup. Settings, memory, API keys and notification history will not be deleted.",
                action = action(
                    kind = ActionKind.CLEAR_AURA_CACHE,
                    title = "Clean AURA cache",
                    summary = "Delete only temporary files created by AURA.",
                    risk = ActionRisk.MEDIUM,
                    payload = emptyMap()
                ),
                intent = "clear_aura_cache"
            )
        }

        if (normalized.containsAny("phone cache", "device cache", "all cache", "system cache")) {
            return actionReply(
                text = "Android does not allow an ordinary app to silently clear every app's cache. I prepared the phone's Storage settings instead.",
                action = action(
                    kind = ActionKind.OPEN_SYSTEM_SETTING,
                    title = "Open Storage settings",
                    summary = "Open Android internal-storage controls for manual cleanup.",
                    risk = ActionRisk.LOW,
                    payload = mapOf("setting" to "storage")
                ),
                intent = "open_storage_settings"
            )
        }

        val app = Regex(
            """(?i)(?:clear|clean|remove)\s+(?:the\s+)?(.+?)\s+(?:app\s+)?cache\b"""
        ).find(message)?.groupValues?.getOrNull(1)
            ?.trim()
            ?.replace(Regex("""(?i)\b(?:please|for)\b"""), " ")
            ?.replace(Regex("""\s+"""), " ")
            ?.trim()
            ?.takeIf { it.isNotBlank() && it.length <= 80 }
            ?: return null

        return actionReply(
            text = "Android requires you to press Clear cache yourself. I prepared $app's app-information screen.",
            action = action(
                kind = ActionKind.OPEN_APP_STORAGE,
                title = "Open $app storage controls",
                summary = "Open Android app information for $app so you can choose Storage and cache.",
                risk = ActionRisk.LOW,
                payload = mapOf("app" to app)
            ),
            intent = "open_app_storage"
        )
    }

    private fun parseCooling(normalized: String): ButlerReply? {
        val statusOnly = normalized.containsAny(
            "phone temperature", "device temperature", "battery temperature",
            "thermal status", "how hot is my phone", "is my phone overheating"
        )
        if (statusOnly) {
            return localReply(
                text = health.report(),
                intent = "thermal_status"
            )
        }

        if (!normalized.containsAny(
                "cooling mode", "start cooling", "cool down phone",
                "cool my phone", "phone is overheating", "reduce phone heat",
                "cooling assist", "cooling", "cool phone"
            )
        ) {
            return null
        }

        return actionReply(
            text = health.report() + " I prepared Cooling Assist. It can reduce display power use and brightness, then open Battery Saver; it cannot directly control sealed phone hardware.",
            action = action(
                kind = ActionKind.COOLING_ASSIST,
                title = "Start Cooling Assist",
                summary = "Reduce brightness when permitted and open Android Battery Saver controls.",
                risk = ActionRisk.MEDIUM,
                payload = emptyMap()
            ),
            intent = "cooling_assist"
        )
    }

    private fun parseVolume(message: String, normalized: String): ButlerReply? {
        if (!normalized.containsAny("volume", "louder", "quieter", "mute", "unmute")) {
            return null
        }

        val stream = when {
            normalized.containsAny("alarm volume", "alarm sound") -> "alarm"
            normalized.containsAny("ring volume", "ringtone volume", "ringer volume") -> "ring"
            normalized.containsAny("notification volume", "message volume") -> "notification"
            normalized.containsAny("call volume", "voice call volume", "earpiece volume") -> "call"
            normalized.containsAny("system volume") -> "system"
            else -> "media"
        }

        val explicit = Regex(
            """(?i)\b(?:volume|sound)(?:\s+(?:to|at))?\s*(\d{1,3})\s*%?"""
        ).find(message)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex(
                """(?i)\b(?:set|make)\s+(?:the\s+)?(?:media|ringtone|ringer|alarm|notification|call|system)?\s*volume\s+(?:to|at)?\s*(\d{1,3})\s*%?"""
            ).find(message)?.groupValues?.getOrNull(1)?.toIntOrNull()

        val operation = when {
            normalized.contains("unmute") -> "unmute"
            normalized.contains("mute") -> "mute"
            explicit != null -> "set"
            normalized.containsAny("maximum volume", "full volume", "volume max") -> "max"
            normalized.containsAny("minimum volume", "volume minimum") -> "min"
            normalized.containsAny("volume up", "increase volume", "raise volume", "louder") -> "up"
            normalized.containsAny("volume down", "decrease volume", "lower volume", "quieter") -> "down"
            else -> return null
        }

        if (explicit != null && explicit !in 0..100) {
            return localReply("Volume must be between 0 and 100 percent.", "system_volume")
        }

        val description = when (operation) {
            "set" -> "${explicit}%"
            "max" -> "maximum"
            "min" -> "minimum"
            else -> operation
        }
        return actionReply(
            text = "Changing $stream volume to $description.",
            action = action(
                kind = ActionKind.SYSTEM_VOLUME,
                title = "Change $stream volume",
                summary = "Set $stream volume action to $description.",
                risk = ActionRisk.LOW,
                payload = buildMap {
                    put("action", operation)
                    put("stream", stream)
                    explicit?.let { put("amount", it.toString()) }
                }
            ),
            intent = "system_volume"
        )
    }

    private fun parseYouTube(message: String, normalized: String): ButlerReply? {
        val exactUrl = Regex(
            """(?i)https://(?:www\.)?(?:youtube\.com|youtu\.be)/\S+"""
        ).find(message)?.value?.trimEnd('.', ',', ')')
        if (exactUrl != null && normalized.containsAny("open", "play", "youtube", "video")) {
            return mediaReply(exactUrl, "Open the requested YouTube video", "youtube_exact_video")
        }

        if (!normalized.contains("youtube")) return null
        if (normalized.matches(Regex("""(?i)^(?:please\s+)?(?:open|launch|start|show)\s+(?:the\s+)?youtube(?:\s+app)?[.!?]?$"""))) {
            return null
        }

        val patterns = listOf(
            Regex("""(?i)^(?:please\s+)?(?:open|play|find|search)\s+(?:a\s+|the\s+)?(?:specific\s+)?(?:youtube\s+)?(?:video\s+)?(?:for\s+)?(.+?)\s+(?:on|in)\s+youtube\s*[.!?]*$"""),
            Regex("""(?i)^(?:please\s+)?(?:open|play|find|search)\s+youtube\s+(?:for\s+)?(?:a\s+|the\s+)?(?:specific\s+)?(?:video\s+)?(.+?)\s*[.!?]*$"""),
            Regex("""(?i)^(?:please\s+)?(?:play|open)\s+(?:the\s+)?youtube\s+video\s+(.+?)\s*[.!?]*$""")
        )
        val query = (patterns.firstNotNullOfOrNull { pattern ->
            pattern.find(message)?.groupValues?.getOrNull(1)?.trim()
        } ?: Regex("""(?i)^youtube\s+(?:video\s+)?(.+?)\s*[.!?]*$""")
            .find(message)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim())
            ?.replace(Regex("""(?i)^(?:for|about|of)\s+"""), "")
            ?.trim()
            ?.takeIf { it.isNotBlank() && it.length <= 300 }
            ?: return null

        val url = "https://www.youtube.com/results?search_query=${encode(query)}"
        return mediaReply(
            url = url,
            title = "Find $query on YouTube",
            intent = "youtube_video_search",
            text = "Opening YouTube results for “$query”."
        )
    }

    private fun mediaReply(
        url: String,
        title: String,
        intent: String,
        text: String = "Opening the requested YouTube video."
    ): ButlerReply = actionReply(
        text = text,
        action = action(
            kind = ActionKind.PLAY_MEDIA,
            title = title,
            summary = "Open a secure YouTube link or search result.",
            risk = ActionRisk.LOW,
            payload = mapOf("url" to url)
        ),
        intent = intent
    )

    private fun actionReply(
        text: String,
        action: ButlerAction,
        intent: String
    ): ButlerReply = ButlerReply(
        assistantMessage = text,
        speechText = text,
        pendingActions = listOf(action),
        memoryWrites = emptyList(),
        provider = "local-device-intelligence",
        intent = intent
    )

    private fun localReply(text: String, intent: String): ButlerReply = ButlerReply(
        assistantMessage = text,
        speechText = text,
        pendingActions = emptyList(),
        memoryWrites = emptyList(),
        provider = "local-device-intelligence",
        intent = intent
    )

    private fun action(
        kind: ActionKind,
        title: String,
        summary: String,
        risk: ActionRisk,
        payload: Map<String, String>
    ): ButlerAction = ButlerAction(
        id = UUID.randomUUID().toString(),
        kind = kind,
        title = title,
        summary = summary,
        risk = risk,
        payload = payload
    )

    private fun encode(value: String): String = URLEncoder.encode(
        value,
        StandardCharsets.UTF_8.name()
    )

    private fun String.containsAny(vararg values: String): Boolean =
        values.any { contains(it, ignoreCase = true) }
}
