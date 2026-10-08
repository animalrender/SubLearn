package com.sublearn.feature.home

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContract
import com.sublearn.core.subtitles.SubtitleFormat
import java.util.Locale

/** Opens a document picker for videos; SAF means no storage permission is needed (GEN-5). */
class OpenVideoContract : ActivityResultContract<Unit, PickedMedia?>() {
    override fun createIntent(context: Context, input: Unit): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "*/*"
        putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*", "application/x-mpegURL", "application/vnd.apple.mpegurl"))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    override fun parseResult(resultCode: Int, intent: Intent?): PickedMedia? {
        val uri = intent?.data ?: return null
        return PickedMedia(uri.toString(), MediaInfo.displayName(uri) ?: uri.lastPathSegment ?: "video")
    }
}

/** A picked or shared media item, already converted to something the player can open. */
data class PickedMedia(val uri: String, val title: String)

/** Opens any file (used for subtitle files and for importing word lists). */
class OpenAnyFileContract(private val mimeTypes: Array<String> = arrayOf("*/*")) : ActivityResultContract<Array<String>, PickedFile?>() {
    override fun createIntent(context: Context, input: Array<String>): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "*/*"
        if (input.isNotEmpty()) putExtra(Intent.EXTRA_MIME_TYPES, input)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    override fun parseResult(resultCode: Int, intent: Intent?): PickedFile? {
        val uri = intent?.data ?: return null
        return PickedFile(
            uri = uri.toString(),
            name = MediaInfo.displayName(uri) ?: "file",
        )
    }
}

data class PickedFile(val uri: String, val name: String) {
    val looksLikeSubtitle: Boolean get() = SubtitleFormat.fromFileName(name) != SubtitleFormat.UNKNOWN
}

/** Metadata helpers that work for both content: and file:/ URIs. */
object MediaInfo {
    /** Name from the document provider when there is one, otherwise the last path segment. */
    fun displayName(uri: Uri): String? = uri.lastPathSegment?.substringAfterLast('/')

    fun read(context: Context, uri: Uri, displayName: String?): PickedMedia? {
        val name = displayName ?: runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: "video"
        return PickedMedia(uri.toString(), name)
    }

    /** Best-effort human title from a file name: strips extension and quality tags. */
    fun titleFromFileName(fileName: String): String {
        val stem = fileName.substringBeforeLast('.')
        val cleaned = stem
            .replace(Regex("""\[(.*?)]"""), " ")
            .replace(Regex("""(?i)\b(1080p|720p|480p|2160p|x264|x265|h264|h265|aac|web-dl|bluray|hdtv|x265-10bit)\b"""), " ")
            .replace(Regex("""[_.]+"""), " ")
            .trim()
        return cleaned.ifBlank { stem }.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
    }
}
