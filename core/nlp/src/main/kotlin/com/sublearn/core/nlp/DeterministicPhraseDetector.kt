package com.sublearn.core.nlp

/**
 * The base phrase detector: lexicon + lemmatiser + pattern matching, with no model dependency.
 *
 * Detection strategy:
 * 1. For each token, lemmatise it and check if it's an anchor in the lexicon's pattern index.
 * 2. For each matching pattern, try to align it against the token window around the anchor.
 * 3. Score the match by pattern length, POS agreement, and disambiguation rules.
 * 4. Resolve overlaps by keeping the longest / highest-confidence span (D-34).
 *
 * This runs without any downloaded model. Accuracy profiles add model-based refinement on top.
 */
class DeterministicPhraseDetector : PhraseDetector {

    override suspend fun detect(
        tokens: List<TokenInfo>,
        lexicon: LexiconAccess,
        config: NlpConfig,
        posTagger: PosTagger?,
    ): List<PhraseSpan> {
        if (tokens.isEmpty()) return emptyList()

        val posTags = posTagger?.tag(tokens.map { it.text })
        val candidates = mutableListOf<PhraseSpan>()

        // --- Pass 1: find contiguous multi-word matches ---
        for (i in tokens.indices) {
            val token = tokens[i]
            val lemmas = lexicon.lemmatiser(token.text)

            for (lemma in lemmas) {
                val entries = lexicon.findByAnchor(lemma)
                for (entry in entries) {
                    if (entry.kind == null) continue
                    if (!isKindEnabled(entry.kind, config)) continue

                    for (pattern in entry.patterns) {
                        val compiled = PatternCompiler.compile(pattern.pattern)
                        val match = tryMatchAt(compiled, tokens, i, lexicon, posTags)
                        if (match != null) {
                            val confidence = computeConfidence(match, pattern, compiled, tokens, posTags, posTagger != null)
                            if (confidence > 0f) {
                                candidates.add(
                                    PhraseSpan(
                                        startChar = tokens[match.startToken].startChar,
                                        endChar = tokens[match.endToken].endChar,
                                        tokens = (match.startToken..match.endToken).toList(),
                                        kind = entry.kind,
                                        headword = entry.headword,
                                        lemmaKey = entry.key,
                                        confidence = confidence,
                                        separable = pattern.separable,
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }

        // --- Pass 2: separated phrasal verbs ---
        if (config.phrasalVerbsEnabled) {
            candidates.addAll(detectSeparatedPhrasals(tokens, lexicon, config, posTags))
        }

        // --- Pass 3: resolve overlaps (longest / highest confidence wins) ---
        val resolved = resolveOverlaps(candidates)

        return resolved.sortedBy { it.startChar }
    }

    /**
     * Try to match a compiled pattern at [anchorIndex] in the token list.
     *
     * The anchor token must correspond to one element in the pattern. We try every possible
     * alignment where the anchor maps to each pattern position, and return the first full match.
     */
    private fun tryMatchAt(
        pattern: List<PatternElement>,
        tokens: List<TokenInfo>,
        anchorIndex: Int,
        lexicon: LexiconAccess,
        posTags: List<String>?,
    ): MatchResult? {
        // Try each possible alignment: anchor at position patAnchorIdx in pattern
        for (patAnchorIdx in pattern.indices) {
            val startToken = anchorIndex - patAnchorIdx
            if (startToken < 0) continue
            val endToken = startToken + pattern.size - 1
            if (endToken >= tokens.size) continue

            var allMatch = true
            for (j in pattern.indices) {
                val tokenIdx = startToken + j
                if (tokenIdx < 0 || tokenIdx >= tokens.size) {
                    allMatch = false
                    break
                }
                val element = pattern[j]
                val token = tokens[tokenIdx].text.lowercase()
                val tokenLemmas = lexicon.lemmatiser(tokens[tokenIdx].text)

                when (element) {
                    is PatternElement.Literal -> {
                        if (element.word !in tokenLemmas && element.word != token) {
                            allMatch = false
                            break
                        }
                    }
                    is PatternElement.Possessive -> {
                        if (token !in POSSESSIVE_FORMS) {
                            allMatch = false
                            break
                        }
                    }
                    is PatternElement.Reflexive -> {
                        if (token !in REFLEXIVE_FORMS) {
                            allMatch = false
                            break
                        }
                    }
                    is PatternElement.BeVerb -> {
                        if (token !in BE_FORMS) {
                            allMatch = false
                            break
                        }
                    }
                    is PatternElement.NounPhrase -> {
                        // Accept any single token for now (a more sophisticated matcher
                        // would consume 1-3 tokens for a noun phrase)
                    }
                }
            }

            if (allMatch) {
                return MatchResult(startToken, endToken)
            }
        }
        return null
    }

    /**
     * Detect separated phrasal verbs like "turn the offer down" or "look it up".
     *
     * Strategy: find a verb + particle pair where the particle is not adjacent to the verb
     * but is separated by 1-4 tokens that form a valid gap (pronoun or NP).
     */
    private fun detectSeparatedPhrasals(
        tokens: List<TokenInfo>,
        lexicon: LexiconAccess,
        config: NlpConfig,
        posTags: List<String>?,
    ): List<PhraseSpan> {
        val results = mutableListOf<PhraseSpan>()

        for (i in tokens.indices) {
            val verbLemmas = lexicon.lemmatiser(tokens[i].text)
            for (verbLemma in verbLemmas) {
                val entries = lexicon.findByAnchor(verbLemma)
                for (entry in entries) {
                    if (entry.kind != PhraseKind.PHRASAL) continue
                    for (pattern in entry.patterns) {
                        if (!pattern.separable) continue

                        val compiled = PatternCompiler.compile(pattern.pattern)
                        val verbPatIdx = compiled.indexOfFirst {
                            it is PatternElement.Literal && (it.word == verbLemma || it.word in verbLemmas)
                        }
                        if (verbPatIdx < 0) continue

                        for (gapSize in 1..config.maxSeparationTokens) {
                            val particleIdx = i + gapSize + 1
                            if (particleIdx >= tokens.size) break

                            if (!isValidGap(tokens, i + 1, particleIdx, posTags)) break

                            val particleToken = tokens[particleIdx].text.lowercase()
                            val particleLemmas = lexicon.lemmatiser(tokens[particleIdx].text)

                            for (patIdx in (verbPatIdx + 1) until compiled.size) {
                                val element = compiled[patIdx]
                                val matches = when (element) {
                                    is PatternElement.Literal -> element.word == particleToken || element.word in particleLemmas
                                    else -> false
                                }
                                if (matches) {
                                    var confidence = 0.7f
                                    if (posTags != null && posTags.getOrNull(particleIdx) == "PREP") {
                                        // If the POS tagger says this is a preposition (IN), not a particle (RP),
                                        // lower the confidence significantly
                                        confidence = 0.4f
                                    }

                                    results.add(
                                        PhraseSpan(
                                            startChar = tokens[i].startChar,
                                            endChar = tokens[particleIdx].endChar,
                                            tokens = (i..particleIdx).toList(),
                                            kind = PhraseKind.PHRASAL,
                                            headword = entry.headword,
                                            lemmaKey = entry.key,
                                            confidence = confidence,
                                            separable = true,
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        return results
    }

    /**
     * Check if the tokens between [startIdx] and [endIdx] (exclusive) form a valid gap
     * for a separated phrasal verb.
     *
     * Rules (from the spec § 6.4):
     * - Gap must NOT contain: verbs, commas, conjunctions, relative pronouns
     * - Single-token gap: must be a pronoun (it/them/him/her/this/that/me/us)
     * - Multi-token gap: determiner/possessive + optional adjectives + noun
     */
    private fun isValidGap(
        tokens: List<TokenInfo>,
        startIdx: Int,
        endIdx: Int,
        posTags: List<String>?,
    ): Boolean {
        if (startIdx >= endIdx) return false
        val gapSize = endIdx - startIdx
        if (gapSize > 4) return false

        val FORBIDDEN_WORDS = setOf(",", ";", ":", "and", "or", "but", "because", "while", "which", "who", "that", "where")
        val PRONOUNS = setOf("it", "them", "him", "her", "this", "that", "me", "us")

        for (i in startIdx until endIdx) {
            val text = tokens[i].text.lowercase()
            val tag = posTags?.getOrNull(i)

            if (text in FORBIDDEN_WORDS) return false
            if (tag == "VERB" || tag == "CONJ" || tag == "PUNCT") return false
        }

        // Single pronoun gap is always valid
        if (gapSize == 1) {
            val text = tokens[startIdx].text.lowercase()
            return text in PRONOUNS || posTags?.getOrNull(startIdx) in setOf("PRON", "DET", "NOUN")
        }

        // Multi-token gap: should be determiner + optional adjectives + noun
        val firstTag = posTags?.getOrNull(startIdx)
        val lastTag = posTags?.getOrNull(endIdx - 1)
        if (firstTag in setOf("DET", "PRON") && lastTag in setOf("NOUN", null)) {
            return true
        }

        // Without POS tags, accept if the gap starts with a determiner
        return gapSize <= 3 && tokens[startIdx].text.lowercase() in DETERMINERS
    }

    /**
     * Compute a confidence score for a match.
     *
     * Factors:
     * - Base: 0.85
     * - Pattern length bonus: +0.05 per element ≥3
     * - POS tagger bonus: +0.05
     * - Disambiguation penalty: if the next token after the match is a preposition
     *   and the pattern doesn't continue with it, reduce confidence (particle vs. preposition)
     */
    private fun computeConfidence(
        match: MatchResult,
        pattern: LexiconPattern,
        compiled: List<PatternElement>,
        tokens: List<TokenInfo>,
        posTags: List<String>?,
        hasTagger: Boolean,
    ): Float {
        var confidence = 0.85f

        // Pattern length bonus
        if (compiled.size >= 3) confidence += 0.05f
        if (compiled.size >= 4) confidence += 0.05f

        // POS tagger bonus
        if (hasTagger && posTags != null) {
            confidence += 0.05f
        }

        // Disambiguation: if the token right after the match is a preposition (like "at"),
        // and the pattern doesn't include it, the matched token might be a preposition too,
        // not a particle. E.g., "look up at the sky" → "up" is likely a preposition.
        val nextTokenIdx = match.endToken + 1
        if (nextTokenIdx < tokens.size) {
            val nextToken = tokens[nextTokenIdx].text.lowercase()
            val nextTag = posTags?.getOrNull(nextTokenIdx)

            // If the next token is "at", "to", "from", "into", etc. and we don't have a tagger,
            // use a heuristic: the last element of the pattern is likely a particle only if
            // there's no longer pattern in the lexicon that includes the next token.
            if (nextToken in PREPOSITION_FOLLOWERS) {
                // Check if the last matched token is a common particle
                val lastMatchedToken = tokens[match.endToken].text.lowercase()
                if (lastMatchedToken in COMMON_PARTICLES) {
                    // Without a tagger, we can't distinguish RP from IN.
                    // Lower confidence to indicate ambiguity.
                    if (!hasTagger) {
                        confidence -= 0.25f
                    } else if (nextTag == "PREP") {
                        // Tagger says next is a preposition, so the particle might actually be a preposition too
                        confidence -= 0.3f
                    }
                }
            }
        }

        return confidence.coerceIn(0f, 1f)
    }

    /**
     * Resolve overlapping spans: the longest / highest-confidence span wins (D-34).
     * Priority: idiom > phrasal > collocation > single word. Longer expression wins.
     */
    private fun resolveOverlaps(spans: List<PhraseSpan>): List<PhraseSpan> {
        if (spans.size <= 1) return spans

        val sorted = spans.sortedWith(
            compareByDescending<PhraseSpan> { kindPriority(it.kind) }
                .thenByDescending { it.tokens.size }
                .thenByDescending { it.confidence }
        )

        val result = mutableListOf<PhraseSpan>()
        val usedTokens = mutableSetOf<Int>()

        for (span in sorted) {
            val overlap = span.tokens.any { it in usedTokens }
            if (!overlap) {
                result.add(span)
                usedTokens.addAll(span.tokens)
            }
        }

        return result
    }

    private fun kindPriority(kind: PhraseKind): Int = when (kind) {
        PhraseKind.IDIOM -> 3
        PhraseKind.PHRASAL -> 2
        PhraseKind.COLLOCATION -> 1
    }

    private fun isKindEnabled(kind: PhraseKind, config: NlpConfig): Boolean = when (kind) {
        PhraseKind.PHRASAL -> config.phrasalVerbsEnabled
        PhraseKind.IDIOM -> config.idiomsEnabled
        PhraseKind.COLLOCATION -> config.collocationsEnabled
    }

    private data class MatchResult(val startToken: Int, val endToken: Int)

    companion object {
        /** Prepositions that often follow a particle and cause ambiguity. */
        private val PREPOSITION_FOLLOWERS = setOf("at", "to", "from", "into", "on", "in", "over", "after", "about")

        /** Common phrasal verb particles. */
        private val COMMON_PARTICLES = setOf("up", "down", "out", "off", "on", "over", "away", "back", "about", "through", "along")
    }
}
