package `in`.diganta.aurabutler.model

import org.json.JSONObject
import java.util.UUID
import `in`.diganta.aurabutler.intelligence.ProactiveEngine

enum class Sender {
    USER,
    AURA
}

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val sender: Sender,
    val text: String,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val provider: String = "",
    val intent: String = "",
    val latencyMs: Long = 0L
)

enum class ActionKind(val wireName: String) {
    OPEN_URL("open_url"),
    DIAL_PHONE("dial_phone"),
    MAP_ROUTE("map_route"),
    CALENDAR_EVENT("calendar_event"),
    SET_ALARM("set_alarm"),
    DRAFT_MESSAGE("draft_message"),
    SHARE_TEXT("share_text"),
    OPEN_APP("open_app"),
    OPEN_SYSTEM_SETTING("open_system_setting"),
    PLAY_MEDIA("play_media"),
    COMPOSE_EMAIL("compose_email"),
    WHATSAPP_MESSAGE("whatsapp_message"),
    SET_TIMER("set_timer"),
    SYSTEM_VOLUME("system_volume"),
    SYSTEM_BRIGHTNESS("system_brightness"),
    FLASHLIGHT("flashlight"),
    OPEN_CAMERA("open_camera"),
    FOOD_SEARCH("food_search"),
    TRAVEL_SEARCH("travel_search"),
    SHOPPING_SEARCH("shopping_search"),
    ANALYZE_SCREEN("analyze_screen"),
    CAPTURE_SCREEN("capture_screen"),
    ANALYZE_IMAGE("analyze_image"),
    IDENTIFY_SONG("identify_song"),
    OPEN_APP_STORAGE("open_app_storage"),
    CLEAR_AURA_CACHE("clear_aura_cache"),
    COOLING_ASSIST("cooling_assist"),
    REMINDER("reminder");

    companion object {
        fun fromWireName(value: String): ActionKind? = entries.firstOrNull {
            it.wireName == value
        }
    }
}

enum class ActionRisk {
    LOW,
    MEDIUM,
    HIGH;

    companion object {
        fun fromWireName(value: String): ActionRisk = when (value.lowercase()) {
            "high" -> HIGH
            "medium" -> MEDIUM
            else -> LOW
        }
    }
}

data class ButlerAction(
    val id: String,
    val kind: ActionKind,
    val title: String,
    val summary: String,
    val risk: ActionRisk,
    val payload: Map<String, String>,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val expiresAtEpochMs: Long = createdAtEpochMs + if (risk == ActionRisk.HIGH) {
        5 * 60_000L
    } else {
        10 * 60_000L
    }
) {
    fun isExpired(nowEpochMs: Long = System.currentTimeMillis()): Boolean =
        expiresAtEpochMs <= nowEpochMs
}

data class MemoryItem(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val value: String,
    val createdAtEpochMs: Long = System.currentTimeMillis()
)

data class ReminderItem(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val triggerAtEpochMs: Long,
    val createdAtEpochMs: Long = System.currentTimeMillis()
)

data class ActionHistoryItem(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val detail: String,
    val approved: Boolean,
    val createdAtEpochMs: Long = System.currentTimeMillis()
)

/**
 * Intelligence mode for the AI system.
 * MINIMAL: Local commands only, no AI reasoning
 * BALANCED: AI reasoning for complex queries, on-demand planning
 * ENHANCED: Full reasoning, planning, memory, proactive (confirmation required)
 * AUTONOMOUS: Full intelligence with auto-execution for high-confidence actions
 */
enum class IntelligenceMode(val wireName: String) {
    MINIMAL("minimal"),
    BALANCED("balanced"),
    ENHANCED("enhanced"),
    AUTONOMOUS("autonomous");

    companion object {
        fun fromWireName(value: String): IntelligenceMode = entries.firstOrNull { it.wireName == value } ?: BALANCED
    }
}

data class ButlerSettings(
    val preferredName: String = "Diganta",
    val formOfAddress: String = "Diganta",
    val homeCity: String = "Kadra, Chandrakona Road",
    val homeLatitude: Double = 22.7369,
    val homeLongitude: Double = 87.3365,
    val spokenReplies: Boolean = true,
    val backgroundReminders: Boolean = true,
    val wakeWordEnabled: Boolean = false,
    val ownerVoiceLockEnabled: Boolean = false,
    val ownerVoiceThreshold: Float = 0.56f,
    val speechLanguage: String = "auto",
    val aiEnabled: Boolean = false,
    val aiProvider: String = "automatic",
    val allowPublicAiFallback: Boolean = false,
    val geminiApiKey: String = "",
    val geminiModel: String = "gemini-3.5-flash-lite",
    val groqApiKey: String = "",
    val aiModel: String = "openai/gpt-oss-20b",
    val localAiEnabled: Boolean = false,
    val localAiEndpoint: String =
        "https://diganta2006--aura-dual-model-server-qwentext-web.modal.run/v1/chat/completions",
    val localAiModel: String = "Qwen/Qwen3-8B",
    val localAiApiKey: String = "",
    val privateVisionEnabled: Boolean = true,
    val privateVisionEndpoint: String =
        "https://diganta2006--aura-dual-model-server-gemmavision-web.modal.run/v1/chat/completions",
    val privateVisionModel: String = "google/gemma-3-4b-it",
    val allowAiPrivateContext: Boolean = false,
    val responseStyle: String = "professional",
    val analyzerEnabled: Boolean = true,
    val notificationCaptureEnabled: Boolean = true,
    val cloudAnalyzerSummaries: Boolean = false,
    val proactiveAnalyzerAlerts: Boolean = true,
    val importantMessageVoiceAlerts: Boolean = true,
    val importantMessageSpeakDetailsWhenLocked: Boolean = false,
    val scheduledAnalyzerDigests: Boolean = true,
    val notificationRetentionDays: Int = 7,
    val screenAnalysisEnabled: Boolean = true,
    val researchEnabled: Boolean = true,
    val researchEndpoint: String =
        "https://diganta2006--aura-dual-model-server-researchgateway-web.modal.run/v1/research",
    val researchMode: String = "quick",
    val temporalMemoryEnabled: Boolean = true,
    val smartCommandCorrection: Boolean = true,
    val aiCommandRepairEnabled: Boolean = true,
    val autoPlayYouTube: Boolean = true,
    val youtubeApiKey: String = "",
    val gnewsApiKey: String = "",
    val butlerBanterEnabled: Boolean = true,
    val butlerBanterLevel: Int = 1,
    // Intelligence settings (v0.18+)
    val intelligenceMode: IntelligenceMode = IntelligenceMode.BALANCED,
    val proactiveSuggestionsEnabled: Boolean = true,
    val proactiveAutoExecute: Boolean = false,
    val semanticMemoryEnabled: Boolean = true,
    val memoryConsolidationEnabled: Boolean = true,
    val reasoningMode: String = "standard", // fast, standard, deep, reflective
    val maxReasoningSteps: Int = 8,
    val maxPlanSteps: Int = 12,
    val proactiveAgentSchedule: String = "default" // default, minimal, aggressive
)

data class ButlerReply(
    val assistantMessage: String,
    val speechText: String,
    val pendingActions: List<ButlerAction>,
    val memoryWrites: List<MemoryItem>,
    val provider: String = "local",
    val intent: String = "",
    val latencyMs: Long = 0L
)

data class ConversationContext(
    val recentMessages: List<ChatMessage>,
    val memories: List<MemoryItem>,
    val settings: ButlerSettings
) {
    companion object {
        fun empty(settings: ButlerSettings = ButlerSettings()): ConversationContext =
            ConversationContext(
                recentMessages = emptyList(),
                memories = emptyList(),
                settings = settings
            )
    }
}

enum class AppTab {
    BUTLER,
    ANALYZER,
    PLANS,
    MEMORY,
    SETTINGS
}

data class AuraUiState(
    val messages: List<ChatMessage> = listOf(
        ChatMessage(
            sender = Sender.AURA,
            text = "Good evening, Diganta. I'm ready. What may I take care of?"
        )
    ),
    val pendingActions: List<ButlerAction> = emptyList(),
    val proactiveSuggestions: List<ProactiveEngine.ProactiveSuggestion> = emptyList(),
    val memories: List<MemoryItem> = emptyList(),
    val reminders: List<ReminderItem> = emptyList(),
    val actionHistory: List<ActionHistoryItem> = emptyList(),
    val settings: ButlerSettings = ButlerSettings(),
    val selectedTab: AppTab = AppTab.BUTLER,
    val input: String = "",
    val isBusy: Boolean = false,
    val streamingPreview: String = "",
    val isListening: Boolean = false,
    val transientMessage: String? = null,
    val speechText: String? = null,
    val speechSequence: Long = 0,
    val notificationAccessGranted: Boolean = false,
    val notificationVaultCount: Int = 0,
    val ownerVoiceEnrolled: Boolean = false,
    val ownerVoiceEnrollmentBusy: Boolean = false,
    val ownerVoiceStatus: String = "",
    val imageAnalysisBusy: Boolean = false
)

internal fun JSONObject.stringMap(): Map<String, String> {
    val result = linkedMapOf<String, String>()
    keys().forEach { key ->
        val value = optString(key, "")
        if (value.isNotBlank()) result[key] = value
    }
    return result
}
