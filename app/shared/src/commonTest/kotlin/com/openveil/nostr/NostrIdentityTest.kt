package com.openveil.nostr

import com.openveil.crypto.hexToBytes
import fr.acinq.secp256k1.Secp256k1
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NostrIdentityTest {

    /** Arbitrary but valid secp256k1 scalar. Test-only; never used to publish anything. */
    private val testKey = "0000000000000000000000000000000000000000000000000000000000000001"

    @Test
    fun derives_a_32_byte_x_only_public_key() {
        val identity = NostrIdentity(testKey.hexToBytes())
        assertEquals(32, identity.publicKey.size)
        assertEquals(64, identity.publicKeyHex.length)
        assertTrue(identity.npub.startsWith("npub1"))
    }

    @Test
    fun signature_verifies_against_the_derived_public_key() {
        val identity = NostrIdentity(testKey.hexToBytes())
        val message = com.openveil.crypto.sha256("openveil".encodeToByteArray())

        val signature = identity.sign(message)

        assertEquals(64, signature.size, "BIP-340 signatures are 64 bytes")
        assertTrue(
            Secp256k1.verifySchnorr(signature, message, identity.publicKey),
            "signature must verify against the x-only pubkey we publish",
        )
    }

    /**
     * The end-to-end property that matters: a relay recomputes the id from the event's own
     * fields and checks the signature against it. If our id derivation and our signing
     * disagree, this is where it shows up.
     */
    @Test
    fun signed_event_id_and_signature_are_mutually_consistent() {
        val identity = NostrIdentity(testKey.hexToBytes())
        val tags = listOf(
            listOf("url", "https://blossom.band/x.jpg"),
            listOf("m", "image/jpeg"),
        )

        val event = identity.signEvent(
            kind = 1063,
            content = "Captured with OpenVeil",
            tags = tags,
            createdAt = 1723456789L,
        )

        val recomputed = computeEventId(
            event.pubkey, event.createdAt, event.kind, event.tags, event.content,
        )
        assertEquals(recomputed, event.id, "id must be reproducible from the event's fields")
        assertTrue(
            Secp256k1.verifySchnorr(event.sig.hexToBytes(), event.id.hexToBytes(), identity.publicKey),
            "signature must verify over the event id",
        )
    }

    @Test
    fun tampering_with_content_invalidates_the_signature() {
        val identity = NostrIdentity(testKey.hexToBytes())
        val event = identity.signEvent(1, "original", emptyList(), 1L)

        val tamperedId = computeEventId(event.pubkey, event.createdAt, event.kind, event.tags, "tampered")

        assertTrue(tamperedId != event.id)
        assertTrue(
            !Secp256k1.verifySchnorr(event.sig.hexToBytes(), tamperedId.hexToBytes(), identity.publicKey),
            "a signature must not verify over a different event id",
        )
    }

    @Test
    fun rejects_invalid_private_keys() {
        assertFailsWith<IllegalArgumentException> { NostrIdentity(ByteArray(31)) }
        assertFailsWith<IllegalArgumentException> { NostrIdentity(ByteArray(32)) } // zero scalar
    }

    @Test
    fun toString_does_not_leak_the_private_key() {
        val identity = NostrIdentity(testKey.hexToBytes())
        val rendered = identity.toString()
        assertTrue(!rendered.contains(testKey), "private key must never appear in toString()")
        assertTrue(!rendered.contains("0000000000"), "private key material must not leak")
    }
}
