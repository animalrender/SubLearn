package com.sublearn.core.nlp

/**
 * Detects multi-word expressions that span two (or more) adjacent subtitle blocks.
 *
 * BlockBuilder closes a block when the gap between cues exceeds 700ms. An expression
 * like "give ... up" can have its verb in one block and its particle in the next.
 *
 * Bridge rules (section 6.6 of the brief):
 * - Window: last 3 tokens of block A + first 3 tokens of block B
 * - Time gap ≤ 1500ms
 * - Block A does not end with `. ? !` (except `...` and abbreviations like `Mr.`)
 * - Block B does not start with `-`, speaker label or `[`
 * - Result is a PhraseSpan with parts in each block
 *
 * This is pure Kotlin with no Android dependencies (D-41).
 */
class CrossBlockBridge(
    private val detector: PhraseDetector,
    private val config: NlpConfig = NlpConfig.DEFAULT,
) {
    /**
     * A subtitle block as seen by the bridge (just the information it needs).
     */
    data class BridgeBlock(
        val id: Int,
        val text: String,
        val tokens: List<TokenInfo>,
        val startMs: Long,
        val endMs: Long,
    )

    /**
     * Try to find cross-block phrases between two adjacent blocks.
     *
     * Returns spans with multiple parts; each part references its own block.
     */
    suspend fun findCrossBlockPhrases(
        blockA: BridgeBlock,
        blockB: BridgeBlock,
        lexicon: LexiconAccess,
        posTagger: PosTagger? = null,
    ): List<PhraseSpan> {
        if (!config.crossBlockBridgeEnabled) return emptyList()

        // Check time gap
        val gap = blockB.startMs - blockA.endMs
        if (gap > config.bridgeMaxGapMs) return emptyList()

        // Check block A ending
        if (endsWithSentence(blockA.text)) return emptyList()

        // Check block B starting
        if (startsNewSegment(blockB.text)) return emptyList()

        // Build bridge token window
        val tailTokens = blockA.tokens.takeLast(config.bridgeTailTokens)
        val headTokens = blockB.tokens.take(config.bridgeHeadTokens)

        if (tailTokens.isEmpty() || headTokens.isEmpty()) return emptyList()

        // Check total duration
        val totalDuration = blockB.endMs - blockA.startMs
        if (totalDuration > config.bridgeMaxDurationMs) return emptyList()

        // Reindex tokens to bridge space
        val bridgeTokens = mutableListOf<TokenInfo>()
        val blockAMap = mutableMapOf<Int, Int>() // bridge index → block A token index
        val blockBMap = mutableMapOf<Int, Int>() // bridge index → block B token index

        for (t in tailTokens) {
            blockAMap[bridgeTokens.size] = t.index
            bridgeTokens.add(t)
        }
        for (t in headTokens) {
            blockBMap[bridgeTokens.size] = t.index
            bridgeTokens.add(t)
        }

        if (bridgeTokens.size < 2) return emptyList()

        // Run the detector on the bridge window
        val spans = detector.detect(bridgeTokens, lexicon, config, posTagger)

        // Filter: only keep spans that actually cross the boundary
        val crossBlock = spans.filter { span ->
            val hasA = span.tokens.any { it in blockAMap }
            val hasB = span.tokens.any { it in blockBMap }
            hasA && hasB
        }

        // Convert to multi-part spans
        return crossBlock.map { span ->
            val partA = span.tokens.filter { it in blockAMap }.let { indices ->
                if (indices.isEmpty()) null
                else SpanPart(
                    blockId = blockA.id,
                    startChar = tailTokens.first { it.index == blockAMap[indices.first()] }.startChar,
                    endChar = tailTokens.last { it.index == blockAMap[indices.last()] }.endChar,
                )
            }
            val partB = span.tokens.filter { it in blockBMap }.let { indices ->
                if (indices.isEmpty()) null
                else SpanPart(
                    blockId = blockB.id,
                    startChar = headTokens.first { it.index == blockBMap[indices.first()] }.startChar,
                    endChar = headTokens.last { it.index == blockBMap[indices.last()] }.endChar,
                )
            }

            val parts = listOfNotNull(partA, partB)
            span.copy(
                parts = parts,
                startChar = partA?.startChar ?: partB?.startChar ?: span.startChar,
                endChar = partB?.endChar ?: partA?.endChar ?: span.endChar,
            )
        }
    }

    /** Check if text ends with a sentence terminator (not ellipsis or abbreviation). */
    private fun endsWithSentence(text: String): Boolean {
        val trimmed = text.trimEnd()
        if (trimmed.isEmpty()) return false
        val last = trimmed.last()
        if (last !in ".?!") return false
        // Ellipsis: "..."
        if (trimmed.endsWith("...")) return false
        // Abbreviations: "Mr.", "Dr.", "Mrs.", etc.
        if (trimmed.matches(Regex(".*\\b(Mr|Mrs|Dr|Ms|Prof|Sr|Jr|St|vs|etc)\\.$", RegexOption.IGNORE_CASE))) return false
        return true
    }

    /** Check if text starts with a new segment marker. */
    private fun startsNewSegment(text: String): Boolean {
        val trimmed = text.trimStart()
        if (trimmed.isEmpty()) return false
        // Starts with dash (speaker change)
        if (trimmed.startsWith("-") || trimmed.startsWith("–") || trimmed.startsWith("—")) return true
        // Starts with [ (sound effect or stage direction)
        if (trimmed.startsWith("[")) return true
        // Starts with uppercase after a colon (speaker label like "JOHN:")
        if (trimmed.matches(Regex("^[A-Z]+:"))) return true
        return false
    }
}
