package com.sublearn.feature.home

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContract
import com.sublearn.core.subtitles.SubtitleFormat

/** Opens a document picker for videos; SAF means no storage permission is needed (GEN-5). */
class OpenVideoContract : ActivityResultContract<Unit, PickedMedia?>() {
    override fun createIntent(context: Context, input: Unit): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "*/*"
        putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*", "application/x-mpegURL", "application/vnd.apple.mpegurl"))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
    }

    override fun parseResult(resultCode: Int, intent: Intent?): PickedMedia? {
        val uri = intent?.data ?: return null
        return PickedMedia(uri.toString(), MediaInfo.displayName(uri) ?: uri.lastPathSegment ?: "video")
    }

    companion object {
        /** Keeps a SAF video grant alive so Home can reopen its recent URI after process death. */
        fun persistReadPermission(context: Context, uriValue: String) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    Uri.parse(uriValue),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }

        /** A stale recent URI is sent back through the picker instead of surfacing an opaque IO error. */
        fun hasPersistentReadPermission(context: Context, uriValue: String): Boolean {
            val uri = Uri.parse(uriValue)
            if (uri.scheme != "content") return uri.scheme == "http" || uri.scheme == "https"
            return context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
        }
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

    /**
     * The media with its real file name. The provider's `DISPLAY_NAME` wins; the last path segment
     * is only a fallback for `file:` URIs and providers that publish no name.
     */
    fun read(context: Context, uri: Uri, displayName: String?): PickedMedia? {
        val name = displayName ?: runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "video"
        return PickedMedia(uri.toString(), name)
    }
}
