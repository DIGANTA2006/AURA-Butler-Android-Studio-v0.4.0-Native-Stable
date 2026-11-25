package `in`.diganta.aurabutler.runtime

import `in`.diganta.aurabutler.model.ButlerSettings
import org.junit.Assert.*
import org.junit.Test

class ExecutionPolicyEngineTest {

    private val engine = ExecutionPolicyEngine()
    private val settings = ButlerSettings()

    @Test
    fun testDeviceOnlyLocalCommand() {
        val mode = engine.resolve(OperatingMode.DEVICE_ONLY, "set a timer for 10 minutes", settings, "set_timer")
        assertEquals(ExecutionMode.LOCAL_COMMAND, mode)
    }

    @Test
    fun testDeviceOnlyLocalReasoning() {
        val mode = engine.resolve(OperatingMode.DEVICE_ONLY, "calculate 2 + 2", settings, "calculate")
        assertEquals(ExecutionMode.LOCAL_REASONING, mode)
    }

    @Test
    fun testDeviceOnlyCloudIntentFallsBackToWebSearch() {
        val mode = engine.resolve(OperatingMode.DEVICE_ONLY, "explain quantum computing", settings, "general_query")
        assertEquals(ExecutionMode.WEB_SEARCH_FALLBACK, mode)
    }

    @Test
    fun testPrivateCloudRoutesToPrivateAi() {
        val privateSettings = settings.copy(
            localAiEnabled = true,
            localAiEndpoint = "https://example.com/v1/chat/completions",
            localAiModel = "Qwen/Qwen3-8B"
        )
        val mode = engine.resolve(OperatingMode.PRIVATE_CLOUD, "explain quantum computing", privateSettings, "general_query")
        assertEquals(ExecutionMode.PRIVATE_AI_STREAMING, mode)
    }

    @Test
    fun testPrivateCloudVisionRoutesToBlocking() {
        val privateSettings = settings.copy(
            privateVisionEnabled = true,
            privateVisionEndpoint = "https://example.com/v1/chat/completions",
            privateVisionModel = "google/gemma-3-4b-it"
        )
        val mode = engine.resolve(OperatingMode.PRIVATE_CLOUD, "analyze this image", privateSettings, "analyze_image")
        assertEquals(ExecutionMode.PRIVATE_AI_BLOCKING, mode)
    }

    @Test
    fun testPrivateCloudResearchRoutesToResearch() {
        val privateSettings = settings.copy(
            researchEnabled = true,
            researchEndpoint = "https://example.com/v1/research"
        )
        val mode = engine.resolve(OperatingMode.PRIVATE_CLOUD, "deep research on AI", privateSettings, "deep_research")
        assertEquals(ExecutionMode.PRIVATE_RESEARCH, mode)
    }

    @Test
    fun testAutomaticPrefersLocalThenPrivateThenPublic() {
        // Local command -> LOCAL_COMMAND
        var mode = engine.resolve(OperatingMode.AUTOMATIC, "set a timer", settings, "set_timer")
        assertEquals(ExecutionMode.LOCAL_COMMAND, mode)

        // Local reasoning -> LOCAL_REASONING
        mode = engine.resolve(OperatingMode.AUTOMATIC, "calculate 2+2", settings, "calculate")
        assertEquals(ExecutionMode.LOCAL_REASONING, mode)

        // With private Qwen configured -> PRIVATE_AI_STREAMING
        val privateSettings = settings.copy(
            localAiEnabled = true,
            localAiEndpoint = "https://example.com/v1/chat/completions",
            localAiModel = "Qwen/Qwen3-8B"
        )
        mode = engine.resolve(OperatingMode.AUTOMATIC, "explain quantum computing", privateSettings, "general_query")
        assertEquals(ExecutionMode.PRIVATE_AI_STREAMING, mode)

        // With public provider allowed -> PUBLIC_AI
        val publicSettings = privateSettings.copy(
            geminiApiKey = "test-key",
            allowPublicAiFallback = true
        )
        mode = engine.resolve(OperatingMode.AUTOMATIC, "explain quantum computing", publicSettings, "general_query")
        assertEquals(ExecutionMode.PRIVATE_AI_STREAMING, mode) // Still prefers private
    }

    @Test
    fun testPublicCloudRequiresExplicitConsent() {
        val publicSettings = settings.copy(
            geminiApiKey = "test-key",
            allowPublicAiFallback = true
        )
        val mode = engine.resolve(OperatingMode.PUBLIC_CLOUD, "explain quantum computing", publicSettings, "general_query")
        assertEquals(ExecutionMode.PUBLIC_AI, mode)

        // Without consent -> WEB_SEARCH_FALLBACK
        val noConsentSettings = settings.copy(geminiApiKey = "")
        val mode2 = engine.resolve(OperatingMode.PUBLIC_CLOUD, "explain quantum computing", noConsentSettings, "general_query")
        assertEquals(ExecutionMode.WEB_SEARCH_FALLBACK, mode2)
    }
}
