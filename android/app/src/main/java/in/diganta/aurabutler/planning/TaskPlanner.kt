package `in`.diganta.aurabutler.planning

import `in`.diganta.aurabutler.intelligence.ReasoningEngine
import `in`.diganta.aurabutler.model.ConversationContext
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
    private val planStore: PlanStore
) {

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
        val errorSummary: String?
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
                
                Output as structured JSON array of tasks.
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
        var completedCount = 0
        var failedCount = 0
        var errorSummary: String? = null
        val startTime = System.currentTimeMillis()

        // Persist the active transition before any task can run.
        planStore.save(mutablePlan)

        try {
            val dependencyGraph = buildDependencyGraph(mutablePlan.tasks)
            val executionOrder = topologicalSort(dependencyGraph)

            for (taskId in executionOrder) {
                currentTaskId = taskId
                val task = taskMap[taskId]
                    ?: throw IllegalStateException("Plan task disappeared: $taskId")

                if (!dependenciesMet(task, taskMap)) {
                    val blockedTask = task.copy(
                        status = TaskStatus.BLOCKED,
                        error = "A dependency did not complete successfully."
                    )
                    taskMap[taskId] = blockedTask
                    planStore.save(
                        mutablePlan.copy(
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

                // Record an in-flight state before entering a potentially long
                // provider/action call. Cancellation will turn this into an
                // explicit CANCELLED task, never a false success or auto-retry.
                val runningTask = task.copy(
                    status = TaskStatus.RUNNING,
                    startedAt = task.startedAt ?: System.currentTimeMillis(),
                    error = null
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

                // Persist every terminal task transition, not just optional
                // checkpoints. The current PlanStore remains process-local, but
                // the latest in-process state is consistent for UI/recovery.
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
                failedCount > 0 -> PlanStatus.FAILED
                allTasks.isNotEmpty() && allTasks.all { it.status == TaskStatus.COMPLETED } ->
                    PlanStatus.COMPLETED
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

    private suspend fun executeTaskWithRetry(
        task: TaskNode,
        context: ConversationContext,
        plan: Plan,
        onProgress: (Plan, TaskNode, TaskStatus) -> Unit
    ): TaskExecutionResult {
        var currentTask = task.copy(status = TaskStatus.RUNNING, startedAt = System.currentTimeMillis())
        var lastError: String? = null

        for (attempt in 0..task.maxRetries) {
            if (attempt > 0) {
                currentTask = currentTask.copy(retryCount = attempt)
            }

            onProgress(plan, currentTask, TaskStatus.RUNNING)

            val result = executeSingleTask(currentTask, context)
            
            if (result.success) {
                return TaskExecutionResult(
                    updatedTask = currentTask.copy(
                        status = TaskStatus.COMPLETED,
                        result = result.output,
                        completedAt = System.currentTimeMillis()
                    ),
                    success = true
                )
            } else {
                lastError = result.error
                // Brief delay before retry
                if (attempt < task.maxRetries) {
                    delay(1_000L * (attempt + 1))
                }
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

    private suspend fun executeSingleTask(task: TaskNode, context: ConversationContext): SingleTaskResult {
        return try {
            when (task.type) {
                TaskType.RESEARCH -> executeResearch(task, context)
                TaskType.ANALYSIS -> executeAnalysis(task, context)
                TaskType.EXECUTION -> executeAction(task, context)
                TaskType.CREATION -> executeCreation(task, context)
                TaskType.VERIFICATION -> executeVerification(task, context)
                TaskType.COMMUNICATION -> executeCommunication(task, context)
                TaskType.DECISION -> executeDecision(task, context)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            SingleTaskResult(success = false, output = null, error = e.message)
        }
    }

    private fun executeResearch(task: TaskNode, context: ConversationContext): SingleTaskResult {
        val tool = task.tool ?: "web_search"
        val toolDef = toolRegistry.getTool(tool)
        
        if (toolDef == null) {
            return SingleTaskResult(false, null, "Tool not found: $tool")
        }

        return SingleTaskResult(
            success = false,
            output = null,
            error = "The '$tool' tool is registered but has no live executor. No research was performed."
        )
    }

    private suspend fun executeAnalysis(task: TaskNode, context: ConversationContext): SingleTaskResult {
        // Use reasoning engine for analysis
        val reasoningResult = reasoningEngine.reason(
            task = "Analyze: ${task.description}\nContext: ${task.toolArguments}",
            context = context,
            options = ReasoningEngine.ReasoningOptions(enableToolUse = false)
        )
        
        val answer = reasoningResult.finalAnswer.trim()
        val credible = answer.isNotBlank() &&
            reasoningResult.confidence >= MIN_ANALYSIS_CONFIDENCE &&
            !answer.startsWith("The configured AI provider did not return an answer.")
        return if (credible) {
            SingleTaskResult(true, answer, null)
        } else {
            SingleTaskResult(
                success = false,
                output = null,
                error = "Analysis was not completed: no sufficiently reliable provider answer was available."
            )
        }
    }

    private fun executeAction(task: TaskNode, context: ConversationContext): SingleTaskResult {
        val tool = task.tool ?: "run_command"
        val toolDef = toolRegistry.getTool(tool)
        
        if (toolDef == null) {
            return SingleTaskResult(false, null, "Tool not found: $tool")
        }

        if (toolDef.requiresConfirmation) {
            return SingleTaskResult(false, null, "Action requires user confirmation: ${task.description}")
        }

        return SingleTaskResult(
            success = false,
            output = null,
            error = "No action executor is connected for '$tool'. No device or external action was performed."
        )
    }

    private fun executeCreation(task: TaskNode, context: ConversationContext): SingleTaskResult {
        return SingleTaskResult(
            success = false,
            output = null,
            error = "No file/content creation executor is connected. Nothing was written."
        )
    }

    private fun executeVerification(task: TaskNode, context: ConversationContext): SingleTaskResult {
        return SingleTaskResult(
            success = false,
            output = null,
            error = "No verification engine is connected. The task has not been verified."
        )
    }

    private fun executeCommunication(task: TaskNode, context: ConversationContext): SingleTaskResult {
        return SingleTaskResult(
            success = false,
            output = null,
            error = "No communication-channel executor is connected. No message was sent."
        )
    }

    private fun executeDecision(task: TaskNode, context: ConversationContext): SingleTaskResult {
        return SingleTaskResult(
            success = false,
            output = null,
            error = "No condition evaluator is connected. No decision branch was executed."
        )
    }

    private fun parseTasksFromReasoning(trace: List<ReasoningEngine.ReasoningStep>, finalAnswer: String): List<TaskNode> {
        // Parse structured tasks from reasoning output
        // In production, use JSON parsing from structured AI output
        val tasks = mutableListOf<TaskNode>()
        
        // Simple fallback: create tasks from plan step
        val planStep = trace.firstOrNull { it.type == ReasoningEngine.StepType.PLANNING }
        if (planStep != null) {
            val lines = planStep.content.lines()
                .map { it.trim() }
                .filter { Regex("^\\d+\\.\\s+.+$").matches(it) }
                .map { it.replaceFirst(Regex("^\\d+\\.\\s*"), "").trim() }
                .filter(String::isNotBlank)
            
            var previousId: String? = null
            for ((index, line) in lines.withIndex()) {
                val taskType = inferTaskType(line)
                val task = TaskNode(
                    title = line.take(60),
                    description = line,
                    type = taskType,
                    dependencies = previousId?.let { listOf(it) } ?: emptyList(),
                    checkpoint = (index == lines.lastIndex) || (index % 3 == 0),
                    estimatedDurationMs = estimateDuration(taskType)
                )
                tasks.add(task)
                previousId = task.id
            }
        }
        
        return if (tasks.isEmpty()) {
            // Default single task
            listOf(TaskNode(
                title = "Execute goal",
                description = finalAnswer.take(200),
                type = TaskType.EXECUTION
            ))
        } else {
            tasks
        }
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
        const val MIN_ANALYSIS_CONFIDENCE = 0.55
    }

    private data class TaskExecutionResult(
        val updatedTask: TaskNode,
        val success: Boolean
    )

    private data class SingleTaskResult(
        val success: Boolean,
        val output: String?,
        val error: String?
    )
}

/**
 * In-memory plan repository with bounded version history.
 * Durable persistence and process-death recovery are not implemented yet.
 */
class PlanStore {
    private val plans = ConcurrentHashMap<String, TaskPlanner.Plan>()
    private val versions = ConcurrentHashMap<String, MutableList<TaskPlanner.Plan>>()

    /**
     * Serializes compound map/history mutations. ConcurrentHashMap protects
     * individual entries, not the mutable version list stored inside an entry.
     */
    @Synchronized
    fun save(plan: TaskPlanner.Plan) {
        val updated = plan.copy(updatedAt = System.currentTimeMillis())
        plans[plan.id] = updated

        val history = versions.getOrPut(plan.id) { mutableListOf() }
        history += updated
        while (history.size > MAX_HISTORY_PER_PLAN) {
            history.removeAt(0)
        }
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
        plans.remove(id)
        versions.remove(id)
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
    }
}
