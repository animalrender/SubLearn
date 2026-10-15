package com.sublearn.core.ai

import com.sublearn.core.common.AppResult
import com.sublearn.core.common.SubLearnError

/**
 * Runtime an optional NLP model needs. The app ships only the `NONE` tier; the others require
 * a native runtime that is not part of this build yet (see [NlpModelRuntime.isAvailable]).
 */
enum class NlpModelRuntime {
    /** Pure-Kotlin inference: rankers over the lexicon pack, lookup-table embeddings. */
    NONE,

    /** ONNX Runtime via `com.microsoft.onnxruntime:onnxruntime-android`. */
    ONNX,

    /** LiteRT / MediaPipe GenAI LLM runner. */
    LITERT,
    ;

    /**
     * Whether this build can actually execute the runtime. `false` is the honest answer for
     * ONNX and LiteRT: the dependencies and model loaders are not part of this build, so the
     * profiles that need them are offered but refuse to start instead of failing silently.
     */
    val isAvailable: Boolean get() = this == NONE
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
        sizeBytes = 30_212_096L,
        minRamMb = 128,
    )

    val MINILM_L6_INT8 = NlpModel(
        id = "emb-minilm-l6-int8",
        name = "all-MiniLM-L6-v2 (int8)",
        runtime = NlpModelRuntime.ONNX,
        sizeBytes = 23_068_672L,
        minRamMb = 256,
    )

    val DISTILBERT_INT8 = NlpModel(
        id = "wsd-distilbert-int8",
        name = "distilbert-base-uncased (int8)",
        runtime = NlpModelRuntime.ONNX,
        sizeBytes = 67_895_296L,
        minRamMb = 512,
    )

    val QWEN25_05B = NlpModel(
        id = "llm-qwen25-05b",
        name = "Qwen2.5-0.5B-Instruct",
        runtime = NlpModelRuntime.LITERT,
        sizeBytes = 524_288_000L,
        minRamMb = 1536,
    )

    val SMOLLM2_360M = NlpModel(
        id = "llm-smollm2-360m",
        name = "SmolLM2-360M-Instruct",
        runtime = NlpModelRuntime.LITERT,
        sizeBytes = 377_487_360L,
        minRamMb = 1024,
    )

    val GEMMA3_270M = NlpModel(
        id = "llm-gemma3-270m",
        name = "Gemma3-270M-IT",
        runtime = NlpModelRuntime.LITERT,
        sizeBytes = 283_115_520L,
        minRamMb = 1024,
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
     */
    fun select(device: DeviceCapabilities): ProfileTier {
        val candidates = listOf(
            ProfileTier.ADVANCED,
            ProfileTier.PRECISE,
            ProfileTier.BALANCED,
        )
        for (profile in candidates) {
            if (!NlpModelCatalog.isRunnableInThisBuild(profile)) continue
            if (availability(profile, device, emptySet()) is ProfileAvailability.Ready) return profile
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
