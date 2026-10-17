package com.sublearn.core.nlp.llm

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.sublearn.core.ai.LlmRunner
import com.sublearn.core.ai.ModelFileLayout
import com.sublearn.core.common.AppResult
import com.sublearn.core.common.SubLearnError
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs a LiteRT-LM bundle through the MediaPipe LLM Inference API (`D-75`).
 *
 * The SMART profile's models are single `.task` containers holding both weights and tokenizer, so
 * loading one is just a path: no separate vocabulary, no graph surgery. Three of the catalog's
 * bundles are published by `litert-community`; the Gemma 3 one is licence-gated, and the settings
 * screen says so rather than offering a download that cannot finish.
 *
 * Generation is deliberately conservative: a bounded token budget, a temperature low enough that
 * the same subtitle twice gives the same ranking, and a single in-flight request, because the
 * caller is a subtitle segment analyser rather than a chat UI.
 *
 * @property inference the loaded engine, or null before [load]
 * @property maxTokens the cap on generated tokens, enforced by the engine itself
 */
class LiteRtLlmRunner private constructor(
    private val layout: ModelFileLayout,
    private val inference: LlmInference,
    val maxTokens: Int,
) : LlmRunner {

    private val closed = AtomicBoolean(false)

    /** Human-readable engine name, for the diagnostics screen and the analysis log. */
    override val runtimeName: String get() = RUNTIME_NAME

    /** The catalog id this runner was opened for. */
    override val modelId: String get() = layout.id

    /**
     * Answers one prompt.
     *
     * The engine is single-threaded, so callers must serialise their requests; this method blocks
     * until the model has finished, which is why the analysis path calls it from a coroutine on
     * `Dispatchers.Default` rather than from the main thread.
     */
    override fun generate(prompt: String): AppResult<String> {
        if (closed.get()) return failure("the LLM session is closed")
        if (prompt.isBlank()) return failure("the prompt is empty")

        return try {
            AppResult.Success(inference.generateResponse(prompt).trim())
        } catch (error: Throwable) {
            // The native library reports device-level failures (out of memory, delegate refused)
            // as Error subclasses as well as Exceptions, so both are caught here.
            AppResult.Failure(
                SubLearnError(
                    SubLearnError.Kind.ModelFailure,
                    "LLM generation failed: ${error.message ?: error.javaClass.simpleName}",
                    error,
                ),
            )
        }
    }

    /** Asks the model to pick one option, returning the index, or null when nothing matched. */
    override fun choose(prompt: String, options: List<String>): AppResult<Int?> {
        val answer = when (val result = generate(prompt)) {
            is AppResult.Success -> result.value
            is AppResult.Failure -> return result
        }
        val normalised = answer.lowercase()
        // The prompt asks for a bare index, but a small model occasionally answers with the option
        // text instead, so both forms are accepted before giving up.
        val index = options.indices.firstOrNull { normalised.contains(it.toString()) }
            ?: options.indices.firstOrNull { normalised.contains(options[it].lowercase()) }
        return AppResult.Success(index)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { inference.close() }
    }

    private fun failure(message: String): AppResult.Failure = AppResult.Failure(
        SubLearnError(SubLearnError.Kind.ModelFailure, message),
    )

    companion object {
        const val RUNTIME_NAME = "litert-llm"

        /**
         * Whether MediaPipe's LLM Inference API is on the classpath.
         *
         * The native library loads on the first real call rather than at class load, so this probe
         * can only prove the Java half. That is enough for the honest distinction this build needs:
         * a device that reaches [load] and fails there gets the engine's own message, and no device
         * ever sees a profile offered as working when the classes are simply absent.
         */
        fun isUsable(): Boolean =
            runCatching { Class.forName("com.google.mediapipe.tasks.genai.llminference.LlmInference") }
                .isSuccess

        /** Enough for one short answer, small enough to keep a phone's thermals sane. */
        private const val DEFAULT_MAX_TOKENS = 256

        /**
         * Opens a `.task` bundle.
         *
         * [context] only has to be the application context: MediaPipe uses it to find its native
         * library, and holding an Activity here would leak it for as long as the model stays open.
         *
         * `preferGpu` is a request, not a requirement: the runner never falls back silently, but a
         * device without a usable GPU delegate fails the load with the engine's own message so the
         * settings screen can offer the CPU build instead.
         */
        fun load(
            context: Context,
            layout: ModelFileLayout,
            root: File,
            preferGpu: Boolean = false,
            maxTokens: Int = DEFAULT_MAX_TOKENS,
        ): AppResult<LiteRtLlmRunner> {
            val bundle = layout.fileUnder(root, layout.sessionFile ?: "")
            if (bundle.extension.equals("litertlm", ignoreCase = true)) {
                return loadFailure("${bundle.name} needs LiteRT-LM; MediaPipe LlmInference only supports .task/TFLite models")
            }
            if (!bundle.isFile || bundle.length() == 0L) {
                return loadFailure("model file ${bundle.name} is missing; download the model first")
            }

            return try {
                // `LlmInferenceOptions` is a nested class of `LlmInference`, not a sibling: the
                // options type is only ever used to build the engine it configures.
                val options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(bundle.absolutePath)
                    .setMaxTokens(maxTokens)
                    .setPreferredBackend(
                        if (preferGpu) LlmInference.Backend.GPU else LlmInference.Backend.CPU,
                    )
                    .build()

                // The engine needs a Context to locate its native library, and takes only the
                // application context here so nothing leaks an Activity for the model's lifetime.
                val inference = LlmInference.createFromOptions(context.applicationContext, options)
                AppResult.Success(LiteRtLlmRunner(layout, inference, maxTokens))
            } catch (error: Throwable) {
                loadFailure(
                    "could not open ${bundle.name}: ${error.message ?: error.javaClass.simpleName}",
                )
            }
        }

        private fun loadFailure(message: String): AppResult.Failure = AppResult.Failure(
            SubLearnError(SubLearnError.Kind.ModelFailure, message),
        )
    }
}
