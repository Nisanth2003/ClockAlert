package com.example.alarmtracker.friends

import android.util.Base64
import com.example.alarmtracker.util.SecretBox
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom

/**
 * Everything cryptographic about a friend pairing, in one place.
 *
 * The relay is treated as hostile by design. It only ever sees base64 blobs, so it cannot learn
 * where anyone is even though the public ntfy server is unauthenticated and its topics are readable
 * by anyone who knows the name. AES-256-GCM gives us both halves of what that requires:
 *  - confidentiality, so a stranger on the topic can't read positions, and
 *  - authenticity, so they can't FORGE one either. That second half matters as much as the first:
 *    without it someone who learned the topic could tell you your friend is around the corner.
 *
 * Two layers of key:
 *  - the PAIR key, 256 random bits generated once and carried to the other phone inside the invite.
 *    Both friends hold it; nothing else ever does.
 *  - the WRAPPING key, non-exportable and held by the Android Keystore, used to encrypt the pair key
 *    before it is written to the database. Lifting the DB off the device yields nothing usable.
 */
object PairCrypto {

    private const val WRAP_KEY_ALIAS = "alarmtracker_friend_wrap"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12
    private const val PAIR_KEY_BYTES = 32
    private const val TOPIC_BYTES = 18

    private val random = SecureRandom()

    // ---- Pairing material ----

    /**
     * A fresh relay topic. Long and random because on a public ntfy server the topic name is the
     * only thing standing between this channel and anyone who wants to listen to it.
     */
    fun newTopic(): String = "at_" + urlSafe(randomBytes(TOPIC_BYTES))

    /** A fresh 256-bit pair secret, base64. */
    fun newPairKey(): String = Base64.encodeToString(randomBytes(PAIR_KEY_BYTES), Base64.NO_WRAP)

    /** A stable per-device id so each side can recognise and skip its own messages on the topic. */
    fun newDeviceId(): String = urlSafe(randomBytes(8))

    // ---- Message encryption with the pair key ----

    /** Encrypts [plaintext] with the pair key. Output is `base64(iv || ciphertext||tag)`. */
    fun seal(pairKeyB64: String, plaintext: String): String {
        val key = SecretKeySpec(Base64.decode(pairKeyB64, Base64.NO_WRAP), "AES")
        val iv = randomBytes(IV_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        }
        val body = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + body, Base64.NO_WRAP)
    }

    /**
     * Decrypts a [seal]ed payload, or returns null if it wasn't produced by the holder of this pair
     * key — a wrong key, a truncated blob or a tampered one all fail the GCM tag and land here.
     * Callers must treat null as "not from my friend" and drop the message.
     */
    fun open(pairKeyB64: String, sealedB64: String): String? = try {
        val key = SecretKeySpec(Base64.decode(pairKeyB64, Base64.NO_WRAP), "AES")
        val raw = Base64.decode(sealedB64, Base64.NO_WRAP)
        if (raw.size <= IV_BYTES) {
            null
        } else {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(
                    Cipher.DECRYPT_MODE, key,
                    GCMParameterSpec(GCM_TAG_BITS, raw.copyOfRange(0, IV_BYTES))
                )
            }
            String(cipher.doFinal(raw.copyOfRange(IV_BYTES, raw.size)), Charsets.UTF_8)
        }
    } catch (_: Exception) {
        null
    }

    // ---- Keystore wrapping for storage at rest ----

    /** Wraps a pair key for storage under its own Keystore alias. See [SecretBox]. */
    fun wrapForStorage(pairKeyB64: String): String = SecretBox.seal(pairKeyB64, WRAP_KEY_ALIAS)

    /** Unwraps a stored pair key, or null if the Keystore key is gone (app data cleared, restore). */
    fun unwrapFromStorage(wrapped: String): String? = SecretBox.open(wrapped, WRAP_KEY_ALIAS)

    private fun randomBytes(count: Int): ByteArray = ByteArray(count).also { random.nextBytes(it) }

    private fun urlSafe(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
}
