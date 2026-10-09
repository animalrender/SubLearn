package com.sublearn.core.subtitles

import kotlin.math.max

/**
 * Splits subtitle text into tappable units. SubLearn draws the subtitle layers itself (never the
 * player's built-in renderer) so that a tap can hit a word, so this tokenizer is the contract
 * between the text on screen and the translate/search/AI features.
 */
object Tokenizer {
    /** ZWNJ (Persian half-space) and Arabic harakat stay inside a word. */
    private fun isWordChar(ch: Char): Boolean =
        Character.isLetterOrDigit(ch) ||
            ch == '\'' ||
            ch == '\u2019' ||
            ch == '\u200c' ||
            ch.code in 0x064B..0x065F ||
            ch.code in 0x0670..0x0670 ||
            ch.code in 0x06D6..0x06ED

    private fun isApostrophe(ch: Char): Boolean = ch == '\'' || ch == '\u2019'

    /** A punctuation run: anything that is neither whitespace nor the start of a word. */
    private fun isPunctuationChar(ch: Char): Boolean = !ch.isWhitespace() && (!isWordChar(ch) || isApostrophe(ch))

    /**
     * Splits [text] into word and punctuation spans that tile the text (whitespace excluded). An
     * apostrophe inside a word stays in it (`don't`); a leading or trailing one (`girls'`, `'quoted'`)
     * is emitted as punctuation so the word itself stays usable for lookups.
     */
    fun spans(text: String): List<TokenSpan> {
        if (text.isEmpty()) return emptyList()
        val result = ArrayList<TokenSpan>(text.length / 6 + 1)
        var index = 0
        while (index < text.length) {
            val ch = text[index]
            when {
                ch.isWhitespace() -> index++
                isWordChar(ch) && !isApostrophe(ch) -> {
                    val start = index
                    while (index < text.length && isWordChar(text[index])) index++
                    var end = index
                    while (end > start + 1 && isApostrophe(text[end - 1])) end--
                    result.add(TokenSpan(text.substring(start, end), start, end, isWord = true))
                    index = end
                }
                else -> {
                    val start = index
                    while (index < text.length && isPunctuationChar(text[index])) index++
                    val end = max(start + 1, index)
                    result.add(TokenSpan(text.substring(start, end), start, end, isWord = false))
                    index = end
                }
            }
        }
        return result
    }

    fun words(text: String): List<TokenSpan> = spans(text).filter { it.isWord }

    /**
     * Builds [CueToken]s for a cue. When [timings] is provided (ASS `\k`, WebVTT cue timings) each
     * word gets its real time; otherwise time is distributed over the cue duration proportionally
     * to character length, which is good enough for karaoke-style highlighting and repeat math.
     */
    fun toTokens(
        text: String,
        startMs: Long,
        endMs: Long,
        trackId: String = "",
        timings: List<TokenTiming>? = null,
    ): List<CueToken> {
        val spans = spans(text)
        if (spans.isEmpty()) return emptyList()
        val duration = (endMs - startMs).coerceAtLeast(0L)
        val words = spans.filter { it.isWord }
        if (timings != null && timings.size == words.size && timings.isNotEmpty()) {
            val length = text.length.coerceAtLeast(1)
            var wordOrdinal = -1
            return spans.map { span ->
                val timing = if (span.isWord) timings.getOrNull(++wordOrdinal) else null
                CueToken(
                    text = span.text,
                    charStart = span.start,
                    charEnd = span.end,
                    startMs = timing?.startMs ?: (startMs + duration * span.start / length),
                    endMs = timing?.endMs ?: (startMs + duration * span.end / length),
                    direction = TextDirection.of(span.text),
                    isWord = span.isWord,
                )
            }
        }
        val charTotal = spans.sumOf { it.text.length }.coerceAtLeast(1)
        var consumed = 0
        return spans.map { span ->
            val from = consumed
            consumed += span.text.length
            CueToken(
                text = span.text,
                charStart = span.start,
                charEnd = span.end,
                startMs = startMs + (duration * from / charTotal),
                endMs = startMs + (if (consumed >= charTotal) duration else duration * consumed / charTotal),
                direction = TextDirection.of(span.text),
                isWord = span.isWord,
            )
        }
    }
}

data class TokenSpan(
    val text: String,
    val start: Int,
    val end: Int,
    val isWord: Boolean,
)

/** Absolute word timing supplied by a format-specific parser (karaoke tags). */
data class TokenTiming(val startMs: Long, val endMs: Long)
