package `in`.diganta.aurabutler.memory

import org.junit.Assert.assertTrue
import org.junit.Test

class HybridRetrievalScorerTest {
    @Test
    fun `exact topic outranks unrelated memory`() {
        val profile = HybridRetrievalScorer.prepare("modal qwen research server")
        val related = HybridRetrievalScorer.score(
            profile,
            "AURA uses Modal Qwen for private deep research and tool planning",
            ageDays = 2.0
        )
        val unrelated = HybridRetrievalScorer.score(
            profile,
            "Hostel dinner preference is rice and vegetables",
            ageDays = 2.0
        )
        assertTrue(related.total > unrelated.total)
    }

    @Test
    fun `fuzzy character overlap helps minor typo`() {
        val fuzzy = HybridRetrievalScorer.score(
            "scholership aplication",
            "scholarship application status approved",
            ageDays = 10.0
        )
        assertTrue(fuzzy.fuzzy > 0.05)
    }

    @Test
    fun `prepared query is reusable across documents`() {
        val profile = HybridRetrievalScorer.prepare("android wake word service")
        val service = HybridRetrievalScorer.score(
            profile,
            "WakeWordService keeps Android foreground listening active",
            ageDays = 20.0
        )
        val unrelated = HybridRetrievalScorer.score(
            profile,
            "Mathematics sequence and series lecture notes",
            ageDays = 1.0
        )
        assertTrue(service.total > unrelated.total)
    }

    @Test
    fun `ordered phrase earns more relevance than shuffled words`() {
        val profile = HybridRetrievalScorer.prepare("private qwen research")
        val ordered = HybridRetrievalScorer.score(
            profile,
            "AURA uses private qwen research for sourced technical answers",
            ageDays = 4.0
        )
        val shuffled = HybridRetrievalScorer.score(
            profile,
            "research notes discuss qwen as one private model",
            ageDays = 4.0
        )
        assertTrue(ordered.total > shuffled.total)
    }
}
