package com.openveil.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.openveil.domain.service.Preferences
import com.openveil.domain.service.SecureStorage
import com.openveil.domain.service.SecureStorageUnreadable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Secrets wrapped by a hardware-backed AES key held in the Android Keystore.
 *
 * The wrapping key is generated inside the Keystore and is not extractable: only its
 * handle is available to the app, so the ciphertext sitting in SharedPreferences is
 * useless without the device. Copying the app's data directory off the phone does not
 * yield the Nostr private key.
 *
 * `androidx.security:security-crypto` (EncryptedSharedPreferences) would cover this, but
 * it has been parked in alpha for years; this is a small amount of code against a stable
 * platform API instead.
 */
class AndroidSecureStorage(
    context: Context,
    private val keyAlias: String = "openveil.master",
) : SecureStorage {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("openveil.secure", Context.MODE_PRIVATE)

    override suspend fun putBytes(key: String, value: ByteArray) = withContext(Dispatchers.IO) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, wrappingKey())
        }
        val ciphertext = cipher.doFinal(value)
        // The GCM IV must be stored with the ciphertext, and must never be reused with the
        // same key -- the Cipher generates a fresh one per encryption, so it is recorded
        // alongside rather than fixed anywhere.
        val packed = cipher.iv + ciphertext
        prefs.edit().putString(key, encodeBase64(packed)).commit()
        Unit
    }

    override suspend fun getBytes(key: String): ByteArray? = withContext(Dispatchers.IO) {
        val stored = prefs.getString(key, null) ?: return@withContext null
        try {
            val packed = decodeBase64(stored)
            val iv = packed.copyOfRange(0, GCM_IV_LENGTH)
            val ciphertext = packed.copyOfRange(GCM_IV_LENGTH, packed.size)
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            }.doFinal(ciphertext)
        } catch (t: Throwable) {
            // Ciphertext is present but will not decrypt -- the Keystore key it was sealed
            // with is gone or changed. Reporting null here would read as "nothing stored"
            // and the caller would generate a replacement over the top of a secret that
            // might still be recoverable. Fail loudly instead.
            throw SecureStorageUnreadable(key, t)
        }
    }

    override suspend fun remove(key: String) = withContext(Dispatchers.IO) {
        prefs.edit().remove(key).commit()
        Unit
    }

    override suspend fun contains(key: String): Boolean = withContext(Dispatchers.IO) {
        prefs.contains(key)
    }

    /** Fetches the Keystore wrapping key, creating it on first use. */
    private fun wrappingKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // Deliberately NOT requiring user authentication: publishing must work
                // from a background retry after the screen has locked, and the threat
                // being defended against here is offline extraction of app data, not an
                // attacker holding an unlocked phone.
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encodeBase64(bytes: ByteArray): String =
        android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)

    private fun decodeBase64(value: String): ByteArray =
        android.util.Base64.decode(value, android.util.Base64.NO_WRAP)

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_LENGTH = 12
        const val GCM_TAG_BITS = 128
    }
}

/** Ordinary preferences. Never used for secrets -- that is what [SecureStorage] is for. */
class AndroidPreferences(context: Context) : Preferences {
    private val prefs =
        context.applicationContext.getSharedPreferences("openveil.prefs", Context.MODE_PRIVATE)

    override suspend fun putBoolean(key: String, value: Boolean) = withContext(Dispatchers.IO) {
        prefs.edit().putBoolean(key, value).commit()
        Unit
    }

    override suspend fun getBoolean(key: String, default: Boolean): Boolean =
        withContext(Dispatchers.IO) { prefs.getBoolean(key, default) }
}
