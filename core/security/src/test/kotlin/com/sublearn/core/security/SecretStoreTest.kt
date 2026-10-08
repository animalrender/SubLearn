package com.sublearn.core.security

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class SecretStoreTest {
    private fun store(): Pair<SecretStore, InMemorySecretStorage> {
        val storage = InMemorySecretStorage()
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() as SecretKey
        return EncryptedSecretStore(storage, AesGcmCipher { key }) to storage
    }

    @Test
    fun `a secret round trips and is stored encrypted`() = runTest {
        val (secrets, storage) = store()
        secrets.put(SecretStore.AI_KEY_REF, "AIzaSuperSecretKeyValue12345")
        assertEquals("AIzaSuperSecretKeyValue12345", secrets.get(SecretStore.AI_KEY_REF))
        val raw = storage.read(SecretStore.AI_KEY_REF)!!
        assertFalse(raw.contains("Secret"))
        assertTrue(raw.length > 20)
    }

    @Test
    fun `deleting works and a blank value clears the entry`() = runTest {
        val (secrets, _) = store()
        secrets.put("a", "value")
        secrets.delete("a")
        assertNull(secrets.get("a"))
        secrets.put("b", "value")
        secrets.put("b", "")
        assertFalse(secrets.contains("b"))
    }

    @Test
    fun `a corrupted payload returns null instead of crashing`() = runTest {
        val (secrets, storage) = store()
        storage.write("broken", "not-base64-!!")
        assertNull(secrets.get("broken"))
    }

    @Test
    fun `cipher never produces the same bytes twice`() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() as SecretKey
        val cipher = AesGcmCipher { key }
        val plain = "same input".toByteArray()
        val a = cipher.encrypt(plain)
        val b = cipher.encrypt(plain)
        assertFalse(a.contentEquals(b))
        assertEquals("same input", String(cipher.decrypt(a)))
        assertEquals("same input", String(cipher.decrypt(b)))
    }
}
