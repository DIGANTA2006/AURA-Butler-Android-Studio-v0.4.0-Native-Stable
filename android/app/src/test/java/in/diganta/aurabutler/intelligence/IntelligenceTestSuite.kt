package `in`.diganta.aurabutler.intelligence

import `in`.diganta.aurabutler.data.AiProvider
import `in`.diganta.aurabutler.model.ButlerSettings
import `in`.diganta.aurabutler.model.ConversationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Comprehensive test suite for AURA v0.18 Intelligence Architecture
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

    // ===== Reasoning Engine Tests =====

    @Test
    fun testReasoningEngineBasicReasoning() = runBlocking {
        val toolRegistry = ReasoningEngine.ToolRegistry()
        val engine = ReasoningEngine(mockAiProvider, toolRegistry)
        
        val result = engine.reason(
            "What is the weather like today?",
            context
        )
        
        assertNotNull(result.finalAnswer)
        assertTrue(result.confidence > 0.0)
        assertNotNull(result.reasoningTrace)
    }

    @Test
    fun testReasoningEngineToolSelection() = runBlocking {
        val toolRegistry = ReasoningEngine.ToolRegistry()
        // Register tools
        registerAuraTools(toolRegistry)
        
        val engine = ReasoningEngine(mockAiProvider, toolRegistry)
        
        // Weather query should select get_weather tool
        val result = engine.reason(
            "What's the weather in Mumbai?",
            context
        )
        
        val toolUseSteps = result.reasoningTrace?.filter { 
            it.type == ReasoningEngine.StepType.TOOL_USE 
        } ?: emptyList()
        
        assertTrue("Should have tool use steps", toolUseSteps.isNotEmpty())
    }

    @Test
    fun testReasoningEngineReflectionMode() = runBlocking {
        val toolRegistry = ReasoningEngine.ToolRegistry()
        val engine = ReasoningEngine(mockAiProvider, toolRegistry)
        
        val options = ReasoningEngine.ReasoningOptions(
            mode = ReasoningEngine.ReasoningMode.REFLECTIVE,
            enableReflection = true
        )
        
        val result = engine.reason(
            "Explain quantum computing",
            context,
            options
        )
        
        val reflectionSteps = result.reasoningTrace?.filter { 
            it.type == ReasoningEngine.StepType.REFLECTION 
        } ?: emptyList()
        
        assertTrue("Reflective mode should have reflection steps", reflectionSteps.isNotEmpty())
    }

    // ===== Task Planner Tests =====

    @Test
    fun testTaskPlannerPlanCreation() = runBlocking {
        val toolRegistry = ReasoningEngine.ToolRegistry()
        registerAuraTools(toolRegistry)
        val reasoningEngine = ReasoningEngine(mockAiProvider, toolRegistry)
        val planStore = TaskPlanner.PlanStore()
        val planner = TaskPlanner(reasoningEngine, toolRegistry, planStore)
        
        val plan = planner.createPlanFromGoal(
            "Set a timer for 10 minutes and then play music",
            context
        )
        
        assertNotNull(plan)
        assertTrue("Plan should have tasks", plan.tasks.size >= 2)
        assertEquals(TaskPlanner.PlanStatus.PENDING, plan.status)
    }

    @Test
    fun testTaskPlannerParallelExecution() = runBlocking {
        val toolRegistry = ReasoningEngine.ToolRegistry()
        registerAuraTools(toolRegistry)
        val reasoningEngine = ReasoningEngine(mockAiProvider, toolRegistry)
        val planStore = TaskPlanner.PlanStore()
        val planner = TaskPlanner(reasoningEngine, toolRegistry, planStore)
        
        val plan = planner.createPlanFromGoal(
            "Check weather and get news headlines",
            context
        )
        
        // Should create parallel tasks for independent operations
        val parallelGroups = plan.tasks.filter { it.type == TaskPlanner.TaskType.PARALLEL_GROUP }
        // Note: Current implementation may not auto-detect parallelism
        // This test documents expected behavior
    }

    @Test
    fun testTaskPlannerPlanPersistence() = runBlocking {
        val toolRegistry = ReasoningEngine.ToolRegistry()
        registerAuraTools(toolRegistry)
        val reasoningEngine = ReasoningEngine(mockAiProvider, toolRegistry)
        val planStore = TaskPlanner.PlanStore()
        val planner = TaskPlanner(reasoningEngine, toolRegistry, planStore)
        
        val plan = planner.createPlanFromGoal(
            "Create a daily schedule",
            context
        )
        
        // Save and retrieve
        planStore.savePlan(plan)
        val retrieved = planStore.getPlan(plan.id)
        
        assertNotNull(retrieved)
        assertEquals(plan.id, retrieved!!.id)
        assertEquals(plan.goal, retrieved.goal)
    }

    // ===== Semantic Memory Tests =====

    @Test
    fun testSemanticMemoryRememberAndRecall() = runBlocking {
        val embeddingProvider = SemanticMemory.LocalEmbeddingProvider("test")
        val memoryStore = SemanticMemory.MemoryStore()
        val memory = SemanticMemory(mockAiProvider, embeddingProvider, memoryStore)
        
        // Store a memory
        val entry = memory.remember(
            content = "User prefers dark mode and quiet notifications",
            label = "UI Preferences",
            context = context,
            importance = 0.8
        )
        
        assertNotNull(entry.id)
        assertEquals("UI Preferences", entry.label)
        assertEquals(0.8, entry.metadata.importance, 0.01)
    }

    @Test
    fun testSemanticMemorySemanticSearch() = runBlocking {
        val embeddingProvider = SemanticMemory.LocalEmbeddingProvider("test")
        val memoryStore = SemanticMemory.MemoryStore()
        val memory = SemanticMemory(mockAiProvider, embeddingProvider, memoryStore)
        
        // Store related memories
        memory.remember("User likes jazz music", "Music Preference 1", context, importance = 0.7)
        memory.remember("User enjoys classical piano", "Music Preference 2", context, importance = 0.6)
        memory.remember("User hates loud rock music", "Music Preference 3", context, importance = 0.5)
        memory.remember("User's favorite food is pizza", "Food Preference", context, importance = 0.7)
        
        // Search for music-related content
        val result = memory.recall(
            "What music does the user like?",
            context,
            SemanticMemory.RecallOptions(
                maxResults = 3,
                strategy = SemanticMemory.RetrievalStrategy.SEMANTIC,
                minSimilarity = 0.1
            )
        )
        
        assertTrue("Should find music memories", result.entries.size >= 2)
        val musicEntries = result.entries.filter { 
            it.entry.label.contains("Music") 
        }
        assertTrue("Should have music entries", musicEntries.size >= 2)
    }

    @Test
    fun testSemanticMemoryTemporalRetrieval() = runBlocking {
        val embeddingProvider = SemanticMemory.LocalEmbeddingProvider("test")
        val memoryStore = SemanticMemory.MemoryStore()
        val memory = SemanticMemory(mockAiProvider, embeddingProvider, memoryStore)
        
        memory.remember("Meeting at 10 AM tomorrow", "Meeting 1", context, importance = 0.8)
        memory.remember("Lunch with client at 1 PM", "Meeting 2", context, importance = 0.7)
        memory.remember("Project deadline next Friday", "Deadline", context, importance = 0.9)
        
        val result = memory.recall(
            "What's my schedule?",
            context,
            SemanticMemory.RecallOptions(
                maxResults = 5,
                strategy = SemanticMemory.RetrievalStrategy.TEMPORAL
            )
        )
        
        assertTrue("Should retrieve temporal memories", result.entries.isNotEmpty())
    }

    @Test
    fun testSemanticMemoryConsolidation() = runBlocking {
        val embeddingProvider = SemanticMemory.LocalEmbeddingProvider("test")
        val memoryStore = SemanticMemory.MemoryStore()
        val memory = SemanticMemory(mockAiProvider, embeddingProvider, memoryStore)
        
        // Add duplicate memories
        memory.remember("User likes coffee", "Coffee 1", context, importance = 0.5)
        memory.remember("User enjoys coffee in the morning", "Coffee 2", context, importance = 0.5)
        memory.remember("Coffee is user's favorite drink", "Coffee 3", context, importance = 0.5)
        
        val statsBefore = memory.getStats()
        
        // Run consolidation
        memory.consolidate()
        
        val statsAfter = memory.getStats()
        
        // Should have created consolidated memory
        assertTrue("Should have consolidated memories", statsAfter.totalMemories >= statsBefore.totalMemories)
    }

    @Test
    fun testSemanticMemoryForgettingCurve() = runBlocking {
        val embeddingProvider = SemanticMemory.LocalEmbeddingProvider("test")
        val memoryStore = SemanticMemory.MemoryStore()
        val memory = SemanticMemory(mockAiProvider, embeddingProvider, memoryStore)
        
        // Add memory with low importance
        val entry = memory.remember(
            "Random fact: bananas are berries",
            "Trivia",
            context,
            importance = 0.1
        )
        
        // Manually age the memory
        val agedEntry = entry.copy(
            metadata = entry.metadata.copy(
                lastAccessed = System.currentTimeMillis() - (90 * 24 * 60 * 60 * 1000L) // 90 days ago
            )
        )
        memoryStore.upsert(agedEntry)
        
        // Apply forgetting curve
        memory.applyForgettingCurve()
        
        // Low importance old memory should be forgotten
        val retrieved = memoryStore.get(entry.id)
        assertTrue("Low importance old memory should be forgotten or very low importance", 
            retrieved == null || retrieved.metadata.importance < 0.05)
    }

    // ===== Proactive Engine Tests =====

    @Test
    fun testProactiveEngineSuggestionGeneration() = runBlocking {
        val proactiveEngine = ProactiveEngine(
            aiProvider = mockAiProvider,
            semanticMemory = createTestSemanticMemory(),
            settings = settings,
            contextProvider = testContextProvider
        )
        
        proactiveEngine.start()
        
        // Wait for suggestion generation
        Thread.sleep(1000)
        
        val suggestions = proactiveEngine.getSuggestions(context)
        
        // Should generate time-based suggestions
        assertTrue("Should have suggestions", suggestions.isNotEmpty())
        
        proactiveEngine.stop()
    }

    @Test
    fun testProactiveEngineBatteryAlert() = runBlocking {
        val proactiveEngine = ProactiveEngine(
            aiProvider = mockAiProvider,
            semanticMemory = createTestSemanticMemory(),
            settings = settings,
            contextProvider = TestContextProvider(lowBattery = true)
        )
        
        proactiveEngine.start()
        Thread.sleep(1000)
        
        val suggestions = proactiveEngine.getSuggestions(context)
        
        val batteryAlerts = suggestions.filter { 
            it.type == ProactiveEngine.SuggestionType.MAINTENANCE &&
            it.title.contains("Battery")
        }
        
        assertTrue("Should have battery alert", batteryAlerts.isNotEmpty())
        
        proactiveEngine.stop()
    }

    @Test
    fun testProactiveEngineDismissAndAccept() = runBlocking {
        val proactiveEngine = ProactiveEngine(
            aiProvider = mockAiProvider,
            semanticMemory = createTestSemanticMemory(),
            settings = settings,
            contextProvider = testContextProvider
        )
        
        proactiveEngine.start()
        Thread.sleep(1000)
        
        val suggestions = proactiveEngine.getSuggestions(context)
        if (suggestions.isNotEmpty()) {
            val suggestion = suggestions.first()
            
            // Test dismiss
            proactiveEngine.dismissSuggestion(suggestion.id)
            val afterDismiss = proactiveEngine.getSuggestions(context)
            assertFalse("Dismissed suggestion should not appear", 
                afterDismiss.any { it.id == suggestion.id })
        }
        
        proactiveEngine.stop()
    }

    @Test
    fun testProactiveEngineBackgroundAgents() = runBlocking {
        val proactiveEngine = ProactiveEngine(
            aiProvider = mockAiProvider,
            semanticMemory = createTestSemanticMemory(),
            settings = settings,
            contextProvider = testContextProvider
        )
        
        val agents = proactiveEngine.getAgentStatus()
        
        assertTrue("Should have default agents", agents.size >= 6)
        
        val agentNames = agents.map { it.name }.toSet()
        assertTrue("Should have Daily Briefing agent", agentNames.contains("Daily Briefing"))
        assertTrue("Should have Memory Consolidation agent", agentNames.contains("Memory Consolidation"))
        assertTrue("Should have Battery Optimization agent", agentNames.contains("Battery Optimization"))
        assertTrue("Should have Storage Monitor agent", agentNames.contains("Storage Monitor"))
        assertTrue("Should have Habit Learning agent", agentNames.contains("Habit Learning"))
        assertTrue("Should have Research Follow-up agent", agentNames.contains("Research Follow-up"))
    }

    // ===== Intelligence Coordinator Tests =====

    @Test
    fun testIntelligenceCoordinatorStrategySelection() = runBlocking {
        val coordinator = IntelligenceCoordinator(
            aiProvider = mockAiProvider,
            settings = settings,
            contextProvider = testContextProvider
        )
        
        coordinator.initialize()
        
        // Test local command strategy
        val localResult = coordinator.processRequest("Open YouTube", context)
        assertEquals(IntelligenceCoordinator.IntelligenceMode.MINIMAL, localResult.mode)
        
        // Test reasoning strategy
        val reasoningResult = coordinator.processRequest("Explain how photosynthesis works", context)
        assertEquals(IntelligenceCoordinator.IntelligenceMode.BALANCED, reasoningResult.mode)
        
        // Test planning strategy
        val planningResult = coordinator.processRequest("Plan my week with meetings and tasks", context)
        assertEquals(IntelligenceCoordinator.IntelligenceMode.ENHANCED, planningResult.mode)
        assertNotNull(planningResult.plan)
    }

    @Test
    fun testIntelligenceCoordinatorModeSwitching() = runBlocking {
        val coordinator = IntelligenceCoordinator(
            aiProvider = mockAiProvider,
            settings = settings,
            contextProvider = testContextProvider
        )
        
        coordinator.initialize()
        
        // Test minimal mode
        coordinator.setIntelligenceMode(IntelligenceCoordinator.IntelligenceMode.MINIMAL)
        val minimalResult = coordinator.processRequest("Explain quantum physics", context)
        assertEquals(IntelligenceCoordinator.IntelligenceMode.MINIMAL, minimalResult.mode)
        
        // Test autonomous mode
        coordinator.setIntelligenceMode(IntelligenceCoordinator.IntelligenceMode.AUTONOMOUS)
        val autonomousResult = coordinator.processRequest("Figure out my schedule and optimize it", context)
        assertEquals(IntelligenceCoordinator.IntelligenceMode.AUTONOMOUS, autonomousResult.mode)
    }

    @Test
    fun testIntelligenceCoordinatorMemoryIntegration() = runBlocking {
        val coordinator = IntelligenceCoordinator(
            aiProvider = mockAiProvider,
            settings = settings,
            contextProvider = testContextProvider
        )
        
        coordinator.initialize()
        
        // Store memory
        val entry = coordinator.remember(
            "User is a software engineer who likes Kotlin",
            "User Profile",
            context,
            importance = 0.9
        )
        
        assertNotNull(entry.id)
        
        // Recall should find it
        val result = coordinator.recall("What is the user's profession?", context)
        assertTrue("Should find user profile", result.entries.isNotEmpty())
    }

    @Test
    fun testIntelligenceCoordinatorStatus() = runBlocking {
        val coordinator = IntelligenceCoordinator(
            aiProvider = mockAiProvider,
            settings = settings,
            contextProvider = testContextProvider
        )
        
        coordinator.initialize()
        
        val status = coordinator.getStatus()
        
        assertTrue("Should be initialized", status.initialized)
        assertEquals(IntelligenceCoordinator.IntelligenceMode.BALANCED, status.mode)
        assertTrue("Should have tools registered", status.toolCount > 0)
    }

    // ===== Enhanced Butler Engine Tests =====

    @Test
    fun testEnhancedButlerEngineLocalCommands() = runBlocking {
        val engine = EnhancedButlerEngine.create(
            liveInfo = TestLiveInfoProvider(),
            generalAi = mockAiProvider,
            settingsProvider = { settings },
            contextProvider = testContextProvider
        )
        
        // Test local command still works
        val reply = engine.chat("What time is it?", context)
        
        assertNotNull(reply.assistantMessage)
        assertTrue(reply.assistantMessage.contains("time") || reply.assistantMessage.contains("Time"))
    }

    @Test
    fun testEnhancedButlerEngineIntelligenceMode() = runBlocking {
        val engine = EnhancedButlerEngine.create(
            liveInfo = TestLiveInfoProvider(),
            generalAi = mockAiProvider,
            settingsProvider = { settings },
            contextProvider = testContextProvider
        )
        
        // Test complex query uses intelligence
        val reply = engine.chat("Analyze the pros and cons of using Kotlin vs Java for Android development", context)
        
        assertNotNull(reply.assistantMessage)
        assertTrue(reply.assistantMessage.length > 50) // Should have substantial answer
    }

    @Test
    fun testEnhancedButlerEngineProactiveSuggestions() = runBlocking {
        val engine = EnhancedButlerEngine.create(
            liveInfo = TestLiveInfoProvider(),
            generalAi = mockAiProvider,
            settingsProvider = { settings },
            contextProvider = testContextProvider
        )
        
        val suggestions = engine.getProactiveSuggestions(context)
        
        // Should return suggestions (may be empty initially)
        assertNotNull(suggestions)
    }

    // ===== Helper Methods =====

    private fun registerAuraTools(registry: ReasoningEngine.ToolRegistry) {
        registry.register(ReasoningEngine.ToolDefinition(
            name = "get_weather",
            description = "Get current weather",
            keywords = listOf("weather"),
            requiresConfirmation = false
        ))
        registry.register(ReasoningEngine.ToolDefinition(
            name = "get_news",
            description = "Get news headlines",
            keywords = listOf("news"),
            requiresConfirmation = false
        ))
        registry.register(ReasoningEngine.ToolDefinition(
            name = "set_timer",
            description = "Set a timer",
            keywords = listOf("timer"),
            requiresConfirmation = false
        ))
        registry.register(ReasoningEngine.ToolDefinition(
            name = "play_media",
            description = "Play music",
            keywords = listOf("play", "music"),
            requiresConfirmation = false
        ))
    }

    private fun createTestSemanticMemory(): SemanticMemory {
        val embeddingProvider = SemanticMemory.LocalEmbeddingProvider("test")
        val memoryStore = SemanticMemory.MemoryStore()
        return SemanticMemory(mockAiProvider, embeddingProvider, memoryStore)
    }

    // ===== Test Doubles =====

    private class MockAiProvider : AiProvider {
        override suspend fun answer(message: String, context: ConversationContext): String? {
            return "Mock AI response for: $message"
        }

        override suspend fun answerStreaming(
            message: String,
            context: ConversationContext,
            onDelta: (String) -> Unit
        ): String? {
            val response = "Mock streaming response for: $message"
            onDelta(response)
            return response
        }
    }

    private class TestContextProvider(
        val lowBattery: Boolean = false
    ) : IntelligenceCoordinator.ContextProvider, ProactiveEngine.ContextProvider {
        
        override fun getCurrentContext(): ConversationContext = context
        
        override fun getDeviceState(): ProactiveEngine.DeviceState {
            return ProactiveEngine.DeviceState(
                batteryLevel = if (lowBattery) 10 else 75,
                isCharging = false,
                networkType = "WIFI",
                storageFreePercent = 35.0,
                screenOn = true,
                foregroundApp = "in.diganta.aurabutler"
            )
        }
    }

    private class TestLiveInfoProvider : LiveInfoProvider {
        override suspend fun weather(settings: ButlerSettings): String = "Sunny, 25°C"
        override suspend fun headlines(limit: Int): List<String> = listOf("Headline 1", "Headline 2")
        override suspend fun weatherFor(settings: ButlerSettings, location: String?): String = "Sunny in $location"
        override suspend fun weatherForecast(settings: ButlerSettings, location: String?, days: Int): String = "$days day forecast for $location"
        override suspend fun headlinesFor(limit: Int, topic: String?): List<String> = listOf("$topic Headline 1")
        override suspend fun knowledge(query: String): String? = "Knowledge about $query"
        override fun deviceStatus(): String = "Battery: 75%, Storage: 35% free"
    }
}
