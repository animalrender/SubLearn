package com.sublearn.feature.settings

import com.sublearn.core.nlp.PhraseSpan
import com.sublearn.core.nlp.SenseCandidate
import com.sublearn.core.subtitles.Tokenizer

/** One word of the text in the lab, with its place in the cleaned text it was analysed from (D-93). */
data class LabTextWord(
    /** Position among the words only, so it is stable whatever the punctuation around it. */
    val index: Int,
    val text: String,
    val start: Int,
    val end: Int,
)

/**
 * What the pack says about one word or phrase: its entry, its part of speech and CEFR level, every
 * meaning, and the meaning the ranker put first when the word was ranked.
 */
data class LabEntryInfo(
    val key: String,
    val pos: String?,
    val cefr: String?,
    val senses: List<SenseCandidate>,
    val topSense: SenseCandidate?,
)

/**
 * The analysis of one pasted text: the words, the phrases the analyser found in them, and what the
 * pack says about each. [wordInfo] and [spanInfo] line up by index with [words] and [spans]; a null
 * entry means the pack does not have it.
 */
data class LabTextAnalysis(
    val text: String,
    val words: List<LabTextWord>,
    val spans: List<PhraseSpan>,
    val wordInfo: List<LabEntryInfo?>,
    val spanInfo: List<LabEntryInfo?>,
)

/** What the current word selection means. */
sealed interface LabSelectionResult {
    /** Nothing is selected. */
    data object Empty : LabSelectionResult

    /** The selection is exactly the words of one detected phrase. */
    data class Phrase(val span: PhraseSpan, val info: LabEntryInfo?) : LabSelectionResult

    /**
     * The selection is not one detected phrase, so each selected word is shown on its own.
     * [partial] lists the detected phrases that share some of the selected words.
     */
    data class Words(val words: List<LabTextWord>, val infos: List<LabEntryInfo?>, val partial: List<PhraseSpan>) :
        LabSelectionResult
}

/**
 * The rules of the lab's free-text mode, kept free of Android so they can be unit-tested (D-93).
 *
 * A selection is a set of word indices. It matches a phrase only when the words wholly inside the
 * phrase's character range are exactly the selected words, so a phrasal verb with a split object
 * ("turn the offer down") matches only when the object is selected too.
 */
object LabTextSelection {

    /** Longest text the lab accepts; the phrase pass is per block and a paste is not a film. */
    const val MAX_CHARS = 2000

    /** The words of [text]: tokens with a letter in them, numbered in order. */
    fun words(text: String): List<LabTextWord> =
        Tokenizer.spans(text)
            .filter { it.isWord && it.text.any(Char::isLetter) }
            .mapIndexed { index, token -> LabTextWord(index, token.text, token.start, token.end) }

    /** The indices of the words lying wholly inside [span]. */
    fun wordsIn(span: PhraseSpan, words: List<LabTextWord>): Set<Int> =
        words.filter { it.start >= span.startChar && it.end <= span.endChar }.mapTo(HashSet()) { it.index }

    /** Words covered by any detected phrase, so the text can underline them. */
    fun coveredWords(spans: List<PhraseSpan>, words: List<LabTextWord>): Set<Int> =
        spans.flatMapTo(HashSet()) { wordsIn(it, words) }

    /** Flip one word in or out of the selection. */
    fun toggle(selected: Set<Int>, index: Int): Set<Int> =
        if (index in selected) selected - index else selected + index

    /** The most confident phrase whose words are exactly [selected], or null. */
    fun exactSpan(selected: Set<Int>, spans: List<PhraseSpan>, words: List<LabTextWord>): PhraseSpan? {
        if (selected.isEmpty()) return null
        return spans.filter { wordsIn(it, words) == selected }.maxByOrNull { it.confidence }
    }

    /** What [selected] means for [analysis]. */
    fun resolve(analysis: LabTextAnalysis, selected: Set<Int>): LabSelectionResult {
        if (selected.isEmpty()) return LabSelectionResult.Empty
        val spanIndex = analysis.spans.indexOfFirst { wordsIn(it, analysis.words) == selected }
        if (spanIndex >= 0) {
            return LabSelectionResult.Phrase(analysis.spans[spanIndex], analysis.spanInfo.getOrNull(spanIndex))
        }
        val chosen = analysis.words.filter { it.index in selected }
        val infos = chosen.map { analysis.wordInfo.getOrNull(it.index) }
        val partial = analysis.spans.filter { span -> wordsIn(span, analysis.words).any { it in selected } }
        return LabSelectionResult.Words(chosen, infos, partial)
    }
}
