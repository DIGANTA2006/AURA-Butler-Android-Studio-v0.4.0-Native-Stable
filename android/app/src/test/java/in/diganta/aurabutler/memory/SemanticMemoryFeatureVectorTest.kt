package `in`.diganta.aurabutler.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import `in`.diganta.aurabutler.model.ButlerSettings
import `in`.diganta.aurabutler.model.ConversationContext
import kotlinx.coroutines.runBlocking

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
    fun validatorCreatesConceptOnlyFromExistingReferencedMemories() {
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

        val result = memory.validateAndStorePatterns(
            proposedPatterns = listOf(
                SemanticMemory.PatternCandidate(
                    theme = "Reliable execution",
                    memoryIds = setOf("memory-coroutines", "memory-plans"),
                    confidence = 0.92
                )
            ),
            proposedConcepts = listOf(
                SemanticMemory.ConceptCandidate(
                    name = "Execution safety",
                    description = "Reliable task execution preserves explicit cancellation state.",
                    relatedMemoryIds = setOf("memory-coroutines", "memory-plans")
                )
            ),
            entries = entries
        )
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
    fun validatorRejectsConceptsThatReferenceUnknownMemoryIds() {
        val store = MemoryStore()
        val memory = SemanticMemory(null, LocalEmbeddingProvider("unused"), store)
        val entries = listOf(
            SemanticMemory.MemoryEntry(
                id = "memory-one",
                content = "A real memory with sufficient detail.",
                label = "Real memory"
            )
        )

        val result = memory.validateAndStorePatterns(
            proposedPatterns = listOf(
                SemanticMemory.PatternCandidate(
                    theme = "Unverified theme",
                    memoryIds = setOf("memory-one", "invented-memory"),
                    confidence = 0.99
                )
            ),
            proposedConcepts = listOf(
                SemanticMemory.ConceptCandidate(
                    name = "Unverified concept",
                    description = "This concept references an invented memory identifier.",
                    relatedMemoryIds = setOf("memory-one", "invented-memory")
                )
            ),
            entries = entries
        )

        assertEquals(0, result.patternsFound)
        assertEquals(0, result.conceptsCreated)
        assertTrue(store.getAll().isEmpty())
    }

    @Test
    fun memoryStoreEvictsLeastRecentlyUsedEntryAtConfiguredCapacity() {
        val store = MemoryStore(maxEntries = 2)
        val first = SemanticMemory.MemoryEntry(
            content = "First memory entry with content.",
            label = "First"
        )
        val second = SemanticMemory.MemoryEntry(
            content = "Second memory entry with content.",
            label = "Second"
        )
        val third = SemanticMemory.MemoryEntry(
            content = "Third memory entry with content.",
            label = "Third"
        )

        store.save(first)
        store.save(second)
        assertEquals(first.id, store.get(first.id)?.id)
        store.save(third)

        assertEquals(2, store.size())
        assertEquals(first.id, store.get(first.id)?.id)
        assertNull(store.get(second.id))
        assertEquals(third.id, store.get(third.id)?.id)
    }

    @Test
    fun invalidTimeRangeReturnsNoResultsInsteadOfRemovingTheFilter() = runBlocking {
        val provider = LocalEmbeddingProvider("unused")
        val store = MemoryStore()
        store.save(
            SemanticMemory.MemoryEntry(
                content = "AURA voice interruption memory.",
                label = "Voice interruption",
                embedding = provider.embed("AURA voice interruption memory"),
                createdAt = 10L
            )
        )
        val memory = SemanticMemory(null, provider, store)
        val context = ConversationContext(
            recentMessages = emptyList(),
            memories = emptyList(),
            settings = ButlerSettings()
        )

        val result = memory.recall(
            query = "voice interruption",
            context = context,
            options = SemanticMemory.RecallOptions(
                timeRange = SemanticMemory.TimeRange(start = 20L, end = 10L)
            )
        )

        assertTrue(result.entries.isEmpty())
        assertEquals(1, result.totalCandidates)
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
