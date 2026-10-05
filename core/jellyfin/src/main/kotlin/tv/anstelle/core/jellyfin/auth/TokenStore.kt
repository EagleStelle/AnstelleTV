package tv.anstelle.core.jellyfin.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import tv.anstelle.core.common.AppLog
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/** Jellyfin access tokens, keyed by Jellyfin server Id. Tokens never go into SQLite. */
interface TokenStore {
    fun get(jellyfinServerId: String): String?
    fun put(jellyfinServerId: String, token: String)
    fun remove(jellyfinServerId: String)
}

/**
 * Encrypts tokens with an AES-256-GCM key that lives in the Android Keystore and keeps
 * only the ciphertext in a private preferences file. `EncryptedSharedPreferences` is
 * deprecated, so this does the same job with platform APIs only.
 *
 * Some devices (Fire OS 6 among them) have flaky Keystore implementations. Any Keystore or
 * decryption failure drops the stored token, so the user is asked to sign in again
 * instead of the app crashing.
 */
@Singleton
class KeystoreTokenStore @Inject constructor(
    @ApplicationContext context: Context,
) : TokenStore {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Decrypted tokens, so tuning and sync never pay for a Keystore round trip. */
    private val cache = ConcurrentHashMap<String, String>()

    override fun get(jellyfinServerId: String): String? {
        cache[jellyfinServerId]?.let { return it }
        val stored = prefs.getString(jellyfinServerId, null) ?: return null
        return runCatching { decrypt(stored) }
            .onSuccess { cache[jellyfinServerId] = it }
            .onFailure {
                AppLog.w(TAG, "Stored token for $jellyfinServerId is unreadable; sign-in needed", it)
                remove(jellyfinServerId)
            }
            .getOrNull()
    }

    override fun put(jellyfinServerId: String, token: String) {
        cache[jellyfinServerId] = token
        runCatching { encrypt(token) }
            .onSuccess { prefs.edit { putString(jellyfinServerId, it) } }
            // The token still works for this process; it just won't survive a restart.
            .onFailure { AppLog.e(TAG, "Keystore unavailable; token kept in memory only", it) }
    }

    override fun remove(jellyfinServerId: String) {
        cache.remove(jellyfinServerId)
        prefs.edit { remove(jellyfinServerId) }
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(byteArrayOf(iv.size.toByte()) + iv + sealed, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        val ivLength = bytes[0].toInt()
        val iv = bytes.copyOfRange(1, 1 + ivLength)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        return String(cipher.doFinal(bytes, 1 + ivLength, bytes.size - 1 - ivLength), Charsets.UTF_8)
    }

    @Synchronized
    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val TAG = "TokenStore"
        const val PREFS_NAME = "jellyfin_tokens"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "anstelle.jellyfin.tokens"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BITS = 256
        const val TAG_BITS = 128
    }
}
