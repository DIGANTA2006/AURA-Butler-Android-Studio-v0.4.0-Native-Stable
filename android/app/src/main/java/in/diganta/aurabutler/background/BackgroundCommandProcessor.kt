package `in`.diganta.aurabutler.background

import android.content.Context
import android.content.Intent
import android.util.Log
import `in`.diganta.aurabutler.data.AiResponseFormatter
import `in`.diganta.aurabutler.data.HybridAiProvider
import `in`.diganta.aurabutler.data.KeylessLiveInfoProvider
import `in`.diganta.aurabutler.data.SecureStore
import `in`.diganta.aurabutler.domain.ActionExecutor
import `in`.diganta.aurabutler.domain.ButlerPersona
import `in`.diganta.aurabutler.domain.DirectActionPolicy
import `in`.diganta.aurabutler.domain.ExecutionResult
import `in`.diganta.aurabutler.domain.LocalButlerEngine
import `in`.diganta.aurabutler.domain.ProductionButlerEngine
import `in`.diganta.aurabutler.model.ActionHistoryItem
import `in`.diganta.aurabutler.model.ButlerAction
import `in`.diganta.aurabutler.model.ChatMessage
import `in`.diganta.aurabutler.model.ConversationContext
import `in`.diganta.aurabutler.model.MemoryItem
import `in`.diganta.aurabutler.model.Sender
import `in`.diganta.aurabutler.runtime.ActionAuditLog
import `in`.diganta.aurabutler.runtime.CapabilityPolicy
import `in`.diganta.aurabutler.runtime.PolicyDecision
import `in`.diganta.aurabutler.voice.SpeechTextSanitizer

data class BackgroundCommandResult(
    val speech: String,
    val shouldSpeak: Boolean
)

class BackgroundCommandProcessor(private val context: Context) {
    private val store = SecureStore(context)
    private val localButler = LocalButlerEngine(
        liveInfo = KeylessLiveInfoProvider(context),
        generalAi = HybridAiProvider()
    )
    private val butler = ProductionButlerEngine(
        context = context,
        store = store,
        delegate = localButler
    )
    private val actionExecutor = ActionExecutor(context)
    private val capabilityPolicy = CapabilityPolicy()
    private val audit = ActionAuditLog(context)

    suspend fun process(text: String): BackgroundCommandResult {
        val messages = store.loadMessages()
        val memories = store.loadMemories()
        val settings = store.loadSettings()
        val reply = butler.chat(
            message = text,
            context = ConversationContext(
                recentMessages = messages.takeLast(16),
                memories = memories,
                settings = settings
            )
        )

        val directMessages = mutableListOf<String>()
        val directHistory = mutableListOf<ActionHistoryItem>()
        val approvalActions = mutableListOf<ButlerAction>()

        reply.pendingActions.forEach { action ->
            if (!DirectActionPolicy.canExecuteImmediately(action)) {
                approvalActions += action
                return@forEach
            }

            if (
                capabilityPolicy.evaluate(action, confirmed = true) ==
                PolicyDecision.DENY
            ) {
                val reason = "AURA blocked ${action.title} under the local safety policy."
                directMessages += reason
                directHistory += ActionHistoryItem(
                    title = action.title,
                    detail = reason,
                    approved = false
                )
                audit.record(
                    "action.direct.denied",
                    mapOf("kind" to action.kind.name, "title" to action.title)
                )
                return@forEach
            }

            when (val result = actionExecutor.execute(action)) {
                is ExecutionResult.Success -> {
                    directMessages += result.detail
                    directHistory += ActionHistoryItem(
                        title = action.title,
                        detail = result.detail,
                        approved = true
                    )
                    audit.record(
                        "action.direct.completed",
                        mapOf(
                            "kind" to action.kind.name,
                            "title" to action.title,
                            "detail" to result.detail,
                            "source" to "background-wake"
                        )
                    )
                    Log.i(TAG, "Direct action succeeded: ${action.kind}")
                }

                is ExecutionResult.Failure -> {
                    directMessages += result.reason
                    directHistory += ActionHistoryItem(
                        title = action.title,
                        detail = result.reason,
                        approved = false
                    )
                    audit.record(
                        "action.direct.failed",
                        mapOf(
                            "kind" to action.kind.name,
                            "title" to action.title,
                            "reason" to result.reason,
                            "source" to "background-wake"
                        )
                    )
                    Log.w(TAG, "Direct action failed: ${action.kind}: ${result.reason}")
                }
            }
        }

        val assistantText = resolvedAssistantText(
            original = reply.assistantMessage,
            hadActions = reply.pendingActions.isNotEmpty(),
            directMessages = directMessages,
            approvalActions = approvalActions
        )
        val speechText = resolvedAssistantText(
            original = reply.speechText,
            hadActions = reply.pendingActions.isNotEmpty(),
            directMessages = directMessages,
            approvalActions = approvalActions
        )
        val styledAssistantText = AiResponseFormatter.plainText(
            ButlerPersona.style(
                text = assistantText,
                intent = reply.intent,
                settings = settings,
                request = text
            )
        )
        val styledSpeechText = SpeechTextSanitizer.clean(
            ButlerPersona.style(
                text = speechText,
                intent = reply.intent,
                settings = settings,
                request = text
            )
        )

        val updatedMessages = (
            messages +
                ChatMessage(sender = Sender.USER, text = text) +
                ChatMessage(
                    sender = Sender.AURA,
                    text = styledAssistantText,
                    provider = reply.provider,
                    intent = reply.intent,
                    latencyMs = reply.latencyMs
                )
            ).takeLast(80)
        val updatedMemories = mergeMemories(memories, reply.memoryWrites)
        val updatedActions = (
            store.loadPendingActions() + approvalActions
            ).takeLast(20)
        val updatedHistory = (
            store.loadHistory() + directHistory
            ).takeLast(100)

        store.saveMessages(updatedMessages)
        store.saveMemories(updatedMemories)
        store.savePendingActions(updatedActions)
        store.saveHistory(updatedHistory)

        context.sendBroadcast(
            Intent(WakeWordService.ACTION_STATE_CHANGED)
                .setPackage(context.packageName)
        )

        return BackgroundCommandResult(
            speech = styledSpeechText,
            shouldSpeak = settings.spokenReplies
        )
    }

    private fun resolvedAssistantText(
        original: String,
        hadActions: Boolean,
        directMessages: List<String>,
        approvalActions: List<ButlerAction>
    ): String {
        val parts = mutableListOf<String>()

        if (!hadActions || approvalActions.isNotEmpty()) {
            original.trim().takeIf(String::isNotBlank)?.let(parts::add)
        }
        parts += directMessages.filter(String::isNotBlank)

        if (approvalActions.isNotEmpty()) {
            parts += "I prepared ${approvalActions.size} approval card. Open AURA to review it."
        }

        return parts
            .distinct()
            .joinToString(" ")
            .ifBlank { original }
    }

    private fun mergeMemories(
        current: List<MemoryItem>,
        writes: List<MemoryItem>
    ): List<MemoryItem> {
        var result = current
        writes.forEach { incoming ->
            result = result.filterNot {
                it.label.equals(incoming.label, ignoreCase = true)
            } + incoming
        }
        return result.takeLast(1_500)
    }

    private companion object {
        const val TAG = "AuraBackgroundAction"
    }
}
