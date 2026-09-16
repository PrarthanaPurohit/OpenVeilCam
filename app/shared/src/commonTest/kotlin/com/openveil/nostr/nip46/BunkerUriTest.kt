package com.openveil.nostr.nip46

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class BunkerUriTest {

    private val pubkey = "3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d"

    @Test
    fun parses_relays_and_secret() {
        val uri = BunkerUri.parse(
            "bunker://$pubkey?relay=wss%3A%2F%2Frelay.nsec.app&relay=wss://relay.damus.io&secret=s3cret"
        )
        assertEquals(pubkey, uri.remoteSignerPubkey)
        assertEquals(listOf("wss://relay.nsec.app", "wss://relay.damus.io"), uri.relays)
        assertEquals("s3cret", uri.secret)
    }

    @Test
    fun secret_is_optional_and_whitespace_is_tolerated() {
        val uri = BunkerUri.parse("  bunker://${pubkey.uppercase()}?relay=wss://relay.nsec.app \n")
        assertEquals(pubkey, uri.remoteSignerPubkey, "pubkey is normalised to lowercase")
        assertNull(uri.secret)
    }

    @Test
    fun rejects_other_schemes() {
        assertFailsWith<IllegalArgumentException> { BunkerUri.parse("nostrconnect://$pubkey?relay=wss://x") }
        assertFailsWith<IllegalArgumentException> { BunkerUri.parse("nsec1qqqqqqqq") }
    }

    @Test
    fun rejects_a_bad_pubkey() {
        assertFailsWith<IllegalArgumentException> { BunkerUri.parse("bunker://abc?relay=wss://x") }
        assertFailsWith<IllegalArgumentException> { BunkerUri.parse("bunker://npub1abc?relay=wss://x") }
    }

    @Test
    fun rejects_missing_or_non_websocket_relays() {
        assertFailsWith<IllegalArgumentException> { BunkerUri.parse("bunker://$pubkey") }
        assertFailsWith<IllegalArgumentException> { BunkerUri.parse("bunker://$pubkey?secret=x") }
        assertFailsWith<IllegalArgumentException> { BunkerUri.parse("bunker://$pubkey?relay=https://relay.example") }
    }
}
