package `in`.diganta.aurabutler.planning

import android.content.Context
import `in`.diganta.aurabutler.data.HybridAiProvider
import `in`.diganta.aurabutler.intelligence.ReasoningEngine
import `in`.diganta.aurabutler.model.ConversationContext

/**
 * Android production composition root for durable planning.
 *
 * Construct this object on Dispatchers.IO because repository initialization
 * restores encrypted SQLite checkpoints. A single instance is owned by the
 * ButlerViewModel and its planner is never recreated between approval events.
 */
class AndroidPlanningRuntime(context: Context) {
    private val appContext = context.applicationContext
    private val toolRegistry = ReasoningEngine.ToolRegistry()
    private val reasoningEngine = ReasoningEngine(
        aiProvider = HybridAiProvider(),
        toolRegistry = toolRegistry
    )
    private val planStore = PlanStore(
        persistence = EncryptedPlanStorePersistence(appContext)
    )

    val planner = TaskPlanner(
        reasoningEngine = reasoningEngine,
        toolRegistry = toolRegistry,
        planStore = planStore,
        executor = ProductionTaskExecutor(
            reasoningEngine = reasoningEngine
        )
    )

    fun getPlan(planId: String): TaskPlanner.Plan? {
        planStore.reload()
        return planStore.get(planId)
    }

    fun getPlans(): List<TaskPlanner.Plan> {
        planStore.reload()
        return planStore.getAll()
    }

    fun close() {
        planner.close()
    }
}
