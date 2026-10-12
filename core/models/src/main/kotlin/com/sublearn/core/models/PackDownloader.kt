package com.sublearn.core.models

import com.sublearn.core.common.AppResult
import com.sublearn.core.common.SubLearnError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

data class PackDownloadState(
    val entryId: String,
    val status: Status,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val sha256: String? = null,
    val filePath: String? = null,
    val error: String? = null,
) {
    enum class Status { IDLE, DOWNLOADING, PAUSED, VERIFYING, COMPLETED, FAILED }

    val fraction: Float
        get() = if (totalBytes <= 0L) 0f else (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
}

/**
 * Resumable HTTP range downloader for lexicon packs and model artifacts (`D-54`).
 *
 * - Writes into `<destFile>.part` and sends `Range: bytes=<existing>-` when a `.part` file exists.
 * - Verifies SHA-256 on completion before atomically renaming `<destFile>.part` to `<destFile>`.
 */
class PackDownloader(
    private val storageDir: File,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build(),
) {
    private val _states = MutableStateFlow<Map<String, PackDownloadState>>(emptyMap())
    val states: StateFlow<Map<String, PackDownloadState>> = _states.asStateFlow()

    suspend fun download(
        entry: ModelCatalogEntry,
        targetFileName: String = defaultFileNameFor(entry),
    ): AppResult<File> = withContext(Dispatchers.IO) {
        if (entry.source.url.isBlank()) {
            return@withContext AppResult.failure(
                SubLearnError(SubLearnError.Kind.MalformedInput, "Entry '${entry.id}' has no download URL"),
            )
        }
        storageDir.mkdirs()
        val finalFile = File(storageDir, targetFileName)
        val partFile = File(storageDir, "$targetFileName.part")
        val existingBytes = if (partFile.exists()) partFile.length() else 0L

        updateState(
            PackDownloadState(
                entryId = entry.id,
                status = PackDownloadState.Status.DOWNLOADING,
                downloadedBytes = existingBytes,
                totalBytes = entry.sizeBytes,
            ),
        )

        try {
            val reqBuilder = Request.Builder()
                .url(entry.source.url)
                .header("User-Agent", "SubLearn/2.0")
                .get()
            if (existingBytes > 0L) {
                reqBuilder.header("Range", "bytes=$existingBytes-")
            }

            client.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful && resp.code != 206) {
                    val msg = "HTTP ${resp.code} while downloading ${entry.id}"
                    updateState(
                        PackDownloadState(
                            entryId = entry.id,
                            status = PackDownloadState.Status.FAILED,
                            downloadedBytes = existingBytes,
                            totalBytes = entry.sizeBytes,
                            error = msg,
                        ),
                    )
                    return@withContext AppResult.failure(SubLearnError(SubLearnError.Kind.NetworkUnavailable, msg))
                }

                val appendMode = resp.code == 206 && existingBytes > 0L
                val body = resp.body ?: return@withContext AppResult.failure(
                    SubLearnError(SubLearnError.Kind.NetworkUnavailable, "Empty response body for ${entry.id}"),
                )
                val contentLength = body.contentLength().coerceAtLeast(0L)
                val totalExpected = if (appendMode) {
                    existingBytes + contentLength
                } else {
                    contentLength.takeIf { it > 0L } ?: entry.sizeBytes
                }

                FileOutputStream(partFile, appendMode).use { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var currentBytes = if (appendMode) existingBytes else 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            currentBytes += n
                            updateState(
                                PackDownloadState(
                                    entryId = entry.id,
                                    status = PackDownloadState.Status.DOWNLOADING,
                                    downloadedBytes = currentBytes,
                                    totalBytes = totalExpected,
                                ),
                            )
                        }
                        out.flush()
                    }
                }
            }

            updateState(
                PackDownloadState(
                    entryId = entry.id,
                    status = PackDownloadState.Status.VERIFYING,
                    downloadedBytes = partFile.length(),
                    totalBytes = partFile.length(),
                ),
            )

            val computedSha = computeSha256(partFile)
            val expectedSha = entry.sha256?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            if (expectedSha != null && computedSha != expectedSha) {
                partFile.delete()
                val err = "SHA-256 mismatch for ${entry.id}: expected $expectedSha, got $computedSha"
                updateState(
                    PackDownloadState(
                        entryId = entry.id,
                        status = PackDownloadState.Status.FAILED,
                        error = err,
                    ),
                )
                return@withContext AppResult.failure(SubLearnError(SubLearnError.Kind.MalformedInput, err))
            }

            if (finalFile.exists()) finalFile.delete()
            if (!partFile.renameTo(finalFile)) {
                partFile.copyTo(finalFile, overwrite = true)
                partFile.delete()
            }

            updateState(
                PackDownloadState(
                    entryId = entry.id,
                    status = PackDownloadState.Status.COMPLETED,
                    downloadedBytes = finalFile.length(),
                    totalBytes = finalFile.length(),
                    sha256 = computedSha,
                    filePath = finalFile.absolutePath,
                ),
            )
            AppResult.success(finalFile)
        } catch (ce: CancellationException) {
            updateState(
                PackDownloadState(
                    entryId = entry.id,
                    status = PackDownloadState.Status.PAUSED,
                    downloadedBytes = if (partFile.exists()) partFile.length() else 0L,
                    totalBytes = entry.sizeBytes,
                ),
            )
            throw ce
        } catch (t: Throwable) {
            val err = t.message ?: "Download failed"
            updateState(
                PackDownloadState(
                    entryId = entry.id,
                    status = PackDownloadState.Status.FAILED,
                    downloadedBytes = if (partFile.exists()) partFile.length() else 0L,
                    totalBytes = entry.sizeBytes,
                    error = err,
                ),
            )
            AppResult.failure(SubLearnError.from(t))
        }
    }

    private fun updateState(state: PackDownloadState) {
        _states.value = _states.value + (state.entryId to state)
    }

    companion object {
        private const val DEFAULT_BUFFER_SIZE = 32 * 1024

        fun defaultFileNameFor(entry: ModelCatalogEntry): String {
            val urlName = entry.source.url.substringAfterLast('/').substringBefore('?').trim()
            return if (urlName.isNotEmpty() && '.' in urlName) {
                urlName
            } else {
                when (entry.kind) {
                    ModelKind.LEXICON, ModelKind.DICT_FA_EN, ModelKind.TRANSLATION_PACK -> "${entry.id}.sqlite.gz"
                    ModelKind.EMBEDDER_ONNX, ModelKind.WSD_ONNX -> "${entry.id}.onnx"
                    ModelKind.LLM -> "${entry.id}.task"
                    else -> "${entry.id}.bin"
                }
            }
        }

        fun computeSha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buf = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
