package com.sublearn.core.subtitles

/**
 * The subtitle containers SubLearn reads.
 *
 * Detection order is "extension, then content" (see [SubtitleParsers.detect]) because the extension
 * is the author's own claim about the file and is the cheapest reliable signal; the content check is
 * what saves a `.srt` that is really VTT.
 */
enum class SubtitleFormat(
    /** Canonical extension, without the dot. */
    val extension: String,
    /** Extensions accepted for this format. */
    val aliases: Set<String>,
) {
    SRT("srt", setOf("srt", "sub", "txt")),
    VTT("vtt", setOf("vtt", "webvtt")),
    ASS("ass", setOf("ass", "ssa", "aqt")),
    UNKNOWN("txt", emptySet()),
    ;

    val isAdvancedStyling: Boolean get() = this == ASS

    companion object {
        /** Never fails: an unrecognised extension comes back as [UNKNOWN] and the caller sniffs. */
        fun fromFileName(name: String): SubtitleFormat {
            val extension = name.substringAfterLast('.', "").lowercase()
            if (extension.isEmpty()) return UNKNOWN
            return entries.firstOrNull { format -> extension in format.aliases } ?: UNKNOWN
        }

        /**
         * True for extensions that only subtitles use. `.txt` parses as SRT when the user picks it,
         * but auto-loading must not treat every text file next to a video as a subtitle.
         */
        fun isUnambiguousName(name: String): Boolean {
            val extension = name.substringAfterLast('.', "").lowercase()
            return extension.isNotEmpty() && extension != AMBIGUOUS_EXTENSION && fromFileName(name) != UNKNOWN
        }

        private const val AMBIGUOUS_EXTENSION = "txt"

        fun fromMime(mimeType: String?): SubtitleFormat = when (mimeType?.lowercase()) {
            "text/vtt", "application/vtt", "application/mp4vtt" -> VTT
            "text/x-ssa", "text/x-ass", "application/ass" -> ASS
            "application/x-subrip", "text/srt", "application/srt" -> SRT
            else -> UNKNOWN
        }
    }
}
