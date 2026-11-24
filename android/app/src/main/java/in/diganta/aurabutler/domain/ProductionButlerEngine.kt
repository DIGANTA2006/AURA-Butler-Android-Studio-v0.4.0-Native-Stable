package `in`.diganta.aurabutler.domain

import android.content.Context
import android.os.SystemClock
import `in`.diganta.aurabutler.data.SecureStore
import `in`.diganta.aurabutler.analyzer.UnifiedAnalyzerEngine
import `in`.diganta.aurabutler.data.GeminiCommandInterpreter
import `in`.diganta.aurabutler.data.HybridAiProvider
import `in`.diganta.aurabutler.data.StructuredAgentProvider
import `in`.diganta.aurabutler.maintenance.AuraDoctor
import `in`.diganta.aurabutler.model.ButlerReply
import `in`.diganta.aurabutler.model.MemoryItem
import `in`.diganta.aurabutler.memory.EmbeddingModelFiles
import `in`.diganta.aurabutler.memory.EncryptedSemanticMemoryPersistence
import `in`.diganta.aurabutler.memory.MemoryStore
import `in`.diganta.aurabutler.memory.OnnxSentenceEmbeddingProvider
import `in`.diganta.aurabutler.memory.SemanticMemory
import android.util.Log
import java.io.File
import `in`.diganta.aurabutler.model.ConversationContext
import `in`.diganta.aurabutler.runtime.ActionAuditLog
import `in`.diganta.aurabutler.runtime.AuraRuntimeState
import `in`.diganta.aurabutler.runtime.AuraRuntimeStateMachine
import `in`.diganta.aurabutler.runtime.IntentRoute
import `in`.diganta.aurabutler.runtime.ProductionIntentRouter
import `in`.diganta.aurabutler.runtime.SecretRedactor
import `in`.diganta.aurabutler.runtime.StudentBudgetGuard
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Mobile port of the hardened AURA 8.2 orchestration core.
 *
 * Deterministic commands and private memory are attempted first. The local
 * trained intent router supplies bounded classification. Optional cloud AI is
 * reached only through LocalButlerEngine's unresolved-request fallback.
 */
class ProductionButlerEngine(
    context: Context,
    private val store: SecureStore,
    private val delegate: LocalButlerEngine
) {
    private val appContext = context.applicationContext
    private val router by lazy { ProductionIntentRouter(appContext) }
    private val audit by lazy { ActionAuditLog(appContext) }
    private val unifiedAnalyzer by lazy { UnifiedAnalyzerEngine(appContext) }
    private val deviceCommands by lazy { DeviceCommandEngine(appContext) }
    private val youtubeAutoplay by lazy { YouTubeAutoplayEngine() }
    private val newsCommands by lazy { NewsCommandEngine(store) }
    private val commandInterpreter by lazy { GeminiCommandInterpreter() }
    private val structuredAgent by lazy { StructuredAgentProvider() }

    @Volatile
    private var semanticMemoryInstance: SemanticMemory? = null

    private val semanticMemoryLock = Any()

    private fun semanticMemoryOrNull(): SemanticMemory? {
        semanticMemoryInstance?.let { return it }
        if (!EmbeddingModelFiles.isInstalled(appContext)) return null
        synchronized(semanticMemoryLock) {
            semanticMemoryInstance?.let { return it }
            val model = EmbeddingModelFiles.modelFile(appContext)
            val vocabulary = EmbeddingModelFiles.vocabularyFile(appContext)
            val provider = OnnxSentenceEmbeddingProvider(model, vocabulary)
            return SemanticMemory(
                aiProvider = null,
                embeddingProvider = provider,
                memoryStore = MemoryStore(
                    persistence = EncryptedSemanticMemoryPersistence(appContext)
                )
            ).also { semanticMemoryInstance = it }
        }
    }

    private suspend fun enrichWithSemanticMemory(
        message: String,
        context: ConversationContext
    ): ConversationContext {
        if (!context.settings.semanticMemoryEnabled || message.isBlank()) return context
        val semantic = try {
            semanticMemoryOrNull()
        } catch (error: Exception) {
            Log.w(TAG, "The local ONNX semantic-memory engine could not initialize.", error)
            null
        } ?: return context

        return try {
            val privateItems = withContext(Dispatchers.IO) { store.loadMemories() }
            semantic.reconcilePrivateMemories(privateItems, context)
            val recalled = semantic.recall(
                query = message,
                context = context,
                options = SemanticMemory.RecallOptions(
                    maxResults = 6,
                    minScore = 0.18,
                    semanticWeight = 0.82,
                    strategy = SemanticMemory.RetrievalStrategy.HYBRID
                )
            )
            val semanticItems = recalled.entries.map { scored ->
                val entry = scored.entry
                MemoryItem(
                    id = entry.id,
                    label = entry.label,
                    value = entry.content,
                    createdAtEpochMs = entry.createdAt
                )
            }
            context.copy(
                memories = (semanticItems + context.memories)
                    .distinctBy(MemoryItem::id)
                    .take(20)
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Local semantic recall failed; AURA will use its encrypted lexical memory path.", error)
            context
        }
    }

    private suspend fun reconcileSemanticWrites(
        writes: List<MemoryItem>,
        context: ConversationContext
    ) {
        val semantic = try {
            semanticMemoryOrNull()
        } catch (error: Exception) {
            Log.w(TAG, "The local semantic-memory engine could not initialize.", error)
            null
        } ?: return
        try {
            val completeMemoryList = withContext(Dispatchers.IO) { store.loadMemories() }
            semantic.reconcilePrivateMemories(completeMemoryList, context)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "New private memories could not be indexed semantically.", error)
        }
    }

    suspend fun chat(
        message: String,
        context: ConversationContext,
        onPartial: (String) -> Unit = {}
    ): ButlerReply {
        val startedAt = SystemClock.elapsedRealtime()
        val turnDeadlineAt = startedAt + totalTurnDeadlineMs(message, context)
        var deterministicMs = 0L
        var commandRepairMs = 0L
        var agentMs = 0L
        var delegatedAiMs = 0L
        val bridgedMessage = MultilingualCommandBridge.normalizeKnownCommand(message)
        val normalization = CommandNormalizer.normalize(
            bridgedMessage,
            context.settings.smartCommandCorrection
        )
        var effectiveMessage = normalization.corrected
        var repairApplied = false
        AuraRuntimeStateMachine.startIfNeeded()
        AuraRuntimeStateMachine.safeTransition(
            AuraRuntimeState.THINKING,
            "processing user request"
        )

        var route = withContext(Dispatchers.Default) {
            router.route(effectiveMessage)
        }

        return try {
            val deterministicStartedAt = SystemClock.elapsedRealtime()
            var direct = withTimeoutOrNull(
                remainingTurnMs(turnDeadlineAt, deterministicTimeoutMs(effectiveMessage))
            ) {
                deterministicReply(
                    message = effectiveMessage,
                    route = route,
                    context = context
                )
            }
            deterministicMs += SystemClock.elapsedRealtime() - deterministicStartedAt

            if (
                direct == null &&
                context.settings.aiCommandRepairEnabled &&
                HybridAiProvider.publicProviderAllowed(
                    context.settings,
                    HybridAiProvider.PROVIDER_GEMINI
                ) &&
                CommandNormalizer.isLikelyCommand(effectiveMessage)
            ) {
                val repairStartedAt = SystemClock.elapsedRealtime()
                val repair = try {
                    withTimeoutOrNull(remainingTurnMs(turnDeadlineAt, COMMAND_REPAIR_TIMEOUT_MS)) {
                        commandInterpreter.repairCommand(
                            raw = effectiveMessage,
                            settings = context.settings
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                commandRepairMs += SystemClock.elapsedRealtime() - repairStartedAt

                val repaired = repair
                    ?.takeIf {
                        it.confidence >= MIN_COMMAND_REPAIR_CONFIDENCE
                    }
                    ?.correctedCommand
                    ?.takeIf {
                        !it.equals(
                            effectiveMessage,
                            ignoreCase = true
                        )
                    }

                if (repaired != null) {
                    effectiveMessage = repaired
                    repairApplied = true
                    route = withContext(Dispatchers.Default) {
                        router.route(effectiveMessage)
                    }
                    val repairedDeterministicStartedAt = SystemClock.elapsedRealtime()
                    direct = withTimeoutOrNull(
                        remainingTurnMs(turnDeadlineAt, deterministicTimeoutMs(effectiveMessage))
                    ) {
                        deterministicReply(
                            message = effectiveMessage,
                            route = route,
                            context = context
                        )
                    }
                    deterministicMs += SystemClock.elapsedRealtime() - repairedDeterministicStartedAt
                }
            }

            val intelligenceContext = if (direct == null) {
                enrichWithSemanticMemory(effectiveMessage, context)
            } else {
                context
            }

            if (direct == null && shouldUseStructuredAgent(effectiveMessage, intelligenceContext)) {
                val agentStartedAt = SystemClock.elapsedRealtime()
                direct = try {
                    withTimeoutOrNull(remainingTurnMs(turnDeadlineAt, AGENT_TIMEOUT_MS)) {
                        runStructuredAgent(effectiveMessage, intelligenceContext)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                agentMs += SystemClock.elapsedRealtime() - agentStartedAt
            }

            val reply = direct ?: run {
                val relevant = (
                    store.searchMemories(effectiveMessage, 10) + intelligenceContext.memories
                ).distinctBy(MemoryItem::id).take(10)

                // Never send a likely secret to an optional provider. The
                // deterministic local engine can still explain the restriction.
                val safeContext = if (
                    SecretRedactor.containsLikelySecret(effectiveMessage) &&
                    route.intent in CLOUD_ELIGIBLE_INTENTS
                ) {
                    intelligenceContext.copy(
                        memories = relevant,
                        settings = intelligenceContext.settings.copy(aiEnabled = false)
                    )
                } else {
                    intelligenceContext.copy(memories = relevant)
                }

                val delegatedStartedAt = SystemClock.elapsedRealtime()
                val delegatedReply = withTimeoutOrNull(
                    remainingTurnMs(turnDeadlineAt, turnTimeoutMs(effectiveMessage, safeContext))
                ) {
                    delegate.chat(
                        message = effectiveMessage,
                        context = safeContext,
                        onPartial = onPartial
                    )
                } ?: throw IOException(
                    "The request reached AURA's time limit before the optional AI reply completed."
                )
                delegatedAiMs += SystemClock.elapsedRealtime() - delegatedStartedAt
                delegatedReply.copy(
                    provider = inferProvider(route, safeContext),
                    intent = route.intent
                )
            }

            val resolvedReply = if (
                normalization.changed || repairApplied
            ) {
                reply.copy(
                    assistantMessage =
                        "I understood your command as “${effectiveMessage.take(220)}”.\n\n" +
                            reply.assistantMessage
                )
            } else {
                reply
            }

            if (
                context.settings.semanticMemoryEnabled &&
                resolvedReply.memoryWrites.isNotEmpty()
            ) {
                reconcileSemanticWrites(resolvedReply.memoryWrites, context)
            }

            val latency = SystemClock.elapsedRealtime() - startedAt
            audit.record(
                event = "turn.completed",
                fields = mapOf(
                    "intent" to route.intent,
                    "source" to route.source,
                    "confidence" to route.confidence,
                    "provider" to resolvedReply.provider,
                    "latency_ms" to latency,
                    "deterministic_ms" to deterministicMs,
                    "command_repair_ms" to commandRepairMs,
                    "agent_ms" to agentMs,
                    "delegated_ai_ms" to delegatedAiMs,
                    "actions" to resolvedReply.pendingActions.size,
                    "memory_writes" to resolvedReply.memoryWrites.size,
                    "request" to message.take(180),
                    "effective_request" to effectiveMessage.take(180),
                    "command_corrected" to (
                        normalization.changed || repairApplied
                    )
                )
            )
            AuraRuntimeStateMachine.forceIdleUnlessListening("turn completed")
            resolvedReply.copy(latencyMs = latency)
        } catch (cancelled: CancellationException) {
            val latency = SystemClock.elapsedRealtime() - startedAt
            audit.record(
                event = "turn.cancelled",
                fields = mapOf(
                    "intent" to route.intent,
                    "latency_ms" to latency,
                    "deterministic_ms" to deterministicMs,
                    "command_repair_ms" to commandRepairMs,
                    "agent_ms" to agentMs,
                    "delegated_ai_ms" to delegatedAiMs
                )
            )
            AuraRuntimeStateMachine.forceIdleUnlessListening("turn cancelled")
            throw cancelled
        } catch (error: Exception) {
            val latency = SystemClock.elapsedRealtime() - startedAt
            audit.record(
                event = "turn.failed",
                fields = mapOf(
                    "intent" to route.intent,
                    "error" to error.javaClass.simpleName,
                    "message" to error.message.orEmpty().take(180),
                    "latency_ms" to latency,
                    "deterministic_ms" to deterministicMs,
                    "command_repair_ms" to commandRepairMs,
                    "agent_ms" to agentMs,
                    "delegated_ai_ms" to delegatedAiMs
                )
            )
            AuraRuntimeStateMachine.safeTransition(
                AuraRuntimeState.ERROR,
                "turn failed"
            )
            AuraRuntimeStateMachine.forceIdle("recovered after turn failure")

            val failureMessage = safeFailureMessage(error)
            ButlerReply(
                assistantMessage = failureMessage,
                speechText = SpeechFailureSummary.forSpeech(failureMessage),
                pendingActions = emptyList(),
                memoryWrites = emptyList(),
                provider = "safe-fallback",
                intent = route.intent,
                latencyMs = latency
            )
        }
    }

    /**
     * Qwen decomposes a genuinely multi-step request, but every step is executed
     * by AURA's bounded deterministic experts. The model never receives direct
     * Android control and all resulting actions still pass the normal approval layer.
     */
    private suspend fun runStructuredAgent(
        message: String,
        context: ConversationContext
    ): ButlerReply? {
        if (SecretRedactor.containsLikelySecret(message)) return null
        val relevant = store.searchMemories(message, 6)
            .ifEmpty { context.memories.takeLast(3) }
        val plannerMemories = if (context.settings.allowAiPrivateContext) relevant else emptyList()
        val steps = structuredAgent.plan(message, plannerMemories, context.settings)
        if (steps.size < 2) return null

        val localContext = context.copy(
            memories = relevant,
            settings = context.settings.copy(
                aiEnabled = false,
                aiCommandRepairEnabled = false
            )
        )
        val replies = mutableListOf<Pair<StructuredAgentProvider.Step, ButlerReply>>()
        for (step in steps.take(MAX_AGENT_STEPS)) {
            currentCoroutineContext().ensureActive()
            val stepRoute = withContext(Dispatchers.Default) {
                router.route(step.command)
            }
            val reply = deterministicReply(step.command, stepRoute, localContext)
                ?: delegate.chat(step.command, localContext)
            val unusable = reply.assistantMessage.contains(
                "needs optional AI reasoning",
                ignoreCase = true
            )
            if (!unusable) replies += step to reply
        }
        if (replies.size < 2) return null

        val text = buildString {
            append("I broke that into ${replies.size} bounded AURA steps:\n")
            replies.forEachIndexed { index, (step, reply) ->
                append("\n${index + 1}. [${step.expert}] ")
                append(reply.assistantMessage.trim())
            }
        }
        return ButlerReply(
            assistantMessage = text,
            speechText = "I prepared ${replies.size} steps. Review the results and any approval cards on screen.",
            pendingActions = replies.flatMap { it.second.pendingActions }.take(12),
            memoryWrites = replies.flatMap { it.second.memoryWrites }.take(20),
            provider = "qwen-planner+local-experts",
            intent = "agent_plan"
        )
    }

    private fun shouldUseStructuredAgent(
        message: String,
        context: ConversationContext
    ): Boolean {
        if (!context.settings.aiEnabled || !context.settings.localAiEnabled) return false
        if (context.settings.localAiApiKey.isBlank()) return false
        if (message.length < 18 || SecretRedactor.containsLikelySecret(message)) return false
        val lower = message.lowercase(Locale.ROOT)
        val connectors = listOf(
            " and then ", " then ", " after that ", " afterwards ", " also ",
            " first ", " next ", ";", "\n", "তারপর", "এরপর", "फिर", "उसके बाद"
        )
        val explicit = lower.startsWith("do these ") ||
            lower.startsWith("handle these ") ||
            lower.startsWith("plan and do ") ||
            lower.startsWith("multi step ")
        return explicit || connectors.count { lower.contains(it) } >= 1
    }

    private suspend fun deterministicReply(
        message: String,
        route: IntentRoute,
        context: ConversationContext
    ): ButlerReply? {
        youtubeAutoplay.handle(message, context)?.let {
            return it
        }
        newsCommands.handle(message, context)?.let {
            return it
        }
        deviceCommands.handle(message, context)?.let {
            return it
        }
        unifiedAnalyzer.handle(message, context)?.let {
            return it
        }
        return handleProductionCommand(
            message = message,
            route = route,
            context = context
        )
    }

    private fun handleProductionCommand(
        message: String,
        route: IntentRoute,
        context: ConversationContext
    ): ButlerReply? {
        val normalized = route.normalizedText

        val doctorRequested = route.intent == "system_vitals" &&
            normalized.containsAny(
                "aura doctor",
                "run doctor",
                "voice diagnostics",
                "wake status",
                "aura diagnostics",
                "system health"
            )
        if (doctorRequested) {
            return localReply(
                text = AuraDoctor.run(appContext),
                speech = "AURA diagnostics are complete. Open the report in the app.",
                provider = "local-doctor",
                intent = route.intent
            )
        }

        if (
            normalized.startsWith("forget ") ||
            normalized.startsWith("delete memory ") ||
            normalized.startsWith("remove memory ")
        ) {
            val target = normalized
                .replaceFirst(Regex("""^(?:forget(?: that)?|delete memory|remove memory)\s+"""), "")
                .trim()
            if (target.isBlank()) {
                return localReply(
                    "Tell me what memory to forget.",
                    intent = "forget_memory"
                )
            }
            val removed = store.forgetMemories(target)
            return localReply(
                text = if (removed > 0) {
                    "I removed $removed matching private memory ${if (removed == 1) "item" else "items"}."
                } else {
                    "I could not find a saved memory matching “$target”."
                },
                intent = "forget_memory"
            )
        }

        if (route.intent in setOf("cancel_action", "cancel")) {
            val pending = store.loadPendingActions()
            store.savePendingActions(emptyList())
            return localReply(
                text = if (pending.isEmpty()) {
                    "There is no pending action to cancel."
                } else {
                    "Cancelled. I removed ${pending.size} pending approval ${if (pending.size == 1) "card" else "cards"}."
                },
                intent = "cancel_action"
            )
        }

        if (route.intent == "confirm") {
            return localReply(
                text = "For safety, open AURA and approve the prepared action card on screen.",
                intent = "confirm_action"
            )
        }

        if (route.intent == "interrupt") {
            return localReply(
                text = "Stopped. I will wait for your next command.",
                intent = "interrupt"
            )
        }

        if (
            SecretRedactor.containsLikelySecret(message) &&
            normalized.containsAny("remember", "save", "store", "send to ai")
        ) {
            return localReply(
                text = "For your safety, I will not store or send passwords, PINs, OTPs, card numbers, API keys, or recovery phrases.",
                intent = "sensitive_data_blocked"
            )
        }

        if (
            normalized.containsAny(
                "ai budget",
                "cloud budget",
                "ai usage",
                "student budget",
                "how many ai requests"
            )
        ) {
            return localReply(
                text = StudentBudgetGuard.statusText(),
                intent = "ai_budget_status"
            )
        }

        if (
            normalized.containsAny(
                "ai status",
                "which ai",
                "which model",
                "gemini status",
                "groq status"
            )
        ) {
            val provider = HybridAiProvider.configuredProviderLabel(context.settings)
            return localReply(
                text = if (provider == "local-only") {
                    "Optional AI is not configured. Local memory, phone commands, weather, news and Wikipedia remain available."
                } else {
                    "Optional AI is enabled with route $provider. Local memory and deterministic tools are still tried first. ${StudentBudgetGuard.statusText()}"
                },
                intent = "ai_status"
            )
        }

        return null
    }

    private fun safeFailureMessage(error: Exception): String {
        if (error is TimeoutCancellationException) {
            return "That request took too long. The private cloud model may be cold-starting; please try again in a moment."
        }
        return when (error) {
            is SocketTimeoutException ->
                "The network or private cloud model timed out. If this is the first AI request after idle time, the model may still be warming up."
            is UnknownHostException, is ConnectException ->
                "I could not reach the configured service. Check mobile data or Wi-Fi and try again."
            is IOException, is IllegalArgumentException, is IllegalStateException -> {
                val detail = SecretRedactor.redact(error.message.orEmpty(), 280).trim()
                detail.ifBlank { "The configured service could not complete that request." }
            }
            else -> "AURA recovered from an unexpected request error. Please try again."
        }
    }

    private object SpeechFailureSummary {
        fun forSpeech(message: String): String = when {
            message.contains("warming", ignoreCase = true) ->
                "The private model is still warming up. Please try again in a moment."
            message.contains("network", ignoreCase = true) ||
                message.contains("reach", ignoreCase = true) ->
                "I could not reach the service. Please check the connection."
            else -> "I could not complete that request. Please try again."
        }
    }

    private fun localReply(
        text: String,
        speech: String = text,
        provider: String = "local-production-core",
        intent: String
    ): ButlerReply = ButlerReply(
        assistantMessage = text,
        speechText = speech,
        pendingActions = emptyList(),
        memoryWrites = emptyList(),
        provider = provider,
        intent = intent
    )

    private fun totalTurnDeadlineMs(
        message: String,
        context: ConversationContext
    ): Long {
        val normalized = message.trim().lowercase(Locale.ROOT)
        return when {
            normalized.startsWith("exhaustive research ") -> 125_000L
            normalized.startsWith("deep research ") ||
                normalized.startsWith("research ") ||
                normalized.contains(" with sources") -> 95_000L
            normalized.contains("detailed news") ||
                normalized.contains("news in detail") -> 60_000L
            context.settings.aiEnabled && context.settings.localAiEnabled -> 95_000L
            else -> 45_000L
        }
    }

    private fun remainingTurnMs(deadlineAt: Long, phaseLimitMs: Long): Long {
        val remaining = (deadlineAt - SystemClock.elapsedRealtime()).coerceAtLeast(1L)
        return minOf(remaining, phaseLimitMs.coerceAtLeast(1L))
    }

    private fun deterministicTimeoutMs(message: String): Long {
        val normalized = message.trim().lowercase(Locale.ROOT)
        return when {
            normalized.startsWith("exhaustive research ") -> EXHAUSTIVE_RESEARCH_TURN_TIMEOUT_MS
            normalized.startsWith("deep research ") ||
                normalized.startsWith("research ") ||
                normalized.contains(" with sources") -> RESEARCH_TURN_TIMEOUT_MS
            normalized.contains("detailed news") ||
                normalized.contains("news in detail") -> DETAILED_NEWS_TIMEOUT_MS
            else -> DETERMINISTIC_TURN_TIMEOUT_MS
        }
    }

    private fun turnTimeoutMs(
        message: String,
        context: ConversationContext
    ): Long {
        val normalized = message.trim().lowercase(Locale.ROOT)
        return when {
            normalized.startsWith("exhaustive research ") -> EXHAUSTIVE_RESEARCH_TURN_TIMEOUT_MS
            normalized.startsWith("deep research ") -> RESEARCH_TURN_TIMEOUT_MS
            context.settings.aiEnabled && context.settings.localAiEnabled -> PRIVATE_AI_TURN_TIMEOUT_MS
            else -> TURN_TIMEOUT_MS
        }
    }

    private fun inferProvider(
        route: IntentRoute,
        context: ConversationContext
    ): String = when {
        route.intent in KEYLESS_INTENTS -> "keyless-internet"
        route.intent in CLOUD_ELIGIBLE_INTENTS ->
            HybridAiProvider.configuredProviderLabel(context.settings)
        else -> "local"
    }

    private fun String.containsAny(vararg values: String): Boolean =
        values.any { contains(it, ignoreCase = true) }

    private companion object {
        const val TURN_TIMEOUT_MS = 38_000L
        const val DETERMINISTIC_TURN_TIMEOUT_MS = 20_000L
        const val DETAILED_NEWS_TIMEOUT_MS = 45_000L
        const val PRIVATE_AI_TURN_TIMEOUT_MS = 90_000L
        const val RESEARCH_TURN_TIMEOUT_MS = 90_000L
        const val EXHAUSTIVE_RESEARCH_TURN_TIMEOUT_MS = 120_000L
        const val AGENT_TIMEOUT_MS = 45_000L
        const val COMMAND_REPAIR_TIMEOUT_MS = 20_000L
        const val MIN_COMMAND_REPAIR_CONFIDENCE = 0.72
        const val MAX_AGENT_STEPS = 5

        val KEYLESS_INTENTS = setOf(
            "ask_weather",
            "get_weather",
            "get_news",
            "search_wiki"
        )

        val CLOUD_ELIGIBLE_INTENTS = setOf(
            "general_query",
            "general_chat",
            "chitchat/greet",
            "out_of_scope",
            "generate_code",
            "translate_text",
            "read_pdf",
            "scan_image",
            "get_horoscope"
        )
    }
}
