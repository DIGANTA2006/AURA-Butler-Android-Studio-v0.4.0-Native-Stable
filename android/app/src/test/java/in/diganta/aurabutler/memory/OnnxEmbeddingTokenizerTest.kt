package `in`.diganta.aurabutler.memory

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnnxEmbeddingTokenizerTest {
    private val tokenizer = WordPieceTokenizer(
        listOf(
            "[PAD]", "[UNK]", "[CLS]", "[SEP]",
            "aura", "is", "##s", "cafe", ",", "hello", "world"
        )
    )

    @Test
    fun addsSpecialTokensAndBuildsAnAttentionMask() {
        val encoded = tokenizer.encode("AURA is", maxSequenceLength = 8)

        assertArrayEquals(longArrayOf(2, 4, 5, 3, 0, 0, 0, 0), encoded.inputIds)
        assertArrayEquals(longArrayOf(1, 1, 1, 1, 0, 0, 0, 0), encoded.attentionMask)
        assertArrayEquals(LongArray(8), encoded.tokenTypeIds)
    }

    @Test
    fun wordPieceSplitsSuffixesAndNormalizesAccents() {
        val encoded = tokenizer.encode("AURAS CAFÉ", maxSequenceLength = 8)

        assertArrayEquals(longArrayOf(2, 4, 6, 7, 3, 0, 0, 0), encoded.inputIds)
        assertEquals(5, encoded.attentionMask.count { it == 1L })
    }

    @Test
    fun unknownWordsUseTheUnknownTokenAndLongInputsAreTruncatedSafely() {
        val unknown = tokenizer.encode("unseen", maxSequenceLength = 6)
        assertEquals(1L, unknown.inputIds[1])
        assertEquals(3L, unknown.inputIds[2])

        val truncated = tokenizer.encode(
            "aura aura aura aura aura",
            maxSequenceLength = 5
        )
        assertArrayEquals(longArrayOf(2, 4, 4, 4, 3), truncated.inputIds)
        assertTrue(truncated.attentionMask.all { it == 1L })
    }
}
