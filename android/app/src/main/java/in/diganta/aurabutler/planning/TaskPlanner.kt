package `in`.diganta.aurabutler.planning

import `in`.diganta.aurabutler.intelligence.ReasoningEngine
import `in`.diganta.aurabutler.model.ConversationContext
import `in`.diganta.aurabutler.model.ButlerAction
import `in`.diganta.aurabutler.model.ActionKind
import `in`.diganta.aurabutler.model.ActionRisk
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Multi-step planning with dependency validation, checkpoints, and progress tracking.
 * Execution is limited to the task handlers with real implementations; unsupported
 * executor types fail explicitly rather than reporting simulated success.
 */
class TaskPlanner(
    private val reasoningEngine: ReasoningEngine,
    private val toolRegistry: ReasoningEngine.ToolRegistry,
    private val planStore: PlanStore,
    private val executor: ProductionTaskExecutor =
        ProductionTaskExecutor(reasoningEngine)
) {

    fun close() {
        planStore.close()
    }

    data class TaskNode(
        val id: String = UUID.randomUUID().toString(),
        val title: String,
        val description: String,
        val type: TaskType,
        val dependencies: List<String> = emptyList(),  // Task IDs that must complete first
        val tool: String? = null,                      // Tool to use for this task
        val toolArguments: Map<String, Any> = emptyMap(),
        val status: TaskStatus = TaskStatus.PENDING,
        val result: String? = null,
        val error: String? = null,
        val retryCount: Int = 0,
        val maxRetries: Int = 2,
        val checkpoint: Boolean = false,               // Save state after this task
        val estimatedDurationMs: Long = 5000,
        val createdAt: Long = System.currentTimeMillis(),
        val startedAt: Long? = null,
        val completedAt: Long? = null
    )

    enum class TaskType {
        RESEARCH,       // Information gathering
        ANALYSIS,       // Processing/synthesizing information
        EXECUTION,      // Running code, commands, actions
        CREATION,       // Writing files, generating content
        VERIFICATION,   // Checking results, testing
        COMMUNICATION,  // User interaction, notifications
        DECISION        // Branching based on conditions
    }

    enum class TaskStatus {
        PENDING,
        READY,          // Dependencies met, ready to execute
        RUNNING,
        COMPLETED,
        FAILED,
        SKIPPED,
        BLOCKED,
        AWAITING_APPROVAL,
        CANCELLED
    }

    data class Plan(
        val id: String = UUID.randomUUID().toString(),
        val title: String,
        val description: String,
        val tasks: List<TaskNode> = emptyList(),
        val status: PlanStatus = PlanStatus.DRAFT,
        val createdAt: Long = System.currentTimeMillis(),
        val updatedAt: Long = System.currentTimeMillis(),
        val completedAt: Long? = null,
        val contextSnapshot: String? = null,  // Serialized context for resumption
        val metadata: Map<String, String> = emptyMap()
    )

    enum class PlanStatus {
        DRAFT,
        ACTIVE,
        PAUSED,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    data class ExecutionResult(
        val planId: String,
        val success: Boolean,
        val completedTasks: Int,
        val failedTasks: Int,
        val totalDurationMs: Long,
        val finalOutput: String?,
        val errorSummary: String?,
        val pendingApproval: ButlerAction? = null
    )

    /**
     * Create a plan from a high-level goal using AI reasoning.
     */
    suspend fun createPlanFromGoal(
        goal: String,
        context: ConversationContext,
        options: PlanOptions = PlanOptions()
    ): Plan = withContext(Dispatchers.IO) {
        // Use reasoning engine to break down the goal
        val reasoningResult = reasoningEngine.reason(
            task = """
                Break down this goal into a detailed execution plan with specific, actionable tasks.
                Goal: $goal
                
                For each task, specify:
                1. Clear title and description
                2. Task type (RESEARCH, ANALYSIS, EXECUTION, CREATION, VERIFICATION, COMMUNICATION, DECISION)
                3. Dependencies on other tasks
                4. Required tool (if any) and arguments
                5. Whether it's a checkpoint (save state after)
                6. Estimated duration
                
                Return only a valid JSON array (no Markdown fences) with fields:
                id (unique step-N string), title, description, type, dependencies (array of step IDs),
                tool (optional), toolArguments (JSON object), checkpoint (boolean), and estimatedDurationMs.
                Use only RESEARCH, ANALYSIS, EXECUTION, CREATION, VERIFICATION, COMMUNICATION, DECISION.
                Research tools: web_search, news_search, get_weather, weather_forecast.
                Execution tools may only be open_url, open_app, open_setting, dial_phone, map_route,
                set_alarm, set_timer, draft_message, compose_email, share_text, set_volume,
                set_brightness, flashlight, open_camera, set_reminder, clear_aura_cache,
                cooling_assist. Device/external operations require explicit approval.
                Never request shell commands, arbitrary code execution, payment, deletion outside AURA,
                or sending a message automatically. Communication tasks create drafts only.
                A decision task must specify operator, left, and right where required.
                Verification must specify check and expected values where applicable.
                """.trimIndent(),
            context = context,
            options = ReasoningEngine.ReasoningOptions(enableToolUse = false, enableReflection = true)
        )

        val tasks = parseTasksFromReasoning(reasoningResult.reasoningTrace, reasoningResult.finalAnswer)
        
        val plan = Plan(
            title = "Plan: ${goal.take(80)}",
            description = goal,
            tasks = tasks,
            contextSnapshot = serializeContext(context)
        )

        planStore.save(plan)
        plan
    }

    /**
     * Execute a plan autonomously with progress tracking and error recovery.
     */
    suspend fun executePlan(
        planId: String,
        context: ConversationContext,
        options: PlanOptions = PlanOptions(),
        onProgress: (Plan, TaskNode, TaskStatus) -> Unit = { _, _, _ -> }
    ): ExecutionResult = withContext(Dispatchers.IO) {
        val plan = planStore.get(planId)
            ?: throw IllegalArgumentException("Plan not found: $planId")
        val mutablePlan = plan.copy(
            status = PlanStatus.ACTIVE,
            updatedAt = System.currentTimeMillis()
        )
        val taskMap = mutablePlan.tasks.associateBy { it.id }.toMutableMap()
        var currentTaskId: String? = null
        var completedCount = taskMap.values.count { it.status == TaskStatus.COMPLETED }
        var failedCount = taskMap.values.count { it.status == TaskStatus.FAILED }
        var errorSummary: String? = null
        val startTime = System.currentTimeMillis()

        planStore.save(mutablePlan)

        try {
            val dependencyGraph = buildDependencyGraph(mutablePlan.tasks)
            val executionOrder = topologicalSort(dependencyGraph)

            for (taskId in executionOrder) {
                currentTaskId = taskId
                val task = taskMap[taskId]
                    ?: throw IllegalStateException("Plan task disappeared: $taskId")

                if (task.status == TaskStatus.COMPLETED || task.status == TaskStatus.SKIPPED) {
                    currentTaskId = null
                    continue
                }

                if (task.status == TaskStatus.AWAITING_APPROVAL) {
                    val action = restorePendingApproval(task)
                        ?: throw IllegalStateException(
                            "A persisted approval checkpoint is missing its validated action."
                        )
                    val pausedPlan = mutablePlan.copy(
                        status = PlanStatus.PAUSED,
                        tasks = taskMap.values.toList(),
                        updatedAt = System.currentTimeMillis()
                    )
                    planStore.save(pausedPlan)
                    currentTaskId = null
                    return@withContext ExecutionResult(
                        planId = planId,
                        success = false,
                        completedTasks = completedCount,
                        failedTasks = failedCount,
                        totalDurationMs = System.currentTimeMillis() - startTime,
                        finalOutput = "Plan paused. Approval is required for: " + action.title,
                        errorSummary = null,
                        pendingApproval = action
                    )
                }

                if (!dependenciesMet(task, taskMap)) {
                    val blockedTask = task.copy(
                        status = TaskStatus.BLOCKED,
                        error = "A dependency did not complete successfully.",
                        completedAt = System.currentTimeMillis()
                    )
                    taskMap[taskId] = blockedTask
                    planStore.save(
                        mutablePlan.copy(
                            status = PlanStatus.ACTIVE,
                            tasks = taskMap.values.toList(),
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                    onProgress(
                        mutablePlan.copy(tasks = taskMap.values.toList()),
                        blockedTask,
                        TaskStatus.BLOCKED
                    )
                    currentTaskId = null
                    continue
                }

                val runningTask = task.copy(
                    status = TaskStatus.RUNNING,
                    startedAt = task.startedAt ?: System.currentTimeMillis(),
                    error = null,
                    completedAt = null
                )
                taskMap[taskId] = runningTask
                planStore.save(
                    mutablePlan.copy(
                        tasks = taskMap.values.toList(),
                        updatedAt = System.currentTimeMillis()
                    )
                )

                val result = executeTaskWithRetry(
                    runningTask,
                    context,
                    mutablePlan.copy(tasks = taskMap.values.toList()),
                    onProgress
                )
                taskMap[taskId] = result.updatedTask

                if (result.pendingApproval != null) {
                    val pausedPlan = mutablePlan.copy(
                        status = PlanStatus.PAUSED,
                        tasks = taskMap.values.toList(),
                        updatedAt = System.currentTimeMillis()
                    )
                    planStore.save(pausedPlan)
                    onProgress(
                        pausedPlan,
                        result.updatedTask,
                        TaskStatus.AWAITING_APPROVAL
                    )
                    currentTaskId = null
                    return@withContext ExecutionResult(
                        planId = planId,
                        success = false,
                        completedTasks = completedCount,
                        failedTasks = failedCount,
                        totalDurationMs = System.currentTimeMillis() - startTime,
                        finalOutput = "Plan paused for approval: " + result.pendingApproval.title,
                        errorSummary = null,
                        pendingApproval = result.pendingApproval
                    )
                }

                if (result.updatedTask.status == TaskStatus.COMPLETED) {
                    completedCount++
                } else if (result.updatedTask.status == TaskStatus.FAILED) {
                    failedCount++
                    errorSummary = result.updatedTask.error
                    if (!options.continueOnFailure) {
                        planStore.save(
                            mutablePlan.copy(
                                tasks = taskMap.values.toList(),
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                        currentTaskId = null
                        break
                    }
                }

                planStore.save(
                    mutablePlan.copy(
                        tasks = taskMap.values.toList(),
                        updatedAt = System.currentTimeMillis()
                    )
                )
                onProgress(
                    mutablePlan.copy(tasks = taskMap.values.toList()),
                    result.updatedTask,
                    result.updatedTask.status
                )
                currentTaskId = null
            }

            val allTasks = taskMap.values.toList()
            val finalStatus = when {
                failedCount > 0 || allTasks.any { it.status == TaskStatus.BLOCKED } ->
                    PlanStatus.FAILED
                allTasks.isNotEmpty() && allTasks.all {
                    it.status == TaskStatus.COMPLETED || it.status == TaskStatus.SKIPPED
                } -> PlanStatus.COMPLETED
                else -> PlanStatus.FAILED
            }
            val finalOutput = synthesizePlanOutput(allTasks, mutablePlan.description)
            val finalPlan = mutablePlan.copy(
                status = finalStatus,
                tasks = allTasks,
                completedAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
            planStore.save(finalPlan)

            ExecutionResult(
                planId = planId,
                success = finalStatus == PlanStatus.COMPLETED,
                completedTasks = completedCount,
                failedTasks = failedCount,
                totalDurationMs = System.currentTimeMillis() - startTime,
                finalOutput = finalOutput,
                errorSummary = errorSummary
            )
        } catch (cancelled: CancellationException) {
            currentTaskId?.let { taskId ->
                val task = taskMap[taskId]
                if (task?.status == TaskStatus.RUNNING) {
                    taskMap[taskId] = task.copy(
                        status = TaskStatus.CANCELLED,
                        error = "Execution was cancelled before a terminal result was recorded.",
                        completedAt = System.currentTimeMillis()
                    )
                }
            }
            planStore.save(
                mutablePlan.copy(
                    status = PlanStatus.CANCELLED,
                    tasks = taskMap.values.toList(),
                    completedAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
            )
            throw cancelled
        } catch (error: Exception) {
            currentTaskId?.let { taskId ->
                val task = taskMap[taskId]
                if (task?.status == TaskStatus.RUNNING) {
                    taskMap[taskId] = task.copy(
                        status = TaskStatus.FAILED,
                        error = error.message ?: error.javaClass.simpleName,
                        completedAt = System.currentTimeMillis()
                    )
                    failedCount++
                }
            }
            errorSummary = error.message ?: error.javaClass.simpleName
            planStore.save(
                mutablePlan.copy(
                    status = PlanStatus.FAILED,
                    tasks = taskMap.values.toList(),
                    completedAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
            )
            ExecutionResult(
                planId = planId,
                success = false,
                completedTasks = completedCount,
                failedTasks = maxOf(1, failedCount),
                totalDurationMs = System.currentTimeMillis() - startTime,
                finalOutput = null,
                errorSummary = errorSummary
            )
        }
    }

    /**
     * Completes an approval-gated task after ActionExecutor has actually
     * returned success/failure from the user's explicit approval.
     */
    fun resolveApproval(
        planId: String,
        taskId: String,
        actionId: String,
        approved: Boolean,
        executionDetail: String
    ): Boolean {
        // A WorkManager instance may have committed this plan in another process.
        // Refresh before resolving its approval from the foreground ActionExecutor.
        planStore.reload()
        val plan = planStore.get(planId) ?: return false
        val task = plan.tasks.firstOrNull { it.id == taskId } ?: return false
        if (task.status != TaskStatus.AWAITING_APPROVAL) return false
        val pending = restorePendingApproval(task) ?: return false
        if (pending.id != actionId) return false

        val taskArguments = task.toolArguments - "_approvalAction"
        val expired = pending.isExpired()
        val updatedTask = when {
            expired -> task.copy(
                status = TaskStatus.SKIPPED,
                result = null,
                error = "The approval expired before execution. The action was not run.",
                completedAt = System.currentTimeMillis(),
                toolArguments = taskArguments
            )
            approved -> task.copy(
                status = TaskStatus.COMPLETED,
                result = executionDetail.take(MAX_TASK_RESULT_CHARS),
                error = null,
                completedAt = System.currentTimeMillis(),
                toolArguments = taskArguments
            )
            else -> task.copy(
                status = TaskStatus.SKIPPED,
                result = null,
                error = "The user declined the proposed action. No action was executed.",
                completedAt = System.currentTimeMillis(),
                toolArguments = taskArguments
            )
        }
        planStore.save(
            plan.copy(
                status = PlanStatus.ACTIVE,
                tasks = plan.tasks.map { if (it.id == taskId) updatedTask else it },
                completedAt = null,
                updatedAt = System.currentTimeMillis()
            )
        )
        return true
    }

    private fun restorePendingApproval(task: TaskNode): ButlerAction? {
        val encoded = task.toolArguments["_approvalAction"] as? Map<*, *> ?: return null
        return runCatching {
            val payloadRaw = encoded["payload"] as? Map<*, *> ?: emptyMap<Any, Any>()
            val payload = payloadRaw.entries.associate { entry ->
                val key = entry.key as? String
                    ?: throw IllegalArgumentException("Invalid approval payload key.")
                key to (entry.value?.toString()
                    ?: throw IllegalArgumentException("Invalid approval payload value."))
            }
            ButlerAction(
                id = encoded["id"]?.toString() ?: return null,
                kind = ActionKind.valueOf(encoded["kind"]?.toString() ?: return null),
                title = encoded["title"]?.toString() ?: return null,
                summary = encoded["summary"]?.toString() ?: return null,
                risk = ActionRisk.valueOf(encoded["risk"]?.toString() ?: return null),
                payload = payload,
                createdAtEpochMs = (encoded["createdAtEpochMs"] as? Number)?.toLong()
                    ?: return null,
                expiresAtEpochMs = (encoded["expiresAtEpochMs"] as? Number)?.toLong()
                    ?: return null
            )
        }.getOrNull()
    }

    private fun storePendingApproval(task: TaskNode, action: ButlerAction): TaskNode {
        val encoded = mapOf<String, Any>(
            "id" to action.id,
            "kind" to action.kind.name,
            "title" to action.title,
            "summary" to action.summary,
            "risk" to action.risk.name,
            "createdAtEpochMs" to action.createdAtEpochMs,
            "expiresAtEpochMs" to action.expiresAtEpochMs,
            "payload" to action.payload
        )
        return task.copy(
            status = TaskStatus.AWAITING_APPROVAL,
            error = "Waiting for explicit user approval.",
            completedAt = null,
            toolArguments = task.toolArguments + ("_approvalAction" to encoded)
        )
    }

    private suspend fun executeTaskWithRetry(
        task: TaskNode,
        context: ConversationContext,
        plan: Plan,
        onProgress: (Plan, TaskNode, TaskStatus) -> Unit
    ): TaskExecutionResult {
        var currentTask = task.copy(status = TaskStatus.RUNNING)
        var lastError: String? = null

        for (attempt in 0..task.maxRetries.coerceIn(0, MAX_TASK_RETRIES)) {
            if (attempt > 0) {
                currentTask = currentTask.copy(retryCount = attempt)
                delay(RETRY_BACKOFF_BASE_MS * attempt)
            }
            onProgress(plan, currentTask, TaskStatus.RUNNING)
            val outcome = executor.execute(
                currentTask,
                plan.copy(
                    tasks = plan.tasks.map { if (it.id == currentTask.id) currentTask else it }
                ),
                context
            )

            outcome.pendingApproval?.let { action ->
                val awaiting = storePendingApproval(currentTask, action)
                return TaskExecutionResult(
                    updatedTask = awaiting,
                    success = false,
                    pendingApproval = action
                )
            }

            if (outcome.success) {
                return TaskExecutionResult(
                    updatedTask = currentTask.copy(
                        status = TaskStatus.COMPLETED,
                        result = outcome.output?.take(MAX_TASK_RESULT_CHARS),
                        error = null,
                        completedAt = System.currentTimeMillis()
                    ),
                    success = true
                )
            }

            lastError = outcome.error ?: "The task executor did not produce a result."
            if (!outcome.retryable || attempt >= task.maxRetries.coerceIn(0, MAX_TASK_RETRIES)) {
                break
            }
        }

        return TaskExecutionResult(
            updatedTask = currentTask.copy(
                status = TaskStatus.FAILED,
                error = lastError,
                completedAt = System.currentTimeMillis()
            ),
            success = false
        )
    }

    private fun parseTasksFromReasoning(
        trace: List<ReasoningEngine.ReasoningStep>,
        finalAnswer: String
    ): List<TaskNode> {
        val planningText = trace.firstOrNull {
            it.type == ReasoningEngine.StepType.PLANNING
        }?.content.orEmpty()
        val structured = parseStructuredTasks(finalAnswer)
            ?: parseStructuredTasks(planningText)
        if (structured != null) {
            buildDependencyGraph(structured)
            topologicalSort(buildDependencyGraph(structured))
            return structured
        }

        val numberedLines = planningText.lines()
            .map(String::trim)
            .filter { Regex("^\\d+\\.\\s+.+$").matches(it) }
            .map { it.replaceFirst(Regex("^\\d+\\.\\s*"), "").trim() }
            .filter(String::isNotBlank)
            .take(MAX_PLAN_TASKS)

        if (numberedLines.isEmpty()) {
            return listOf(
                TaskNode(
                    title = "Analyze the requested goal",
                    description = finalAnswer.take(1_000).ifBlank {
                        "Analyze and break down the user's requested goal."
                    },
                    type = TaskType.ANALYSIS,
                    checkpoint = true,
                    estimatedDurationMs = estimateDuration(TaskType.ANALYSIS)
                )
            )
        }

        var previousId: String? = null
        val tasks = numberedLines.mapIndexed { index, line ->
            val taskType = inferTaskType(line)
            TaskNode(
                title = line.take(80),
                description = line.take(1_000),
                type = taskType,
                dependencies = previousId?.let(::listOf).orEmpty(),
                checkpoint = index == numberedLines.lastIndex || index % 3 == 0,
                estimatedDurationMs = estimateDuration(taskType)
            ).also { previousId = it.id }
        }
        buildDependencyGraph(tasks)
        topologicalSort(buildDependencyGraph(tasks))
        return tasks
    }

    private fun parseStructuredTasks(source: String): List<TaskNode>? {
        val start = source.indexOf('[')
        val end = source.lastIndexOf(']')
        if (start < 0 || end <= start) return null

        return runCatching {
            val array = JSONArray(source.substring(start, end + 1))
            require(array.length() in 1..MAX_PLAN_TASKS) {
                "The generated plan contains an invalid number of tasks."
            }
            val objects = (0 until array.length()).map { index ->
                array.optJSONObject(index)
                    ?: throw IllegalArgumentException("A generated plan task was not a JSON object.")
            }
            val ids = objects.mapIndexed { index, item ->
                val externalId = item.optString("id").takeIf(String::isNotBlank)
                    ?: "step-" + (index + 1)
                externalId to UUID.randomUUID().toString()
            }.toMap()
            require(ids.size == objects.size) { "The generated plan contains duplicate task IDs." }

            objects.mapIndexed { index, item ->
                val title = item.optString("title").trim()
                val description = item.optString("description").trim()
                require(title.isNotBlank() && description.isNotBlank()) {
                    "A generated plan task is missing its title or description."
                }
                val typeName = item.optString("type").uppercase()
                val type = enumValues<TaskType>().firstOrNull { it.name == typeName }
                    ?: inferTaskType(description)
                val dependencyJson = item.optJSONArray("dependencies") ?: JSONArray()
                val dependencies = buildList {
                    for (dependencyIndex in 0 until dependencyJson.length()) {
                        val dependency = dependencyJson.optString(dependencyIndex).trim()
                        val mapped = ids[dependency]
                            ?: dependency.toIntOrNull()?.let { oneBased ->
                                ids.values.elementAtOrNull(oneBased - 1)
                            }
                        require(mapped != null) {
                            "A generated plan task references an unknown dependency."
                        }
                        add(mapped)
                    }
                }.distinct()
                val tool = item.optString("tool").trim().takeIf(String::isNotBlank)
                val argsJson = item.optJSONObject("toolArguments") ?: JSONObject()
                val args = jsonObjectToArguments(argsJson)
                validateGeneratedTool(type, tool, args)
                TaskNode(
                    id = ids.values.elementAt(index),
                    title = title.take(100),
                    description = description.take(4_000),
                    type = type,
                    dependencies = dependencies,
                    tool = tool,
                    toolArguments = args,
                    checkpoint = item.optBoolean("checkpoint", false),
                    estimatedDurationMs = item.optLong(
                        "estimatedDurationMs",
                        estimateDuration(type)
                    ).coerceIn(1_000L, MAX_TASK_DURATION_MS),
                    maxRetries = item.optInt("maxRetries", 1).coerceIn(0, 2)
                )
            }
        }.getOrNull()
    }

    private fun validateGeneratedTool(
        type: TaskType,
        tool: String?,
        arguments: Map<String, Any>
    ) {
        if (tool == null) return
        val allowed = when (type) {
            TaskType.RESEARCH -> tool in setOf(
                "web_search", "knowledge", "wikipedia", "search_wiki",
                "news_search", "get_news", "news", "headlines",
                "get_weather", "weather", "weather_forecast", "forecast"
            )
            TaskType.EXECUTION -> ProductionTaskExecutor.isSupportedAction(tool)
            else -> true
        }
        require(allowed) {
            "The generated task requested a tool that is not allowlisted: " + tool
        }
        require(arguments.size <= MAX_TASK_ARGUMENTS) {
            "A generated task contains too many tool arguments."
        }
        require(arguments.keys.all { it.isNotBlank() && it.length <= 100 }) {
            "A generated task contains an invalid tool-argument key."
        }
    }

    private fun jsonObjectToArguments(json: JSONObject): Map<String, Any> =
        buildMap {
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = jsonToTaskValue(json.get(key))
                if (value != null) put(key, value)
            }
        }

    private fun jsonToTaskValue(value: Any): Any? = when (value) {
        JSONObject.NULL -> null
        is JSONObject -> jsonObjectToArguments(value)
        is JSONArray -> buildList {
            for (index in 0 until minOf(value.length(), MAX_TASK_ARGUMENTS)) {
                jsonToTaskValue(value.get(index))?.let(::add)
            }
        }
        is String, is Boolean, is Number -> value
        else -> throw IllegalArgumentException("Unsupported generated task argument value.")
    }

    private fun String.containsAny(vararg values: String): Boolean =
        values.any { contains(it, ignoreCase = true) }

    private fun inferTaskType(description: String): TaskType {
        val lower = description.lowercase()
        return when {
            lower.containsAny("research", "search", "find", "gather", "collect") -> TaskType.RESEARCH
            lower.containsAny("analyze", "evaluate", "assess", "review", "synthesize") -> TaskType.ANALYSIS
            lower.containsAny("run", "execute", "build", "test", "deploy", "command") -> TaskType.EXECUTION
            lower.containsAny("write", "create", "generate", "draft", "produce", "code") -> TaskType.CREATION
            lower.containsAny("verify", "check", "validate", "test", "confirm") -> TaskType.VERIFICATION
            lower.containsAny("notify", "inform", "tell", "ask", "communicate") -> TaskType.COMMUNICATION
            lower.containsAny("decide", "choose", "branch", "if", "condition") -> TaskType.DECISION
            else -> TaskType.EXECUTION
        }
    }

    private fun estimateDuration(type: TaskType): Long = when (type) {
        TaskType.RESEARCH -> 15_000
        TaskType.ANALYSIS -> 10_000
        TaskType.EXECUTION -> 20_000
        TaskType.CREATION -> 15_000
        TaskType.VERIFICATION -> 5_000
        TaskType.COMMUNICATION -> 2_000
        TaskType.DECISION -> 1_000
    }

    private fun buildDependencyGraph(tasks: List<TaskNode>): Map<String, Set<String>> {
        val taskIds = tasks.map(TaskNode::id).toSet()
        require(taskIds.size == tasks.size) { "The plan contains duplicate task IDs." }

        val graph = linkedMapOf<String, Set<String>>()
        for (task in tasks) {
            val missing = task.dependencies.filterNot(taskIds::contains)
            require(missing.isEmpty()) {
                "Task '${task.title}' depends on unknown task IDs: ${missing.joinToString()}."
            }
            graph[task.id] = task.dependencies.toSet()
        }
        return graph
    }

    private fun topologicalSort(graph: Map<String, Set<String>>): List<String> {
        val inDegree = mutableMapOf<String, Int>()
        val adjList = mutableMapOf<String, MutableSet<String>>()
        
        for ((node, deps) in graph) {
            inDegree[node] = 0
            adjList[node] = mutableSetOf()
        }
        
        for ((node, deps) in graph) {
            for (dep in deps) {
                adjList[dep]?.add(node)
                inDegree[node] = inDegree.getOrDefault(node, 0) + 1
            }
        }
        
        val queue = java.util.ArrayDeque<String>()
        for ((node, degree) in inDegree) {
            if (degree == 0) queue.add(node)
        }
        
        val result = mutableListOf<String>()
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            result.add(node)
            
            for (neighbor in adjList[node]!!) {
                inDegree[neighbor] = inDegree[neighbor]!! - 1
                if (inDegree[neighbor] == 0) queue.add(neighbor)
            }
        }
        
        require(result.size == graph.size) {
            "The plan dependency graph contains a cycle. No tasks were executed."
        }
        return result
    }

    private fun dependenciesMet(task: TaskNode, taskMap: Map<String, TaskNode>): Boolean {
        return task.dependencies.all { depId ->
            taskMap[depId]?.status == TaskStatus.COMPLETED
        }
    }

    private fun synthesizePlanOutput(tasks: Collection<TaskNode>, goal: String): String {
        val completed = tasks.filter { it.status == TaskStatus.COMPLETED }
        val failed = tasks.filter { it.status == TaskStatus.FAILED }
        
        return """
            Plan Execution Summary: $goal
            
            Completed: ${completed.size}/${tasks.size} tasks
            Failed: ${failed.size}
            
            Results:
            ${completed.map { "✓ ${it.title}: ${it.result?.take(100) ?: "Done"}" }.joinToString("\n")}
            ${if (failed.isNotEmpty()) "\nFailures:\n${failed.map { "✗ ${it.title}: ${it.error}" }.joinToString("\n")}" else ""}
        """.trimIndent()
    }

    private fun serializeContext(context: ConversationContext): String {
        // Simplified - in production, use proper serialization
        return "context:${context.settings.preferredName}:${context.settings.formOfAddress}"
    }

    data class PlanOptions(
        val continueOnFailure: Boolean = false,
        val maxParallelTasks: Int = 1,
        val checkpointInterval: Int = 3
    )

    private companion object {
        const val MAX_TASK_RESULT_CHARS = 12_000
        const val MAX_PLAN_TASKS = 50
        const val MAX_TASK_ARGUMENTS = 50
        const val MAX_TASK_DURATION_MS = 24L * 60 * 60 * 1_000
        const val MAX_TASK_RETRIES = 3
        const val RETRY_BACKOFF_BASE_MS = 500L
    }

    private data class TaskExecutionResult(
        val updatedTask: TaskNode,
        val success: Boolean,
        val pendingApproval: ButlerAction? = null
    )
}

/**
 * Versioned, encrypted-capable persistence contract for plans and checkpoints.
 */
data class PersistedPlanState(
    val current: TaskPlanner.Plan,
    val history: List<TaskPlanner.Plan>
)

interface PlanStorePersistence {
    fun loadAll(): List<PersistedPlanState>
    fun save(state: PersistedPlanState)
    fun delete(planId: String)
}

/** Thread-safe bounded repository with durable checkpoint/version recovery. */
class PlanStore(
    private val persistence: PlanStorePersistence? = null
) {
    private val plans = ConcurrentHashMap<String, TaskPlanner.Plan>()
    private val versions = ConcurrentHashMap<String, MutableList<TaskPlanner.Plan>>()

    init {
        reload()
    }

    /**
     * Refresh committed state written by another Android runtime/WorkManager worker.
     * SQLite is the source of truth; unsaved changes are never present because save()
     * commits the durable state before publishing it to the in-memory maps.
     */
    @Synchronized
    fun reload() {
        val repository = persistence ?: return
        val restored = repository.loadAll().take(MAX_PERSISTED_PLANS)
        plans.clear()
        versions.clear()
        restored.forEach { state ->
            plans[state.current.id] = state.current
            versions[state.current.id] = state.history
                .takeLast(MAX_HISTORY_PER_PLAN)
                .toMutableList()
        }
    }

    /**
     * Persist the new checkpoint before publishing it in memory. An I/O or
     * encryption failure is visible to the caller and cannot look like a save.
     */
    @Synchronized
    fun save(plan: TaskPlanner.Plan) {
        val updated = plan.copy(updatedAt = System.currentTimeMillis())
        val history = (versions[plan.id].orEmpty() + updated)
            .takeLast(MAX_HISTORY_PER_PLAN)
        persistence?.save(PersistedPlanState(updated, history))
        plans[plan.id] = updated
        versions[plan.id] = history.toMutableList()
    }

    @Synchronized
    fun get(id: String): TaskPlanner.Plan? = plans[id]

    @Synchronized
    fun getAll(): List<TaskPlanner.Plan> = plans.values.toList()

    @Synchronized
    fun getHistory(id: String): List<TaskPlanner.Plan> =
        versions[id]?.toList() ?: emptyList()

    @Synchronized
    fun delete(id: String) {
        persistence?.delete(id)
        plans.remove(id)
        versions.remove(id)
    }

    fun close() {
        (persistence as? java.io.Closeable)?.close()
    }

    @Synchronized
    fun getActivePlans(): List<TaskPlanner.Plan> = plans.values
        .filter {
            it.status == TaskPlanner.PlanStatus.ACTIVE ||
                it.status == TaskPlanner.PlanStatus.PAUSED
        }
        .toList()

    private companion object {
        const val MAX_HISTORY_PER_PLAN = 10
        const val MAX_PERSISTED_PLANS = 250
    }
}
