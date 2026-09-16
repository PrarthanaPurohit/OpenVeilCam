package com.openveil.nostr.nip46

import io.ktor.http.decodeURLQueryComponent

/**
 * A parsed `bunker://` connection string (NIP-46).
 *
 * ```
 * bunker://<remote-signer-pubkey>?relay=wss://...&relay=wss://...&secret=<optional>
 * ```
 *
 * [remoteSignerPubkey] is the key the bunker *service* signs its replies with. It is
 * frequently not the user's own key -- a hosted bunker fronts many users with one key --
 * which is why the user's pubkey is discovered with `get_public_key` after connecting
 * rather than read from here.
 */
data class BunkerUri(
    val remoteSignerPubkey: String,
    val relays: List<String>,
    val secret: String?,
) {
    companion object {
        private const val SCHEME = "bunker://"
        private val HEX64 = Regex("^[0-9a-f]{64}$")

        /** Throws [IllegalArgumentException] with a message safe to show the user. */
        fun parse(input: String): BunkerUri {
            val trimmed = input.trim()
            require(trimmed.startsWith(SCHEME, ignoreCase = true)) {
                "Not a bunker link. It should start with bunker://"
            }
            val rest = trimmed.substring(SCHEME.length)
            val pubkey = rest.substringBefore('?').lowercase()
            require(HEX64.matches(pubkey)) { "The bunker link's signer key is not a valid 64-character hex key" }

            val query = rest.substringAfter('?', missingDelimiterValue = "")
            val relays = mutableListOf<String>()
            var secret: String? = null
            for (pair in query.split('&')) {
                if (pair.isEmpty()) continue
                val key = pair.substringBefore('=')
                // Relay URLs arrive percent-encoded (wss%3A%2F%2F...) from most bunkers.
                val value = pair.substringAfter('=', missingDelimiterValue = "").decodeURLQueryComponent()
                when (key) {
                    "relay" -> if (value.isNotBlank()) relays.add(value.trim())
                    "secret" -> secret = value.takeIf { it.isNotBlank() }
                }
            }
            require(relays.isNotEmpty()) { "The bunker link does not name any relay" }
            require(relays.all { it.startsWith("wss://") || it.startsWith("ws://") }) {
                "A relay in the bunker link is not a websocket URL"
            }
            return BunkerUri(pubkey, relays, secret)
        }
    }
}
