package com.openveil.nostr

import com.openveil.crypto.encodeNpub
import com.openveil.crypto.hexToBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Expected strings come from an independent JavaScript implementation of NIP-19 written
 * against the spec text, not from this code. A test that only checks the encoder against
 * itself would pass just as happily on a wrong TLV layout.
 */
class Nip19Test {

    private val pubkeyHex = "d76a5ac24eb0f07a1efc84535c32bc16ef310645ed3e488477e6b24bf572bba2"
    private val eventIdHex = "70909249204a0b67729499232b1b7383a4ba30b4c6f6217ef1465100f1221450"

    @Test
    fun npub_matches_reference() {
        assertEquals(
            "npub16a494sjwkrc858hus3f4cv4uzmhnzpj9a5ly3prhu6eyhatjhw3q9wjcaa",
            encodeNpub(pubkeyHex.hexToBytes()),
        )
    }

    @Test
    fun nprofile_with_one_relay_matches_reference() {
        assertEquals(
            "nprofile1qqsdw6j6cf8tpur6rm7gg56ux27pdme3qez760jgs3m7dvjt74ethgs" +
                "pp4mhxue69uhkummn9ekx7mqvqeyn6",
            Nip19.nprofile(pubkeyHex, listOf("wss://nos.lol")),
        )
    }

    @Test
    fun nprofile_with_several_relays_matches_reference() {
        assertEquals(
            "nprofile1qqsdw6j6cf8tpur6rm7gg56ux27pdme3qez760jgs3m7dvjt74ethgs" +
                "pp4mhxue69uhkummn9ekx7mqpzemhxue69uhhyetvv9ujuurjd9kkzmpwdejhgqg0" +
                "waehxw309ahx7um5wghx6mmd3ea5wh",
            Nip19.nprofile(
                pubkeyHex,
                listOf("wss://nos.lol", "wss://relay.primal.net", "wss://nostr.mom"),
            ),
        )
    }

    @Test
    fun nevent_with_author_and_kind_matches_reference() {
        assertEquals(
            "nevent1qqs8pyyjfysy5zm8w22fjgetrdec8f96xz6vda3p0mc5v5gq7y3pg5q" +
                "pp4mhxue69uhkummn9ekx7mqzyrtk5kkzf6c0q7s7ljz9xhpjhstw7vgxghknujyy" +
                "wlntyjl4w2a6yqcyqqqqgfcfp3250",
            Nip19.nevent(eventIdHex, listOf("wss://nos.lol"), pubkeyHex, KIND_FILE_METADATA),
        )
    }

    /**
     * NIP-19 waives bech32's 90-character cap. Silently truncating or refusing a long
     * relay list would produce an identifier that decodes to the wrong thing.
     */
    @Test
    fun long_relay_lists_are_not_truncated() {
        val relays = listOf(
            "wss://relay.damus.io",
            "wss://nos.lol",
            "wss://relay.primal.net",
            "wss://relay.snort.social",
            "wss://nostr.mom",
        )
        val encoded = Nip19.nprofile(pubkeyHex, relays)
        assertTrue(encoded.length > 90, "expected a long identifier, got ${encoded.length} chars")
        assertTrue(encoded.startsWith("nprofile1"))
    }

    @Test
    fun rejects_a_relay_url_too_long_for_the_length_field() {
        // The TLV length field is one byte; anything longer would corrupt the entries that
        // follow it rather than fail loudly.
        val tooLong = "wss://" + "a".repeat(260)
        assertFailsWith<IllegalArgumentException> { Nip19.nprofile(pubkeyHex, listOf(tooLong)) }
    }

    @Test
    fun rejects_a_malformed_pubkey() {
        assertFailsWith<IllegalArgumentException> { Nip19.nprofile("abcd", emptyList()) }
    }

    @Test
    fun builds_a_browser_link_any_client_can_resolve() {
        val nevent = Nip19.nevent(eventIdHex, listOf("wss://nos.lol"), pubkeyHex, KIND_FILE_METADATA)
        assertEquals("https://njump.me/$nevent", NostrLinks.forEvent(nevent))
    }
}
