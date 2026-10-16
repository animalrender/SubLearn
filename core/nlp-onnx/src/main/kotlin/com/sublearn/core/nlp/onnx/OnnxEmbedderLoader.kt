package com.sublearn.core.nlp.onnx

import com.sublearn.core.ai.EmbedderLoader
import com.sublearn.core.ai.ModelFileLayout
import com.sublearn.core.ai.NlpModel
import com.sublearn.core.ai.NlpModelRuntime
import com.sublearn.core.ai.NlpRuntimeRegistry
import com.sublearn.core.ai.TextEmbedder
import com.sublearn.core.common.AppResult
import java.io.File

/**
 * The ONNX engine's entry point for the accuracy profiles (`D-74`).
 *
 * The app registers the runtime with [NlpRuntimeRegistry] once, at startup, based on
 * [OnnxTextEmbedder.isUsable]; after that this loader is what `ProfiledNlpEngine` calls for the
 * PRECISE and ADVANCED models.
 *
 * @property preferNnapi request the NNAPI delegate. It is an accelerator, so a device without the
 *   delegate still runs on the CPU provider; the flag exists for the settings toggle rather than
 *   for correctness.
 * @property maxTokens the tokenizer cap. 256 positions covers a subtitle line with room to spare
 *   and keeps the quadratic attention cost of a 512-position window off the phone.
 */
class OnnxEmbedderLoader(
    private val preferNnapi: Boolean = false,
    private val maxTokens: Int = 256,
) : EmbedderLoader {

    override fun load(model: NlpModel, layout: ModelFileLayout, root: File): AppResult<TextEmbedder> =
        OnnxTextEmbedder.load(
            layout = layout,
            root = root,
            preferNnapi = preferNnapi,
            maxTokens = maxTokens,
        )

    companion object {
        /**
         * Registers ONNX with the profile system when the native library loads.
         *
         * @return true when the runtime became available, so the caller can log the outcome once
         */
        fun registerIfUsable(): Boolean {
            if (!OnnxTextEmbedder.isUsable()) return false
            NlpRuntimeRegistry.register(NlpModelRuntime.ONNX)
            return true
        }
    }
}
