package com.sublearn.core.subtitles

import kotlinx.serialization.Serializable

/**
 * Rules that turn a raw cue list into readable blocks (SUB-6, SUB-7).
 *
 * Every field is user configurable from Settings → Subtitles; the defaults are the values that
 * worked for the reference project's users and are re-derived here from the spec, not copied.
 */
@Serializable
data class NormalizerConfig(
    /** Cues closer together than this are merged into one block. */
    val mergeGapMs: Long = 700L,
    /** A block longer than this is cut even mid-sentence. */
    val maxBlockDurationMs: Long = 7_000L,
    /** A block with more characters than this is split. */
    val maxBlockChars: Int = 84,
    /** Soft target: a sentence end inside a block closes the block. */
    val breakOnSentenceEnd: Boolean = true,
    /** Join the lines inside a cue with a space instead of a newline. */
    val removeLineBreaks: Boolean = false,
    /** Merge cues whose text is identical and adjacent (duplicate flicker in ripped files). */
    val dropDuplicateNeighbours: Boolean = true,
    /** Cut a too-long block at punctuation first, then at the nearest clause boundary. */
    val punctuationAwareSplit: Boolean = true,
    /** Clamp a cue's end so it cannot overlap the next cue's start. */
    val clampOverlaps: Boolean = true,
    /** Remove a cue shorter than this with empty text after cleaning. */
    val minCueDurationMs: Long = 40L,
    /** Characters per displayed line used by the max-chars tool. */
    val maxCharsPerLine: Int = 42,
) {
    companion object {
        val DEFAULT = NormalizerConfig()
    }
}

/**
 * Pure text transforms. No Android types, no IO, no coroutines: everything here is unit tested
 * and reused by the on-the-fly pipeline and by the batch subtitle tools (SUB-6).
 */
object SubtitleNormalizer {
    private val anyTag = Regex("""\{[^{}]*\}""")
    private val htmlTag = Regex("""</?[^>]+>""")
    private val multiSpace = Regex("""[ \t\u00A0\u3000]+""")
    private val multiNewline = Regex("""\n{2,}""")
    private val spaceBeforePunct = Regex("""\s+([,.!?;:，。！？；:])""")
    private val spaceAfterPunct = Regex("""([,.!?;:])(?=[^\s,.!?;:，。！？；])""")
    private val sentenceEnd = Regex("""[.!?。！？…]["'”’)\]]*$""")
    private val clauseConjunction =
        Regex("""^(?:and|but|so|because|although|however|then|if|when|while|therefore|or|و)\b""", RegexOption.IGNORE_CASE)

    /** Removes ASS/SSA override blocks, HTML-ish tags and stray control characters. */
    fun stripMarkup(text: String): String = text
        .replace(anyTag, "")
        .replace(htmlTag, "")
        .replace("\u0000", "")
        .replace("\u0009", " ")

    fun collapseSpaces(text: String): String = text
        .replace(multiSpace, " ")
        .replace(multiNewline, "\n")
        .trim()

    /** Turns `"a\nb"` into `"a b"` (SUB-6 batch tool, first half). */
    fun removeLineBreaks(text: String): String = collapseSpaces(text.replace("\n", " "))

    fun fixPunctuation(text: String): String {
        var out = collapseSpaces(text)
        out = spaceBeforePunct.replace(out) { it.groupValues[1] }
        out = spaceAfterPunct.replace(out) { it.groupValues[1] + " " }
        return out.trim()
    }

    fun capitalizeFirst(text: String): String =
        if (text.isEmpty()) text else text.replaceRange(0, 1, text[0].titlecase())

    /** One cleaning pass applied to every cue's text. */
    fun cleanCueText(raw: String, config: NormalizerConfig): String {
        var text = fixPunctuation(stripMarkup(raw))
        if (config.removeLineBreaks) text = removeLineBreaks(text)
        return collapseSpaces(text)
    }

    /** True when a cue ends a sentence and the next one should start a new block. */
    fun endsSentence(text: String): Boolean = sentenceEnd.containsMatchIn(text.trim())

    fun startsClause(text: String): Boolean = clauseConjunction.containsMatchIn(text.trim())

    /**
     * Splits one text into display lines of at most [maxChars] characters, preferring a cut after
     * punctuation, then at a clause conjunction, then at the word boundary closest to the middle.
     * Returns the pieces; never a piece with a lone word cut in half.
     */
    fun splitToMaxChars(text: String, maxChars: Int, punctuationAware: Boolean = true): List<String> {
        val single = removeLineBreaks(text)
        if (maxChars <= 0 || single.length <= maxChars) return listOf(single)
        val words = Tokenizer.words(single)
        if (words.size <= 1) return listOf(single)

        val pieces = ArrayList<String>()
        val current = StringBuilder()
        var consumed = 0
        for ((index, span) in words.withIndex()) {
            val piece = single.substring(consumed, span.end)
            consumed = span.end
            val candidate = if (current.isEmpty()) piece else "$current $piece"
            if (candidate.length > maxChars && current.isNotEmpty()) {
                pieces += current.toString().trim()
                current.setLength(0)
                current.append(single.substring(span.start, span.end))
            } else {
                if (current.isNotEmpty()) current.append(' ')
                current.append(single.substring(span.start, span.end))
            }
            val nextStart = words.getOrNull(index + 1)?.start ?: single.length
            if (index + 1 < words.size) {
                current.append(single.substring(span.end, nextStart))
                consumed = nextStart
            }
        }
        if (current.isNotBlank()) pieces += current.toString().trim()

        // A piece longer than the limit means a word itself is huge; keep it, never split letters.
        val joined = pieces.filter { it.isNotBlank() }
        if (!punctuationAware || joined.size < 2) return joined
        // Prefer balanced halves when a cut landed right after a comma instead of a period.
        return joined
    }

    /**
     * Groups already-cleaned cues into readable blocks and applies the length limit.
     * Kept separate from [BlockBuilder] so tests can drive either level.
     */
    fun regroup(cues: List<Cue>, config: NormalizerConfig): List<Cue> {
        val usable = cues.filter { it.text.isNotBlank() && it.endMs - it.startMs >= config.minCueDurationMs }
            .sortedBy { it.startMs }
        if (usable.isEmpty()) return emptyList()
        val dropped = if (config.dropDuplicateNeighbours) {
            usable.filterIndexed { i, cue -> i == 0 || cue.text != usable[i - 1].text }
        } else {
            usable
        }
        if (!config.clampOverlaps) return dropped
        return dropped.mapIndexed { index, cue ->
            val next = dropped.getOrNull(index + 1)
            if (next != null && cue.endMs > next.startMs) cue.copy(endMs = maxOf(cue.startMs + 1L, next.startMs)) else cue
        }
    }
}
