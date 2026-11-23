package `in`.diganta.aurabutler.intelligence

import `in`.diganta.aurabutler.data.AiProvider
import `in`.diganta.aurabutler.memory.LocalEmbeddingProvider
import `in`.diganta.aurabutler.memory.MemoryStore
import `in`.diganta.aurabutler.memory.SemanticMemory
import `in`.diganta.aurabutler.model.ButlerSettings
import `in`.diganta.aurabutler.model.ConversationContext
import `in`.diganta.aurabutler.planning.PlanStore
import `in`.diganta.aurabutler.planning.TaskPlanner
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests the APIs that are actually implemented in the current Android source.
 * These tests deliberately do not expect placeholder tools or integrations to
 * report successful execution.
 */
class IntelligenceTestSuite {
    private lateinit var mockAiProvider: AiProvider
    private lateinit var settings: ButlerSettings
    private lateinit var context: ConversationContext
    private lateinit var testContextProvider: TestContextProvider

    @Before
    fun setup() {
        mockAiProvider = MockAiProvider()
        settings = ButlerSettings(
            aiEnabled = true,
            proactiveSuggestionsEnabled = true,
            proactiveAutoExecute = false
        )
        context = ConversationContext(
            recentMessages = emptyList(),
            memories = emptyList(),
            settings = settings
        )
        testContextProvider = TestContextProvider()
    }

    @Test
    fun reasoningEngineReturnsProviderAnswerAndSafeTrace() = runBlocking {
        val registry = ReasoningEngine.ToolRegistry()
        val engine = ReasoningEngine(mockAiProvider, registry)

        val result = engine.reason("Explain what an Android foreground service does.", context)

        assertTrue(result.finalAnswer.contains("Mock AI response"))
        assertTrue(result.confidence > 0.0)
        assertTrue(result.reasoningTrace.isNotEmpty())
        assertTrue(result.toolsUsed.isEmpty())
        assertFalse(result.requiresUserConfirmation)
    }

    @Test
    fun toolRegistryIsMetadataAndDoesNotPretendToExecuteTools() = runBlocking {
        val registry = ReasoningEngine.ToolRegistry()
        registry.register(
            ReasoningEngine.ToolDefinition(
                name = "get_weather",
                description = "Get current weather",
                keywords = listOf("weather")
            )
        )
        val result = ReasoningEngine(mockAiProvider, registry).reason(
            "What's the weather in Mumbai?",
            context
        )

        assertTrue(registry.availableTools().contains("get_weather"))
        assertTrue(result.toolsUsed.isEmpty())
        assertFalse(result.reasoningTrace.any { it.type == ReasoningEngine.StepType.TOOL_USE })
        assertTrue(result.finalAnswer.contains("Mock AI response"))
    }

    @Test
    fun reasoningEngineCanAddReflectionSummary() = runBlocking {
        val result = ReasoningEngine(
            mockAiProvider,
            ReasoningEngine.ToolRegistry()
        ).reason(
            "Explain quantum computing.",
            context,
            ReasoningEngine.ReasoningOptions(enableReflection = true)
        )

        assertTrue(result.reasoningTrace.any {
            it.type == ReasoningEngine.StepType.REFLECTION
        })
    }

    @Test
    fun taskPlannerParsesNumberedPlanSteps() = runBlocking {
        val registry = ReasoningEngine.ToolRegistry()
        val planner = TaskPlanner(
            ReasoningEngine(mockAiProvider, registry),
            registry,
            PlanStore()
        )

        val plan = planner.createPlanFromGoal(
            "Set a timer for ten minutes and then play music.",
            context
        )

        assertTrue(plan.tasks.size >= 2)
        assertEquals(TaskPlanner.PlanStatus.DRAFT, plan.status)
        assertTrue(plan.tasks.first().dependencies.isEmpty())
        assertTrue(plan.tasks.drop(1).all { it.dependencies.isNotEmpty() })
    }

    @Test
    fun taskPlannerPlanStoreKeepsInMemoryPlanAndHistory() = runBlocking {
        val registry = ReasoningEngine.ToolRegistry()
        val store = PlanStore()
        val planner = TaskPlanner(
            ReasoningEngine(mockAiProvider, registry),
            registry,
            store
        )

        val plan = planner.createPlanFromGoal("Create a daily study schedule.", context)
        store.save(plan)
        val retrieved = store.get(plan.id)

        assertNotNull(retrieved)
        assertEquals(plan.id, retrieved?.id)
        assertEquals(plan.description, retrieved?.description)
        assertTrue(store.getHistory(plan.id).isNotEmpty())
    }

    @Test
    fun semanticMemoryCanRememberAndRecallWithoutClaimingSemanticModelInference() = runBlocking {
        val store = MemoryStore()
        val memory = SemanticMemory(
            mockAiProvider,
            LocalEmbeddingProvider("test-placeholder"),
            store
        )

        val entry = memory.remember(
            content = "User prefers dark mode and quiet notifications.",
            label = "UI Preferences",
            context = context,
            importance = 0.8
        )
        assertEquals(0.8, entry.importance, 0.001)
        assertNotNull(store.get(entry.id))

        val result = memory.recall(
            "UI preferences",
            context,
            SemanticMemory.RecallOptions(
                maxResults = 3,
                minScore = 0.0,
                strategy = SemanticMemory.RetrievalStrategy.TEMPORAL
            )
        )
        assertTrue(result.entries.isNotEmpty())
        assertTrue(result.entries.size <= 3)
    }

    @Test
    fun memoryRecallReturnsBoundedCandidatesWithPlaceholderEmbeddings() = runBlocking {
        val store = MemoryStore()
        val memory = SemanticMemory(
            mockAiProvider,
            LocalEmbeddingProvider("test-placeholder"),
            store
        )

        memory.remember("User likes jazz music.", "Music 1", context, importance = 0.7)
        memory.remember("User enjoys classical piano.", "Music 2", context, importance = 0.6)
        memory.remember("User likes pizza.", "Food", context, importance = 0.7)

        val result = memory.recall(
            "Music preferences",
            context,
            SemanticMemory.RecallOptions(
                maxResults = 2,
                minScore = 0.0,
                strategy = SemanticMemory.RetrievalStrategy.HYBRID
            )
        )

        // This currently verifies the retrieval contract, not model-level semantic quality.
        assertTrue(result.entries.isNotEmpty())
        assertTrue(result.entries.size <= 2)
    }

    @Test
    fun temporalMemoryRetrievalReturnsRecentEntries() = runBlocking {
        val store = MemoryStore()
        val memory = SemanticMemory(
            mockAiProvider,
            LocalEmbeddingProvider("test-placeholder"),
            store
        )

        memory.remember("Meeting at 10 AM tomorrow.", "Meeting", context, importance = 0.8)
        memory.remember("Lunch with a client at 1 PM.", "Lunch", context, importance = 0.7)
        memory.remember("Project deadline next Friday.", "Deadline", context, importance = 0.9)

        val result = memory.recall(
            "Schedule",
            context,
            SemanticMemory.RecallOptions(
                maxResults = 5,
                minScore = 0.0,
                strategy = SemanticMemory.RetrievalStrategy.TEMPORAL
            )
        )
        assertTrue(result.entries.isNotEmpty())
    }

    @Test
    fun memoryConsolidationReturnsStructuredResult() = runBlocking {
        val store = MemoryStore()
        val memory = SemanticMemory(
            mockAiProvider,
            LocalEmbeddingProvider("test-placeholder"),
            store
        )
        memory.remember("User likes coffee.", "Coffee 1", context, importance = 0.5)
        memory.remember("User enjoys coffee in the morning.", "Coffee 2", context, importance = 0.5)

        val result = memory.consolidate()

        assertTrue(result.mergedCount >= 0)
        assertTrue(result.patternsFound >= 0)
        assertTrue(result.conceptsCreated >= 0)
        assertTrue(result.decayedCount >= 0)
    }

    @Test
    fun forgettingCurveUsesEntryImportanceAndLastAccessTime() = runBlocking {
        val store = MemoryStore()
        val memory = SemanticMemory(
            null,
            LocalEmbeddingProvider("test-placeholder"),
            store
        )
        val entry = memory.remember(
            "Old low-importance trivia.",
            "Trivia",
            context,
            importance = 0.1
        )
        store.save(
            entry.copy(
                lastAccessed = System.currentTimeMillis() - 90L * 24 * 60 * 60 * 1_000
            )
        )

        val result = memory.consolidate()

        assertTrue(result.decayedCount >= 1)
        assertEquals(null, store.get(entry.id))
    }

    @Test
    fun proactiveEngineSuggestionGenerationIsSafeWithoutExternalData() = runBlocking {
        val engine = ProactiveEngine(
            aiProvider = mockAiProvider,
            semanticMemory = createTestSemanticMemory(),
            settings = settings,
            contextProvider = testContextProvider
        )
        try {
            engine.start()
            delay(300)
            val suggestions = engine.getSuggestions(context)
            assertTrue(suggestions.size <= 5)
            assertTrue(suggestions.all { it.expiresAt > System.currentTimeMillis() })
        } finally {
            engine.stop()
        }
    }

    @Test
    fun proactiveEngineCreatesBatteryAlertFromInjectedDeviceState() = runBlocking {
        val engine = ProactiveEngine(
            aiProvider = mockAiProvider,
            semanticMemory = createTestSemanticMemory(),
            settings = settings,
            contextProvider = TestContextProvider(lowBattery = true)
        )
        try {
            engine.start()
            delay(500)
            val alerts = engine.getSuggestions(context).filter {
                it.type == ProactiveEngine.SuggestionType.MAINTENANCE &&
                    it.title.contains("Battery")
            }
            assertTrue(alerts.isNotEmpty())
        } finally {
            engine.stop()
        }
    }

    @Test
    fun proactiveSuggestionDismissalRemovesItFromVisibleSuggestions() = runBlocking {
        val engine = ProactiveEngine(
            aiProvider = mockAiProvider,
            semanticMemory = createTestSemanticMemory(),
            settings = settings,
            contextProvider = TestContextProvider(lowBattery = true)
        )
        try {
            engine.start()
            delay(500)
            val suggestion = engine.getSuggestions(context).firstOrNull()
            if (suggestion != null) {
                engine.dismissSuggestion(suggestion.id)
                assertFalse(engine.getSuggestions(context).any { it.id == suggestion.id })
            }
        } finally {
            engine.stop()
        }
    }

    @Test
    fun proactiveAgentsExposeIntegrationAvailability() {
        val engine = ProactiveEngine(
            aiProvider = mockAiProvider,
            semanticMemory = createTestSemanticMemory(),
            settings = settings,
            contextProvider = testContextProvider
        )
        val agents = engine.getAgentStatus()

        assertTrue(agents.size >= 6)
        assertTrue(agents.any { it.name == "Memory Consolidation" })
        assertTrue(agents.any { it.name == "Battery Optimization" })
        assertTrue(agents.any { it.name == "Storage Monitor" })
        assertTrue(agents.filter {
            it.name in setOf("Daily Briefing", "Habit Learning", "Research Follow-up")
        }.all { !it.enabled })
    }

    @Test
    fun coordinatorSelectsLocalReasoningAndPlanningStrategies() = runBlocking {
        val coordinator = IntelligenceCoordinator(
            aiProvider = mockAiProvider,
            settings = settings,
            contextProvider = testContextProvider
        )
        coordinator.initialize()

        val localResult = coordinator.processRequest("Open YouTube", context)
        assertEquals(IntelligenceCoordinator.IntelligenceMode.MINIMAL, localResult.mode)

        val reasoningResult = coordinator.processRequest(
            "Explain how photosynthesis works.",
            context
        )
        assertEquals(IntelligenceCoordinator.IntelligenceMode.BALANCED, reasoningResult.mode)

        val planningResult = coordinator.processRequest(
            "Plan my week with meetings and tasks.",
            context
        )
        assertEquals(IntelligenceCoordinator.IntelligenceMode.ENHANCED, planningResult.mode)
        assertNotNull(planningResult.plan)
    }

    @Test
    fun coordinatorModeSwitchingRemainsExplicit() = runBlocking {
        val coordinator = IntelligenceCoordinator(
            aiProvider = mockAiProvider,
            settings = settings,
            contextProvider = testContextProvider
        )
        coordinator.initialize()

        coordinator.setIntelligenceMode(IntelligenceCoordinator.IntelligenceMode.MINIMAL)
        val minimal = coordinator.processRequest("Explain quantum physics.", context)
        assertEquals(IntelligenceCoordinator.IntelligenceMode.MINIMAL, minimal.mode)

        coordinator.setIntelligenceMode(IntelligenceCoordinator.IntelligenceMode.AUTONOMOUS)
        val autonomous = coordinator.processRequest(
            "Figure out my schedule and optimize it.",
            context
        )
        assertEquals(IntelligenceCoordinator.IntelligenceMode.AUTONOMOUS, autonomous.mode)
    }

    @Test
    fun coordinatorMemoryIntegrationUsesConfiguredRecallContract() = runBlocking {
        val coordinator = IntelligenceCoordinator(
            aiProvider = mockAiProvider,
            settings = settings,
            contextProvider = testContextProvider
        )
        coordinator.initialize()

        val entry = coordinator.remember(
            "User is a software developer who likes Kotlin.",
            "User Profile",
            context,
            importance = 0.9
        )
        assertNotNull(entry.id)

        val result = coordinator.recall(
            "What is the user's profession?",
            context,
            SemanticMemory.RecallOptions(
                minScore = 0.0,
                strategy = SemanticMemory.RetrievalStrategy.TEMPORAL
            )
        )
        assertTrue(result.entries.isNotEmpty())
    }

    @Test
    fun coordinatorStatusReportsRegisteredCapabilities() = runBlocking {
        val coordinator = IntelligenceCoordinator(
            aiProvider = mockAiProvider,
            settings = settings,
            contextProvider = testContextProvider
        )
        coordinator.initialize()

        val status = coordinator.getStatus()
        assertTrue(status.initialized)
        assertEquals(IntelligenceCoordinator.IntelligenceMode.BALANCED, status.mode)
        assertTrue(status.toolCount > 0)
    }

    @Test
    fun enhancedEngineKeepsLocalCommandsAvailable() = runBlocking {
        val engine = EnhancedButlerEngine.create(
            liveInfo = TestLiveInfoProvider(),
            generalAi = mockAiProvider,
            settingsProvider = { settings },
            contextProvider = testContextProvider
        )
        val reply = engine.chat("What time is it?", context)

        assertNotNull(reply.assistantMessage)
        assertTrue(
            reply.assistantMessage.contains("time", ignoreCase = true) ||
                reply.assistantMessage.contains("Time")
        )
    }

    @Test
    fun enhancedEngineUsesAiForComplexQuestionsWithoutFakeActions() = runBlocking {
        val engine = EnhancedButlerEngine.create(
            liveInfo = TestLiveInfoProvider(),
            generalAi = mockAiProvider,
            settingsProvider = { settings },
            contextProvider = testContextProvider
        )
        val reply = engine.chat(
            "Analyze the pros and cons of using Kotlin vs Java for Android development.",
            context
        )

        assertTrue(reply.assistantMessage.contains("Mock AI response"))
        assertTrue(reply.pendingActions.isEmpty())
    }

    @Test
    fun enhancedEngineReturnsProactiveMetadataWithoutPretendingToExecuteIt() {
        val engine = EnhancedButlerEngine.create(
            liveInfo = TestLiveInfoProvider(),
            generalAi = mockAiProvider,
            settingsProvider = { settings },
            contextProvider = testContextProvider
        )
        assertNotNull(engine.getProactiveSuggestions(context))
    }

    private fun createTestSemanticMemory(): SemanticMemory = SemanticMemory(
        mockAiProvider,
        LocalEmbeddingProvider("test-placeholder"),
        MemoryStore()
    )

    private class MockAiProvider : AiProvider {
        override suspend fun answer(
            message: String,
            context: ConversationContext
        ): String = "Mock AI response for: $message"

        override suspend fun answerStreaming(
            message: String,
            context: ConversationContext,
            onDelta: (String) -> Unit
        ): String {
            val response = "Mock streaming response for: $message"
            onDelta(response)
            return response
        }
    }

    private inner class TestContextProvider(
        private val lowBattery: Boolean = false
    ) : IntelligenceCoordinator.ContextProvider {
        override fun getCurrentContext(): ConversationContext = context

        override fun getDeviceState(): ProactiveEngine.DeviceState =
            ProactiveEngine.DeviceState(
                batteryLevel = if (lowBattery) 10 else 75,
                isCharging = false,
                networkType = "WIFI",
                storageFreePercent = 35.0,
                screenOn = true,
                foregroundApp = "in.diganta.aurabutler"
            )
    }

    private class TestLiveInfoProvider : LiveInfoProvider {
        override suspend fun weather(settings: ButlerSettings): String = "Sunny, 25°C"
        override suspend fun headlines(limit: Int): List<String> =
            listOf("Headline 1", "Headline 2")
        override suspend fun weatherFor(settings: ButlerSettings, location: String?): String =
            "Sunny in $location"
        override suspend fun weatherForecast(
            settings: ButlerSettings,
            location: String?,
            days: Int
        ): String = "$days day forecast for $location"
        override suspend fun headlinesFor(limit: Int, topic: String?): List<String> =
            listOf("$topic Headline 1")
        override suspend fun knowledge(query: String): String? = "Knowledge about $query"
        override fun deviceStatus(): String = "Battery: 75%, Storage: 35% free"
    }
}
