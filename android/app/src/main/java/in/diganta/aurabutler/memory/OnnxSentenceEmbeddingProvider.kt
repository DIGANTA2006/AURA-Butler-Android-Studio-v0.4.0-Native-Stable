package `in`.diganta.aurabutler.memory

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.io.File
import java.nio.LongBuffer
import java.text.Normalizer
import java.util.Locale

/**
 * SentenceTransformer-compatible all-MiniLM-L6-v2 inference.
 * The graph produces token states; this class applies attention-mask mean pooling
 * and L2 normalization as specified by the model's sentence-embedding pipeline.
 */
class OnnxSentenceEmbeddingProvider(
    modelFile: File,
    vocabularyFile: File,
    intraOpThreads: Int = 2
) : EmbeddingProvider, Closeable {

    private val environment = OrtEnvironment.getEnvironment()
    private val tokenizer: WordPieceTokenizer
    private val session: OrtSession

    override val dimension: Int = EMBEDDING_DIMENSION
    val isModelBacked: Boolean = true
    val backendDescription: String =
        "all-MiniLM-L6-v2 quantized ONNX (ARM64), mean pooled and normalized"

    init {
        require(modelFile.isFile && modelFile.length() > MIN_MODEL_BYTES) {
            "The verified AURA sentence-embedding model is not installed."
        }
        require(vocabularyFile.isFile && vocabularyFile.length() in MIN_VOCAB_BYTES..MAX_VOCAB_BYTES) {
            "The verified AURA sentence-embedding vocabulary is not installed."
        }
        require(intraOpThreads in 1..4)
        tokenizer = WordPieceTokenizer(vocabularyFile.readLines(Charsets.UTF_8))
        val options = OrtSession.SessionOptions()
        try {
            options.setIntraOpNumThreads(intraOpThreads)
            options.setInterOpNumThreads(1)
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            session = environment.createSession(modelFile.absolutePath, options)
        } finally {
            options.close()
        }
        require("input_ids" in session.inputNames) {
            "The installed ONNX model does not expose input_ids."
        }
        require("attention_mask" in session.inputNames) {
            "The installed ONNX model does not expose attention_mask."
        }
        require(session.outputNames.isNotEmpty()) {
            "The installed ONNX model does not expose an embedding output."
        }
    }

    @Synchronized
    override fun embed(text: String): FloatArray {
        require(text.isNotBlank()) { "Cannot embed blank text." }
        val tokenized = tokenizer.encode(text.take(MAX_INPUT_CHARS), MAX_SEQUENCE_LENGTH)
        val shape = longArrayOf(1L, MAX_SEQUENCE_LENGTH.toLong())
        val inputs = linkedMapOf<String, OnnxTensor>()
        try {
            inputs["input_ids"] = OnnxTensor.createTensor(
                environment,
                LongBuffer.wrap(tokenized.inputIds),
                shape
            )
            inputs["attention_mask"] = OnnxTensor.createTensor(
                environment,
                LongBuffer.wrap(tokenized.attentionMask),
                shape
            )
            if ("token_type_ids" in session.inputNames) {
                inputs["token_type_ids"] = OnnxTensor.createTensor(
                    environment,
                    LongBuffer.wrap(tokenized.tokenTypeIds),
                    shape
                )
            }

            session.run(inputs).use { result ->
                val tensor = result[0] as? OnnxTensor
                    ?: error("The ONNX model returned a non-tensor embedding output.")
                val batch = tensor.value as? Array<*>
                    ?: error("The ONNX embedding output has an invalid rank.")
                val tokenRows = batch.firstOrNull() as? Array<*>
                    ?: error("The ONNX embedding output has no token dimension.")
                require(tokenRows.size == MAX_SEQUENCE_LENGTH) {
                    "The ONNX model returned an unexpected sequence length."
                }

                val pooled = DoubleArray(dimension)
                var tokenCount = 0
                tokenRows.forEachIndexed { tokenIndex, rowValue ->
                    if (tokenized.attentionMask[tokenIndex] == 0L) return@forEachIndexed
                    val row = rowValue as? FloatArray
                        ?: error("The ONNX model returned an invalid hidden-state row.")
                    require(row.size == dimension) {
                        "The ONNX embedding dimension changed; expected " + dimension +
                            ", got " + row.size + "."
                    }
                    for (column in row.indices) {
                        pooled[column] += row[column].toDouble()
                    }
                    tokenCount++
                }
                require(tokenCount > 0) { "The tokenizer produced an empty attention mask." }

                val vector = FloatArray(dimension) { index ->
                    (pooled[index] / tokenCount).toFloat()
                }
                var normSquared = 0.0
                vector.forEach { value -> normSquared += value.toDouble() * value.toDouble() }
                val norm = kotlin.math.sqrt(normSquared)
                require(norm > 1e-12 && norm.isFinite()) {
                    "The ONNX model returned an invalid sentence embedding."
                }
                for (index in vector.indices) vector[index] = (vector[index] / norm).toFloat()
                return vector
            }
        } finally {
            inputs.values.forEach { it.close() }
        }
    }

    override fun close() {
        session.close()
    }

    internal companion object {
        const val EMBEDDING_DIMENSION = 384
        const val MAX_SEQUENCE_LENGTH = 256
        const val MAX_INPUT_CHARS = 8_000
        const val MIN_MODEL_BYTES = 1_000_000L
        const val MIN_VOCAB_BYTES = 100_000L
        const val MAX_VOCAB_BYTES = 1_000_000L
    }
}

/** BERT uncased BasicTokenizer + greedy WordPiece tokenizer for MiniLM. */
internal class WordPieceTokenizer(vocabularyLines: List<String>) {
    private val vocabulary = vocabularyLines.mapIndexedNotNull { index, token ->
        token.trim().takeIf(String::isNotEmpty)?.let { it to index.toLong() }
    }.toMap()
    private val padId = vocabulary["[PAD]"] ?: error("The model vocabulary is missing [PAD].")
    private val unknownId = vocabulary["[UNK]"] ?: error("The model vocabulary is missing [UNK].")
    private val clsId = vocabulary["[CLS]"] ?: error("The model vocabulary is missing [CLS].")
    private val sepId = vocabulary["[SEP]"] ?: error("The model vocabulary is missing [SEP].")

    data class EncodedInput(
        val inputIds: LongArray,
        val attentionMask: LongArray,
        val tokenTypeIds: LongArray
    )

    fun encode(text: String, maxSequenceLength: Int): EncodedInput {
        require(maxSequenceLength >= 3)
        val pieces = basicTokens(text).flatMap(::wordPiece)
        val sequence = buildList {
            add(clsId)
            addAll(pieces.take(maxSequenceLength - 2))
            add(sepId)
        }
        val inputIds = LongArray(maxSequenceLength) { padId }
        val attentionMask = LongArray(maxSequenceLength)
        val tokenTypeIds = LongArray(maxSequenceLength)
        sequence.forEachIndexed { index, id ->
            inputIds[index] = id
            attentionMask[index] = 1L
        }
        return EncodedInput(inputIds, attentionMask, tokenTypeIds)
    }

    private fun basicTokens(input: String): List<String> {
        val normalized = Normalizer.normalize(
            input.lowercase(Locale.ROOT),
            Normalizer.Form.NFD
        )
        val result = mutableListOf<String>()
        val token = StringBuilder()

        fun flush() {
            if (token.isNotEmpty()) {
                result += token.toString()
                token.setLength(0)
            }
        }

        normalized.forEach { character ->
            val type = Character.getType(character)
            when {
                character == '\uFFFD' -> flush()
                type == Character.NON_SPACING_MARK.toInt() ||
                    type == Character.ENCLOSING_MARK.toInt() -> Unit
                type == Character.CONTROL.toInt() ||
                    type == Character.FORMAT.toInt() -> flush()
                character.isWhitespace() -> flush()
                isPunctuation(type) -> {
                    flush()
                    result += character.toString()
                }
                else -> token.append(character)
            }
        }
        flush()
        return result
    }

    private fun wordPiece(token: String): List<Long> {
        if (token.length > MAX_WORD_CHARS) return listOf(unknownId)
        val output = mutableListOf<Long>()
        var start = 0
        while (start < token.length) {
            var end = token.length
            var match: Long? = null
            var nextStart = start
            while (end > start) {
                val fragment = token.substring(start, end)
                val candidate = if (start == 0) fragment else "##" + fragment
                match = vocabulary[candidate]
                if (match != null) {
                    nextStart = end
                    break
                }
                end--
            }
            if (match == null) return listOf(unknownId)
            output += match
            start = nextStart
        }
        return output
    }

    private fun isPunctuation(type: Int): Boolean =
        type == Character.CONNECTOR_PUNCTUATION.toInt() ||
            type == Character.DASH_PUNCTUATION.toInt() ||
            type == Character.START_PUNCTUATION.toInt() ||
            type == Character.END_PUNCTUATION.toInt() ||
            type == Character.INITIAL_QUOTE_PUNCTUATION.toInt() ||
            type == Character.FINAL_QUOTE_PUNCTUATION.toInt() ||
            type == Character.OTHER_PUNCTUATION.toInt() ||
            type == Character.MATH_SYMBOL.toInt() ||
            type == Character.CURRENCY_SYMBOL.toInt() ||
            type == Character.MODIFIER_SYMBOL.toInt() ||
            type == Character.OTHER_SYMBOL.toInt()

    private companion object {
        const val MAX_WORD_CHARS = 100
    }
}
