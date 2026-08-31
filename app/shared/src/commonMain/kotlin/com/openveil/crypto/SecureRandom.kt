package com.openveil.crypto

/**
 * Cryptographically secure random bytes.
 *
 * Deliberately an expect/actual over each platform's CSPRNG rather than
 * `kotlin.random.Random`, which is a deterministic PRNG seeded from the clock and would
 * make generated private keys predictable.
 */
expect fun secureRandomBytes(size: Int): ByteArray

/**
 * A uniformly random, valid secp256k1 private key.
 *
 * Valid scalars are in [1, n-1]. Random 32-byte strings are overwhelmingly likely to
 * qualify, but "overwhelmingly likely" is not "always", so the caller-supplied validity
 * check decides and we resample rather than clamping. Clamping would bias the key space.
 */
fun generatePrivateKey(isValid: (ByteArray) -> Boolean): ByteArray {
    repeat(16) {
        val candidate = secureRandomBytes(32)
        if (isValid(candidate)) return candidate
    }
    error("could not generate a valid secp256k1 key after 16 attempts")
}
