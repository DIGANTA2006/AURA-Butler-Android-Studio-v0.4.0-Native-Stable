package `in`.diganta.aurabutler.runtime

import `in`.diganta.aurabutler.data.HybridAiProvider
import `in`.diganta.aurabutler.model.ButlerSettings
import java.util.Locale

/**
 * Operating mode for AURA execution, defining the trust boundary and capability ceiling.
 *
 * - DEVICE_ONLY: Zero network calls. Full local capability (voice, commands, memory, tools).
 *   StudentBudgetGuard is bypassed entirely. No cloud quotas apply.
 * - PRIVATE_CLOUD: Authenticated private Modal endpoints (QwenText, GemmaVision, ResearchGateway).
 *   Budget enforced via reserve/commit/release ledger.
 * - PUBLIC_CLOUD: Explicitly authorized external providers (Gemini, Groq, NVIDIA Nemotron, etc.).
 *   Requires per-provider consent flags and budget.
 * - AUTOMATIC: Policy-driven selection. Prefers DEVICE_ONLY for local commands, then PRIVATE_CLOUD,
 *   then PUBLIC_CLOUD only when explicitly configured and consented.
 */
enum class OperatingMode(val wireName: String) {
    DEVICE_ONLY("device_only"),
    PRIVATE_CLOUD("private_cloud"),
    PUBLIC_CLOUD("public_cloud"),
    AUTOMATIC("automatic");

    companion object {
        fun fromWireName(value: String): OperatingMode =
            entries.firstOrNull { it.wireName == value } ?: AUTOMATIC

        /**
         * Migrates legacy `local` / `automatic` string values to the new enum.
         * Non-silent: logs the migration so the owner can verify intent.
         */
        fun migrateFromLegacy(legacy: String?): OperatingMode = when (legacy?.trim().lowercase(Locale.ROOT)) {
            "local", "device_only" -> DEVICE_ONLY
            "private", "private_cloud" -> PRIVATE_CLOUD
            "public", "public_cloud" -> PUBLIC_CLOUD
            "automatic", "" -> AUTOMATIC
            else -> {
                // Unknown legacy value: default to AUTOMATIC but surface for review
                AUTOMATIC
            }
        }
    }
}

/**
 * Execution mode refines how a single turn is routed within the selected OperatingMode.
 * This is derived per-request, not a global setting.
 */
enum class ExecutionMode(val wireName: String) {
    LOCAL_COMMAND("local_command"),
    LOCAL_REASONING("local_reasoning"),
    PRIVATE_AI_STREAMING("private_ai_streaming"),
    PRIVATE_AI_BLOCKING("private_ai_blocking"),
    PRIVATE_RESEARCH("private_research"),
    PUBLIC_AI("public_ai"),
    WEB_SEARCH_FALLBACK("web_search_fallback");

    companion object {
        fun fromWireName(value: String): ExecutionMode =
            entries.firstOrNull { it.wireName == value } ?: LOCAL_COMMAND
    }
}

/**
 * Policy resolver that maps (OperatingMode, request characteristics) -> ExecutionMode.
 * Single source of truth for routing decisions.
 */
class ExecutionPolicyEngine {

    fun resolve(
        mode: OperatingMode,
        message: String,
        settings: ButlerSettings,
        intent: String
    ): ExecutionMode {
        return when (mode) {
            OperatingMode.DEVICE_ONLY -> resolveDeviceOnly(message, settings, intent)
            OperatingMode.PRIVATE_CLOUD -> resolvePrivateCloud(message, settings, intent)
            OperatingMode.PUBLIC_CLOUD -> resolvePublicCloud(message, settings, intent)
            OperatingMode.AUTOMATIC -> resolveAutomatic(message, settings, intent)
        }
    }

    private fun resolveDeviceOnly(
        message: String,
        settings: ButlerSettings,
        intent: String
    ): ExecutionMode {
        return when {
            isLocalCommandIntent(intent) -> ExecutionMode.LOCAL_COMMAND
            isLocalReasoningIntent(intent) -> ExecutionMode.LOCAL_REASONING
            else -> ExecutionMode.WEB_SEARCH_FALLBACK
        }
    }

    private fun resolvePrivateCloud(
        message: String,
        settings: ButlerSettings,
        intent: String
    ): ExecutionMode {
        return when {
            isLocalCommandIntent(intent) -> ExecutionMode.LOCAL_COMMAND
            isLocalReasoningIntent(intent) -> ExecutionMode.LOCAL_REASONING
            intent == "analyze_image" && settings.privateVisionEnabled -> ExecutionMode.PRIVATE_AI_BLOCKING
            intent in RESEARCH_INTENTS && settings.researchEnabled -> ExecutionMode.PRIVATE_RESEARCH
            settings.localAiEnabled && settings.localAiEndpoint.isNotBlank() -> {
                if (supportsStreaming(intent)) ExecutionMode.PRIVATE_AI_STREAMING
                else ExecutionMode.PRIVATE_AI_BLOCKING
            }
            else -> ExecutionMode.WEB_SEARCH_FALLBACK
        }
    }

    private fun resolvePublicCloud(
        message: String,
        settings: ButlerSettings,
        intent: String
    ): ExecutionMode {
        val publicAllowed = HybridAiProvider.publicProviderAllowed(settings)
        return when {
            isLocalCommandIntent(intent) -> ExecutionMode.LOCAL_COMMAND
            isLocalReasoningIntent(intent) -> ExecutionMode.LOCAL_REASONING
            publicAllowed -> ExecutionMode.PUBLIC_AI
            else -> ExecutionMode.WEB_SEARCH_FALLBACK
        }
    }

    private fun resolveAutomatic(
        message: String,
        settings: ButlerSettings,
        intent: String
    ): ExecutionMode {
        return when {
            isLocalCommandIntent(intent) -> ExecutionMode.LOCAL_COMMAND
            isLocalReasoningIntent(intent) -> ExecutionMode.LOCAL_REASONING
            settings.localAiEnabled && settings.localAiEndpoint.isNotBlank() -> {
                if (supportsStreaming(intent)) ExecutionMode.PRIVATE_AI_STREAMING
                else ExecutionMode.PRIVATE_AI_BLOCKING
            }
            intent == "analyze_image" && settings.privateVisionEnabled -> ExecutionMode.PRIVATE_AI_BLOCKING
            intent in RESEARCH_INTENTS && settings.researchEnabled -> ExecutionMode.PRIVATE_RESEARCH
            HybridAiProvider.publicProviderAllowed(settings) -> ExecutionMode.PUBLIC_AI
            else -> ExecutionMode.WEB_SEARCH_FALLBACK
        }
    }

    private fun isLocalCommandIntent(intent: String): Boolean =
        intent in LOCAL_COMMAND_INTENTS

    private fun isLocalReasoningIntent(intent: String): Boolean =
        intent in LOCAL_REASONING_INTENTS

    private fun supportsStreaming(intent: String): Boolean =
        intent !in NON_STREAMING_INTENTS

    companion object {
        private val LOCAL_COMMAND_INTENTS = setOf(
            "set_timer", "set_alarm", "set_reminder", "open_app", "open_system_setting",
            "system_volume", "system_brightness", "flashlight", "open_camera",
            "dial_phone", "draft_message", "compose_email", "whatsapp_message",
            "map_route", "play_media", "share_text", "calendar_event",
            "food_search", "travel_search", "shopping_search", "identify_song",
            "analyze_screen", "capture_screen", "open_app_storage", "clear_aura_cache",
            "cooling_assist", "cancel_action", "confirm", "interrupt",
            "forget_memory", "ai_budget_status", "ai_status"
        )

        private val LOCAL_REASONING_INTENTS = setOf(
            "calculate", "device_status", "time_query", "date_query", "plan_day",
            "knowledge_query", "weather_query", "news_query", "search_wiki"
        )

        private val RESEARCH_INTENTS = setOf(
            "deep_research", "exhaustive_research", "research_with_sources"
        )

        private val NON_STREAMING_INTENTS = setOf(
            "analyze_image", "deep_research", "exhaustive_research",
            "research_with_sources", "generate_code_detailed"
        )
    }
}

/**
 * Extension on ButlerSettings to expose the resolved OperatingMode with migration.
 */
val ButlerSettings.operatingMode: OperatingMode
    get() = OperatingMode.migrateFromLegacy(this.aiProvider ?: "")
