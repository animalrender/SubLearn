package com.sublearn.app

import com.sublearn.core.ai.NlpModelRuntime
import com.sublearn.core.ai.NlpRuntimeRegistry
import com.sublearn.core.nlp.llm.LiteRtLlmLoader
import com.sublearn.core.nlp.onnx.OnnxEmbedderLoader

/**
 * Decides which optional engines this process can run, once, at startup (`D-74`).
 *
 * Both probes load the engine's native library, which is the only question that matters: a phone
 * whose ABI the shipped `.so` files do not cover must not be offered a profile that would fail on
 * the subtitle screen. The answer is kept in [NlpRuntimeRegistry] so the catalog, the settings
 * cards and the runtime guard all read the same value.
 *
 * The result is deliberately not cached in a field of its own: `NlpRuntimeRegistry` is the single
 * source of truth, and a second copy could drift from it.
 */
object NlpRuntimeBootstrap {

    /**
     * Probes and registers every engine, and reports what happened.
     *
     * Never throws: a probe that fails is the answer `false`, not a crash on launch.
     */
    fun register(): Set<NlpModelRuntime> {
        runCatching { OnnxEmbedderLoader.registerIfUsable() }
        runCatching { LiteRtLlmLoader.registerIfUsable() }
        return NlpRuntimeRegistry.available()
    }

    /** One line naming the engines, for the startup log and the diagnostics screen. */
    fun describe(available: Set<NlpModelRuntime>): String {
        val names = available.map { it.name }.sorted()
        return "nlp runtimes available: ${names.joinToString(", ")}"
    }
}
