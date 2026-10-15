package com.sublearn.core.translate

import android.content.Context
import com.sublearn.core.common.AppResult
import com.sublearn.core.common.SubLearnError
import com.sublearn.core.common.SubLearnLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Robust ML Kit translation model installer with HTTP download, SHA-256 verification,
 * zip-slip protection, and atomic installation.
 *
 * Reference: `melonityhub/dictionaryproject` `mlkit_models.py` and `translate_models_metadata.json`.
 *
 * @param context Android context for file paths
 * @param logger optional logger
 */
class MlKitModelInstaller(
    private val context: Context,
    private val logger: SubLearnLogger? = null,
) {
    /**
     * Metadata for a translation model pair.
     */
    data class ModelMetadata(
        val pair: String,       // e.g. "en_fa"
        val version: Int,       // V
        val revision: Int,      // RV
        val downloadSize: Long, // DL_SZ
        val installedSize: Long,// SZ
        val sha256: String,     // HASH
    )

    private val installBaseDir: File
        get() = File(context.noBackupFilesDir, "com.google.mlkit.translate.models")

    /**
     * Current state of a model download/install.
     */
    sealed class InstallState {
        object NotInstalled : InstallState()
        data class Downloading(val bytesDownloaded: Long, val totalBytes: Long) : InstallState()
        object Verifying : InstallState()
        object Installing : InstallState()
        object Installed : InstallState()
        data class Error(val message: String, val retryable: Boolean = true) : InstallState()
    }

    /**
     * Build the download URL for a model pair.
     * Primary: https://dl.google.com/translate/offline/v{V}/high/r{RV}/{pair}.zip
     * Fallback: https://redirector.gvt1.com/edgedl/translate/offline/v{V}/high/r{RV}/{pair}.zip
     */
    fun buildDownloadUrl(meta: ModelMetadata, useFallback: Boolean = false): String {
        val host = if (useFallback) "redirector.gvt1.com" else "dl.google.com"
        return "https://$host/translate/offline/v${meta.version}/high/r${meta.revision}/${meta.pair}.zip"
    }

    /**
     * Download and install a translation model pair.
     *
     * @param meta model metadata
     * @param onProgress progress callback
     * @return success or error
     */
    suspend fun install(
        meta: ModelMetadata,
        onProgress: (InstallState) -> Unit = {},
    ): AppResult<Unit> = withContext(Dispatchers.IO) {
        val pairDir = getInstallDir(meta.pair)
        val partFile = File(context.cacheDir, "mlkit_${meta.pair}.zip.part")

        try {
            // 1. Download with resume
            onProgress(InstallState.Downloading(0, meta.downloadSize))
            val downloadResult = downloadWithResume(meta, partFile, onProgress)
            if (downloadResult is AppResult.Failure) {
                return@withContext downloadResult
            }

            // 2. Verify SHA-256
            onProgress(InstallState.Verifying)
            val hash = sha256(partFile)
            if (hash.lowercase() != meta.sha256.lowercase()) {
                partFile.delete()
                return@withContext AppResult.failure(
                    SubLearnError(SubLearnError.Kind.Unknown, "SHA-256 mismatch: expected ${meta.sha256}, got $hash")
                )
            }

            // 3. Unzip with zip-slip protection
            onProgress(InstallState.Installing)
            val tempDir = File(context.cacheDir, "mlkit_unzip_${meta.pair}")
            tempDir.deleteRecursively()
            tempDir.mkdirs()
            val unzipResult = unzipSafe(partFile, tempDir)
            if (unzipResult is AppResult.Failure) {
                tempDir.deleteRecursively()
                return@withContext unzipResult
            }

            // 4. Validate structure
            val validationResult = validateStructure(tempDir, meta.pair)
            if (validationResult is AppResult.Failure) {
                tempDir.deleteRecursively()
                return@withContext validationResult
            }

            // 5. Atomic install: move to final location
            pairDir.deleteRecursively()
            if (!tempDir.renameTo(pairDir)) {
                // Fallback: copy and delete
                tempDir.copyRecursively(pairDir, overwrite = true)
                tempDir.deleteRecursively()
            }

            // 6. Cleanup
            partFile.delete()

            logger?.log(SubLearnLogger.Level.INFO, TAG, "Model ${meta.pair} installed to ${pairDir.absolutePath}")
            onProgress(InstallState.Installed)
            AppResult.success(Unit)
        } catch (cancelled: CancellationException) {
            partFile.delete()
            throw cancelled
        } catch (t: Throwable) {
            logger?.log(SubLearnLogger.Level.ERROR, TAG, "Install failed for ${meta.pair}: ${t.message}")
            AppResult.failure(SubLearnError.from(t))
        }
    }

    /**
     * Delete an installed model pair.
     */
    suspend fun delete(pair: String): AppResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val dir = getInstallDir(pair)
            if (dir.exists()) {
                dir.deleteRecursively()
                logger?.log(SubLearnLogger.Level.INFO, TAG, "Deleted model $pair")
            }
            AppResult.success(Unit)
        } catch (t: Throwable) {
            AppResult.failure(SubLearnError.from(t))
        }
    }

    /**
     * Check if a model pair is installed.
     */
    fun isInstalled(pair: String): Boolean {
        val dir = getInstallDir(pair)
        return dir.exists() && dir.isDirectory && dir.listFiles()?.isNotEmpty() == true
    }

    private fun getInstallDir(pair: String): File {
        // Same layout as offdic: noBackupFilesDir/com.google.mlkit.translate.models/<pair>/
        return File(context.noBackupFilesDir, "com.google.mlkit.translate.models/$pair")
    }

    private suspend fun downloadWithResume(
        meta: ModelMetadata,
        partFile: File,
        onProgress: (InstallState) -> Unit,
    ): AppResult<Unit> {
        var useFallback = false
        var attempts = 0
        val maxAttempts = 3

        while (attempts < maxAttempts) {
            attempts++
            try {
                val existingBytes = if (partFile.exists()) partFile.length() else 0L
                val downloadUrl = buildDownloadUrl(meta, useFallback)
                val conn = URL(downloadUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 30_000
                conn.readTimeout = 60_000
                if (existingBytes > 0) {
                    conn.setRequestProperty("Range", "bytes=$existingBytes-")
                }

                val code = conn.responseCode
                if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                    conn.disconnect()
                    if (!useFallback && attempts < maxAttempts) {
                        useFallback = true
                        continue
                    }
                    return AppResult.failure(
                        SubLearnError(SubLearnError.Kind.NetworkUnavailable, "HTTP $code from $downloadUrl")
                    )
                }

                val isResume = code == HttpURLConnection.HTTP_PARTIAL && existingBytes > 0
                val totalBytes = meta.downloadSize
                var downloaded = existingBytes

                val outputStream = if (isResume) {
                    java.io.RandomAccessFile(partFile, "rw").apply { seek(existingBytes) }
                } else {
                    null
                }
                try {
                    conn.inputStream.use { input ->
                        val sink = outputStream ?: partFile.outputStream()
                        sink.use { out ->
                            val buffer = ByteArray(8192)
                            var bytesRead: Int
                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                ensureActive()
                                out.write(buffer, 0, bytesRead)
                                downloaded += bytesRead
                                onProgress(InstallState.Downloading(downloaded, totalBytes))
                            }
                        }
                    }
                } finally {
                    outputStream?.close()
                }
                conn.disconnect()
                return AppResult.success(Unit)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                if (!useFallback && attempts < maxAttempts) {
                    useFallback = true
                    continue
                }
                if (attempts >= maxAttempts) {
                    return AppResult.failure(SubLearnError.from(t))
                }
            }
        }
        return AppResult.failure(SubLearnError(SubLearnError.Kind.Unknown, "Download failed after $maxAttempts attempts"))
    }

    private fun unzipSafe(zipFile: File, targetDir: File): AppResult<Unit> {
        try {
            ZipInputStream(zipFile.inputStream()).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    val outFile = File(targetDir, entry.name)
                    // Zip-slip protection
                    if (!outFile.canonicalPath.startsWith(targetDir.canonicalPath + File.separator)) {
                        return AppResult.failure(
                            SubLearnError(SubLearnError.Kind.Unknown, "Zip-slip detected: ${entry.name}")
                        )
                    }
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().use { out ->
                            zis.copyTo(out)
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            return AppResult.success(Unit)
        } catch (t: Throwable) {
            return AppResult.failure(SubLearnError.from(t))
        }
    }

    private fun validateStructure(dir: File, pair: String): AppResult<Unit> {
        // Expected: translate_enfa/translation_model.pb or translate_faen/...
        val parts = pair.split("_")
        if (parts.size != 2) {
            return AppResult.failure(SubLearnError(SubLearnError.Kind.Unknown, "Invalid pair format: $pair"))
        }
        val fwdDir = "translate_${parts[0]}${parts[1]}"
        val revDir = "translate_${parts[1]}${parts[0]}"

        val fwdModel = File(File(dir, fwdDir), "translation_model.pb")
        val revModel = File(File(dir, revDir), "translation_model.pb")

        if (!fwdModel.exists() && !revModel.exists()) {
            return AppResult.failure(
                SubLearnError(SubLearnError.Kind.Unknown, "Missing translation_model.pb in $fwdDir or $revDir")
            )
        }
        return AppResult.success(Unit)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { fis ->
            val buffer = ByteArray(8192)
            var read: Int
            while (fis.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "MlKitModelInstaller"

        /**
         * Known model metadata for EN↔FA translation.
         * These values come from translate_models_metadata.json in dictionaryproject.
         */
        val EN_FA_METADATA = ModelMetadata(
            pair = "en_fa",
            version = 5,
            revision = 29,
            downloadSize = 31_457_280,  // ~30 MB
            installedSize = 42_000_000,
            sha256 = "", // Filled by verify-catalog.yml
        )
    }
}