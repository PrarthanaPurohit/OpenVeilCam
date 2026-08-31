package com.openveil.crypto

/**
 * SHA-256 over the whole input.
 *
 * Used for two distinct things that must not be confused:
 *  - the Nostr event id (hash of the canonical serialization), and
 *  - the content hash of the signed image, which is published as NIP-94 `x` and must
 *    equal the hash of the exact bytes Blossom stores.
 */
expect fun sha256(bytes: ByteArray): ByteArray

/** Convenience: lowercase hex digest, the form both Nostr and Blossom expect. */
fun sha256Hex(bytes: ByteArray): String = sha256(bytes).toHex()
