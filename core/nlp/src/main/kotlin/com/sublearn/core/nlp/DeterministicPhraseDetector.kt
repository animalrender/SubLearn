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
                                    var confidence = 0.78f
                                    if (posTags != null && posTags.getOrNull(particleIdx) == "PREP") {
                                        confidence = 0.4f
                                    } else if (posTags != null && posTags.getOrNull(particleIdx) == "PART") {
                                        confidence = 0.85f
                                    }

                                    results.add(
                                        PhraseSpan(
                                            startChar = tokens[i].startChar,
                                            endChar = tokens[particleIdx].endChar,
                                            tokens = listOf(i, particleIdx),
                                            kind = PhraseKind.PHRASAL,
                                            headword = entry.headword,
                                            lemmaKey = entry.key,
                                            confidence = confidence,
                                            separable = true,
                                        ),
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

    private fun isValidGap(
        tokens: List<TokenInfo>,
        startIdx: Int,
        endIdx: Int,
        posTags: List<String>?,
    ): Boolean {
        if (startIdx >= endIdx) return false
        val gapSize = endIdx - startIdx
        if (gapSize > 4) return false

        val forbiddenWords = setOf(",", ";", ":", "and", "or", "but", "because", "while", "which", "who", "that", "where")
        val pronouns = setOf("it", "them", "him", "her", "this", "that", "me", "us", "someone", "something", "somebody")

        for (i in startIdx until endIdx) {
            val text = tokens[i].text.lowercase()
            val tag = posTags?.getOrNull(i)

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
        var confidence = 0.85f

        if (compiled.size >= 3) confidence += 0.05f
        if (compiled.size >= 4) confidence += 0.05f

        if (hasTagger && posTags != null) {
            confidence += 0.05f
        }

        val nextTokenIdx = match.endToken + 1
        if (nextTokenIdx < tokens.size) {
            val nextToken = tokens[nextTokenIdx].text.lowercase()
            val nextTag = posTags?.getOrNull(nextTokenIdx)

            if (nextToken in PREPOSITION_FOLLOWERS) {
                val lastMatchedToken = tokens[match.endToken].text.lowercase()
                if (lastMatchedToken in COMMON_PARTICLES) {
                    if (!hasTagger) {
                        confidence -= 0.25f
                    } else if (nextTag == "PREP") {
                        confidence -= 0.3f
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
            val overlap = usedRanges.any { existing ->
                maxOf(existing.first, spanRange.first) <= minOf(existing.last, spanRange.last)
            }
            if (!overlap) {
                result.add(span)
                usedRanges.add(spanRange)
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
