package com.sublearn.core.subtitles

/**
 * SubRip (.srt) — the format everyone has, so it is also the fallback for anything unrecognised.
 *
 * A block is `index? / timing / text lines`, separated from the next by a blank line. Everything here
 * is forgiving on purpose: missing indices, CRLF, a comma or a dot for milliseconds, and `-->` with
 * extra spaces all parse, because ripped files are messy and a dropped cue is better than a dropped
 * file.
 */
object SrtParser : SubtitleParser {
    override val format = SubtitleFormat.SRT

    private val timing = Regex(
        """(\d{1,3}:\d{1,2}(?::\d{1,2})?[.,]\d{1,3})\s*-->\s*(\d{1,3}:\d{1,2}(?::\d{1,2})?[.,]\d{1,3})""",
    )

    override fun parse(text: String, trackId: String): List<Cue> {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val cues = ArrayList<Cue>(lines.size / 4 + 1)
        var block = mutableListOf<String>()
        fun flush() {
            if (block.isEmpty()) return
            parseBlock(block, trackId, cues.size + 1)?.let { cues += it }
            block = mutableListOf()
        }
        for (raw in lines) {
            if (raw.isBlank()) flush() else block += raw.trim()
        }
        flush()
        return cues
    }

    private fun parseBlock(block: List<String>, trackId: String, id: Int): Cue? {
        val timingIndex = block.indexOfFirst { it.contains("-->") }
        if (timingIndex < 0) return null
        val match = timing.find(block[timingIndex]) ?: return null
        val startMs = Timecode.parse(match.groupValues[1]) ?: return null
        val declaredEnd = Timecode.parse(match.groupValues[2]) ?: return null
        val rawText = block.drop(timingIndex + 1).joinToString("\n").trim()
        if (rawText.isEmpty()) return null
        val text = SubtitleNormalizer.collapseSpaces(
            SubtitleMarkup.decodeEntities(SubtitleNormalizer.stripMarkup(rawText)),
        )
        // A cue with no visible text after markup removal is dropped, and a zero-length range would
        // make the block unhittable, so a one-millisecond floor keeps both cases harmless.
        if (text.isBlank()) return null
        return Cue(
            id = id,
            startMs = startMs,
            endMs = (declaredEnd - startMs).coerceAtLeast(1L) + startMs,
            text = text,
            trackId = trackId,
            rawText = rawText,
            tokens = Tokenizer.toTokens(text, startMs, (declaredEnd - startMs).coerceAtLeast(1L) + startMs, trackId),
        )
    }
}
