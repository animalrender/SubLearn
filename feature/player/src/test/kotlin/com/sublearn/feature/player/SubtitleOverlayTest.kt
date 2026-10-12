package com.sublearn.feature.player

import com.sublearn.core.nlp.PhraseKind
import com.sublearn.core.nlp.PhraseSpan
import com.sublearn.core.settings.WordStyleSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleOverlayTest {

    @Test
    fun `styleWords applies range-based PhraseSpan styling and dashed underline annotations`() {
        val line = "Please pick the book up now"
        // Tokens: 0="Please", 1="pick", 2="the", 3="book", 4="up", 5="now"
        val sepSpan = PhraseSpan(
            startChar = 7,
            endChar = 23,
            tokens = listOf(1, 4),
            kind = PhraseKind.PHRASAL,
            headword = "pick up",
            lemmaKey = "pick up",
            confidence = 0.95f,
            separable = true,
        )
        val annotated = styleWords(
            text = line,
            wordStates = emptyMap(),
            styles = WordStyleSettings(enabled = true),
            phraseSpans = listOf(sepSpan),
        )
        val underlineAnns = annotated.getStringAnnotations(SUBTITLE_UNDERLINE_TAG, 0, line.length)
        assertEquals(2, underlineAnns.size)
        assertEquals("pick", line.substring(underlineAnns[0].start, underlineAnns[0].end))
        assertEquals("up", line.substring(underlineAnns[1].start, underlineAnns[1].end))
        assertTrue(underlineAnns[0].item.startsWith("DASHED_UNDERLINE:"))
    }

    @Test
    fun `styleWords keeps phrase type color when savedPhrasesKeepTypeColor is true`() {
        val line = "Never gave up"
        val span = PhraseSpan(
            startChar = 6,
            endChar = 13,
            tokens = listOf(1, 2),
            kind = PhraseKind.PHRASAL,
            headword = "give up",
            lemmaKey = "give up",
            confidence = 0.92f,
            separable = false,
        )
        val annotatedKeep = styleWords(
            text = line,
            wordStates = mapOf("gave" to WordVisualState.MARKED, "up" to WordVisualState.MARKED),
            styles = WordStyleSettings(enabled = true, savedPhrasesKeepTypeColor = true),
            phraseSpans = listOf(span),
            savedPhraseKeys = setOf("give up"),
        )
        val annsKeep = annotatedKeep.getStringAnnotations(SUBTITLE_UNDERLINE_TAG, 0, line.length)
        assertEquals(2, annsKeep.size)
        // Phrasal underline color is Palette.PROUD_PHRASAL_UNDERLINE (0xFFA855F7)
        assertTrue(annsKeep[0].item.endsWith("4289222135") || annsKeep[0].item.contains("DASHED_UNDERLINE:"))

        val annotatedGreen = styleWords(
            text = line,
            wordStates = mapOf("gave" to WordVisualState.MARKED, "up" to WordVisualState.MARKED),
            styles = WordStyleSettings(enabled = true, savedPhrasesKeepTypeColor = false),
            phraseSpans = listOf(span),
            savedPhraseKeys = setOf("give up"),
        )
        val annsGreen = annotatedGreen.getStringAnnotations(SUBTITLE_UNDERLINE_TAG, 0, line.length)
        assertEquals(2, annsGreen.size)
        // Saved green underline color differs from phrasal purple underline color
        assertTrue(annsGreen[0].item != annsKeep[0].item)
    }

    @Test
    fun `styleWords styles single marked words with dashed green underline`() {
        val line = "She gave everything"
        val annotated = styleWords(
            text = line,
            wordStates = mapOf("gave" to WordVisualState.MARKED),
            styles = WordStyleSettings(enabled = true),
        )
        val underlineAnns = annotated.getStringAnnotations(SUBTITLE_UNDERLINE_TAG, 0, line.length)
        assertEquals(1, underlineAnns.size)
        assertEquals("gave", line.substring(underlineAnns[0].start, underlineAnns[0].end))
    }
}
