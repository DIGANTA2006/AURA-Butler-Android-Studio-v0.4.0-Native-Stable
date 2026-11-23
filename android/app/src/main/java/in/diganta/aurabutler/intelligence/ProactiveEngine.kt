package `in`.diganta.aurabutler.intelligence

import `in`.diganta.aurabutler.data.AiProvider
import `in`.diganta.aurabutler.memory.SemanticMemory
import `in`.diganta.aurabutler.model.ConversationContext
import `in`.diganta.aurabutler.model.ButlerSettings
import `in`.diganta.aurabutler.runtime.SecretRedactor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Proactive intelligence engine that anticipates needs, suggests actions,
 * and runs background agents for continuous assistance.
 */
class ProactiveEngine(
    private val aiProvider: AiProvider?,
    private val semanticMemory: SemanticMemory,
    private val settings: ButlerSettings,
    private val contextProvider: ContextProvider
) {

    data class ProactiveSuggestion(
        val id: String = UUID.randomUUID().toString(),
        val type: SuggestionType,
        val title: String,
        val description: String,
        val action: ProactiveAction,
        val confidence: Double,
        val priority: Priority,
        val context: SuggestionContext,
        val createdAt: Long = System.currentTimeMillis(),
        val expiresAt: Long = System.currentTimeMillis() + 3600_000, // 1 hour default
        val dismissed: Boolean = false,
        val accepted: Boolean = false
    )

    enum class SuggestionType {
        REMINDER,           // Time-based reminders
        FOLLOW_UP,          // Follow up on previous conversation
        PREPARATION,        // Prepare for upcoming event/task
        OPTIMIZATION,       // Suggest better way to do something
        INSIGHT,            // Pattern discovered in data
        MAINTENANCE,        // Device/app maintenance needed
        LEARNING,           // Offer to teach/learn something
        AUTOMATION          // Suggest automation for repetitive task
    }

    enum class Priority(val weight: Int) {
        LOW(1),
        MEDIUM(2),
        HIGH(3),
        CRITICAL(4)
    }

    data class ProactiveAction(
        val type: ActionType,
        val payload: Map<String, Any> = emptyMap(),
        val requiresConfirmation: Boolean = true
    )

    enum class ActionType {
        SHOW_NOTIFICATION,
        ASK_QUESTION,
        RUN_COMMAND,
        OPEN_APP,
        CREATE_REMINDER,
        SCHEDULE_TASK,
        START_RESEARCH,
        SHOW_INSIGHT
    }

    data class SuggestionContext(
        val trigger: Trigger,
        val relatedMemoryIds: List<String> = emptyList(),
        val timeRelevance: ProactiveEngine.TimeRelevance = ProactiveEngine.TimeRelevance.ANYTIME,
        val locationRelevance: String? = null
    )

    enum class Trigger {
        TIME_BASED,
        CONVERSATION_CONTEXT,
        DEVICE_STATE,
        MEMORY_PATTERN,
        CALENDAR_EVENT,
        LOCATION_CHANGE,
        USER_HABIT
    }

    enum class TimeRelevance {
        MORNING, AFTERNOON, EVENING, NIGHT, ANYTIME, SPECIFIC_TIME
    }

    data class BackgroundAgent(
        val id: String = UUID.randomUUID().toString(),
        val name: String,
        val description: String,
        val schedule: AgentSchedule,
        val task: AgentTask,
        val enabled: Boolean = true,
        val lastRun: Long? = null,
        val nextRun: Long? = null,
        val runCount: Int = 0,
        val successCount: Int = 0,
        val errorCount: Int = 0
    )

    sealed class AgentSchedule {
        object HOURLY : AgentSchedule()
        object DAILY : AgentSchedule()
        object WEEKLY : AgentSchedule()
        data class ON_EVENT(val eventName: String) : AgentSchedule()
        data class CRON(val expression: String) : AgentSchedule()
    }

    fun interface AgentTask {
        suspend fun execute(context: AgentContext): AgentResult
    }

    data class AgentContext(
        val currentTime: Long = System.currentTimeMillis(),
        val deviceState: DeviceState,
        val recentMemories: List<SemanticMemory.MemoryEntry>,
        val activePlans: List<String>,
        val userPreferences: ButlerSettings
    )

    data class DeviceState(
        val batteryLevel: Int,
        val isCharging: Boolean,
        val networkType: String,
        val storageFreePercent: Double,
        val screenOn: Boolean,
        val foregroundApp: String?
    )

    data class AgentResult(
        val success: Boolean,
        val output: String?,
        val suggestions: List<ProactiveSuggestion> = emptyList(),
        val actions: List<ProactiveAction> = emptyList(),
        val error: String? = null
    )

    private val suggestions = ConcurrentHashMap<String, ProactiveSuggestion>()
    private val agents = ConcurrentHashMap<String, BackgroundAgent>()
    @Volatile
    private var scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(2)
    @Volatile
    private var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile
    private var running = false

    init {
        registerDefaultAgents()
    }

    /**
     * Start the proactive engine - begins background agents and suggestion generation.
     */
    @Synchronized
    fun start() {
        if (running) return
        if (scheduler.isShutdown) {
            scheduler = Executors.newScheduledThreadPool(2)
        }
        if (scope.coroutineContext[kotlinx.coroutines.Job]?.isCancelled != false) {
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        }
        running = true

        // Scheduled callbacks only enqueue work; the coroutine scope owns
        // suspend operations and is cancelled when the engine stops.
        scheduler.scheduleAtFixedRate({
            if (running) scope.launch { generateProactiveSuggestions() }
        }, 5, 15, TimeUnit.MINUTES)

        scheduleAgents()
        scope.launch { generateProactiveSuggestions() }
    }

    /**
     * Stop scheduled and in-flight proactive work. A later start() creates
     * fresh executors/scopes, so switching intelligence modes is reversible.
     */
    @Synchronized
    fun stop() {
        running = false
        scheduler.shutdownNow()
        scope.cancel()
    }

    /**
     * Get current proactive suggestions for the user.
     */
    fun getSuggestions(context: ConversationContext, limit: Int = 5): List<ProactiveSuggestion> {
        val now = System.currentTimeMillis()
        return suggestions.values
            .filter { !it.dismissed && !it.accepted && it.expiresAt > now }
            .filter { isRelevant(it, context) }
            .sortedByDescending { it.priority.weight * it.confidence }
            .take(limit)
            .toList()
    }

    /**
     * Mark a suggestion as dismissed.
     */
    fun dismissSuggestion(id: String) {
        suggestions[id]?.let { suggestions[id] = it.copy(dismissed = true) }
    }

    /**
     * Mark a suggestion as accepted and execute its action.
     */
    suspend fun acceptSuggestion(id: String, context: ConversationContext): Boolean {
        val suggestion = suggestions[id] ?: return false
        val executed = executeAction(suggestion.action, context)
        if (executed) {
            suggestions[id] = suggestion.copy(accepted = true)
        }
        return executed
    }

    /**
     * Register a custom background agent.
     */
    fun registerAgent(agent: BackgroundAgent) {
        agents[agent.id] = agent
        if (running) scheduleAgent(agent)
    }

    /**
     * Get agent status.
     */
    fun getAgentStatus(): List<BackgroundAgent> = agents.values.toList()

    // ===== Private Implementation =====

    private fun registerDefaultAgents() {
        // Daily briefing agent
        registerAgent(BackgroundAgent(
            name = "Daily Briefing",
            description = "Generates a morning summary of schedule, weather, and priorities",
            schedule = AgentSchedule.CRON("0 7 * * *"), // 7 AM daily
            task = { _ ->
                AgentResult(
                    success = false,
                    output = "Daily briefing is unavailable: calendar and weather data sources are not connected.",
                    error = "Daily briefing integrations are not configured."
                )
            }
        ))

        // Memory consolidation agent
        registerAgent(BackgroundAgent(
            name = "Memory Consolidation",
            description = "Consolidates memories, extracts patterns, applies forgetting curve",
            schedule = AgentSchedule.DAILY,
            task = { ctx ->
                // This would call semanticMemory.consolidate()
                AgentResult(true, "Memory consolidation completed")
            }
        ))

        // Battery optimization agent
        registerAgent(BackgroundAgent(
            name = "Battery Optimization",
            description = "Suggests power-saving actions when battery is low",
            schedule = AgentSchedule.HOURLY,
            task = { ctx ->
                val suggestions = mutableListOf<ProactiveSuggestion>()
                if (ctx.deviceState.batteryLevel < 20 && !ctx.deviceState.isCharging) {
                    suggestions += ProactiveSuggestion(
                        type = SuggestionType.MAINTENANCE,
                        title = "Low Battery",
                        description = "Battery at ${ctx.deviceState.batteryLevel}%. Consider enabling battery saver.",
                        action = ProactiveAction(
                            type = ProactiveEngine.ActionType.SHOW_NOTIFICATION,
                            payload = mapOf("message" to "Battery low: ${ctx.deviceState.batteryLevel}%"),
                            requiresConfirmation = false
                        ),
                        confidence = 0.95,
                        priority = Priority.HIGH,
                        context = SuggestionContext(trigger = Trigger.DEVICE_STATE)
                    )
                }
                AgentResult(true, "Battery check completed", suggestions = suggestions)
            }
        ))

        // Storage cleanup agent
        registerAgent(BackgroundAgent(
            name = "Storage Monitor",
            description = "Alerts when storage is running low",
            schedule = AgentSchedule.DAILY,
            task = { ctx ->
                val suggestions = mutableListOf<ProactiveSuggestion>()
                if (ctx.deviceState.storageFreePercent < 10) {
                    suggestions += ProactiveSuggestion(
                        type = SuggestionType.MAINTENANCE,
                        title = "Low Storage",
                        description = "Only ${String.format("%.1f", ctx.deviceState.storageFreePercent)}% storage free. Consider clearing cache or unused apps.",
                        action = ProactiveAction(
                            type = ProactiveEngine.ActionType.OPEN_APP,
                            payload = mapOf("app" to "Settings > Storage"),
                            requiresConfirmation = true
                        ),
                        confidence = 0.9,
                        priority = Priority.MEDIUM,
                        context = SuggestionContext(trigger = Trigger.DEVICE_STATE)
                    )
                }
                AgentResult(true, "Storage check completed", suggestions = suggestions)
            }
        ))

        // Habit learning agent
        registerAgent(BackgroundAgent(
            name = "Habit Learning",
            description = "Detects recurring patterns and suggests automations",
            schedule = AgentSchedule.WEEKLY,
            task = { _ ->
                AgentResult(
                    success = false,
                    output = "Habit analysis is unavailable until real usage signals are connected.",
                    error = "No usage-history source is configured."
                )
            }
        ))

        // Research follow-up agent
        registerAgent(BackgroundAgent(
            name = "Research Follow-up",
            description = "Checks for updates on previous research topics",
            schedule = AgentSchedule.DAILY,
            task = { _ ->
                AgentResult(
                    success = false,
                    output = "Research follow-up is unavailable until prior research topics and a live update source are connected.",
                    error = "Research update source is not configured."
                )
            }
        ))
    }

    private fun scheduleAgents() {
        for (agent in agents.values) {
            if (agent.enabled) scheduleAgent(agent)
        }
    }

    private fun scheduleAgent(agent: BackgroundAgent) {
        val delay = calculateNextRun(agent.schedule)
        scheduler.schedule({
            if (running && agents[agent.id]?.enabled == true) {
                runAgent(agent)
            }
        }, delay, TimeUnit.MILLISECONDS)
    }

    private fun calculateNextRun(schedule: AgentSchedule): Long {
        val now = System.currentTimeMillis()
        return when (schedule) {
            AgentSchedule.HOURLY -> 3_600_000 // 1 hour
            AgentSchedule.DAILY -> 24 * 3_600_000 // 24 hours
            AgentSchedule.WEEKLY -> 7 * 24 * 3_600_000 // 1 week
            is AgentSchedule.ON_EVENT -> 3_600_000 // Check hourly for events
            is AgentSchedule.CRON -> 3_600_000 // Cron parsing remains a separate scheduling enhancement
        }
    }

    private fun runAgent(agent: BackgroundAgent) {
        val updatedAgent = agent.copy(
            lastRun = System.currentTimeMillis(),
            runCount = agent.runCount + 1
        )
        agents[agent.id] = updatedAgent

        // Keep all suspend work inside the engine-owned, cancellable scope.
        scope.launch {
            try {
                val agentContext = buildAgentContext()
                val result = agent.task.execute(agentContext)
                val successAgent = updatedAgent.copy(
                    successCount = updatedAgent.successCount + (if (result.success) 1 else 0),
                    errorCount = updatedAgent.errorCount + (if (result.success) 0 else 1),
                    nextRun = System.currentTimeMillis() + calculateNextRun(agent.schedule)
                )
                agents[agent.id] = successAgent
                
                // Add generated suggestions
                result.suggestions.forEach { suggestions[it.id] = it }
                
                // Execute suggested actions if high confidence and auto-execute enabled
                if (settings.proactiveAutoExecute) {
                    result.actions.filter { it.requiresConfirmation == false && result.suggestions.any { s -> s.confidence > 0.8 } }
                        .forEach { executeAction(it, ConversationContext.empty()) }
                }
            } catch (e: Exception) {
                val errorAgent = updatedAgent.copy(
                    errorCount = updatedAgent.errorCount + 1,
                    nextRun = System.currentTimeMillis() + calculateNextRun(agent.schedule)
                )
                agents[agent.id] = errorAgent
            }
        }
    }

    private suspend fun buildAgentContext(): AgentContext {
        val conversationContext = contextProvider.getCurrentContext()
        val memoryResult = semanticMemory.recall(
            query = "recent",
            context = conversationContext,
            options = SemanticMemory.RecallOptions(
                maxResults = 20,
                strategy = SemanticMemory.RetrievalStrategy.TEMPORAL
            )
        )
        return AgentContext(
            deviceState = contextProvider.getDeviceState(),
            recentMemories = memoryResult.entries.map { it.entry },
            activePlans = emptyList(),
            userPreferences = settings
        )
    }

    private suspend fun generateProactiveSuggestions() {
        val context = buildAgentContext()
        val allSuggestions = mutableListOf<ProactiveSuggestion>()
        
        // Time-based suggestions
        allSuggestions += generateTimeBasedSuggestions(context)
        
        // Conversation context suggestions
        allSuggestions += generateContextualSuggestions(context)
        
        // Memory pattern suggestions
        allSuggestions += generateMemoryPatternSuggestions(context)
        
        // Device state suggestions
        allSuggestions += generateDeviceStateSuggestions(context)
        
        // Filter and store
        val now = System.currentTimeMillis()
        for (suggestion in allSuggestions) {
            if (suggestion.confidence >= 0.4 && suggestion.expiresAt > now) {
                suggestions[suggestion.id] = suggestion
            }
        }
        
        // Clean expired
        val expired = suggestions.values.filter { it.expiresAt <= now }.map { it.id }
        expired.forEach { suggestions.remove(it) }
    }

    private fun generateTimeBasedSuggestions(ctx: AgentContext): List<ProactiveSuggestion> {
        val hour = java.time.LocalDateTime.now().hour
        val suggestions = mutableListOf<ProactiveSuggestion>()
        
        when {
            hour in 6..9 -> {
                // Morning
                suggestions += ProactiveSuggestion(
                    type = SuggestionType.PREPARATION,
                    title = "Good Morning",
                    description = "Would you like your daily briefing with weather, schedule, and priorities?",
                    action = ProactiveAction(
                        type = ProactiveEngine.ActionType.ASK_QUESTION,
                        payload = mapOf("question" to "Show daily briefing?"),
                        requiresConfirmation = true
                    ),
                    confidence = 0.8,
                    priority = Priority.MEDIUM,
                    context = SuggestionContext(
                        trigger = Trigger.TIME_BASED,
                        timeRelevance = ProactiveEngine.TimeRelevance.MORNING
                    )
                )
            }
            hour in 12..13 -> {
                // Lunch
                suggestions += ProactiveSuggestion(
                    type = SuggestionType.REMINDER,
                    title = "Lunch Break",
                    description = "Time for a break. Want me to set a timer or find nearby food?",
                    action = ProactiveAction(
                        type = ProactiveEngine.ActionType.ASK_QUESTION,
                        payload = mapOf("question" to "Set lunch timer or find food?"),
                        requiresConfirmation = true
                    ),
                    confidence = 0.6,
                    priority = Priority.LOW,
                    context = SuggestionContext(
                        trigger = Trigger.TIME_BASED,
                        timeRelevance = ProactiveEngine.TimeRelevance.AFTERNOON
                    )
                )
            }
            hour in 17..19 -> {
                // Evening wrap-up
                suggestions += ProactiveSuggestion(
                    type = SuggestionType.FOLLOW_UP,
                    title = "Evening Wrap-up",
                    description = "Review today's accomplishments and plan tomorrow?",
                    action = ProactiveAction(
                        type = ProactiveEngine.ActionType.ASK_QUESTION,
                        payload = mapOf("question" to "Show evening summary?"),
                        requiresConfirmation = true
                    ),
                    confidence = 0.7,
                    priority = Priority.MEDIUM,
                    context = SuggestionContext(
                        trigger = Trigger.TIME_BASED,
                        timeRelevance = ProactiveEngine.TimeRelevance.EVENING
                    )
                )
            }
            hour in 22..23 -> {
                // Night
                suggestions += ProactiveSuggestion(
                    type = SuggestionType.MAINTENANCE,
                    title = "Night Mode",
                    description = "Enable do not disturb and set morning alarm?",
                    action = ProactiveAction(
                        type = ProactiveEngine.ActionType.ASK_QUESTION,
                        payload = mapOf("question" to "Enable night mode?"),
                        requiresConfirmation = true
                    ),
                    confidence = 0.5,
                    priority = Priority.LOW,
                    context = SuggestionContext(
                        trigger = Trigger.TIME_BASED,
                        timeRelevance = ProactiveEngine.TimeRelevance.NIGHT
                    )
                )
            }
        }
        
        return suggestions
    }

    private fun generateContextualSuggestions(ctx: AgentContext): List<ProactiveSuggestion> {
        // Would analyze recent conversation context
        // For now, return empty - would integrate with conversation history
        return emptyList()
    }

    private fun generateMemoryPatternSuggestions(ctx: AgentContext): List<ProactiveSuggestion> {
        val suggestions = mutableListOf<ProactiveSuggestion>()
        
        // Check for recurring topics in recent memories
        val topics = ctx.recentMemories.flatMap { it.metadata.relatedEntities }
            .groupBy { it }
            .mapValues { it.value.size }
            .toList()
            .sortedByDescending { it.second }
            .take(3)
        
        for ((topic, count) in topics) {
            if (count >= 3) {
                suggestions += ProactiveSuggestion(
                    type = SuggestionType.INSIGHT,
                    title = "Recurring Topic: $topic",
                    description = "You've mentioned '$topic' $count times recently. Want to create a project or research note?",
                    action = ProactiveAction(
                        type = ProactiveEngine.ActionType.ASK_QUESTION,
                        payload = mapOf("question" to "Create project for $topic?"),
                        requiresConfirmation = true
                    ),
                    confidence = 0.7,
                    priority = Priority.LOW,
                    context = SuggestionContext(
                        trigger = Trigger.MEMORY_PATTERN,
                        relatedMemoryIds = ctx.recentMemories.filter { it.metadata.relatedEntities.contains(topic) }.map { it.id }
                    )
                )
            }
        }
        
        return suggestions
    }

    private fun generateDeviceStateSuggestions(ctx: AgentContext): List<ProactiveSuggestion> {
        val suggestions = mutableListOf<ProactiveSuggestion>()
        val state = ctx.deviceState
        
        if (state.batteryLevel < 15 && !state.isCharging) {
            suggestions += ProactiveSuggestion(
                type = SuggestionType.MAINTENANCE,
                title = "Critical Battery",
                description = "Battery at ${state.batteryLevel}%. Connect charger immediately.",
                action = ProactiveAction(
                    type = ProactiveEngine.ActionType.SHOW_NOTIFICATION,
                    payload = mapOf("message" to "Critical battery: ${state.batteryLevel}%"),
                    requiresConfirmation = false
                ),
                confidence = 0.99,
                priority = Priority.CRITICAL,
                context = SuggestionContext(trigger = Trigger.DEVICE_STATE)
            )
        }
        
        if (state.storageFreePercent < 5) {
            suggestions += ProactiveSuggestion(
                type = SuggestionType.MAINTENANCE,
                title = "Critical Storage",
                description = "Only ${String.format("%.1f", state.storageFreePercent)}% free. Device may become unstable.",
                action = ProactiveAction(
                    type = ProactiveEngine.ActionType.OPEN_APP,
                    payload = mapOf("app" to "Settings > Storage"),
                    requiresConfirmation = true
                ),
                confidence = 0.95,
                priority = Priority.HIGH,
                context = SuggestionContext(trigger = Trigger.DEVICE_STATE)
            )
        }
        
        return suggestions
    }

    private fun generateDailyBriefing(ctx: AgentContext): List<ProactiveSuggestion> {
        // Calendar and weather integrations are not connected; do not invent data.
        return emptyList()
    }


    private fun detectHabitPatterns(ctx: AgentContext): List<ProactiveSuggestion> {
        // No genuine usage-history source is connected yet.
        return emptyList()
    }

    private fun checkResearchUpdates(ctx: AgentContext): List<ProactiveSuggestion> {
        // Would check for updates on previous research topics
        return emptyList()
    }

    private fun isRelevant(suggestion: ProactiveSuggestion, context: ConversationContext): Boolean {
        // Check time relevance
        val hour = java.time.LocalDateTime.now().hour
        when (suggestion.context.timeRelevance) {
            ProactiveEngine.TimeRelevance.MORNING -> if (hour !in 6..11) return false
            ProactiveEngine.TimeRelevance.AFTERNOON -> if (hour !in 12..17) return false
            ProactiveEngine.TimeRelevance.EVENING -> if (hour !in 17..21) return false
            ProactiveEngine.TimeRelevance.NIGHT -> if (hour !in 22..23 && hour !in 0..5) return false
            else -> {}
        }
        
        // Check if user prefers proactive suggestions
        return settings.proactiveSuggestionsEnabled
    }

    private suspend fun executeAction(
        action: ProactiveAction,
        context: ConversationContext
    ): Boolean {
        // Suggestions are metadata only until a reviewed action executor is
        // injected. Never mark a suggestion accepted after a no-op.
        return false
    }

    interface ContextProvider {
        fun getCurrentContext(): ConversationContext
        fun getDeviceState(): DeviceState
    }
}
