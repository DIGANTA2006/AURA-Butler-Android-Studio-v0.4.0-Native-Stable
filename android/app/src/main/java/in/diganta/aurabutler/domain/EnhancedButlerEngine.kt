package `in`.diganta.aurabutler.domain

import `in`.diganta.aurabutler.data.AiProvider
import `in`.diganta.aurabutler.data.HybridAiProvider
import `in`.diganta.aurabutler.intelligence.IntelligenceCoordinator
import `in`.diganta.aurabutler.intelligence.ProactiveEngine
import `in`.diganta.aurabutler.model.ActionKind
import `in`.diganta.aurabutler.model.ActionRisk
import `in`.diganta.aurabutler.model.ButlerAction
import `in`.diganta.aurabutler.model.ButlerReply
import `in`.diganta.aurabutler.model.ButlerSettings
import `in`.diganta.aurabutler.model.ConversationContext
import `in`.diganta.aurabutler.model.MemoryItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Enhanced Butler Engine that integrates the new Intelligence Coordinator
 * with the existing LocalButlerEngine for backward compatibility.
 * 
 * This engine provides:
 * - Chain-of-Thought reasoning for complex questions
 * - Multi-step autonomous planning and execution
 * - Semantic memory with embeddings-based retrieval
 * - Proactive suggestions and background agents
 * - Full backward compatibility with existing local commands
 */
class EnhancedButlerEngine(
    private val liveInfo: LiveInfoProvider,
    private val generalAi: AiProvider? = null,
    private val settingsProvider: () -> ButlerSettings,
    private val contextProvider: IntelligenceCoordinator.ContextProvider
) {

    // Core engines
    private val localEngine = LocalButlerEngine(liveInfo, generalAi)
    private val intelligenceCoordinator: IntelligenceCoordinator? = generalAi?.let {
        IntelligenceCoordinator(it, settingsProvider(), contextProvider)
    }
    
    // State
    private var initialized = false
    private val initScope = CoroutineScope(Dispatchers.IO)

    /**
     * Main chat entry point - enhanced with intelligence capabilities.
     */
    suspend fun chat(
        message: String,
        context: ConversationContext,
        onPartial: (String) -> Unit = {}
    ): ButlerReply {
        // Lazy initialization
        if (!initialized) {
            initialize()
        }
        
        // Check if we should use intelligence coordinator
        val useIntelligence = intelligenceCoordinator != null && 
            shouldUseIntelligence(message, context)
        
        return if (useIntelligence) {
            processWithIntelligence(message, context, onPartial)
        } else {
            // Fall back to local engine for simple commands
            localEngine.chat(message, context, onPartial)
        }
    }

    /**
     * Get proactive suggestions for the current context.
     */
    fun getProactiveSuggestions(context: ConversationContext): List<ProactiveEngine.ProactiveSuggestion> {
        return intelligenceCoordinator?.getProactiveSuggestions(context) ?: emptyList()
    }

    /**
     * Store a memory with semantic understanding.
     */
    suspend fun remember(
        content: String,
        label: String,
        context: ConversationContext,
        importance: Double = 0.5
    ) {
        intelligenceCoordinator?.remember(content, label, context, importance)
    }

    /**
     * Recall relevant memories.
     */
    suspend fun recall(
        query: String,
        context: ConversationContext,
        options: IntelligenceCoordinator.SemanticMemory.RecallOptions = IntelligenceCoordinator.SemanticMemory.RecallOptions()
    ): IntelligenceCoordinator.SemanticMemory.RetrievalResult? {
        return intelligenceCoordinator?.recall(query, context, options)
    }

    /**
     * Create and execute a plan for a complex goal.
     */
    suspend fun createAndExecutePlan(
        goal: String,
        context: ConversationContext,
        autoExecute: Boolean = false
    ): IntelligenceCoordinator.TaskPlanner.ExecutionResult? {
        return intelligenceCoordinator?.createAndExecutePlan(goal, context, autoExecute)
    }

    /**
     * Set intelligence mode.
     */
    fun setIntelligenceMode(mode: IntelligenceCoordinator.IntelligenceMode) {
        intelligenceCoordinator?.setIntelligenceMode(mode)
    }

    /**
     * Get intelligence system status.
     */
    fun getIntelligenceStatus(): IntelligenceCoordinator.IntelligenceStatus? {
        return intelligenceCoordinator?.getStatus()
    }

    // ===== Private Implementation =====

    private fun initialize() {
        if (initialized) return
        initialized = true
        
        initScope.launch {
            intelligenceCoordinator?.initialize()
        }
    }

    private fun shouldUseIntelligence(message: String, context: ConversationContext): Boolean {
        val lower = message.lowercase()
        val settings = settingsProvider()
        
        // Check if AI is enabled in settings
        if (!settings.aiEnabled) return false
        
        // Check if intelligence mode is not minimal
        val coordinator = intelligenceCoordinator
        if (coordinator != null) {
            // We can't directly access the mode, but we can check if it's initialized
        }
        
        // Use intelligence for:
        // 1. Complex questions (why, how, explain, analyze, compare)
        // 2. Planning requests
        // 3. Long queries
        // 4. When local engine would fall back to AI anyway
        val intelligenceTriggers = listOf(
            "why ", "how ", "explain ", "analyze ", "compare ", "evaluate ",
            "plan ", "schedule ", "organize ", "strategy ", "roadmap ",
            "research ", "investigate ", "deep dive ", "comprehensive ",
            "autonomous ", "on your own ", "figure it out ", "handle it ",
            "teach me ", "tell me about ", "what is ", "who is ",
            "meaning of ", "define ", "describe "
        )
        
        // Don't use intelligence for simple local commands
        val localCommands = listOf(
            "open ", "launch ", "start ", "close ", "stop ",
            "set timer", "set alarm", "remind me",
            "weather", "news", "battery", "storage",
            "volume", "brightness", "flashlight",
            "call ", "message ", "email ", "whatsapp ",
            "navigate ", "map ", "directions ",
            "play ", "pause ", "music ", "youtube ",
            "calculate ", "time", "date"
        )
        
        // If it's clearly a local command, don't use intelligence
        if (localCommands.any { lower.startsWith(it) || lower.contains(" $it") }) {
            return false
        }
        
        // Use intelligence for complex queries
        return intelligenceTriggers.any { lower.contains(it) } || message.length > 150
    }

    private suspend fun processWithIntelligence(
        message: String,
        context: ConversationContext,
        onPartial: (String) -> Unit
    ): ButlerReply {
        val coordinator = intelligenceCoordinator!!
        val result = coordinator.processRequest(message, context)
        
        // Convert IntelligenceResult to ButlerReply
        val actions = mutableListOf<ButlerAction>()
        val memories = mutableListOf<MemoryItem>()
        
        // If there's a plan, add execution action
        result.plan?.let { plan ->
            actions += ButlerAction(
                id = UUID.randomUUID().toString(),
                kind = ActionKind.OPEN_APP,
                title = "Execute plan: ${plan.goal}",
                summary = "Plan with ${plan.tasks.size} steps: ${plan.tasks.map { it.title }.joinToString(", ")}",
                risk = ActionRisk.MEDIUM,
                payload = mapOf(
                    "planId" to plan.id,
                    "autoExecute" to "false"
                )
            )
        }
        
        // Add proactive suggestions as implicit actions
        if (result.suggestions.isNotEmpty()) {
            val topSuggestion = result.suggestions.first()
            actions += ButlerAction(
                id = UUID.randomUUID().toString(),
                kind = ActionKind.OPEN_APP,
                title = "Suggestion: ${topSuggestion.title}",
                summary = topSuggestion.description,
                risk = ActionRisk.LOW,
                payload = mapOf(
                    "suggestionId" to topSuggestion.id,
                    "type" to "proactive"
                )
            )
        }
        
        // If reasoning trace exists, add it as context
        var responseText = result.response
        if (result.reasoningTrace != null && result.reasoningTrace!!.isNotEmpty()) {
            // Optionally include reasoning summary
            val reasoningSummary = result.reasoningTrace!!
                .filter { it.type == IntelligenceCoordinator.ReasoningEngine.StepType.CONCLUSION }
                .map { it.content }
                .joinToString(" ")
            if (reasoningSummary.isNotBlank()) {
                responseText = "$responseText\n\n[Reasoning: $reasoningSummary]"
            }
        }
        
        return ButlerReply(
            assistantMessage = responseText,
            speechText = responseText.replace(Regex("""https?://\S+"""), "the prepared link")
                .replace(Regex("""\[Reasoning:.*\]"""), ""),
            pendingActions = actions,
            memoryWrites = memories
        )
    }

    /**
     * Companion object for factory methods.
     */
    companion object {
        /**
         * Create an EnhancedButlerEngine with default configuration.
         */
        fun create(
            liveInfo: LiveInfoProvider,
            generalAi: AiProvider? = null,
            settingsProvider: () -> ButlerSettings,
            contextProvider: IntelligenceCoordinator.ContextProvider
        ): EnhancedButlerEngine {
            return EnhancedButlerEngine(liveInfo, generalAi, settingsProvider, contextProvider)
        }
    }
}
