package `in`.diganta.aurabutler.data

import org.junit.Assert.assertEquals
import org.junit.Test

class DeepResearchProviderTest {
    @Test
    fun researchModesAreNormalized() {
        assertEquals("quick", DeepResearchProvider.normalizeMode("QUICK"))
        assertEquals("standard", DeepResearchProvider.normalizeMode("unknown"))
        assertEquals("deep", DeepResearchProvider.normalizeMode("deep"))
        assertEquals("exhaustive", DeepResearchProvider.normalizeMode("exhaustive"))
    }

    @Test
    fun explicitMessageDepthOverridesDefault() {
        assertEquals(
            "deep",
            DeepResearchProvider.requestedModeFromMessage(
                "deep research local llm tooling",
                "standard"
            )
        )
        assertEquals(
            "exhaustive",
            DeepResearchProvider.requestedModeFromMessage(
                "exhaustive research battery technology",
                "standard"
            )
        )
    }
}
