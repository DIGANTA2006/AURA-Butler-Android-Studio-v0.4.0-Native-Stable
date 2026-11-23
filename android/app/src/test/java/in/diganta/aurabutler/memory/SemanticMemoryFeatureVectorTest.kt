package `in`.diganta.aurabutler.memory

import `in`.diganta.aurabutler.data.AiProvider
import `in`.diganta.aurabutler.model.ButlerSettings
import `in`.diganta.aurabutler.model.ConversationContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticMemoryFeatureVectorTest {
    @Test
    fun localVectorsAreDeterministicNormalizedAndPreserveLexicalOverlap() {
        val provider = LocalEmbeddingProvider("assets/embeddings/all-MiniLM-L6-v2.onnx")
        val first = provider.embed("AURA offline voice assistant memory")
        val repeated = provider.embed("AURA offline voice assistant memory")
        val related = provider.embed("offline voice assistant for AURA")
        val unrelated = provider.embed("circuit analysis mathematics homework")

        assertFalse(provider.isModelBacked)
        assertEquals(384, provider.dimension)
        assertEquals(first.toList(), repeated.toList())
        assertTrue(cosine(first, related) > cosine(first, unrelated))
        assertTrue(kotlin.math.abs(kotlin.math.sqrt(first.sumOf { it.toDouble() * it }) - 1.0) < 1e-5)
    }

    @Test
    fun consolidationCreatesConceptsOnlyFromVerifiedExistingMemoryLinks() = runBlocking {
        val settings = ButlerSettings(aiEnabled = true)
        val context = ConversationContext(
            recentMessages = emptyList(),
            memories = emptyList(),
            settings = settings
        )
        val store = MemoryStore()
        val provider = LocalEmbeddingProvider("unused")
        lateinit var entries: List<SemanticMemory.MemoryEntry>

        val ai = object : AiProvider {
            override suspend fun answer(
                message: String,
                context: ConversationContext
            ): String? {
                if (!message.startsWith("Analyze these memory entries")) {
                    return """{"relatedEntities":[],"temporalTags":[],"sentiment":0,"confidence":0.6}"""
                }
                val ids = entries
                val payload = org.json.JSONObject()
                    .put(
                        "patterns",
                        org.json.JSONArray().put(
                            org.json.JSONObject()
                                .put("theme", "Reliable execution")
                                .put("memoryIds", org.json.JSONArray().put(ids[0].id).put(ids[4].id))
                                .put("confidence", 0.92)
                        )
                    )
                    .put(
                        "concepts",
                        org.json.JSONArray().put(
                            org.json.JSONObject()
                                .put("name", "Execution safety")
                                .put(
                                    "description",
                                    "Reliable task execution preserves explicit cancellation state."
                                )
                                .put(
                                    "relatedMemoryIds",
                                    org.json.JSONArray().put(ids[0].id).put(ids[4].id)
                                )
                        )
                    )
                return payload.toString()
            }
        }

        val memory = SemanticMemory(ai, provider, store)
        entries = listOf(
            memory.remember(
                "Kotlin coroutine cancellation should propagate through child tasks.",
                "Coroutine cancellation",
                context
            ),
            memory.remember(
                "Android foreground service keeps the wake word detector alive.",
                "Wake service",
                context
            ),
            memory.remember(
                "Private memory uses AES GCM with Android Keystore encryption.",
                "Private storage",
                context
            ),
            memory.remember(
                "Network requests use bounded timeouts and response sizes.",
                "Network bounds",
                context
            ),
            memory.remember(
                "Task plans should persist completed checkpoints for recovery.",
                "Plan recovery",
                context
            )
        )

        val result = memory.consolidate()
        println("SEMANTIC_MEMORY_TEST_DIAGNOSTIC result=" + result + "; labels=" + store.getAll().map { it.label })
        val concept = store.getAll().firstOrNull {
            it.source == SemanticMemory.MemorySource.CONSOLIDATED &&
                it.label == "Concept: Execution safety"
        }

        assertEquals("No valid pattern was parsed; check the provider prompt and response.", 1, result.patternsFound)
        assertEquals("No concept passed validation or was stored.", 1, result.conceptsCreated)
        assertTrue("Expected a stored linked concept.", concept != null)
        assertTrue("Stored concept does not contain the expected source-memory labels.", concept!!.metadata.relatedEntities.containsAll(listOf("Coroutine cancellation", "Plan recovery")))
    }

    private fun cosine(a: FloatArray, b: FloatArray): Double {
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (index in a.indices) {
            dot += a[index] * b[index]
            normA += a[index] * a[index]
            normB += b[index] * b[index]
        }
        return if (normA > 0.0 && normB > 0.0) {
            dot / (kotlin.math.sqrt(normA) * kotlin.math.sqrt(normB))
        } else 0.0
    }
}
