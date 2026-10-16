package com.sublearn.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddingMathTest {

    @Test
    fun `mean pooling averages only the real tokens`() {
        // Two real tokens of width 2, then one padded row that must be ignored.
        val activations = floatArrayOf(
            1f, 2f,
            3f, 4f,
            100f, 100f,
        )
        val pooled = EmbeddingMath.meanPoolNormalized(activations, intArrayOf(1, 1, 0), hiddenSize = 2)
        // Mean of (1,2) and (3,4) is (2,3); normalised it is (2,3)/sqrt(13).
        val norm = kotlin.math.sqrt(13f)
        assertEquals(2f / norm, pooled[0], 1e-5f)
        assertEquals(3f / norm, pooled[1], 1e-5f)
    }

    @Test
    fun `a fully padded input yields a zero vector instead of NaN`() {
        val pooled = EmbeddingMath.meanPoolNormalized(floatArrayOf(1f, 2f), intArrayOf(0, 0), hiddenSize = 2)
        assertEquals(0f, pooled[0], 0f)
        assertEquals(0f, pooled[1], 0f)
    }

    @Test
    fun `pooling stops at the end of the activations rather than reading past it`() {
        // Mask claims three tokens but only two rows exist: the third must be skipped, not read.
        val pooled = EmbeddingMath.meanPoolNormalized(floatArrayOf(1f, 1f, 3f, 3f), intArrayOf(1, 1, 1), hiddenSize = 2)
        val norm = kotlin.math.sqrt(8f)
        assertEquals(2f / norm, pooled[0], 1e-5f)
    }

    @Test
    fun `normalising gives unit length`() {
        val v = EmbeddingMath.l2Normalize(floatArrayOf(3f, 4f))
        assertEquals(1f, kotlin.math.sqrt(v[0] * v[0] + v[1] * v[1]), 1e-5f)
    }

    @Test
    fun `normalising a zero vector leaves it alone`() {
        val v = floatArrayOf(0f, 0f)
        assertSame(v, EmbeddingMath.l2Normalize(v))
        assertEquals(0f, v[0], 0f)
    }

    @Test
    fun `cosine of identical unit vectors is one`() {
        val a = EmbeddingMath.l2Normalize(floatArrayOf(1f, 1f, 1f))
        assertEquals(1f, EmbeddingMath.cosine(a, a.copyOf()), 1e-5f)
    }

    @Test
    fun `cosine of a zero vector is zero`() {
        assertEquals(0f, EmbeddingMath.cosine(floatArrayOf(0f, 0f), floatArrayOf(1f, 1f)), 0f)
    }

    @Test
    fun `cosine of mismatched lengths uses the shorter one`() {
        assertEquals(0f, EmbeddingMath.cosine(floatArrayOf(), floatArrayOf(1f)), 0f)
    }
}

class EmbeddingSenseRankerTest {

    /** A stub embedder: the vector is just the word's letters mapped to numbers. */
    private class StubEmbedder(private val vectors: Map<String, FloatArray>) : TextEmbedder {
        override val dimensions: Int = 3
        override val runtimeName: String = "stub"
        override fun embed(text: String): FloatArray = vectors[text.lowercase()] ?: FloatArray(dimensions)
        override fun close() = Unit
    }

    private class BrokenEmbedder : TextEmbedder {
        override val dimensions: Int = 3
        override val runtimeName: String = "broken"
        override fun embed(text: String): FloatArray = error("model failed")
        override fun close() = Unit
    }

    @Test
    fun `with an embedder the closest gloss wins`() {
        val embedder = StubEmbedder(
            mapOf(
                "she gave up smoking" to floatArrayOf(1f, 0f, 0f),
                "to quit" to floatArrayOf(0.99f, 0.1f, 0f),
                "to surrender" to floatArrayOf(0f, 1f, 0f),
            ),
        )
        val ranked = EmbeddingSenseRanker(embedder).rank(
            contextText = "she gave up smoking",
            candidates = listOf(10L to "to surrender", 11L to "to quit"),
        )
        assertEquals(11L, ranked.first().first)
    }

    @Test
    fun `without an embedder the lexical overlap still ranks`() {
        val ranked = EmbeddingSenseRanker(null).rank(
            contextText = "she gave up smoking last year",
            candidates = listOf(10L to "to surrender", 11L to "quit smoking"),
        )
        assertEquals(11L, ranked.first().first)
    }

    @Test
    fun `a broken model falls back instead of failing the screen`() {
        val ranked = EmbeddingSenseRanker(BrokenEmbedder()).rank(
            contextText = "give up",
            candidates = listOf(1L to "give up now", 2L to "unrelated"),
        )
        assertEquals(2, ranked.size)
        assertEquals(1L, ranked.first().first)
    }

    @Test
    fun `no candidates rank to nothing`() {
        assertTrue(EmbeddingSenseRanker(null).rank("anything", emptyList()).isEmpty())
    }

    @Test
    fun `blank context still returns every candidate once`() {
        val ranked = EmbeddingSenseRanker(StubEmbedder(emptyMap())).rank(
            contextText = "   ",
            candidates = listOf(1L to "a", 2L to "b"),
        )
        assertEquals(2, ranked.size)
    }

    @Test
    fun `the same ranking is produced by the lexical path as ids stay stable`() {
        val once = EmbeddingSenseRanker(null).rank("cat sat", listOf(5L to "cat", 6L to "dog"))
        val twice = EmbeddingSenseRanker(null).rank("cat sat", listOf(5L to "cat", 6L to "dog"))
        assertEquals(once, twice)
    }
}
