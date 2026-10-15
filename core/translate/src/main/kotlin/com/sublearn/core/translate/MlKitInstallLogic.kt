package com.sublearn.core.translate

import com.sublearn.core.common.AppResult
import com.sublearn.core.common.SubLearnError
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Metadata for a translation model pair, mirroring the fields of
 * `translate_models_metadata.json` in `melonityhub/dictionaryproject`.
 *
 * @property pair language pair, `en_fa` style
 * @property version model version (`V`)
 * @property revision model revision (`RV`)
 * @property downloadSize archive size in bytes (`DL_SZ`)
 * @property installedSize size after extraction (`SZ`)
 * @property sha256 expected archive digest (`HASH`); empty skips verification
 */
data class MlKitModelMetadata(
    val pair: String,
    val version: Int,
    val revision: Int,
    val downloadSize: Long,
    val installedSize: Long,
    val sha256: String,
)

/**
 * Pure, Android-free installation logic for ML Kit translation models (§3.1).
 *
 * Extracted from [MlKitModelInstaller] so the URL scheme, checksum verification,
 * zip-slip protection and archive validation can be unit tested on a plain JVM
 * without an Android `Context`.
 */
internal object MlKitInstallLogic {

    const val PRIMARY_HOST = "dl.google.com"
    const val FALLBACK_HOST = "redirector.gvt1.com"

    /**
     * Build the download URL for a model pair.
     *
     * Primary: `https://dl.google.com/translate/offline/v{V}/high/r{RV}/{pair}.zip`
     * Fallback: `https://redirector.gvt1.com/edgedl/translate/offline/v{V}/high/r{RV}/{pair}.zip`
     */
    fun buildDownloadUrl(meta: MlKitModelMetadata, useFallback: Boolean = false): String {
        val host = if (useFallback) FALLBACK_HOST else PRIMARY_HOST
        val path = "translate/offline/v${meta.version}/high/r${meta.revision}/${meta.pair}.zip"
        return if (useFallback) "https://$host/edgedl/$path" else "https://$host/$path"
    }

    /** Streaming SHA-256 of a file, lowercase hex. */
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Extract [zipFile] into [targetDir], rejecting any entry whose canonical path escapes
     * [targetDir] (zip-slip, CWE-22).
     */
    fun unzipSafe(zipFile: File, targetDir: File): AppResult<Unit> {
        return try {
            val canonicalTarget = targetDir.canonicalFile
            ZipInputStream(zipFile.inputStream()).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    val outFile = File(canonicalTarget, entry.name)
                    if (!isInside(canonicalTarget, outFile)) {
                        return AppResult.failure(
                            SubLearnError(SubLearnError.Kind.MalformedInput, "Zip-slip detected: ${entry.name}"),
                        )
                    }
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().use { out -> zis.copyTo(out) }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            AppResult.success(Unit)
        } catch (t: Throwable) {
            AppResult.failure(SubLearnError.from(t))
        }
    }

    /**
     * Check that an extracted archive contains a usable model.
     *
     * ML Kit ships both directions in one archive: `translate_enfa/` and `translate_faen/`.
     * At least one of them must contain `translation_model.pb`.
     */
    fun validateStructure(dir: File, pair: String): AppResult<Unit> {
        val parts = pair.split("_")
        if (parts.size != 2) {
            return AppResult.failure(
                SubLearnError(SubLearnError.Kind.MalformedInput, "Invalid pair format: $pair"),
            )
        }
        val forwardDir = "translate_${parts[0]}${parts[1]}"
        val reverseDir = "translate_${parts[1]}${parts[0]}"

        val forwardModel = File(File(dir, forwardDir), "translation_model.pb")
        val reverseModel = File(File(dir, reverseDir), "translation_model.pb")

        if (!forwardModel.exists() && !reverseModel.exists()) {
            return AppResult.failure(
                SubLearnError(
                    SubLearnError.Kind.ModelMissing,
                    "Missing translation_model.pb in $forwardDir or $reverseDir",
                ),
            )
        }
        return AppResult.success(Unit)
    }

    /** True when [candidate] resolves to [parent] or something below it. */
    private fun isInside(parent: File, candidate: File): Boolean {
        val parentPath = parent.canonicalPath
        val candidatePath = candidate.canonicalPath
        return candidatePath == parentPath || candidatePath.startsWith(parentPath + File.separator)
    }
}