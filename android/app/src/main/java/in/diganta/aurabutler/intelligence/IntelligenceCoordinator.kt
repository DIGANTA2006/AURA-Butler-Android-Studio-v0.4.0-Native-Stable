package `in`.diganta.aurabutler.intelligence

import `in`.diganta.aurabutler.data.AiProvider
import `in`.diganta.aurabutler.data.HybridAiProvider
import `in`.diganta.aurabutler.data.KeylessLiveInfoProvider
import `in`.diganta.aurabutler.memory.EncryptedSemanticMemoryPersistence
import `in`.diganta.aurabutler.memory.EmbeddingProvider
import `in`.diganta.aurabutler.memory.LazyOnnxSentenceEmbeddingProvider
import `in`.diganta.aurabutler.memory.LocalEmbeddingProvider
import `in`.diganta.aurabutler.memory.MemoryStore
import `in`.diganta.aurabutler.memory.SemanticMemory
import `in`.diganta.aurabutler.model.ChatMessage
import `in`.diganta.aurabutler.model.ConversationContext
import `in`.diganta.aurabutler.model.ButlerSettings
import `in`.diganta.aurabutler.model.Sender
import `in`.diganta.aurabutler.planning.EncryptedPlanStorePersistence
import `in`.diganta.aurabutler.planning.PlanStore
import `in`.diganta.aurabutler.planning.ProductionTaskExecutor
import `in`.diganta.aurabutler.planning.TaskPlanner
import android.content.Context
import `in`.diganta.aurabutler.runtime.SecretRedactor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Central coordinator that integrates all intelligence components:
 * - Reasoning Engine (Chain-of-Thought)
 * - Task Planner (Multi-step autonomous execution)
 * - Semantic Memory (Long-term memory with embeddings)
 * - Proactive Engine (Anticipatory suggestions & background agents)
 * 
 * Provides a unified interface for the existing LocalButlerEngine to leverage
 * advanced intelligence capabilities while maintaining backward compatibility.
 */
class IntelligenceCoordinator(
    private val aiProvider: AiProvider,
    private val settings: ButlerSettings,
    private val contextProvider: ContextProvider,
    private val appContext: Context? = null
) {

    // Core intelligence components
    private val toolRegistry = ReasoningEngine.ToolRegistry()
    private val reasoningEngine = ReasoningEngine(aiProvider, toolRegistry)
    private val embeddingProvider: EmbeddingProvider = appContext?.let {
        LazyOnnxSentenceEmbeddingProvider(it)
    } ?: LocalEmbeddingProvider("assets/embeddings/all-MiniLM-L6-v2.onnx")
    private val memoryStore = MemoryStore(
        persistence = appContext?.let(::EncryptedSemanticMemoryPersistence)
    )
    private val semanticMemory = SemanticMemory(aiProvider, embeddingProvider, memoryStore)
    private val planStore = PlanStore(
        persistence = appContext?.let(::EncryptedPlanStorePersistence)
    )
    private val taskPlanner = TaskPlanner(
        reasoningEngine = reasoningEngine,
        toolRegistry = toolRegistry,
        planStore = planStore,
        executor = ProductionTaskExecutor(
            reasoningEngine = reasoningEngine,
            liveInfo = KeylessLiveInfoProvider(appContext)
        )
    )
    private val proactiveEngine = ProactiveEngine(aiProvider, semanticMemory, settings, contextProvider)

    // State
    private var initialized = false
    private var intelligenceMode = IntelligenceMode.BALANCED

    enum class IntelligenceMode {
        MINIMAL,      // Basic local commands only
        BALANCED,     // Local + AI for complex tasks (default)
        ENHANCED,     // Full reasoning, planning, proactive
        AUTONOMOUS    // Full autonomy with background agents
    }

    data class IntelligenceResult(
        val response: String,
        val reasoningTrace: List<ReasoningEngine.ReasoningStep>? = null,
        val plan: TaskPlanner.Plan? = null,
        val memoriesUsed: List<SemanticMemory.MemoryEntry> = emptyList(),
        val suggestions: List<ProactiveEngine.ProactiveSuggestion> = emptyList(),
        val confidence: Double = 1.0,
        val mode: IntelligenceMode = IntelligenceMode.BALANCED,
        val processingTimeMs: Long = 0
    )

    /**
     * Initialize all intelligence components.
     */
    suspend fun initialize() = withContext(Dispatchers.IO) {
        if (initialized) return@withContext
        
        // Register custom tools for AURA's capabilities
        registerAuraTools()
        
        // Start proactive engine based on mode
        when (intelligenceMode) {
            IntelligenceMode.ENHANCED, IntelligenceMode.AUTONOMOUS -> proactiveEngine.start()
            else -> {}
        }
        
        initialized = true
    }

    /**
     * Process a user request with full intelligence pipeline.
     * This is the main entry point that replaces/enhances LocalButlerEngine.chat()
     */
    suspend fun processRequest(
        input: String,
        context: ConversationContext
    ): IntelligenceResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        
        // Determine processing strategy based on input and mode
        val strategy = determineStrategy(input, context)
        
        return@withContext when (strategy) {
            ProcessingStrategy.LOCAL_COMMAND -> processLocalCommand(input, context, startTime)
            ProcessingStrategy.AI_REASONING -> processWithReasoning(input, context, startTime)
            ProcessingStrategy.PLAN_AND_EXECUTE -> processWithPlanning(input, context, startTime)
            ProcessingStrategy.AUTONOMOUS -> processAutonomous(input, context, startTime)
        }
    }

    /**
     * Get proactive suggestions for current context.
     */
    fun getProactiveSuggestions(context: ConversationContext): List<ProactiveEngine.ProactiveSuggestion> {
        return proactiveEngine.getSuggestions(context)
    }

    /**
     * Store a memory from conversation.
     */
    suspend fun remember(
        content: String,
        label: String,
        context: ConversationContext,
        importance: Double = 0.5
    ): SemanticMemory.MemoryEntry = withContext(Dispatchers.IO) {
        semanticMemory.remember(content, label, context, importance = importance)
    }

    /**
     * Recall relevant memories.
     */
    suspend fun recall(
        query: String,
        context: ConversationContext,
        options: SemanticMemory.RecallOptions = SemanticMemory.RecallOptions()
    ): SemanticMemory.RetrievalResult = withContext(Dispatchers.IO) {
        semanticMemory.recall(query, context, options)
    }

    /**
     * Create and optionally execute a plan for a complex goal.
     */
    suspend fun createAndExecutePlan(
        goal: String,
        context: ConversationContext,
        autoExecute: Boolean = false
    ): TaskPlanner.ExecutionResult? = withContext(Dispatchers.IO) {
        val plan = taskPlanner.createPlanFromGoal(goal, context)
        
        if (autoExecute || intelligenceMode == IntelligenceMode.AUTONOMOUS) {
            return@withContext taskPlanner.executePlan(plan.id, context)
        }
        return@withContext null
    }

    /**
     * Set intelligence mode.
     */
    fun setIntelligenceMode(mode: IntelligenceMode) {
        intelligenceMode = mode
        when (mode) {
            IntelligenceMode.ENHANCED, IntelligenceMode.AUTONOMOUS -> proactiveEngine.start()
            else -> proactiveEngine.stop()
        }
    }

    /**
     * Get current intelligence status.
     */
    fun getStatus(): IntelligenceStatus {
        val memoryStats = semanticMemory.getStats()
        val activePlans = planStore.getActivePlans()
        val agents = proactiveEngine.getAgentStatus()
        
        return IntelligenceStatus(
            mode = intelligenceMode,
            initialized = initialized,
            memoryStats = memoryStats,
            activePlans = activePlans.size,
            runningAgents = agents.count { it.enabled },
            toolCount = toolRegistry.availableTools().size
        )
    }

    // ===== Private Implementation =====

    private enum class ProcessingStrategy {
        LOCAL_COMMAND,      // Simple local command (open app, set timer, etc.)
        AI_REASONING,       // Complex question needing reasoning
        PLAN_AND_EXECUTE,   // Multi-step goal requiring planning
        AUTONOMOUS          // Full autonomous execution
    }

    private fun determineStrategy(input: String, context: ConversationContext): ProcessingStrategy {
        val lower = input.lowercase()
        
        // Check for explicit planning keywords
        val planningKeywords = listOf("plan", "schedule", "organize", "roadmap", "strategy", "break down", "step by step")
        val autonomousKeywords = listOf("autonomous", "on your own", "figure it out", "handle it", "take care of")
        val reasoningKeywords = listOf("why", "how", "explain", "analyze", "compare", "evaluate", "research")
        
        return when {
            intelligenceMode == IntelligenceMode.MINIMAL -> ProcessingStrategy.LOCAL_COMMAND
            intelligenceMode == IntelligenceMode.AUTONOMOUS && autonomousKeywords.any { lower.contains(it) } -> ProcessingStrategy.AUTONOMOUS
            planningKeywords.any { lower.contains(it) } -> ProcessingStrategy.PLAN_AND_EXECUTE
            reasoningKeywords.any { lower.contains(it) } || input.length > 200 -> ProcessingStrategy.AI_REASONING
            isLocalCommand(lower) -> ProcessingStrategy.LOCAL_COMMAND
            else -> ProcessingStrategy.AI_REASONING
        }
    }

    private fun isLocalCommand(input: String): Boolean {
        val localCommands = listOf(
            "open", "launch", "start", "close", "stop",
            "set timer", "set alarm", "remind me",
            "weather", "news", "battery", "storage",
            "volume", "brightness", "flashlight",
            "call", "message", "email", "whatsapp",
            "navigate", "map", "directions",
            "play", "pause", "music", "youtube",
            "calculate", "time", "date"
        )
        return localCommands.any { input.contains(it) }
    }

    private fun processLocalCommand(input: String, context: ConversationContext, startTime: Long): IntelligenceResult {
        // Delegate to existing LocalButlerEngine for local commands
        // This maintains backward compatibility
        return IntelligenceResult(
            response = "[LOCAL_COMMAND] Would be handled by LocalButlerEngine: $input",
            confidence = 0.95,
            mode = IntelligenceMode.MINIMAL,
            processingTimeMs = System.currentTimeMillis() - startTime
        )
    }

    private suspend fun processWithReasoning(input: String, context: ConversationContext, startTime: Long): IntelligenceResult {
        // Retrieve relevant memories first
        val recallResult = semanticMemory.recall(input, context, SemanticMemory.RecallOptions(maxResults = 5))
        val memoriesUsed = recallResult.entries.map { it.entry }
        
        // Enhance context with memories
        val enhancedContext = enhanceContextWithMemories(context, memoriesUsed)
        
        // Run reasoning engine
        val reasoningResult = reasoningEngine.reason(input, enhancedContext)
        
        // Store the interaction as memory
        semanticMemory.remember(
            content = "User asked: $input\nAURA reasoned: ${reasoningResult.finalAnswer}",
            label = "Q&A: ${input.take(50)}",
            context = context,
            source = SemanticMemory.MemorySource.AI_GENERATED,
            importance = 0.6
        )
        
        // Get proactive suggestions
        val suggestions = proactiveEngine.getSuggestions(context)
        
        return IntelligenceResult(
            response = reasoningResult.finalAnswer,
            reasoningTrace = reasoningResult.reasoningTrace,
            memoriesUsed = memoriesUsed,
            suggestions = suggestions,
            confidence = reasoningResult.confidence,
            mode = IntelligenceMode.BALANCED,
            processingTimeMs = System.currentTimeMillis() - startTime
        )
    }

    private suspend fun processWithPlanning(input: String, context: ConversationContext, startTime: Long): IntelligenceResult {
        // Create plan
        val plan = taskPlanner.createPlanFromGoal(input, context)
        
        // Store plan creation as memory
        semanticMemory.remember(
            content = "Created plan for: $input\nPlan: ${plan.tasks.joinToString("\n") { "${it.title} (${it.type})" }}",
            label = "Plan: ${input.take(50)}",
            context = context,
            source = SemanticMemory.MemorySource.AI_GENERATED,
            importance = 0.7
        )
        
        // Execute if in autonomous mode or explicitly requested
        val executionResult = if (intelligenceMode == IntelligenceMode.AUTONOMOUS || input.contains("execute") || input.contains("do it")) {
            taskPlanner.executePlan(plan.id, context)
        } else null
        
        return IntelligenceResult(
            response = executionResult?.finalOutput 
                ?: "I've created a plan with ${plan.tasks.size} steps. Would you like me to execute it?",
            plan = plan,
            confidence = 0.85,
            mode = IntelligenceMode.ENHANCED,
            processingTimeMs = System.currentTimeMillis() - startTime
        )
    }

    private suspend fun processAutonomous(input: String, context: ConversationContext, startTime: Long): IntelligenceResult {
        // Full autonomous processing: reason -> plan -> execute -> verify -> learn
        val reasoningResult = reasoningEngine.reason(input, context, ReasoningEngine.ReasoningOptions(enableReflection = true))
        
        // If reasoning suggests a plan, create and execute it
        val plan = if (reasoningResult.reasoningTrace.any { it.type == ReasoningEngine.StepType.PLANNING }) {
            taskPlanner.createPlanFromGoal(input, context)
        } else null
        
        val executionResult = plan?.let { taskPlanner.executePlan(it.id, context) }
        
        // Learn from the interaction
        semanticMemory.remember(
            content = "Autonomous task: $input\nReasoning: ${reasoningResult.finalAnswer}\nPlan: ${plan?.tasks?.size ?: 0} steps\nExecution: ${executionResult?.success ?: false}",
            label = "Autonomous: ${input.take(50)}",
            context = context,
            source = SemanticMemory.MemorySource.AI_GENERATED,
            importance = 0.8
        )
        
        // Trigger memory consolidation periodically
        if (Math.random() < 0.1) { // 10% chance
            semanticMemory.consolidate()
        }
        
        return IntelligenceResult(
            response = executionResult?.finalOutput ?: reasoningResult.finalAnswer,
            reasoningTrace = reasoningResult.reasoningTrace,
            plan = plan,
            confidence = reasoningResult.confidence * (executionResult?.success?.let { 1.0 } ?: 0.9),
            mode = IntelligenceMode.AUTONOMOUS,
            processingTimeMs = System.currentTimeMillis() - startTime
        )
    }

    private fun enhanceContextWithMemories(
        context: ConversationContext,
        memories: List<SemanticMemory.MemoryEntry>
    ): ConversationContext {
        val memoryContext = memories.map { "[Memory] ${it.label}: ${it.content.take(200)}" }.joinToString("\n")
        return context.copy(
            recentMessages = context.recentMessages + ChatMessage(
                sender = Sender.AURA,
                text = "Relevant memories for this request:\n$memoryContext"
            )
        )
    }

    private fun registerAuraTools() {
        // Register AURA's existing capabilities as tools for the reasoning engine
        toolRegistry.register(ReasoningEngine.ToolDefinition(
            name = "open_app",
            description = "Open an installed Android application",
            keywords = listOf("open", "launch", "start", "app"),
            requiresConfirmation = false,
            schema = mapOf("app" to "string")
        ))
        
        toolRegistry.register(ReasoningEngine.ToolDefinition(
            name = "get_weather",
            description = "Get current weather for a location",
            keywords = listOf("weather", "temperature", "forecast", "rain"),
            requiresConfirmation = false,
            schema = mapOf("location" to "string?")
        ))
        
        toolRegistry.register(ReasoningEngine.ToolDefinition(
            name = "get_news",
            description = "Get latest news headlines",
            keywords = listOf("news", "headlines", "current events"),
            requiresConfirmation = false,
            schema = mapOf("topic" to "string?", "limit" to "int")
        ))
        
        toolRegistry.register(ReasoningEngine.ToolDefinition(
            name = "set_timer",
            description = "Set a countdown timer",
            keywords = listOf("timer", "countdown"),
            requiresConfirmation = false,
            schema = mapOf("duration_seconds" to "int", "label" to "string?")
        ))
        
        toolRegistry.register(ReasoningEngine.ToolDefinition(
            name = "set_reminder",
            description = "Create a reminder for a specific time",
            keywords = listOf("remind", "reminder"),
            requiresConfirmation = false,
            schema = mapOf("time" to "string", "message" to "string")
        ))
        
        toolRegistry.register(ReasoningEngine.ToolDefinition(
            name = "device_status",
            description = "Get device battery, storage, and system info",
            keywords = listOf("battery", "storage", "memory", "device", "status"),
            requiresConfirmation = false
        ))
        
        toolRegistry.register(ReasoningEngine.ToolDefinition(
            name = "calculate",
            description = "Perform mathematical calculations",
            keywords = listOf("calculate", "compute", "math"),
            requiresConfirmation = false,
            schema = mapOf("expression" to "string")
        ))
        
        toolRegistry.register(ReasoningEngine.ToolDefinition(
            name = "search_web",
            description = "Search the web for current information",
            keywords = listOf("search", "find", "look up", "latest"),
            requiresConfirmation = false,
            schema = mapOf("query" to "string")
        ))
        
        toolRegistry.register(ReasoningEngine.ToolDefinition(
            name = "send_message",
            description = "Send a message via WhatsApp, SMS, or email",
            keywords = listOf("message", "text", "whatsapp", "sms", "email"),
            requiresConfirmation = true,
            schema = mapOf("contact" to "string", "message" to "string", "app" to "string?")
        ))
        
        toolRegistry.register(ReasoningEngine.ToolDefinition(
            name = "play_media",
            description = "Play music or video on YouTube, Spotify, etc.",
            keywords = listOf("play", "music", "song", "video", "youtube", "spotify"),
            requiresConfirmation = false,
            schema = mapOf("query" to "string", "app" to "string?")
        ))
        
        toolRegistry.register(ReasoningEngine.ToolDefinition(
            name = "control_device",
            description = "Control device settings (volume, brightness, flashlight, etc.)",
            keywords = listOf("volume", "brightness", "flashlight", "torch", "wifi", "bluetooth"),
            requiresConfirmation = false,
            schema = mapOf("setting" to "string", "value" to "string")
        ))
        
        toolRegistry.register(ReasoningEngine.ToolDefinition(
            name = "navigate",
            description = "Open navigation to a destination",
            keywords = listOf("navigate", "directions", "map", "route"),
            requiresConfirmation = false,
            schema = mapOf("destination" to "string", "mode" to "string?")
        ))
        
        toolRegistry.register(ReasoningEngine.ToolDefinition(
            name = "create_note",
            description = "Create a note or save information to memory",
            keywords = listOf("note", "save", "remember", "write down"),
            requiresConfirmation = false,
            schema = mapOf("content" to "string", "title" to "string?")
        ))
    }

    data class IntelligenceStatus(
        val mode: IntelligenceMode,
        val initialized: Boolean,
        val memoryStats: SemanticMemory.MemoryStats,
        val activePlans: Int,
        val runningAgents: Int,
        val toolCount: Int
    )

    interface ContextProvider : ProactiveEngine.ContextProvider
}
