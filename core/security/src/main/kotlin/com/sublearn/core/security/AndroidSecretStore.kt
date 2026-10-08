package com.sublearn.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.flow.first
import java.security.Key
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences

/**
 * Android Keystore master key.
 *
 * `setUserAuthenticationRequired(false)` is deliberate: a background video must keep working after
 * a reboot without a fingerprint, and the threat model here is a stolen backup, not an unlocked
 * phone being used by someone else. The key is non-exportable either way.
 */
class AndroidKeyStoreProvider(
    private val alias: String = DEFAULT_ALIAS,
) : MasterKeyProvider {
    override fun getOrCreate(): Key {
        val keyStore = java.security.KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(alias, null) as? java.security.KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val DEFAULT_ALIAS = "sublearn.ai.master.key"
    }
}

/** Key rotation helper: wipes the ciphertexts and lets a fresh key be created on next use. */
object SecretKeyRotation {
    const val FAILED_PREFIX = "!"

    fun markUnusable(encoded: String): String = if (encoded.startsWith(FAILED_PREFIX)) encoded else FAILED_PREFIX + encoded

    fun isUnusable(encoded: String?): Boolean = encoded?.startsWith(FAILED_PREFIX) == true
}

/** DataStore-backed [SecretStorage]; one small file, never included in Settings export. */
class DataStoreSecretStorage(
    private val store: DataStore<Preferences>,
) : SecretStorage {
    override suspend fun read(ref: String): String? = store.data.first()[stringPreferencesKey(ref)]

    override suspend fun write(ref: String, value: String) {
        store.edit { it[stringPreferencesKey(ref)] = value }
    }

    override suspend fun delete(ref: String) {
        store.edit { it.remove(stringPreferencesKey(ref)) }
    }

    companion object {
        const val FILE_NAME = "sublearn_secrets"

        fun create(context: Context): DataStoreSecretStorage {
            val store = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
                produceFile = { context.preferencesDataStoreFile(FILE_NAME) },
                corruptionHandler = androidx.datastore.core.handlers.ReplaceFileCorruptionHandler(emptyPreferences()),
            )
            return DataStoreSecretStorage(store)
        }
    }
}

/** Convenience factory used by the app's dependency graph. */
fun productionSecretStore(context: Context): SecretStore = EncryptedSecretStore(
    storage = DataStoreSecretStorage.create(context),
    cipher = AesGcmCipher(AndroidKeyStoreProvider()),
)

/** Test/preview double: an exportable in-memory key, so unit tests exercise the same cipher path. */
fun inMemorySecretStore(): SecretStore {
    val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() as SecretKey
    return EncryptedSecretStore(InMemorySecretStorage(), AesGcmCipher { key })
}
