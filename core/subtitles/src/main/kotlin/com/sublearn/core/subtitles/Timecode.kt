package com.sublearn.core.subtitles

/**
 * Timecode parsing shared by all three subtitle formats.
 *
 * Accepted shapes: `hh:mm:ss,mmm`, `hh:mm:ss.mmm`, `mm:ss.mmm`, `ss,mmm`, `h:mm:ss.cc` (ASS) and
 * bare seconds. Anything else returns null so a malformed file degrades to "no cues" instead of
 * throwing in the middle of playback.
 */
object Timecode {
    private val parts = Regex("""(\d{1,3}):(\d{1,2})(?::(\d{1,2}))?(?:[.,](\d{1,3}))?""")

    fun parse(value: String): Long? {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return null
        val match = parts.find(trimmed) ?: return null
        val first = match.groupValues[1].toLongOrNull() ?: return null
        val second = match.groupValues[2].toLongOrNull() ?: return null
        val third = match.groupValues[3].toLongOrNull()
        val fraction = match.groupValues[4]
        val hours: Long
        val minutes: Long
        val seconds: Long
        if (third != null) {
            hours = first
            minutes = second
            seconds = third
        } else {
            hours = 0
            minutes = first
            seconds = second
        }
        val fractionMs = when (fraction.length) {
            0 -> 0L
            // ASS uses centiseconds ("50" -> 500ms); a single digit is tenths of a second.
            1 -> fraction.toLong() * 100L
            2 -> fraction.toLong() * 10L
            else -> fraction.take(3).toLong()
        }
        return hours * 3_600_000L + minutes * 60_000L + seconds * 1_000L + fractionMs
    }

    /**
     * Splits a timing line such as `00:00:01,000 --> 00:00:03,000 align:start position:10%`.
     * Returns null when either timecode is unreadable.
     */
    fun parseRange(line: String): TimecodeRange? {
        val arrowIndex = line.indexOf("-->")
        if (arrowIndex < 0) return null
        val left = line.substring(0, arrowIndex)
        val right = line.substring(arrowIndex + 3).trim()
        val start = parse(left) ?: return null
        // The end timecode ends at the first whitespace; everything after it is WebVTT cue settings.
        val endToken = right.takeWhile { !it.isWhitespace() }
        val end = parse(endToken) ?: return null
        val settings = right.substring(endToken.length).trim()
        return TimecodeRange(startMs = start, endMs = end, settings = settings)
    }

    data class TimecodeRange(val startMs: Long, val endMs: Long, val settings: String = "")
}
