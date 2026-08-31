package com.openveil.nostr

import com.openveil.domain.model.Photo

/**
 * Turns a published [Photo] into identifiers and links other people can actually open.
 *
 * A bare event id or npub is not much use to a recipient: their client still has to find a
 * relay that holds the event. These carry relay hints, so the link resolves for someone
 * who has never heard of the relays this device happens to use.
 */

/**
 * Relay hints for a specific photo.
 *
 * Prefers the relays that actually accepted the event. Falling back to the configured set
 * is a guess, but a guess is better than an identifier with no hints at all -- and it only
 * happens for a photo published before relay outcomes were recorded.
 */
private fun Photo.relayHints(fallback: List<String>): List<String> =
    acceptedRelays.ifEmpty { fallback }.take(MAX_RELAY_HINTS)

/** NIP-19 `nevent` for this photo's file-metadata event, or null if it is unpublished. */
fun Photo.nevent(fallbackRelays: List<String> = NostrConfig().relayUrls): String? {
    val eventId = nostrEventId ?: return null
    return Nip19.nevent(
        eventIdHex = eventId,
        relays = relayHints(fallbackRelays),
        authorPubkeyHex = nostrPubkey,
        kind = KIND_FILE_METADATA,
    )
}

/** NIP-19 `nprofile` for the device that captured this photo. */
fun Photo.nprofile(relays: List<String> = NostrConfig().relayUrls): String? {
    val pubkey = nostrPubkey ?: return null
    return Nip19.nprofile(pubkey, relays.take(MAX_RELAY_HINTS))
}

/** A link to this photo that opens in a browser or hands off to any Nostr client. */
fun Photo.nostrEventLink(host: String = NostrLinks.DEFAULT_HOST): String? =
    nevent()?.let { NostrLinks.forEvent(it, host) }

/** A link to everything this device has published. */
fun Photo.nostrProfileLink(host: String = NostrLinks.DEFAULT_HOST): String? =
    nprofile()?.let { NostrLinks.forProfile(it, host) }

/**
 * Three relay hints is the point of diminishing returns: enough redundancy that one dead
 * relay does not break resolution, without producing an identifier too long to paste
 * comfortably into a message.
 */
private const val MAX_RELAY_HINTS = 3
