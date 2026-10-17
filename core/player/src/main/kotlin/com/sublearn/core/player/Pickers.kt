package com.sublearn.core.player

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract

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

class OpenDocumentContract(
    private val mimeTypes: Array<String> = arrayOf("*/*"),
    context: Context? = null,
) :
    ActivityResultContract<Array<String>, PickedFile?>() {

    private var applicationContext: Context? = context?.applicationContext

    override fun createIntent(context: Context, input: Array<String>): Intent {
        applicationContext = context.applicationContext
        return Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = input.firstOrNull() ?: "*/*"
            if (input.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, input)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
    }

    override fun parseResult(resultCode: Int, intent: Intent?): PickedFile? {
        if (resultCode != Activity.RESULT_OK) return null
        val uri = intent?.data ?: return null
        return PickedFile(uri.toString(), applicationContext?.let { DocumentNames.read(it, uri) } ?: "file")
    }

    companion object {
        /** Persist the document grant before its URI is remembered for the next player session. */
        fun persistReadPermission(context: Context, uriValue: String) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    Uri.parse(uriValue),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
    }
}

/** A whole folder, so subtitles next to a video can be found automatically (SUB-2). */
class OpenTreeContract : ActivityResultContract<Unit, String?>() {
    override fun createIntent(context: Context, input: Unit): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
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
