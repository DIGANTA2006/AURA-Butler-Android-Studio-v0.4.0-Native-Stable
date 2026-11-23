package \`in\`.diganta.aurabutler.data

import \`in\`.diganta.aurabutler.model.ButlerSettings
import \`in\`.diganta.aurabutler.model.ConversationContext
import \`in\`.diganta.aurabutler.runtime.SecretRedactor
import java.io.IOException
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * Privacy-aware optional AI router.
 *
 * Each concrete provider owns its own budget lease. The router never reserves
 * a second quota unit around Gemini/Groq/private-Qwen calls.
 */
class HybridAiProvider(
    private val local: AiProvider = OpenAiCompatibleProvider(),
    private val gemini: AiProvider = GeminiAiProvider(),
    private val groq: AiProvider = GroqAiProvider()
) : AiProvider {
    private val circuitBreakers = mapOf(
        PROVIDER_LOCAL to ProviderCircuitBreaker(),
        PROVIDER_GEMINI to ProviderCircuitBreaker(),
        PROVIDER_GROQ to ProviderCircuitBreaker()
    )

    override suspend fun answer(
        message: String,
        context: ConversationContext
    ): String? = runProviders(message, context) { provider ->
        provider.answer(message, context)
    }

    override suspend fun answerStreaming(
        message: String,
        context: ConversationContext,
        onDelta: (String) -> Unit
    ): String? = runStreamingProviders(message, context, onDelta)

    private suspend fun runProviders(
        message: String,
        context: ConversationContext,
        call: suspend (AiProvider) -> String?
    ): String? {
        val settings = context.settings
        if (!settings.aiEnabled) return null
        val candidates = providerOrder(settings).filter { configured(it, settings) }
        if (candidates.isEmpty()) return null

        val errors = mutableListOf<String>()
        for (candidate in candidates) {
            val circuit = circuitBreakers.getValue(candidate)
            if (!circuit.tryAcquire()) {
                errors += displayName(candidate) + ": temporarily unavailable; " +
                    "retry window " + circuit.remainingSeconds() + "s"
                continue
            }

            val provider = providerFor(candidate)
            try {
                val result = withTimeout(timeoutFor(candidate)) {
                    call(provider)?.takeIf(String::isNotBlank)
                }
                if (result != null) {
                    circuit.recordSuccess()
                    return result
                }

                circuit.recordFailure(System.currentTimeMillis(), quotaLike = false)
                errors += displayName(candidate) + ": returned no usable answer"
            } catch (timeout: TimeoutCancellationException) {
                circuit.recordFailure(System.currentTimeMillis(), quotaLike = false)
                errors += displayName(candidate) + ": request exceeded its provider deadline"
            } catch (cancelled: CancellationException) {
                circuit.abandonProbe()
                throw cancelled
            } catch (error: Exception) {
                circuit.recordFailure(
                    System.currentTimeMillis(),
                    quotaLike = isQuotaFailure(error)
                )
                errors += displayName(candidate) + ": " + safeFailure(error)
            }
        }

        throw IOException(
            errors.joinToString(" Then ").ifBlank {
                "The configured optional AI provider could not answer."
            }
        )
    }

    /**
     * A streaming provider may fail after displaying partial text. Falling
     * through to a second provider after that point would concatenate unrelated
     * completions in the chat UI. Therefore fallback is permitted only before
     * the current provider emits its first visible delta.
     */
    private suspend fun runStreamingProviders(
        message: String,
        context: ConversationContext,
        onDelta: (String) -> Unit
    ): String? {
        val settings = context.settings
        if (!settings.aiEnabled) return null
        val candidates = providerOrder(settings).filter { configured(it, settings) }
        if (candidates.isEmpty()) return null

        val errors = mutableListOf<String>()
        for (candidate in candidates) {
            val circuit = circuitBreakers.getValue(candidate)
            if (!circuit.tryAcquire()) {
                errors += displayName(candidate) + ": temporarily unavailable; " +
                    "retry window " + circuit.remainingSeconds() + "s"
                continue
            }

            val provider = providerFor(candidate)
            val attemptActive = AtomicBoolean(true)
            val emittedVisibleText = AtomicBoolean(false)
            val emittedCharacters = AtomicInteger(0)

            try {
                val result = withTimeout(timeoutFor(candidate)) {
                    provider.answerStreaming(message, context) { delta ->
                        if (attemptActive.get() && delta.isNotBlank()) {
                            val total = emittedCharacters.addAndGet(delta.length)
                            if (total > MAX_STREAM_CHARACTERS) {
                                throw IOException(
                                    "The AI provider stream exceeded AURA's output limit."
                                )
                            }
                            emittedVisibleText.set(true)
                            onDelta(delta)
                        }
                    }?.takeIf(String::isNotBlank)
                }
                attemptActive.set(false)

                if (result != null) {
                    circuit.recordSuccess()
                    // Some providers use the default non-streaming interface.
                    if (!emittedVisibleText.get()) onDelta(result)
                    return result
                }

                circuit.recordFailure(System.currentTimeMillis(), quotaLike = false)
                errors += displayName(candidate) + ": returned no usable answer"
                if (emittedVisibleText.get()) {
                    throw IOException(
                        displayName(candidate) +
                            " ended after partial output; fallback withheld to prevent mixed answers."
                    )
                }
            } catch (timeout: TimeoutCancellationException) {
                attemptActive.set(false)
                circuit.recordFailure(System.currentTimeMillis(), quotaLike = false)
                if (emittedVisibleText.get()) {
                    throw IOException(
                        displayName(candidate) +
                            " timed out after partial output; retry the turn to avoid duplicated text.",
                        timeout
                    )
                }
                errors += displayName(candidate) + ": request exceeded its provider deadline"
            } catch (cancelled: CancellationException) {
                attemptActive.set(false)
                circuit.abandonProbe()
                throw cancelled
            } catch (error: Exception) {
                attemptActive.set(false)
                circuit.recordFailure(
                    System.currentTimeMillis(),
                    quotaLike = isQuotaFailure(error)
                )
                if (emittedVisibleText.get()) {
                    throw IOException(
                        displayName(candidate) +
                            " failed after partial output; fallback withheld to prevent mixed answers.",
                        error
                    )
                }
                errors += displayName(candidate) + ": " + safeFailure(error)
            }
        }

        throw IOException(
            errors.joinToString(" Then ").ifBlank {
                "The configured optional AI provider could not answer."
            }
        )
    }

    private fun providerFor(name: String): AiProvider = when (name) {
        PROVIDER_LOCAL -> local
        PROVIDER_GEMINI -> gemini
        PROVIDER_GROQ -> groq
        else -> throw IllegalArgumentException("Unknown AI provider.")
    }

    private fun timeoutFor(provider: String): Long = when (provider) {
        PROVIDER_LOCAL -> LOCAL_PROVIDER_TIMEOUT_MS
        PROVIDER_GEMINI -> PUBLIC_PROVIDER_TIMEOUT_MS
        PROVIDER_GROQ -> PUBLIC_PROVIDER_TIMEOUT_MS
        else -> PUBLIC_PROVIDER_TIMEOUT_MS
    }

    private fun safeFailure(error: Exception): String {
        val detail = error.message.orEmpty()
            .replace('\n', ' ')
            .replace('\r', ' ')
            .take(320)
        return SecretRedactor.redact(
            detail.ifBlank { error.javaClass.simpleName },
            240
        )
    }

    private fun isQuotaFailure(error: Exception): Boolean {
        val detail = error.message.orEmpty().lowercase(Locale.ROOT)
        return listOf(
            "quota",
            "rate limit",
            "too many requests",
            "429",
            "resource_exhausted"
        ).any(detail::contains)
    }

    internal fun providerOrder(settings: ButlerSettings): List<String> {
        val allowCrossPublicFallback = settings.allowPublicAiFallback
        return when (normalizeProvider(settings.aiProvider)) {
            PROVIDER_LOCAL -> listOf(PROVIDER_LOCAL)

            // Selecting Gemini or Groq is explicit consent for that provider.
            // Private Qwen remains the first fallback. The other public provider
            // is considered only when the separate public-fallback switch is on.
            PROVIDER_GEMINI -> buildList {
                add(PROVIDER_GEMINI)
                add(PROVIDER_LOCAL)
                if (allowCrossPublicFallback) add(PROVIDER_GROQ)
            }

            PROVIDER_GROQ -> buildList {
                add(PROVIDER_GROQ)
                add(PROVIDER_LOCAL)
                if (allowCrossPublicFallback) add(PROVIDER_GEMINI)
            }

            else -> buildList {
                add(PROVIDER_LOCAL)
                if (allowCrossPublicFallback) {
                    add(PROVIDER_GEMINI)
                    add(PROVIDER_GROQ)
                }
            }
        }
    }

    internal fun configured(provider: String, settings: ButlerSettings): Boolean =
        when (provider) {
            PROVIDER_LOCAL -> settings.localAiEnabled &&
                settings.localAiEndpoint.isNotBlank() &&
                settings.localAiModel.isNotBlank()
            PROVIDER_GEMINI -> settings.geminiApiKey.isNotBlank()
            PROVIDER_GROQ -> settings.groqApiKey.isNotBlank()
            else -> false
        }

    private fun displayName(provider: String): String = when (provider) {
        PROVIDER_LOCAL -> "Private Qwen"
        PROVIDER_GEMINI -> "Gemini"
        PROVIDER_GROQ -> "Groq"
        else -> "AI"
    }

    companion object {
        const val PROVIDER_AUTOMATIC = "automatic"
        const val PROVIDER_LOCAL = "local"
        const val PROVIDER_GEMINI = "gemini"
        const val PROVIDER_GROQ = "groq"

        private const val LOCAL_PROVIDER_TIMEOUT_MS = 125_000L
        private const val PUBLIC_PROVIDER_TIMEOUT_MS = 40_000L
        private const val MAX_STREAM_CHARACTERS = 500_000
    }
}

/**
 * Per-router circuit breaker with one half-open recovery probe after cooldown.
 * It deliberately keeps no response or credential data.
 */
private class ProviderCircuitBreaker {
    private var consecutiveFailures = 0
    private var openUntilMs = 0L
    private var halfOpenProbeInFlight = false

    @Synchronized
    fun tryAcquire(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (openUntilMs == 0L) return true
        if (nowMs < openUntilMs) return false
        if (halfOpenProbeInFlight) return false
        halfOpenProbeInFlight = true
        return true
    }

    @Synchronized
    fun recordSuccess() {
        consecutiveFailures = 0
        openUntilMs = 0L
        halfOpenProbeInFlight = false
    }

    @Synchronized
    fun recordFailure(nowMs: Long, quotaLike: Boolean) {
        consecutiveFailures = (consecutiveFailures + 1).coerceAtMost(8)
        halfOpenProbeInFlight = false

        val delayMs = if (quotaLike) {
            QUOTA_COOLDOWN_MS
        } else if (consecutiveFailures >= FAILURE_THRESHOLD) {
            val exponent = (consecutiveFailures - FAILURE_THRESHOLD).coerceIn(0, 4)
            (BASE_COOLDOWN_MS * (1L shl exponent)).coerceAtMost(MAX_COOLDOWN_MS)
        } else {
            0L
        }
        if (delayMs > 0L) openUntilMs = nowMs + delayMs
    }

    @Synchronized
    fun abandonProbe() {
        if (openUntilMs > 0L) halfOpenProbeInFlight = false
    }

    @Synchronized
    fun remainingSeconds(nowMs: Long = System.currentTimeMillis()): Long =
        ((openUntilMs - nowMs).coerceAtLeast(0L) + 999L) / 1_000L

    private companion object {
        const val FAILURE_THRESHOLD = 2
        const val BASE_COOLDOWN_MS = 30_000L
        const val MAX_COOLDOWN_MS = 10 * 60_000L
        const val QUOTA_COOLDOWN_MS = 10 * 60_000L
    }
}
