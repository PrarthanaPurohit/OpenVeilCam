package com.openveil.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EncodingTest {

    @Test
    fun sha256_matches_nist_vectors() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            sha256Hex(ByteArray(0)),
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256Hex("abc".encodeToByteArray()),
        )
    }

    @Test
    fun hex_round_trips() {
        val bytes = ByteArray(256) { it.toByte() }
        assertContentEquals(bytes, bytes.toHex().hexToBytes())
    }

    @Test
    fun hex_encodes_lowercase_and_pads_single_digits() {
        assertEquals("000102ff", byteArrayOf(0, 1, 2, -1).toHex())
    }

    @Test
    fun hex_accepts_uppercase() {
        assertContentEquals(byteArrayOf(-34, -83, -66, -17), "DEADBEEF".hexToBytes())
    }

    @Test
    fun hex_rejects_malformed_input() {
        assertFailsWith<IllegalArgumentException> { "abc".hexToBytes() }
        assertFailsWith<IllegalArgumentException> { "zz".hexToBytes() }
    }

    /**
     * RFC 4648 test vectors, with padding stripped. Blossom's BUD-11 Authorization header
     * requires base64url *without* padding -- a stray '=' is rejected by the server as an
     * opaque 401, so the no-padding property is asserted explicitly.
     */
    @Test
    fun base64url_matches_rfc4648_without_padding() {
        assertEquals("", ByteArray(0).toBase64UrlNoPad())
        assertEquals("Zg", "f".encodeToByteArray().toBase64UrlNoPad())
        assertEquals("Zm8", "fo".encodeToByteArray().toBase64UrlNoPad())
        assertEquals("Zm9v", "foo".encodeToByteArray().toBase64UrlNoPad())
        assertEquals("Zm9vYg", "foob".encodeToByteArray().toBase64UrlNoPad())
        assertEquals("Zm9vYmE", "fooba".encodeToByteArray().toBase64UrlNoPad())
        assertEquals("Zm9vYmFy", "foobar".encodeToByteArray().toBase64UrlNoPad())
        assertEquals("AAECAwQ", byteArrayOf(0, 1, 2, 3, 4).toBase64UrlNoPad())
    }

    @Test
    fun base64url_never_emits_padding_or_unsafe_characters() {
        for (len in 0..64) {
            val encoded = ByteArray(len) { (it * 7).toByte() }.toBase64UrlNoPad()
            assertEquals(-1, encoded.indexOf('='), "padding leaked at length $len")
            assertEquals(-1, encoded.indexOf('+'), "non-url character at length $len")
            assertEquals(-1, encoded.indexOf('/'), "non-url character at length $len")
        }
    }

    /**
     * Cross-checked against the reference BIP-173 implementation, and the result is a
     * widely published key (fiatjaf's), so this pins the encoder to a real-world value
     * rather than to our own output.
     */
    @Test
    fun npub_matches_known_published_key() {
        val pubkeyHex = "3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d"
        assertEquals(
            "npub180cvv07tjdrrgpa0j7j7tmnyl2yr6yr7l8j4s3evf6u64th6gkwsyjh6w6",
            encodeNpub(pubkeyHex.hexToBytes()),
        )
    }

    @Test
    fun npub_rejects_wrong_key_length() {
        assertFailsWith<IllegalArgumentException> { encodeNpub(ByteArray(31)) }
        assertFailsWith<IllegalArgumentException> { encodeNpub(ByteArray(33)) }
    }
}
