package com.sublearn.core.ai

import com.sublearn.core.common.AppResult
import com.sublearn.core.common.SubLearnError

/**
 * Runtime an optional NLP model needs.
 *
 * `NONE` is pure Kotlin and always present. The other two are native engines in separate modules;
 * [isAvailable] reports whether the running build registered them (`D-74`).
 */
enum class NlpModelRuntime {
    /** Pure-Kotlin inference: rankers over the lexicon pack, lookup-table embeddings. */
    NONE,

    /** ONNX Runtime via `com.microsoft.onnxruntime:onnxruntime-android` (`core:nlp-onnx`). */
    ONNX,

    /** LiteRT-LM bundles through MediaPipe's GenAI LLM Inference API (`core:nlp-llm`). */
    LITERT,
    ;

    /**
     * Whether this build can actually execute the runtime.
     *
     * Answered by [NlpRuntimeRegistry] rather than by a constant, because the engines live in
     * modules that depend on this one. The app registers the runtimes it was built with during
     * dependency injection setup (`D-74`); anything that never registers — a unit test, a tool —
     * reports `false` for ONNX and LiteRT, so a profile is never claimed to work without proof.
     */
    val isAvailable: Boolean get() = NlpRuntimeRegistry.isAvailable(this)
}

/**
 * One downloadable model the accuracy profiles can use.
 *
 * @property id catalog id, matching `docs/catalog/catalog-v2.json`
 * @property name what the settings screen shows
 * @property runtime which engine executes it
 * @property sizeBytes download size, kept in step with the catalog
 * @property minRamMb RAM the device should have free before the model is offered
 */
data class NlpModel(
    val id: String,
    val name: String,
    val runtime: NlpModelRuntime,
    val sizeBytes: Long,
    val minRamMb: Int,
    /**
     * The publisher answers an unauthenticated request with 401 until the licence is accepted, so
     * the settings card states that instead of offering a download that cannot finish.
     */
    val requiresTermsAcceptance: Boolean = false,
    /** `.litertlm` is not a MediaPipe `.task`; this build has no LiteRT-LM engine. */
    val requiresLiteRtLmEngine: Boolean = false,
)

/**
 * The accuracy profiles (`AccuracyProfileToken`) and the models each one adds, mirroring
 * `docs/catalog/catalog-v2.json`. Kept as data so a profile change is a one-line diff and the
 * catalog check can compare both sides.
 */
object NlpModelCatalog {

    val POTION_BASE_8M = NlpModel(
        id = "emb-potion-base-8m",
        name = "potion-base-8M",
        runtime = NlpModelRuntime.NONE,
        sizeBytes = 30_456_450L,
        minRamMb = 128,
    )

    val MINILM_L6_INT8 = NlpModel(
        id = "emb-minilm-l6-int8",
        name = "all-MiniLM-L6-v2 (int8)",
        runtime = NlpModelRuntime.ONNX,
        sizeBytes = 23_203_878L,
        minRamMb = 256,
    )

    val DISTILBERT_INT8 = NlpModel(
        id = "wsd-distilbert-int8",
        name = "distilbert-base-uncased (int8)",
        runtime = NlpModelRuntime.ONNX,
        sizeBytes = 67_942_134L,
        minRamMb = 512,
    )

    val QWEN25_05B = NlpModel(
        id = "llm-qwen25-05b",
        name = "Qwen2.5-0.5B-Instruct",
        runtime = NlpModelRuntime.LITERT,
        sizeBytes = 546_660_344L,
        minRamMb = 1536,
    )

    val SMOLLM2_360M = NlpModel(
        id = "llm-smollm2-360m",
        name = "SmolLM2-360M-Instruct",
        runtime = NlpModelRuntime.LITERT,
        sizeBytes = 373_719_040L,
        minRamMb = 1024,
        requiresLiteRtLmEngine = true,
    )

    val GEMMA3_270M = NlpModel(
        id = "llm-gemma3-270m",
        name = "Gemma3-270M-IT",
        runtime = NlpModelRuntime.LITERT,
        sizeBytes = 303_950_933L,
        minRamMb = 1024,
        // `litert-community/gemma-3-270m-it` is `gated: auto` on the model host.
        requiresTermsAcceptance = true,
    )

    /** Every model the catalog knows, in profile order. */
    val all: List<NlpModel> = listOf(
        POTION_BASE_8M,
        MINILM_L6_INT8,
        DISTILBERT_INT8,
        QWEN25_05B,
        SMOLLM2_360M,
        GEMMA3_270M,
    )

    val byId: Map<String, NlpModel> = all.associateBy { it.id }

    /**
     * Further models the SMART tier may run instead of [QWEN25_05B]. All three are LiteRT LLMs;
     * the smaller two exist for phones that cannot hold the 0.5B weights, so the tier is a set of
     * choices rather than one mandatory download.
     */
    val smartAlternatives: List<NlpModel> = listOf(SMOLLM2_360M, GEMMA3_270M)

    /** The models a profile needs on top of the lexicon pack. */
    fun modelsFor(profile: ProfileTier): List<NlpModel> = when (profile) {
        ProfileTier.BASIC -> emptyList()
        ProfileTier.LIGHT -> emptyList()
        ProfileTier.BALANCED -> listOf(POTION_BASE_8M)
        ProfileTier.PRECISE -> listOf(POTION_BASE_8M, MINILM_L6_INT8)
        ProfileTier.ADVANCED -> listOf(POTION_BASE_8M, MINILM_L6_INT8, DISTILBERT_INT8)
        ProfileTier.SMART -> listOf(POTION_BASE_8M, MINILM_L6_INT8, DISTILBERT_INT8, QWEN25_05B)
        ProfileTier.CUSTOM -> emptyList()
    }

    /** Total download a profile adds, in bytes. */
    fun downloadBytesFor(profile: ProfileTier): Long = modelsFor(profile).sumOf { it.sizeBytes }

    /**
     * The subset of [modelsFor] that has to be *resident* for the profile to do its job.
     *
     * The download list and the runtime list differ on purpose. A SMART device downloads all four
     * models so it can upgrade or downgrade without another transfer, but it only keeps the
     * word-sense encoder and the prompt model in memory: 640 MB of weights resident on a phone
     * would be an out-of-memory crash, not a feature.
     *
     * Every runtime model appears in [modelsFor], which a unit test asserts.
     */
    fun runtimeModelsFor(profile: ProfileTier): List<NlpModel> = when (profile) {
        ProfileTier.BASIC, ProfileTier.LIGHT, ProfileTier.CUSTOM -> emptyList()
        ProfileTier.BALANCED -> listOf(POTION_BASE_8M)
        ProfileTier.PRECISE -> listOf(MINILM_L6_INT8)
        // Order matters: [ProfiledNlpEngine] walks this list and keeps the first embedder it opens,
        // so the strongest encoder comes first.
        ProfileTier.ADVANCED -> listOf(DISTILBERT_INT8)
        ProfileTier.SMART -> listOf(DISTILBERT_INT8, QWEN25_05B)
    }

    /** True when every runtime the profile needs exists in this build. */
    fun isRunnableInThisBuild(profile: ProfileTier): Boolean =
        modelsFor(profile).all { it.runtime.isAvailable }
}

/**
 * Accuracy profiles as the model catalog sees them. Mirrors `AccuracyProfileToken` from
 * `core:settings`, minus the settings-tree serialization concern.
 */
enum class ProfileTier {
    BASIC,
    LIGHT,
    BALANCED,
    PRECISE,
    ADVANCED,
    SMART,
    CUSTOM,
    ;

    companion object {
        /** Map the persisted settings token to the catalog tier. */
        fun fromToken(tokenName: String): ProfileTier = when (tokenName) {
            "BASIC" -> BASIC
            "LIGHT" -> LIGHT
            "BALANCED" -> BALANCED
            "PRECISE" -> PRECISE
            "ADVANCED" -> ADVANCED
            "SMART_EXPERIMENTAL" -> SMART
            else -> CUSTOM
        }
    }
}

/** What the device reports about itself, used to pick a profile automatically. */
data class DeviceCapabilities(
    val totalRamMb: Int,
    val cpuCores: Int,
    val batterySaver: Boolean,
    val freeStorageMb: Long,
)

/** Why a profile cannot be used right now. */
sealed interface ProfileAvailability {
    data object Ready : ProfileAvailability
    data class NeedsRam(val requiredMb: Int) : ProfileAvailability
    data class NeedsStorage(val requiredMb: Long) : ProfileAvailability
    data class NeedsModel(val modelId: String) : ProfileAvailability
    data class RuntimeMissing(val runtime: NlpModelRuntime) : ProfileAvailability
}

/**
 * Picks the strongest profile the device can run (§5.1).
 *
 * The automatic choice never returns a profile whose runtime this build lacks, so `SMART`
 * on a large phone degrades to `LIGHT` rather than pretending to run an LLM.
 */
object SmartProfileSelector {

    /**
     * Highest-rung profile that fits the device. Never throws: the smallest profile is always
     * runnable because it needs no model.
     *
     * @param installedModelIds the models that are on the device. Without them only the profiles
     * that need no model at all can be ready, which is why the caller passes what it has.
     */
    fun select(device: DeviceCapabilities, installedModelIds: Set<String> = emptySet()): ProfileTier {
        val candidates = listOf(
            ProfileTier.ADVANCED,
            ProfileTier.PRECISE,
            ProfileTier.BALANCED,
        )
        for (profile in candidates) {
            if (!NlpModelCatalog.isRunnableInThisBuild(profile)) continue
            if (availability(profile, device, installedModelIds) is ProfileAvailability.Ready) return profile
        }
        return if (device.cpuCores >= 4) ProfileTier.LIGHT else ProfileTier.BASIC
    }

    /**
     * Whether [profile] can be used now: model runtimes exist, the device has the RAM, the
     * download fits in free storage and every model is installed.
     */
    fun availability(
        profile: ProfileTier,
        device: DeviceCapabilities,
        installedModelIds: Set<String>,
    ): ProfileAvailability {
        for (model in NlpModelCatalog.modelsFor(profile)) {
            if (!model.runtime.isAvailable) return ProfileAvailability.RuntimeMissing(model.runtime)
        }
        val requiredRam = NlpModelCatalog.modelsFor(profile).maxOfOrNull { it.minRamMb } ?: 0
        if (device.totalRamMb < requiredRam) return ProfileAvailability.NeedsRam(requiredRam)

        val missing = NlpModelCatalog.modelsFor(profile).firstOrNull { it.id !in installedModelIds }
        if (missing != null) {
            val requiredStorageMb = NlpModelCatalog.downloadBytesFor(profile) / 1_048_576L
            if (device.freeStorageMb < requiredStorageMb) {
                return ProfileAvailability.NeedsStorage(requiredStorageMb)
            }
            return ProfileAvailability.NeedsModel(missing.id)
        }
        return ProfileAvailability.Ready
    }

    /** True when the profile has everything it needs except models that cannot be downloaded yet. */
    fun isUsableNow(profile: ProfileTier, device: DeviceCapabilities): Boolean =
        availability(profile, device, emptySet()) !is ProfileAvailability.RuntimeMissing
}

/**
 * Gate in front of the model-backed analysis paths.
 *
 * The failure is reported, not hidden: the settings screen offers the profile, explains which
 * model it needs, and the analysis path returns a `NotImplemented` error rather than quietly
 * falling back to weaker results the user did not choose.
 */
object NlpModelRuntimeGuard {

    /** `Success(Unit)` when the profile's runtimes exist in this build, otherwise a clear error. */
    fun check(profile: ProfileTier): AppResult<Unit> {
        val missing = NlpModelCatalog.modelsFor(profile).firstOrNull { !it.runtime.isAvailable }
        if (missing != null) {
            return AppResult.failure(
                SubLearnError(
                    SubLearnError.Kind.NotImplemented,
                    "profile ${profile.name} needs the ${missing.runtime.name} runtime for " +
                        "${missing.name}, which is not part of this build",
                ),
            )
        }
        return AppResult.success(Unit)
    }
}
