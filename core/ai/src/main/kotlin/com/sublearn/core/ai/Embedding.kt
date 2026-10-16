package com.sublearn.core.ai

import kotlin.math.sqrt

/**
 * A text embedding produced on the device. Implementations live in the runtime modules
 * (`core:nlp-onnx` for ONNX, `core:ai` for the pure-Kotlin fallback); every caller works against
 * this interface so a profile can be swapped without touching the analysis path.
 */
interface TextEmbedder : AutoCloseable {
    /** Vector length the model produces. Callers size their buffers from this, not a constant. */
    val dimensions: Int

    /** Embeds one already-trimmed input. Throws only for a broken model, never for short text. */
    fun embed(text: String): FloatArray

    /** Which runtime is answering, for logs and the diagnostics screen. */
    val runtimeName: String
}

/**
 * Token ids for one encoded input, in the layout BERT-family encoders expect.
 *
 * @property inputIds `[CLS] … [SEP] [PAD]…`
 * @property attentionMask `1` for real tokens, `0` for padding
 * @property tokenTypeIds always `0` for single-segment input
 * @property tokenCount how many real (unpadded) tokens the input produced
 */
data class WordPieceEncoding(
    val inputIds: IntArray,
    val attentionMask: IntArray,
    val tokenTypeIds: IntArray,
    val tokenCount: Int,
) {
    // IntArray gives identity equality, which silently breaks any data-class assumption, so the
    // comparisons are spelled out rather than left to the generated ones.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WordPieceEncoding) return false
        return tokenCount == other.tokenCount &&
            inputIds.contentEquals(other.inputIds) &&
            attentionMask.contentEquals(other.attentionMask) &&
            tokenTypeIds.contentEquals(other.tokenTypeIds)
    }

    override fun hashCode(): Int {
        var result = tokenCount
        result = 31 * result + inputIds.contentHashCode()
        result = 31 * result + attentionMask.contentHashCode()
        result = 31 * result + tokenTypeIds.contentHashCode()
        return result
    }
}

/**
 * WordPiece tokenizer for the BERT-family encoders in the catalog (`all-MiniLM-L6-v2`,
 * `distilbert-base-uncased`).
 *
 * The vocabulary is the model's own `vocab.txt`, read in order, so a model swap only changes the
 * file. Unknown words become `[UNK]`; the greedy longest-match rule is the same one the reference
 * Python tokenizer uses, including the `##` continuation prefix and lower-casing.
 *
 * @property vocabulary token strings, index = id
 * @property maxLength hard cap including `[CLS]` and `[SEP]`
 */
class WordPieceTokenizer(
    private val vocabulary: List<String>,
    val maxLength: Int = DEFAULT_MAX_LENGTH,
) {
    private val indexOfToken: Map<String, Int> = vocabulary.withIndex().associate { (i, token) -> token to i }

    private val clsId: Int = indexOfToken[CLS] ?: 101
    private val sepId: Int = indexOfToken[SEP] ?: 102
    private val padId: Int = indexOfToken[PAD] ?: 0
    private val unkId: Int = indexOfToken[UNK] ?: 100

    val vocabularySize: Int get() = vocabulary.size
    val isUsable: Boolean get() = vocabulary.isNotEmpty() && indexOfToken.containsKey(CLS) && indexOfToken.containsKey(SEP)

    /**
     * Encode [text] into padded id arrays. Text longer than [maxLength] is truncated at the
     * tail, which is what the reference tokenizer does for a single segment.
     */
    fun encode(text: String): WordPieceEncoding {
        val capacity = maxLength - 2  // room for [CLS] and [SEP]
        val pieces = ArrayList<Int>(capacity + 2)
        pieces += clsId

        for (word in splitWords(text)) {
            if (pieces.size - 1 >= capacity) break
            for (id in wordPiece(word)) {
                if (pieces.size - 1 >= capacity) break
                pieces += id
            }
        }
        pieces += sepId

        val inputIds = IntArray(maxLength) { i -> pieces.getOrElse(i) { padId } }
        val attentionMask = IntArray(maxLength) { i -> if (i < pieces.size) 1 else 0 }
        val tokenTypeIds = IntArray(maxLength)
        return WordPieceEncoding(inputIds, attentionMask, tokenTypeIds, pieces.size)
    }

    /** Greedy longest-match subword split for a single word. */
    private fun wordPiece(word: String): List<Int> {
        if (word.isEmpty()) return emptyList()

        val direct = indexOfToken[word]
        if (direct != null) return listOf(direct)
        if (word.length > MAX_CHARS_PER_WORD) return listOf(unkId)

        val out = ArrayList<Int>(4)
        var start = 0
        while (start < word.length) {
            var end = word.length
            var matched = -1
            while (end > start) {
                val candidate = if (start == 0) {
                    word.substring(start, end)
                } else {
                    SUBWORD_PREFIX + word.substring(start, end)
                }
                val id = indexOfToken[candidate]
                if (id != null) {
                    matched = id
                    break
                }
                end--
            }
            if (matched == -1) return listOf(unkId)
            out += matched
            start = end
        }
        return out
    }

    companion object {
        const val CLS = "[CLS]"
        const val SEP = "[SEP]"
        const val PAD = "[PAD]"
        const val UNK = "[UNK]"
        const val SUBWORD_PREFIX = "##"
        const val DEFAULT_MAX_LENGTH = 128

        /** The reference tokenizer gives up on very long words rather than scanning them. */
        private const val MAX_CHARS_PER_WORD = 100

        /**
         * Split on whitespace and separate punctuation the way the reference basic tokenizer does:
         * letters and digits stay together, every punctuation character stands alone.
         */
        fun splitWords(text: String): List<String> {
            val out = ArrayList<String>()
            val current = StringBuilder()
            for (ch in text.lowercase()) {
                when {
                    ch.isWhitespace() -> {
                        if (current.isNotEmpty()) {
                            out += current.toString()
                            current.clear()
                        }
                    }
                    isWordChar(ch) -> current.append(ch)
                    else -> {
                        if (current.isNotEmpty()) {
                            out += current.toString()
                            current.clear()
                        }
                        out += ch.toString()
                    }
                }
            }
            if (current.isNotEmpty()) out += current.toString()
            return out
        }

        private fun isWordChar(ch: Char): Boolean =
            ch.isLetterOrDigit() || ch == '\'' || ch == '_'
    }
}

/**
 * Embedding post-processing shared by every runtime.
 *
 * The catalog models all use mean pooling over the token outputs followed by L2 normalisation,
 * which is what makes a dot product equal to cosine similarity.
 */
object EmbeddingMath {

    /**
     * Mean-pool `[sequence, hidden]` activations over the real tokens only, then L2-normalise.
     *
     * @param activations row-major `sequenceLength * hiddenSize` values
     * @param attentionMask `1` for real tokens, `0` for padding
     */
    fun meanPoolNormalized(
        activations: FloatArray,
        attentionMask: IntArray,
        hiddenSize: Int,
    ): FloatArray {
        require(hiddenSize > 0) { "hiddenSize must be positive" }
        val sequenceLength = attentionMask.size
        if (sequenceLength == 0) return FloatArray(hiddenSize)

        val pooled = FloatArray(hiddenSize)
        var counted = 0

        for (token in 0 until sequenceLength) {
            if (attentionMask[token] == 0) continue
            val base = token * hiddenSize
            if (base + hiddenSize > activations.size) break
            for (h in 0 until hiddenSize) {
                pooled[h] += activations[base + h]
            }
            counted++
        }
        if (counted > 0) {
            for (h in 0 until hiddenSize) pooled[h] /= counted
        }
        return l2Normalize(pooled)
    }

    /** Scale a vector to unit length. A zero vector is returned unchanged. */
    fun l2Normalize(vector: FloatArray): FloatArray {
        var sumOfSquares = 0.0
        for (value in vector) sumOfSquares += value.toDouble() * value.toDouble()

        val norm = sqrt(sumOfSquares)
        if (norm <= 1e-12) return vector

        for (i in vector.indices) vector[i] = (vector[i] / norm).toFloat()
        return vector
    }

    /** Cosine similarity. Both inputs are assumed normalised, so this is a dot product. */
    fun cosine(a: FloatArray, b: FloatArray): Float {
        val length = minOf(a.size, b.size)
        if (length == 0) return 0f
        var dot = 0f
        for (i in 0 until length) dot += a[i] * b[i]
        return dot.coerceIn(-1f, 1f)
    }
}

/**
 * Ranks the candidate Persian senses of a word against the subtitle line it appeared in.
 *
 * Used by the PRECISE and ADVANCED profiles: when an embedder is available the ranking follows
 * meaning, and when it is not the lexical fallback keeps the screen working rather than empty.
 */
class EmbeddingSenseRanker(
    private val embedder: TextEmbedder?,
) {
    /**
     * @return sense ids best-first with their similarity; empty when there is nothing to rank.
     */
    fun rank(contextText: String, candidates: List<Pair<Long, String>>): List<Pair<Long, Float>> {
        if (candidates.isEmpty()) return emptyList()

        val active = embedder
        if (active == null || contextText.isBlank()) {
            return lexicalFallback(contextText, candidates)
        }

        return try {
            val context = active.embed(contextText)
            candidates
                .map { (senseId, gloss) ->
                    val similarity = if (gloss.isBlank()) 0f else EmbeddingMath.cosine(context, active.embed(gloss))
                    senseId to similarity
                }
                .sortedByDescending { it.second }
        } catch (t: Throwable) {
            // A model that loads but fails mid-inference must not take the subtitle screen down.
            lexicalFallback(contextText, candidates)
        }
    }

    private fun lexicalFallback(
        contextText: String,
        candidates: List<Pair<Long, String>>,
    ): List<Pair<Long, Float>> {
        val contextTokens = tokens(contextText)
        return candidates
            .mapIndexed { index, (senseId, gloss) ->
                val overlap = tokens(gloss).count { it in contextTokens }
                val priorBonus = 1f / (1f + index * 0.15f)
                senseId to (overlap * 0.5f + priorBonus)
            }
            .sortedByDescending { it.second }
    }

    private fun tokens(text: String): Set<String> =
        text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 1 }.toSet()
}
