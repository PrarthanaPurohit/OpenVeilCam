package com.openveil.nostr.nip55

import com.openveil.crypto.decodeNpub
import com.openveil.crypto.toHex
import com.openveil.nostr.NostrEvent

/**
 * A signer application installed on this device (NIP-55: Amber and compatibles on
 * Android).
 *
 * The platform layer implements this; common code only ever sees the interface. Nothing
 * about it is Android-specific in shape, but there is no such thing on iOS today, so
 * platforms without a signer app pass null and the option simply does not appear.
 */
interface ExternalSignerApp {
    /** True when at least one app on the device answers the `nostrsigner:` scheme. */
    val isInstalled: Boolean

    /**
     * Asks the signer which account to use and which package answered. Opens the signer's
     * UI. [permissions] are pre-authorisations the user can grant so later signatures can
     * happen without a prompt.
     */
    suspend fun getPublicKey(permissions: List<SignerPermission>): ExternalSignerAccount

    /**
     * Signs through the signer app. Tries silently first (only works for permissions the
     * user chose to remember), then opens the signer UI. The result is verified locally
     * before it is returned.
     */
    suspend fun signEvent(
        kind: Int,
        content: String,
        tags: List<List<String>>,
        createdAt: Long,
        account: ExternalSignerAccount,
    ): NostrEvent
}

/** `sign_event` for one kind, or another method by name. */
data class SignerPermission(val type: String, val kind: Int? = null)

data class ExternalSignerAccount(
    /** x-only hex. */
    val userPubkeyHex: String,
    /** The signer app's package; every later request is addressed to it. */
    val packageName: String,
)

class ExternalSignerException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Amber answers `get_public_key` with an npub; the spec says hex. Accept both. */
fun normalizeSignerPubkey(raw: String?): String? {
    val value = raw?.trim() ?: return null
    return when {
        value.startsWith("npub1", ignoreCase = true) ->
            runCatching { decodeNpub(value).toHex() }.getOrNull()
        HEX64.matches(value.lowercase()) -> value.lowercase()
        else -> null
    }
}

private val HEX64 = Regex("^[0-9a-f]{64}$")
