package com.sublearn.core.ai

import com.sublearn.core.common.AppResult
import com.sublearn.core.common.SubLearnError
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * One download attempt: where to fetch from, what it must hash to, and where it lands.
 *
 * @property urls tried in order; the second host is the fallback when the first is blocked
 * @property expectedSha256 lowercase digest the downloaded file must match, or empty to skip
 * @property targetDirectory directory the archive's contents belong in
 * @property stagingFileName temporary name for the partial download inside [targetDirectory]
 */
data class DownloadPlan(
    val urls: List<String>,
    val expectedSha256: String,
    val targetDirectory: File,
    val stagingFileName: String = "download.part",
)

/** Progress of a download, reported as it arrives. */
data class DownloadProgress(
    val bytesDownloaded: Long,
    val totalBytes: Long,
) {
    /** `0f..1f`, or `0f` when the server did not send a length. */
    val fraction: Float
        get() = if (totalBytes > 0L) (bytesDownloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
}

/** What a finished download produced. */
data class DownloadOutcome(
    val bytesWritten: Long,
    val sha256: String,
    val host: String,
)

/**
 * The single download path for every optional model (§3.2: one unified download pattern).
 *
 * Deliberately plain JVM: no Android, no OkHttp, no coroutines. That is what lets the tests drive
 * a real HTTP transfer — including the failure modes that matter, a wrong hash, a truncated body
 * and a dead host — without a device. Callers wrap it in whatever dispatcher and progress
 * reporting they already have.
 *
 * Every exit path deletes the partial file, so a cancelled or failed download never leaves a
 * half-written file that a later run would trust.
 */
class ModelDownloader(
    private val connectTimeoutMs: Int = 30_000,
    private val readTimeoutMs: Int = 60_000,
    private val bufferSize: Int = 64 * 1024,
    private val digestFactory: () -> MessageDigest = { MessageDigest.getInstance("SHA-256") },
    /** Called before each buffered write; throwing cancels the transfer and deletes the partial. */
    private val onChunk: () -> Unit = {},
) {

    /**
     * Transfer [plan] to disk, verify it and report what arrived.
     *
     * @param onProgress invoked per chunk with the running totals
     */
    fun download(
        plan: DownloadPlan,
        onProgress: (DownloadProgress) -> Unit = {},
    ): AppResult<DownloadOutcome> {
        if (plan.urls.isEmpty()) {
            return AppResult.failure(
                SubLearnError(SubLearnError.Kind.MalformedInput, "no download URL was configured"),
            )
        }
        plan.targetDirectory.mkdirs()
        val partial = File(plan.targetDirectory, plan.stagingFileName)

        var lastError: SubLearnError? = null

        for (url in plan.urls) {
            try {
                val outcome = transfer(url, partial, plan.expectedSha256, onProgress)
                if (outcome is AppResult.Success) return outcome
                lastError = (outcome as AppResult.Failure).error
                partial.delete()
                // A checksum mismatch means the bytes are wrong, not the host; retrying elsewhere
                // would download the same wrong bytes again, so stop and report it.
                if (lastError.kind == SubLearnError.Kind.MalformedInput) return outcome
            } catch (cancelled: InterruptedException) {
                partial.delete()
                Thread.currentThread().interrupt()
                return AppResult.failure(
                    SubLearnError(SubLearnError.Kind.Timeout, "the download was cancelled", cancelled),
                )
            } catch (aborted: Throwable) {
                // A caller that cancels from inside `onProgress` (a coroutine's `ensureActive`, the
                // user's stop button) throws through the transfer loop. The partial file must not
                // survive that any more than it survives a failure, so clean up and let it propagate.
                partial.delete()
                throw aborted
            }
        }

        partial.delete()
        return AppResult.failure(
            lastError ?: SubLearnError(SubLearnError.Kind.NetworkUnavailable, "every download host failed"),
        )
    }

    private fun transfer(
        url: String,
        partial: File,
        expectedSha256: String,
        onProgress: (DownloadProgress) -> Unit,
    ): AppResult<DownloadOutcome> {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                instanceFollowRedirects = true
                setRequestProperty("Accept-Encoding", "identity")
            }

            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                return AppResult.failure(
                    SubLearnError(SubLearnError.Kind.NetworkUnavailable, "HTTP $code from $url"),
                )
            }

            val totalBytes = connection.contentLengthLong.coerceAtLeast(0L)
            val digest = digestFactory()
            var written = 0L

            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(bufferSize)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        onChunk()
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        written += read
                        onProgress(DownloadProgress(written, totalBytes))
                    }
                }
            }

            if (totalBytes > 0L && written != totalBytes) {
                return AppResult.failure(
                    SubLearnError(
                        SubLearnError.Kind.NetworkUnavailable,
                        "the connection closed early: got $written of $totalBytes bytes",
                    ),
                )
            }

            val actual = digest.digest().toHex()
            if (expectedSha256.isNotBlank() && !actual.equals(expectedSha256, ignoreCase = true)) {
                return AppResult.failure(
                    SubLearnError(
                        SubLearnError.Kind.MalformedInput,
                        "SHA-256 mismatch: expected $expectedSha256, got $actual",
                    ),
                )
            }

            AppResult.success(DownloadOutcome(written, actual, URL(url).host))
        } catch (io: IOException) {
            AppResult.failure(SubLearnError.from(io))
        } finally {
            connection?.disconnect()
        }
    }

    companion object {
        /** Streaming SHA-256 of a file, lowercase hex. */
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().toHex()
        }

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
