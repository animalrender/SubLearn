package com.sublearn.core.nlp

import java.util.concurrent.ConcurrentHashMap

/**
 * The base phrase detector: lexicon + lemmatiser + pattern matching, with no model dependency.
 *
 * Detection strategy:
 * 1. For each token, lemmatise it and check if it's an anchor in the lexicon's pattern index.
 * 2. Patterns are precompiled and cached in [compiledPatternCache] (D-46) so regex splitting never
 *    runs repeatedly per subtitle cue.
 * 3. Score the match by pattern length, POS agreement, and disambiguation rules.
 * 4. Resolve overlaps by keeping the longest / highest-confidence span (D-34).
 */
class DeterministicPhraseDetector : PhraseDetector {

    private val compiledPatternCache = ConcurrentHashMap<String, List<PatternElement>>()

    private fun getCompiled(patternStr: String): List<PatternElement> =
        compiledPatternCache.getOrPut(patternStr) { PatternCompiler.compile(patternStr) }

    override suspend fun detect(
        tokens: List<TokenInfo>,
        lexicon: LexiconAccess,
        config: NlpConfig,
        posTagger: PosTagger?,
    ): List<PhraseSpan> {
        if (tokens.isEmpty()) return emptyList()

        val posTags = posTagger?.tag(tokens.map { it.text })
        val candidates = mutableListOf<PhraseSpan>()

        // --- Pass 1: find contiguous (and {NP}-slotted) multi-word matches ---
        for (i in tokens.indices) {
            val token = tokens[i]
            val lemmas = lexicon.lemmatiser(token.text)

            for (lemma in lemmas) {
                val entries = lexicon.findByAnchor(lemma)
                for (entry in entries) {
                    if (entry.kind == null) continue
                    if (!isKindEnabled(entry.kind, config)) continue

                    for (pattern in entry.patterns) {
                        val compiled = getCompiled(pattern.pattern)
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
                                    ),
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
     * Supports 1..3 token `{NP}` slots as well as fixed-length elements.
     */
    private fun tryMatchAt(
        pattern: List<PatternElement>,
        tokens: List<TokenInfo>,
        anchorIndex: Int,
        lexicon: LexiconAccess,
        posTags: List<String>?,
    ): MatchResult? {
        val hasNpSlot = pattern.any { it is PatternElement.NounPhrase }
        if (!hasNpSlot) {
            for (patAnchorIdx in pattern.indices) {
                val startToken = anchorIndex - patAnchorIdx
                if (startToken < 0) continue
                val endToken = startToken + pattern.size - 1
                if (endToken >= tokens.size) continue

                var allMatch = true
                for (j in pattern.indices) {
                    val tokenIdx = startToken + j
                    if (!matchesSingleElement(pattern[j], tokens[tokenIdx], lexicon)) {
                        allMatch = false
                        break
                    }
                }
                if (allMatch) {
                    return MatchResult(startToken, endToken)
                }
            }
            return null
        }

        // Pattern has {NP}: try start positions around anchorIndex
        val minStart = (anchorIndex - pattern.size - 2).coerceAtLeast(0)
        for (startToken in minStart..anchorIndex) {
            val endToken = matchVariableLength(pattern, 0, tokens, startToken, anchorIndex, false, lexicon, posTags)
            if (endToken != null) {
                return MatchResult(startToken, endToken)
            }
        }
        return null
    }

    private fun matchVariableLength(
        pattern: List<PatternElement>,
        patIdx: Int,
        tokens: List<TokenInfo>,
        tokIdx: Int,
        anchorIndex: Int,
        seenAnchor: Boolean,
        lexicon: LexiconAccess,
        posTags: List<String>?,
    ): Int? {
        if (patIdx == pattern.size) {
            return if (seenAnchor) tokIdx - 1 else null
        }
        if (tokIdx >= tokens.size) return null

        val element = pattern[patIdx]
        val isNowSeen = seenAnchor || (tokIdx == anchorIndex)
        if (element is PatternElement.NounPhrase) {
            for (npLen in 1..3) {
                val nextTok = tokIdx + npLen
                if (nextTok > tokens.size) break
                if (!isValidGap(tokens, tokIdx, nextTok, posTags)) continue
                val coversAnchor = seenAnchor || (anchorIndex in tokIdx until nextTok)
                val res = matchVariableLength(pattern, patIdx + 1, tokens, nextTok, anchorIndex, coversAnchor, lexicon, posTags)
                if (res != null) return res
            }
            return null
        } else {
            if (!matchesSingleElement(element, tokens[tokIdx], lexicon)) return null
            return matchVariableLength(pattern, patIdx + 1, tokens, tokIdx + 1, anchorIndex, isNowSeen, lexicon, posTags)
        }
    }

    private fun matchesSingleElement(
        element: PatternElement,
        tokenInfo: TokenInfo,
        lexicon: LexiconAccess,
    ): Boolean {
        val token = tokenInfo.text.lowercase()
        val tokenLemmas = lexicon.lemmatiser(tokenInfo.text)
        return when (element) {
            is PatternElement.Literal -> element.word in tokenLemmas || element.word == token
            is PatternElement.Possessive -> token in POSSESSIVE_FORMS || token.endsWith("'s")
            is PatternElement.Reflexive -> token in REFLEXIVE_FORMS
            is PatternElement.BeVerb -> token in BE_FORMS
            is PatternElement.NounPhrase -> true
        }
    }

    /**
     * Detect separated phrasal verbs like "turn the offer down" or "look it up".
     *
     * Fix for §2.2: The particle must be the **first element after the verb in the pattern**,
     * and the remaining pattern elements must come immediately after the particle.
     * This prevents false matches like "put it with" matching "put up with".
     * Also: "look that up" works when "that" is the sole gap token and not in forbidden list.
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

                        val compiled = getCompiled(pattern.pattern)
                        val verbPatIdx = compiled.indexOfFirst {
                            it is PatternElement.Literal && (it.word == verbLemma || it.word in verbLemmas)
                        }
                        if (verbPatIdx < 0) continue

                        // The particle must be the FIRST element after the verb in the pattern.
                        val particlePatIdx = verbPatIdx + 1
                        if (particlePatIdx >= compiled.size) continue
                        val particleElement = compiled[particlePatIdx]
                        if (particleElement !is PatternElement.Literal) continue

                        for (gapSize in 1..config.maxSeparationTokens) {
                            val particleIdx = i + gapSize + 1
                            if (particleIdx >= tokens.size) break

                            // Gap validation: only pronouns, determiners, nouns allowed in gap
                            if (!isValidGap(tokens, i + 1, particleIdx, posTags)) break

                            val particleToken = tokens[particleIdx].text.lowercase()
                            val particleLemmas = lexicon.lemmatiser(tokens[particleIdx].text)

                            // Match the particle element from the pattern
                            val matches = particleElement.word == particleToken ||
                                particleElement.word in particleLemmas

                            if (matches) {
                                // Check that remaining pattern elements (after particle)
                                // must follow immediately (no further gap allowed)
                                val remainingPatStart = particlePatIdx + 1
                                val remainingPatCount = compiled.size - remainingPatStart
                                val nextTokenAfterParticle = particleIdx + 1

                                // Allow up to 1 extra token for remaining pattern elements
                                // (e.g. "put up with" has "with" as the next element)
                                if (remainingPatCount > 0) {
                                    val restEnd = nextTokenAfterParticle + remainingPatCount - 1
                                    if (restEnd >= tokens.size) break
                                    var restMatch = true
                                    for (rp in 0 until remainingPatCount) {
                                        val tokIdx = nextTokenAfterParticle + rp
                                        if (!matchesSingleElement(compiled[remainingPatStart + rp], tokens[tokIdx], lexicon)) {
                                            restMatch = false
                                            break
                                        }
                                    }
                                    if (!restMatch) continue
                                }

                                // POS-based confidence for the particle
                                var confidence = pattern.prior
                                if (posTags != null && posTags.getOrNull(particleIdx) == "PREP") {
                                    confidence -= 0.30f
                                } else if (posTags != null && posTags.getOrNull(particleIdx) == "PART") {
                                    confidence += 0.05f
                                }

                                val endTok = if (remainingPatCount > 0)
                                    particleIdx + remainingPatCount else particleIdx

                                results.add(
                                    PhraseSpan(
                                        startChar = tokens[i].startChar,
                                        endChar = tokens[endTok].endChar,
                                        tokens = (i..endTok).toList(),
                                        kind = PhraseKind.PHRASAL,
                                        headword = entry.headword,
                                        lemmaKey = entry.key,
                                        confidence = confidence.coerceIn(0f, 1f),
                                        separable = true,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }
        return results
    }

    private fun isValidGap(
        tokens: List<TokenInfo>,
        startIdx: Int,
        endIdx: Int,
        posTags: List<String>?,
    ): Boolean {
        if (startIdx >= endIdx) return false
        val gapSize = endIdx - startIdx
        if (gapSize > 4) return false

        // "that" is removed from forbiddenWords: it can be a pronoun in single-token gaps
        // (e.g. "look that up") or a conjunction/determiner in larger gaps.
        val forbiddenWords = setOf(",", ";", ":", "and", "or", "but", "because", "while", "which", "who", "where")
        val pronouns = setOf("it", "them", "him", "her", "this", "that", "me", "us", "someone", "something", "somebody")

        for (i in startIdx until endIdx) {
            val text = tokens[i].text.lowercase()
            val tag = posTags?.getOrNull(i)

            // "that" is allowed as a pronoun but not as a conjunction in multi-token gaps
            if (text == "that" && gapSize > 1) return false
            if (text in forbiddenWords) return false
            if (tag == "VERB" || tag == "CONJ" || tag == "PUNCT") return false
        }

        if (gapSize == 1) {
            val text = tokens[startIdx].text.lowercase()
            return text in pronouns || posTags?.getOrNull(startIdx) in setOf("PRON", "DET", "NOUN")
        }

        val firstTag = posTags?.getOrNull(startIdx)
        val lastTag = posTags?.getOrNull(endIdx - 1)
        if (firstTag in setOf("DET", "PRON") && lastTag in setOf("NOUN", null)) {
            return true
        }

        return gapSize <= 3 && tokens[startIdx].text.lowercase() in DETERMINERS
    }

    private fun computeConfidence(
        match: MatchResult,
        pattern: LexiconPattern,
        compiled: List<PatternElement>,
        tokens: List<TokenInfo>,
        posTags: List<String>?,
        hasTagger: Boolean,
    ): Float {
        // Start from the pattern's prior probability (read from the lexicon pack),
        // NOT from a flat 0.85. This is the main fix for §2.1.
        var confidence = pattern.prior

        // +0.05 if contiguous and ≥3 tokens (more context = more certain)
        val spanTokens = match.endToken - match.startToken + 1
        if (spanTokens >= 3) confidence += 0.05f

        // -0.15 if there is a gap ≥ 3 tokens within the span
        if (match.endToken - match.startToken + 1 > compiled.size + 2) {
            confidence -= 0.15f
        }

        // +0.05 if POS tags agree with the pattern
        if (hasTagger && posTags != null) {
            confidence += 0.05f
        }

        // -0.30 if the next token is an ambiguous particle/preposition follower
        // that could make this a false positive (e.g. "look up at the sky")
        val nextTokenIdx = match.endToken + 1
        if (nextTokenIdx < tokens.size) {
            val nextToken = tokens[nextTokenIdx].text.lowercase()
            val nextTag = posTags?.getOrNull(nextTokenIdx)

            if (nextToken in PREPOSITION_FOLLOWERS) {
                val lastMatchedToken = tokens[match.endToken].text.lowercase()
                if (lastMatchedToken in COMMON_PARTICLES) {
                    // Ambiguous: e.g. "looked up at" — reduce confidence
                    if (!hasTagger) {
                        confidence -= 0.25f
                    } else if (nextTag == "PREP") {
                        confidence -= 0.30f
                    }
                }
            }
        }

        return confidence.coerceIn(0f, 1f)
    }

    private fun resolveOverlaps(spans: List<PhraseSpan>): List<PhraseSpan> {
        if (spans.size <= 1) return spans

        val sorted = spans.sortedWith(
            compareByDescending<PhraseSpan> { kindPriority(it.kind) }
                .thenByDescending { it.endChar - it.startChar }
                .thenByDescending { it.tokens.size }
                .thenByDescending { it.confidence },
        )

        val result = mutableListOf<PhraseSpan>()
        val usedRanges = mutableListOf<IntRange>()

        for (span in sorted) {
            val spanRange = span.startChar until span.endChar
            val overlappingIdx = usedRanges.indexOfFirst { existing ->
                maxOf(existing.first, spanRange.first) <= minOf(existing.last, spanRange.last)
            }
            if (overlappingIdx < 0) {
                // No overlap — this span wins
                result.add(span)
                usedRanges.add(spanRange)
            } else {
                // Overlaps with a winner — attach as alternative (up to 2)
                val winner = result[overlappingIdx]
                if (winner.alternatives.size < 2) {
                    result[overlappingIdx] = winner.copy(
                        alternatives = winner.alternatives + span
                    )
                }
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
        private val PREPOSITION_FOLLOWERS = setOf("at", "to", "from", "into", "on", "in", "over", "after", "about")
        private val COMMON_PARTICLES = setOf("up", "down", "out", "off", "on", "over", "away", "back", "about", "through", "along")
    }
}
