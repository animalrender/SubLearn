package com.sublearn.core.models

/**
 * Describes a downloadable model or data pack.
 *
 * The catalog is a JSON file (default: `docs/catalog/catalog-v1.json` served via GitHub raw URL)
 * that lists every model and data pack the app can download. Each entry is verified against
 * the original host before being shipped in a release build.
 *
 * @property id unique identifier (e.g. "pos-opennlp-ud")
 * @property kind the type of model/data
 * @property nameEn display name in English
 * @property nameFa display name in Persian
 * @property version semver version string
 * @property sizeBytes approximate size in bytes
 * @property sha256 expected SHA-256 hash (null if not yet verified)
 * @property source where to download from
 * @property files the individual files in this pack
 * @property runtime the runtime needed (NONE for data packs, ONNX for ONNX models, LITERT for LiteRT)
 * @property minRamMb minimum recommended RAM
 * @property profile the accuracy profile this belongs to
 * @property verified whether the entry has been verified against the source
 * @property notes additional notes for the user
 */
data class ModelCatalogEntry(
    val id: String,
    val kind: ModelKind,
    val nameEn: String,
    val nameFa: String,
    val version: String,
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

enum class ModelKind {
    LEXICON,
    POS_TAGGER,
    EMBEDDER_STATIC,
    EMBEDDER_ONNX,
    WSD_ONNX,
    LLM,
}

enum class ModelRuntime {
    NONE,
    ONNX,
    LITERT,
}

enum class AccuracyProfile {
    BASIC,
    LIGHT,
    BALANCED,
    PRECISE,
    ADVANCED,
    SMART,
}

data class SourceInfo(
    val host: String,
    val url: String,
)

data class FileInfo(
    val name: String,
    val sizeBytes: Long,
    val sha256: String? = null,
)

/**
 * The model catalog: a list of available models and data packs.
 */
data class ModelCatalog(
    val version: Int,
    val updatedAt: String,
    val entries: List<ModelCatalogEntry>,
) {
    fun findById(id: String): ModelCatalogEntry? = entries.firstOrNull { it.id == id }

    fun findByKind(kind: ModelKind): List<ModelCatalogEntry> = entries.filter { it.kind == kind }

    fun findByProfile(profile: AccuracyProfile): List<ModelCatalogEntry> = entries.filter { it.profile <= profile }
}
