package com.sublearn.core.nlp.llm

import android.content.Context
import com.sublearn.core.ai.LlmLoader
import com.sublearn.core.ai.LlmRunner
import com.sublearn.core.ai.ModelFileLayout
import com.sublearn.core.ai.NlpModel
import com.sublearn.core.ai.NlpModelRuntime
import com.sublearn.core.ai.NlpRuntimeRegistry
import com.sublearn.core.common.AppResult
import java.io.File

/**
 * The LiteRT engine's entry point for the SMART profile (`D-75`).
 *
 * The application [context] is captured once here rather than per call, because MediaPipe needs it
 * to find its native library and the process holds a single LLM at a time anyway.
 *
 * @property context an application context — never an Activity, which would leak
 * @property preferGpu ask for the GPU delegate. Subtitle analysis runs beside video playback, so
 *   the default is CPU: the delegate is a speed trade against battery, not a correctness one.
 * @property maxTokens the generation cap. A subtitle-word choice needs a few tokens, not a
 *   paragraph, and a tighter cap is what keeps the model from rambling past the answer.
 */
class LiteRtLlmLoader(
    private val context: Context,
    private val preferGpu: Boolean = false,
    private val maxTokens: Int = 256,
) : LlmLoader {

    override fun load(model: NlpModel, layout: ModelFileLayout, root: File): AppResult<LlmRunner> =
        LiteRtLlmRunner.load(
            context = context,
            layout = layout,
            root = root,
            preferGpu = preferGpu,
            maxTokens = maxTokens,
        )

    companion object {
        /**
         * Registers LiteRT with the profile system when the engine is on the classpath.
         *
         * @return true when the runtime became available, so the caller can log the outcome once
         */
        fun registerIfUsable(): Boolean {
            if (!LiteRtLlmRunner.isUsable()) return false
            NlpRuntimeRegistry.register(NlpModelRuntime.LITERT)
            return true
        }
    }
}
