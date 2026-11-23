package `in`.diganta.aurabutler.data

import `in`.diganta.aurabutler.model.ConversationContext

/**
 * Common boundary for optional network reasoning providers.
 *
 * Implementations return null when disabled or not configured. Streaming is an
 * optional UX capability: providers that do not support it transparently fall
 * back to the normal answer path.
 */
interface AiProvider {
    suspend fun answer(
        message: String,
        context: ConversationContext
    ): String?

    suspend fun answerStreaming(
        message: String,
        context: ConversationContext,
        onDelta: (String) -> Unit
    ): String? = answer(message, context)
}
