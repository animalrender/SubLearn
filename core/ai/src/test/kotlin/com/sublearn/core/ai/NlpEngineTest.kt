package com.sublearn.core.ai

import com.sublearn.core.common.AppResult
import com.sublearn.core.common.SubLearnError
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * `ProfiledNlpEngine` decides what runs on a device, so it is tested against every combination that
 * can actually happen: a model that is not installed, an engine that is not linked, and a model
 * that is present but will not open.
 *
 * The loaders are fakes, which is the point of the loader interfaces — the ONNX and LiteRT engines
 * need a device, while the selection logic needs none.
 */
class NlpEngineTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        NlpRuntimeRegistry.reset()
        root = File.createTempFile("sublearn-engine", "").let { file ->
            file.delete()
            File(file, "models").also { it.mkdirs() }
        }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
        NlpRuntimeRegistry.reset()
    }

    /** A marker embedder; [dims] makes it identifiable in the diagnostics. */
    private class FakeEmbedder(override val dimensions: Int, val name: String) : TextEmbedder {
        override val runtimeName: String get() = name
        var closed = false
            private set

        override fun embed(text: String): FloatArray = FloatArray(dimensions)

        override fun close() {
            closed = true
        }
    }

    private class FakeLlm(override val modelId: String) : LlmRunner {
        override val runtimeName: String get() = "fake-llm"
        var closed = false
            private set

        override fun generate(prompt: String): AppResult<String> = AppResult.Success("1")

        override fun choose(prompt: String, options: List<String>): AppResult<Int?> = AppResult.Success(0)

        override fun close() {
            closed = true
        }
    }

    /** Installs a model by writing every file its layout lists, so `ModelStore` agrees it is there. */
    private fun install(modelId: String): Set<String> {
        val layout = ModelStore.LAYOUTS.getValue(modelId)
        for (name in layout.requiredFiles) {
            val file = layout.fileUnder(root, name)
            file.parentFile?.mkdirs()
            file.writeText("x")
        }
        return setOf(modelId)
    }

    private fun loaderThatOpens(): Pair<EmbedderLoader, MutableList<String>> {
        val loaded = mutableListOf<String>()
        return EmbedderLoader { model, _, _ ->
            loaded += model.id
            AppResult.Success(FakeEmbedder(dimensions = 384, name = model.runtime.name.lowercase()))
        } to loaded
    }

    @Test
    fun `a profile with no models opens nothing and still meets its promise`() {
        val engine = ProfiledNlpEngine(root, ProfileTier.BASIC, emptySet())

        val report = engine.load().getOrThrow()

        assertTrue("BASIC needs no model at all", report.meetsProfile)
        assertTrue(report.isEmpty)
        engine.close()
    }

    @Test
    fun `balanced runs the pure-Kotlin model without any loader`() {
        val installed = install("emb-potion-base-8m")
        // The file is one byte, so the real loader fails on the header: this asserts the routing to
        // the pure-Kotlin path, and that a failed open is reported rather than hidden.
        val engine = ProfiledNlpEngine(root, ProfileTier.BALANCED, installed)

        val report = engine.load().getOrThrow()

        assertTrue(report.diagnostics.any { it.startsWith("emb-potion-base-8m:") })
        assertFalse("a one-byte safetensors file cannot load", report.meetsProfile)
        engine.close()
    }

    @Test
    fun `precise loads the onnx model through the supplied loader`() {
        NlpRuntimeRegistry.register(NlpModelRuntime.ONNX)
        val installed = install("emb-minilm-l6-int8")
        val (loader, loaded) = loaderThatOpens()
        val engine = ProfiledNlpEngine(root, ProfileTier.PRECISE, installed, embedderLoader = loader)

        val report = engine.load().getOrThrow()

        assertEquals(listOf("emb-minilm-l6-int8"), loaded)
        assertTrue(report.meetsProfile)
        assertEquals(384, report.embedder?.dimensions)
        assertEquals(listOf("onnx"), report.runtimeNames)
        engine.close()
    }

    @Test
    fun `an engine that is not linked leaves the profile unmet without even calling the loader`() {
        val installed = install("emb-minilm-l6-int8")
        val (loader, loaded) = loaderThatOpens()
        // ONNX is not registered, so the loader must never be asked: `NlpRuntimeBootstrap` decided
        // this device cannot run it.
        val engine = ProfiledNlpEngine(root, ProfileTier.PRECISE, installed, embedderLoader = loader)

        val report = engine.load().getOrThrow()

        assertTrue(loaded.isEmpty())
        assertFalse(report.meetsProfile)
        assertTrue(report.isEmpty)
        assertTrue(
            "the diagnostics must name the missing engine",
            report.diagnostics.any { it.contains("ONNX") },
        )
        engine.close()
    }

    @Test
    fun `a model that is installed but will not open does not satisfy the profile`() {
        NlpRuntimeRegistry.register(NlpModelRuntime.ONNX)
        val installed = install("emb-minilm-l6-int8")
        val failing = EmbedderLoader { _, _, _ ->
            AppResult.Failure(SubLearnError(SubLearnError.Kind.ModelFailure, "corrupt weights"))
        }
        val engine = ProfiledNlpEngine(root, ProfileTier.PRECISE, installed, embedderLoader = failing)

        val report = engine.load().getOrThrow()

        assertFalse(report.meetsProfile)
        assertTrue(report.diagnostics.any { it.contains("corrupt weights") })
        engine.close()
    }

    @Test
    fun `smart loads the encoder and the prompt model together`() {
        NlpRuntimeRegistry.register(NlpModelRuntime.ONNX, NlpModelRuntime.LITERT)
        val installed = install("wsd-distilbert-int8") + install("llm-qwen25-05b")
        val (embedder, _) = loaderThatOpens()
        val llmLoader = LlmLoader { model, _, _ -> AppResult.Success(FakeLlm(model.id)) }
        val engine = ProfiledNlpEngine(
            root,
            ProfileTier.SMART,
            installed,
            embedderLoader = embedder,
            llmLoader = llmLoader,
        )

        val report = engine.load().getOrThrow()

        assertTrue(report.meetsProfile)
        assertEquals("onnx", report.embedder?.runtimeName)
        assertEquals("llm-qwen25-05b", report.llm?.modelId)
        assertEquals(listOf("fake-llm", "onnx"), report.runtimeNames.sorted())
        assertTrue(
            "the encoder that opened must be the word-sense model, not another one",
            report.diagnostics.any { it.startsWith("wsd-distilbert-int8: loaded") },
        )
        engine.close()
    }

    @Test
    fun `smart falls back to the encoder when the prompt model is not installed`() {
        NlpRuntimeRegistry.register(NlpModelRuntime.ONNX, NlpModelRuntime.LITERT)
        val installed = install("wsd-distilbert-int8")
        val (embedder, _) = loaderThatOpens()
        val engine = ProfiledNlpEngine(
            root,
            ProfileTier.SMART,
            installed,
            embedderLoader = embedder,
            llmLoader = { _, _, _ -> AppResult.Success(FakeLlm("llm-qwen25-05b")) },
        )

        val report = engine.load().getOrThrow()

        assertFalse("SMART is a promise the device has to keep", report.meetsProfile)
        assertTrue("but meaning-aware ranking still works", report.embedder != null)
        assertNull(report.llm)
        assertTrue(report.diagnostics.any { it.contains("llm-qwen25-05b: not installed") })
        engine.close()
    }

    @Test
    fun `runtime models are a subset of what the profile downloads`() {
        for (tier in ProfileTier.entries) {
            val downloaded = NlpModelCatalog.modelsFor(tier).map { it.id }.toSet()
            val resident = NlpModelCatalog.runtimeModelsFor(tier).map { it.id }.toSet()
            assertTrue("$tier keeps $resident, which it does not download", downloaded.containsAll(resident))
        }
    }

    @Test
    fun `closing releases every model and is idempotent`() {
        NlpRuntimeRegistry.register(NlpModelRuntime.ONNX, NlpModelRuntime.LITERT)
        val installed = install("wsd-distilbert-int8") + install("llm-qwen25-05b")
        val embedder = FakeEmbedder(384, "onnx")
        val llm = FakeLlm("llm-qwen25-05b")
        val engine = ProfiledNlpEngine(
            root,
            ProfileTier.SMART,
            installed,
            embedderLoader = { _, _, _ -> AppResult.Success(embedder) },
            llmLoader = { _, _, _ -> AppResult.Success(llm) },
        )
        engine.load().getOrThrow()

        engine.close()
        engine.close()

        assertTrue(embedder.closed)
        assertTrue(llm.closed)
        assertNull(engine.embedder)
        assertNull(engine.llm)
    }

    @Test
    fun `loading a closed engine reports the mistake instead of throwing`() {
        val engine = ProfiledNlpEngine(root, ProfileTier.BALANCED, emptySet())
        engine.close()

        val result = engine.load()

        assertTrue(result is AppResult.Failure)
        assertEquals(SubLearnError.Kind.ModelFailure, result.errorOrNull()?.kind)
    }
}
