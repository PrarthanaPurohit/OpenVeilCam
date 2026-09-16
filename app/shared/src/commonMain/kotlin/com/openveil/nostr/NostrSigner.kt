package com.openveil.nostr

/**
 * Something that can sign a Nostr event on behalf of a public key.
 *
 * The device's own [NostrIdentity] holds a private key and signs in memory. A linked
 * account signs through a NIP-46 remote signer, where the key lives in another app and
 * every signature is a network round trip the user may have to approve. Both look the
 * same from the publish pipeline, which is the point: the pipeline never learns which
 * kind it has and so can never be tempted to reach for a private key.
 */
interface NostrSigner {
    /** x-only public key, lowercase hex, of whoever will sign. */
    val publicKeyHex: String
    val npub: String

    /**
     * Signs an unsigned event, filling in id, pubkey and sig.
     *
     * Suspends because a remote signer may take seconds -- or a user's tap -- to answer.
     * Throws on failure rather than returning null, so a caller cannot mistake "no
     * signature" for an event.
     */
    suspend fun signEvent(
        kind: Int,
        content: String,
        tags: List<List<String>>,
        createdAt: Long,
    ): NostrEvent
}

/** The device key as a [NostrSigner]. Signing is local and immediate. */
fun NostrIdentity.asSigner(): NostrSigner = LocalSigner(this)

private class LocalSigner(private val identity: NostrIdentity) : NostrSigner {
    override val publicKeyHex: String get() = identity.publicKeyHex
    override val npub: String get() = identity.npub

    override suspend fun signEvent(
        kind: Int,
        content: String,
        tags: List<List<String>>,
        createdAt: Long,
    ): NostrEvent = identity.signEvent(kind, content, tags, createdAt)
}
