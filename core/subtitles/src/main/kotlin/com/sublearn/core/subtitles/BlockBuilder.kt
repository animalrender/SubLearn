package com.sublearn.core.subtitles

/**
 * Turns a cleaned cue list into [SubtitleBlock]s: the unit that is displayed, repeated, searched,
 * translated and sent to the AI. Split mid-sentence cues (the single most common ripped-file
 * problem) are joined, over-long blocks are re-cut at punctuation.
 */
class BlockBuilder(
    private val config: NormalizerConfig = NormalizerConfig.DEFAULT,
) {
    /** Cleans, regroups and groups in one call; the entry point every caller uses. */
    fun build(cues: List<Cue>, trackId: String = ""): List<SubtitleBlock> =
        buildRegrouped(SubtitleNormalizer.regroup(cues, config), trackId)

    /** Groups an already-regrouped cue list, so a caller that cleaned the text does not pay twice. */
    private fun buildRegrouped(regroupedCues: List<Cue>, trackId: String): List<SubtitleBlock> {
        if (regroupedCues.isEmpty()) return emptyList()
        val blocks = ArrayList<SubtitleBlock>(regroupedCues.size)
        var blockCues = mutableListOf<Cue>()

        fun flush() {
            if (blockCues.isEmpty()) return
            val merged = mergeInto(blockCues, trackId, blocks.size.toLong())
            for (piece in splitIfNeeded(merged)) {
                blocks += piece
            }
            blockCues = mutableListOf()
        }

        regroupedCues.forEachIndexed { index, cue ->
            if (blockCues.isEmpty()) {
                blockCues += cue
                return@forEachIndexed
            }
            val previous = blockCues.last()
            val gap = cue.startMs - previous.endMs
            val duration = cue.endMs - blockCues.first().startMs
            val combinedLength = blocksText(blockCues).length + cue.text.length + 1
            val mustClose = gap > config.mergeGapMs ||
                duration > config.maxBlockDurationMs ||
                combinedLength > config.maxBlockChars ||
                (config.breakOnSentenceEnd && SubtitleNormalizer.endsSentence(previous.text))
            if (mustClose) {
                flush()
                blockCues += cue
            } else {
                blockCues += cue
            }
            if (index == regroupedCues.lastIndex) flush()
        }
        flush()
        return blocks
    }

    private fun blocksText(cues: List<Cue>): String = cues.joinToString(" ") { it.text }

    private fun mergeInto(cues: List<Cue>, trackId: String, ordinal: Long): SubtitleBlock {
        val startMs = cues.minOf { it.startMs }
        val endMs = cues.maxOf { it.endMs }
        val joined = buildString {
            cues.forEachIndexed { index, cue ->
                if (index > 0) append(if (cue.text.startsWith("'")) "" else " ")
                append(cue.text)
            }
        }.let { SubtitleNormalizer.fixPunctuation(it) }
        // Offset the word timings of later cues by the gap to the previous cue's end, so the
        // per-word highlight stays in sync across a merged block.
        val tokens = collectTokens(cues, joined, startMs, endMs, trackId)
        return SubtitleBlock(
            id = ordinal,
            startMs = startMs,
            endMs = endMs.coerceAtLeast(startMs + 1L),
            text = joined,
            trackId = trackId,
            cues = cues,
            tokens = tokens,
        )
    }

    private fun collectTokens(
        cues: List<Cue>,
        joined: String,
        startMs: Long,
        endMs: Long,
        trackId: String,
    ): List<CueToken> {
        val hasPreciseTiming = cues.any { cue -> cue.tokens.isNotEmpty() && cue.tokens.all { it.endMs > it.startMs } }
        if (!hasPreciseTiming) return Tokenizer.toTokens(joined, startMs, endMs, trackId)
        val offsets = ArrayList<Int>(cues.size)
        var cursor = 0
        for (cue in cues) {
            offsets += cursor
            cursor += cue.text.length + 1
        }
        val out = ArrayList<CueToken>()
        cues.forEachIndexed { cueIndex, cue ->
            val offset = offsets[cueIndex]
            val source = if (cue.tokens.isNotEmpty()) {
                cue.tokens
            } else {
                Tokenizer.toTokens(cue.text, cue.startMs, cue.endMs, cue.trackId)
            }
            for (token in source) {
                val start = token.charStart + offset
                val end = token.charEnd + offset
                if (start >= joined.length || end > joined.length) continue
                out += token.copy(
                    charStart = start,
                    charEnd = end,
                    text = joined.substring(start, minOf(end, joined.length)),
                )
            }
        }
        return out.sortedBy { it.charStart }
    }

    private fun splitIfNeeded(block: SubtitleBlock): List<SubtitleBlock> {
        if (config.maxBlockChars <= 0 || block.text.length <= config.maxBlockChars) return listOf(block)
        val pieces = SubtitleNormalizer.splitToMaxChars(block.text, config.maxBlockChars, config.punctuationAwareSplit)
        if (pieces.size <= 1) return listOf(block)
        val share = block.durationMs / pieces.size
        return pieces.mapIndexed { index, piece ->
            SubtitleBlock(
                id = block.id,
                startMs = block.startMs + share * index,
                endMs = block.startMs + share * (index + 1),
                text = piece,
                trackId = block.trackId,
                cues = block.cues,
                tokens = Tokenizer.toTokens(piece, block.startMs + share * index, block.startMs + share * (index + 1), block.trackId),
            )
        }
    }
}
