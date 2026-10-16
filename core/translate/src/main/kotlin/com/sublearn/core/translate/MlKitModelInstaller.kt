package com.sublearn.core.translate

import android.content.Context
import com.sublearn.core.common.AppResult
import com.sublearn.core.common.SubLearnError
import com.sublearn.core.common.SubLearnLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Robust ML Kit translation model installer with HTTP download, SHA-256 verification,
 * zip-slip protection, and atomic installation.
 *
 * The pure logic (URL scheme, checksum, archive handling) lives in [MlKitInstallLogic]
 * so it is unit testable without an Android context; this class only adds the
 * Android file locations, the network transfer and its progress reporting.
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

    /** Build the download URL for a model pair (see [MlKitInstallLogic.buildDownloadUrl]). */
    fun buildDownloadUrl(meta: MlKitModelMetadata, useFallback: Boolean = false): String =
        MlKitInstallLogic.buildDownloadUrl(meta, useFallback)

    /**
     * Download and install a translation model pair.
     *
     * @param meta model metadata
     * @param onProgress progress callback
     * @return success or error
     */
    suspend fun install(
        meta: MlKitModelMetadata,
        onProgress: (InstallState) -> Unit = {},
    ): AppResult<Unit> = withContext(Dispatchers.IO) {
        val pairDir = getInstallDir(meta.pair)
        val partFile = File(context.cacheDir, "mlkit_${meta.pair}.zip.part")

        try {
            // 1. Download (with redirect-host fallback)
            onProgress(InstallState.Downloading(0, meta.downloadSize))
            val downloadResult = download(meta, partFile, onProgress)
            if (downloadResult is AppResult.Failure) {
                return@withContext downloadResult
            }

            // 2. Verify SHA-256
            onProgress(InstallState.Verifying)
            val hash = MlKitInstallLogic.sha256(partFile)
            if (meta.sha256.isNotBlank() && hash.lowercase() != meta.sha256.lowercase()) {
                partFile.delete()
                return@withContext AppResult.failure(
                    SubLearnError(
                        SubLearnError.Kind.MalformedInput,
                        "SHA-256 mismatch: expected ${meta.sha256}, got $hash",
                    ),
                )
            }

            // 3. Unzip with zip-slip protection
            onProgress(InstallState.Installing)
            val tempDir = File(context.cacheDir, "mlkit_unzip_${meta.pair}")
            tempDir.deleteRecursively()
            tempDir.mkdirs()
            val unzipResult = MlKitInstallLogic.unzipSafe(partFile, tempDir)
            if (unzipResult is AppResult.Failure) {
                tempDir.deleteRecursively()
                return@withContext unzipResult
            }

            // 4. Validate the extracted layout
            val validationResult = MlKitInstallLogic.validateStructure(tempDir, meta.pair)
            if (validationResult is AppResult.Failure) {
                tempDir.deleteRecursively()
                return@withContext validationResult
            }

            // 5. Atomic install: drop the old copy, then move the verified one into place
            pairDir.deleteRecursively()
            pairDir.parentFile?.mkdirs()
            if (!tempDir.renameTo(pairDir)) {
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
        return dir.isDirectory && dir.listFiles()?.isNotEmpty() == true
    }

    /** Same layout ML Kit itself uses: `noBackupFilesDir/com.google.mlkit.translate.models/<pair>/`. */
    private fun getInstallDir(pair: String): File =
        File(context.noBackupFilesDir, "$MODELS_DIR/$pair")

    private suspend fun download(
        meta: MlKitModelMetadata,
        target: File,
        onProgress: (InstallState) -> Unit,
    ): AppResult<Unit> {
        var lastError: Throwable? = null

        for (useFallback in listOf(false, true)) {
            try {
                val url = MlKitInstallLogic.buildDownloadUrl(meta, useFallback)
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = CONNECT_TIMEOUT_MS
                conn.readTimeout = READ_TIMEOUT_MS
                conn.instanceFollowRedirects = true

                val code = conn.responseCode
                if (code != HttpURLConnection.HTTP_OK) {
                    conn.disconnect()
                    if (!useFallback) continue
                    return AppResult.failure(
                        SubLearnError(SubLearnError.Kind.NetworkUnavailable, "HTTP $code from $url"),
                    )
                }

                var downloaded = 0L
                val buffer = ByteArray(BUFFER_SIZE)

                target.outputStream().use { out ->
                    conn.inputStream.use { input ->
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            currentCoroutineContext().ensureActive()
                            out.write(buffer, 0, read)
                            downloaded += read
                            onProgress(InstallState.Downloading(downloaded, meta.downloadSize))
                        }
                    }
                }
                conn.disconnect()
                return AppResult.success(Unit)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                lastError = t
                target.delete()
                if (!useFallback) continue
            }
        }

        return AppResult.failure(
            lastError?.let { SubLearnError.from(it) }
                ?: SubLearnError(SubLearnError.Kind.NetworkUnavailable, "Download failed for ${meta.pair}"),
        )
    }

    companion object {
        private const val TAG = "MlKitModelInstaller"
        private const val MODELS_DIR = "com.google.mlkit.translate.models"
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val BUFFER_SIZE = 8192

        /**
         * Known model metadata for the EN↔FA pair, from `translate_models_metadata.json`
         * in `melonityhub/dictionaryproject`. `sha256` is filled in by `verify-catalog.yml`
         * once the catalog is resolved; an empty hash skips the checksum step.
         */
        val EN_FA_METADATA = MlKitModelMetadata(
            pair = "en_fa",
            version = 5,
            revision = 29,
            downloadSize = 31_457_280L,  // ~30 MB
            installedSize = 42_000_000L,
            sha256 = "",
        )
    }
}