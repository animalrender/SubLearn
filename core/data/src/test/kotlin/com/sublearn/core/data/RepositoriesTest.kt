package com.sublearn.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RepositoriesTest {
    @Test
    fun `word key normalises case punctuation and curly quotes`() {
        assertEquals("don't", WordKey.normalize("Don't"))
        assertEquals("don't", WordKey.normalize(" don\u2019t "))
        assertEquals("hello", WordKey.normalize("hello!"))
        assertEquals("سلام", WordKey.normalize("  سلام. "))
    }

    @Test
    fun `persian half space is treated as a separator so both spellings match`() {
        assertEquals(WordKey.normalize("می‌رم"), WordKey.normalize("می رم"))
    }

    @Test
    fun `punctuation-only tokens are not markable`() {
        assertTrue(!WordKey.isMarkable("..."))
        assertTrue(WordKey.isMarkable("word"))
        assertTrue(WordKey.isMarkable("۳"))
    }

    @Test
    fun `fts query quotes tokens and drops fts syntax`() {
        assertEquals("\"mak*\"", WordKey.ftsQuery("mak"))
        assertEquals("\"and*\" \"near*\"", WordKey.ftsQuery("and near"))
        assertEquals("\"NOT*\"", WordKey.ftsQuery("NOT"))
        assertEquals(null, WordKey.ftsQuery("   "))
    }

    @Test
    fun `translation key depends on text languages and provider only`() {
        val a = TranslationKey.of("Hello world", "en", "fa", "mlkit")
        val b = TranslationKey.of("  hello   WORLD ", "en", "fa", "mlkit")
        assertEquals(a, b)
        assertNotEquals(a, TranslationKey.of("Hello world", "en", "ar", "mlkit"))
        assertNotEquals(a, TranslationKey.of("Hello world", "en", "fa", "gemini"))
    }

    @Test
    fun `entity and domain round trip keeps semantics`() {
        val domain = MarkedWord(
            id = 3,
            word = "Give up",
            isPhrase = true,
            translation = "دست کشیدن",
            contextText = "I gave up.",
            contextTranslation = null,
            sourceTitle = "Movie",
            sourceStartMs = 42_000,
            status = WordStatus.LEARNING,
            level = "B2",
            note = "phrasal",
            markedAt = 10L,
            reviewCount = 2,
        )
        val entity = domain.toEntity()
        assertEquals(MyWordEntity.KIND_MULTI_WORD, entity.kind)
        assertEquals("give up", entity.wordLower)
        assertEquals(domain, entity.toDomain())
    }

    @Test
    fun `status parsing is total`() {
        assertEquals(WordStatus.KNOWN, WordStatus.fromKey("KNOWN"))
        assertEquals(WordStatus.NEW, WordStatus.fromKey("nonsense"))
        assertEquals(WordStatus.NEW, WordStatus.fromKey(null))
    }
}
