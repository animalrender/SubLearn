package com.sublearn.core.ai

import com.sublearn.core.common.AppResult
import com.sublearn.core.common.SubLearnError
import java.io.File

/**
 * The `BALANCED` profile's embedder: a pure-Kotlin inference path over the
 * `minishlab/potion-base-8M` weights, which are a static token-embedding table rather than a
 * transformer. That is what makes this profile runnable without any native runtime.
 *
 * The model is a lookup plus a mean: each token id in the input is looked up in the embedding
 * matrix, the rows are averaged and the result is L2-normalised, matching what the reference
 * `model2vec` implementation does for a static model.
 *
 * Everything here is plain JVM, so the Balanced profile is covered by ordinary unit tests.
 *
 * @param embeddingMatrix row-major `vocabularySize * dimensions`
 */
class StaticTokenEmbedder(
    private val embeddingMatrix: FloatArray,
    override val dimensions: Int,
    private val vocabularySize: Int,
    private val tokenizer: WordPieceTokenizer,
) : TextEmbedder {

    override val runtimeName: String = "kotlin-static"

    init {
        require(dimensions > 0) { "dimensions must be positive" }
        require(embeddingMatrix.size >= vocabularySize * dimensions) {
            "embedding matrix holds ${embeddingMatrix.size} values, need ${vocabularySize * dimensions}"
        }
    }

    override fun embed(text: String): FloatArray {
        val encoding = tokenizer.encode(text)
        val hidden = FloatArray(dimensions)
        var counted = 0

        for (token in 0 until encoding.tokenCount) {
            val id = encoding.inputIds[token]
            if (id < 0 || id >= vocabularySize) continue
            if (encoding.attentionMask[token] == 0) continue
            val base = id * dimensions
            for (d in 0 until dimensions) {
                hidden[d] += embeddingMatrix[base + d]
            }
            counted++
        }

        if (counted == 0) return hidden
        for (d in 0 until dimensions) hidden[d] /= counted
        return EmbeddingMath.l2Normalize(hidden)
    }

    override fun close() = Unit

    companion object {
        /**
         * Load the token embedding table from the model directory.
         *
         * The published file uses the safetensors container; the table is accepted under either of
         * the names the reference repository uses so a re-quantised model keeps working.
         */
        fun load(modelDirectory: File, sessionFile: String = "model.safetensors"): AppResult<StaticTokenEmbedder> {
            val weights = File(modelDirectory, sessionFile)
            val vocabularyFile = File(modelDirectory, ModelStore.VOCAB_FILE)
            if (!weights.isFile) {
                return AppResult.failure(SubLearnError(SubLearnError.Kind.NotFound, "no $sessionFile in ${modelDirectory.name}"))
            }
            if (!vocabularyFile.isFile) {
                return AppResult.failure(
                    SubLearnError(SubLearnError.Kind.NotFound, "no ${ModelStore.VOCAB_FILE} in ${modelDirectory.name}"),
                )
            }

            val vocabulary = readVocabulary(vocabularyFile)
            if (vocabulary.isEmpty()) {
                return AppResult.failure(SubLearnError(SubLearnError.Kind.MalformedInput, "the vocabulary file is empty"))
            }

            val loaded = Safetensors.open(weights)
            if (loaded is AppResult.Failure) return loaded

            return (loaded as AppResult.Success).value.use { safetensors ->
                val name = EMBEDDING_TENSOR_NAMES.firstOrNull { safetensors.tensors.containsKey(it) }
                    ?: return AppResult.failure(
                        SubLearnError(
                            SubLearnError.Kind.MalformedInput,
                            "none of ${EMBEDDING_TENSOR_NAMES.joinToString()} is in the model file",
                        ),
                    )
                val info = safetensors.tensors.getValue(name)
                if (info.shape.size != 2) {
                    return AppResult.failure(
                        SubLearnError(SubLearnError.Kind.MalformedInput, "$name is not a matrix (shape ${info.shape.toList()})"),
                    )
                }
                val matrix = safetensors.readTensor(name)
                    ?: return AppResult.failure(
                        SubLearnError(SubLearnError.Kind.MalformedInput, "$name uses an unsupported dtype ${info.dtype}"),
                    )
                AppResult.success(
                    StaticTokenEmbedder(
                        embeddingMatrix = matrix,
                        dimensions = info.rowLength,
                        vocabularySize = info.rowCount,
                        tokenizer = WordPieceTokenizer(vocabulary),
                    ),
                )
            }
        }

        /**
         * Read a `vocab.txt`: one token per line, index = line number. The final newline does not
         * produce a token, which is the convention every BERT vocabulary file follows.
         */
        fun readVocabulary(file: File): List<String> {
            val lines = file.readLines()
            return lines.dropLastWhile { it.isEmpty() }
        }

        private val EMBEDDING_TENSOR_NAMES = listOf("embeddings", "embedding", "token_embeddings", "word_embeddings")
    }
}
