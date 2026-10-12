package com.sublearn.core.nlp

/**
 * Maps inflected English word forms to their lemma(s).
 *
 * Two sources are combined:
 * 1. The `lemma_form` table in the lexicon pack (irregular + learned forms, ordered by rank)
 * 2. A set of regular English suffix rules for verbs, nouns and adjectives
 *
 * Each form can map to multiple candidate lemmas (e.g. "saw" → {see, saw}, "left" → {leave, left}).
 * The phrase detector tries each candidate and keeps the match with the highest confidence.
 *
 * This class is pure Kotlin with no Android dependencies (D-41).
 */
class Lemmatiser(
    private val irregularForms: Map<String, Set<String>> = emptyMap(),
) {
    /**
     * Return all candidate lemmas for a word form.
     *
     * Rank-0 irregular lemmas come first so [bestCandidate] and iteration prefer the base lemma,
     * followed by regular suffix rules and the lowercased surface form itself.
     */
    fun candidates(word: String): Set<String> {
        val lower = word.lowercase().trim()
        if (lower.isEmpty()) return emptySet()
        val result = LinkedHashSet<String>()

        // 1. Check irregular forms from the lexicon first (preserves rank order)
        irregularForms[lower]?.let { result.addAll(it) }

        // 2. Apply regular rules
        result.addAll(applyRegularRules(lower))

        // 3. Always include the lowercased surface form
        result.add(lower)

        return result
    }

    /**
     * Return the single most likely lemma. If there are multiple candidates, prefer
     * the one that is an actual lemma in the provided set of known lemmas.
     */
    fun bestCandidate(word: String, knownLemmas: Set<String>): String {
        val lower = word.lowercase().trim()
        val cands = candidates(lower)
        // Prefer an irregular candidate that is in knownLemmas
        irregularForms[lower]?.firstOrNull { it in knownLemmas }?.let { return it }
        // Otherwise any candidate in knownLemmas (preferring non-self base lemma first)
        cands.firstOrNull { it != lower && it in knownLemmas }?.let { return it }
        if (lower in knownLemmas) return lower
        return cands.firstOrNull { it != lower } ?: lower
    }

    companion object {
        /**
         * Regular English inflection rules.
         * Returns a set of candidate base forms.
         */
        private fun applyRegularRules(word: String): Set<String> {
            val candidates = LinkedHashSet<String>()

            // --- Verb forms ---
            // -ying → -ie ("dying" → "die", "lying" → "lie", "tying" → "tie")
            if (word.endsWith("ying") && word.length >= 5) {
                candidates.add(word.dropLast(4) + "ie")
            }

            // -ing → remove "ing", possibly add "e" or deduplicate consonant
            if (word.endsWith("ing") && word.length >= 5) {
                val stem = word.dropLast(3)
                if (stem.length >= 2) {
                    candidates.add(stem)
                    candidates.add(stem + "e") // "making" → "make"
                    // Deduplicate final consonant: "running" → "run"
                    if (stem.last() == stem[stem.length - 2] && stem.last() !in "aeiou") {
                        candidates.add(stem.dropLast(1))
                    }
                    // -ying → -y: "carrying" → "carry"
                    if (stem.endsWith("i")) {
                        candidates.add(stem.dropLast(1) + "y")
                    }
                }
            }

            // -ied → -y ("carried" → "carry", "tried" → "try")
            if (word.endsWith("ied") && word.length >= 4) {
                candidates.add(word.dropLast(3) + "y")
            }

            // -ed → remove "ed", possibly add "e" or deduplicate
            if (word.endsWith("ed") && word.length >= 4) {
                val stem = word.dropLast(2)
                if (stem.length >= 2) {
                    candidates.add(stem)
                    candidates.add(stem + "e") // "died" → "die", "liked" → "like"
                    if (stem.last() == stem[stem.length - 2] && stem.last() !in "aeiou") {
                        candidates.add(stem.dropLast(1))
                    }
                }
            }

            // -ies → -y ("flies" → "fly", "tries" → "try")
            if (word.endsWith("ies") && word.length >= 4) {
                candidates.add(word.dropLast(3) + "y")
            }

            // -es → remove "es" or "es" → "e"
            if (word.endsWith("es") && word.length > 3) {
                candidates.add(word.dropLast(2))
                candidates.add(word.dropLast(1))
            }

            // -s (3rd person / plural) → remove "s"
            if (word.endsWith("s") && !word.endsWith("ss") && word.length > 2) {
                candidates.add(word.dropLast(1))
            }

            // --- Adjective forms ---
            // -ier → -y ("happier" → "happy")
            if (word.endsWith("ier") && word.length > 4) {
                candidates.add(word.dropLast(3) + "y")
            }
            // -er → remove "er" or "er" → "e"
            if (word.endsWith("er") && word.length > 4) {
                val stem = word.dropLast(2)
                candidates.add(stem)
                candidates.add(stem + "e")
                if (stem.length >= 2 && stem.last() == stem[stem.length - 2] && stem.last() !in "aeiou") {
                    candidates.add(stem.dropLast(1))
                }
            }

            // -iest → -y ("happiest" → "happy")
            if (word.endsWith("iest") && word.length > 5) {
                candidates.add(word.dropLast(4) + "y")
            }
            // -est → remove "est" or "est" → "e"
            if (word.endsWith("est") && word.length > 5) {
                val stem = word.dropLast(3)
                candidates.add(stem)
                candidates.add(stem + "e")
                if (stem.length >= 2 && stem.last() == stem[stem.length - 2] && stem.last() !in "aeiou") {
                    candidates.add(stem.dropLast(1))
                }
            }

            // --- Adverb ---
            // -ly → remove "ly"
            if (word.endsWith("ly") && word.length > 4) {
                if (word.endsWith("ily")) {
                    candidates.add(word.dropLast(3) + "y")
                }
                candidates.add(word.dropLast(2))
            }

            return candidates
        }

        /**
         * Build a Lemmatiser from the lemma_form table of a lexicon pack.
         */
        fun fromLexicon(forms: List<Triple<String, String, String?>>): Lemmatiser {
            val map = LinkedHashMap<String, MutableSet<String>>()
            for ((form, lemma, _) in forms) {
                map.getOrPut(form.lowercase()) { LinkedHashSet() }.add(lemma.lowercase())
            }
            return Lemmatiser(map)
        }
    }
}
