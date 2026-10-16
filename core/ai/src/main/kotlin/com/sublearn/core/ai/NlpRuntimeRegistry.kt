package com.sublearn.core.ai

/**
 * Which inference runtimes this particular build actually links.
 *
 * `core:ai` cannot answer "is ONNX Runtime present?" by itself: the engine lives in an Android
 * library module that depends on this one, so a compile-time reference would be circular. Instead
 * the app registers the runtimes it was built with, at startup, and everything that offers a
 * model to the user asks this registry first.
 *
 * The default is deliberately pessimistic. A process that never calls [register] — a unit test,
 * the benchmark harness — reports `false` for ONNX and LiteRT, so a profile is never offered as
 * working when nothing has proven that it is.
 */
object NlpRuntimeRegistry {

    private val available: MutableSet<NlpModelRuntime> = mutableSetOf(NlpModelRuntime.NONE)

    /** Records every engine this build links. Call once, during dependency injection setup. */
    fun register(vararg runtimes: NlpModelRuntime) {
        available += runtimes
        available += NlpModelRuntime.NONE
    }

    /** True when [runtime] can actually execute a model in this process. */
    fun isAvailable(runtime: NlpModelRuntime): Boolean = runtime in available

    /** The runtimes recorded so far, for diagnostics and the settings screen. */
    fun available(): Set<NlpModelRuntime> = available.toSet()

    /** Restores the pessimistic default. Tests use this so they cannot leak state into each other. */
    fun reset() {
        available.clear()
        available += NlpModelRuntime.NONE
    }
}
