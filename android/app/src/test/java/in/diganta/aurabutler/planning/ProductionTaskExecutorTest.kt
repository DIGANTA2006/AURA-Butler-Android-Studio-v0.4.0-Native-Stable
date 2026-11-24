package `in`.diganta.aurabutler.planning

import `in`.diganta.aurabutler.data.HybridAiProvider
import `in`.diganta.aurabutler.intelligence.ReasoningEngine
import `in`.diganta.aurabutler.model.ButlerSettings
import `in`.diganta.aurabutler.model.ConversationContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductionTaskExecutorTest {
    private val executor = ProductionTaskExecutor(
        reasoningEngine = ReasoningEngine(
            aiProvider = HybridAiProvider(),
            toolRegistry = ReasoningEngine.ToolRegistry()
        )
    )

    private val context = ConversationContext(
        recentMessages = emptyList(),
        memories = emptyList(),
        settings = ButlerSettings()
    )

    @Test
    fun deterministicNumericDecisionReturnsActualBooleanResult() = runBlocking {
        val task = TaskPlanner.TaskNode(
            id = "decision-1",
            title = "Compare values",
            description = "Check whether 42 is greater than 7",
            type = TaskPlanner.TaskType.DECISION,
            toolArguments = mapOf(
                "operator" to "gt",
                "left" to "42",
                "right" to "7"
            )
        )
        val plan = planWith(task)

        val result = executor.execute(task, plan, context)

        assertTrue(result.success)
        assertEquals("DECISION_TRUE", result.output)
        assertNull(result.pendingApproval)
    }

    @Test
    fun verificationChecksDependencyOutputInsteadOfReportingUnconditionalSuccess() = runBlocking {
        val completed = TaskPlanner.TaskNode(
            id = "source",
            title = "Source",
            description = "Output the release identifier",
            type = TaskPlanner.TaskType.CREATION,
            status = TaskPlanner.TaskStatus.COMPLETED,
            result = "release-2026.11"
        )
        val verify = TaskPlanner.TaskNode(
            id = "verify",
            title = "Verify release",
            description = "Ensure the release identifier is present",
            type = TaskPlanner.TaskType.VERIFICATION,
            dependencies = listOf("source"),
            toolArguments = mapOf(
                "check" to "contains",
                "expected" to "2026.11"
            )
        )
        val plan = planWith(completed, verify)

        val passed = executor.execute(verify, plan, context)
        assertTrue(passed.success)
        assertEquals("Verification passed: contains", passed.output)

        val failedTask = verify.copy(
            toolArguments = mapOf(
                "check" to "contains",
                "expected" to "2027.01"
            )
        )
        val failed = executor.execute(failedTask, plan, context)
        assertFalse(failed.success)
        assertTrue(failed.error.orEmpty().contains("Verification failed"))
    }

    @Test
    fun deviceActionsBecomeSafetyCheckedApprovalProposals() = runBlocking {
        val task = TaskPlanner.TaskNode(
            id = "open-website",
            title = "Open project website",
            description = "Open the secure project website",
            type = TaskPlanner.TaskType.EXECUTION,
            tool = "open_url",
            toolArguments = mapOf("url" to "https://example.com")
        )
        val result = executor.execute(task, planWith(task), context)

        assertFalse(result.success)
        assertNotNull(result.pendingApproval)
        assertEquals(task.id, result.pendingApproval?.payload?.get(
            ProductionTaskExecutor.TASK_ID_ARGUMENT
        ))
    }

    @Test
    fun arbitraryShellCommandIsRejectedAndNeverConvertedToAnApproval() = runBlocking {
        val task = TaskPlanner.TaskNode(
            id = "unsafe",
            title = "Run command",
            description = "Execute arbitrary shell text",
            type = TaskPlanner.TaskType.EXECUTION,
            tool = "run_command",
            toolArguments = mapOf("command" to "rm -rf /")
        )
        val result = executor.execute(task, planWith(task), context)

        assertFalse(result.success)
        assertNull(result.pendingApproval)
        assertTrue(result.error.orEmpty().contains("Unsupported or unsafe"))
    }

    private fun planWith(vararg tasks: TaskPlanner.TaskNode) = TaskPlanner.Plan(
        id = "test-plan",
        title = "Executor test",
        description = "Validate task executor safety and deterministic logic",
        tasks = tasks.toList()
    )
}
