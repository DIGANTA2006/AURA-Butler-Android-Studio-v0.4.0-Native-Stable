package `in`.diganta.aurabutler.data

import `in`.diganta.aurabutler.model.ButlerSettings
import org.junit.Assert.assertEquals
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
}
