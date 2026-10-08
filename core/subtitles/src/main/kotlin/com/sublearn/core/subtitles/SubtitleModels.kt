package com.sublearn.core.subtitles

/** Which subtitle layer a track belongs to. Two independent layers exist (SUB-1, PLY-7). */
enum class TrackRole {
    /** The language being learned (default: English). */
    LEARNING,

    /** The user's native language, shown as the secondary layer (default: Persian). */
    TRANSLATION,
    ;

    companion object {
        fun fromKey(key: String): TrackRole = entries.firstOrNull { it.name.equals(key, ignoreCase = true) } ?: LEARNING
    }
}

enum class TrackOrigin {
    EMBEDDED,
    EXTERNAL_SRT,
    EXTERNAL_VTT,
    EXTERNAL_ASS,
    GENERATED,
    MEMORY,
}

/** Which language role a text run uses; drives direction and font surface. */
enum class TextDirection {
    LTR,
    RTL,
    ;

    companion object {
        /**
         * Direction of a single text run, decided per run (GEN-4) rather than per app: the first
         * strong character wins; digits and punctuation never decide direction.
         */
        fun of(text: String): TextDirection {
            for (ch in text) {
                if (isRtlChar(ch)) return RTL
                if (ch.code in 0x41..0x7A || ch.code in 0xC0..0x24F) return LTR
            }
            return LTR
        }

        fun isRtlChar(ch: Char): Boolean {
            val c = ch.code
            return c in 0x0590..0x05FF || // Hebrew
                c in 0x0600..0x06FF || // Arabic
                c in 0x0750..0x077F || // Arabic supplement
                c in 0x08A0..0x08FF || // Arabic extended A
                c in 0xFB1D..0xFB4F || // presentation forms A
                c in 0xFB50..0xFDFF || // Arabic presentation forms A
                c in 0xFE70..0xFEFF // Arabic presentation forms B
        }
    }
}

/**
 * One word-like unit inside a cue, with an optional precise time.
 *
 * [charStart]/[charEnd] are offsets into the cue's display [Cue.text] (not into markup) so that
 * hit testing can map a touch offset straight to a token.
 */
data class CueToken(
    val text: String,
    val charStart: Int,
    val charEnd: Int,
    val startMs: Long,
    val endMs: Long,
    val direction: TextDirection = TextDirection.LTR,
    val isWord: Boolean = true,
) {
    val isPunctuation: Boolean get() = !isWord
}

/** A raw subtitle entry before block merging. */
data class Cue(
    val id: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val trackId: String = "",
    val tokens: List<CueToken> = emptyList(),
    /** Original markup line, kept so re-serialisation is lossless for the tools in SUB-6. */
    val rawText: String = text,
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
}

/** A readable group of cues: the unit users repeat, translate and seek by. */
data class SubtitleBlock(
    val id: Long,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val trackId: String,
    val cues: List<Cue>,
    val tokens: List<CueToken> = emptyList(),
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)

    /** Cue ids in order, used by the subtitle list and by the AI context builder. */
    val cueIds: List<Int> get() = cues.map { it.id }
}

/** A parsed, normalised track with its blocks and a time index. */
class SubtitleDocument(
    val track: SubtitleTrack,
    val blocks: List<SubtitleBlock>,
) {
    val cues: List<Cue> get() = track.cues

    private val blockIndex: List<Long> = blocks.map { it.startMs }

    fun isEmpty(): Boolean = blocks.isEmpty()

    /** O(log n) lookup of the block covering [timeMs], if any. */
    fun blockAt(timeMs: Long): SubtitleBlock? {
        if (blocks.isEmpty()) return null
        var lo = 0
        var hi = blockIndex.size - 1
        var candidate = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (blockIndex[mid] <= timeMs) {
                candidate = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        if (candidate < 0) return blocks.first().takeIf { timeMs < it.endMs }
        val block = blocks[candidate]
        return if (timeMs < block.endMs) block else null
    }

    /** Index of [blockId] or -1. */
    fun indexOf(blockId: Long): Int = blocks.indexOfFirst { it.id == blockId }

    fun blockById(blockId: Long): SubtitleBlock? = blocks.firstOrNull { it.id == blockId }

    /** Next block that starts at or after [timeMs] + [epsilonMs]; null at the end of the file. */
    fun nextBlock(afterTimeMs: Long, epsilonMs: Long = 1L): SubtitleBlock? =
        blocks.firstOrNull { it.startMs > afterTimeMs + epsilonMs }

    fun previousBlock(beforeTimeMs: Long): SubtitleBlock? = blocks.lastOrNull { it.startMs < beforeTimeMs - 1L }

    fun blocksInRange(fromMs: Long, toMs: Long): List<SubtitleBlock> =
        blocks.filter { it.endMs > fromMs && it.startMs < toMs }

    /** The [count] blocks ending just before [index], for AI context (AI-4). */
    fun blocksBefore(index: Int, count: Int): List<SubtitleBlock> {
        if (count <= 0 || index <= 0) return emptyList()
        val from = (index - count).coerceAtLeast(0)
        return blocks.subList(from, index.coerceAtMost(blocks.size))
    }
}

/** One subtitle track as offered to the user. */
data class SubtitleTrack(
    val id: String,
    val name: String,
    val languageTag: String?,
    val role: TrackRole,
    val origin: TrackOrigin,
    val cues: List<Cue>,
    val delayMs: Long = 0L,
    /** Media3 track index for embedded tracks; null for external files. */
    val embeddedTrackIndex: Int? = null,
) {
    fun toDocument(): SubtitleDocument = SubtitleDocument(this, BlockBuilder().build(cues, id))

    fun withDelay(delayMs: Long): SubtitleTrack = copy(delayMs = delayMs)

    /** Cues shifted by the track delay; keeps the document consistent for hit testing. */
    fun shiftedCues(): List<Cue> =
        if (delayMs == 0L) cues else cues.map { it.copy(startMs = it.startMs + delayMs, endMs = it.endMs + delayMs) }
}
