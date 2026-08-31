package com.openveil.nostr

import com.openveil.crypto.encodeNpub
import com.openveil.crypto.hexToBytes
import com.openveil.crypto.toHex
import fr.acinq.secp256k1.Secp256k1

/**
 * The device's Nostr identity.
 *
 * The private key never leaves the device: it is generated locally, held only here in
 * memory, and persisted through SecureStorage (Android Keystore-wrapped). It is never
 * logged, never sent anywhere, and never written to any remote store.
 *
 * Deliberately not a data class -- a generated toString() would print the private key.
 */
class NostrIdentity(private val privateKey: ByteArray) {

    init {
        require(privateKey.size == 32) { "private key must be 32 bytes" }
        require(Secp256k1.secKeyVerify(privateKey)) { "not a valid secp256k1 private key" }
    }

    /**
     * x-only public key: the 32-byte X coordinate. `pubkeyCreate` returns the 65-byte
     * uncompressed form (0x04 || X || Y), and Nostr uses X alone.
     */
    val publicKey: ByteArray by lazy {
        Secp256k1.pubkeyCreate(privateKey).copyOfRange(1, 33)
    }

    val publicKeyHex: String by lazy { publicKey.toHex() }

    val npub: String by lazy { encodeNpub(publicKey) }

    /**
     * BIP-340 Schnorr signature over a 32-byte message (the event id).
     *
     * `auxrand32` is null, which makes signatures deterministic. BIP-340 permits this and
     * it makes the pipeline reproducible; it is not a security weakness the way reusing a
     * nonce in ECDSA would be.
     */
    fun sign(message32: ByteArray): ByteArray {
        require(message32.size == 32) { "Schnorr message must be 32 bytes" }
        return Secp256k1.signSchnorr(message32, privateKey, null)
    }

    /** Signs an unsigned event, filling in id and sig. */
    fun signEvent(
        kind: Int,
        content: String,
        tags: List<List<String>>,
        createdAt: Long,
    ): NostrEvent {
        val id = computeEventId(publicKeyHex, createdAt, kind, tags, content)
        val sig = sign(id.hexToBytes()).toHex()
        return NostrEvent(
            id = id,
            pubkey = publicKeyHex,
            createdAt = createdAt,
            kind = kind,
            tags = tags,
            content = content,
            sig = sig,
        )
    }

    /** Only for handing the key to SecureStorage. Never log or transmit the result. */
    internal fun exportPrivateKey(): ByteArray = privateKey.copyOf()

    override fun toString(): String = "NostrIdentity(npub=$npub)"
}
