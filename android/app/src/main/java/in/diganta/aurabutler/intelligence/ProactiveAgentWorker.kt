package `in`.diganta.aurabutler.intelligence

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import `in`.diganta.aurabutler.data.HybridAiProvider
import `in`.diganta.aurabutler.data.SecureStore
import `in`.diganta.aurabutler.memory.EmbeddingModelFiles
import `in`.diganta.aurabutler.memory.EncryptedSemanticMemoryPersistence
import `in`.diganta.aurabutler.memory.MemoryStore
import `in`.diganta.aurabutler.memory.OnnxSentenceEmbeddingProvider
import `in`.diganta.aurabutler.memory.SemanticMemory
import `in`.diganta.aurabutler.model.ConversationContext
import `in`.diganta.aurabutler.model.Sender
import `in`.diganta.aurabutler.planning.AndroidPlanningRuntime
import `in`.diganta.aurabutler.planning.ProductionTaskExecutor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Rehydrates encrypted agent checkpoints after process recreation and executes
 * only due, locally scheduled agents. External effects still stop at a Butler
 * approval card. The OS may defer this worker for battery/background policy.
 */
class ProactiveAgentWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val secureStore = SecureStore(applicationContext)
        val settings = try {
            secureStore.loadSettings()
        } catch (error: Exception) {
            return@withContext Result.retry()
        }

        if (!settings.proactiveSuggestionsEnabled) {
            AndroidProactiveScheduler.cancel(applicationContext)
            return@withContext Result.success()
        }

        var embeddingProvider: OnnxSentenceEmbeddingProvider? = null
        var semanticPersistence: EncryptedSemanticMemoryPersistence? = null
        var proactivePersistence: EncryptedProactivePersistence? = null
        var semanticMemory: SemanticMemory? = null
        var engine: ProactiveEngine? = null
        var planningRuntime: AndroidPlanningRuntime? = null

        try {
            val memories = secureStore.loadMemories()
            val userMessages = secureStore.loadMessages()
                .filter { it.sender == Sender.USER }
                .takeLast(80)
            val context = ConversationContext(
                recentMessages = userMessages,
                memories = memories,
                settings = settings
            )

            if (
                settings.semanticMemoryEnabled &&
                EmbeddingModelFiles.isInstalled(applicationContext)
            ) {
                semanticPersistence = EncryptedSemanticMemoryPersistence(applicationContext)
                embeddingProvider = OnnxSentenceEmbeddingProvider(
                    EmbeddingModelFiles.modelFile(applicationContext),
                    EmbeddingModelFiles.vocabularyFile(applicationContext)
                )
                semanticMemory = SemanticMemory(
                    aiProvider = null,
                    embeddingProvider = embeddingProvider,
                    memoryStore = MemoryStore(persistence = semanticPersistence)
                )
            }

            planningRuntime = AndroidPlanningRuntime(applicationContext)
            proactivePersistence = EncryptedProactivePersistence(applicationContext)
            engine = ProactiveEngine(
                aiProvider = HybridAiProvider(),
                semanticMemory = semanticMemory,
                settings = settings,
                contextProvider = AndroidProactiveContextProvider(applicationContext) { context },
                persistence = proactivePersistence,
                actionHandler = AndroidProactiveActionHandler(
                    applicationContext,
                    requireNotNull(planningRuntime)
                )
            )

            engine.runDueAgentsNow()

            // Expired proposals are never replayed. Persist their plan checkpoint
            // as skipped so the user can explicitly resume the remaining safe work.
            engine.expirePendingApprovals().forEach { action ->
                val planId = action.payload[ProductionTaskExecutor.PLAN_ID_ARGUMENT]
                val taskId = action.payload[ProductionTaskExecutor.TASK_ID_ARGUMENT]
                if (!planId.isNullOrBlank() && !taskId.isNullOrBlank()) {
                    planningRuntime.planner.resolveApproval(
                        planId = planId,
                        taskId = taskId,
                        actionId = action.id,
                        approved = false,
                        executionDetail = "Approval expired while AURA was closed. The action was not executed."
                    )
                }
            }
            engine.refreshNow(force = true)
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
        } finally {
            engine?.stop()
            embeddingProvider?.close()
            semanticPersistence?.close()
            proactivePersistence?.close()
            planningRuntime?.close()
        }
    }

    private companion object {
        const val MAX_RETRIES = 3
    }
}
