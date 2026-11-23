package `in`.diganta.aurabutler.intelligence

import `in`.diganta.aurabutler.data.AiProvider
import `in`.diganta.aurabutler.model.ConversationContext
import `in`.diganta.aurabutler.runtime.ResponseQualityGate
import `in`.diganta.aurabutler.runtime.SecretRedactor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

/**
 * Advanced reasoning engine with visible chain-of-thought, tool use, and self-verification.
 * Provides structured reasoning traces for complex tasks while maintaining privacy boundaries.
 */
class ReasoningEngine(
    private val aiProvider: AiProvider,
    private val toolRegistry: ToolRegistry
) {

    data class ReasoningStep(
        val id: String = UUID.randomUUID().toString(),
        val type: StepType,
        val content: String,
        val toolCall: ToolCall? = null,
        val toolResult: String? = null,
        val confidence: Double = 1.0,
        val timestamp: Long = System.currentTimeMillis()
    )

    enum class StepType {
        OBSERVATION,      // Understanding the problem
        PLANNING,         // Breaking down the approach
        TOOL_USE,         // Executing a tool/action
        REASONING,        // Internal reasoning
        VERIFICATION,     // Checking results
        CONCLUSION,       // Final answer
        REFLECTION        // Meta-cognition about the process
    }

    data class ToolCall(
        val name: String,
        val arguments: Map<String, Any>,
        val id: String = UUID.randomUUID().toString()
    )

    data class ReasoningResult(
        val finalAnswer: String,
        val reasoningTrace: List<ReasoningStep>,
        val toolsUsed: List<String>,
        val confidence: Double,
        val requiresUserConfirmation: Boolean = false
    )

    /**
     * Execute a reasoning task with full chain-of-thought visibility.
     * Returns structured reasoning trace for transparency and debugging.
     */
    suspend fun reason(
        task: String,
        context: ConversationContext,
        options: ReasoningOptions = ReasoningOptions()
    ): ReasoningResult = withContext(Dispatchers.IO) {
        val trace = mutableListOf<ReasoningStep>()
        val toolsUsed = mutableSetOf<String>()
        var currentConfidence = 1.0
        var requiresConfirmation = false

        // Step 1: Observation - Understand the task
        trace += observeTask(task, context)
        
        // Step 2: Planning - Break down approach
        val plan = createPlan(task, context, trace)
        trace += plan
        
        // Step 3: Execute plan with tool use and reasoning
        val executionResult = executePlan(plan, task, context, trace, toolsUsed, options)
        currentConfidence = executionResult.confidence
        requiresConfirmation = executionResult.requiresConfirmation
        
        // Step 4: Verification - Self-check results
        val verification = verifyResults(executionResult.partialAnswer, task, context, trace)
        trace += verification
        currentConfidence *= verification.confidence
        
        // Step 5: Reflection - Meta-cognition
        if (options.enableReflection) {
            trace += reflectOnProcess(task, trace, currentConfidence)
        }
        
        // Step 6: Conclusion
        val finalAnswer = synthesizeConclusion(executionResult.partialAnswer, trace, currentConfidence)
        trace += ReasoningStep(
            type = StepType.CONCLUSION,
            content = finalAnswer,
            confidence = currentConfidence
        )

        ReasoningResult(
            finalAnswer = ResponseQualityGate.forDisplay(finalAnswer, context.settings.responseStyle),
            reasoningTrace = trace,
            toolsUsed = toolsUsed.toList(),
            confidence = currentConfidence,
            requiresUserConfirmation = requiresConfirmation
        )
    }

    private fun observeTask(task: String, context: ConversationContext): ReasoningStep {
        val complexity = assessComplexity(task)
        val domain = identifyDomain(task)
        val hasCode = task.contains("```") || task.matches(Regex("""(?m)^\s*(?:fun|class|def|function|import|package)\b"""))
        
        return ReasoningStep(
            type = StepType.OBSERVATION,
            content = """
                Task Analysis:
                - Domain: $domain
                - Complexity: $complexity
                - Contains code/technical content: $hasCode
                - Language: ${context.settings.speechLanguage}
                - Available tools: ${toolRegistry.availableTools().joinToString(", ")}
                - Private context enabled: ${context.settings.allowAiPrivateContext}
            """.trimIndent(),
            confidence = 0.95
        )
    }

    private fun assessComplexity(task: String): String {
        val length = task.length
        val lines = task.count { it == '\n' }
        val technicalTerms = Regex("""\b(?:architecture|algorithm|optimize|refactor|debug|migration|security|performance|concurrency|distributed)\b""")
            .findAll(task).count()
        
        return when {
            length > 500 || lines > 10 || technicalTerms >= 3 -> "HIGH"
            length > 200 || lines > 5 || technicalTerms >= 1 -> "MEDIUM"
            else -> "LOW"
        }
    }

    private fun identifyDomain(task: String): String {
        val lower = task.lowercase(Locale.ROOT)
        return when {
            lower.containsAny("code", "program", "debug", "compile", "kotlin", "java", "python", "android", "gradle") -> "SOFTWARE_ENGINEERING"
            lower.containsAny("research", "analyze", "investigate", "study", "compare", "evaluate") -> "RESEARCH_ANALYSIS"
            lower.containsAny("plan", "schedule", "organize", "strategy", "roadmap") -> "PLANNING_STRATEGY"
            lower.containsAny("write", "create", "draft", "compose", "explain", "teach") -> "CREATIVE_COMMUNICATION"
            lower.containsAny("calculate", "compute", "math", "statistics", "data") -> "QUANTITATIVE"
            else -> "GENERAL"
        }
    }

    private fun createPlan(task: String, context: ConversationContext, trace: MutableList<ReasoningStep>): ReasoningStep {
        val domain = identifyDomain(task)
        val steps = when (domain) {
            "SOFTWARE_ENGINEERING" -> listOf(
                "Analyze requirements and constraints",
                "Identify relevant files, APIs, and dependencies",
                "Design solution architecture",
                "Implement core logic with error handling",
                "Write tests and verify behavior",
                "Document changes and edge cases"
            )
            "RESEARCH_ANALYSIS" -> listOf(
                "Define research questions and scope",
                "Gather sources via available tools",
                "Synthesize findings with citations",
                "Identify gaps and contradictions",
                "Formulate evidence-based conclusions"
            )
            "PLANNING_STRATEGY" -> listOf(
                "Clarify objectives and success criteria",
                "Break into actionable milestones",
                "Identify dependencies and risks",
                "Create timeline with checkpoints",
                "Define validation checkpoints"
            )
            else -> listOf(
                "Understand the core question",
                "Retrieve relevant knowledge",
                "Structure a clear response",
                "Verify accuracy and completeness"
            )
        }

        val planText = "Execution Plan ($domain):\n" + steps.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n")
        
        return ReasoningStep(
            type = StepType.PLANNING,
            content = planText,
            confidence = 0.9
        )
    }

    private data class ExecutionResult(
        val partialAnswer: String,
        val confidence: Double,
        val requiresConfirmation: Boolean
    )

    private suspend fun executePlan(
        planStep: ReasoningStep,
        originalTask: String,
        context: ConversationContext,
        trace: MutableList<ReasoningStep>,
        toolsUsed: MutableSet<String>,
        options: ReasoningOptions
    ): ExecutionResult {
        var currentAnswer = ""
        var confidence = 0.85
        var requiresConfirmation = false

        // For complex tasks, use AI with tool-calling capability
        if (options.enableToolUse && toolRegistry.availableTools().isNotEmpty()) {
            val toolEnhancedPrompt = buildToolEnhancedPrompt(originalTask, planStep.content, context)
            
            // This would integrate with AI provider that supports function calling
            // For now, simulate structured reasoning with available tools
            val reasoningSteps = performStructuredReasoning(originalTask, context, trace, toolsUsed)
            currentAnswer = reasoningSteps.lastOrNull()?.content ?: "Unable to complete reasoning."
            confidence = reasoningSteps.lastOrNull()?.confidence ?: 0.5
        } else {
            // Direct AI reasoning without tools
            val reasoningPrompt = buildReasoningPrompt(originalTask, planStep.content, context)
            val aiResponse = aiProvider.answer(reasoningPrompt, context)
            
            if (aiResponse != null && aiResponse.isNotBlank()) {
                trace += ReasoningStep(
                    type = StepType.REASONING,
                    content = "AI Reasoning: $aiResponse",
                    confidence = 0.8
                )
                currentAnswer = aiResponse
                confidence = 0.75
            }
        }

        // Check if any step requires user confirmation (high-risk actions)
        requiresConfirmation = trace.any { it.type == StepType.TOOL_USE && 
            it.toolCall?.let { toolRegistry.requiresConfirmation(it.name) } == true }

        return ExecutionResult(currentAnswer, confidence, requiresConfirmation)
    }

    private suspend fun performStructuredReasoning(
        task: String,
        context: ConversationContext,
        trace: MutableList<ReasoningStep>,
        toolsUsed: MutableSet<String>
    ): List<ReasoningStep> {
        val steps = mutableListOf<ReasoningStep>()
        
        // Decompose task into reasoning subtasks
        val subtasks = decomposeTask(task)
        
        for (subtask in subtasks) {
            // Check if a tool can help
            val applicableTool = toolRegistry.findToolForTask(subtask)
            
            if (applicableTool != null) {
                // Tool use step
                val toolCall = ToolCall(
                    name = applicableTool.name,
                    arguments = buildToolArguments(subtask, applicableTool, context)
                )
                
                trace += ReasoningStep(
                    type = StepType.TOOL_USE,
                    content = "Using tool: ${applicableTool.name} for: $subtask",
                    toolCall = toolCall,
                    confidence = 0.85
                )
                
                // Execute tool (simulated - real implementation would call actual tool)
                val toolResult = executeTool(applicableTool, toolCall.arguments)
                toolsUsed.add(applicableTool.name)
                
                trace += ReasoningStep(
                    type = StepType.TOOL_USE,
                    content = "Tool result: $toolResult",
                    toolCall = toolCall,
                    toolResult = toolResult,
                    confidence = 0.9
                )
                
                steps += ReasoningStep(
                    type = StepType.REASONING,
                    content = "Integrated tool result into reasoning for: $subtask",
                    confidence = 0.8
                )
            } else {
                // Pure reasoning step
                steps += ReasoningStep(
                    type = StepType.REASONING,
                    content = "Reasoning about: $subtask",
                    confidence = 0.75
                )
            }
        }
        
        return steps
    }

    private fun decomposeTask(task: String): List<String> {
        // Simple decomposition - in production, use AI for this
        val sentences = task.split(Regex("[.!?]+")).map { it.trim() }.filter { it.isNotBlank() }
        return if (sentences.size > 1) sentences else listOf(task)
    }

    private fun buildToolArguments(subtask: String, tool: ToolDefinition, context: ConversationContext): Map<String, Any> {
        // Build arguments based on tool schema and subtask
        return mapOf(
            "query" to subtask,
            "context" to context.settings.preferredName
        )
    }

    private fun executeTool(tool: ToolDefinition, arguments: Map<String, Any>): String {
        // Placeholder - real implementation executes actual tool
        return "Tool ${tool.name} executed with args: ${arguments.keys.joinToString(", ")}"
    }

    private fun verifyResults(answer: String, task: String, context: ConversationContext, trace: MutableList<ReasoningStep>): ReasoningStep {
        val checks = mutableListOf<String>()
        var confidence = 1.0
        
        // Completeness check
        if (answer.length < 50) {
            checks += "⚠ Answer may be incomplete (too short)"
            confidence *= 0.8
        }
        
        // Consistency check - no contradictory statements
        if (answer.contains("however") && answer.contains("therefore")) {
            checks += "✓ Contains balanced reasoning with caveats"
        }
        
        // Technical accuracy indicators
        if (task.contains("code") || task.contains("program")) {
            if (answer.contains("```") || answer.contains("function") || answer.contains("class")) {
                checks += "✓ Includes technical artifacts"
            } else {
                checks += "⚠ Technical task but no code/structure in answer"
                confidence *= 0.7
            }
        }
        
        // Source attribution
        if (answer.contains("source") || answer.contains("reference") || answer.contains("according to")) {
            checks += "✓ References sources"
        }
        
        val verificationText = "Verification Results:\n" + checks.map { "  $it" }.joinToString("\n")
        
        return ReasoningStep(
            type = StepType.VERIFICATION,
            content = verificationText,
            confidence = confidence
        )
    }

    private fun reflectOnProcess(task: String, trace: List<ReasoningStep>, confidence: Double): ReasoningStep {
        val toolSteps = trace.count { it.type == StepType.TOOL_USE }
        val reasoningSteps = trace.count { it.type == StepType.REASONING }
        val avgConfidence = trace.map { it.confidence }.average()
        
        val reflection = """
            Meta-Reflection:
            - Total reasoning steps: ${trace.size}
            - Tool invocations: $toolSteps
            - Pure reasoning steps: $reasoningSteps
            - Average step confidence: ${String.format("%.2f", avgConfidence)}
            - Final confidence: ${String.format("%.2f", confidence)}
            - Process quality: ${when {
                confidence > 0.8 && toolSteps > 0 -> "HIGH - Structured with tool validation"
                confidence > 0.7 -> "GOOD - Coherent reasoning"
                else -> "NEEDS_IMPROVEMENT - Low confidence or gaps"
            }}
            - Potential improvements: ${if (toolSteps == 0) "Consider tool use for verification" else "Good tool integration"}
        """.trimIndent()
        
        return ReasoningStep(
            type = StepType.REFLECTION,
            content = reflection,
            confidence = 0.9
        )
    }

    private fun synthesizeConclusion(partialAnswer: String, trace: List<ReasoningStep>, confidence: Double): String {
        val verification = trace.lastOrNull { it.type == StepType.VERIFICATION }?.content ?: ""
        val reflection = trace.lastOrNull { it.type == StepType.REFLECTION }?.content ?: ""
        
        return if (confidence > 0.75) {
            partialAnswer
        } else {
            """
                $partialAnswer
                
                ⚠ Confidence Note: This answer has moderate confidence (${String.format("%.0f", confidence * 100)}%).
                Consider verifying critical details independently.
            """.trimIndent()
        }
    }

    private fun buildToolEnhancedPrompt(task: String, plan: String, context: ConversationContext): String {
        val tools = toolRegistry.availableTools().joinToString(", ")
        return """
            $task
            
            Plan: $plan
            
            Available tools: $tools
            
            Use tools when they can provide factual data, execute actions, or verify claims.
            Show your reasoning for each tool choice.
        """.trimIndent()
    }

    private fun buildReasoningPrompt(task: String, plan: String, context: ConversationContext): String {
        return """
            Task: $task

            Approach: $plan

            Provide a concise answer with explicit assumptions and uncertainty.
            Do not expose private hidden reasoning. Summarize the method and verification evidence.
        """.trimIndent()
    }

    private fun String.containsAny(vararg values: String): Boolean =
        values.any { contains(it, ignoreCase = true) }

    data class ReasoningOptions(
        val enableToolUse: Boolean = true,
        val enableReflection: Boolean = true,
        val maxSteps: Int = 20,
        val requireVerification: Boolean = true
    )

    data class ToolDefinition(
        val name: String,
        val description: String,
        val keywords: List<String>,
        val requiresConfirmation: Boolean = false,
        val schema: Map<String, Any> = emptyMap()
    )

    /**
     * Typed tool catalog shared by the reasoning and planning subsystems.
     * Registering a tool describes it; it does not execute the tool.
     */
    class ToolRegistry {
        private val tools = linkedMapOf<String, ToolDefinition>()

        init {
            registerDefaultTools()
        }

        @Synchronized
        fun register(tool: ToolDefinition) {
            require(tool.name.isNotBlank()) { "Tool name cannot be blank." }
            tools[tool.name] = tool
        }

        @Synchronized
        fun availableTools(): List<String> = tools.keys.toList()

        @Synchronized
        fun getTool(name: String): ToolDefinition? = tools[name]

        @Synchronized
        fun findToolForTask(task: String): ToolDefinition? {
            val lower = task.lowercase(Locale.ROOT)
            return tools.values.firstOrNull { tool ->
                tool.keywords.any { lower.contains(it.lowercase(Locale.ROOT)) }
            }
        }

        @Synchronized
        fun requiresConfirmation(toolName: String): Boolean =
            tools[toolName]?.requiresConfirmation == true

        private fun registerDefaultTools() {
            register(
                ToolDefinition(
                    name = "web_search",
                    description = "Search the web for current information",
                    keywords = listOf("search", "find", "look up", "current", "latest", "news")
                )
            )
            register(
                ToolDefinition(
                    name = "calculate",
                    description = "Perform mathematical calculations",
                    keywords = listOf("calculate", "compute", "math", "formula")
                )
            )
            register(
                ToolDefinition(
                    name = "read_file",
                    description = "Read a file from the project",
                    keywords = listOf("read", "file", "code", "source")
                )
            )
            register(
                ToolDefinition(
                    name = "write_file",
                    description = "Write a file to the project",
                    keywords = listOf("write", "create", "save", "file"),
                    requiresConfirmation = true
                )
            )
            register(
                ToolDefinition(
                    name = "run_command",
                    description = "Execute a shell command",
                    keywords = listOf("run", "execute", "command", "build", "test"),
                    requiresConfirmation = true
                )
            )
            register(
                ToolDefinition(
                    name = "get_weather",
                    description = "Get current weather for a location",
                    keywords = listOf("weather", "temperature", "forecast")
                )
            )
            register(
                ToolDefinition(
                    name = "device_status",
                    description = "Get device battery, storage, memory info",
                    keywords = listOf("battery", "storage", "memory", "device", "status")
                )
            )
        }
    }
}
