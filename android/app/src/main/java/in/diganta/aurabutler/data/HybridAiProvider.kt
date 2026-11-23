package `in`.diganta.aurabutler.data

import `in`.diganta.aurabutler.model.ButlerSettings
import `in`.diganta.aurabutler.model.ConversationContext
import `in`.diganta.aurabutler.runtime.AiWorkload
import `in`.diganta.aurabutler.runtime.StudentBudgetGuard
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.CancellationException

/** Private-Qwen-first optional AI router with explicit public-provider consent. */
class HybridAiProvider(
    private val local: AiProvider = OpenAiCompatibleProvider(),
    private val gemini: AiProvider = GeminiAiProvider(),
    private val groq: AiProvider = GroqAiProvider()
) : AiProvider {
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
    ): String? = runProviders(message, context) { provider ->
        provider.answerStreaming(message, context, onDelta)
    }

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
            if (isCoolingDown(candidate)) {
                errors += "${displayName(candidate)}: temporarily skipped after a recent quota/rate-limit failure"
                continue
            }

            val provider = when (candidate) {
                PROVIDER_LOCAL -> local
                PROVIDER_GEMINI -> gemini
                PROVIDER_GROQ -> groq
                else -> continue
            }

            val lease = StudentBudgetGuard.tryReserve(
                workload = AiWorkload.TEXT,
                endpoint = meteredEndpoint(candidate, settings)
            )
            if (!lease.allowed) {
                errors += "${displayName(candidate)}: ${lease.message}"
                continue
            }

            try {
                val result = call(provider)?.takeIf(String::isNotBlank)
                if (result != null) {
                    StudentBudgetGuard.commit(lease)
                    return result
                }
                StudentBudgetGuard.release(lease)
                errors += "${displayName(candidate)}: returned no usable answer"
            } catch (cancelled: CancellationException) {
                StudentBudgetGuard.release(lease)
                throw cancelled
            } catch (error: Exception) {
                StudentBudgetGuard.release(lease)
                noteTransientFailure(candidate, error)
                errors += "${displayName(candidate)}: " +
                    (error.message ?: "request failed")
            }
        }

        throw IOException(
            errors.joinToString(" Then ").ifBlank {
                "The configured optional AI provider could not answer."
            }
        )
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

    private fun isCoolingDown(provider: String): Boolean {
        val now = System.currentTimeMillis()
        return when (provider) {
            PROVIDER_GEMINI -> now < geminiCooldownUntilMs
            PROVIDER_GROQ -> now < groqCooldownUntilMs
            else -> false
        }
    }

    private fun noteTransientFailure(provider: String, error: Exception) {
        val detail = error.message.orEmpty().lowercase(Locale.ROOT)
        val quotaLike = listOf(
            "quota", "rate limit", "too many requests", "temporarily unavailable"
        ).any(detail::contains)
        if (!quotaLike) return

        val until = System.currentTimeMillis() + PROVIDER_COOLDOWN_MS
        when (provider) {
            PROVIDER_GEMINI -> geminiCooldownUntilMs = until
            PROVIDER_GROQ -> groqCooldownUntilMs = until
        }
    }

    private fun displayName(provider: String): String = when (provider) {
        PROVIDER_LOCAL -> "Private Qwen"
        PROVIDER_GEMINI -> "Gemini"
        PROVIDER_GROQ -> "Groq"
        else -> "AI"
    }

    private fun meteredEndpoint(
        provider: String,
        settings: ButlerSettings
    ): String = when (provider) {
        PROVIDER_LOCAL -> settings.localAiEndpoint
        PROVIDER_GEMINI -> "https://generativelanguage.googleapis.com"
        PROVIDER_GROQ -> "https://api.groq.com"
        else -> "https://external-ai.invalid"
    }

    companion object {
        const val PROVIDER_AUTOMATIC = "automatic"
        const val PROVIDER_LOCAL = "local"
        const val PROVIDER_GEMINI = "gemini"
        const val PROVIDER_GROQ = "groq"

        @Volatile private var geminiCooldownUntilMs: Long = 0L
        @Volatile private var groqCooldownUntilMs: Long = 0L
        private const val PROVIDER_COOLDOWN_MS = 10 * 60 * 1_000L

        fun normalizeProvider(value: String): String = when (
            value.trim().lowercase(Locale.ROOT)
        ) {
            PROVIDER_LOCAL -> PROVIDER_LOCAL
            PROVIDER_GEMINI -> PROVIDER_GEMINI
            PROVIDER_GROQ -> PROVIDER_GROQ
            else -> PROVIDER_AUTOMATIC
        }

        fun publicProviderAllowed(
            settings: ButlerSettings,
            provider: String = PROVIDER_GEMINI
        ): Boolean {
            val selected = normalizeProvider(settings.aiProvider)
            return when (provider) {
                PROVIDER_GEMINI -> selected == PROVIDER_GEMINI || settings.allowPublicAiFallback
                PROVIDER_GROQ -> selected == PROVIDER_GROQ || settings.allowPublicAiFallback
                else -> false
            }
        }

        fun configuredProviderLabel(settings: ButlerSettings): String {
            if (!settings.aiEnabled) return "local-only"
            val configured = buildList {
                if (settings.localAiEnabled && settings.localAiEndpoint.isNotBlank()) {
                    add("private-qwen")
                }
                if (
                    settings.geminiApiKey.isNotBlank() &&
                    publicProviderAllowed(settings, PROVIDER_GEMINI)
                ) {
                    add("gemini")
                }
                if (
                    settings.groqApiKey.isNotBlank() &&
                    publicProviderAllowed(settings, PROVIDER_GROQ)
                ) {
                    add("groq")
                }
            }
            return when (normalizeProvider(settings.aiProvider)) {
                PROVIDER_LOCAL -> configured.firstOrNull { it == "private-qwen" }
                PROVIDER_GEMINI -> configured.firstOrNull { it == "gemini" }
                PROVIDER_GROQ -> configured.firstOrNull { it == "groq" }
                else -> configured.joinToString("→").takeIf(String::isNotBlank)
            } ?: "local-only"
        }
    }
}
