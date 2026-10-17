package com.sublearn.core.ai

import com.sublearn.core.common.AppResult
import com.sublearn.core.common.SubLearnError
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

/**
 * The self-test is what the "Test" button runs (D-86), so every verdict it can give is pinned
 * here: not installed, truncated, loader failed, embedder unusable, embedder fine, LLM fine.
 *
 * The loaders are fakes; the real ones need a device. What is under test is the verdict logic and
 * that the probe closes what it opened.
 */
class NlpModelSelfTestTest {

    private lateinit var root: File
    private lateinit var store: ModelStore

    @Before
    fun setUp() {
        NlpRuntimeRegistry.reset()
        root = File.createTempFile("sublearn-selftest", "").let { file ->
            file.delete()
            File(file, "models").also { it.mkdirs() }
        }
        store = ModelStore(root)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
        NlpRuntimeRegistry.reset()
    }

    /** Writes every file of a layout at its published size, so the store and the size check agree. */
    private fun installAtPublishedSize(modelId: String) {
        val layout = ModelStore.LAYOUTS.getValue(modelId)
        for (asset in layout.assets) {
            val file = layout.fileUnder(root, asset.name)
            file.parentFile?.mkdirs()
            RandomAccessFile(file, "rw").use { it.setLength(asset.sizeBytes.coerceAtLeast(1L)) }
        }
    }

    private class FakeEmbedder(private val vector: (String) -> FloatArray, override val dimensions: Int) : TextEmbedder {
        override val runtimeName: String get() = "fake-onnx"
        var closed = false
            private set

        override fun embed(text: String): FloatArray = vector(text)

        override fun close() {
            closed = true
        }
    }

    private class FakeLlm(private val answer: Int?) : LlmRunner {
        override val runtimeName: String get() = "fake-litert"
        override val modelId: String get() = "llm-smollm2-360m"
        var closed = false
            private set

        override fun generate(prompt: String): AppResult<String> = AppResult.Success(answer?.toString().orEmpty())

        override fun choose(prompt: String, options: List<String>): AppResult<Int?> = AppResult.Success(answer)

        override fun close() {
            closed = true
        }
    }

    @Test
    fun `a model that is not installed fails without touching a loader`() {
        var loads = 0
        val test = NlpModelSelfTest(store, embedderLoader = { _, _, _ -> loads++; error("must not load") })

        val report = test.run("emb-minilm-l6-int8")

        assertFalse(report.ok)
        assertTrue(report.detail, report.detail.contains("not installed"))
        assertEquals(0, loads)
    }

    @Test
    fun `a file shorter than the publisher lists fails before loading`() {
        NlpRuntimeRegistry.register(NlpModelRuntime.ONNX)
        val layout = ModelStore.LAYOUTS.getValue("emb-minilm-l6-int8")
        for (name in layout.requiredFiles) {
            layout.fileUnder(root, name).apply { parentFile?.mkdirs() }.writeText("x")
        }
        var loads = 0
        val test = NlpModelSelfTest(store, embedderLoader = { _, _, _ -> loads++; error("must not load") })

        val report = test.run("emb-minilm-l6-int8")

        assertFalse(report.ok)
        assertTrue(report.detail, report.detail.contains("bytes"))
        assertEquals(0, loads)
    }

    @Test
    fun `an engine missing from the build is reported as such`() {
        installAtPublishedSize("emb-minilm-l6-int8")
        val test = NlpModelSelfTest(store, embedderLoader = { _, _, _ -> error("must not load") })

        val report = test.run("emb-minilm-l6-int8")

        assertFalse(report.ok)
        assertTrue(report.detail, report.detail.contains("ONNX"))
    }

    @Test
    fun `a loader failure is the verdict`() {
        NlpRuntimeRegistry.register(NlpModelRuntime.ONNX)
        installAtPublishedSize("emb-minilm-l6-int8")
        val test = NlpModelSelfTest(
            store,
            embedderLoader = { _, _, _ ->
                AppResult.failure(SubLearnError(SubLearnError.Kind.Unknown, "libonnxruntime.so not found"))
            },
        )

        val report = test.run("emb-minilm-l6-int8")

        assertFalse(report.ok)
        assertTrue(report.detail, report.detail.contains("libonnxruntime.so"))
    }

    @Test
    fun `a working encoder passes and is closed`() {
        NlpRuntimeRegistry.register(NlpModelRuntime.ONNX)
        installAtPublishedSize("emb-minilm-l6-int8")
        val embedder = FakeEmbedder(dimensions = 4, vector = { text -> FloatArray(4) { i -> (text.length % (i + 2)).toFloat() + 1f } })
        val test = NlpModelSelfTest(store, embedderLoader = { _, _, _ -> AppResult.Success(embedder) })

        val report = test.run("emb-minilm-l6-int8")

        assertTrue(report.detail, report.ok)
        assertEquals("fake-onnx", report.runtimeName)
        assertTrue(report.detail, report.detail.contains("4 dims"))
        assertTrue("the probe must release the model it opened", embedder.closed)
    }

    @Test
    fun `an encoder that returns zeros fails`() {
        NlpRuntimeRegistry.register(NlpModelRuntime.ONNX)
        installAtPublishedSize("emb-minilm-l6-int8")
        val embedder = FakeEmbedder(dimensions = 4, vector = { FloatArray(4) })
        val test = NlpModelSelfTest(store, embedderLoader = { _, _, _ -> AppResult.Success(embedder) })

        val report = test.run("emb-minilm-l6-int8")

        assertFalse(report.ok)
        assertTrue(report.detail, report.detail.contains("zero"))
        assertTrue(embedder.closed)
    }

    @Test
    fun `a prompt model that picks an option passes`() {
        NlpRuntimeRegistry.register(NlpModelRuntime.LITERT)
        installAtPublishedSize("llm-smollm2-360m")
        val llm = FakeLlm(answer = NlpModelSelfTest.LLM_EXPECTED)
        val test = NlpModelSelfTest(store, llmLoader = { _, _, _ -> AppResult.Success(llm) })

        val report = test.run("llm-smollm2-360m")

        assertTrue(report.detail, report.ok)
        assertEquals("fake-litert", report.runtimeName)
        assertTrue(report.detail, report.detail.contains("correct"))
        assertTrue(llm.closed)
    }

    @Test
    fun `a prompt model that matches no option fails`() {
        NlpRuntimeRegistry.register(NlpModelRuntime.LITERT)
        installAtPublishedSize("llm-smollm2-360m")
        val test = NlpModelSelfTest(store, llmLoader = { _, _, _ -> AppResult.Success(FakeLlm(answer = null)) })

        val report = test.run("llm-smollm2-360m")

        assertFalse(report.ok)
        assertTrue(report.detail, report.detail.contains("none of the options"))
    }
}
