package `in`.diganta.aurabutler.memory

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MemoryStorePersistenceTest {
    @Test
    fun restoredStoreRehydratesTextMetadataTagsAndEmbeddingVector() {
        val persistence = FakePersistence()
        val first = MemoryStore(persistence = persistence)
        val entry = SemanticMemory.MemoryEntry(
            id = "memory-onnx-1",
            content = "AURA uses on-device semantic retrieval.",
            label = "Architecture note",
            embedding = floatArrayOf(0.25f, -0.5f, 0.75f),
            metadata = SemanticMemory.MemoryMetadata(
                context = "test context",
                relatedEntities = listOf("AURA", "ONNX"),
                temporalTags = listOf("today"),
                sentiment = 0.25,
                confidence = 0.92
            ),
            accessCount = 4,
            importance = 0.8,
            tags = setOf("research", "architecture"),
            source = SemanticMemory.MemorySource.USER_INPUT
        )

        first.save(entry)
        val restored = MemoryStore(persistence = persistence)
        val actual = restored.get(entry.id)

        assertEquals(entry.content, actual?.content)
        assertEquals(entry.label, actual?.label)
        assertEquals(entry.metadata, actual?.metadata)
        assertEquals(entry.tags, actual?.tags)
        assertEquals(entry.accessCount, actual?.accessCount)
        assertArrayEquals(entry.embedding, actual?.embedding)
    }

    @Test
    fun deleteAndClearArePropagatedToTheDurableAdapter() {
        val persistence = FakePersistence()
        val store = MemoryStore(persistence = persistence)
        val first = SemanticMemory.MemoryEntry(
            id = "delete-me",
            content = "Private memory",
            label = "Private"
        )
        val second = SemanticMemory.MemoryEntry(
            id = "also-clear",
            content = "Another private memory",
            label = "Private"
        )
        store.save(first)
        store.save(second)

        store.delete(first.id)
        assertNull(MemoryStore(persistence = persistence).get(first.id))

        store.clear()
        assertEquals(0, MemoryStore(persistence = persistence).size())
    }

    private class FakePersistence : SemanticMemoryPersistence {
        private val entries = linkedMapOf<String, SemanticMemory.MemoryEntry>()

        override fun loadAll(): List<SemanticMemory.MemoryEntry> = entries.values.toList()

        override fun upsert(entry: SemanticMemory.MemoryEntry) {
            entries[entry.id] = entry
        }

        override fun delete(id: String) {
            entries.remove(id)
        }

        override fun clear() {
            entries.clear()
        }
    }
}
