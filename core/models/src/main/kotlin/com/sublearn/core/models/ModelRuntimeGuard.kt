package com.sublearn.core.models

import com.sublearn.core.settings.AccuracyProfileToken
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.sqrt

/**
 * Result of running a device micro-benchmark for [AccuracyProfileToken] (`D-52`).
 */
data class ProfileBenchmarkReport(
    val requestedProfile: AccuracyProfileToken,
    val recommendedProfile: AccuracyProfileToken,
    val availableHeapMb: Int,
    val latencyMs: Long,
    val downgradedReason: String? = null,
)

/**
 * Runtime guard for accuracy profiles (`D-52`, `D-53`).
 *
 * Enforces RAM and latency budgets (`150ms` for embedding/ONNX tiers, `1500ms` for `SMART_EXPERIMENTAL`)
 * and automatically downgrades one tier on OOM, timeout, or runtime failure so subtitle playback
 * never stutters.
 */
class ModelRuntimeGuard(
    private val availableHeapMbProvider: () -> Int = {
        val rt = Runtime.getRuntime()
        ((rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())) / (1024L * 1024L)).toInt().coerceAtLeast(64)
    },
) {
    fun benchmark(requested: AccuracyProfileToken): ProfileBenchmarkReport {
        val start = System.currentTimeMillis()
        val heapMb = availableHeapMbProvider()
        val minRequired = minHeapMbFor(requested)
        if (heapMb < minRequired) {
            val fallback = downgradeUntilFits(requested, heapMb)
            return ProfileBenchmarkReport(
                requestedProfile = requested,
                recommendedProfile = fallback,
                availableHeapMb = heapMb,
                latencyMs = (System.currentTimeMillis() - start).coerceAtLeast(1L),
                downgradedReason = "Available heap ${heapMb}MB is below ${minRequired}MB required for $requested",
            )
        }
        // Synthetic vector dot-product micro-benchmark to gauge CPU throughput
        var acc = 0f
        for (i in 1..4096) {
            acc += (i * 0.001f) * (i * 0.002f)
        }
        val elapsed = (System.currentTimeMillis() - start).coerceAtLeast(1L) + if (acc < 0f) 1L else 0L
        return ProfileBenchmarkReport(
            requestedProfile = requested,
            recommendedProfile = requested,
            availableHeapMb = heapMb,
            latencyMs = elapsed,
        )
    }

    suspend fun <T> runGuarded(
        profile: AccuracyProfileToken,
        onDowngrade: (AccuracyProfileToken, String) -> Unit = { _, _ -> },
        fallbackValue: () -> T,
        block: suspend () -> T,
    ): T {
        val timeoutMs = timeoutMsFor(profile)
        return try {
            val res = withTimeoutOrNull(timeoutMs) { block() }
            if (res == null) {
                val lower = stepDown(profile)
                onDowngrade(lower, "$profile timed out after ${timeoutMs}ms")
                fallbackValue()
            } else {
                res
            }
        } catch (oom: OutOfMemoryError) {
            val lower = stepDown(profile)
            onDowngrade(lower, "$profile ran out of memory")
            fallbackValue()
        } catch (t: Throwable) {
            val lower = stepDown(profile)
            onDowngrade(lower, "$profile failed: ${t.message}")
            fallbackValue()
        }
    }

    companion object {
        fun minHeapMbFor(profile: AccuracyProfileToken): Int = when (profile) {
            AccuracyProfileToken.BASIC -> 32
            AccuracyProfileToken.LIGHT -> 48
            AccuracyProfileToken.BALANCED -> 128
            AccuracyProfileToken.PRECISE -> 256
            AccuracyProfileToken.ADVANCED -> 512
            AccuracyProfileToken.SMART_EXPERIMENTAL -> 1024
            AccuracyProfileToken.CUSTOM -> 64
        }

        fun timeoutMsFor(profile: AccuracyProfileToken): Long = when (profile) {
            AccuracyProfileToken.BASIC, AccuracyProfileToken.LIGHT -> 50L
            AccuracyProfileToken.BALANCED, AccuracyProfileToken.PRECISE, AccuracyProfileToken.ADVANCED -> 150L
            AccuracyProfileToken.SMART_EXPERIMENTAL -> 1_500L
            AccuracyProfileToken.CUSTOM -> 250L
        }

        fun stepDown(profile: AccuracyProfileToken): AccuracyProfileToken = when (profile) {
            AccuracyProfileToken.SMART_EXPERIMENTAL -> AccuracyProfileToken.ADVANCED
            AccuracyProfileToken.ADVANCED -> AccuracyProfileToken.PRECISE
            AccuracyProfileToken.PRECISE -> AccuracyProfileToken.BALANCED
            AccuracyProfileToken.BALANCED -> AccuracyProfileToken.LIGHT
            AccuracyProfileToken.LIGHT -> AccuracyProfileToken.BASIC
            AccuracyProfileToken.BASIC -> AccuracyProfileToken.BASIC
            AccuracyProfileToken.CUSTOM -> AccuracyProfileToken.LIGHT
        }

        fun downgradeUntilFits(requested: AccuracyProfileToken, availableHeapMb: Int): AccuracyProfileToken {
            var cur = requested
            while (cur != AccuracyProfileToken.BASIC && availableHeapMb < minHeapMbFor(cur)) {
                cur = stepDown(cur)
            }
            return cur
        }
    }
}

/**
 * Static token/sense cosine similarity scorer for the `BALANCED` profile (`D-52`).
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
