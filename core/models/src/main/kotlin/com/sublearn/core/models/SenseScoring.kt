package com.sublearn.core.models

import kotlin.math.sqrt

/**
 * Static token/sense cosine similarity scorer for the `BALANCED` profile (`D-52`).
 *
 * The pure-Kotlin path: it needs no model file and no native library, so it is the floor every
 * other ranker falls back to.
 */
object StaticEmbedderScorer {
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        val len = minOf(a.size, b.size)
        if (len == 0) return 0f
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in 0 until len) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom <= 1e-8f) 0f else (dot / denom).coerceIn(-1f, 1f)
    }

    /**
     * Ranks candidate senses `(senseId, glossText)` against `contextText` using token bag / static vectors.
     */
    fun rankSensesByOverlap(
        contextText: String,
        candidates: List<Pair<Long, String>>,
    ): List<Pair<Long, Float>> {
        if (candidates.isEmpty()) return emptyList()
        val ctxTokens = tokenizeLower(contextText).toSet()
        return candidates.mapIndexed { idx, (senseId, gloss) ->
            val glossTokens = tokenizeLower(gloss)
            val overlap = glossTokens.count { it in ctxTokens }
            val priorBonus = 1f / (1f + idx * 0.15f)
            senseId to (overlap * 0.5f + priorBonus)
        }.sortedByDescending { it.second }
    }

    private fun tokenizeLower(text: String): List<String> =
        text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 1 }
}

/**
 * Prompt builder and response parser for the `SMART_EXPERIMENTAL` on-device `.task` LLM sense
 * selector (`D-53`).
 */
object LlmSenseSelector {
    fun buildPrompt(
        targetWordOrPhrase: String,
        subtitleLine: String,
        candidateGlosses: List<String>,
    ): String = buildString {
        appendLine("Select the best Persian meaning index (1..${candidateGlosses.size}) for \"$targetWordOrPhrase\" in this subtitle line:")
        appendLine("Line: \"$subtitleLine\"")
        candidateGlosses.forEachIndexed { i, gloss ->
            appendLine("${i + 1}. $gloss")
        }
        append("Reply with ONLY the number (e.g. 1):")
    }

    /**
     * Parses the 1-based choice index from the LLM output and returns the 0-based index in
     * `0 until candidateCount`, or `0` if unparseable.
     */
    fun parseSelectedIndex(llmOutput: String, candidateCount: Int): Int {
        if (candidateCount <= 0) return 0
        val match = Regex("\\b(\\d+)\\b").find(llmOutput) ?: return 0
        val oneBased = match.groupValues[1].toIntOrNull() ?: return 0
        return (oneBased - 1).coerceIn(0, candidateCount - 1)
    }
}
