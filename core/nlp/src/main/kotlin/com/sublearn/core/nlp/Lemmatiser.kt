package com.sublearn.core.nlp

/**
 * Maps inflected English word forms to their lemma(s).
 *
 * Two sources are combined:
 * 1. The `lemma_form` table in the lexicon pack (irregular + learned forms)
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
     * The set always includes the word itself (lowercased), because not every word
     * is inflected and the detector needs to try the surface form as a lemma too.
     */
    fun candidates(word: String): Set<String> {
        val lower = word.lowercase()
        val result = mutableSetOf(lower)

        // 1. Check irregular forms from the lexicon
        irregularForms[lower]?.let { result.addAll(it) }

        // 2. Apply regular rules
        result.addAll(applyRegularRules(lower))

        return result
    }

    /**
     * Return the single most likely lemma. If there are multiple candidates, prefer
     * the one that is an actual lemma in the provided set of known lemmas.
     */
    fun bestCandidate(word: String, knownLemmas: Set<String>): String {
        val cands = candidates(word)
        // Prefer a candidate that is a known lemma
        val known = cands.filter { it in knownLemmas }
        if (known.isNotEmpty()) return known.first()
        // Otherwise return the first non-self candidate, or self
        return cands.firstOrNull { it != word.lowercase() } ?: word.lowercase()
    }

    companion object {
        /**
         * Regular English inflection rules.
         * Each rule: (suffix to strip, suffix to add back or "", lemma suffix condition).
         * Returns a set of candidate base forms.
         */
        private fun applyRegularRules(word: String): Set<String> {
            val candidates = mutableSetOf<String>()

            // --- Verb forms ---
            // -ing → remove "ing", possibly add "e" or deduplicate consonant
            if (word.endsWith("ing") && word.length > 5) {
                val stem = word.dropLast(3)
                candidates.add(stem) // "running" → "runn" (will try dedup)
                candidates.add(stem + "e") // "making" → "make"
                // Deduplicate final consonant: "running" → "run"
                if (stem.length >= 2 && stem.last() == stem[stem.length - 2] && stem.last() !in "aeiou") {
                    candidates.add(stem.dropLast(1))
                }
                // -ying → -y: "carrying" → "carry"
                if (stem.endsWith("i")) {
                    candidates.add(stem.dropLast(1) + "y")
                }
            }

            // -ed → remove "ed", possibly add "e" or deduplicate
            if (word.endsWith("ed") && word.length > 4) {
                val stem = word.dropLast(2)
                candidates.add(stem)
                candidates.add(stem + "e")
                if (stem.length >= 2 && stem.last() == stem[stem.length - 2] && stem.last() !in "aeiou") {
                    candidates.add(stem.dropLast(1))
                }
            }
            // Special: -ied → -y
            if (word.endsWith("ied") && word.length > 4) {
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
            // -ies → -y
            if (word.endsWith("ies") && word.length > 4) {
                candidates.add(word.dropLast(3) + "y")
            }

            // --- Adjective forms ---
            // -er → remove "er" or "er" → "e"
            if (word.endsWith("er") && word.length > 4) {
                val stem = word.dropLast(2)
                candidates.add(stem)
                candidates.add(stem + "e")
                if (stem.length >= 2 && stem.last() == stem[stem.length - 2] && stem.last() !in "aeiou") {
                    candidates.add(stem.dropLast(1))
                }
            }
            // -ier → -y
            if (word.endsWith("ier") && word.length > 4) {
                candidates.add(word.dropLast(3) + "y")
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
            // -iest → -y
            if (word.endsWith("iest") && word.length > 5) {
                candidates.add(word.dropLast(4) + "y")
            }

            // --- Adverb ---
            // -ly → remove "ly"
            if (word.endsWith("ly") && word.length > 4) {
                candidates.add(word.dropLast(2))
                // -ily → -y
                if (word.endsWith("ily")) {
                    candidates.add(word.dropLast(3) + "y")
                }
            }

            return candidates
        }

        /**
         * Build a Lemmatiser from the lemma_form table of a lexicon pack.
         */
        fun fromLexicon(forms: List<Triple<String, String, String?>>): Lemmatiser {
            val map = mutableMapOf<String, MutableSet<String>>()
            for ((form, lemma, _) in forms) {
                map.getOrPut(form) { mutableSetOf() }.add(lemma)
            }
            return Lemmatiser(map)
        }
    }
}
