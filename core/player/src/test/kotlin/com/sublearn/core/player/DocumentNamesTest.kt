package com.sublearn.core.player

import android.app.Activity
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.OpenableColumns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowContentResolver

/** Real provider metadata must win over opaque numeric document IDs, including restored URIs. */
@RunWith(RobolectricTestRunner::class)
class DocumentNamesTest {
    private val context = RuntimeEnvironment.getApplication()
    private val uri = Uri.parse("content://sublearn.test/document/7880")

    private fun provider(name: String?) {
        ShadowContentResolver.registerProviderInternal("sublearn.test", object : ContentProvider() {
            override fun onCreate() = true
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
                MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf(name)) }
            override fun getType(uri: Uri) = "application/x-subrip"
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
        })
    }

    @Test fun numericUriUsesTheProviderName() {
        provider("Movie.fa.srt")
        assertEquals("Movie.fa.srt", DocumentNames.read(context, uri, "7880"))
    }

    @Test fun pickerUsesMetadataAndIgnoresCancelledResults() {
        provider("Movie.en.srt")
        val picker = OpenDocumentContract()
        picker.createIntent(context, arrayOf("*/*"))
        val intent = Intent().setData(uri)
        assertEquals("Movie.en.srt", picker.parseResult(Activity.RESULT_OK, intent)?.name)
        assertNull(picker.parseResult(Activity.RESULT_CANCELED, intent))
    }

    @Test fun restoredPickerCanResolveAResultWithoutCreatingAnotherIntent() {
        provider("Movie.fa.srt")
        val recreatedPicker = OpenDocumentContract(context = context)
        val result = recreatedPicker.parseResult(Activity.RESULT_OK, Intent().setData(uri))
        assertEquals("Movie.fa.srt", result?.name)
    }

    @Test fun missingProviderNameDoesNotDisplayAnOpaqueId() {
        provider(null)
        assertEquals("file", DocumentNames.read(context, uri))
        assertEquals("file", DocumentNames.read(context, uri, "7880"))
    }

    @Test fun fileUriUsesItsFileName() {
        assertEquals("Movie.en.srt", DocumentNames.read(context, Uri.parse("file:///storage/Movie.en.srt")))
    }
}
