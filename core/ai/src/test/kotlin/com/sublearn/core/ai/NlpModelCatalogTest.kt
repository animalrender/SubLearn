package com.sublearn.core.ai

import com.sublearn.core.common.AppResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The catalog is what the settings screen promises about downloads, so the sizes and runtimes
 * are pinned here against the values in `docs/catalog/catalog-v2.json` (§5.1, §5.3).
 */
class NlpModelCatalogTest {

    @Test
    fun `model ids match the shipped catalog`() {
        assertEquals(
            listOf(
                "emb-potion-base-8m",
                "emb-minilm-l6-int8",
                "wsd-distilbert-int8",
                "llm-qwen25-05b",
                "llm-smollm2-360m",
                "llm-gemma3-270m",
            ),
            NlpModelCatalog.all.map { it.id },
        )
    }

    @Test
    fun `sizes match the catalog file`() {
        assertEquals(30_212_096L, NlpModelCatalog.POTION_BASE_8M.sizeBytes)
        assertEquals(23_068_672L, NlpModelCatalog.MINILM_L6_INT8.sizeBytes)
        assertEquals(67_895_296L, NlpModelCatalog.DISTILBERT_INT8.sizeBytes)
        assertEquals(524_288_000L, NlpModelCatalog.QWEN25_05B.sizeBytes)
        assertEquals(377_487_360L, NlpModelCatalog.SMOLLM2_360M.sizeBytes)
        assertEquals(283_115_520L, NlpModelCatalog.GEMMA3_270M.sizeBytes)
    }

    @Test
    fun `runtimes match the catalog file`() {
        assertEquals(NlpModelRuntime.NONE, NlpModelCatalog.POTION_BASE_8M.runtime)
        assertEquals(NlpModelRuntime.ONNX, NlpModelCatalog.MINILM_L6_INT8.runtime)
        assertEquals(NlpModelRuntime.ONNX, NlpModelCatalog.DISTILBERT_INT8.runtime)
        assertEquals(NlpModelRuntime.LITERT, NlpModelCatalog.QWEN25_05B.runtime)
        assertEquals(NlpModelRuntime.LITERT, NlpModelCatalog.SMOLLM2_360M.runtime)
        assertEquals(NlpModelRuntime.LITERT, NlpModelCatalog.GEMMA3_270M.runtime)
    }

    @Test
    fun `only the pure Kotlin runtime exists in this build`() {
        assertTrue(NlpModelRuntime.NONE.isAvailable)
        assertFalse(NlpModelRuntime.ONNX.isAvailable)
        assertFalse(NlpModelRuntime.LITERT.isAvailable)
    }

    @Test
    fun `profiles accumulate models`() {
        assertTrue(NlpModelCatalog.modelsFor(ProfileTier.BASIC).isEmpty())
        assertTrue(NlpModelCatalog.modelsFor(ProfileTier.LIGHT).isEmpty())
        assertEquals(listOf("emb-potion-base-8m"), NlpModelCatalog.modelsFor(ProfileTier.BALANCED).map { it.id })
        assertEquals(
            listOf("emb-potion-base-8m", "emb-minilm-l6-int8"),
            NlpModelCatalog.modelsFor(ProfileTier.PRECISE).map { it.id },
        )
        assertEquals(3, NlpModelCatalog.modelsFor(ProfileTier.ADVANCED).size)
        assertEquals(4, NlpModelCatalog.modelsFor(ProfileTier.SMART).size)
    }

    @Test
    fun `download totals add up`() {
        assertEquals(0L, NlpModelCatalog.downloadBytesFor(ProfileTier.BASIC))
        assertEquals(30_212_096L, NlpModelCatalog.downloadBytesFor(ProfileTier.BALANCED))
        assertEquals(53_280_768L, NlpModelCatalog.downloadBytesFor(ProfileTier.PRECISE))
        assertEquals(121_176_064L, NlpModelCatalog.downloadBytesFor(ProfileTier.ADVANCED))
        assertEquals(645_464_064L, NlpModelCatalog.downloadBytesFor(ProfileTier.SMART))
    }

    @Test
    fun `profiles up to balanced are runnable in this build`() {
        // BASIC and LIGHT add nothing; BALANCED adds only the pure-Kotlin potion model.
        assertTrue(NlpModelCatalog.isRunnableInThisBuild(ProfileTier.BASIC))
        assertTrue(NlpModelCatalog.isRunnableInThisBuild(ProfileTier.LIGHT))
        assertTrue(NlpModelCatalog.isRunnableInThisBuild(ProfileTier.BALANCED))
        // PRECISE and above need ONNX, ADVANCED/SMART additionally need LiteRT.
        assertFalse(NlpModelCatalog.isRunnableInThisBuild(ProfileTier.PRECISE))
        assertFalse(NlpModelCatalog.isRunnableInThisBuild(ProfileTier.ADVANCED))
        assertFalse(NlpModelCatalog.isRunnableInThisBuild(ProfileTier.SMART))
    }

    @Test
    fun `token names map to tiers`() {
        assertEquals(ProfileTier.BASIC, ProfileTier.fromToken("BASIC"))
        assertEquals(ProfileTier.SMART, ProfileTier.fromToken("SMART_EXPERIMENTAL"))
        assertEquals(ProfileTier.CUSTOM, ProfileTier.fromToken("SOMETHING_ELSE"))
    }
}

class SmartProfileSelectorTest {

    private val modestPhone = DeviceCapabilities(
        totalRamMb = 2_048,
        cpuCores = 4,
        batterySaver = false,
        freeStorageMb = 4_096,
    )

    private val largePhone = DeviceCapabilities(
        totalRamMb = 12_288,
        cpuCores = 8,
        batterySaver = false,
        freeStorageMb = 64_000,
    )

    private val tinyPhone = DeviceCapabilities(
        totalRamMb = 1_024,
        cpuCores = 2,
        batterySaver = false,
        freeStorageMb = 1_000,
    )

    @Test
    fun `selection never returns a profile whose runtime is missing`() {
        assertEquals(ProfileTier.LIGHT, SmartProfileSelector.select(largePhone))
        assertEquals(ProfileTier.LIGHT, SmartProfileSelector.select(modestPhone))
    }

    @Test
    fun `weak devices fall back to base`() {
        assertEquals(ProfileTier.BASIC, SmartProfileSelector.select(tinyPhone))
    }

    @Test
    fun `availability reports a missing runtime before anything else`() {
        val installed = setOf("emb-potion-base-8m")
        assertEquals(
            ProfileAvailability.Ready,
            SmartProfileSelector.availability(ProfileTier.BALANCED, largePhone, installed),
        )
        assertEquals(
            ProfileAvailability.RuntimeMissing(NlpModelRuntime.ONNX),
            SmartProfileSelector.availability(ProfileTier.PRECISE, largePhone, installed),
        )
    }

    @Test
    fun `base and light need neither a model nor extra RAM`() {
        assertEquals(
            ProfileAvailability.Ready,
            SmartProfileSelector.availability(ProfileTier.BASIC, tinyPhone, emptySet()),
        )
        assertEquals(
            ProfileAvailability.Ready,
            SmartProfileSelector.availability(ProfileTier.LIGHT, tinyPhone, emptySet()),
        )
    }

    @Test
    fun `availability asks for a download when the model is absent`() {
        val device = DeviceCapabilities(
            totalRamMb = 4_096,
            cpuCores = 8,
            batterySaver = false,
            freeStorageMb = 4_096,
        )
        val result = SmartProfileSelector.availability(ProfileTier.BALANCED, device, emptySet())
        assertEquals(ProfileAvailability.NeedsModel("emb-potion-base-8m"), result)
    }

    @Test
    fun `availability is ready once every model is installed`() {
        val device = DeviceCapabilities(
            totalRamMb = 4_096,
            cpuCores = 8,
            batterySaver = false,
            freeStorageMb = 4_096,
        )
        val installed = setOf("emb-potion-base-8m")
        assertEquals(ProfileAvailability.Ready, SmartProfileSelector.availability(ProfileTier.BALANCED, device, installed))
    }

    @Test
    fun `availability reports insufficient storage`() {
        val cramped = DeviceCapabilities(
            totalRamMb = 4_096,
            cpuCores = 8,
            batterySaver = false,
            freeStorageMb = 10,
        )
        assertEquals(
            ProfileAvailability.NeedsStorage(28L),
            SmartProfileSelector.availability(ProfileTier.BALANCED, cramped, emptySet()),
        )
    }

    @Test
    fun `runtime guard passes the pure Kotlin profiles`() {
        assertTrue(NlpModelRuntimeGuard.check(ProfileTier.BASIC) is AppResult.Success<*>)
        assertTrue(NlpModelRuntimeGuard.check(ProfileTier.LIGHT) is AppResult.Success<*>)
    }

    @Test
    fun `runtime guard refuses the model backed profiles with a clear reason`() {
        val result = NlpModelRuntimeGuard.check(ProfileTier.PRECISE)
        assertTrue(result is AppResult.Failure)
        val error = (result as AppResult.Failure).error
        assertEquals(com.sublearn.core.common.SubLearnError.Kind.NotImplemented, error.kind)
        assertTrue(error.message.contains("ONNX"))
    }
}