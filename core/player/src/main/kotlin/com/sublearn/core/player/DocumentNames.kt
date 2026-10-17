package com.sublearn.core.player

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

/** SAF path segments are document IDs, not filenames; ask the provider before using a fallback. */
object DocumentNames {
    fun read(context: Context, uri: Uri, fallback: String = "file"): String {
        val provided = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }
        val segment = uri.lastPathSegment?.substringAfterLast('/')
        val safeFallback = fallback.takeUnless { uri.scheme == "content" && (it == segment || it == uri.toString()) } ?: "file"
        return provided ?: segment
            ?.takeIf { it.isNotBlank() && (uri.scheme != "content" || '.' in it) } ?: safeFallback
    }
}
