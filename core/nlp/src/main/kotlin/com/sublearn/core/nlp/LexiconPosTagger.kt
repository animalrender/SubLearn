package com.sublearn.core.nlp

/**
 * Fast, zero-model POS tagger backed by the lexicon's `word_pos(word, pos, freq)` table,
 * closed-class function word tables, English morphological suffix rules, and contextual
 * disambiguation rules (D-47).
 *
 * Used in the `LIGHT` profile (and as a fast baseline/fallback in higher profiles) to
 * disambiguate separated phrasal verbs (`pick the book up` -> `PART` vs `look up the street` -> `PREP`)
 * and filter sense candidates by part of speech.
 */
class LexiconPosTagger(
    private val wordPosProvider: (List<String>) -> Map<String, Map<String, Int>> = { emptyMap() },
) : PosTagger {

    override suspend fun tag(tokens: List<String>): List<String> {
        if (tokens.isEmpty()) return emptyList()

        val lowered = tokens.map { it.lowercase().trim() }
        val lexFreqs = wordPosProvider(lowered)

        // Pass 1: unigram tag selection (closed-class -> lexicon frequency -> suffix heuristics -> NOUN)
        val tags = MutableList(tokens.size) { i ->
            val raw = tokens[i]
            val low = lowered[i]
            when {
                low.isEmpty() || low.all { !it.isLetterOrDigit() } -> "PUNCT"
                low.all { it.isDigit() } -> "NUM"
                low in DETERMINERS_SET -> "DET"
                low in PRONOUNS_SET -> "PRON"
                low in CONJUNCTIONS_SET -> "CONJ"
                low in AUX_MODALS_SET -> "VERB"
                else -> {
                    val dist = lexFreqs[low]
                    if (!dist.isNullOrEmpty()) {
                        dist.maxByOrNull { it.value }?.key ?: guessBySuffix(low, raw, i > 0)
                    } else {
                        guessBySuffix(low, raw, i > 0)
                    }
                }
            }
        }

        // Pass 2: contextual disambiguation rules (Brill-style transformation rules)
        for (i in tokens.indices) {
            val low = lowered[i]
            val prevTag = if (i > 0) tags[i - 1] else null
            val prevLow = if (i > 0) lowered[i - 1] else null
            val nextTag = if (i + 1 < tokens.size) tags[i + 1] else null
            val nextLow = if (i + 1 < tokens.size) lowered[i + 1] else null
            val dist = lexFreqs[low].orEmpty()

            // Rule 1: After DET ("the", "a", "an", "my", "this") -> NOUN or ADJ, never VERB
            if (prevTag == "DET" || prevLow in POSSESSIVE_DETERMINERS) {
                if (tags[i] == "VERB" || tags[i] == "PART" || tags[i] == "PREP") {
                    tags[i] = if (nextTag == "NOUN" && ("ADJ" in dist || isAdjectiveLike(low))) "ADJ" else "NOUN"
                }
            }

            // Rule 2: After infinitive "to" or modal/auxiliary ("can", "will", "did", "don't") -> VERB
            if ((prevLow == "to" || prevLow in MODALS_ONLY) && tags[i] in setOf("NOUN", "ADJ")) {
                if ("VERB" in dist || dist.isEmpty()) {
                    tags[i] = "VERB"
                }
            }

            // Rule 3: Subject pronoun ("I", "you", "he", "she", "we", "they") followed by ambiguous NOUN/VERB -> VERB
            if (prevLow in SUBJECT_PRONOUNS && tags[i] == "NOUN" && ("VERB" in dist || low.endsWith("ed") || low.endsWith("s"))) {
                tags[i] = "VERB"
            }

            // Rule 4: Particle (PART) vs Preposition (PREP) for phrasal verb disambiguation
            if (low in AMBIGUOUS_PARTICLES) {
                val hasPrecedingVerb = (1..4).any { offset ->
                    i - offset >= 0 && tags[i - offset] == "VERB"
                }
                val followedByNounPhrase = nextTag in setOf("DET", "NOUN", "PRON", "ADJ") &&
                    nextLow !in SUBJECT_PRONOUNS
                val followedByPreposition = nextLow in PURE_PREPOSITIONS

                tags[i] = when {
                    // "picked the book up ." or "gave up" or "came up with"
                    hasPrecedingVerb && (!followedByNounPhrase || followedByPreposition) -> "PART"
                    // "walked up the hill" -> followed by DET + NOUN without an intervening object
                    followedByNounPhrase && prevTag != "NOUN" && prevTag != "PRON" -> "PREP"
                    hasPrecedingVerb && (prevTag == "NOUN" || prevTag == "PRON") && !followedByNounPhrase -> "PART"
                    else -> if (followedByNounPhrase) "PREP" else "PART"
                }
            } else if (low in PURE_PREPOSITIONS) {
                tags[i] = "PREP"
            }
        }

        return tags
    }

    private fun guessBySuffix(low: String, raw: String, notSentenceStart: Boolean): String = when {
        low.endsWith("ly") && low.length > 4 -> "ADV"
        low.endsWith("ing") && low.length >= 5 -> "VERB"
        low.endsWith("ed") && low.length >= 4 -> "VERB"
        low.endsWith("tion") || low.endsWith("sion") || low.endsWith("ness") ||
            low.endsWith("ment") || low.endsWith("ity") -> "NOUN"
        isAdjectiveLike(low) -> "ADJ"
        notSentenceStart && raw.firstOrNull()?.isUpperCase() == true -> "NOUN"
        else -> "NOUN"
    }

    private fun isAdjectiveLike(low: String): Boolean =
        low.endsWith("able") || low.endsWith("ible") || low.endsWith("ous") ||
            low.endsWith("ive") || low.endsWith("ful") || low.endsWith("less") ||
            (low.endsWith("al") && low.length > 4)

    companion object {
        private val DETERMINERS_SET = setOf(
            "a", "an", "the", "this", "that", "these", "those",
            "some", "any", "every", "each", "no", "another",
        )
        private val POSSESSIVE_DETERMINERS = setOf(
            "my", "your", "his", "her", "its", "our", "their",
        )
        private val PRONOUNS_SET = setOf(
            "i", "me", "you", "he", "him", "she", "her", "it", "we", "us", "they", "them",
            "my", "your", "his", "its", "our", "their", "mine", "yours", "hers", "ours", "theirs",
            "myself", "yourself", "himself", "herself", "itself", "ourselves", "themselves",
            "someone", "somebody", "something", "anyone", "anybody", "anything", "everyone", "nothing",
        )
        private val SUBJECT_PRONOUNS = setOf("i", "you", "he", "she", "we", "they")
        private val CONJUNCTIONS_SET = setOf(
            "and", "or", "but", "nor", "yet", "so", "because", "although", "though", "while", "if", "unless", "until",
        )
        private val AUX_MODALS_SET = setOf(
            "be", "am", "is", "are", "was", "were", "been", "being",
            "have", "has", "had", "having", "do", "does", "did", "done",
            "can", "could", "will", "would", "shall", "should", "may", "might", "must",
            "don't", "doesn't", "didn't", "can't", "won't", "wouldn't", "shouldn't", "couldn't",
        )
        private val MODALS_ONLY = setOf(
            "can", "could", "will", "would", "shall", "should", "may", "might", "must",
            "do", "does", "did", "don't", "doesn't", "didn't", "can't", "won't",
        )
        private val AMBIGUOUS_PARTICLES = setOf(
            "up", "down", "out", "off", "on", "in", "over", "away", "back", "around", "through", "along", "about",
        )
        private val PURE_PREPOSITIONS = setOf(
            "of", "at", "from", "into", "with", "by", "for", "to", "between", "among",
            "against", "without", "within", "during", "before", "after", "under", "upon", "towards", "toward",
        )
    }
}
