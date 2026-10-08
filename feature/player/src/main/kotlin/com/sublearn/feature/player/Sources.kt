package com.sublearn.feature.player

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.sublearn.core.common.AppResult
import com.sublearn.core.common.SubLearnError
import com.sublearn.core.lexicon.TextResourceReader
import com.sublearn.core.subtitles.SubtitleFile
import com.sublearn.core.subtitles.SubtitleFileSource
import com.sublearn.core.subtitles.SubtitleFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads a file the user picked through the Storage Access Framework.
 *
 * SubLearn never asks for a storage permission: the picked URI is the grant. The grant is kept for
 * the current session only (persistable permissions are Phase 1 work), which is why a layer's file
 * has to be re-picked after a reinstall rather than silently disappearing.
 */
class SafSubtitleFileSource(private val context: Context) : SubtitleFileSource {
    override suspend fun read(file: SubtitleFile): ByteArray = withContext(Dispatchers.IO) {
        val uri = Uri.parse(file.key)
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw java.io.FileNotFoundException(uri.toString())
        }.getOrElse { throwable ->
            throw IllegalStateException(readable(file, throwable), throwable)
        }
    }

    /**
     * Subtitles that sit next to the video, found through the tree grant when the picker gave one.
     *
     * A plain document URI from `ACTION_OPEN_DOCUMENT` carries no tree, so the caller passes the
     * persisted tree (Settings -> "subtitle folder") as [sidecarTreeUri]. Without it there is no
     * directory to list and the feature degrades to manual file picking, which is the honest
     * behaviour rather than a guess (see docs/KNOWN_ISSUES.md).
     */
    suspend fun sidecars(videoKey: String, sidecarTreeUri: String?): List<SubtitleFile> = withContext(Dispatchers.IO) {
        if (sidecarTreeUri.isNullOrBlank()) emptyList() else context.listSubtitleChildren(sidecarTreeUri, videoKey)
    }

    private fun readable(file: SubtitleFile, throwable: Throwable): String =
        "cannot read ${file.name}: ${throwable.message ?: "the file may have moved"}"
}

private fun Context.listSubtitleChildren(treeUri: String, videoKey: String): List<SubtitleFile> {
    val base = Uri.parse(videoKey)
    val videoName = base.lastPathSegment?.substringAfterLast('/') ?: return emptyList()
    val stem = videoName.substringBeforeLast('.')
    val children = runCatching {
        DocumentsContract.buildChildDocumentsUriUsingTree(Uri.parse(treeUri), DocumentsContract.getTreeDocumentId(Uri.parse(treeUri)))
    }.getOrNull() ?: return emptyList()
    return runCatching {
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        )
        contentResolver.query(children, projection, null, null, null)
            ?.use { cursor ->
                val out = ArrayList<SubtitleFile>()
                while (cursor.moveToNext()) {
                    val name = cursor.getString(1) ?: continue
                    if (!name.startsWith(stem, ignoreCase = true)) continue
                    if (SubtitleFormat.fromFileName(name) != SubtitleFormat.UNKNOWN) {
                        val docId = cursor.getString(0)
                        out += SubtitleFile(
                            key = DocumentsContract.buildDocumentUriUsingTree(Uri.parse(treeUri), docId).toString(),
                            name = name,
                        )
                    }
                }
                out
            }
    }.getOrDefault(emptyList()).orEmpty()
}

/** Same access, shaped for the word-list importer in `:core:lexicon`. */
class SafTextResourceReader(private val context: Context) : TextResourceReader {
    override suspend fun readText(uriKey: String): AppResult<Pair<String, String>> = withContext(Dispatchers.IO) {
        runCatching {
            val uri = Uri.parse(uriKey)
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "list.txt"
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return@runCatching AppResult.failure(SubLearnError(SubLearnError.Kind.NotFound, "the file could not be opened"))
            AppResult.success(name to com.sublearn.core.subtitles.CharsetSniffer.decode(bytes).text)
        }.getOrElse { throwable ->
            AppResult.failure(SubLearnError(SubLearnError.Kind.NotFound, throwable.message ?: "the file could not be read"))
        }
    }
}

/** File-name driven role suggestion, shared by the picker and the sidecar loader (SUB-2). */
object SubtitleRoleGuess {
    fun guess(fileName: String, nativeTag: String): SubtitleFormatAndRole {
        val format = SubtitleFormat.fromFileName(fileName)
        val tag = com.sublearn.core.subtitles.SubtitleLanguage.guessFromName(fileName)
        val role = if (tag != null && com.sublearn.core.subtitles.SubtitleLanguage.isNativeSide(tag, nativeTag)) {
            com.sublearn.core.subtitles.TrackRole.TRANSLATION
        } else {
            com.sublearn.core.subtitles.TrackRole.LEARNING
        }
        return SubtitleFormatAndRole(format, role, tag)
    }

    data class SubtitleFormatAndRole(val format: SubtitleFormat, val role: com.sublearn.core.subtitles.TrackRole, val languageTag: String?)
}
