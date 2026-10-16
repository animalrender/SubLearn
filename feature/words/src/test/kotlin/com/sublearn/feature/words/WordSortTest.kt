package com.sublearn.feature.words

import com.sublearn.core.data.MarkedWord
import com.sublearn.core.data.WordStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Sorting rules for the My Words list (LRN-3). Each order is applied with the comparator the
 * ViewModel uses, so the tests cover the real ordering rather than a copy of it.
 */
class WordSortTest {

    private fun word(
        id: Long,
        text: String,
        markedAt: Long,
        level: String? = null,
    ) = MarkedWord(
        id = id,
        word = text,
        isPhrase = false,
        translation = null,
        contextText = null,
        contextTranslation = null,
        sourceTitle = null,
        sourceStartMs = 0L,
        status = WordStatus.NEW,
        level = level,
        note = null,
        markedAt = markedAt,
        reviewCount = 0,
    )

    private val banana = word(1, "banana", markedAt = 300L, level = "B2")
    private val apple = word(2, "Apple", markedAt = 100L, level = "A1")
    private val cherry = word(3, "cherry", markedAt = 200L, level = null)

    private fun sortedBy(sort: WordSort) = listOf(banana, apple, cherry).sortedWith(sort.comparator)

    @Test
    fun `newest puts the most recently marked first`() {
        assertEquals(listOf(banana, cherry, apple), sortedBy(WordSort.NEWEST))
    }

    @Test
    fun `oldest puts the earliest marked first`() {
        assertEquals(listOf(apple, cherry, banana), sortedBy(WordSort.OLDEST))
    }

    @Test
    fun `alphabetical ignores case`() {
        assertEquals(listOf(apple, banana, cherry), sortedBy(WordSort.ALPHABETICAL))
    }

    @Test
    fun `level groups by CEFR and sends unknown levels last`() {
        assertEquals(listOf(apple, banana, cherry), sortedBy(WordSort.LEVEL))
    }

    @Test
    fun `every order is stable for equal primary keys`() {
        val first = word(10, "same", markedAt = 500L)
        val second = word(11, "same", markedAt = 500L)
        for (sort in WordSort.entries) {
            val once = listOf(first, second).sortedWith(sort.comparator)
            val twice = once.sortedWith(sort.comparator)
            assertEquals("$sort must be stable", once.map { it.id }, twice.map { it.id })
        }
    }
}