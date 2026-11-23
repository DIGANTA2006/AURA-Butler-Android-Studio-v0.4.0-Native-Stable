package `in`.diganta.aurabutler.memory

import org.json.JSONArray
import org.json.JSONObject
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
    fun parserCreatesConceptOnlyFromExistingReferencedMemories() {
        val store = MemoryStore()
        val memory = SemanticMemory(null, LocalEmbeddingProvider("unused"), store)
        val entries = listOf(
            SemanticMemory.MemoryEntry(
                id = "memory-coroutines",
                content = "Kotlin coroutine cancellation propagates through child tasks.",
                label = "Coroutine cancellation"
            ),
            SemanticMemory.MemoryEntry(
                id = "memory-plans",
                content = "Task plans persist completed checkpoints for recovery.",
                label = "Plan recovery"
            )
        )
        val response = JSONObject()
            .put(
                "patterns",
                JSONArray().put(
                    JSONObject()
                        .put("theme", "Reliable execution")
                        .put("memoryIds", JSONArray().put("memory-coroutines").put("memory-plans"))
                        .put("confidence", 0.92)
                )
            )
            .put(
                "concepts",
                JSONArray().put(
                    JSONObject()
                        .put("name", "Execution safety")
                        .put(
                            "description",
                            "Reliable task execution preserves explicit cancellation state."
                        )
                        .put(
                            "relatedMemoryIds",
                            JSONArray().put("memory-coroutines").put("memory-plans")
                        )
                )
            )
            .toString()

        val result = memory.parsePatternResponse(response, entries)
        val concept = store.getAll().singleOrNull {
            it.source == SemanticMemory.MemorySource.CONSOLIDATED &&
                it.label == "Concept: Execution safety"
        }

        assertEquals(1, result.patternsFound)
        assertEquals(1, result.conceptsCreated)
        assertTrue("Expected exactly one validated concept.", concept != null)
        assertTrue(
            concept!!.metadata.relatedEntities.containsAll(
                listOf("Coroutine cancellation", "Plan recovery")
            )
        )
    }

    @Test
    fun parserRejectsConceptsThatReferenceUnknownMemoryIds() {
        val store = MemoryStore()
        val memory = SemanticMemory(null, LocalEmbeddingProvider("unused"), store)
        val entries = listOf(
            SemanticMemory.MemoryEntry(
                id = "memory-one",
                content = "A real memory with sufficient detail.",
                label = "Real memory"
            )
        )
        val response = JSONObject()
            .put(
                "patterns",
                JSONArray().put(
                    JSONObject()
                        .put("theme", "Unverified theme")
                        .put("memoryIds", JSONArray().put("memory-one").put("invented-memory"))
                        .put("confidence", 0.99)
                )
            )
            .put(
                "concepts",
                JSONArray().put(
                    JSONObject()
                        .put("name", "Unverified concept")
                        .put("description", "This concept references an invented memory identifier.")
                        .put(
                            "relatedMemoryIds",
                            JSONArray().put("memory-one").put("invented-memory")
                        )
                )
            )
            .toString()

        val result = memory.parsePatternResponse(response, entries)

        assertEquals(0, result.patternsFound)
        assertEquals(0, result.conceptsCreated)
        assertTrue(store.getAll().isEmpty())
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
