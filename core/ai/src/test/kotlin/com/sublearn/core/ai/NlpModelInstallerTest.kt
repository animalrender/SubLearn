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

/**
 * The installer's job is the part [ModelDownloaderTest] cannot cover: a model is several files, and
 * "installed" means every one of them arrived.
 *
 * The transfer itself is swapped for a function that writes a file of the right size, so the tests
 * exercise the ordering, the resume, the cleanup and the post-install check without a network.
 */
class NlpModelInstallerTest {

    private lateinit var root: File
    private lateinit var store: ModelStore
    private val requests = mutableListOf<String>()

    @Before
    fun setUp() {
        root = File.createTempFile("sublearn-installer", "").let { file ->
            file.delete()
            File(file, "models").also { it.mkdirs() }
        }
        store = ModelStore(root)
        requests.clear()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    /** A downloader that writes the staged file at the published size, as a real one would. */
    private fun writingDownloader(): (DownloadPlan, (DownloadProgress) -> Unit) -> AppResult<DownloadOutcome> =
        { plan, onProgress ->
            val asset = ModelStore.LAYOUTS.values
                .flatMap { it.assets }
                .first { it.url == plan.urls.first() }
            requests += asset.name
            val staged = File(plan.targetDirectory, plan.stagingFileName)
            staged.writeBytes(ByteArray(asset.sizeBytes.toInt().coerceAtMost(4096)))
            onProgress(DownloadProgress(asset.sizeBytes, asset.sizeBytes))
            AppResult.Success(DownloadOutcome(asset.sizeBytes, asset.sha256.orEmpty(), "test"))
        }

    private fun installerWith(
        downloader: (DownloadPlan, (DownloadProgress) -> Unit) -> AppResult<DownloadOutcome>,
    ) = NlpModelInstaller(store, downloader)

    @Test
    fun `installs every file of a multi-file model`() {
        val installer = installerWith(writingDownloader())

        val layout = installer.install("emb-minilm-l6-int8").getOrThrow()

        assertEquals(listOf("model_quantized.onnx", "vocab.txt"), requests)
        assertEquals(2, layout.requiredFiles.size)
        // The stand-ins are a few kilobytes rather than the published megabytes: what the gate
        // checks is that every file of the model is present and non-empty, which is also what makes
        // a hand-copied directory count as installed.
        assertTrue(installer.isInstalled("emb-minilm-l6-int8"))
        assertTrue(layout.fileUnder(root, "vocab.txt").isFile)
    }

    @Test
    fun `reports progress across the whole model, not per file`() {
        val installer = installerWith(writingDownloader())
        val seen = mutableListOf<NlpModelProgress>()

        installer.install("emb-minilm-l6-int8") { seen += it }

        assertTrue(seen.isNotEmpty())
        assertEquals(setOf(0, 1), seen.map { it.fileIndex }.toSet())
        assertTrue("progress must never run backwards", seen.zipWithNext().all { it.second.bytesDownloaded >= it.first.bytesDownloaded })
        assertEquals(seen.first().totalBytes, seen.last().totalBytes)
        assertEquals(2, seen.first().fileCount)
    }

    @Test
    fun `a file that is already the right size is not downloaded again`() {
        val layout = ModelStore.LAYOUTS.getValue("emb-minilm-l6-int8")
        // The graph is exactly the published size, so a resume must skip it and fetch only the rest.
        val graph = layout.fileUnder(root, "model_quantized.onnx")
        graph.parentFile?.mkdirs()
        // A sparse file of the published size: the installer only compares lengths, and writing
        // 22 MB of zeros into a test would be pointless.
        java.io.RandomAccessFile(graph, "rw").use { it.setLength(layout.assets.first().sizeBytes) }
        assertEquals(layout.assets.first().sizeBytes, graph.length())

        val installer = installerWith(writingDownloader())
        installer.install("emb-minilm-l6-int8")

        assertEquals(listOf("vocab.txt"), requests)
    }

    @Test
    fun `a failed file leaves nothing behind`() {
        val failing: (DownloadPlan, (DownloadProgress) -> Unit) -> AppResult<DownloadOutcome> =
            { _, _ -> AppResult.Failure(SubLearnError(SubLearnError.Kind.NetworkUnavailable, "no route")) }
        val installer = installerWith(failing)

        val result = installer.install("wsd-distilbert-int8")

        assertTrue(result is AppResult.Failure)
        assertEquals("no route", result.errorOrNull()?.message)
        assertFalse(installer.isInstalled("wsd-distilbert-int8"))
        assertTrue(
            "a half-downloaded model must not stay on disk",
            !ModelStore.LAYOUTS.getValue("wsd-distilbert-int8").directoryUnder(root).exists(),
        )
    }

    @Test
    fun `a model under a licence gate is refused with the publisher named`() {
        val installer = installerWith(writingDownloader())

        val result = installer.install("llm-gemma3-270m")

        assertTrue(result is AppResult.Failure)
        assertTrue(result.errorOrNull()!!.message.contains("huggingface.co"))
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `an unknown model id is refused rather than crashing`() {
        val installer = installerWith(writingDownloader())

        val result = installer.install("no-such-model")

        assertTrue(result is AppResult.Failure)
        assertEquals(SubLearnError.Kind.MalformedInput, result.errorOrNull()?.kind)
    }

    @Test
    fun `an already installed model is returned without touching the network`() {
        val layout = ModelStore.LAYOUTS.getValue("emb-potion-base-8m")
        for (asset in layout.assets) {
            val file = layout.fileUnder(root, asset.name)
            file.parentFile?.mkdirs()
            file.writeText("x")
        }
        val installer = installerWith(writingDownloader())

        val result = installer.install("emb-potion-base-8m")

        assertTrue(result is AppResult.Success)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `deleting frees the model and clears the installed state`() {
        val layout = ModelStore.LAYOUTS.getValue("emb-minilm-l6-int8")
        for (asset in layout.assets) {
            val file = layout.fileUnder(root, asset.name)
            file.parentFile?.mkdirs()
            file.writeText("x")
        }
        val installer = installerWith(writingDownloader())
        assertTrue(installer.installedBytes("emb-minilm-l6-int8") > 0)

        assertTrue(installer.delete("emb-minilm-l6-int8"))

        assertFalse(installer.isInstalled("emb-minilm-l6-int8"))
        assertEquals(0, installer.installedBytes("emb-minilm-l6-int8"))
        assertEquals(setOf<String>(), installer.installedIds())
    }

    @Test
    fun `the installer offers every catalog model`() {
        val installer = installerWith(writingDownloader())

        assertEquals(NlpModelCatalog.all.map { it.id }, installer.availableModels().map { it.id })
    }
}
