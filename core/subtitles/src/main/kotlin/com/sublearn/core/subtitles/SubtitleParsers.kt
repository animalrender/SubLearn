package com.sublearn.core.subtitles

import com.sublearn.core.common.TimeUtils

/** Format detection and parser selection shared by every caller. */
object SubtitleParsers {
    private val srtCue = Regex("""^\s*\d+\s*\n\s*\d{1,2}:\d{2}""")
    private val timingLine = Regex("""\d{1,2}:\d{2}[:,.]\d{2,3}\s*-->\s*\d{1,2}:\d{2}""")

    fun forFormat(format: SubtitleFormat): SubtitleParser = when (format) {
        SubtitleFormat.SRT -> SrtParser
        SubtitleFormat.VTT -> WebVttParser
        SubtitleFormat.ASS -> AssParser
        SubtitleFormat.UNKNOWN -> SrtParser
    }

    /**
     * Uses the extension first (it is the author's own claim), then the content: a `WEBVTT`
     * header, an `[Events]` section, or an SRT-style arrow. A `.srt` file that turns out to hold
     * VTT is still parsed correctly because each parser tolerates the other's timing lines.
     */
    fun detect(fileName: String, text: String): SubtitleFormat {
        val byName = SubtitleFormat.fromFileName(fileName)
        val head = text.take(4096)
        return when {
            head.trimStart().startsWith("WEBVTT") -> SubtitleFormat.VTT
            head.contains("[Events]", ignoreCase = true) -> SubtitleFormat.ASS
            head.contains("[Script Info]", ignoreCase = true) -> SubtitleFormat.ASS
            byName != SubtitleFormat.UNKNOWN -> byName
            srtCue.containsMatchIn(head) -> SubtitleFormat.SRT
            timingLine.containsMatchIn(head) -> SubtitleFormat.SRT
            else -> SubtitleFormat.UNKNOWN
        }
    }

    fun originFor(format: SubtitleFormat): TrackOrigin = when (format) {
        SubtitleFormat.SRT, SubtitleFormat.UNKNOWN -> TrackOrigin.EXTERNAL_SRT
        SubtitleFormat.VTT -> TrackOrigin.EXTERNAL_VTT
        SubtitleFormat.ASS -> TrackOrigin.EXTERNAL_ASS
    }

    /** Serialises cues back to SRT; used by the batch subtitle tools (SUB-6) when exporting. */
    fun toSrt(cues: List<Cue>): String = buildString {
        cues.forEachIndexed { index, cue ->
            append(index + 1)
            append('\n')
            append(TimeUtils.formatTimestamp(cue.startMs, ','))
            append(" --> ")
            append(TimeUtils.formatTimestamp(cue.endMs, ','))
            append('\n')
            append(cue.text)
            append("\n\n")
        }
    }
}
