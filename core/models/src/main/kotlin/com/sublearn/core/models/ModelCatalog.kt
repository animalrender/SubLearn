package com.sublearn.core.models

import com.sublearn.core.common.AppResult
import com.sublearn.core.common.SubLearnError
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Describes a downloadable model or data pack in `catalog-v2.json` (`D-51`).
 */
@Serializable
data class ModelCatalogEntry(
    val id: String,
    val kind: ModelKind,
    val nameEn: String,
    val nameFa: String,
    val version: String,
    val direction: String? = null,
    val license: String? = null,
    val requiresTermsAcceptance: Boolean = false,
    val sizeBytes: Long,
    val sha256: String? = null,
    val source: SourceInfo,
    val files: List<FileInfo> = emptyList(),
    val runtime: ModelRuntime = ModelRuntime.NONE,
    val minRamMb: Int = 0,
    val profile: AccuracyProfile = AccuracyProfile.BASIC,
    val verified: Boolean = false,
    val notes: String? = null,
)

@Serializable
enum class ModelKind {
    LEXICON,
    DICT_FA_EN,
    TRANSLATION_PACK,
    POS_TAGGER,
    EMBEDDER_STATIC,
    EMBEDDER_ONNX,
    WSD_ONNX,
    LLM,
}

@Serializable
enum class ModelRuntime {
    NONE,
    ONNX,
    LITERT,
}

@Serializable
enum class AccuracyProfile {
    BASIC,
    LIGHT,
    BALANCED,
    PRECISE,
    ADVANCED,
    SMART,
}

@Serializable
data class SourceInfo(
    val host: String,
    val url: String,
)

@Serializable
data class FileInfo(
    val name: String,
    val sizeBytes: Long,
    val sha256: String? = null,
)

/**
 * The model catalog: a list of available models and data packs (`D-51`).
 */
@Serializable
data class ModelCatalog(
    val version: Int,
    val updatedAt: String,
    val entries: List<ModelCatalogEntry>,
) {
    fun findById(id: String): ModelCatalogEntry? = entries.firstOrNull { it.id == id }

    fun findByKind(kind: ModelKind): List<ModelCatalogEntry> = entries.filter { it.kind == kind }

    fun findByProfile(profile: AccuracyProfile): List<ModelCatalogEntry> = entries.filter { it.profile <= profile }

    /**
     * Returns entries eligible for one-click download in the UI (`D-51`: `verified == true` and
     * non-blank download URL unless `includeUnverified == true`).
     */
    fun visibleEntries(includeUnverified: Boolean = false): List<ModelCatalogEntry> =
        if (includeUnverified) entries else entries.filter { it.verified && it.source.url.isNotBlank() }
}

object ModelCatalogParser {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun parse(jsonText: String): AppResult<ModelCatalog> = try {
        val decoded = json.decodeFromString(ModelCatalog.serializer(), jsonText)
        AppResult.success(decoded)
    } catch (t: Throwable) {
        AppResult.failure(
            SubLearnError(SubLearnError.Kind.MalformedInput, "Invalid model catalog JSON: ${t.message}", t),
        )
    }

    fun encode(catalog: ModelCatalog): String = json.encodeToString(ModelCatalog.serializer(), catalog)

    fun bundledDefault(): ModelCatalog = ModelCatalog(
        version = 2,
        updatedAt = "2026-10-16T00:00:00Z",
        entries = listOf(
            ModelCatalogEntry(
                id = "sublearn-lexicon-en-fa-v2",
                kind = ModelKind.LEXICON,
                nameEn = "SubLearn English–Persian Lexicon Pack v2",
                nameFa = "پک واژه‌نامه و عبارات انگلیسی به فارسی (نسخهٔ ۲)",
                version = "2.0.0",
                direction = "en-fa",
                license = "ODbL-1.0 / CC-BY-SA-4.0",
                sizeBytes = 35_648_482L,
                sha256 = "1957c0afbf7e7b7c42d5453f91120a5726a53747b67132b8005851118001b95e",
                source = SourceInfo(
                    host = "github.com",
                    url = "https://github.com/animalrender/SubLearn/releases/download/packs-v2.0.0/sublearn-lexicon-en-fa-v2.sqlite.gz",
                ),
                runtime = ModelRuntime.NONE,
                minRamMb = 64,
                profile = AccuracyProfile.BASIC,
                verified = true,
                notes = "102k+ English headwords, phrasal verbs, idioms, collocations, CEFR levels, and Persian senses.",
            ),
            ModelCatalogEntry(
                id = "sublearn-dict-fa-en-v2",
                kind = ModelKind.DICT_FA_EN,
                nameEn = "SubLearn Persian–English Dictionary Pack v2",
                nameFa = "پک دیکشنری فارسی به انگلیسی (نسخهٔ ۲)",
                version = "2.0.0",
                direction = "fa-en",
                license = "ODbL-1.0 / CC-BY-SA-4.0",
                sizeBytes = 14_044_816L,
                sha256 = "88597dbd0a977953061fde6f351519f759b1dff43ca509fb81b4bb2c20ece1b2",
                source = SourceInfo(
                    host = "github.com",
                    url = "https://github.com/animalrender/SubLearn/releases/download/packs-v2.0.0/sublearn-dict-fa-en-v1.sqlite.gz",
                ),
                runtime = ModelRuntime.NONE,
                minRamMb = 64,
                profile = AccuracyProfile.BASIC,
                verified = true,
                notes = "107k+ Persian headwords with English equivalents and examples.",
            ),
            ModelCatalogEntry(
                id = "emb-potion-base-8m",
                kind = ModelKind.EMBEDDER_STATIC,
                nameEn = "Static Sense Embeddings (Potion-base-8M)",
                nameFa = "بردارهای ایستا برای ابهام‌زدایی معنایی (Potion 8M)",
                version = "1.0.0",
                direction = "en",
                license = "mit",
                sizeBytes = 30_456_450L,
                sha256 = "f65d0f325faadc1e121c319e2faa41170d3fa07d8c89abd48ca5358d9a223de2",
                source = SourceInfo(
                    host = "huggingface.co",
                    url = "https://huggingface.co/minishlab/potion-base-8M/resolve/main/model.safetensors",
                ),
                files = listOf(
                    FileInfo(
                        name = "model.safetensors",
                        sizeBytes = 30_236_760L,
                        sha256 = "f65d0f325faadc1e121c319e2faa41170d3fa07d8c89abd48ca5358d9a223de2",
                    ),
                    FileInfo(
                        name = "vocab.txt",
                        sizeBytes = 219_690L,
                    ),
                ),
                runtime = ModelRuntime.NONE,
                minRamMb = 128,
                profile = AccuracyProfile.BALANCED,
                verified = true,
                notes = "Ultra-fast static token embeddings for context-to-sense cosine ranking.",
            ),
            ModelCatalogEntry(
                id = "emb-minilm-l6-int8",
                kind = ModelKind.EMBEDDER_ONNX,
                nameEn = "Sentence Embeddings (MiniLM-L6-v2 INT8)",
                nameFa = "مدل بردار جمله (MiniLM INT8)",
                version = "1.0.0",
                direction = "en",
                license = "apache-2.0",
                sizeBytes = 23_203_878L,
                sha256 = "afdb6f1a0e45b715d0bb9b11772f032c399babd23bfc31fed1c170afc848bdb1",
                source = SourceInfo(
                    host = "huggingface.co",
                    url = "https://huggingface.co/Xenova/all-MiniLM-L6-v2/resolve/main/onnx/model_quantized.onnx",
                ),
                files = listOf(
                    FileInfo(
                        name = "model_quantized.onnx",
                        sizeBytes = 22_972_370L,
                        sha256 = "afdb6f1a0e45b715d0bb9b11772f032c399babd23bfc31fed1c170afc848bdb1",
                    ),
                    FileInfo(
                        name = "vocab.txt",
                        sizeBytes = 231_508L,
                    ),
                ),
                runtime = ModelRuntime.ONNX,
                minRamMb = 256,
                profile = AccuracyProfile.PRECISE,
                verified = true,
                notes = "Quantized INT8 ONNX bi-encoder for sentence-level sense ranking.",
            ),
            ModelCatalogEntry(
                id = "wsd-distilbert-int8",
                kind = ModelKind.WSD_ONNX,
                nameEn = "Contextual Sense Disambiguator (DistilBERT INT8)",
                nameFa = "ابهام‌زدای متنی دقیق (DistilBERT INT8)",
                version = "1.0.0",
                direction = "en",
                license = "apache-2.0",
                sizeBytes = 67_942_134L,
                sha256 = "52ff4db0aaa896d9af790a1464373a77923dcd27d0479859f429598c888d8b09",
                source = SourceInfo(
                    host = "huggingface.co",
                    url = "https://huggingface.co/Xenova/distilbert-base-uncased/resolve/main/onnx/model_quantized.onnx",
                ),
                files = listOf(
                    FileInfo(
                        name = "model_quantized.onnx",
                        sizeBytes = 67_710_626L,
                        sha256 = "52ff4db0aaa896d9af790a1464373a77923dcd27d0479859f429598c888d8b09",
                    ),
                    FileInfo(
                        name = "vocab.txt",
                        sizeBytes = 231_508L,
                    ),
                ),
                runtime = ModelRuntime.ONNX,
                minRamMb = 512,
                profile = AccuracyProfile.ADVANCED,
                verified = true,
                notes = "Contextual token representations for polysemous words and idioms.",
            ),
            ModelCatalogEntry(
                id = "llm-qwen25-05b",
                kind = ModelKind.LLM,
                nameEn = "On-Device Sense Selector (Qwen2.5 0.5B Instruct)",
                nameFa = "مدل هوشمند انتخاب معنی (Qwen2.5 0.5B)",
                version = "1.0.0",
                direction = "en-fa",
                license = "apache-2.0",
                sizeBytes = 546_660_344L,
                sha256 = "e608953f169aeb1bd7b9155fec2559825e08453fc209b84eda3a781ed0452fd2",
                source = SourceInfo(
                    host = "huggingface.co",
                    url = "https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct/resolve/main/Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
                ),
                files = listOf(
                    FileInfo(
                        name = "qwen2.5-0.5b-instruct.task",
                        sizeBytes = 546_660_344L,
                        sha256 = "e608953f169aeb1bd7b9155fec2559825e08453fc209b84eda3a781ed0452fd2",
                    ),
                ),
                runtime = ModelRuntime.LITERT,
                minRamMb = 1536,
                profile = AccuracyProfile.SMART,
                verified = true,
                notes = "On-device LiteRT/MediaPipe LLM that picks the exact Persian sense for a subtitle line.",
            ),
            ModelCatalogEntry(
                id = "llm-smollm2-360m",
                kind = ModelKind.LLM,
                nameEn = "On-Device Sense Selector (SmolLM2 360M Instruct)",
                nameFa = "مدل هوشمند سبک (SmolLM2 360M)",
                version = "1.0.0",
                direction = "en",
                license = "apache-2.0",
                sizeBytes = 373_719_040L,
                sha256 = "8e2834da211b439751af968ed650febdde5a8cb8d88bc6c1a3059f049caa5c2e",
                source = SourceInfo(
                    host = "huggingface.co",
                    url = "https://huggingface.co/litert-community/SmolLM2-360M-Instruct/resolve/main/SmolLM2_360M_instruct.litertlm",
                ),
                files = listOf(
                    FileInfo(
                        name = "smollm2-360m-instruct.litertlm",
                        sizeBytes = 373_719_040L,
                        sha256 = "8e2834da211b439751af968ed650febdde5a8cb8d88bc6c1a3059f049caa5c2e",
                    ),
                ),
                runtime = ModelRuntime.LITERT,
                minRamMb = 1024,
                profile = AccuracyProfile.SMART,
                verified = true,
                notes = "Compact 360M LiteRT LLM for devices with 4–6 GB RAM.",
            ),
            ModelCatalogEntry(
                id = "llm-gemma3-270m",
                kind = ModelKind.LLM,
                nameEn = "On-Device Sense Selector (Gemma 3 270M IT)",
                nameFa = "مدل هوشمند جمّا (Gemma 3 270M)",
                version = "1.0.0",
                direction = "en",
                license = "gemma",
                requiresTermsAcceptance = true,
                sizeBytes = 303_950_933L,
                sha256 = "0f7147f1c22eaf758b819bbf7841793e4c90096c9352cde7fbe5c631f2265ef5",
                source = SourceInfo(
                    host = "huggingface.co",
                    url = "https://huggingface.co/litert-community/gemma-3-270m-it/resolve/main/gemma3-270m-it-q8.task",
                ),
                files = listOf(
                    FileInfo(
                        name = "gemma3-270m-it.task",
                        sizeBytes = 303_950_933L,
                        sha256 = "0f7147f1c22eaf758b819bbf7841793e4c90096c9352cde7fbe5c631f2265ef5",
                    ),
                ),
                runtime = ModelRuntime.LITERT,
                minRamMb = 1024,
                profile = AccuracyProfile.SMART,
                verified = true,
                notes = "Requires Hugging Face Gemma licence acceptance: accept it on huggingface.co before the download can start.",
            ),
        ),
    )
}
