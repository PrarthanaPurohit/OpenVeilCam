package com.openveil.nostr.nip46

import com.openveil.net.createHttpClient
import com.openveil.testing.freshIdentity
import io.ktor.http.decodeURLQueryComponent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The `nostrconnect://` link exactly as a signer will parse it. Anything wrong here
 * fails silently on the user's other device, so the shape is pinned down.
 */
class NostrConnectPairingTest {

    private fun pairing(relays: List<String> = listOf("wss://relay.nsec.app", "wss://nos.lol")) =
        NostrConnectPairing(
            httpClient = createHttpClient(),
            clientKey = freshIdentity(),
            relays = relays,
            appName = "OpenVeil",
            permissions = listOf("sign_event:1063", "sign_event:1"),
        )

    private fun String.query(): List<Pair<String, String>> =
        substringAfter('?').split('&').map { it.substringBefore('=') to it.substringAfter('=').decodeURLQueryComponent() }

    @Test
    fun the_link_names_our_pubkey_relays_secret_permissions_and_app() {
        val p = pairing()

        assertTrue(p.uri.startsWith("nostrconnect://${p.clientKey.publicKeyHex}?"))
        val query = p.uri.query()
        assertEquals(listOf("wss://relay.nsec.app", "wss://nos.lol"), query.filter { it.first == "relay" }.map { it.second })
        assertEquals(p.secret, query.single { it.first == "secret" }.second)
        assertEquals("sign_event:1063,sign_event:1", query.single { it.first == "perms" }.second)
        assertEquals("OpenVeil", query.single { it.first == "name" }.second)
    }

    @Test
    fun relay_urls_are_url_encoded_so_a_scanner_does_not_split_on_them() {
        val raw = pairing().uri.substringAfter('?')

        assertTrue(!raw.contains("wss://"), "unencoded scheme separators would break query parsing: $raw")
        assertTrue(raw.contains("wss%3A%2F%2Frelay.nsec.app"))
    }

    @Test
    fun the_secret_is_random_and_long_enough_to_identify_the_signer() {
        val a = pairing()
        val b = pairing()

        assertEquals(32, a.secret.length, "16 random bytes as hex")
        assertTrue(a.secret.all { it in '0'..'9' || it in 'a'..'f' })
        assertNotEquals(a.secret, b.secret)
        assertNotEquals(a.clientKey.publicKeyHex, b.clientKey.publicKeyHex, "each pairing uses a fresh client key")
    }

    @Test
    fun pairing_waits_long_enough_for_someone_to_reach_for_another_phone() {
        assertTrue(NostrConnectPairing.PAIRING_TIMEOUT.inWholeMinutes >= 2)
    }
}
