package com.sublearn.core.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A document the user picked through the Storage Access Framework.
 *
 * Every "add a file" affordance in SubLearn goes through [OpenDocumentContract], so there is one
 * place that knows how SAF grants are requested and read (no runtime storage permission anywhere).
 */
data class PickedFile(val uri: String, val name: String) {
    val uriValue: Uri get() = Uri.parse(uri)

    /** Whether the provider can still read the file; a stale grant shows as an unreadable item. */
    val isProbablyReadable: Boolean get() = uri.startsWith("content://") || uri.startsWith("file://")
}

class OpenDocumentContract(private val mimeTypes: Array<String> = arrayOf("*/*")) :
    ActivityResultContract<Array<String>, PickedFile?>() {

    override fun createIntent(context: Context, input: Array<String>): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = input.firstOrNull() ?: "*/*"
            if (input.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, input)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    override fun parseResult(resultCode: Int, intent: Intent?): PickedFile? {
        val uri = intent?.data ?: return null
        return PickedFile(uri.toString(), displayName(uri) ?: "file")
    }

    private fun displayName(uri: Uri): String? = uri.lastPathSegment?.substringAfterLast('/')
}

/** A whole folder, so subtitles next to a video can be found automatically (SUB-2). */
class OpenTreeContract : ActivityResultContract<Unit, String?>() {
    override fun createIntent(context: Context, input: Unit): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    /**
     * The tree URI exactly as SAF handed it back. It is deliberately not converted into a document URI:
     * the caller needs the grant, and the reader resolves children from the tree itself.
     */
    override fun parseResult(resultCode: Int, intent: Intent?): String? = intent?.data?.toString()

    companion object {
        /**
         * Keeps the read grant alive across reboots. Parsing the result happens without a Context, so the
         * screen that stores the tree URI calls this once, otherwise the folder is unreadable after the
         * next launch and the sidecar list comes up empty for a reason the user cannot see (SUB-2).
         */
        fun persistReadPermission(context: Context, treeUri: String) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    Uri.parse(treeUri),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
    }
}

/**
 * Whether a media URI can still be opened (PLY-12).
 *
 * A URI the user picked through the Storage Access Framework is readable for the session, and only
 * until the device restarts unless the app took a persistable permission. Home has to be able to ask
 * before it navigates, otherwise a stale entry fails inside the decoder as
 * `ERROR_CODE_IO_UNSPECIFIED`, which tells the user nothing.
 */
interface MediaAccess {
    suspend fun isReadable(uri: String): Boolean
}

class AndroidMediaAccess(private val context: Context) : MediaAccess {
    override suspend fun isReadable(uri: String): Boolean = withContext(Dispatchers.IO) {
        when {
            uri.startsWith("file://") -> runCatching { File(Uri.parse(uri).path.orEmpty()).canRead() }.getOrDefault(false)
            uri.startsWith("content://") -> runCatching {
                context.contentResolver.openFileDescriptor(Uri.parse(uri), "r")?.use { true } ?: false
            }.getOrDefault(false)
            // A stream is the network's problem, never the filesystem's; the player reports its own errors.
            else -> true
        }
    }
}

/**
 * Persistable SAF grants.
 *
 * `ACTION_OPEN_DOCUMENT` hands back a URI whose read permission only lives until the next restart
 * unless it is persisted here. Without this call every recent-video row breaks after a reboot with
 * a source error, which is exactly the bug PLY-12 fixes. The call is safe to repeat and never
 * throws out of the caller: a provider that does not offer persistable grants (a share intent, for
 * example) simply cannot be persisted, and the caller falls back to re-picking the file.
 */
object UriGrants {
    /** Returns true when the grant is now durable. */
    fun persistRead(context: Context, uri: String): Boolean = runCatching {
        context.contentResolver.takePersistableUriPermission(
            Uri.parse(uri),
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
        true
    }.getOrDefault(false)

    /** True when this URI was already persisted, so Home can tell "moved" from "never granted". */
    fun isPersisted(context: Context, uri: String): Boolean = runCatching {
        context.contentResolver.persistedUriPermissions.any { it.isReadPermission && it.uri.toString() == uri }
    }.getOrDefault(false)
}
