package com.sublearn.core.nlp

/**
 * Base sense ranker: orders senses by prior probability without any model.
 *
 * Scoring factors:
 * 1. is_primary flag (strongest signal)
 * 2. POS match with the tagger (if available)
 * 3. CEFR level (more common levels score higher)
 * 4. Order in the lexicon (earlier = more frequent)
 *
 * This is the "base profile" ranker (D-33). Profiles with models override
 * this with embedding-based ranking in phases 14-15.
 */
class PriorSenseRanker : SenseRanker {

    override suspend fun rank(
        lemmaKey: String,
        senses: List<SenseCandidate>,
        contextWindow: List<String>,
        targetPos: String?,
    ): List<SenseCandidate> {
        if (senses.size <= 1) return senses

        return senses.sortedByDescending { sense ->
            scoreSense(sense, targetPos, contextWindow)
        }
    }

    private fun scoreSense(sense: SenseCandidate, targetPos: String?, context: List<String>): Double {
        var score = 0.0

        // Primary sense gets a big boost
        if (sense.isPrimary) score += 3.0

        // POS match
        if (targetPos != null && sense.pos != null) {
            if (targetPos == sense.pos) {
                score += 2.0
            } else {
                score -= 1.0
            }
        }

        // CEFR frequency prior: more common levels (A1, A2) score slightly higher
        // because they tend to be the primary meanings
        val cefrPrior = when (sense.cefr) {
            "A1" -> 1.5
            "A2" -> 1.3
            "B1" -> 1.0
            "B2" -> 0.8
            "C1" -> 0.5
            "C2" -> 0.3
            else -> 0.7
        }
        score += cefrPrior

        // Simple context overlap: if any context word appears in the gloss, boost
        val contextLower = context.map { it.lowercase() }.toSet()
        val glossWords = (sense.glossFa + " " + (sense.glossEn ?: "")).lowercase().split("\\s+".toRegex()).toSet()
        val overlap = contextLower.intersect(glossWords).size
        score += overlap * 0.2

        // Example-based: if context words appear in examples, boost
        for (example in sense.examples) {
            val exWords = example.lowercase().split("\\s+".toRegex()).toSet()
            val exOverlap = contextLower.intersect(exWords).size
            score += exOverlap * 0.1
        }

        return score
    }
}
