package com.sublearn.feature.player

import com.sublearn.core.settings.CefrLevel
import com.sublearn.core.settings.LearningMode
import com.sublearn.core.settings.LearningSettings

/** A real lookup candidate; missing CEFR is unknown, never a fabricated level badge. */
internal data class LearningCandidate(
    val query: String,
    val lemma: String = query,
    val level: CefrLevel? = null,
    val known: Boolean = false,
    val phrase: Boolean = false,
)

/** LRN-2's filter is independent of the renderer so entertainment can never accidentally emit cards. */
internal fun selectLearningCandidates(
    candidates: List<LearningCandidate>,
    settings: LearningSettings,
): List<LearningCandidate> {
    if (settings.mode != LearningMode.LEARNING) return emptyList()
    return candidates.asSequence()
        .filterNot { it.known }
        .filter {
            !settings.showAboveLevelOnly ||
                (it.level != null && it.level != CefrLevel.UNKNOWN && it.level.ordinal > settings.manualLevel.ordinal)
        }
        .distinctBy { it.lemma.lowercase() }
        .sortedByDescending { it.phrase }
        .take(settings.maxPopupCards.coerceIn(0, MAX_LEARNING_CANDIDATES))
        .toList()
}

internal const val MAX_LEARNING_CANDIDATES = 10
internal const val VISIBLE_LEARNING_CARDS = 2

/** Only successful, non-empty translations reach the picture. No spinner or error covers a movie. */
data class LearningCardUi(val key: String, val word: String, val translation: String, val level: String? = null)
