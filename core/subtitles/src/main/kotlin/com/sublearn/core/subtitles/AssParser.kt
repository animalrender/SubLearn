package com.sublearn.core.subtitles

/**
 * ASS / SSA, text only (no colour or positioning replay: SubLearn owns styling, so player-side
 * tags would fight the design system and the two-layer layout).
 *
 * Word timing comes from the karaoke tags `\k`, `\kf`, `\ko`, `\K` when present, which is what
 * makes the AI re-segmentation and word-level highlighting plans possible without a model.
 */
object AssParser : SubtitleParser {
    override val format = SubtitleFormat.ASS

    private val time = Regex("""(\d+):(\d{2}):(\d{2})\.(\d{2,3})""")

    override fun parse(text: String, trackId: String): List<Cue> {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        var inEvents = false
        var formatFields: List<String> = emptyList()
        val cues = ArrayList<Cue>(lines.size / 4 + 1)
        var nextId = 1
        for (rawLine in lines) {
            val line = rawLine.trim()
            when {
                line.equals("[Events]", ignoreCase = true) -> {
                    inEvents = true
                    if (formatFields.isEmpty()) formatFields = DEFAULT_FORMAT
                }
                line.equals("[Script Info]", ignoreCase = true) ||
                    line.equals("[V4+ Styles]", ignoreCase = true) ||
                    line.equals("[V4 Styles]", ignoreCase = true) -> inEvents = false
                inEvents && line.startsWith("Format:", ignoreCase = true) ->
                    formatFields = line.substringAfter(':').split(',').map { it.trim() }
                inEvents && line.startsWith("Dialogue:", ignoreCase = true) -> {
                    val cue = parseDialogue(line.substringAfter(':'), formatFields, trackId, nextId)
                    if (cue != null) {
                        cues += cue
                        nextId++
                    }
                }
            }
        }
        return cues
    }

    private val DEFAULT_FORMAT = listOf(
        "Layer", "Start", "End", "Style", "Name", "MarginL", "MarginR", "MarginV", "Effect", "Text",
    )

    private fun parseDialogue(body: String, fields: List<String>, trackId: String, id: Int): Cue? {
        // The Text field may itself contain commas, so split only up to its declared position.
        val startIndex = fields.indexOfFirst { it.equals("Start", ignoreCase = true) }.coerceAtLeast(0)
        val endIndex = fields.indexOfFirst { it.equals("End", ignoreCase = true) }.coerceAtLeast(1)
        val textField = fields.indexOfFirst { it.equals("Text", ignoreCase = true) }
            .let { if (it < 0) fields.size - 1 else it }
        val pieces = body.split(',', limit = textField + 1)
        val start = parseTime(pieces.getOrNull(startIndex).orEmpty()) ?: return null
        val end = parseTime(pieces.getOrNull(endIndex).orEmpty()) ?: return null
        val raw = pieces.getOrNull(textField).orEmpty()
        val processed = AssTextProcessor.process(raw, start, end)
        if (processed.text.isBlank()) return null
        val language = Regex("""\|\|lang\(([^)]+)\)\s*$""").find(processed.text)?.groupValues?.get(1)
        val cleanText = processed.text.replace(Regex("""\|\|lang\([^)]+\)\s*$"""), "").trim()
        return Cue(
            id = id,
            startMs = start,
            endMs = maxOf(end, start + 1L),
            text = cleanText,
            rawText = raw,
            trackId = trackId,
            tokens = Tokenizer.toTokens(cleanText, start, end, trackId, processed.timings),
        )
    }

    private fun parseTime(value: String): Long? {
        val match = time.find(value) ?: return Timecode.parse(value)
        val hours = match.groupValues[1].toLongOrNull() ?: return null
        val minutes = match.groupValues[2].toLongOrNull() ?: return null
        val seconds = match.groupValues[3].toLongOrNull() ?: return null
        val fraction = match.groupValues[4]
        val ms = fraction.toLongOrNull()?.let { if (fraction.length <= 2) it * 10L else it } ?: 0L
        return hours * 3_600_000L + minutes * 60_000L + seconds * 1_000L + ms
    }
}

/** ASS dialogue text with the styling tags removed and karaoke word timings extracted. */
data class AssProcessedText(val text: String, val timings: List<TokenTiming>?)

object AssTextProcessor {
    private val commentBlock = Regex("""\{[^{}]*\}""")
    private val durationTag = Regex("""\\[kK][nof]?(\d+)""")

    fun process(raw: String, startMs: Long, endMs: Long): AssProcessedText {
        var text = raw
        // Karaoke durations in centiseconds, in order of appearance.
        val durations = commentBlock.findAll(raw).flatMap { match ->
            durationTag.findAll(match.value).mapNotNull { it.groupValues[1].toLongOrNull()?.let { cs -> cs * 10L } }
        }.toList()

        text = text.replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ")
        text = text.replace(commentBlock, "")
        text = text.replace(Regex("""\\[aAbBpP]\s*"""), "")
        text = text.replace(Regex("""\{[^{}]*$"""), "") // unterminated tag at line end
        text = text.trim().replace(Regex("[ \t]+"), " ")

        if (durations.isEmpty()) return AssProcessedText(text, null)
        val words = Tokenizer.words(text)
        if (words.isEmpty()) return AssProcessedText(text, null)
        // ASS karaoke tags cover only the syllables that are sung/spoken; extra words get the
        // leftover time spread evenly so the sequence stays monotonic.
        var cursor = startMs
        val timings = ArrayList<TokenTiming>(words.size)
        words.forEachIndexed { index, span ->
            val duration = durations.getOrNull(index)
                ?: if (durations.isNotEmpty()) durations.average().toLong() else 0L
            val fallback = if (duration > 0L) duration else ((endMs - startMs).coerceAtLeast(1L) / words.size)
            val end = (cursor + fallback).coerceAtMost(endMs.coerceAtLeast(cursor + 1L))
            timings += TokenTiming(startMs = cursor, endMs = maxOf(cursor, end))
            cursor = maxOf(cursor, end)
        }
        return AssProcessedText(text, timings)
    }
}
