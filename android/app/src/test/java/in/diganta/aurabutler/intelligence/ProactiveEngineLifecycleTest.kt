package `in`.diganta.aurabutler.intelligence

import `in`.diganta.aurabutler.model.ActionKind
import `in`.diganta.aurabutler.model.ActionRisk
import `in`.diganta.aurabutler.model.ButlerAction
import `in`.diganta.aurabutler.model.ButlerSettings
import `in`.diganta.aurabutler.model.ChatMessage
import `in`.diganta.aurabutler.model.ConversationContext
import `in`.diganta.aurabutler.model.MemoryItem
import `in`.diganta.aurabutler.model.Sender
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

class ProactiveEngineLifecycleTest {

    @Test
    fun detectsRecurringTopicsOnlyFromUserMessagesOnDistinctDays() = runBlocking {
        val now = System.currentTimeMillis()
        val messages = (0 until 5).map { offset ->
            ChatMessage(
                sender = Sender.USER,
                text = "I am coding and debugging my software project today.",
                createdAtEpochMs = now - offset * MILLIS_PER_DAY
            )
        }
        val context = context(messages = messages)
        val engine = engine(context)

        try {
            engine.refreshNow(force = true)
            val suggestion = engine.getSuggestions(context, limit = 20)
                .firstOrNull { it.title == "Recurring topic: software development" }

            assertNotNull("A repeated software-development topic should be detected.", suggestion)
            assertEquals(ProactiveEngine.Trigger.USER_HABIT, suggestion?.context?.trigger)
            assertTrue(suggestion?.description.orEmpty().contains("AURA conversations"))
            assertFalse(suggestion?.description.orEmpty().contains("other apps"))
        } finally {
            engine.stop()
        }
    }

    @Test
    fun doesNotTreatAssistantMessagesAsUserHabits() = runBlocking {
        val now = System.currentTimeMillis()
        val messages = (0 until 8).map { offset ->
            ChatMessage(
                sender = Sender.AURA,
                text = "You are coding a software project.",
                createdAtEpochMs = now - offset * MILLIS_PER_DAY
            )
        }
        val context = context(messages = messages)
        val engine = engine(context)

        try {
            engine.refreshNow(force = true)
            val habitSuggestions = engine.getSuggestions(context, limit = 20)
                .filter { it.context.trigger == ProactiveEngine.Trigger.USER_HABIT }

            assertTrue(habitSuggestions.isEmpty())
        } finally {
            engine.stop()
        }
    }

    @Test
    fun researchFollowUpRequiresExplicitResearchTagInLocalMemory() = runBlocking {
        val context = context(
            memories = listOf(
                MemoryItem(
                    label = "[RESEARCH] Kotlin coroutine cancellation",
                    value = "AURA saved notes about structured concurrency and cancellation."
                )
            )
        )
        val engine = engine(context)

        try {
            engine.refreshNow(force = true)
            val research = engine.getSuggestions(context, limit = 20)
                .firstOrNull { it.title.startsWith("Refresh saved research:") }

            assertNotNull(research)
            assertEquals(ProactiveEngine.ActionType.START_RESEARCH, research?.action?.type)
            assertTrue(research?.action?.requiresConfirmation == true)
        } finally {
            engine.stop()
        }
    }

    @Test
    fun dueAgentExecutionReturnsOnlyAfterEachAgentCheckpointIsPersisted() = runBlocking {
        val persistence = FakePersistence()
        val context = context()
        val engine = engine(context, persistence)

        try {
            val completed = engine.runDueAgentsNow()
            val persisted = persistence.agentStates.values

            assertEquals(5, completed)
            assertTrue(persisted.isNotEmpty())
            assertTrue(persisted.all { it.lastRun != null })
            assertTrue(persisted.all { it.runCount == 1 })
            assertTrue(persisted.all { it.nextRun != null })
        } finally {
            engine.stop()
        }
    }

    @Test
    fun acceptedSuggestionStoresPendingActionAndDoesNotMarkItCompleteUntilApproval() = runBlocking {
        val action = ButlerAction(
            id = "proactive-approval-1",
            kind = ActionKind.OPEN_URL,
            title = "Open a trusted project page",
            summary = "Open the saved project URL.",
            risk = ActionRisk.MEDIUM,
            payload = mapOf("url" to "https://example.com")
        )
        val suggestion = ProactiveEngine.ProactiveSuggestion(
            id = "suggestion-pending-1",
            type = ProactiveEngine.SuggestionType.FOLLOW_UP,
            title = "Open project page",
            description = "Review the saved project page.",
            action = ProactiveEngine.ProactiveAction(
                type = ProactiveEngine.ActionType.OPEN_APP,
                payload = mapOf("app" to "project page"),
                requiresConfirmation = true
            ),
            confidence = 0.8,
            priority = ProactiveEngine.Priority.MEDIUM,
            context = ProactiveEngine.SuggestionContext(
                trigger = ProactiveEngine.Trigger.CONVERSATION_CONTEXT
            )
        )
        val persistence = FakePersistence().apply {
            saveSuggestion(suggestion)
        }
        val context = context()
        val engine = engine(
            context = context,
            persistence = persistence,
            actionHandler = object : ProactiveEngine.ActionHandler {
                override suspend fun execute(
                    action: ProactiveEngine.ProactiveAction,
                    context: ConversationContext
                ) = ProactiveEngine.ActionResult(
                    completed = false,
                    pendingApproval = action
                        .let { action.copy(payload = action.payload) }
                        .let { action ->
                            // The action gateway returns the canonical approval object.
                            this@ProactiveEngineLifecycleTest.pendingAction
                        }
                )
            }
        )

        try {
            val result = engine.acceptSuggestionDetailed(suggestion.id, context)

            assertNotNull(result.pendingApproval)
            assertFalse(result.completed)
            assertNotNull(engine.getSuggestionForAction(action.id))
            assertFalse(engine.getSuggestion(suggestion.id)?.accepted == true)

            assertTrue(engine.resolveActionApproval(action.id, completed = true, detail = "Opened."))
            assertTrue(engine.getSuggestion(suggestion.id)?.accepted == true)
            assertNull(engine.getSuggestionForAction(action.id))
        } finally {
            engine.stop()
        }
    }

    private val pendingAction: ButlerAction
        get() = ButlerAction(
            id = "proactive-approval-1",
            kind = ActionKind.OPEN_URL,
            title = "Open a trusted project page",
            summary = "Open the saved project URL.",
            risk = ActionRisk.MEDIUM,
            payload = mapOf("url" to "https://example.com")
        )

    private fun engine(
        context: ConversationContext,
        persistence: FakePersistence = FakePersistence(),
        actionHandler: ProactiveEngine.ActionHandler? = null
    ) = ProactiveEngine(
        aiProvider = null,
        semanticMemory = null,
        settings = context.settings,
        contextProvider = object : ProactiveEngine.ContextProvider {
            override fun getCurrentContext(): ConversationContext = context

            override fun getDeviceState() = ProactiveEngine.DeviceState(
                batteryLevel = 80,
                isCharging = true,
                networkType = "wifi",
                storageFreePercent = 70.0,
                screenOn = true,
                foregroundApp = null
            )
        },
        persistence = persistence,
        actionHandler = actionHandler
    )

    private fun context(
        messages: List<ChatMessage> = emptyList(),
        memories: List<MemoryItem> = emptyList()
    ) = ConversationContext(
        recentMessages = messages,
        memories = memories,
        settings = ButlerSettings(
            proactiveSuggestionsEnabled = true,
            semanticMemoryEnabled = false,
            memoryConsolidationEnabled = false
        )
    )

    private class FakePersistence : ProactiveSuggestionPersistence {
        val suggestions = ConcurrentHashMap<String, ProactiveEngine.ProactiveSuggestion>()
        val agentStates = ConcurrentHashMap<String, ProactiveEngine.AgentState>()

        override fun loadSuggestions(): List<ProactiveEngine.ProactiveSuggestion> =
            suggestions.values.toList()

        override fun saveSuggestion(suggestion: ProactiveEngine.ProactiveSuggestion) {
            suggestions[suggestion.id] = suggestion
        }

        override fun deleteSuggestion(id: String) {
            suggestions.remove(id)
        }

        override fun loadAgentStates(): List<ProactiveEngine.AgentState> =
            agentStates.values.toList()

        override fun saveAgentState(state: ProactiveEngine.AgentState) {
            agentStates[state.id] = state
        }
    }

    private companion object {
        const val MILLIS_PER_DAY = 24L * 60 * 60 * 1_000
    }
}
