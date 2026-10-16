package com.sublearn.core.nlp.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxTensorLike
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import com.sublearn.core.ai.EmbedderLoader
import com.sublearn.core.ai.EmbeddingMath
import com.sublearn.core.ai.ModelFileLayout
import com.sublearn.core.ai.NlpModel
import com.sublearn.core.ai.TextEmbedder
import com.sublearn.core.ai.WordPieceTokenizer
import com.sublearn.core.common.AppResult
import com.sublearn.core.common.SubLearnError
import java.io.File
import java.nio.LongBuffer
import java.util.EnumSet
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs a quantised sentence encoder through ONNX Runtime (`D-74`).
 *
 * The models are the `Xenova` ONNX exports of `all-MiniLM-L6-v2` (embedding ranker) and
 * `distilbert-base-uncased` (word-sense disambiguation). Both take `input_ids` /
 * `attention_mask` as `int64[1, n]` and are exported with a `last_hidden_state` output; the mean
 * of that state, pooled and L2-normalised, is the sentence embedding.
 *
 * This is the PRECISE runtime. It is deliberately *not* given a `NotImplemented` fallback: if the
 * session cannot be created, [load] reports why, and the caller keeps using the lexical ranker
 * instead of pretending the model ran.
 *
 * @property layout where the graph and its vocabulary live on disk
 * @property interpreter the loaded runtime, or null before [load]
 * @property dimensions the width of the embedding, read from the graph itself
 */
class OnnxTextEmbedder private constructor(
    private val layout: ModelFileLayout,
    private val root: File,
    private val environment: OrtEnvironment,
    private val session: OrtSession,
    override val dimensions: Int,
    private val inputIdsName: String,
    private val attentionMaskName: String,
    private val outputName: String,
    private val tokenizer: WordPieceTokenizer,
    private val maxTokens: Int,
) : TextEmbedder {

    override val runtimeName: String = RUNTIME_NAME

    private val closed = AtomicBoolean(false)

    override fun embed(text: String): AppResult<FloatArray> {
        if (closed.get()) return failure("the ONNX session is closed")

        val encoding = tokenizer.encode(text, maxTokens)
        if (encoding.isEmpty) return failure("the text produced no tokens")

        return runSession(encoding.ids, encoding.attentionMask)
    }

    /** Embeds several texts with one session, which is how batch ranking calls into it. */
    fun embedAll(texts: List<String>): AppResult<List<FloatArray>> {
        val out = ArrayList<FloatArray>(texts.size)
        for (text in texts) {
            when (val result = embed(text)) {
                is AppResult.Success -> out += result.value
                is AppResult.Failure -> return result
            }
        }
        return AppResult.Success(out)
    }

    private fun runSession(ids: LongArray, mask: LongArray): AppResult<FloatArray> {
        val shape = longArrayOf(1L, ids.size.toLong())
        return try {
            OnnxTensor.createTensor(environment, LongBuffer.wrap(ids), shape).use { idTensor ->
                OnnxTensor.createTensor(environment, LongBuffer.wrap(mask), shape).use { maskTensor ->
                    val inputs: Map<String, OnnxTensorLike> = mapOf(
                        inputIdsName to idTensor as OnnxTensorLike,
                        attentionMaskName to maskTensor as OnnxTensorLike,
                    )
                    session.run(inputs).use { result ->
                        val tensor = result.get(0)
                        AppResult.Success(meanPool(tensor.value, mask))
                    }
                }
            }
        } catch (error: Exception) {
            AppResult.Failure(
                SubLearnError(
                    SubLearnError.Kind.ModelFailure,
                    "ONNX inference failed: ${error.message ?: error.javaClass.simpleName}",
                    error,
                ),
            )
        }
    }

    /**
     * Mean-pools the last hidden state over the tokens the mask selects, then normalises.
     *
     * ONNX Runtime's Java binding hands a 3-D float output back as `float[][][]` and a 2-D one as
     * `float[][]`, and Kotlin sees those as `Array<Array<FloatArray>>` and `Array<FloatArray>`. The
     * flattening below walks whatever nesting arrives instead of casting to one of them, so a graph
     * that returns `[1, n, d]` or `[n, d]` is handled by the same code. Pooling itself lives in
     * [EmbeddingMath] so the ONNX and pure-Kotlin runtimes round identically.
     */
    private fun meanPool(value: Any?, mask: LongArray): FloatArray {
        val flat = ArrayList<Float>(mask.size * dimensions)
        appendFloats(value, flat, mask.size * dimensions)
        if (flat.isEmpty()) return FloatArray(dimensions)

        val rows = ArrayList<FloatArray>(mask.size)
        val stride = flat.size / mask.size
        for (index in mask.indices) {
            if (mask[index] == 0L) continue
            val start = index * stride
            if (start + stride > flat.size) break
            val row = FloatArray(stride)
            for (position in 0 until stride) row[position] = flat[start + position]
            rows += row
        }
        return EmbeddingMath.meanPoolNormalized(rows)
    }

    /** Flattens a nested float output in row-major order, stopping once [limit] values are read. */
    private fun appendFloats(source: Any?, out: MutableList<Float>, limit: Int) {
        when (source) {
            is FloatArray -> for (value in source) {
                if (out.size >= limit) return
                out += value
            }

            is DoubleArray -> for (value in source) {
                if (out.size >= limit) return
                out += value.toFloat()
            }

            is Number -> out += source.toFloat()

            is Array<*> -> for (element in source) {
                if (out.size >= limit) return
                appendFloats(element, out, limit)
            }

            else -> Unit
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { session.close() }
    }

    private fun failure(message: String): AppResult.Failure = AppResult.Failure(
        SubLearnError(SubLearnError.Kind.ModelFailure, message),
    )

    companion object {
        const val RUNTIME_NAME = "onnxruntime"

        /**
         * Whether the native runtime can actually be loaded in this process.
         *
         * Calling [OrtEnvironment.getEnvironment] is the probe: it is what forces the loader to map
         * `libonnxruntime.so`, so a build that ships the Java classes without a usable native
         * library for the device's ABI answers `false` here instead of failing later on the
         * subtitle screen.
         */
        fun isUsable(): Boolean = runCatching { OrtEnvironment.getEnvironment() }.isSuccess

        /** The MiniLM/DistilBERT exports accept at most 512 positions; 256 keeps the cost sane. */
        private const val DEFAULT_MAX_TOKENS = 256

        /**
         * Opens a model directory and builds the ONNX session.
         *
         * Everything that can go wrong — missing files, a corrupt graph, a vocabulary the graph
         * does not accept — is reported as a [SubLearnError] rather than thrown, because the call
         * sits on the subtitle analysis path.
         */
        fun load(
            layout: ModelFileLayout,
            root: File,
            preferNnapi: Boolean = false,
            maxTokens: Int = DEFAULT_MAX_TOKENS,
        ): AppResult<OnnxTextEmbedder> {
            val graph = layout.fileUnder(root, layout.sessionFile ?: "")
            if (!graph.isFile || graph.length() == 0L) {
                return loadFailure("model file ${graph.name} is missing; download the model first")
            }
            val vocabularyName = layout.vocabularyFile ?: return loadFailure("model has no vocabulary")
            val vocabulary = layout.fileUnder(root, vocabularyName)
            if (!vocabulary.isFile || vocabulary.length() == 0L) {
                return loadFailure("vocabulary $vocabularyName is missing; download the model first")
            }

            val tokenizer = try {
                WordPieceTokenizer.fromFile(vocabulary)
            } catch (error: Exception) {
                return loadFailure("could not read $vocabularyName: ${error.message}")
            }

            val environment = try {
                OrtEnvironment.getEnvironment()
            } catch (error: Throwable) {
                return loadFailure("ONNX Runtime is not loadable on this device: ${error.message}")
            }

            return try {
                val options = buildSessionOptions(preferNnapi)
                val session = options.use { environment.createSession(graph.absolutePath, it) }
                val names = readSignatures(session)
                    ?: return loadFailure("the ONNX graph has no int64 input pair (input_ids/attention_mask)")
                val dimensions = readDimensions(session, names.outputName)
                    ?: return loadFailure("could not read the embedding width from the ONNX graph")

                AppResult.Success(
                    OnnxTextEmbedder(
                        layout = layout,
                        root = root,
                        environment = environment,
                        session = session,
                        dimensions = dimensions,
                        inputIdsName = names.inputIds,
                        attentionMaskName = names.attentionMask,
                        outputName = names.outputName,
                        tokenizer = tokenizer,
                        maxTokens = maxTokens,
                    ),
                )
            } catch (error: Exception) {
                loadFailure("could not open ${graph.name}: ${error.message ?: error.javaClass.simpleName}")
            }
        }

        private fun buildSessionOptions(preferNnapi: Boolean): OrtSession.SessionOptions {
            val options = OrtSession.SessionOptions()
            // Subtitle analysis runs while a video plays, so the session keeps to a handful of
            // threads rather than saturating a big-core device.
            runCatching { options.setIntraOpNumThreads(2) }
            runCatching { options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT) }
            if (preferNnapi) {
                // NNAPI is only an accelerator: a device without the delegate keeps running on the
                // CPU provider, so a failure here must not fail the load.
                runCatching { options.addNnapi(EnumSet.noneOf(OrtSession.SessionOptions.NNAPIFlags::class.java)) }
            }
            return options
        }

        /** The shapes of the graph's inputs and outputs, from the model's own metadata. */
        private class Signatures(
            val inputIds: String,
            val attentionMask: String,
            val outputName: String,
        )

        private fun readSignatures(session: OrtSession): Signatures? {
            val inputs = session.inputNames
            val inputIds = inputs.firstOrNull { it.contains("input_ids") } ?: return null
            val attentionMask = inputs.firstOrNull { it.contains("attention_mask") } ?: return null

            // Prefer the hidden state over a pooled output: pooling it here keeps one code path for
            // both models and matches how the pure-Kotlin embedder computes its vectors.
            val outputs = session.outputNames
            val outputName = outputs.firstOrNull { it.contains("last_hidden_state") }
                ?: outputs.firstOrNull { it.contains("token_embeddings") }
                ?: outputs.firstOrNull()
                ?: return null

            return Signatures(inputIds, attentionMask, outputName)
        }

        /** The embedding width, read from the output tensor's last dimension. */
        private fun readDimensions(session: OrtSession, outputName: String): Int? {
            val info = session.outputInfo[outputName] ?: return null
            val tensor = info as? TensorInfo ?: return null
            val shape = tensor.shape ?: return null
            val last = shape.lastOrNull() ?: return null
            return if (last > 0) last.toInt() else null
        }

        private fun loadFailure(message: String): AppResult.Failure = AppResult.Failure(
            SubLearnError(SubLearnError.Kind.ModelFailure, message),
        )
    }
}
