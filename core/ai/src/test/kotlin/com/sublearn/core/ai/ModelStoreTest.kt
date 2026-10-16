package com.sublearn.core.ai

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Installation state is read from the filesystem, so the tests use a real temporary directory:
 * "installed" means the files are actually there, not that a flag says so.
 */
class ModelStoreTest {

    private lateinit var root: File
    private lateinit var store: ModelStore

    @Before
    fun setUp() {
        root = File.createTempFile("sublearn-store", "").let { file ->
            file.delete()
            File(file, "models").also { it.mkdirs() }
        }
        store = ModelStore(root)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun write(modelId: String, name: String, content: String = "data") {
        val layout = store.layoutFor(modelId)!!
        val file = layout.fileUnder(root, name)
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    @Test
    fun `every catalog model has a layout`() {
        for (model in NlpModelCatalog.all) {
            val layout = store.layoutFor(model.id)
            assertTrue("no layout for ${model.id}", layout != null)
            assertTrue("layout ${model.id} has no required files", layout!!.requiredFiles.isNotEmpty())
        }
    }

    @Test
    fun `layout ids match the catalog ids exactly`() {
        assertEquals(NlpModelCatalog.all.map { it.id }.toSet(), ModelStore.LAYOUTS.keys)
    }

    @Test
    fun `a model with none of its files is not installed`() {
        assertFalse(store.isInstalled("emb-minilm-l6-int8"))
        assertTrue(store.installedIds().isEmpty())
    }

    @Test
    fun `a model with a partial file set is not installed`() {
        write("emb-minilm-l6-int8", "model_quantized.onnx")
        assertFalse("missing vocab.txt must count as not installed", store.isInstalled("emb-minilm-l6-int8"))
    }

    @Test
    fun `a model with every file is installed`() {
        write("emb-minilm-l6-int8", "model_quantized.onnx")
        write("emb-minilm-l6-int8", "vocab.txt", "[PAD]\n[UNK]\n[CLS]\n[SEP]\n")

        assertTrue(store.isInstalled("emb-minilm-l6-int8"))
        assertEquals(setOf("emb-minilm-l6-int8"), store.installedIds())
    }

    @Test
    fun `an empty file does not count as installed`() {
        write("emb-minilm-l6-int8", "model_quantized.onnx", "")
        write("emb-minilm-l6-int8", "vocab.txt", "[CLS]\n")
        assertFalse("a zero-length file is a failed download", store.isInstalled("emb-minilm-l6-int8"))
    }

    @Test
    fun `the session and vocabulary files resolve only when installed`() {
        assertNull(store.sessionFileFor("emb-minilm-l6-int8"))
        assertNull(store.vocabularyFileFor("emb-minilm-l6-int8"))

        write("emb-minilm-l6-int8", "model_quantized.onnx")
        write("emb-minilm-l6-int8", "vocab.txt", "[CLS]\n")

        assertEquals("model_quantized.onnx", store.sessionFileFor("emb-minilm-l6-int8")?.name)
        assertEquals("vocab.txt", store.vocabularyFileFor("emb-minilm-l6-int8")?.name)
    }

    @Test
    fun `the LLM bundles have no vocabulary file of their own`() {
        write("llm-qwen25-05b", "qwen2.5-0.5b-instruct.task", "bundle")
        assertTrue(store.isInstalled("llm-qwen25-05b"))
        assertEquals("qwen2.5-0.5b-instruct.task", store.sessionFileFor("llm-qwen25-05b")?.name)
        assertNull("a .task bundle carries its own tokenizer", store.vocabularyFileFor("llm-qwen25-05b"))
    }

    @Test
    fun `an unknown model id has no layout and is never installed`() {
        assertNull(store.layoutFor("does-not-exist"))
        assertFalse(store.isInstalled("does-not-exist"))
        assertFalse(store.delete("does-not-exist"))
    }

    @Test
    fun `deleting removes the directory and the installed state`() {
        write("wsd-distilbert-int8", "model_quantized.onnx")
        write("wsd-distilbert-int8", "vocab.txt", "[CLS]\n")
        assertTrue(store.isInstalled("wsd-distilbert-int8"))

        assertTrue(store.delete("wsd-distilbert-int8"))
        assertFalse(store.isInstalled("wsd-distilbert-int8"))
        assertFalse(store.layoutFor("wsd-distilbert-int8")!!.directoryUnder(root).exists())
    }

    @Test
    fun `installed ids lists only the complete models`() {
        write("emb-minilm-l6-int8", "model_quantized.onnx")
        write("emb-minilm-l6-int8", "vocab.txt", "[CLS]\n")
        write("wsd-distilbert-int8", "model_quantized.onnx")  // missing vocab.txt

        assertEquals(setOf("emb-minilm-l6-int8"), store.installedIds())
    }

    @Test
    fun `the store creates its root when it is missing`() {
        val fresh = File(root, "deep/nested/models")
        assertFalse(fresh.exists())
        ModelStore(fresh)
        assertTrue(fresh.isDirectory)
    }

    @Test
    fun `installed ids can be limited to a subset of models`() {
        write("emb-minilm-l6-int8", "model_quantized.onnx")
        write("emb-minilm-l6-int8", "vocab.txt", "[CLS]\n")

        val subset = listOf(NlpModelCatalog.POTION_BASE_8M)
        assertTrue(store.installedIds(subset).isEmpty())
    }

    @Test
    fun `staging directory is created on demand`() {
        val staging = store.stagingDirectoryFor("emb-minilm-l6-int8")
        assertTrue(staging.isDirectory)
        assertEquals("minilm-l6-int8", staging.name)
    }
}
