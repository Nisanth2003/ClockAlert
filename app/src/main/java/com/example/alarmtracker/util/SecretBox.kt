package com.example.alarmtracker.util

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts small secrets — API tokens, pairing keys — before they are written to
 * SharedPreferences or the database, using a non-exportable AES key held by the Android Keystore.
 *
 * The threat this actually addresses is an offline copy of the app's data: a rooted device dump, a
 * careless backup, an adb pull on a debug build. Without this, a Jira API token and a relay token
 * sit in an XML file in plain text. It is NOT protection against malware running as this app.
 *
 * Deliberately not requiring device unlock to use the key: the alarm and friend-alert paths run
 * while the phone is locked, and a key that needed authentication would break them.
 */
object SecretBox {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val DEFAULT_ALIAS = "alarmtracker_secrets"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12

    /**
     * Returns `base64(iv || ciphertext||tag)`, or the input unchanged if the Keystore is
     * unavailable — a stored secret is worth more than a hard failure here.
     */
    fun seal(plaintext: String, alias: String = DEFAULT_ALIAS): String = try {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            // The IV comes FROM the cipher: a Keystore key defaults to
            // setRandomizedEncryptionRequired(true), which rejects a caller-supplied IV on encrypt.
            init(Cipher.ENCRYPT_MODE, key(alias))
        }
        val body = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        PREFIX + Base64.encodeToString(cipher.iv + body, Base64.NO_WRAP)
    } catch (_: Exception) {
        plaintext
    }

    /**
     * Reverses [seal]. Values written before this was introduced were stored in the clear and
     * carry no [PREFIX], so they are returned as-is and re-sealed the next time they're saved.
     */
    fun open(stored: String?, alias: String = DEFAULT_ALIAS): String? {
        if (stored.isNullOrEmpty()) return stored
        if (!stored.startsWith(PREFIX)) return stored
        return try {
            val raw = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            if (raw.size <= IV_BYTES) return null
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(
                    Cipher.DECRYPT_MODE, key(alias),
                    GCMParameterSpec(GCM_TAG_BITS, raw.copyOfRange(0, IV_BYTES))
                )
            }
            String(cipher.doFinal(raw.copyOfRange(IV_BYTES, raw.size)), Charsets.UTF_8)
        } catch (_: Exception) {
            // Keystore key gone (data cleared, device restore) — the secret is unrecoverable.
            null
        }
    }

    private fun key(alias: String): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKey()
    }

    /** Marks a value as sealed, so older plaintext values are still readable after an upgrade. */
    private const val PREFIX = "v1:"
}
