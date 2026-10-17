package com.sublearn.core.ai

import com.sublearn.core.common.AppResult

/**
 * What one self-test found out about an installed model.
 *
 * The report is deliberately a flat record of numbers and one sentence: the Downloads screen
 * prints it as it is, in both languages, and a future diagnostics export can log it verbatim.
 *
 * @property modelId the catalog id that was tested
 * @property ok true when the model loaded *and* produced a usable answer
 * @property runtimeName the engine that answered, or null when nothing opened
 * @property loadMs wall time to open the model
 * @property inferenceMs wall time of the probe inference(s), excluding the load
 * @property heapDeltaBytes JVM heap growth across load and inference; native memory is not counted
 * @property detail one line describing the probe's outcome: dimensions and similarities, or the
 *   prompt model's answer, or the reason it failed
 */
data class ModelSelfTestReport(
    val modelId: String,
    val ok: Boolean,
    val runtimeName: String?,
    val loadMs: Long,
    val inferenceMs: Long,
    val heapDeltaBytes: Long,
    val detail: String,
)

/**
 * The steps one self-test goes through, in order (D-91).
 *
 * The app's test runs in its own process, and a process that dies gives no reason of its own. The
 * last stage it announced is the only evidence of where it stopped, so the stages are named here
 * and reported through the service.
 */
enum class SelfTestStage {
    /**
     * The engine is opening the model. The native session is built inside this step, so a process
     * that dies here died while loading, not while answering.
     */
    LOADING_MODEL,

    /** The model is open and answering its probe; a process that dies here died during inference. */
    PROBING,
}

/**
 * Proves an installed model works by running it, not by looking at its files (`D-86`).
 *
 * `ModelStore.isInstalled` only says the files are present and non-empty. That is not the same as
 * "the download screen can promise this model runs": a truncated graph, a vocabulary from the wrong
 * model or a native library the device's ABI cannot load all pass the file check and fail on the
 * first tapped word. This class loads the model through the same loaders the player uses, asks it
 * one small question and reports what happened, so the "Test" button next to an installed model
 * tells the truth.
 *
 * The probe is cheap on purpose: two or three short sentences for an encoder, one four-option
 * question for a prompt model. It is a smoke test, not a benchmark.
 *
 * @property store where the models live
 * @property embedderLoader the ONNX loader, or null in a build without the engine
 * @property llmLoader the LiteRT loader, or null in a build without the engine
 */
class NlpModelSelfTest(
    private val store: ModelStore,
    private val embedderLoader: EmbedderLoader? = null,
    private val llmLoader: LlmLoader? = null,
) {

    /**
     * Runs the probe. [onStage] is told each step before it starts, so a caller whose process dies
     * in the middle (a native abort cannot be caught here) can say *where* it died (D-91).
     */
    fun run(modelId: String, onStage: (SelfTestStage) -> Unit = {}): ModelSelfTestReport = try {
        runProbe(modelId, onStage)
    } catch (error: Throwable) {
        failed(modelId, "model test failed: ${error.message ?: error.javaClass.simpleName}")
    }

    private fun runProbe(modelId: String, onStage: (SelfTestStage) -> Unit): ModelSelfTestReport {
        val model = NlpModelCatalog.byId[modelId]
            ?: return failed(modelId, "the catalog has no model called $modelId")
        if (model.requiresLiteRtLmEngine) {
            return failed(modelId, "This .litertlm model requires LiteRT-LM, not this build's MediaPipe engine. Use a .task model.")
        }
        val layout = store.layoutFor(modelId)
            ?: return failed(modelId, "this build has no file layout for $modelId")
        if (!store.isInstalled(modelId)) return failed(modelId, "the model is not installed")

        val truncated = layout.assets.firstOrNull { asset ->
            val file = layout.fileUnder(store.root, asset.name)
            asset.sizeBytes > 0 && file.length() != asset.sizeBytes
        }
        if (truncated != null) {
            val actual = layout.fileUnder(store.root, truncated.name).length()
            return failed(
                modelId,
                "${truncated.name} is $actual bytes, the publisher lists ${truncated.sizeBytes}; " +
                    "delete the model and download it again",
            )
        }
        if (!model.runtime.isAvailable) {
            return failed(modelId, "the ${model.runtime.name} engine is not part of this build")
        }

        val runtime = Runtime.getRuntime()
        val heapBefore = runtime.totalMemory() - runtime.freeMemory()
        val loadStart = System.nanoTime()
        onStage(SelfTestStage.LOADING_MODEL)
        return when (model.runtime) {
            NlpModelRuntime.NONE, NlpModelRuntime.ONNX -> {
                val loader = if (model.runtime == NlpModelRuntime.NONE) StaticEmbedderLoader else embedderLoader
                    ?: return failed(modelId, "no embedder loader was supplied")
                when (val loaded = loader.load(model, layout, store.root)) {
                    is AppResult.Failure -> failed(modelId, loaded.error.message)
                    is AppResult.Success -> loaded.value.use { embedder ->
                        val loadMs = elapsedMs(loadStart)
                        onStage(SelfTestStage.PROBING)
                        probeEmbedder(modelId, embedder, loadMs, heapBefore)
                    }
                }
            }

            NlpModelRuntime.LITERT -> {
                val loader = llmLoader ?: return failed(modelId, "no LLM loader was supplied")
                when (val loaded = loader.load(model, layout, store.root)) {
                    is AppResult.Failure -> failed(modelId, loaded.error.message)
                    is AppResult.Success -> loaded.value.use { llm ->
                        val loadMs = elapsedMs(loadStart)
                        onStage(SelfTestStage.PROBING)
                        probeLlm(modelId, llm, loadMs, heapBefore)
                    }
                }
            }
        }
    }

    private fun probeEmbedder(modelId: String, embedder: TextEmbedder, loadMs: Long, heapBefore: Long): ModelSelfTestReport {
        val start = System.nanoTime()
        val outcome = runCatching {
            val a = embedder.embed(SIMILAR_A)
            val b = embedder.embed(SIMILAR_B)
            val c = embedder.embed(UNRELATED)
            Triple(a, b, c)
        }
        val inferenceMs = elapsedMs(start)
        val heapDelta = heapNow() - heapBefore
        val (a, b, c) = outcome.getOrElse { error ->
            return ModelSelfTestReport(
                modelId = modelId,
                ok = false,
                runtimeName = embedder.runtimeName,
                loadMs = loadMs,
                inferenceMs = inferenceMs,
                heapDeltaBytes = heapDelta,
                detail = "loaded, but embedding failed: ${error.message ?: error.javaClass.simpleName}",
            )
        }
        val usable = a.size == embedder.dimensions && a.size > 0 && a.all { it.isFinite() } && a.any { it != 0f }
        val near = EmbeddingMath.cosine(a, b)
        val far = EmbeddingMath.cosine(a, c)
        return ModelSelfTestReport(
            modelId = modelId,
            ok = usable,
            runtimeName = embedder.runtimeName,
            loadMs = loadMs,
            inferenceMs = inferenceMs,
            heapDeltaBytes = heapDelta,
            detail = if (usable) {
                "${embedder.dimensions} dims; similar pair %.2f, unrelated pair %.2f".format(near, far)
            } else {
                "the encoder returned ${a.size} values (expected ${embedder.dimensions}) or a zero/NaN vector"
            },
        )
    }

    private fun probeLlm(modelId: String, llm: LlmRunner, loadMs: Long, heapBefore: Long): ModelSelfTestReport {
        val start = System.nanoTime()
        val answer = runCatching { llm.choose(LLM_PROMPT, LLM_OPTIONS) }
        val inferenceMs = elapsedMs(start)
        val heapDelta = heapNow() - heapBefore
        val result = answer.getOrNull()
        val chosen = (result as? AppResult.Success)?.value
        val detail = when {
            answer.isFailure -> "loaded, but generation threw: ${answer.exceptionOrNull()?.message.orEmpty()}"
            result is AppResult.Failure -> "loaded, but generation failed: ${result.error.message}"
            chosen == null -> "answered, but matched none of the options"
            chosen == LLM_EXPECTED -> "answered \"${LLM_OPTIONS[chosen]}\" (correct)"
            else -> "answered \"${LLM_OPTIONS[chosen]}\" (expected \"${LLM_OPTIONS[LLM_EXPECTED]}\")"
        }
        return ModelSelfTestReport(
            modelId = modelId,
            ok = chosen != null,
            runtimeName = llm.runtimeName,
            loadMs = loadMs,
            inferenceMs = inferenceMs,
            heapDeltaBytes = heapDelta,
            detail = detail,
        )
    }

    private fun failed(modelId: String, reason: String) = ModelSelfTestReport(
        modelId = modelId,
        ok = false,
        runtimeName = null,
        loadMs = 0L,
        inferenceMs = 0L,
        heapDeltaBytes = 0L,
        detail = reason,
    )

    private fun elapsedMs(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000L

    private fun heapNow(): Long = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }

    companion object {
        /** Two sentences about the same thing and one about something else: an encoder that works ranks them apart. */
        const val SIMILAR_A = "I really enjoyed the movie last night."
        const val SIMILAR_B = "The film we watched yesterday was great."
        const val UNRELATED = "The invoice is due on Monday morning."

        const val LLM_PROMPT = "Which of these is a fruit? Answer with just the number.\n0. apple\n1. hammer\n2. river\n3. shoe"
        val LLM_OPTIONS: List<String> = listOf("apple", "hammer", "river", "shoe")
        const val LLM_EXPECTED = 0
    }
}
