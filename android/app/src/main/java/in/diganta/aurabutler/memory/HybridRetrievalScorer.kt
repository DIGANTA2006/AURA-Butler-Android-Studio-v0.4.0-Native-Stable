package `in`.diganta.aurabutler.memory

import java.util.Locale
import kotlin.math.exp
import kotlin.math.max

/**
 * Lightweight local retrieval scorer.
 *
 * v0.17 prepares query features once, then combines token F1, ordered bigrams,
 * character n-grams, long-term specificity and recency. This keeps retrieval
 * private and inexpensive while improving typo tolerance and reducing repeated
 * query work across the bounded memory set.
 */
object HybridRetrievalScorer {
    data class Score(
        val total: Double,
        val lexical: Double,
        val fuzzy: Double
    )

    data class QueryProfile internal constructor(
        internal val normalized: String,
        internal val terms: Set<String>,
        internal val trigrams: Set<String>,
        internal val bigrams: Set<String>,
        internal val specificTerms: Set<String>
    )

    fun prepare(query: String): QueryProfile {
        val normalized = normalize(query)
        val termSet = terms(normalized)
        return QueryProfile(
            normalized = normalized,
            terms = termSet,
            trigrams = trigrams(normalized),
            bigrams = bigrams(normalized),
            specificTerms = termSet.filterTo(linkedSetOf()) { it.length >= 5 }
        )
    }

    fun score(query: String, document: String, ageDays: Double): Score =
        score(prepare(query), document, ageDays)

    fun score(
        query: QueryProfile,
        document: String,
        ageDays: Double
    ): Score {
        val d = normalize(document)
        if (query.normalized.isBlank() || d.isBlank()) return Score(0.0, 0.0, 0.0)

        val dTerms = terms(d)
        val overlap = query.terms.intersect(dTerms).size
        val precision = if (dTerms.isEmpty()) 0.0 else overlap.toDouble() / dTerms.size
        val recall = if (query.terms.isEmpty()) 0.0 else overlap.toDouble() / query.terms.size
        val lexical = if (precision + recall <= 0.0) {
            0.0
        } else {
            (2.0 * precision * recall) / max(precision + recall, 1e-9)
        }

        val fuzzy = jaccard(query.trigrams, trigrams(d))
        val ordered = jaccard(query.bigrams, bigrams(d))

        val exactPhraseBoost = when {
            d.contains(query.normalized) -> 0.20
            query.normalized.length >= 12 && query.normalized.contains(d.take(180)) -> 0.10
            else -> 0.0
        }

        val specificity = if (query.specificTerms.isEmpty()) {
            0.0
        } else {
            query.specificTerms.intersect(dTerms).size.toDouble() /
                query.specificTerms.size.coerceAtLeast(1)
        }

        val recency = exp(-ageDays.coerceAtLeast(0.0) / 210.0)

        val total =
            (lexical * 0.52) +
            (fuzzy * 0.20) +
            (ordered * 0.10) +
            (specificity * 0.10) +
            (recency * 0.08) +
            exactPhraseBoost

        return Score(total.coerceAtMost(1.45), lexical, fuzzy)
    }

    private fun normalize(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
        .trim()
        .replace(Regex("""\s+"""), " ")
        .take(6_000)

    private fun terms(value: String): Set<String> = value
        .split(' ')
        .asSequence()
        .map(String::trim)
        .filter { it.length >= 2 }
        .take(500)
        .toSet()

    private fun bigrams(value: String): Set<String> {
        val words = value.split(' ').filter { it.isNotBlank() }.take(300)
        if (words.size < 2) return emptySet()
        return buildSet {
            for (index in 0 until words.lastIndex) {
                add(words[index] + "\u0001" + words[index + 1])
            }
        }
    }

    private fun trigrams(value: String): Set<String> {
        val compact = "  ${value.take(2_500)}  "
        if (compact.length < 3) return emptySet()
        return buildSet {
            for (index in 0..compact.length - 3) add(compact.substring(index, index + 3))
        }
    }

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val intersection = a.intersect(b).size
        if (intersection == 0) return 0.0
        return intersection.toDouble() / (a.size + b.size - intersection).coerceAtLeast(1)
    }
}
