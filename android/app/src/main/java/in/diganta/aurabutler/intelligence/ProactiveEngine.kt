package `in`.diganta.aurabutler.intelligence

import `in`.diganta.aurabutler.data.AiProvider
import `in`.diganta.aurabutler.memory.SemanticMemory
import `in`.diganta.aurabutler.model.ConversationContext
import `in`.diganta.aurabutler.model.ButlerSettings
import `in`.diganta.aurabutler.runtime.SecretRedactor
import `in`.diganta.aurabutler.model.ButlerAction
import `in`.diganta.aurabutler.model.Sender
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

private const val MIN_REFRESH_INTERVAL_MS = 60_000L
private const val MIN_SUGGESTION_CONFIDENCE = 0.4
private const val INTERRUPTED_AGENT_RETRY_MS = 5 * 60 * 1_000L

/**
 * Proactive intelligence engine that anticipates needs, suggests actions,
 * and runs background agents for continuous assistance.
 */
class ProactiveEngine(
    private val aiProvider: AiProvider?,
    semanticMemory: SemanticMemory?,
    settings: ButlerSettings,
    private val contextProvider: ContextProvider,
    private val persistence: ProactiveSuggestionPersistence? = null,
    private val actionHandler: ActionHandler? = null
) {
    @Volatile
    private var settings: ButlerSettings = settings

    @Volatile
    private var semanticMemory: SemanticMemory? = semanticMemory

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
        val accepted: Boolean = false,
        val pendingApprovalAction: ButlerAction? = null,
        val lastActionError: String? = null
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
        val userPreferences: ButlerSettings,
        val recentMessages: List<String> = emptyList(),
        val recentMessageTimestamps: List<Long> = emptyList()
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

    data class AgentState(
        val id: String,
        val enabled: Boolean,
        val lastRun: Long?,
        val nextRun: Long?,
        val runCount: Int,
        val successCount: Int,
        val errorCount: Int
    )

    data class ActionResult(
        val completed: Boolean,
        val detail: String? = null,
        val pendingApproval: ButlerAction? = null,
        val error: String? = null
    )

    interface ActionHandler {
        suspend fun execute(
            action: ProactiveAction,
            context: ConversationContext
        ): ActionResult
    }

    private val suggestions = ConcurrentHashMap<String, ProactiveSuggestion>()
    private val agents = ConcurrentHashMap<String, BackgroundAgent>()
    private val dueAgentMutex = Mutex()
    @Volatile
    private var scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(2)
    @Volatile
    private var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile
    private var running = false

    init {
        persistence?.loadSuggestions()
            ?.filter { it.expiresAt > System.currentTimeMillis() }
            ?.forEach { suggestions[it.id] = it }
        registerDefaultAgents()
        persistence?.loadAgentStates()?.forEach { state ->
            agents.computeIfPresent(state.id) { _, current ->
                current.copy(
                    enabled = state.enabled,
                    lastRun = state.lastRun,
                    nextRun = state.nextRun,
                    runCount = state.runCount.coerceAtLeast(0),
                    successCount = state.successCount.coerceAtLeast(0),
                    errorCount = state.errorCount.coerceAtLeast(0)
                ).also(::persistAgent)
            }
        }
    }

    fun updateSemanticMemory(memory: SemanticMemory?) {
        semanticMemory = memory
        val consolidation = agents.values.firstOrNull {
            it.id == "aura-agent-memory-consolidation-v1"
        } ?: return
        val enabled = memory != null && settings.proactiveSuggestionsEnabled
        val updated = consolidation.copy(enabled = enabled)
        agents[updated.id] = updated
        persistAgent(updated)
        if (running && enabled) scheduleAgent(updated)
    }

    fun updateSettings(updated: ButlerSettings) {
        settings = updated
        if (!updated.proactiveSuggestionsEnabled) stop()
        // Production scheduling is owned by AndroidProactiveScheduler/WorkManager.
        // Call start() explicitly only for in-process consumers that opt into it.
    }

    /**
     * Start the proactive engine - begins background agents and suggestion generation.
     */
    @Synchronized
    fun start() {
        if (running || !settings.proactiveSuggestionsEnabled) return
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

    @Volatile
    private var lastSuggestionRefreshAt: Long = 0L

    private val suggestionRefreshLock = Any()

    /**
     * Re-evaluate local context on demand. Calls are throttled to prevent
     * chat-message bursts from repeatedly running consolidation and pattern
     * analysis. The scheduled agent executor remains independent.
     */
    suspend fun refreshNow(force: Boolean = false) {
        if (!settings.proactiveSuggestionsEnabled) return
        val now = System.currentTimeMillis()
        val shouldRefresh = synchronized(suggestionRefreshLock) {
            if (!force && now - lastSuggestionRefreshAt < MIN_REFRESH_INTERVAL_MS) {
                false
            } else {
                lastSuggestionRefreshAt = now
                true
            }
        }
        if (shouldRefresh) generateProactiveSuggestions()
    }

    /**
     * Get current proactive suggestions for the user.
     */
    fun getSuggestions(context: ConversationContext, limit: Int = 5): List<ProactiveSuggestion> {
        val now = System.currentTimeMillis()
        return suggestions.values
            .filter {
                !it.dismissed && !it.accepted &&
                    it.pendingApprovalAction == null && it.expiresAt > now
            }
            .filter { isRelevant(it, context) }
            .sortedByDescending { it.priority.weight * it.confidence }
            .take(limit)
            .toList()
    }

    /**
     * Mark a suggestion as dismissed.
     */
    fun dismissSuggestion(id: String) {
        suggestions[id]?.let { existing ->
            if (existing.pendingApprovalAction == null) {
                val updated = existing.copy(dismissed = true)
                suggestions[id] = updated
                persistence?.saveSuggestion(updated)
            }
        }
    }

    /**
     * Mark a suggestion as accepted and execute its action.
     */
    suspend fun acceptSuggestion(id: String, context: ConversationContext): Boolean =
        acceptSuggestionDetailed(id, context).completed

    suspend fun acceptSuggestionDetailed(
        id: String,
        context: ConversationContext
    ): ActionResult {
        val suggestion = suggestions[id] ?: return ActionResult(
            completed = false,
            error = "The proactive suggestion no longer exists."
        )
        if (suggestion.expiresAt <= System.currentTimeMillis()) {
            dismissSuggestion(id)
            return ActionResult(completed = false, error = "This suggestion has expired.")
        }
        val handler = actionHandler ?: return ActionResult(
            completed = false,
            error = "No production action handler is configured."
        )
        val result = try {
            handler.execute(suggestion.action, context)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            ActionResult(
                completed = false,
                error = (error.message ?: error.javaClass.simpleName).take(500)
            )
        }

        val updated = when {
            result.pendingApproval != null -> suggestion.copy(
                pendingApprovalAction = result.pendingApproval,
                lastActionError = null
            )
            result.completed -> suggestion.copy(
                accepted = true,
                pendingApprovalAction = null,
                lastActionError = null
            )
            else -> suggestion.copy(
                lastActionError = result.error ?: result.detail ?: "The suggested action did not complete."
            )
        }
        suggestions[id] = updated
        persistence?.saveSuggestion(updated)
        return result
    }

    fun resolveActionApproval(
        actionId: String,
        completed: Boolean,
        detail: String? = null
    ): Boolean {
        val suggestion = suggestions.values.firstOrNull {
            it.pendingApprovalAction?.id == actionId
        } ?: return false
        val updated = suggestion.copy(
            accepted = completed,
            dismissed = !completed,
            pendingApprovalAction = null,
            lastActionError = if (completed) null else {
                detail?.take(500) ?: "The user declined or the approved action failed."
            }
        )
        suggestions[suggestion.id] = updated
        persistence?.saveSuggestion(updated)
        return true
    }

    fun getSuggestionForAction(actionId: String): ProactiveSuggestion? =
        suggestions.values.firstOrNull { it.pendingApprovalAction?.id == actionId }

    fun getSuggestion(id: String): ProactiveSuggestion? = suggestions[id]

    fun getAgentStates(): List<AgentState> = agents.values.map { agent ->
        AgentState(
            id = agent.id,
            enabled = agent.enabled,
            lastRun = agent.lastRun,
            nextRun = agent.nextRun,
            runCount = agent.runCount,
            successCount = agent.successCount,
            errorCount = agent.errorCount
        )
    }

    /**
     * Executes only agents whose persisted deadline is due. Used by WorkManager
     * after process recreation; it does not start the in-process timer pool.
     */
    suspend fun runDueAgentsNow(): Int = dueAgentMutex.withLock {
        if (!settings.proactiveSuggestionsEnabled) return@withLock 0
        val now = System.currentTimeMillis()
        val due = agents.values
            .filter { it.enabled && (it.nextRun == null || it.nextRun <= now) }
            .sortedBy { it.nextRun ?: 0L }
        due.forEach { agent ->
            currentCoroutineContext().ensureActive()
            runAgent(agent)
        }
        due.size
    }

    fun nextAgentRunAt(): Long? = agents.values
        .filter { it.enabled }
        .mapNotNull { it.nextRun }
        .minOrNull()

    /**
     * Register a custom background agent.
     */
    fun registerAgent(agent: BackgroundAgent) {
        agents[agent.id] = agent
        persistAgent(agent)
        if (running && agent.enabled) scheduleAgent(agent)
    }

    private fun persistAgent(agent: BackgroundAgent) {
        persistence?.saveAgentState(
            AgentState(
                id = agent.id,
                enabled = agent.enabled,
                lastRun = agent.lastRun,
                nextRun = agent.nextRun,
                runCount = agent.runCount,
                successCount = agent.successCount,
                errorCount = agent.errorCount
            )
        )
    }

    /**
     * Get agent status.
     */
    fun getAgentStatus(): List<BackgroundAgent> = agents.values.toList()

    // ===== Private Implementation =====

    private fun registerDefaultAgents() {
        // Daily briefing agent
        registerAgent(BackgroundAgent(
            id = "aura-agent-daily-briefing-v2",
            name = "Daily Briefing",
            description = "Generates a morning summary from local device state and AURA conversation context",
            schedule = AgentSchedule.CRON("0 7 * * *"), // 7 AM daily
            enabled = true,
            task = { context ->
                val briefings = generateDailyBriefing(context)
                val text = briefings.firstOrNull()?.description
                    ?: "AURA checked local device and saved-memory context; no new briefing item is available."
                AgentResult(
                    success = true,
                    output = text,
                    suggestions = briefings
                )
            }
        ))

        // Memory consolidation agent
        registerAgent(BackgroundAgent(
            id = "aura-agent-memory-consolidation-v1",
            name = "Memory Consolidation",
            description = "Consolidates memories, extracts patterns, applies forgetting curve",
            schedule = AgentSchedule.DAILY,
            task = { _ ->
                val memoryEngine = semanticMemory
                if (memoryEngine == null) {
                    AgentResult(
                        success = false,
                        output = "Memory consolidation is waiting for the verified ONNX embedding model.",
                        error = "The on-device semantic model is not installed."
                    )
                } else {
                    val result = memoryEngine.consolidate()
                    AgentResult(
                        success = result.errors.isEmpty(),
                        output = "Memory consolidation: merged ${result.mergedCount}, patterns ${result.patternsFound}, concepts ${result.conceptsCreated}, decayed ${result.decayedCount}.",
                        error = result.errors.takeIf { it.isNotEmpty() }?.joinToString("; ")
                    )
                }
            }
        ))

        // Battery optimization agent
        registerAgent(BackgroundAgent(
            id = "aura-agent-battery-optimization-v1",
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
            id = "aura-agent-storage-monitor-v1",
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
            id = "aura-agent-habit-learning-v2",
            name = "Habit Learning",
            description = "Detects recurring topics and time patterns in AURA's own chat history only",
            schedule = AgentSchedule.WEEKLY,
            enabled = true,
            task = { context ->
                val patterns = detectHabitPatterns(context)
                AgentResult(
                    success = true,
                    output = if (patterns.isEmpty()) {
                        "Insufficient repeated AURA conversation history to identify a reliable pattern."
                    } else {
                        "Detected " + patterns.size + " recurring AURA conversation pattern(s)."
                    },
                    suggestions = patterns
                )
            }
        ))

        // Research follow-up agent
        registerAgent(BackgroundAgent(
            id = "aura-agent-research-follow-up-v2",
            name = "Research Follow-up",
            description = "Offers a user-approved refresh of explicitly tagged saved research notes",
            schedule = AgentSchedule.DAILY,
            enabled = true,
            task = { context ->
                val followUps = checkResearchUpdates(context)
                AgentResult(
                    success = true,
                    output = if (followUps.isEmpty()) {
                        "No explicitly tagged saved research note needs a follow-up prompt."
                    } else {
                        "Prepared " + followUps.size + " saved-research follow-up suggestion(s)."
                    },
                    suggestions = followUps
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
        val now = System.currentTimeMillis()
        val nextRun = agent.nextRun?.takeIf { it > now }
            ?: now + calculateNextRun(agent.schedule)
        val scheduled = agent.copy(nextRun = nextRun)
        agents[agent.id] = scheduled
        persistAgent(scheduled)
        val delay = (nextRun - now).coerceAtLeast(0L)
        scheduler.schedule({
            if (running && agents[agent.id]?.enabled == true) {
                scope.launch { runAgent(scheduled) }
            }
        }, delay, TimeUnit.MILLISECONDS)
    }

    private fun calculateNextRun(schedule: AgentSchedule): Long {
        return when (schedule) {
            AgentSchedule.HOURLY -> 3_600_000L
            AgentSchedule.DAILY -> 24L * 3_600_000L
            AgentSchedule.WEEKLY -> 7L * 24L * 3_600_000L
            is AgentSchedule.ON_EVENT -> 3_600_000L
            is AgentSchedule.CRON -> nextCronTime(schedule.expression)
                ?.minus(System.currentTimeMillis())
                ?.coerceAtLeast(60_000L)
                ?: 3_600_000L
        }
    }

    private fun nextCronTime(expression: String): Long? {
        val fields = expression.trim().split(Regex("\\s+"))
        if (fields.size != 5) return null
        val minute = parseCronField(fields[0], 0, 59) ?: return null
        val hour = parseCronField(fields[1], 0, 23) ?: return null
        val day = parseCronField(fields[2], 1, 31) ?: return null
        val month = parseCronField(fields[3], 1, 12) ?: return null
        val weekday = parseCronField(fields[4], 0, 7) ?: return null
        val dayOfMonthWildcard = fields[2] == "*"
        val weekdayWildcard = fields[4] == "*"

        var candidate = java.time.ZonedDateTime.now()
            .withSecond(0)
            .withNano(0)
            .plusMinutes(1)
        val maximum = candidate.plusYears(1)
        while (candidate.isBefore(maximum)) {
            val javaDay = candidate.dayOfWeek.value % 7
            val cronDay = candidate.dayOfMonth
            val cronWeekday = javaDay
            if (
                candidate.minute in minute &&
                candidate.hour in hour &&
                candidate.monthValue in month &&
                (
                    (dayOfMonthWildcard && weekdayWildcard) ||
                    (dayOfMonthWildcard && (cronWeekday in weekday || (cronWeekday == 0 && 7 in weekday))) ||
                    (weekdayWildcard && cronDay in day) ||
                    (!dayOfMonthWildcard && !weekdayWildcard &&
                        (cronDay in day || cronWeekday in weekday || (cronWeekday == 0 && 7 in weekday)))
                )
            ) {
                return candidate.toInstant().toEpochMilli()
            }
            candidate = candidate.plusMinutes(1)
        }
        return null
    }

    private fun parseCronField(field: String, min: Int, max: Int): Set<Int>? {
        val values = mutableSetOf<Int>()
        field.split(',').forEach { part ->
            val pieces = part.split('/')
            if (pieces.size > 2) return null
            val step = pieces.getOrNull(1)?.toIntOrNull() ?: 1
            if (step !in 1..(max - min + 1)) return null
            val base = pieces[0]
            val range = when {
                base == "*" -> min..max
                base.contains('-') -> {
                    val endpoints = base.split('-')
                    if (endpoints.size != 2) return null
                    val start = endpoints[0].toIntOrNull() ?: return null
                    val end = endpoints[1].toIntOrNull() ?: return null
                    if (start !in min..max || end !in start..max) return null
                    start..end
                }
                else -> {
                    val value = base.toIntOrNull() ?: return null
                    if (value !in min..max) return null
                    value..value
                }
            }
            range.filterIndexed { index, _ -> index % step == 0 }.forEach(values::add)
        }
        return values.takeIf { it.isNotEmpty() }
    }

    private suspend fun runAgent(agent: BackgroundAgent) {
        val updatedAgent = agent.copy(
            lastRun = System.currentTimeMillis(),
            runCount = agent.runCount + 1
        )
        agents[agent.id] = updatedAgent
        persistAgent(updatedAgent)

        try {
            val agentContext = buildAgentContext()
            val result = agent.task.execute(agentContext)
            val nextRun = System.currentTimeMillis() + calculateNextRun(agent.schedule)
            val completedAgent = updatedAgent.copy(
                successCount = updatedAgent.successCount + if (result.success) 1 else 0,
                errorCount = updatedAgent.errorCount + if (result.success) 0 else 1,
                nextRun = nextRun
            )
            agents[agent.id] = completedAgent
            persistAgent(completedAgent)

            if (result.success) result.suggestions.forEach(::upsertSuggestion)

            // Agent-generated actions are never executed automatically.
            // The user must review a suggestion and explicitly accept it.
            if (running && completedAgent.enabled) scheduleAgent(completedAgent)
        } catch (cancelled: CancellationException) {
            val interruptedAgent = updatedAgent.copy(
                nextRun = System.currentTimeMillis() + INTERRUPTED_AGENT_RETRY_MS
            )
            agents[agent.id] = interruptedAgent
            persistAgent(interruptedAgent)
            throw cancelled
        } catch (error: Exception) {
            val failedAgent = updatedAgent.copy(
                errorCount = updatedAgent.errorCount + 1,
                nextRun = System.currentTimeMillis() + calculateNextRun(agent.schedule)
            )
            agents[agent.id] = failedAgent
            persistAgent(failedAgent)
            if (running && failedAgent.enabled) scheduleAgent(failedAgent)
        }
    }

    private suspend fun buildAgentContext(): AgentContext {
        val conversationContext = contextProvider.getCurrentContext()
        val memoryResult = semanticMemory?.recall(
            query = "recent",
            context = conversationContext,
            options = SemanticMemory.RecallOptions(
                maxResults = 20,
                strategy = SemanticMemory.RetrievalStrategy.TEMPORAL
            )
        )
        val userMessages = conversationContext.recentMessages
            .asSequence()
            .filter { it.sender == Sender.USER }
            .filter { !SecretRedactor.containsSensitive(it.text) }
            .takeLast(80)
            .toList()
        return AgentContext(
            deviceState = contextProvider.getDeviceState(),
            recentMemories = memoryResult?.entries?.map { it.entry }
                ?: conversationContext.memories.map { item ->
                    SemanticMemory.MemoryEntry(
                        id = item.id,
                        content = SecretRedactor.redact(item.value, 2_000),
                        label = SecretRedactor.redact(item.label, 120),
                        embedding = null,
                        lastAccessed = item.createdAtEpochMs,
                        createdAt = item.createdAtEpochMs,
                        tags = if (item.label.startsWith("[RESEARCH]", ignoreCase = true)) {
                            setOf("research")
                        } else {
                            emptySet()
                        }
                    )
                },
            activePlans = emptyList(),
            userPreferences = settings,
            recentMessages = userMessages.map { SecretRedactor.redact(it.text, 1_000) },
            recentMessageTimestamps = userMessages.map { it.createdAtEpochMs }
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

        // Local, privacy-bounded behaviour and research follow-ups.
        allSuggestions += detectHabitPatterns(context)
        allSuggestions += checkResearchUpdates(context)
        allSuggestions += generateDailyBriefing(context)

        // Filter and store
        val now = System.currentTimeMillis()
        for (suggestion in allSuggestions) {
            if (
                suggestion.confidence >= MIN_SUGGESTION_CONFIDENCE &&
                suggestion.expiresAt > now &&
                suggestion.title.isNotBlank() &&
                suggestion.description.isNotBlank() &&
                !SecretRedactor.containsLikelySecret(suggestion.title + " " + suggestion.description)
            ) {
                upsertSuggestion(suggestion)
            }
        }

        val expired = suggestions.values.filter { it.expiresAt <= now }.map { it.id }
        expired.forEach { id ->
            suggestions.remove(id)
            persistence?.deleteSuggestion(id)
        }
    }

    private fun upsertSuggestion(generated: ProactiveSuggestion) {
        val stableId = UUID.nameUUIDFromBytes(
            ("aura-proactive-v1:" + generated.type.name + ":" +
                generated.title.trim().lowercase(java.util.Locale.ROOT))
                .toByteArray(Charsets.UTF_8)
        ).toString()
        val old = suggestions[stableId]
        val updated = generated.copy(
            id = stableId,
            dismissed = old?.dismissed ?: false,
            accepted = old?.accepted ?: false,
            pendingApprovalAction = old?.pendingApprovalAction,
            lastActionError = old?.lastActionError
        )
        suggestions[stableId] = updated
        persistence?.saveSuggestion(updated)
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
        if (ctx.recentMessages.isEmpty()) return emptyList()
        val recentUserText = ctx.recentMessages.takeLast(8)
            .filter { !SecretRedactor.containsSensitive(it) }
            .map { SecretRedactor.redact(it, 800) }
        if (recentUserText.isEmpty()) return emptyList()

        val reminders = recentUserText.filter { message ->
            listOf(
                "remind me", "don't forget", "need to finish",
                "i should", "follow up", "deadline", "due tomorrow",
                "tomorrow i will", "next week"
            ).any { phrase -> message.contains(phrase, ignoreCase = true) }
        }
        val latest = reminders.lastOrNull() ?: return emptyList()
        val subject = latest.take(160).trim()
        return listOf(
            ProactiveSuggestion(
                type = SuggestionType.FOLLOW_UP,
                title = "Follow-up from your conversation",
                description = "You mentioned: \"$subject\". Would you like me to turn this into a reminder or a task plan?",
                action = ProactiveAction(
                    type = ActionType.ASK_QUESTION,
                    payload = mapOf("question" to "Create a reminder or task plan for this follow-up?")
                ),
                confidence = 0.68,
                priority = Priority.MEDIUM,
                context = SuggestionContext(trigger = Trigger.CONVERSATION_CONTEXT)
            )
        )
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
        val localNow = java.time.ZonedDateTime.now()
        if (localNow.hour !in 6..10) return emptyList()

        val device = ctx.deviceState
        val memoryTopics = ctx.recentMemories
            .flatMap { it.metadata.relatedEntities }
            .map(String::trim)
            .filter(String::isNotBlank)
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(3)
            .map { it.key }

        val description = buildString {
            append("Your local AURA briefing: battery ")
            append(device.batteryLevel).append("%")
            append(if (device.isCharging) " and charging" else " and not charging")
            append("; network ").append(device.networkType)
            append("; app-storage availability ")
            append(device.storageFreePercent.toInt()).append("%.")
            if (memoryTopics.isNotEmpty()) {
                append(" Topics in your saved memory include ")
                append(memoryTopics.joinToString(", "))
                append(".")
            }
            append(" This uses only AURA's local conversation, memory, and device-state data; ")
            append("calendar, email, location, and other-app activity are not connected.")
        }
        return listOf(
            ProactiveSuggestion(
                type = SuggestionType.PREPARATION,
                title = "Your local daily briefing — ${localNow.toLocalDate()}",
                description = description,
                action = ProactiveAction(
                    type = ActionType.SHOW_INSIGHT,
                    payload = mapOf("insight" to description),
                    requiresConfirmation = false
                ),
                confidence = 0.82,
                priority = Priority.MEDIUM,
                context = SuggestionContext(
                    trigger = Trigger.TIME_BASED,
                    relatedMemoryIds = ctx.recentMemories.take(5).map { it.id },
                    timeRelevance = TimeRelevance.MORNING
                ),
                expiresAt = System.currentTimeMillis() + 4 * 60 * 60 * 1_000L
            )
        )
    }

    private fun detectHabitPatterns(ctx: AgentContext): List<ProactiveSuggestion> {
        val now = System.currentTimeMillis()
        val cutoff = now - HABIT_LOOKBACK_DAYS * MILLIS_PER_DAY
        val activities = ctx.recentMessages.zip(ctx.recentMessageTimestamps)
            .filter { (_, timestamp) -> timestamp in cutoff..now }
        if (activities.size < MIN_PATTERN_EVENTS) return emptyList()

        val categories = listOf(
            "software development" to listOf(
                "coding", "code", "programming", "debug", "bug", "api", "website", "app development"
            ),
            "study and coursework" to listOf(
                "study", "exam", "lecture", "assignment", "semester", "lab report", "coursework"
            ),
            "design and creative work" to listOf(
                "design", "drawing", "illustration", "artwork", "craft", "poster"
            ),
            "planning and deadlines" to listOf(
                "deadline", "remind me", "task list", "schedule", "plan my", "due date"
            )
        )
        val periodNames = mapOf(
            0 to "overnight",
            1 to "morning",
            2 to "afternoon",
            3 to "evening"
        )
        val results = mutableListOf<ProactiveSuggestion>()

        for ((category, phrases) in categories) {
            val matching = activities.filter { (message, _) ->
                phrases.any { message.contains(it, ignoreCase = true) }
            }
            val strongestPeriod = matching.groupBy { (_, timestamp) ->
                val hour = java.time.Instant.ofEpochMilli(timestamp)
                    .atZone(java.time.ZoneId.systemDefault()).hour
                when (hour) {
                    in 0..4 -> 0
                    in 5..11 -> 1
                    in 12..17 -> 2
                    else -> 3
                }
            }.entries.maxByOrNull { (_, items) ->
                items.map { (_, timestamp) ->
                    java.time.Instant.ofEpochMilli(timestamp)
                        .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                }.distinct().size
            } ?: continue

            val distinctDays = strongestPeriod.value.map { (_, timestamp) ->
                java.time.Instant.ofEpochMilli(timestamp)
                    .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            }.distinct().size
            if (distinctDays < MIN_PATTERN_DAYS) continue

            val period = periodNames[strongestPeriod.key] ?: "different times"
            val description = "Your recent AURA conversations returned to " + category +
                " during the " + period + " on " + distinctDays +
                " separate days in the last " + HABIT_LOOKBACK_DAYS +
                " days. This reflects AURA chat history only, not activity in other apps. " +
                "Would a flexible recurring task plan help?"
            results += ProactiveSuggestion(
                type = SuggestionType.INSIGHT,
                title = "Recurring topic: " + category,
                description = description,
                action = ProactiveAction(
                    type = ActionType.SCHEDULE_TASK,
                    payload = mapOf(
                        "goal" to "Create a flexible, user-confirmed routine for " + category +
                            ". Ask for preferred days and times before scheduling or changing anything."
                    ),
                    requiresConfirmation = true
                ),
                confidence = (0.55 + distinctDays * 0.06).coerceAtMost(0.84),
                priority = Priority.LOW,
                context = SuggestionContext(
                    trigger = Trigger.USER_HABIT,
                    timeRelevance = TimeRelevance.ANYTIME
                ),
                expiresAt = now + SUGGESTION_TTL_MS
            )
        }
        return results.sortedByDescending { it.confidence }.take(MAX_HABIT_SUGGESTIONS)
    }

    private fun checkResearchUpdates(ctx: AgentContext): List<ProactiveSuggestion> {
        val researchEntries = ctx.recentMemories.filter { entry ->
            entry.tags.any { tag ->
                tag.equals("research", ignoreCase = true) ||
                    tag.equals("research-note", ignoreCase = true) ||
                    tag.startsWith("research-topic:", ignoreCase = true)
            } || entry.label.startsWith("[RESEARCH]", ignoreCase = true)
        }.sortedByDescending { it.lastAccessed }.take(MAX_RESEARCH_FOLLOW_UPS)

        return researchEntries.mapNotNull { entry ->
            val label = SecretRedactor.redact(entry.label, 120)
            val summary = SecretRedactor.redact(entry.content, 420)
            if (
                label.isBlank() ||
                summary.isBlank() ||
                SecretRedactor.containsSensitive(label + " " + summary)
            ) return@mapNotNull null

            val goal = "Refresh this saved research note using current reliable sources. " +
                "Compare new information with the saved note and clearly identify what changed, " +
                "what remains current, and any uncertainty. Saved topic: " + label +
                ". Saved note: " + summary
            ProactiveSuggestion(
                type = SuggestionType.FOLLOW_UP,
                title = "Refresh saved research: " + label.take(64),
                description = "AURA found an explicitly tagged research note in local memory. " +
                    "Would you like a current-source review of '" + label + "'?",
                action = ProactiveAction(
                    type = ActionType.START_RESEARCH,
                    payload = mapOf("goal" to goal.take(MAX_RESEARCH_GOAL_CHARS)),
                    requiresConfirmation = true
                ),
                confidence = 0.74,
                priority = Priority.MEDIUM,
                context = SuggestionContext(
                    trigger = Trigger.MEMORY_PATTERN,
                    relatedMemoryIds = listOf(entry.id)
                ),
                expiresAt = System.currentTimeMillis() + SUGGESTION_TTL_MS
            )
        }
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
        
        return context.settings.proactiveSuggestionsEnabled
    }

    private suspend fun executeAction(
        action: ProactiveAction,
        context: ConversationContext
    ): Boolean {
        val handler = actionHandler ?: return false
        val result = handler.execute(action, context)
        return result.completed
    }

    interface ContextProvider {
        fun getCurrentContext(): ConversationContext
        fun getDeviceState(): DeviceState
    }
}


interface ProactiveSuggestionPersistence {
    fun loadSuggestions(): List<ProactiveEngine.ProactiveSuggestion>
    fun saveSuggestion(suggestion: ProactiveEngine.ProactiveSuggestion)
    fun deleteSuggestion(id: String)
    fun loadAgentStates(): List<ProactiveEngine.AgentState>
    fun saveAgentState(state: ProactiveEngine.AgentState)
}

private const val HABIT_LOOKBACK_DAYS = 30
private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1_000
private const val MIN_PATTERN_EVENTS = 3
private const val MIN_PATTERN_DAYS = 3
private const val MAX_HABIT_SUGGESTIONS = 2
private const val MAX_RESEARCH_FOLLOW_UPS = 2
private const val MAX_RESEARCH_GOAL_CHARS = 2_000
private const val SUGGESTION_TTL_MS = 24L * 60 * 60 * 1_000
