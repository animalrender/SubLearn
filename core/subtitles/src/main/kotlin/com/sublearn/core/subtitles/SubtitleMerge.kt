package com.sublearn.core.subtitles

/**
 * Stacks several files inside one layer (PLY-8).
 *
 * A layer may have any number of subtitle files switched on at once. Rendering each file as its own
 * plate would need a placement per file, which the layer model does not have, so the files are
 * merged into one document whose blocks are ordered by start time and renumbered.
 *
 * Block ids are rebuilt as `(fileIndex << 32) | (blockIndex + 1)`, which keeps them unique across
 * files while staying stable for the same set of files: the subtitle list, the shadowing run and
 * the word styling all key on a block id.
 */
object SubtitleMerge {
    fun merged(role: TrackRole, documents: List<SubtitleDocument>): SubtitleDocument? = when (documents.size) {
        0 -> null
        1 -> documents.first()
        else -> {
            val blocks = documents.flatMapIndexed { fileIndex, document ->
                document.blocks.mapIndexed { blockIndex, block ->
                    block.copy(id = (fileIndex.toLong() shl ID_FILE_SHIFT) or (blockIndex + 1L).toLong())
                }
            }.sortedBy { it.startMs }
            val track = SubtitleTrack(
                id = "merged:" + role.name.lowercase(),
                name = documents.joinToString(" + ") { it.track.name },
                languageTag = documents.first().track.languageTag,
                role = role,
                origin = documents.first().track.origin,
                cues = documents.flatMap { it.cues },
            )
            SubtitleDocument(track, blocks)
        }
    }

    /** Blocks covering [timeMs] across every file, in the order the files were switched on. */
    fun blocksAt(documents: List<SubtitleDocument>, timeMs: Long): List<SubtitleBlock> =
        documents.mapNotNull { it.blockAt(timeMs) }

    /**
     * Text of every block covering [timeMs], one per line.
     *
     * The merged document's own `blockAt` returns a single block, so this is what the layer renders
     * when two files overlap at the same moment; a file with nothing at that time contributes
     * nothing, which is the point of stacking them.
     */
    fun textAt(documents: List<SubtitleDocument>, timeMs: Long): String? {
        val covering = blocksAt(documents, timeMs)
        return when (covering.size) {
            0 -> null
            1 -> covering.first().text
            else -> covering.joinToString("\n") { it.text }
        }
    }
}

/** Bits of a merged block id reserved for the file index. */
private const val ID_FILE_SHIFT = 32
