package com.sublearn.core.nlp

/**
 * Detects multi-word expressions (phrasal verbs, idioms, collocations) in a sequence of tokens.
 *
 * Implementations range from pure lexicon-based matching (phase 12) to model-assisted
 * detection with POS tags and embeddings (phases 14-15). The UI never knows which
 * implementation is active — it just receives [PhraseSpan]s.
 */
interface PhraseDetector {
    /**
     * Analyse a block's tokens and return all detected phrase spans.
     *
     * @param tokens the tokenised display text with offsets
     * @param lexicon provides lemma lookups, pattern matching and sense data
     * @param config tunable thresholds
     * @param posTagger optional POS tagger for disambiguation (may be null in base profile)
     * @return list of detected spans, ordered by start position
     */
    suspend fun detect(
        tokens: List<TokenInfo>,
        lexicon: LexiconAccess,
        config: NlpConfig,
        posTagger: PosTagger? = null,
    ): List<PhraseSpan>
}

/**
 * A single token with its position and text. Offsets are in the display text
 * so underlines land correctly.
 */
data class TokenInfo(
    val index: Int,
    val text: String,
    val startChar: Int,
    val endChar: Int,
)

/**
 * Part-of-speech tagger interface. Implementations range from rule-based (base)
 * to model-based (phases 14+).
 */
interface PosTagger {
    /**
     * Tag a sequence of tokens.
     * @return a list of POS tags, one per token, in Universal Dependencies format
     *         (VERB, NOUN, ADJ, ADV, PREP, PRON, DET, CONJ, PART, INTJ, PUNCT, X).
     *         "RP" for particles, "IN" for prepositions.
     */
    suspend fun tag(tokens: List<String>): List<String>
}

/**
 * Ranks the senses of a lexicon entry for a given context.
 *
 * Each accuracy profile provides its own implementation (D-33):
 * - Base: prior order, CEFR, primary flag
 * - Light: POS filter + priors
 * - Balanced: static embedding similarity
 * - Precise: sentence embedding with ONNX
 * - Advanced: span-in-context embedding
 * - Smart: LLM selection among ≤4 candidates
 */
interface SenseRanker {
    /**
     * Rank senses for a word/phrase in context.
     *
     * @param lemmaKey the lexicon key for the entry
     * @param senses candidate senses from the lexicon
     * @param contextWindow words around the target (±8 tokens)
     * @param targetPos optional POS constraint from the tagger
     * @return senses reordered by relevance, best first
     */
    suspend fun rank(
        lemmaKey: String,
        senses: List<SenseCandidate>,
        contextWindow: List<String>,
        targetPos: String? = null,
    ): List<SenseCandidate>
}

/** A sense from the lexicon, ready for ranking. */
data class SenseCandidate(
    val senseId: Long,
    val glossFa: String,
    val glossEn: String?,
    val pos: String?,
    val domain: String?,
    val register: String?,
    val isPrimary: Boolean,
    val examples: List<String> = emptyList(),
    val cefr: String? = null,
)

/**
 * Lexicon access interface used by the detector and ranker.
 * The real implementation reads from the SQLite lexicon pack; tests use an in-memory fake.
 */
interface LexiconAccess {
    /** Look up all forms that map to a lemma. */
    fun lemmatiser(form: String): Set<String>

    /** Find entries whose pattern anchor matches the given lemma. */
    fun findByAnchor(anchorLemma: String): List<LexiconEntry>

    /** Look up a single-word entry by key. */
    fun lookupWord(key: String): LexiconEntry?

    /** Get senses for an entry. */
    fun senses(entryId: Long): List<SenseCandidate>

    /** Get examples for a sense. */
    fun examples(senseId: Long): List<Pair<String, String>>
}

/** A lexicon entry with its pattern(s) for matching. */
data class LexiconEntry(
    val id: Long,
    val key: String,
    val headword: String,
    val kind: PhraseKind?,
    val pos: String?,
    val cefr: String?,
    val patterns: List<LexiconPattern> = emptyList(),
)

/** A pattern for matching multi-word expressions. */
data class LexiconPattern(
    val entryId: Long,
    val pattern: String,
    val anchorLemma: String,
    val separable: Boolean,
    val minTokens: Int,
    val maxTokens: Int,
)
