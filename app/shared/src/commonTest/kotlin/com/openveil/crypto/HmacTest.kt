package com.openveil.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * HMAC and HKDF against their RFC test vectors. NIP-44 derives every conversation key
 * and MAC from these, so a one-bit error here means no bunker can talk to us.
 */
class HmacTest {

    // RFC 4231, test case 2.
    @Test
    fun hmac_sha256_matches_rfc4231_short_key() {
        val mac = hmacSha256("Jefe".encodeToByteArray(), "what do ya want for nothing?".encodeToByteArray())
        assertEquals("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843", mac.toHex())
    }

    // RFC 4231, test case 6: a key longer than the block size is hashed first.
    @Test
    fun hmac_sha256_hashes_keys_longer_than_the_block() {
        val key = ByteArray(131) { 0xaa.toByte() }
        val mac = hmacSha256(key, "Test Using Larger Than Block-Size Key - Hash Key First".encodeToByteArray())
        assertEquals("60e431591ee0b67f0d8a26aacbf5b77f8e0bc6213728c5140546040f0ee37f54", mac.toHex())
    }

    // RFC 5869, test case 1.
    @Test
    fun hkdf_matches_rfc5869_basic_case() {
        val ikm = "0b".repeat(22).hexToBytes()
        val salt = "000102030405060708090a0b0c".hexToBytes()
        val info = "f0f1f2f3f4f5f6f7f8f9".hexToBytes()

        val prk = hkdfExtract(salt, ikm)
        assertEquals("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5", prk.toHex())

        val okm = hkdfExpand(prk, info, 42)
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            okm.toHex(),
        )
    }

    // RFC 5869, test case 2: output spans several HMAC blocks, exercising the counter.
    @Test
    fun hkdf_expand_chains_blocks_for_long_output() {
        val ikm = (0x00..0x4f).map { it.toByte() }.toByteArray()
        val salt = (0x60..0xaf).map { it.toByte() }.toByteArray()
        val info = (0xb0..0xff).map { it.toByte() }.toByteArray()

        val okm = hkdfExpand(hkdfExtract(salt, ikm), info, 82)

        assertEquals(
            "b11e398dc80327a1c8e7f78c596a49344f012eda2d4efad8a050cc4c19afa97c" +
                "59045a99cac7827271cb41c65e590e09da3275600c2f09b8367793a9aca3db71" +
                "cc30c58179ec3e87c14c01d5c1f3434f1d87",
            okm.toHex(),
        )
    }

    @Test
    fun hkdf_expand_rejects_impossible_lengths() {
        val prk = ByteArray(32)
        assertFailsWith<IllegalArgumentException> { hkdfExpand(prk, ByteArray(0), 0) }
        assertFailsWith<IllegalArgumentException> { hkdfExpand(prk, ByteArray(0), 255 * 32 + 1) }
        assertEquals(255 * 32, hkdfExpand(prk, ByteArray(0), 255 * 32).size)
    }

    @Test
    fun constant_time_equals_compares_content_not_identity() {
        val a = byteArrayOf(1, 2, 3)
        assertTrue(constantTimeEquals(a, byteArrayOf(1, 2, 3)))
        assertFalse(constantTimeEquals(a, byteArrayOf(1, 2, 4)))
        assertFalse(constantTimeEquals(a, byteArrayOf(1, 2)))
        assertTrue(constantTimeEquals(ByteArray(0), ByteArray(0)))
        assertContentEquals(byteArrayOf(1, 2, 3), a, "the comparison must not mutate its input")
    }
}
