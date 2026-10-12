package com.sublearn.core.nlp

/**
 * A single subtitle cue or block slice in timeline order (D-45).
 */
data class CueSlice(
    val blockId: Long,
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

/**
 * Continuous cue-stream phrase analyzer (D-31 revised, D-45).
 *
 * Replaces `CrossBlockBridge` by building a continuous token stream across consecutive cues
 * where `next.startMs - prev.endMs <= maxGapMs` and `prev.text` does not end with terminal
 * sentence punctuation (`.`, `!`, `?`, `…`), running [PhraseDetector] on the continuous stream,
 * and projecting matched [PhraseSpan]s back onto each `blockId`.
 */
class CueStreamPhraseAnalyzer(
    private val detector: PhraseDetector = DeterministicPhraseDetector(),
) {

    /**
     * Analyze a sequence of [CueSlice]s and return detected [PhraseSpan]s grouped by `blockId`.
     */
    suspend fun analyze(
        cues: List<CueSlice>,
        lexicon: LexiconAccess,
        config: NlpConfig = NlpConfig.DEFAULT,
        posTagger: PosTagger? = null,
        maxGapMs: Long = 1200L,
    ): Map<Long, List<PhraseSpan>> {
        if (cues.isEmpty()) return emptyMap()

        val resultByBlock = LinkedHashMap<Long, MutableList<PhraseSpan>>()
        for (cue in cues) {
            resultByBlock.getOrPut(cue.blockId) { ArrayList() }
        }

        // Partition cues into continuous sentence streams
        val streams = ArrayList<List<CueSlice>>()
        var currentStream = ArrayList<CueSlice>()
        for (cue in cues) {
            if (currentStream.isEmpty()) {
                currentStream.add(cue)
            } else {
                val prev = currentStream.last()
                val gap = cue.startMs - prev.endMs
                val canBridge = config.crossBlockBridgeEnabled &&
                    gap <= maxGapMs &&
                    !endsWithSentenceBoundary(prev.text)
                if (canBridge) {
                    currentStream.add(cue)
                } else {
                    streams.add(currentStream)
                    currentStream = ArrayList()
                    currentStream.add(cue)
                }
            }
        }
        if (currentStream.isNotEmpty()) {
            streams.add(currentStream)
        }

        // Run detection on each continuous stream and project back to blockIds
        for (stream in streams) {
            val mappedTokens = ArrayList<StreamToken>()
            var virtualOffset = 0

            for (slice in stream) {
                val localTokens = tokenizeText(slice.text)
                for (lt in localTokens) {
                    val streamIdx = mappedTokens.size
                    val vStart = virtualOffset + lt.startChar
                    val vEnd = virtualOffset + lt.endChar
                    mappedTokens += StreamToken(
                        streamToken = TokenInfo(
                            index = streamIdx,
                            text = lt.text,
                            startChar = vStart,
                            endChar = vEnd,
                        ),
                        blockId = slice.blockId,
                        localTokenIndex = lt.index,
                        localStartChar = lt.startChar,
                        localEndChar = lt.endChar,
                    )
                }
                virtualOffset += slice.text.length + 1
            }

            if (mappedTokens.isEmpty()) continue

            val rawSpans = detector.detect(
                tokens = mappedTokens.map { it.streamToken },
                lexicon = lexicon,
                config = config,
                posTagger = posTagger,
            )

            for (span in rawSpans) {
                val firstStreamIdx = span.tokens.minOrNull() ?: continue
                val lastStreamIdx = span.tokens.maxOrNull() ?: continue
                if (firstStreamIdx < 0 || lastStreamIdx >= mappedTokens.size) continue

                val coveredStreamTokens = (firstStreamIdx..lastStreamIdx).map { mappedTokens[it] }
                val byBlock = coveredStreamTokens.groupBy { it.blockId }

                val allParts = byBlock.entries.map { (bId, toks) ->
                    SpanPart(
                        blockId = bId.toInt(),
                        startChar = toks.minOf { it.localStartChar },
                        endChar = toks.maxOf { it.localEndChar },
                    )
                }

                for ((bId, toksInBlock) in byBlock) {
                    val localStart = toksInBlock.minOf { it.localStartChar }
                    val localEnd = toksInBlock.maxOf { it.localEndChar }
                    val matchedSet = span.tokens.toSet()
                    val highlightedInBlock = toksInBlock.filter { it.streamToken.index in matchedSet }
                    val localTokenIndices = (highlightedInBlock.ifEmpty { toksInBlock }).map { it.localTokenIndex }

                    val projected = PhraseSpan(
                        startChar = localStart,
                        endChar = localEnd,
                        tokens = localTokenIndices,
                        kind = span.kind,
                        headword = span.headword,
                        lemmaKey = span.lemmaKey,
                        confidence = span.confidence,
                        separable = span.separable,
                        parts = allParts,
                    )
                    resultByBlock.getOrPut(bId) { ArrayList() }.add(projected)
                }
            }
        }

        return resultByBlock.mapValues { (_, list) -> list.sortedBy { it.startChar } }
    }

    private data class StreamToken(
        val streamToken: TokenInfo,
        val blockId: Long,
        val localTokenIndex: Int,
        val localStartChar: Int,
        val localEndChar: Int,
    )

    companion object {
        private val TOKEN_REGEX = Regex("[A-Za-z0-9]+(?:'[A-Za-z0-9]+)*|[.,;:!?]")

        fun tokenizeText(text: String): List<TokenInfo> {
            val out = ArrayList<TokenInfo>()
            var idx = 0
            for (m in TOKEN_REGEX.findAll(text)) {
                out += TokenInfo(
                    index = idx++,
                    text = m.value,
                    startChar = m.range.first,
                    endChar = m.range.last + 1,
                )
            }
            return out
        }

        fun endsWithSentenceBoundary(text: String): Boolean {
            val trimmed = text.trimEnd()
            if (trimmed.isEmpty()) return true
            if (trimmed.endsWith("...") || trimmed.endsWith("…")) return false
            val last = trimmed.last()
            return last == '.' || last == '!' || last == '?' || last == '؟' || last == ';'
        }
    }
}
