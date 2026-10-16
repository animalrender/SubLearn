package com.sublearn.core.nlp.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxTensorLike
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import com.sublearn.core.ai.EmbeddingMath
import com.sublearn.core.ai.ModelFileLayout
import com.sublearn.core.ai.StaticTokenEmbedder
import com.sublearn.core.ai.TextEmbedder
import com.sublearn.core.ai.WordPieceTokenizer
import com.sublearn.core.common.AppResult
import com.sublearn.core.common.SubLearnError
import java.io.File
import java.nio.LongBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs a quantised sentence encoder through ONNX Runtime (`D-74`).
 *
 * The models are the `Xenova` ONNX exports of `all-MiniLM-L6-v2` (embedding ranker) and
 * `distilbert-base-uncased` (word-sense disambiguation). Both take `input_ids` /
 * `attention_mask` as `int64[1, n]` and are exported with a `last_hidden_state` output; the mean
 * of that state over the real tokens, L2-normalised, is the sentence embedding.
 *
 * This is the PRECISE and ADVANCED runtime. It is deliberately *not* given a `NotImplemented`
 * fallback: if the session cannot be created, [load] reports why, and the caller keeps using the
 * lexical ranker instead of pretending a model ran.
 *
 * @property environment the ORT environment, which owns the native runtime
 * @property session the loaded graph
 * @property dimensions the embedding width, read from the graph's own output metadata
 */
class OnnxTextEmbedder private constructor(
    private val environment: OrtEnvironment,
    private val session: OrtSession,
    override val dimensions: Int,
    private val inputIdsName: String,
    private val attentionMaskName: String,
    private val outputName: String,
    private val tokenizer: WordPieceTokenizer,
) : TextEmbedder {

    override val runtimeName: String = RUNTIME_NAME

    private val closed = AtomicBoolean(false)

    /**
     * Embeds one text.
     *
     * Throws only when the session itself fails — a text that encodes to nothing is a zero vector,
     * not an error, matching [TextEmbedder]'s contract. [tryEmbed] is the non-throwing form used by
     * the ranking path, which prefers to fall back to the lexical ranker rather than crash a
     * subtitle overlay.
     */
    override fun embed(text: String): FloatArray = when (val result = tryEmbed(text)) {
        is AppResult.Success -> result.value
        is AppResult.Failure -> throw IllegalStateException(result.error.message, result.error.cause)
    }

    /** [embed] without the exception, for callers that have a fallback. */
    fun tryEmbed(text: String): AppResult<FloatArray> {
        if (closed.get()) return failure("the ONNX session is closed")

        val encoding = tokenizer.encode(text)
        if (encoding.tokenCount == 0) return AppResult.Success(FloatArray(dimensions))

        val ids = LongArray(encoding.inputIds.size) { encoding.inputIds[it].toLong() }
        val mask = IntArray(encoding.attentionMask.size) { encoding.attentionMask[it] }
        return runSession(ids, mask)
    }

    /** Embeds several texts with one session, which is how batch ranking calls into it. */
    fun embedAll(texts: List<String>): AppResult<List<FloatArray>> {
        val out = ArrayList<FloatArray>(texts.size)
        for (text in texts) {
            when (val result = tryEmbed(text)) {
                is AppResult.Success -> out += result.value
                is AppResult.Failure -> return result
            }
        }
        return AppResult.Success(out)
    }

    private fun runSession(ids: LongArray, mask: IntArray): AppResult<FloatArray> {
        val shape = longArrayOf(1L, ids.size.toLong())
        return try {
            OnnxTensor.createTensor(environment, LongBuffer.wrap(ids), shape).use { idTensor ->
                OnnxTensor.createTensor(environment, LongBuffer.wrap(asLongs(mask)), shape)
                    .use { maskTensor ->
                        val inputs: Map<String, OnnxTensorLike> = mapOf(
                            inputIdsName to idTensor,
                            attentionMaskName to maskTensor,
                        )
                        session.run(inputs).use { result ->
                            val tensor = result.get(0)
                            AppResult.Success(meanPool(tensor.value, mask))
                        }
                    }
            }
        } catch (error: Throwable) {
            // The native runtime reports device-level failures as Error subclasses too, so both are
            // caught here rather than only Exception.
            AppResult.Failure(
                SubLearnError(
                    SubLearnError.Kind.ModelFailure,
                    "ONNX inference failed: ${error.message ?: error.javaClass.simpleName}",
                    error,
                ),
            )
        }
    }

    /** ONNX Runtime's int64 inputs need `long[]`, while the tokenizer works in `int[]`. */
    private fun asLongs(values: IntArray): LongArray = LongArray(values.size) { values[it].toLong() }

    /**
     * Mean-pools the hidden state over the tokens the mask selects, then normalises.
     *
     * ONNX Runtime's Java binding hands a 3-D float output back as `float[][][]` and a 2-D one as
     * `float[][]`, and Kotlin sees those as `Array<Array<FloatArray>>` and `Array<FloatArray>`. The
     * flattening below walks whatever nesting arrives instead of casting to one of them, so a graph
     * that returns `[1, n, d]` or `[n, d]` is handled by the same code. The pooling itself lives in
     * [EmbeddingMath] so the ONNX and pure-Kotlin runtimes round identically.
     */
    private fun meanPool(value: Any?, mask: IntArray): FloatArray {
        val limit = mask.size * dimensions
        val flat = ArrayList<Float>(limit)
        appendFloats(value, flat, limit)
        if (flat.isEmpty()) return FloatArray(dimensions)

        val rows = flat.size / dimensions
        // A graph that already pooled returns a single row; anything else is `[tokens, hidden]`.
        if (rows <= 1) return EmbeddingMath.l2Normalize(flat.toFloatArray().copyOf(dimensions))

        val activations = FloatArray(rows * dimensions)
        for (index in activations.indices) activations[index] = flat[index]
        return EmbeddingMath.meanPoolNormalized(activations, mask.copyOf(rows), dimensions)
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

            is Number -> if (out.size < limit) out += source.toFloat()

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

        /**
         * The MiniLM/DistilBERT exports accept at most 512 positions; 256 covers a subtitle line
         * with room to spare and keeps the quadratic attention cost of a 512-position window off
         * the phone.
         */
        const val DEFAULT_MAX_TOKENS = 256

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
            val graphName = layout.sessionFile ?: return loadFailure("the layout has no primary file")
            val graph = layout.fileUnder(root, graphName)
            if (!graph.isFile || graph.length() == 0L) {
                return loadFailure("model file $graphName is missing; download the model first")
            }
            val vocabularyName = layout.vocabularyFile ?: return loadFailure("model has no vocabulary")
            val vocabulary = layout.fileUnder(root, vocabularyName)
            if (!vocabulary.isFile || vocabulary.length() == 0L) {
                return loadFailure("vocabulary $vocabularyName is missing; download the model first")
            }

            val tokenizer = try {
                // The vocabulary reader is the one the pure-Kotlin embedder already uses, so both
                // runtimes agree on what the file means, including the trailing newline.
                WordPieceTokenizer(StaticTokenEmbedder.readVocabulary(vocabulary), maxTokens)
            } catch (error: Exception) {
                return loadFailure("could not read $vocabularyName: ${error.message}")
            }
            if (!tokenizer.isUsable) {
                return loadFailure("$vocabularyName has no [CLS]/[SEP] tokens; it is not a WordPiece vocabulary")
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
                if (dimensions <= 0) return loadFailure("the ONNX graph reports a width of $dimensions")

                AppResult.Success(
                    OnnxTextEmbedder(
                        environment = environment,
                        session = session,
                        dimensions = dimensions,
                        inputIdsName = names.inputIds,
                        attentionMaskName = names.attentionMask,
                        outputName = names.outputName,
                        tokenizer = tokenizer,
                    ),
                )
            } catch (error: Throwable) {
                loadFailure("could not open $graphName: ${error.message ?: error.javaClass.simpleName}")
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
                // CPU provider, so a failure here must not fail the load. The no-argument overload
                // means the default flag set.
                runCatching { options.addNnapi() }
            }
            return options
        }

        /** The names of the graph's inputs and outputs, from the model's own metadata. */
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
            val info = session.outputInfo[outputName] as? TensorInfo ?: return null
            val last = info.shape?.lastOrNull() ?: return null
            return if (last > 0) last.toInt() else null
        }

        private fun loadFailure(message: String): AppResult.Failure = AppResult.Failure(
            SubLearnError(SubLearnError.Kind.ModelFailure, message),
        )
    }
}
