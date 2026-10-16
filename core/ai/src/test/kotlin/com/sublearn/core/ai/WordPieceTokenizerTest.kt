package com.sublearn.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tokenizer has to match the reference WordPiece rules, because a mismatch shows up as a
 * silently wrong sense ranking rather than a crash.
 */
class WordPieceTokenizerTest {

    /** A miniature BERT vocabulary: enough shape to exercise every rule. */
    private val vocabulary = listOf(
        "[PAD]", "[UNK]", "[CLS]", "[SEP]", "[MASK]",
        "the", "a", "cat", "dog", "sat", "on", "mat", "give", "up", "running", "##ing", "##s",
        "un", "##able", "،", ".",
    )

    private val tokenizer = WordPieceTokenizer(vocabulary, maxLength = 12)

    /**
     * The real tokens, `[CLS] … [SEP]` without the padding.
     *
     * This used to stop at the `[SEP]`, which silently dropped the token every test then expected
     * to see; [WordPieceEncoding.tokenCount] is what the encoder actually produces.
     */
    private fun idsOf(text: String): List<Int> {
        val encoding = tokenizer.encode(text)
        return encoding.inputIds.take(encoding.tokenCount)
    }

    private fun tokensOf(text: String): List<String> = idsOf(text).map { vocabulary[it] }

    @Test
    fun `wraps the input in CLS and SEP`() {
        val tokens = tokensOf("cat")
        assertEquals(listOf("[CLS]", "cat", "[SEP]"), tokens)
    }

    @Test
    fun `lowercases the input`() {
        assertEquals(listOf("[CLS]", "cat", "[SEP]"), tokensOf("CAT"))
    }

    @Test
    fun `splits unknown words into subwords greedily`() {
        // "running" is in the vocabulary, so force the subword path with a word that is not.
        assertEquals(listOf("[CLS]", "un", "##able", "[SEP]"), tokensOf("unable"))
    }

    @Test
    fun `falls back to UNK for a word with no subword match`() {
        assertEquals(listOf("[CLS]", "[UNK]", "[SEP]"), tokensOf("zzzz"))
    }

    @Test
    fun `separates punctuation into its own token`() {
        assertEquals(listOf("[CLS]", "cat", ".", "[SEP]"), tokensOf("cat."))
    }

    @Test
    fun `keeps a multi-character punctuation token that is in the vocabulary`() {
        // The Persian comma is a whole vocabulary entry, so it must not be split further.
        assertEquals(listOf("[CLS]", "cat", "،", "[SEP]"), tokensOf("cat،"))
    }

    @Test
    fun `pads to the configured length and masks the padding`() {
        val encoding = tokenizer.encode("cat")
        assertEquals(12, encoding.inputIds.size)
        assertEquals(12, encoding.attentionMask.size)
        assertEquals(12, encoding.tokenTypeIds.size)
        assertEquals(1, encoding.attentionMask[0])
        assertEquals(1, encoding.attentionMask[encoding.tokenCount - 1])
        assertEquals(0, encoding.attentionMask[encoding.tokenCount])
    }

    @Test
    fun `truncates a long input at the cap`() {
        val long = (1..40).joinToString(" ") { "cat" }
        val encoding = tokenizer.encode(long)
        assertEquals(12, encoding.inputIds.size)
        assertEquals(12, encoding.tokenCount)
        assertEquals("[SEP]".let { vocabulary.indexOf(it) }, encoding.inputIds[11])
    }

    @Test
    fun `empty text still produces a valid CLS SEP pair`() {
        val encoding = tokenizer.encode("   ")
        // `[CLS]` and `[SEP]` and nothing between them, which is what a single segment is.
        assertEquals(2, encoding.tokenCount)
        assertEquals(listOf("[CLS]", "[SEP]"), idsOf("   ").map { vocabulary[it] })
    }

    @Test
    fun `vocabulary without CLS or SEP is reported as unusable`() {
        assertFalse(WordPieceTokenizer(listOf("a", "b")).isUsable)
        assertTrue(tokenizer.isUsable)
        assertEquals(vocabulary.size, tokenizer.vocabularySize)
    }

    @Test
    fun `word splitting follows the reference basic tokenizer`() {
        assertEquals(listOf("the", "cat", "sat", "on", "the", "mat"), WordPieceTokenizer.splitWords("The cat sat on the mat"))
        assertEquals(listOf("cat", ".", "dog", "!"), WordPieceTokenizer.splitWords("cat. dog!"))
        assertEquals(listOf("don't", "stop"), WordPieceTokenizer.splitWords("don't stop"))
        assertEquals(emptyList<String>(), WordPieceTokenizer.splitWords("   "))
    }

    @Test
    fun `encoding equality compares the arrays`() {
        val a = tokenizer.encode("cat")
        val b = tokenizer.encode("cat")
        val c = tokenizer.encode("dog")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, c)
    }

    @Test
    fun `a very long word becomes UNK rather than being scanned`() {
        val huge = "a".repeat(150)
        assertEquals(listOf("[CLS]", "[UNK]", "[SEP]"), tokensOf(huge))
    }
}
