package com.openveil.nostr

import com.openveil.testing.fixturePhoto
import com.openveil.testing.freshIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Relay-hint selection for shareable identifiers. The encoding itself is covered by
 * Nip19Test against reference vectors; this pins down which relays get named.
 */
class PhotoLinksTest {

    private val pubkey = freshIdentity().publicKeyHex
    private val eventId = "e1".repeat(32)
    private val fallback = listOf("wss://a.example", "wss://b.example", "wss://c.example", "wss://d.example")

    private fun published(accepted: List<String>) = fixturePhoto().copy(
        nostrEventId = eventId,
        nostrPubkey = pubkey,
        acceptedRelays = accepted,
    )

    @Test
    fun an_unpublished_photo_has_no_event_link() {
        val photo = fixturePhoto().copy(nostrPubkey = pubkey)

        assertNull(photo.nevent(fallback))
        assertNull(photo.nostrEventLink())
    }

    @Test
    fun a_photo_without_a_pubkey_has_no_profile_link() {
        assertNull(fixturePhoto().nprofile(fallback))
        assertNull(fixturePhoto().nostrProfileLink())
    }

    @Test
    fun hints_are_the_relays_that_actually_accepted_the_event() {
        val photo = published(listOf("wss://x.example", "wss://y.example"))

        assertEquals(
            Nip19.nevent(eventId, listOf("wss://x.example", "wss://y.example"), pubkey, KIND_FILE_METADATA),
            photo.nevent(fallback),
        )
    }

    @Test
    fun hints_fall_back_to_the_configured_relays_for_photos_with_no_recorded_outcome() {
        val photo = published(accepted = emptyList())

        assertEquals(
            Nip19.nevent(eventId, fallback.take(3), pubkey, KIND_FILE_METADATA),
            photo.nevent(fallback),
        )
    }

    @Test
    fun at_most_three_relays_are_named() {
        val many = (1..6).map { "wss://r$it.example" }

        assertEquals(
            Nip19.nevent(eventId, many.take(3), pubkey, KIND_FILE_METADATA),
            published(many).nevent(fallback),
        )
        assertEquals(Nip19.nprofile(pubkey, many.take(3)), published(many).nprofile(many))
    }

    @Test
    fun links_are_the_identifier_under_the_chosen_host() {
        val photo = published(listOf("wss://x.example"))

        assertEquals("${NostrLinks.DEFAULT_HOST}/${photo.nevent()}", photo.nostrEventLink())
        assertEquals("https://other.example/${photo.nevent()}", photo.nostrEventLink(host = "https://other.example"))
        assertTrue(photo.nostrProfileLink()!!.endsWith(photo.nprofile()!!))
    }
}
