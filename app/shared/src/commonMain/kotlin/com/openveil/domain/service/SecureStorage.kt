package com.openveil.domain.service

/**
 * Storage for secrets that must never leave the device.
 *
 * The only thing OpenVeil stores here is the Nostr private key. Implementations are
 * expected to wrap values with hardware-backed keys where the platform offers them, so
 * that reading the app's data directory is not enough to recover the secret.
 *
 * Nothing passed through this interface may be logged, sent to analytics, included in a
 * crash report, or written to any remote store.
 */
interface SecureStorage {
    suspend fun putBytes(key: String, value: ByteArray)

    /**
     * Returns the stored value, or null **only when nothing is stored under [key]**.
     *
     * A stored-but-unreadable value must throw [SecureStorageUnreadable] rather than
     * return null. Callers routinely treat null as "first run, create a new one", so
     * collapsing the two cases turns a recoverable read failure into silent, permanent
     * destruction of the secret.
     */
    suspend fun getBytes(key: String): ByteArray?

    suspend fun remove(key: String)
    suspend fun contains(key: String): Boolean
}

/**
 * A secret exists but could not be decrypted -- typically because the hardware-backed
 * wrapping key is gone (device restore, Keystore reset, or a credential change that
 * invalidated it).
 *
 * This is deliberately fatal rather than recoverable. The caller must not respond by
 * generating a replacement: for OpenVeil that would mint a new Nostr identity and orphan
 * everything the previous key ever published, with no way back.
 */
class SecureStorageUnreadable(
    key: String,
    cause: Throwable? = null,
) : Exception("a value is stored under '$key' but could not be decrypted", cause)

/** Non-secret, non-sensitive flags. Kept separate so the two never get confused. */
interface Preferences {
    suspend fun putBoolean(key: String, value: Boolean)
    suspend fun getBoolean(key: String, default: Boolean = false): Boolean
}
