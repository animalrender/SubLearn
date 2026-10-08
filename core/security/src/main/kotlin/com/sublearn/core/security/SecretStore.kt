package com.sublearn.core.security

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Base64

/**
 * Where an API key lives at runtime. The value must never be written into the settings document,
 * exported, or logged (ENGINEERING REQUIREMENTS: secrets).
 */
interface SecretStore {
    suspend fun get(ref: String): String?

    suspend fun put(ref: String, value: String)

    suspend fun delete(ref: String)

    suspend fun contains(ref: String): Boolean

    companion object {
        const val AI_KEY_REF = "ai_api_key"
    }
}

/** Encrypts with AES-GCM under a key the platform keeps in hardware when it can. */
class AesGcmCipher(private val keyProvider: MasterKeyProvider) {
    fun encrypt(plain: ByteArray): ByteArray {
        val cipher = javax.crypto.Cipher.getInstance(TRANSFORMATION)
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, keyProvider.getOrCreate())
        val iv = cipher.iv
        val body = cipher.doFinal(plain)
        return iv + body
    }

    fun decrypt(payload: ByteArray): ByteArray {
        require(payload.size > IV_LENGTH) { "ciphertext is too short" }
        val iv = payload.copyOfRange(0, IV_LENGTH)
        val body = payload.copyOfRange(IV_LENGTH, payload.size)
        val spec = javax.crypto.spec.GCMParameterSpec(TAG_LENGTH_BITS, iv)
        val cipher = javax.crypto.Cipher.getInstance(TRANSFORMATION)
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, keyProvider.getOrCreate(), spec)
        return cipher.doFinal(body)
    }

    companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val TAG_LENGTH_BITS = 128
    }
}

/** Supplies the encryption key; implementations decide whether the key is exportable (never). */
fun interface MasterKeyProvider {
    fun getOrCreate(): java.security.Key
}

/**
 * Data-at-rest store: the ciphertext goes into a private preferences file, the key stays inside the
 * Android Keystore, so a backup of the app's data does not reveal the key material.
 */
class EncryptedSecretStore(
    private val storage: SecretStorage,
    private val cipher: AesGcmCipher,
) : SecretStore {
    private val mutex = Mutex()

    override suspend fun get(ref: String): String? {
        val encoded = storage.read(ref) ?: return null
        return runCatching { String(cipher.decrypt(Base64.getDecoder().decode(encoded))) }.getOrNull()
    }

    override suspend fun put(ref: String, value: String) = mutex.withLock {
        if (value.isBlank()) {
            storage.delete(ref)
            return@withLock
        }
        val encoded = Base64.getEncoder().encodeToString(cipher.encrypt(value.toByteArray()))
        storage.write(ref, encoded)
    }

    override suspend fun delete(ref: String) = mutex.withLock { storage.delete(ref) }

    override suspend fun contains(ref: String): Boolean = !storage.read(ref).isNullOrBlank()
}

/** Key/value blob persistence, kept tiny so the crypto above is unit-testable on the JVM. */
interface SecretStorage {
    suspend fun read(ref: String): String?

    suspend fun write(ref: String, value: String)

    suspend fun delete(ref: String)
}

class InMemorySecretStorage(initial: Map<String, String> = emptyMap()) : SecretStorage {
    private val values = LinkedHashMap(initial)

    override suspend fun read(ref: String): String? = values[ref]

    override suspend fun write(ref: String, value: String) {
        values[ref] = value
    }

    override suspend fun delete(ref: String) {
        values.remove(ref)
    }
}
