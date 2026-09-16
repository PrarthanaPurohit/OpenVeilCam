package com.openveil.crypto

private const val SHA256_BLOCK_SIZE = 64

/**
 * HMAC-SHA256 (RFC 2104) over the platform [sha256].
 *
 * Hand-rolled because it is twelve lines on top of a hash we already have, and the
 * alternative is an expect/actual pair per platform for something with no platform-specific
 * behaviour. Used by NIP-44 for both key derivation and the authentication tag.
 */
fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray {
    val shortKey = if (key.size > SHA256_BLOCK_SIZE) sha256(key) else key
    val padded = shortKey.copyOf(SHA256_BLOCK_SIZE)
    val inner = ByteArray(SHA256_BLOCK_SIZE) { (padded[it].toInt() xor 0x36).toByte() }
    val outer = ByteArray(SHA256_BLOCK_SIZE) { (padded[it].toInt() xor 0x5c).toByte() }
    return sha256(outer + sha256(inner + message))
}

/** HKDF-Extract (RFC 5869) with SHA-256: `HMAC(salt, ikm)`. */
fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray = hmacSha256(salt, ikm)

/** HKDF-Expand (RFC 5869) with SHA-256, producing exactly [length] bytes. */
fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
    require(length in 1..(255 * 32)) { "HKDF output length out of range: $length" }
    val out = ByteArray(length)
    var previous = ByteArray(0)
    var written = 0
    var counter = 1
    while (written < length) {
        previous = hmacSha256(prk, previous + info + byteArrayOf(counter.toByte()))
        val n = minOf(previous.size, length - written)
        previous.copyInto(out, written, 0, n)
        written += n
        counter++
    }
    return out
}

/**
 * Constant-time byte comparison for MACs.
 *
 * `contentEquals` returns on the first differing byte, which leaks the position of the
 * first mismatch through timing. That is the classic MAC-forgery oracle, so authentication
 * tags are compared here instead.
 */
fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
    if (a.size != b.size) return false
    var diff = 0
    for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
    return diff == 0
}
