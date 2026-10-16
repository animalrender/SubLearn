package com.sublearn.core.ai

import com.sublearn.core.common.AppResult
import com.sublearn.core.common.SubLearnError
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest

/**
 * Drives the real transfer path against a local HTTP server, so the failure modes that matter are
 * exercised here rather than discovered on a device: a wrong hash, a truncated body, a 404 and a
 * dead host, each of which must leave no partial file behind.
 */
class ModelDownloaderTest {

    private lateinit var server: HttpServer
    private var baseUrl: String = ""
    private lateinit var root: File

    @Before
    fun setUp() {
        root = File.createTempFile("sublearn-dl", "").let { file ->
            file.delete()
            File(file, "models").also { it.mkdirs() }
        }
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = null
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    @After
    fun tearDown() {
        server.stop(0)
        root.deleteRecursively()
    }

    private fun serve(path: String, body: ByteArray, status: Int = 200, declaredLength: Long? = null) {
        server.createContext(path) { exchange ->
            exchange.sendResponseHeaders(status, declaredLength ?: body.size.toLong())
            if (status == 200) {
                exchange.responseBody.use { it.write(body) }
            } else {
                exchange.responseBody.close()
            }
        }
    }

    private fun planFor(
        path: String,
        sha256: String = "",
        directory: File = File(root, "minilm-l6-int8"),
    ) = DownloadPlan(
        urls = listOf("$baseUrl$path"),
        expectedSha256 = sha256,
        targetDirectory = directory,
    )

    private fun sha256Of(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun `a good download writes the file and reports the digest`() {
        val payload = "model weights".toByteArray()
        serve("/model.onnx", payload)
        val expected = sha256Of(payload)

        val result = ModelDownloader().download(planFor("/model.onnx", expected))

        assertTrue(result is AppResult.Success)
        val outcome = (result as AppResult.Success).value
        assertEquals(payload.size.toLong(), outcome.bytesWritten)
        assertEquals(expected, outcome.sha256)
        assertEquals("127.0.0.1", outcome.host)

        val written = File(File(root, "minilm-l6-int8"), "download.part")
        assertTrue("the transferred bytes must be on disk", written.isFile)
        assertEquals(payload.size.toLong(), written.length())
    }

    @Test
    fun `progress reports the running total and the length`() {
        val payload = ByteArray(300_000) { (it % 251).toByte() }
        serve("/big.bin", payload)
        val seen = ArrayList<DownloadProgress>()

        val result = ModelDownloader(bufferSize = 64 * 1024).download(planFor("/big.bin")) { progress ->
            seen.add(progress)
        }

        assertTrue(result is AppResult.Success)
        assertTrue("progress must be reported at least once", seen.isNotEmpty())
        assertEquals(payload.size.toLong(), seen.last().bytesDownloaded)
        assertEquals(payload.size.toLong(), seen.last().totalBytes)
        assertEquals(1f, seen.last().fraction, 1e-6f)
        // Totals never go backwards.
        assertTrue(seen.zipWithNext().all { (a, b) -> b.bytesDownloaded >= a.bytesDownloaded })
    }

    @Test
    fun `a checksum mismatch is reported and the partial file is removed`() {
        val payload = "not what the catalog promised".toByteArray()
        serve("/model.onnx", payload)

        val result = ModelDownloader().download(planFor("/model.onnx", "00".repeat(32)))

        assertTrue(result is AppResult.Failure)
        assertEquals(SubLearnError.Kind.MalformedInput, (result as AppResult.Failure).error.kind)
        assertTrue(
            "a mismatched download must not be left on disk",
            !File(File(root, "minilm-l6-int8"), "download.part").exists(),
        )
    }

    @Test
    fun `a checksum mismatch does not retry the fallback host`() {
        // The bytes are wrong, not the host, so a planned second URL must not be tried.
        val payload = "bad".toByteArray()
        serve("/primary.bin", payload)
        serve("/fallback.bin", payload)
        var fallbackHits = 0
        server.createContext("/counted.bin") { exchange ->
            fallbackHits++
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }

        val plan = DownloadPlan(
            urls = listOf("$baseUrl/primary.bin", "$baseUrl/counted.bin"),
            expectedSha256 = "00".repeat(32),
            targetDirectory = File(root, "minilm-l6-int8"),
        )
        val result = ModelDownloader().download(plan)

        assertTrue(result is AppResult.Failure)
        assertEquals("the fallback host must not be contacted for a hash mismatch", 0, fallbackHits)
    }

    @Test
    fun `a truncated body is a failure and leaves nothing behind`() {
        val payload = "short body".toByteArray()
        // Claim far more bytes than are sent: the connection closes early.
        serve("/truncated.bin", payload, declaredLength = 10_000L)

        val result = ModelDownloader().download(planFor("/truncated.bin"))

        assertTrue(result is AppResult.Failure)
        assertTrue(
            "a truncated download must not be left on disk",
            !File(File(root, "minilm-l6-int8"), "download.part").exists(),
        )
    }

    @Test
    fun `an HTTP error is reported as a network failure`() {
        serve("/missing.bin", ByteArray(0), status = 404)

        val result = ModelDownloader().download(planFor("/missing.bin"))

        assertTrue(result is AppResult.Failure)
        assertEquals(SubLearnError.Kind.NetworkUnavailable, (result as AppResult.Failure).error.kind)
        assertTrue(result.error.message.contains("404"))
    }

    @Test
    fun `a dead host is a failure rather than an exception`() {
        val plan = DownloadPlan(
            urls = listOf("http://127.0.0.1:1/nowhere.bin"),
            expectedSha256 = "",
            targetDirectory = File(root, "minilm-l6-int8"),
        )
        val result = ModelDownloader(connectTimeoutMs = 500).download(plan)

        assertTrue(result is AppResult.Failure)
    }

    @Test
    fun `the second host is used when the first fails`() {
        val payload = "from the fallback".toByteArray()
        serve("/dead.bin", ByteArray(0), status = 500)
        serve("/alive.bin", payload)

        val plan = DownloadPlan(
            urls = listOf("$baseUrl/dead.bin", "$baseUrl/alive.bin"),
            expectedSha256 = sha256Of(payload),
            targetDirectory = File(root, "minilm-l6-int8"),
        )
        val result = ModelDownloader().download(plan)

        assertTrue(result is AppResult.Success)
        assertEquals("127.0.0.1", (result as AppResult.Success).value.host)
    }

    @Test
    fun `a cancelled transfer deletes the partial file`() {
        val payload = ByteArray(500_000) { 7 }
        serve("/cancelled.bin", payload)

        var chunks = 0
        val downloader = ModelDownloader(bufferSize = 8 * 1024, onChunk = {
            if (++chunks > 2) throw InterruptedException("user cancelled")
        })
        val result = downloader.download(planFor("/cancelled.bin"))

        assertTrue(result is AppResult.Failure)
        assertTrue(
            "a cancelled download must not be left on disk",
            !File(File(root, "minilm-l6-int8"), "download.part").exists(),
        )
    }

    @Test
    fun `an empty URL list is a configuration failure`() {
        val result = ModelDownloader().download(
            DownloadPlan(urls = emptyList(), expectedSha256 = "", targetDirectory = File(root, "x")),
        )
        assertTrue(result is AppResult.Failure)
        assertEquals(SubLearnError.Kind.MalformedInput, (result as AppResult.Failure).error.kind)
    }

    @Test
    fun `an empty hash skips verification instead of failing`() {
        val payload = "unverified but present".toByteArray()
        serve("/nohash.bin", payload)

        val result = ModelDownloader().download(planFor("/nohash.bin"))

        assertTrue(result is AppResult.Success)
        val outcome = (result as AppResult.Success).value
        assertTrue("the digest is still reported for logging", outcome.sha256.isNotBlank())
    }

    @Test
    fun `file hashing matches the digest of the same bytes`() {
        val payload = "hash me".toByteArray()
        val file = File(root, "hash.bin")
        file.writeBytes(payload)
        assertEquals(sha256Of(payload), ModelDownloader.sha256(file))
    }

    @Test
    fun `progress fraction is zero when the server sends no length`() {
        assertEquals(0f, DownloadProgress(100, 0).fraction, 0f)
        assertEquals(0.5f, DownloadProgress(50, 100).fraction, 1e-6f)
    }

    @Test
    fun `the staging file name is honoured`() {
        val payload = "custom name".toByteArray()
        serve("/named.bin", payload)
        val plan = planFor("/named.bin").copy(stagingFileName = "ongoing.tmp")

        val result = ModelDownloader().download(plan)

        assertTrue(result is AppResult.Success)
        assertTrue(File(File(root, "minilm-l6-int8"), "ongoing.tmp").isFile)
        assertFalse(File(File(root, "minilm-l6-int8"), "download.part").exists())
    }
}
