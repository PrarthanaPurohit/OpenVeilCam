package com.openveil.crypto

/** "expand 32-byte k", as four little-endian words. */
private val CHACHA_CONSTANTS = intArrayOf(0x61707865, 0x3320646e, 0x79622d32, 0x6b206574)

/**
 * ChaCha20 stream cipher, IETF variant (RFC 8439: 96-bit nonce, 32-bit block counter).
 *
 * Pure Kotlin, in common code, so NIP-44 has no per-platform crypto surface to keep in
 * sync. Encryption and decryption are the same XOR, so there is one function.
 *
 * This is the raw stream cipher with no authentication. NIP-44 layers its own HMAC over
 * the ciphertext; callers must never use this on its own for anything that is not
 * separately authenticated.
 */
fun chacha20(key: ByteArray, nonce: ByteArray, input: ByteArray, initialCounter: Int = 0): ByteArray {
    require(key.size == 32) { "ChaCha20 key must be 32 bytes, was ${key.size}" }
    require(nonce.size == 12) { "ChaCha20 (IETF) nonce must be 12 bytes, was ${nonce.size}" }

    val state = IntArray(16)
    CHACHA_CONSTANTS.copyInto(state, 0)
    for (i in 0 until 8) state[4 + i] = key.littleEndianInt(i * 4)
    state[12] = initialCounter
    for (i in 0 until 3) state[13 + i] = nonce.littleEndianInt(i * 4)

    val output = ByteArray(input.size)
    val working = IntArray(16)
    val keystream = ByteArray(64)
    var position = 0

    while (position < input.size) {
        state.copyInto(working)
        repeat(10) {
            // Column round.
            quarterRound(working, 0, 4, 8, 12)
            quarterRound(working, 1, 5, 9, 13)
            quarterRound(working, 2, 6, 10, 14)
            quarterRound(working, 3, 7, 11, 15)
            // Diagonal round.
            quarterRound(working, 0, 5, 10, 15)
            quarterRound(working, 1, 6, 11, 12)
            quarterRound(working, 2, 7, 8, 13)
            quarterRound(working, 3, 4, 9, 14)
        }
        for (i in 0 until 16) {
            val word = working[i] + state[i]
            keystream[i * 4] = word.toByte()
            keystream[i * 4 + 1] = (word ushr 8).toByte()
            keystream[i * 4 + 2] = (word ushr 16).toByte()
            keystream[i * 4 + 3] = (word ushr 24).toByte()
        }
        state[12]++

        val n = minOf(64, input.size - position)
        for (i in 0 until n) {
            output[position + i] = (input[position + i].toInt() xor keystream[i].toInt()).toByte()
        }
        position += n
    }
    return output
}

private fun quarterRound(s: IntArray, a: Int, b: Int, c: Int, d: Int) {
    s[a] += s[b]; s[d] = (s[d] xor s[a]).rotateLeft(16)
    s[c] += s[d]; s[b] = (s[b] xor s[c]).rotateLeft(12)
    s[a] += s[b]; s[d] = (s[d] xor s[a]).rotateLeft(8)
    s[c] += s[d]; s[b] = (s[b] xor s[c]).rotateLeft(7)
}

private fun ByteArray.littleEndianInt(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or
        ((this[offset + 1].toInt() and 0xFF) shl 8) or
        ((this[offset + 2].toInt() and 0xFF) shl 16) or
        ((this[offset + 3].toInt() and 0xFF) shl 24)
