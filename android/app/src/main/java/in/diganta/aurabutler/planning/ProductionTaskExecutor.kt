package `in`.diganta.aurabutler.planning

import `in`.diganta.aurabutler.data.KeylessLiveInfoProvider
import `in`.diganta.aurabutler.domain.ActionSafetyPolicy
import `in`.diganta.aurabutler.domain.LiveInfoProvider
import `in`.diganta.aurabutler.intelligence.ReasoningEngine
import `in`.diganta.aurabutler.model.ActionKind
import `in`.diganta.aurabutler.model.ActionRisk
import `in`.diganta.aurabutler.model.ButlerAction
import `in`.diganta.aurabutler.model.ConversationContext
import `in`.diganta.aurabutler.runtime.SecretRedactor
import kotlinx.coroutines.CancellationException
import java.util.Locale
import java.util.UUID

/**
 * Concrete task executors: live keyless research, provider-backed analysis and
 * creation, deterministic verification/decision logic, and approval-gated
 * device actions. Communication creates a draft only; nothing is sent.
 */
class ProductionTaskExecutor(
    private val reasoningEngine: ReasoningEngine,
    private val liveInfo: LiveInfoProvider = KeylessLiveInfoProvider()
) {
    data class Outcome(
        val success: Boolean,
        val output: String? = null,
        val error: String? = null,
        val pendingApproval: ButlerAction? = null
    )

    suspend fun execute(
        task: TaskPlanner.TaskNode,
        plan: TaskPlanner.Plan,
        context: ConversationContext
    ): Outcome = try {
        when (task.type) {
            TaskPlanner.TaskType.RESEARCH -> research(task, context)
            TaskPlanner.TaskType.ANALYSIS -> analyze(task, plan, context)
            TaskPlanner.TaskType.EXECUTION -> proposeAction(task, plan)
            TaskPlanner.TaskType.CREATION -> create(task, plan, context)
            TaskPlanner.TaskType.VERIFICATION -> verify(task, plan)
            TaskPlanner.TaskType.COMMUNICATION -> draftCommunication(task, plan, context)
            TaskPlanner.TaskType.DECISION -> decide(task, plan)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Outcome(false, error = (error.message ?: error.javaClass.simpleName).take(MAX_ERROR_CHARS))
    }

    private suspend fun research(task: TaskPlanner.TaskNode, context: ConversationContext): Outcome {
        val tool = task.tool?.lowercase(Locale.ROOT)?.trim()
        val query = argument(task, "query").ifBlank { argument(task, "topic") }
            .ifBlank { task.description }.take(MAX_QUERY_CHARS)
        val result = when (tool) {
            null, "", "web_search", "knowledge", "wikipedia", "search_wiki" ->
                liveInfo.knowledge(query)
            "get_news", "news", "news_search", "headlines" ->
                liveInfo.headlinesFor(
                    limit = argument(task, "limit").toIntOrNull()?.coerceIn(1, 10) ?: 5,
                    topic = argument(task, "topic").takeIf(String::isNotBlank)
                ).takeIf { it.isNotEmpty() }?.joinToString("\n") { "• $it" }
            "get_weather", "weather" ->
                liveInfo.weatherFor(context.settings, argument(task, "location").takeIf(String::isNotBlank))
            "weather_forecast", "forecast" ->
                liveInfo.weatherForecast(
                    context.settings,
                    argument(task, "location").takeIf(String::isNotBlank),
                    argument(task, "days").toIntOrNull()?.coerceIn(3, 16) ?: 7
                )
            else -> return Outcome(false, error = "Unsupported live research tool: " + tool)
        }
        val clean = result?.trim().orEmpty()
        return if (clean.isBlank()) {
            Outcome(false, error = "The live research provider returned no usable result for: " + query.take(120))
        } else {
            Outcome(true, output = "Research result:\n" + clean +
                "\n\nSource provider: AURA keyless information provider.")
        }
    }

    private suspend fun analyze(
        task: TaskPlanner.TaskNode,
        plan: TaskPlanner.Plan,
        context: ConversationContext
    ): Outcome {
        val prompt = buildString {
            append("Analyze the task using only the supplied evidence. Separate facts from inference, ")
            append("state uncertainty, and do not invent sources.\n\n")
            append("Task: ").append(task.description.take(MAX_TASK_CHARS))
            val dependencies = dependencyOutputs(task, plan)
            if (dependencies.isNotEmpty()) append("\n\nEvidence:\n").append(dependencies.joinToString("\n\n"))
            if (task.toolArguments.isNotEmpty()) {
                append("\n\nConstraints:\n")
                append(task.toolArguments.entries.joinToString("\n") { it.key + ": " + it.value.toString().take(300) })
            }
        }
        return reasoningOutcome(prompt, context, "Analysis")
    }

    private fun proposeAction(task: TaskPlanner.TaskNode, plan: TaskPlanner.Plan): Outcome {
        val tool = (task.tool ?: argument(task, "action")).lowercase(Locale.ROOT).trim()
        val kind = ACTION_TO_KIND[tool] ?: return Outcome(
            false,
            error = "Unsupported or unsafe operation '" + tool + "'. No action was performed."
        )
        val payload = task.toolArguments
            .filterKeys { it !in RESERVED_ARGUMENTS }
            .mapValues { (_, value) -> value.toString().take(MAX_ACTION_VALUE_CHARS) }
            .filterValues(String::isNotBlank)
            .toMutableMap()
        if (payload.values.any { SecretRedactor.containsLikelySecret(it) }) {
            return Outcome(false, error = "AURA blocked an action proposal containing a likely credential or secret.")
        }
        payload[PLAN_ID_ARGUMENT] = plan.id
        payload[TASK_ID_ARGUMENT] = task.id
        val action = ButlerAction(
            id = UUID.randomUUID().toString(),
            kind = kind,
            title = task.title.take(100).ifBlank { "Approve planned action" },
            summary = task.description.take(MAX_TASK_CHARS),
            risk = if (kind in HIGH_RISK_ACTIONS) ActionRisk.HIGH else ActionRisk.MEDIUM,
            payload = payload
        )
        val safety = ActionSafetyPolicy.check(action)
        if (!safety.allowed) return Outcome(false, error = "AURA's action policy rejected this proposal: " + safety.reason)
        return Outcome(false, error = "Waiting for your explicit approval.", pendingApproval = action)
    }

    private suspend fun create(
        task: TaskPlanner.TaskNode,
        plan: TaskPlanner.Plan,
        context: ConversationContext
    ): Outcome {
        val prompt = buildString {
            append("Create the requested artifact as complete, directly usable content. ")
            append("Do not claim a file was saved or an external action was performed. ")
            append("Return the actual content, with a suitable title and formatting.\n\n")
            append("Goal: ").append(plan.description.take(MAX_TASK_CHARS))
            append("\nTask: ").append(task.description.take(MAX_TASK_CHARS))
            task.toolArguments["format"]?.let { append("\nFormat: ").append(it.toString().take(80)) }
            task.toolArguments["requirements"]?.let { append("\nRequirements: ").append(it.toString().take(1_000)) }
        }
        return reasoningOutcome(prompt, context, "Creation")
    }

    private suspend fun draftCommunication(
        task: TaskPlanner.TaskNode,
        plan: TaskPlanner.Plan,
        context: ConversationContext
    ): Outcome {
        val prompt = buildString {
            append("Prepare a complete communication draft for the user to review. ")
            append("Do not send, schedule, publish, or contact anyone. Identify missing recipient data.\n\n")
            append("Context: ").append(plan.description.take(MAX_TASK_CHARS))
            append("\nTask: ").append(task.description.take(MAX_TASK_CHARS))
            task.toolArguments.forEach { (key, value) ->
                append("\n").append(key).append(": ").append(value.toString().take(500))
            }
        }
        val result = reasoningOutcome(prompt, context, "Communication draft")
        return if (result.success) result.copy(output = "Draft only — not sent:\n\n" + result.output) else result
    }

    private fun verify(task: TaskPlanner.TaskNode, plan: TaskPlanner.Plan): Outcome {
        val source = argument(task, "value").ifBlank { dependencyOutputs(task, plan).joinToString("\n\n") }
        val expected = argument(task, "expected")
        val check = argument(task, "check").ifBlank { "non_empty" }.lowercase(Locale.ROOT)
        val passed = when (check) {
            "non_empty" -> source.isNotBlank()
            "contains" -> expected.isNotBlank() && source.contains(expected, ignoreCase = true)
            "equals" -> expected.isNotBlank() && source.trim().equals(expected.trim(), ignoreCase = true)
            "min_length" -> source.length >= (argument(task, "minLength").toIntOrNull() ?: 1)
                .coerceIn(1, MAX_VERIFICATION_CHARS)
            "dependency_count" -> task.dependencies.isNotEmpty() && task.dependencies.all { id ->
                plan.tasks.any { it.id == id && it.status == TaskPlanner.TaskStatus.COMPLETED }
            }
            else -> return Outcome(false, error = "Unsupported verification rule: " + check)
        }
        return if (passed) Outcome(true, output = "Verification passed: " + check)
        else Outcome(false, error = "Verification failed: rule '" + check + "' was not satisfied.")
    }

    private fun decide(task: TaskPlanner.TaskNode, plan: TaskPlanner.Plan): Outcome {
        val operator = argument(task, "operator").ifBlank { "non_empty" }.lowercase(Locale.ROOT)
        val left = argument(task, "left").ifBlank { dependencyOutputs(task, plan).joinToString("\n") }
        val right = argument(task, "right")
        val value = when (operator) {
            "equals" -> left.equals(right, ignoreCase = true)
            "not_equals" -> !left.equals(right, ignoreCase = true)
            "contains" -> right.isNotBlank() && left.contains(right, ignoreCase = true)
            "starts_with" -> right.isNotBlank() && left.startsWith(right, ignoreCase = true)
            "is_empty" -> left.isBlank()
            "non_empty" -> left.isNotBlank()
            "gt", "gte", "lt", "lte" -> {
                val a = left.toDoubleOrNull() ?: return Outcome(false, error = "Invalid left numeric operand.")
                val b = right.toDoubleOrNull() ?: return Outcome(false, error = "Invalid right numeric operand.")
                when (operator) {
                    "gt" -> a > b
                    "gte" -> a >= b
                    "lt" -> a < b
                    else -> a <= b
                }
            }
            else -> return Outcome(false, error = "Unsupported decision operator: " + operator)
        }
        return Outcome(true, output = if (value) "DECISION_TRUE" else "DECISION_FALSE")
    }

    private suspend fun reasoningOutcome(prompt: String, context: ConversationContext, label: String): Outcome {
        val result = reasoningEngine.reason(
            task = prompt,
            context = context,
            options = ReasoningEngine.ReasoningOptions(
                enableToolUse = false,
                enableReflection = true,
                requireVerification = true
            )
        )
        val answer = result.finalAnswer.trim()
        if (
            answer.isBlank() ||
            result.confidence < MIN_ACCEPTED_CONFIDENCE ||
            answer.startsWith("The configured AI provider did not return an answer.")
        ) {
            return Outcome(
                false,
                error = label + " was not generated because the configured provider did not return a sufficiently reliable result."
            )
        }
        return Outcome(true, output = answer.take(MAX_OUTPUT_CHARS))
    }

    private fun argument(task: TaskPlanner.TaskNode, name: String): String =
        task.toolArguments[name]?.toString()?.trim().orEmpty()

    private fun dependencyOutputs(task: TaskPlanner.TaskNode, plan: TaskPlanner.Plan): List<String> =
        task.dependencies.mapNotNull { id ->
            plan.tasks.firstOrNull { it.id == id }
                ?.takeIf { it.status == TaskPlanner.TaskStatus.COMPLETED }
                ?.result?.takeIf(String::isNotBlank)
        }

    companion object {
        const val PLAN_ID_ARGUMENT = "__aura_plan_id"
        const val TASK_ID_ARGUMENT = "__aura_task_id"

        fun isSupportedAction(tool: String): Boolean =
            tool.lowercase(Locale.ROOT).trim() in ACTION_TO_KIND

        private const val MAX_TASK_CHARS = 4_000
        private const val MAX_QUERY_CHARS = 500
        private const val MAX_OUTPUT_CHARS = 12_000
        private const val MAX_ERROR_CHARS = 500
        private const val MAX_ACTION_VALUE_CHARS = 2_000
        private const val MAX_VERIFICATION_CHARS = 20_000
        private const val MIN_ACCEPTED_CONFIDENCE = 0.55
        private val RESERVED_ARGUMENTS = setOf(PLAN_ID_ARGUMENT, TASK_ID_ARGUMENT, "_approvalAction")
        private val ACTION_TO_KIND = mapOf(
            "open_url" to ActionKind.OPEN_URL,
            "open_app" to ActionKind.OPEN_APP,
            "open_setting" to ActionKind.OPEN_SYSTEM_SETTING,
            "dial_phone" to ActionKind.DIAL_PHONE,
            "map_route" to ActionKind.MAP_ROUTE,
            "set_alarm" to ActionKind.SET_ALARM,
            "set_timer" to ActionKind.SET_TIMER,
            "draft_message" to ActionKind.DRAFT_MESSAGE,
            "compose_email" to ActionKind.COMPOSE_EMAIL,
            "share_text" to ActionKind.SHARE_TEXT,
            "set_volume" to ActionKind.SYSTEM_VOLUME,
            "set_brightness" to ActionKind.SYSTEM_BRIGHTNESS,
            "flashlight" to ActionKind.FLASHLIGHT,
            "open_camera" to ActionKind.OPEN_CAMERA,
            "set_reminder" to ActionKind.REMINDER,
            "clear_aura_cache" to ActionKind.CLEAR_AURA_CACHE,
            "cooling_assist" to ActionKind.COOLING_ASSIST
        )
        private val HIGH_RISK_ACTIONS = setOf(
            ActionKind.DIAL_PHONE, ActionKind.DRAFT_MESSAGE, ActionKind.COMPOSE_EMAIL,
            ActionKind.CALENDAR_EVENT, ActionKind.REMINDER, ActionKind.SYSTEM_BRIGHTNESS,
            ActionKind.SYSTEM_VOLUME, ActionKind.FLASHLIGHT, ActionKind.CLEAR_AURA_CACHE,
            ActionKind.COOLING_ASSIST
        )
    }
}
