package com.sublearn.core.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.flow.first
import java.io.IOException

/**
 * Persistence for the settings document: one preference entry holding the versioned JSON, written
 * through DataStore so I/O happens off the main thread and survives process death (GEN-5).
 *
 * A single document (rather than one key per option) keeps a migration atomic and makes
 * export/import the same code path as persistence.
 */
class DataStoreSettingsStorage(
    private val store: DataStore<Preferences>,
) : SettingsStorage {
    override suspend fun read(key: String): String? = try {
        store.data.first()[stringPreferencesKey(key)]
    } catch (_: IOException) {
        // A corrupt DataStore file must not lock the app out of its own settings.
        null
    }

    override suspend fun write(key: String, value: String) {
        store.edit { preferences -> preferences[stringPreferencesKey(key)] = value }
    }

    override suspend fun clear(key: String) {
        store.edit { preferences -> preferences.remove(stringPreferencesKey(key)) }
    }

    companion object {
        const val FILE_NAME = "sublearn_settings"

        fun create(context: Context): DataStoreSettingsStorage {
            val store = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
                produceFile = { context.preferencesDataStoreFile(FILE_NAME) },
                corruptionHandler = androidx.datastore.core.handlers.ReplaceFileCorruptionHandler { emptyPreferences() },
            )
            return DataStoreSettingsStorage(store)
        }
    }
}
