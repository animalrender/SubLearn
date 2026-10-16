package com.sublearn.core.nlp

/**
 * Tunable knobs for the NLP pipeline. Every value has a sane default and is
 * overridden by user settings when available.
 *
 * The defaults prioritise precision over recall (D-34): a wrong underline is
 * worse than a missing one.
 *
 * Per-type thresholds: `phrasalThreshold`, `idiomThreshold`, `collocationThreshold`.
 * A span must meet its type-specific threshold to get a visible underline.
 * The generic `confidenceThreshold` acts as a global floor for all types.
 * `PHRASE` kind (all closed-class tokens) is never underlined.
 */
data class NlpConfig(
    /** Global minimum confidence for a visible underline (superseded by per-type). */
    val confidenceThreshold: Float = 0.75f,

    /** Per-type minimum confidence for a visible underline. */
    val phrasalThreshold: Float = 0.75f,
    val idiomThreshold: Float = 0.75f,
    val collocationThreshold: Float = 0.65f,

    /** Whether to show low-confidence matches in the touch card as "possible". */
    val showPossibleMatches: Boolean = true,

    /** Whether phrasal verbs are detected. */
    val phrasalVerbsEnabled: Boolean = true,

    /** Whether idioms are detected. */
    val idiomsEnabled: Boolean = true,

    /** Whether collocations are detected. */
    val collocationsEnabled: Boolean = true,

    /** Whether the cross-block bridge is active. */
    val crossBlockBridgeEnabled: Boolean = true,

    // --- Bridge limits ---
    /** Maximum tokens from the end of the previous block to consider for bridging. */
    val bridgeTailTokens: Int = 3,

    /** Maximum tokens from the start of the next block to consider for bridging. */
    val bridgeHeadTokens: Int = 3,

    /** Maximum time gap (ms) between blocks for a bridge to be attempted. */
    val bridgeMaxGapMs: Long = 1500L,

    /** Maximum number of blocks a bridge can span. */
    val bridgeMaxBlocks: Int = 3,

    /** Maximum total tokens across a bridge. */
    val bridgeMaxTokens: Int = 12,

    /** Maximum total duration (ms) across a bridge. */
    val bridgeMaxDurationMs: Long = 4000L,

    // --- Gap rules for separated phrasal verbs ---
    /** Maximum number of tokens allowed between the verb and particle in a separated phrasal. */
    val maxSeparationTokens: Int = 4,

    // --- Analysis version: bump to invalidate cached results ---
    val analyzerVersion: Int = 2,
) {
    /**
     * Get the per-type threshold for a given [PhraseKind].
     * PHRASE kind returns [Float.MAX_VALUE] (never underlined).
     */
    fun thresholdFor(kind: PhraseKind?): Float = when (kind) {
        PhraseKind.PHRASAL -> phrasalThreshold
        PhraseKind.IDIOM -> idiomThreshold
        PhraseKind.COLLOCATION -> collocationThreshold
        null -> confidenceThreshold
    }

    companion object {
        val DEFAULT = NlpConfig()
    }
}
