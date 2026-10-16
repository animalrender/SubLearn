package com.sublearn.core.ai

import java.io.File

/**
 * One file a model needs, and where it comes from.
 *
 * @property name the name it is stored under, which is what the runtimes open
 * @property sizeBytes the publisher's size, used as a cheap truncation check
 * @property sha256 the publisher's digest, or null for a file the host does not publish one for
 * @property url the exact file to fetch
 */
data class ModelAsset(
    val name: String,
    val sizeBytes: Long,
    val sha256: String?,
    val url: String,
)

/**
 * The files a catalog model needs on disk, and where they go.
 *
 * Kept separate from both the downloader and the runtime so the download screen, the installer and
 * the ONNX/LLM loaders cannot disagree about a filename. The assets mirror the `files[]` list in
 * `docs/catalog/catalog-v2.json` one-for-one; `tools/refresh_model_catalog.py` writes that list
 * from the publisher's own metadata.
 *
 * @property id catalog id, matching `docs/catalog/catalog-v2.json`
 * @property directoryName folder under the models root
 * @property assets every file that must be present, primary (the model) first
 * @property vocabularyFile the WordPiece `vocab.txt` for the encoder models, if any
 * @property requiresTermsAcceptance the host answers an unauthenticated request with 401
 */
data class ModelFileLayout(
    val id: String,
    val directoryName: String,
    val assets: List<ModelAsset>,
    val vocabularyFile: String? = null,
    val requiresTermsAcceptance: Boolean = false,
) {
    /** Every file that must exist for the model to be usable. */
    val requiredFiles: List<String> get() = assets.map { it.name }

    /** The file the runtime opens: the primary asset. */
    val sessionFile: String? get() = assets.firstOrNull()?.name

    /** Total download size, which is what the download screen shows. */
    val downloadBytes: Long get() = assets.sumOf { it.sizeBytes }

    fun directoryUnder(root: File): File = File(root, directoryName)

    fun fileUnder(root: File, name: String): File = File(directoryUnder(root), name)

    /** True when every asset is present and non-empty. */
    fun isInstalledUnder(root: File): Boolean = requiredFiles.all { name ->
        val file = fileUnder(root, name)
        file.isFile && file.length() > 0
    }
}

/**
 * Where each catalog model lives on the device, and whether it is there.
 *
 * The filesystem is the source of truth: nothing is cached in settings, so a model copied onto
 * the device by hand is usable, and a model whose files were deleted stops being offered. The
 * root is injected, which is what lets the tests run the whole check on a temporary directory.
 */
class ModelStore(val root: File) {

    init {
        root.mkdirs()
    }

    fun layoutFor(modelId: String): ModelFileLayout? = LAYOUTS[modelId]

    fun isInstalled(modelId: String): Boolean = layoutFor(modelId)?.isInstalledUnder(root) == true

    /** Ids of every model in [models] that is fully present on disk. */
    fun installedIds(models: List<NlpModel> = NlpModelCatalog.all): Set<String> =
        models.filter { isInstalled(it.id) }.map { it.id }.toSet()

    /** The session file to hand a runtime, or null when the model is not installed. */
    fun sessionFileFor(modelId: String): File? {
        val layout = layoutFor(modelId) ?: return null
        val name = layout.sessionFile ?: return null
        if (!isInstalled(modelId)) return null
        return layout.fileUnder(root, name)
    }

    fun vocabularyFileFor(modelId: String): File? {
        val layout = layoutFor(modelId) ?: return null
        val name = layout.vocabularyFile ?: return null
        if (!isInstalled(modelId)) return null
        val file = layout.fileUnder(root, name)
        return file.takeIf { it.isFile }
    }

    /** Directory a download should extract into. Created on demand. */
    fun stagingDirectoryFor(modelId: String): File {
        val layout = layoutFor(modelId) ?: error("no layout for model $modelId")
        return layout.directoryUnder(root).also { it.mkdirs() }
    }

    fun delete(modelId: String): Boolean {
        val layout = layoutFor(modelId) ?: return false
        return layout.directoryUnder(root).deleteRecursively()
    }

    companion object {
        /**
         * The published file names, sizes and digests of every catalog model.
         *
         * Generated from `docs/catalog/catalog-v2.json`, which `tools/refresh_model_catalog.py`
         * and `tools/refresh_model_catalog.py --check` keep truthful. The encoder models need
         * their WordPiece vocabulary alongside the graph; the LiteRT containers carry their own
         * tokenizer, so they are a single asset.
         *
         * `tools/check_catalog.py` fails the build when this table and the catalog disagree.
         */
        val LAYOUTS: Map<String, ModelFileLayout> = listOf(
            ModelFileLayout(
                id = "emb-potion-base-8m",
                directoryName = "potion-base-8m",
                assets = listOf(
                    ModelAsset(
                        name = "model.safetensors",
                        sizeBytes = 30236760L,
                        sha256 = "f65d0f325faadc1e121c319e2faa41170d3fa07d8c89abd48ca5358d9a223de2",
                        url =
                            "https://huggingface.co/minishlab/potion-base-8M/resolve/main/model.safetensors",
                    ),
                    ModelAsset(
                        name = "vocab.txt",
                        sizeBytes = 219690L,
                        sha256 = null,
                        url =
                            "https://huggingface.co/minishlab/potion-base-8M/resolve/main/vocab.txt",
                    ),
                ),
                vocabularyFile = "vocab.txt",
            ),
            ModelFileLayout(
                id = "emb-minilm-l6-int8",
                directoryName = "minilm-l6-int8",
                assets = listOf(
                    ModelAsset(
                        name = "model_quantized.onnx",
                        sizeBytes = 22972370L,
                        sha256 = "afdb6f1a0e45b715d0bb9b11772f032c399babd23bfc31fed1c170afc848bdb1",
                        url =
                            "https://huggingface.co/Xenova/all-MiniLM-L6-v2/resolve/main/onnx/model_quantized.onnx",
                    ),
                    ModelAsset(
                        name = "vocab.txt",
                        sizeBytes = 231508L,
                        sha256 = null,
                        url =
                            "https://huggingface.co/Xenova/all-MiniLM-L6-v2/resolve/main/vocab.txt",
                    ),
                ),
                vocabularyFile = "vocab.txt",
            ),
            ModelFileLayout(
                id = "wsd-distilbert-int8",
                directoryName = "distilbert-int8",
                assets = listOf(
                    ModelAsset(
                        name = "model_quantized.onnx",
                        sizeBytes = 67710626L,
                        sha256 = "52ff4db0aaa896d9af790a1464373a77923dcd27d0479859f429598c888d8b09",
                        url =
                            "https://huggingface.co/Xenova/distilbert-base-uncased/resolve/main/onnx/model_quantized.onnx",
                    ),
                    ModelAsset(
                        name = "vocab.txt",
                        sizeBytes = 231508L,
                        sha256 = null,
                        url =
                            "https://huggingface.co/Xenova/distilbert-base-uncased/resolve/main/vocab.txt",
                    ),
                ),
                vocabularyFile = "vocab.txt",
            ),
            ModelFileLayout(
                id = "llm-qwen25-05b",
                directoryName = "qwen25-05b",
                assets = listOf(
                    ModelAsset(
                        name = "qwen2.5-0.5b-instruct.task",
                        sizeBytes = 546660344L,
                        sha256 = "e608953f169aeb1bd7b9155fec2559825e08453fc209b84eda3a781ed0452fd2",
                        url =
                            "https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct/resolve/main/Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
                    ),
                ),
            ),
            ModelFileLayout(
                id = "llm-smollm2-360m",
                directoryName = "smollm2-360m",
                assets = listOf(
                    ModelAsset(
                        name = "smollm2-360m-instruct.litertlm",
                        sizeBytes = 373719040L,
                        sha256 = "8e2834da211b439751af968ed650febdde5a8cb8d88bc6c1a3059f049caa5c2e",
                        url =
                            "https://huggingface.co/litert-community/SmolLM2-360M-Instruct/resolve/main/SmolLM2_360M_instruct.litertlm",
                    ),
                ),
            ),
            ModelFileLayout(
                id = "llm-gemma3-270m",
                directoryName = "gemma3-270m",
                assets = listOf(
                    ModelAsset(
                        name = "gemma3-270m-it.task",
                        sizeBytes = 303950933L,
                        sha256 = "0f7147f1c22eaf758b819bbf7841793e4c90096c9352cde7fbe5c631f2265ef5",
                        url =
                            "https://huggingface.co/litert-community/gemma-3-270m-it/resolve/main/gemma3-270m-it-q8.task",
                    ),
                ),
                requiresTermsAcceptance = true,
            ),
        ).associateBy { it.id }

        /** Every `vocab.txt` in the catalog is the same WordPiece vocabulary shape. */
        const val VOCAB_FILE = "vocab.txt"

        /** Layout for a catalog id, or null when the catalog has an entry this build cannot use. */
        fun layout(modelId: String): ModelFileLayout? = LAYOUTS[modelId]
    }
}
