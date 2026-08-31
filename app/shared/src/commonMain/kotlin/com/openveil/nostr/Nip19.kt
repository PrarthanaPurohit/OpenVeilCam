package com.openveil.nostr

import com.openveil.crypto.Bech32
import com.openveil.crypto.hexToBytes

/**
 * NIP-19 shareable identifiers (`nprofile`, `nevent`).
 *
 * `npub` names a key and nothing else, which makes it a poor thing to hand someone: a
 * client that receives it still has to guess which relays to ask. `nprofile` and `nevent`
 * carry **relay hints** in a TLV payload, so any Nostr client can go straight to a relay
 * that actually holds the data. That is the difference between a link that resolves for
 * strangers and one that only resolves for people who happen to share your relay set.
 *
 * Payload is a sequence of `type (1 byte) | length (1 byte) | value`, then bech32-encoded.
 * NIP-19 explicitly waives bech32's 90-character limit, and [Bech32.encode] never imposed
 * one, so long relay lists are fine.
 */
object Nip19 {

    private const val TLV_SPECIAL = 0    // pubkey for nprofile, event id for nevent
    private const val TLV_RELAY = 1      // ascii relay URL, repeatable
    private const val TLV_AUTHOR = 2     // nevent only: the event's author
    private const val TLV_KIND = 3       // nevent only: 4-byte big-endian kind

    /**
     * A profile plus relay hints.
     *
     * @param pubkeyHex 32-byte x-only public key, lowercase hex.
     */
    fun nprofile(pubkeyHex: String, relays: List<String>): String {
        val payload = buildList {
            add(tlv(TLV_SPECIAL, pubkeyHex.hexToBytes().require32("pubkey")))
            relays.forEach { add(tlv(TLV_RELAY, it.encodeToByteArray())) }
        }
        return Bech32.encode("nprofile", payload.concat())
    }

    /**
     * A specific event plus relay hints.
     *
     * Pass the relays that actually accepted the event rather than every configured relay:
     * a hint pointing at a relay that rejected the publish sends readers somewhere the
     * event provably is not.
     */
    fun nevent(
        eventIdHex: String,
        relays: List<String>,
        authorPubkeyHex: String? = null,
        kind: Int? = null,
    ): String {
        val payload = buildList {
            add(tlv(TLV_SPECIAL, eventIdHex.hexToBytes().require32("event id")))
            relays.forEach { add(tlv(TLV_RELAY, it.encodeToByteArray())) }
            authorPubkeyHex?.let { add(tlv(TLV_AUTHOR, it.hexToBytes().require32("author"))) }
            kind?.let { add(tlv(TLV_KIND, it.toBigEndian4())) }
        }
        return Bech32.encode("nevent", payload.concat())
    }

    private fun tlv(type: Int, value: ByteArray): ByteArray {
        // The length field is a single byte, so an over-long relay URL would silently
        // corrupt every following entry. Refuse instead.
        require(value.size <= 255) { "TLV value of ${value.size} bytes exceeds the 255-byte field" }
        return byteArrayOf(type.toByte(), value.size.toByte()) + value
    }

    private fun Int.toBigEndian4() = byteArrayOf(
        (this ushr 24).toByte(),
        (this ushr 16).toByte(),
        (this ushr 8).toByte(),
        this.toByte(),
    )

    private fun ByteArray.require32(what: String): ByteArray {
        require(size == 32) { "$what must be 32 bytes, was $size" }
        return this
    }

    private fun List<ByteArray>.concat(): ByteArray {
        val out = ByteArray(sumOf { it.size })
        var offset = 0
        for (part in this) {
            part.copyInto(out, offset)
            offset += part.size
        }
        return out
    }
}

/**
 * Web links that resolve a NIP-19 identifier for someone who may not use Nostr at all.
 *
 * njump.me is the default because it renders the content in a plain browser *and* offers
 * to hand off to whichever client the reader actually uses. A client-specific host such as
 * snort.social resolves the identical identifier, so the choice of host is cosmetic --
 * what matters is that the identifier carries relay hints.
 */
object NostrLinks {
    const val DEFAULT_HOST = "https://njump.me"

    fun forEvent(nevent: String, host: String = DEFAULT_HOST) = "$host/$nevent"

    fun forProfile(nprofile: String, host: String = DEFAULT_HOST) = "$host/$nprofile"
}
