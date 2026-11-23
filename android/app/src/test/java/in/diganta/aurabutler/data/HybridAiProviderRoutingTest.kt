package `in`.diganta.aurabutler.data

import `in`.diganta.aurabutler.model.ButlerSettings
import `in`.diganta.aurabutler.model.ConversationContext
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridAiProviderRoutingTest {
    @Test fun automaticRouteIsPrivateOnlyByDefault() {
        val router = HybridAiProvider()
        assertEquals(
            listOf("local"),
            router.providerOrder(ButlerSettings(aiProvider = "automatic"))
        )
    }

    @Test fun automaticRouteMayUsePublicProvidersOnlyAfterExplicitOptIn() {
        val router = HybridAiProvider()
        assertEquals(
            listOf("local", "gemini", "groq"),
            router.providerOrder(
                ButlerSettings(
                    aiProvider = "automatic",
                    allowPublicAiFallback = true
                )
            )
        )
    }

    @Test fun explicitGeminiFallsBackToPrivateQwenWithoutCrossPublicFallback() {
        val router = HybridAiProvider()
        assertEquals(
            listOf("gemini", "local"),
            router.providerOrder(ButlerSettings(aiProvider = "gemini"))
        )
    }

    @Test fun explicitGroqFallsBackToPrivateQwenWithoutCrossPublicFallback() {
        val router = HybridAiProvider()
        assertEquals(
            listOf("groq", "local"),
            router.providerOrder(ButlerSettings(aiProvider = "groq"))
        )
    }

    @Test fun explicitPublicProviderCanCrossFallbackOnlyWhenOptedIn() {
        val router = HybridAiProvider()
        assertEquals(
            listOf("gemini", "local", "groq"),
            router.providerOrder(
                ButlerSettings(
                    aiProvider = "gemini",
                    allowPublicAiFallback = true
                )
            )
        )
    }

    @Test fun explicitPrivateRemainsPrivateOnly() {
        val router = HybridAiProvider()
        assertEquals(
            listOf("local"),
            router.providerOrder(ButlerSettings(aiProvider = "local"))
        )
    }

    @Test
    fun streamingDoesNotFallbackAfterTheFirstProviderEmitsPartialText() = runBlocking {
        val partialProvider = PartialThenFailProvider()
        val fallbackProvider = CountingFallbackProvider()
        val router = HybridAiProvider(
            local = fallbackProvider,
            gemini = partialProvider,
            groq = CountingFallbackProvider()
        )
        val context = context(
            ButlerSettings(
                aiEnabled = true,
                aiProvider = "gemini",
                geminiApiKey = "test-gemini-key",
                localAiEnabled = true,
                localAiEndpoint = "http://127.0.0.1:11434/v1/chat/completions",
                localAiModel = "qwen3:4b"
            )
        )
        val visible = mutableListOf<String>()
        var failure: IOException? = null

        try {
            router.answerStreaming("Explain an Android foreground service.", context) {
                visible += it
            }
        } catch (error: IOException) {
            failure = error
        }

        assertNotNull("The failed partial stream must be surfaced.", failure)
        assertEquals(listOf("first provider partial"), visible)
        assertEquals("A fallback must not append unrelated text.", 0, fallbackProvider.calls)
    }

    @Test
    fun streamingFallsBackWhenTheFirstProviderFailsBeforeVisibleOutput() = runBlocking {
        val failingProvider = FailsBeforeOutputProvider()
        val fallbackProvider = CountingFallbackProvider()
        val router = HybridAiProvider(
            local = fallbackProvider,
            gemini = failingProvider,
            groq = CountingFallbackProvider()
        )
        val context = context(
            ButlerSettings(
                aiEnabled = true,
                aiProvider = "gemini",
                geminiApiKey = "test-gemini-key",
                localAiEnabled = true,
                localAiEndpoint = "http://127.0.0.1:11434/v1/chat/completions",
                localAiModel = "qwen3:4b"
            )
        )
        val visible = mutableListOf<String>()

        val result = router.answerStreaming("Explain Android lifecycle.", context) {
            visible += it
        }

        assertEquals("private fallback answer", result)
        assertEquals(listOf("private fallback answer"), visible)
        assertEquals(1, fallbackProvider.calls)
    }

    @Test
    fun repeatedProviderFailuresOpenCircuitAndSkipRepeatedAttempts() = runBlocking {
        val failingProvider = AlwaysFailingProvider()
        val fallbackProvider = CountingFallbackProvider()
        val router = HybridAiProvider(
            local = fallbackProvider,
            gemini = failingProvider,
            groq = CountingFallbackProvider()
        )
        val context = context(
            ButlerSettings(
                aiEnabled = true,
                aiProvider = "gemini",
                geminiApiKey = "test-gemini-key",
                localAiEnabled = true,
                localAiEndpoint = "http://127.0.0.1:11434/v1/chat/completions",
                localAiModel = "qwen3:4b"
            )
        )

        repeat(3) {
            assertEquals("private fallback answer", router.answer("Explain Android.", context))
        }

        assertEquals("The circuit should stop repeated failing Gemini calls.", 2, failingProvider.calls)
        assertEquals(3, fallbackProvider.calls)
    }

    private fun context(settings: ButlerSettings) = ConversationContext(
        recentMessages = emptyList(),
        memories = emptyList(),
        settings = settings
    )

    private class PartialThenFailProvider : AiProvider {
        override suspend fun answer(
            message: String,
            context: ConversationContext
        ): String? = throw IOException("network lost")

        override suspend fun answerStreaming(
            message: String,
            context: ConversationContext,
            onDelta: (String) -> Unit
        ): String? {
            onDelta("first provider partial")
            throw IOException("network lost after first delta")
        }
    }

    private class FailsBeforeOutputProvider : AiProvider {
        override suspend fun answer(
            message: String,
            context: ConversationContext
        ): String? = throw IOException("temporarily unavailable")

        override suspend fun answerStreaming(
            message: String,
            context: ConversationContext,
            onDelta: (String) -> Unit
        ): String? = throw IOException("temporarily unavailable")
    }

    private class AlwaysFailingProvider : AiProvider {
        var calls = 0
            private set

        override suspend fun answer(
            message: String,
            context: ConversationContext
        ): String? {
            calls += 1
            throw IOException("connection timeout")
        }
    }

    private class CountingFallbackProvider : AiProvider {
        var calls = 0
            private set

        override suspend fun answer(
            message: String,
            context: ConversationContext
        ): String {
            calls += 1
            return "private fallback answer"
        }
    }
}
