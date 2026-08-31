package com.openveil.nostr

import com.openveil.crypto.generatePrivateKey
import com.openveil.domain.service.SecureStorage
import fr.acinq.secp256k1.Secp256k1
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns the device's Nostr identity: generates it once, then loads it from secure storage.
 *
 * The identity is this phone's own. It is generated here and never leaves -- there is no
 * import, export, backup or sync path in this build, which keeps the number of ways a
 * private key can escape the device at zero.
 */
class NostrIdentityRepository(
    private val secureStorage: SecureStorage,
) {
    private val mutex = Mutex()
    private var cached: NostrIdentity? = null

    /**
     * Returns the device identity, creating it on first call.
     *
     * Guarded by a mutex so two concurrent callers on first launch cannot each generate a
     * key and race to persist -- which would leave the app publishing under one identity
     * while having stored another.
     */
    suspend fun getOrCreate(): NostrIdentity = mutex.withLock {
        cached?.let { return it }

        // A key is generated exactly once, and only when storage confirms none exists.
        // `getBytes` throws rather than returning null when a key is present but
        // unreadable, so that case propagates instead of silently minting a replacement
        // identity -- which would orphan every photo published under the old one.
        val stored = secureStorage.getBytes(KEY_NOSTR_PRIVATE)

        val identity = when {
            stored != null && stored.size == 32 -> NostrIdentity(stored)

            stored != null -> error(
                "the stored Nostr key is ${stored.size} bytes, not 32; refusing to " +
                    "overwrite it with a new identity"
            )

            else -> {
                val fresh = generatePrivateKey { Secp256k1.secKeyVerify(it) }
                secureStorage.putBytes(KEY_NOSTR_PRIVATE, fresh)
                NostrIdentity(fresh)
            }
        }
        cached = identity
        identity
    }

    /** The identity if one already exists, without creating one. */
    suspend fun peek(): NostrIdentity? = mutex.withLock {
        cached ?: secureStorage.getBytes(KEY_NOSTR_PRIVATE)
            ?.takeIf { it.size == 32 }
            ?.let { NostrIdentity(it).also { id -> cached = id } }
    }

    suspend fun exists(): Boolean = cached != null || secureStorage.contains(KEY_NOSTR_PRIVATE)

    private companion object {
        const val KEY_NOSTR_PRIVATE = "nostr.private_key"
    }
}
