package com.sublearn.core.nlp

/**
 * The type of multi-word expression detected.
 *
 * Each value maps to a distinct underline color and badge icon in the UI (D-40).
 */
enum class PhraseKind {
    PHRASAL,
    IDIOM,
    COLLOCATION,
}

/**
 * A span of text in a subtitle block (or across two blocks) that represents a
 * multi-word expression: a phrasal verb, an idiom or a collocation.
 *
 * All offsets are relative to the display text of the block(s) they belong to, so
 * the underline lands on the right glyphs regardless of normalisation.
 *
 * @property startChar start offset (inclusive) in the display text
 * @property endChar end offset (exclusive) in the display text
 * @property tokens the token indices within the block that this span covers
 * @property kind phrasal / idiom / collocation
 * @property headword the canonical form of the expression (e.g. "get off")
 * @property lemmaKey the key used to look up the entry in the lexicon
 * @property confidence a score between 0.0 and 1.0; higher means more certain
 * @property separable true for phrasal verbs that can split ("turn the offer down")
 * @property parts when the span crosses blocks, one part per block; single-element for in-block spans
 * @property alternatives non-winning spans that overlapped this one (for showing up to 2 phrases in the card)
 */
data class PhraseSpan(
    val startChar: Int,
    val endChar: Int,
    val tokens: List<Int>,
    val kind: PhraseKind,
    val headword: String,
    val lemmaKey: String,
    val confidence: Float,
    val separable: Boolean = false,
    val parts: List<SpanPart> = listOf(SpanPart(blockId = 0, startChar = startChar, endChar = endChar)),
    val alternatives: List<PhraseSpan> = emptyList(),
) {
    val isCrossBlock: Boolean get() = parts.size > 1

    /** Whether this span should be displayed with an underline (confidence above threshold). */
    fun isAboveThreshold(threshold: Float): Boolean = confidence >= threshold

    /** Whether this span meets the per-type threshold for visible underline. */
    fun isAboveTypeThreshold(config: NlpConfig): Boolean = confidence >= config.thresholdFor(kind)
}

/**
 * One piece of a cross-block span. For single-block spans there is exactly one part.
 *
 * @property blockId the block index within the subtitle document
 * @property startChar start offset within that block's display text
 * @property endChar end offset within that block's display text
 */
data class SpanPart(
    val blockId: Int,
    val startChar: Int,
    val endChar: Int,
)
