package com.sublearn.core.subtitles

/**
 * WebVTT (.vtt) — the format browser exports and YouTube rips use.
 *
 * Beyond SRT it has a header, `NOTE` and `STYLE` regions, optional cue identifiers, cue settings after
 * the timing arrow, and inline `<00:00:01.000>` tags that give per-word timing. The settings are
 * ignored (SubLearn places and sizes text itself, see GEN-3); the inline timings are kept, because
 * they are the only free word-level timing available and the AI re-segmentation plan needs them.
 */
object WebVttParser : SubtitleParser {
    override val format = SubtitleFormat.VTT

    private val timing = Regex(
        """(\d{1,3}:\d{1,2}(?::\d{1,2})?[.,]\d{1,3})\s*-->\s*(\d{1,3}:\d{1,2}(?::\d{1,2})?[.,]\d{1,3})""",
    )
    private val inlineTiming = Regex("""<\s*(\d{1,3}:\d{1,2}(?::\d{1,2})?[.,]\d{1,3})\s*>""")

    override fun parse(text: String, trackId: String): List<Cue> {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val cues = ArrayList<Cue>()
        var block = mutableListOf<String>()
        var skipRegion = false
        fun flush() {
            if (block.isEmpty()) return
            parseBlock(block, trackId, cues.size + 1)?.let { cues += it }
            block = mutableListOf()
        }
        lines.forEachIndexed { index, raw ->
            val line = raw.trim()
            when {
                index == 0 && line.startsWith("WEBVTT", ignoreCase = true) -> Unit
                line.isBlank() -> {
                    skipRegion = false
                    flush()
                }
                // NOTE and STYLE regions run to the next blank line and never produce text. A timing
                // line ends them early: files that forget the blank line are common and losing the
                // first cue is worse than reading a stray comment line.
                line.contains("-->") -> {
                    skipRegion = false
                    block += line
                }
                line.startsWith("NOTE", ignoreCase = true) -> skipRegion = true
                line.startsWith("STYLE", ignoreCase = true) -> skipRegion = true
                line.startsWith("Region", ignoreCase = true) -> skipRegion = true
                skipRegion -> Unit
                else -> block += line
            }
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
        val rawLines = block.drop(timingIndex + 1)
        if (rawLines.isEmpty()) return null
        val rawText = rawLines.joinToString("\n").trim()

        // Inline timings are collected before the tags are stripped, then dropped with the markup.
        val marks = inlineTiming.findAll(rawText).mapNotNull { Timecode.parse(it.groupValues[1]) }.toList()
        val withoutMarks = inlineTiming.replace(rawText, "")
        val text = SubtitleNormalizer.collapseSpaces(
            SubtitleMarkup.decodeEntities(SubtitleNormalizer.stripMarkup(withoutMarks)),
        )
        if (text.isBlank()) return null
        val endMs = (declaredEnd - startMs).coerceAtLeast(1L) + startMs
        val words = Tokenizer.words(text)
        // Only a complete, monotonic run of marks is trustworthy; anything else falls back to even
        // distribution inside the cue so word highlighting never points outside the cue's range.
        val usable = marks.size == words.size && marks.zipWithNext().all { (a, b) -> b >= a } &&
            marks.firstOrNull()?.let { it >= startMs && it <= endMs } == true
        val timings = if (usable) {
            marks.mapIndexed { i, mark -> TokenTiming(mark, marks.getOrNull(i + 1) ?: endMs) }
        } else {
            null
        }
        return Cue(
            id = id,
            startMs = startMs,
            endMs = endMs,
            text = text,
            trackId = trackId,
            rawText = rawText,
            tokens = Tokenizer.toTokens(text, startMs, endMs, trackId, timings),
        )
    }
}
