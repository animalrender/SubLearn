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
        updatedAt = "2026-10-12T00:00:00Z",
        entries = listOf(
            ModelCatalogEntry(
                id = "sublearn-lexicon-en-fa-v2",
                kind = ModelKind.LEXICON,
                nameEn = "SubLearn English–Persian Lexicon Pack v2",
                nameFa = "پک واژه‌نامه و عبارات انگلیسی به فارسی (نسخهٔ ۲)",
                version = "2.0.0",
                direction = "en-fa",
                license = "ODbL-1.0 / CC-BY-SA-4.0",
                sizeBytes = 35_863_515L,
                sha256 = "500696765c0d5339c5c4d0823604d8d53fec9abff96bd1dd1b0bf1b4504669ef",
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
                sizeBytes = 14_044_817L,
                sha256 = "917e10115883394d5faa80a21ca1b39ceb6da2173f2c279f98dea84d44086f72",
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
                license = "MIT",
                sizeBytes = 30_212_096L,
                source = SourceInfo(
                    host = "huggingface.co",
                    url = "https://huggingface.co/minishlab/potion-base-8M/resolve/main/model.safetensors",
                ),
                runtime = ModelRuntime.NONE,
                minRamMb = 128,
                profile = AccuracyProfile.BALANCED,
                verified = true,
            ),
            ModelCatalogEntry(
                id = "emb-minilm-l6-int8",
                kind = ModelKind.EMBEDDER_ONNX,
                nameEn = "Sentence Embeddings (MiniLM-L6-v2 INT8)",
                nameFa = "مدل بردار جمله (MiniLM INT8)",
                version = "1.0.0",
                direction = "en",
                license = "Apache-2.0",
                sizeBytes = 23_068_672L,
                source = SourceInfo(
                    host = "huggingface.co",
                    url = "https://huggingface.co/Xenova/all-MiniLM-L6-v2/resolve/main/onnx/model_quantized.onnx",
                ),
                runtime = ModelRuntime.ONNX,
                minRamMb = 256,
                profile = AccuracyProfile.PRECISE,
                verified = true,
            ),
            ModelCatalogEntry(
                id = "wsd-distilbert-int8",
                kind = ModelKind.WSD_ONNX,
                nameEn = "Contextual Sense Disambiguator (DistilBERT INT8)",
                nameFa = "ابهام‌زدای متنی دقیق (DistilBERT INT8)",
                version = "1.0.0",
                direction = "en",
                license = "Apache-2.0",
                sizeBytes = 67_895_296L,
                source = SourceInfo(
                    host = "huggingface.co",
                    url = "https://huggingface.co/Xenova/distilbert-base-uncased/resolve/main/onnx/model_quantized.onnx",
                ),
                runtime = ModelRuntime.ONNX,
                minRamMb = 512,
                profile = AccuracyProfile.ADVANCED,
                verified = true,
            ),
            ModelCatalogEntry(
                id = "llm-qwen25-05b",
                kind = ModelKind.LLM,
                nameEn = "On-Device Sense Selector (Qwen2.5 0.5B Instruct)",
                nameFa = "مدل هوشمند انتخاب معنی (Qwen2.5 0.5B)",
                version = "1.0.0",
                direction = "en-fa",
                license = "Apache-2.0",
                sizeBytes = 524_288_000L,
                source = SourceInfo(
                    host = "huggingface.co",
                    url = "https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct/resolve/main/Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
                ),
                runtime = ModelRuntime.LITERT,
                minRamMb = 1536,
                profile = AccuracyProfile.SMART,
                verified = true,
            ),
            ModelCatalogEntry(
                id = "llm-smollm2-360m",
                kind = ModelKind.LLM,
                nameEn = "On-Device Sense Selector (SmolLM2 360M Instruct)",
                nameFa = "مدل هوشمند سبک (SmolLM2 360M)",
                version = "1.0.0",
                direction = "en",
                license = "Apache-2.0",
                sizeBytes = 377_487_360L,
                source = SourceInfo(
                    host = "huggingface.co",
                    url = "https://huggingface.co/litert-community/SmolLM2-360M-Instruct/resolve/main/SmolLM2-360M-Instruct_multi-prefill-seq_q8_ekv1280.task",
                ),
                runtime = ModelRuntime.LITERT,
                minRamMb = 1024,
                profile = AccuracyProfile.SMART,
                verified = true,
            ),
        ),
    )
}
