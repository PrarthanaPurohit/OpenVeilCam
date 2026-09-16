package com.openveil.nostr.nip46

import com.openveil.crypto.hexToBytes
import com.openveil.crypto.secureRandomBytes
import com.openveil.crypto.toHex
import com.openveil.nostr.Nip44
import com.openveil.nostr.NostrIdentity
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.http.encodeURLParameter
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Client-initiated NIP-46 pairing: the app shows a `nostrconnect://` link (as a QR code)
 * and the signer, on whatever device it lives, scans it and calls back.
 *
 * The inverse of a `bunker://` link. Here the signer is unknown until it announces itself
 * by proving it read the link: its `connect` reply must decrypt under a conversation key
 * derived from *its* pubkey and carry our [secret] back. A reply that says "ack" without
 * the secret is not accepted -- with an unknown counterparty, the secret is the only thing
 * that separates the user's signer from anyone else watching the relay.
 */
class NostrConnectPairing(
    private val httpClient: HttpClient,
    val clientKey: NostrIdentity,
    val relays: List<String>,
    appName: String,
    permissions: List<String>,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    val secret: String = secureRandomBytes(16).toHex()

    /** What goes in the QR code. */
    val uri: String = buildString {
        append("nostrconnect://").append(clientKey.publicKeyHex)
        var first = true
        fun param(key: String, value: String) {
            append(if (first) '?' else '&'); first = false
            append(key).append('=').append(value.encodeURLParameter())
        }
        for (relay in relays) param("relay", relay)
        param("secret", secret)
        param("perms", permissions.joinToString(","))
        param("name", appName)
    }

    /**
     * Waits for a signer to answer, and returns its pubkey -- the `remoteSignerPubkey` for
     * every request from here on. The caller then asks it for `get_public_key`; a
     * `connect` request must *not* be sent, the signer already considers us connected.
     */
    suspend fun awaitSigner(timeout: Duration = PAIRING_TIMEOUT): String {
        val subscriptionId = secureRandomBytes(8).toHex()
        val subscribe = buildJsonArray {
            add("REQ")
            add(subscriptionId)
            add(
                buildJsonObject {
                    putJsonArray("kinds") { add(KIND_NOSTR_CONNECT) }
                    putJsonArray("#p") { add(clientKey.publicKeyHex) }
                    put("since", Clock.System.now().epochSeconds - 10)
                }
            )
        }.toString()

        val signer = withTimeoutOrNull(timeout) {
            runCatching {
                channelFlow {
                    for (relay in relays) {
                        launch {
                            runCatching {
                                httpClient.webSocket(relay) {
                                    send(Frame.Text(subscribe))
                                    for (frame in incoming) {
                                        if (frame !is Frame.Text) continue
                                        val pubkey = signerPubkeyFrom(frame.readText(), subscriptionId) ?: continue
                                        this@channelFlow.send(pubkey)
                                        break
                                    }
                                }
                            }
                        }
                    }
                }.first()
            }.getOrElse {
                if (it is CancellationException) throw it
                throw Nip46Exception("Could not reach any pairing relay", it)
            }
        }
        return signer ?: throw Nip46Exception("No signer connected in time")
    }

    /**
     * The author of an event on our subscription whose content decrypts under a key
     * derived from that author and carries our secret. Anything else is null.
     */
    private fun signerPubkeyFrom(text: String, subscriptionId: String): String? = runCatching {
        val array = json.parseToJsonElement(text).jsonArray
        if (array.size < 3) return null
        if (array[0].jsonPrimitive.content != "EVENT") return null
        if (array[1].jsonPrimitive.content != subscriptionId) return null
        val event = array[2].jsonObject
        val author = (event["pubkey"] as? JsonPrimitive)?.content?.lowercase() ?: return null
        if (!HEX64.matches(author)) return null
        val content = (event["content"] as? JsonPrimitive)?.content ?: return null
        val conversationKey = Nip44.conversationKey(clientKey.exportPrivateKey(), author.hexToBytes())
        val reply = json.parseToJsonElement(Nip44.decrypt(content, conversationKey)).jsonObject
        val result = (reply["result"] as? JsonPrimitive)?.content
        if (result != secret) return null
        author
    }.getOrNull()

    companion object {
        private val HEX64 = Regex("^[0-9a-f]{64}$")

        /** Long: the user is reaching for another device and opening a scanner on it. */
        val PAIRING_TIMEOUT: Duration = 5.minutes
    }
}
