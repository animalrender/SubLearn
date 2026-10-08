package com.sublearn.core.subtitles

/**
 * One subtitle container format, read into a flat [Cue] list.
 *
 * Parsers are intentionally dumb: they turn text into timed entries and nothing else. Whitespace,
 * mid-sentence splits, block grouping and max-length re-cutting belong to [SubtitleNormalizer] and
 * [BlockBuilder], which is what keeps a new format (TTML, DFXP) a ~100-line file instead of a change
 * in the rendering path.
 *
 * Contracts every implementation keeps:
 *  - never throws on malformed input: an unparseable block is skipped, an unparseable file yields no cues;
 *  - cue ids are 1-based and in document order, regardless of the ids in the file;
 *  - `endMs` is always greater than `startMs`, so a one-frame cue still gets a hit-testable span;
 *  - [Cue.rawText] keeps the original markup for the lossless tools in SUB-6, while [Cue.text] is
 *    what the player renders.
 */
interface SubtitleParser {
    /** The format this parser claims, used for the badge in the layer options and for detection. */
    val format: SubtitleFormat

    /**
     * @param text the whole decoded file (charset already resolved by [CharsetSniffer]).
     * @param trackId stamped onto every cue so a block can be traced back to its track.
     */
    fun parse(text: String, trackId: String): List<Cue>
}

internal object SubtitleMarkup {
    private val entity = Regex("""&(amp|lt|gt|quot|apos|nbsp|#\d+|#x[0-9a-fA-F]+);""")
    private val named = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to "\u00a0",
    )

    /** Decodes the handful of entities subtitle files actually use, leaving anything odd alone. */
    fun decodeEntities(text: String): String = entity.replace(text) { match ->
        val token = match.groupValues[1]
        named[token] ?: numericCodePoint(token)?.toString() ?: match.value
    }

    /** `&#39;` and `&#x27;`; a code point outside the char range returns null and the text survives. */
    private fun numericCodePoint(token: String): Char? {
        val value = when {
            token.startsWith("#x") -> token.substring(2).toLongOrNull(16)
            token.startsWith("#") -> token.substring(1).toLongOrNull()
            else -> null
        } ?: return null
        if (value !in 0..0xFFFF) return null
        return runCatching { value.toInt().toChar() }.getOrNull()
    }
}
