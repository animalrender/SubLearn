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
    private val spaceBeforePunct = Regex("""\s+([,.!?;:،؛؟，。！？；])""")

    /** A comma-like mark glued to a following letter: `Hi,world` but not `3,5` or `e.g.`. */
    private val spaceAfterComma = Regex("""([,;!?،؛؟])(?=\p{L})""")

    /** A period or colon between a lowercase letter and an uppercase one: `end.Next`, never `3.5` or `10:30`. */
    private val spaceAfterPeriod = Regex("""(?<=\p{Ll})([.:])(?=\p{Lu})""")
    private val continuationWords = setOf(
        "and", "but", "or", "so", "because", "if", "when", "while", "that", "which", "who", "to", "the",
        "a", "an", "of", "in", "on", "at", "for", "with", "from", "by", "as", "than", "into", "about",
        "و", "که", "به", "از", "با", "در", "برای", "تا", "یا", "اما", "ولی", "اگر", "چون",
    )
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

    /**
     * Removes the space before a punctuation mark and restores the missing one after it. The rules
     * are deliberately narrow so numbers (`3.5`, `10:30`, `1,000`), abbreviations (`e.g.`) and URLs
     * come through untouched.
     */
    fun fixPunctuation(text: String): String {
        var out = collapseSpaces(text)
        out = spaceBeforePunct.replace(out) { it.groupValues[1] }
        out = spaceAfterComma.replace(out) { it.groupValues[1] + " " }
        out = spaceAfterPeriod.replace(out) { it.groupValues[1] + " " }
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
     * True when [next] reads as the continuation of [previous]: the first cue stops mid-sentence
     * (no end mark, or it ends on a comma, dash or a function word) and the second one does not
     * start a new sentence with a capital. Uncased scripts (Persian, Chinese) and digits count as a
     * continuation; the English pronoun `I` is capitalised everywhere, so it does too.
     */
    fun continuesSentence(previous: String, next: String): Boolean {
        val before = previous.trim()
        val after = next.trim()
        if (before.isEmpty() || after.isEmpty() || endsSentence(before)) return false
        if (before.last() in ",;:-–—") return true
        val lastWord = Tokenizer.words(before).lastOrNull()?.text?.lowercase()
        if (lastWord != null && lastWord in continuationWords) return true
        val first = after.firstOrNull { it.isLetterOrDigit() } ?: return true
        if (!first.isUpperCase() && !first.isTitleCase()) return true
        val firstWord = Tokenizer.words(after).firstOrNull()?.text ?: return false
        return firstWord == "I" || firstWord.startsWith("I'") || firstWord.startsWith("I\u2019")
    }

    /**
     * Splits one text into pieces of at most [maxChars] characters, cutting only between words.
     * With [punctuationAware] a cut after punctuation is preferred when it keeps the piece
     * reasonably full (at least [PUNCTUATION_FILL] of the limit); otherwise the piece is filled up
     * to the limit. A single word longer than the limit is kept whole, never cut into letters.
     * Joining the pieces with one space gives back the single-line text.
     */
    fun splitToMaxChars(text: String, maxChars: Int, punctuationAware: Boolean = true): List<String> {
        val single = removeLineBreaks(text)
        if (maxChars <= 0 || single.length <= maxChars) return listOf(single)
        val words = single.split(' ').filter { it.isNotEmpty() }
        if (words.size <= 1) return listOf(single)

        val pieces = ArrayList<String>()
        var index = 0
        while (index < words.size) {
            var end = index + 1
            var length = words[index].length
            var lastPunctuation = -1
            if (endsWithPunctuation(words[index])) lastPunctuation = end
            while (end < words.size && length + 1 + words[end].length <= maxChars) {
                length += 1 + words[end].length
                end++
                if (endsWithPunctuation(words[end - 1])) lastPunctuation = end
            }
            val cut = if (punctuationAware && end < words.size && lastPunctuation > index) {
                val filled = words.subList(index, lastPunctuation).sumOf { it.length } + (lastPunctuation - index - 1)
                if (filled >= maxChars * PUNCTUATION_FILL) lastPunctuation else end
            } else {
                end
            }
            pieces += words.subList(index, cut).joinToString(" ")
            index = cut
        }
        return pieces
    }

    private fun endsWithPunctuation(word: String): Boolean {
        val last = word.lastOrNull { it != '"' && it != '\'' && it != '\u201d' && it != '\u2019' && it != ')' } ?: return false
        return last in ".,!?;:…،؛؟。！？"
    }

    /** Smallest share of the limit a punctuation-aware cut may leave in the piece before it. */
    private const val PUNCTUATION_FILL = 0.4

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
