package com.sublearn.core.translate

import com.sublearn.core.common.AppResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Unit tests for the pure ML Kit install logic (§3.1):
 * download URL scheme, SHA-256 verification, zip-slip protection, archive validation.
 */
class MlKitInstallLogicTest {

    private val meta = MlKitModelMetadata(
        pair = "en_fa",
        version = 5,
        revision = 29,
        downloadSize = 31_457_280L,
        installedSize = 42_000_000L,
        sha256 = "",
    )

    // --- URL construction ---

    @Test
    fun `primary URL uses dl google com`() {
        val url = MlKitInstallLogic.buildDownloadUrl(meta, useFallback = false)
        assertEquals("https://dl.google.com/translate/offline/v5/high/r29/en_fa.zip", url)
    }

    @Test
    fun `fallback URL uses redirector host`() {
        val url = MlKitInstallLogic.buildDownloadUrl(meta, useFallback = true)
        assertEquals("https://redirector.gvt1.com/edgedl/translate/offline/v5/high/r29/en_fa.zip", url)
    }

    @Test
    fun `URL reflects the metadata version and revision`() {
        val other = meta.copy(pair = "fa_en", version = 12, revision = 3)
        assertEquals(
            "https://dl.google.com/translate/offline/v12/high/r3/fa_en.zip",
            MlKitInstallLogic.buildDownloadUrl(other),
        )
    }

    // --- SHA-256 ---

    @Test
    fun `sha256 matches the known digest of hello world`() {
        val file = File.createTempFile("sha", ".bin")
        try {
            file.writeBytes("hello world".toByteArray())
            assertEquals(
                "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9",
                MlKitInstallLogic.sha256(file),
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun `sha256 of empty file is the empty digest`() {
        val file = File.createTempFile("sha-empty", ".bin")
        try {
            assertEquals(
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                MlKitInstallLogic.sha256(file),
            )
        } finally {
            file.delete()
        }
    }

    // --- Zip-slip ---

    @Test
    fun `unzipSafe rejects parent directory traversal`() {
        withZip("../../../etc/passwd" to "malicious") { zip, target ->
            val result = MlKitInstallLogic.unzipSafe(zip, target)
            assertTrue("traversal entry must be rejected", result is AppResult.Failure)
            assertFalse(
                "nothing may be written outside the target",
                File(target.parentFile, "etc/passwd").exists(),
            )
        }
    }

    @Test
    fun `unzipSafe rejects nested traversal that climbs out of the archive root`() {
        withZip("translate_enfa/../../escape.txt" to "malicious") { zip, target ->
            val result = MlKitInstallLogic.unzipSafe(zip, target)
            assertTrue("nested traversal entry must be rejected", result is AppResult.Failure)
            assertFalse(
                "nothing may be written above the target",
                File(target.parentFile, "escape.txt").exists(),
            )
        }
    }

    @Test
    fun `unzipSafe accepts nested entries`() {
        withZip(
            "translate_enfa/translation_model.pb" to "model data",
            "translate_faen/translation_model.pb" to "model data",
        ) { zip, target ->
            val result = MlKitInstallLogic.unzipSafe(zip, target)
            assertTrue("valid archive must extract", result is AppResult.Success)
            assertTrue(File(target, "translate_enfa/translation_model.pb").exists())
            assertTrue(File(target, "translate_faen/translation_model.pb").exists())
        }
    }

    // --- Structure validation ---

    @Test
    fun `validateStructure accepts translate_enfa layout`() {
        withTempDir { dir ->
            File(dir, "translate_enfa").mkdirs()
            File(dir, "translate_enfa/translation_model.pb").writeText("x")
            assertTrue(MlKitInstallLogic.validateStructure(dir, "en_fa") is AppResult.Success)
        }
    }

    @Test
    fun `validateStructure accepts translate_faen layout`() {
        withTempDir { dir ->
            File(dir, "translate_faen").mkdirs()
            File(dir, "translate_faen/translation_model.pb").writeText("x")
            assertTrue(MlKitInstallLogic.validateStructure(dir, "en_fa") is AppResult.Success)
        }
    }

    @Test
    fun `validateStructure rejects an archive with no model file`() {
        withTempDir { dir ->
            File(dir, "translate_enfa").mkdirs()
            File(dir, "translate_enfa/some_other.pb").writeText("x")
            assertTrue(MlKitInstallLogic.validateStructure(dir, "en_fa") is AppResult.Failure)
        }
    }

    @Test
    fun `validateStructure rejects a malformed pair name`() {
        withTempDir { dir ->
            assertTrue(MlKitInstallLogic.validateStructure(dir, "en") is AppResult.Failure)
            assertTrue(MlKitInstallLogic.validateStructure(dir, "en_fa_ar") is AppResult.Failure)
        }
    }

    // --- Metadata ---

    @Test
    fun `EN_FA_METADATA matches the documented model`() {
        val md = MlKitModelInstaller.EN_FA_METADATA
        assertEquals("en_fa", md.pair)
        assertEquals(5, md.version)
        assertEquals(29, md.revision)
        assertTrue("download size should be roughly 30 MB", md.downloadSize in 25_000_000L..40_000_000L)
    }

    // --- Helpers ---

    private fun withTempDir(block: (File) -> Unit) {
        val dir = File.createTempFile("sublearn-test", "").apply {
            delete()
            mkdirs()
        }
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun withZip(vararg entries: Pair<String, String>, block: (File, File) -> Unit) {
        withTempDir { target ->
            val zip = File.createTempFile("sublearn", ".zip")
            try {
                ZipOutputStream(zip.outputStream()).use { zos ->
                    for ((name, body) in entries) {
                        zos.putNextEntry(ZipEntry(name))
                        zos.write(body.toByteArray())
                        zos.closeEntry()
                    }
                }
                block(zip, target)
            } finally {
                zip.delete()
            }
        }
    }
}