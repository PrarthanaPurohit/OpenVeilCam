package com.openveil.nostr.nip46

import com.openveil.crypto.hexToBytes
import com.openveil.crypto.secureRandomBytes
import com.openveil.crypto.toHex
import com.openveil.nostr.Nip44
import com.openveil.nostr.NostrEvent
import com.openveil.nostr.NostrIdentity
import com.openveil.nostr.parseNostrEvent
import com.openveil.nostr.signingProblem
import com.openveil.nostr.toJson
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
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
import kotlin.time.Duration.Companion.seconds

/** NIP-46 request/response event kind. */
const val KIND_NOSTR_CONNECT = 24133

/**
 * A NIP-46 client: talks to a remote signer ("bunker") over relays.
 *
 * Every call is one encrypted request event and one encrypted response event, matched by
 * a random id. The transport is deliberately connection-per-request, like
 * [com.openveil.nostr.KtorNostrClient]: a publish makes two signing calls, and keeping a
 * socket alive between them would add reconnect and liveness logic for no user-visible
 * gain.
 *
 * [clientKey] is this app's own throwaway key for the conversation. It is not the device
 * identity and it is not the user's key; it exists so the user's key never has to.
 */
class Nip46Client(
    private val httpClient: HttpClient,
    private val clientKey: NostrIdentity,
    private val remoteSignerPubkey: String,
    private val relays: List<String>,
    /**
     * Called when the signer wants the user to approve something in a browser first. The
     * request stays pending; the real answer follows once they have.
     */
    private val onAuthUrl: (String) -> Unit = {},
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val conversationKey: ByteArray =
        Nip44.conversationKey(clientKey.exportPrivateKey(), remoteSignerPubkey.hexToBytes())

    /**
     * Opens the session. [secret] comes from the bunker URI when present; a bunker that
     * issued one will refuse a connect without it.
     */
    suspend fun connect(secret: String?, permissions: List<String>, timeout: Duration = CONNECT_TIMEOUT) {
        val params = listOf(remoteSignerPubkey, secret.orEmpty(), permissions.joinToString(","))
        request("connect", params, timeout)
    }

    /** The user's pubkey, which is not necessarily [remoteSignerPubkey]. */
    suspend fun getPublicKey(timeout: Duration = REQUEST_TIMEOUT): String {
        val result = request("get_public_key", emptyList(), timeout).lowercase()
        if (!HEX64.matches(result)) throw Nip46Exception("The signer returned an invalid public key")
        return result
    }

    /**
     * Asks the signer to sign an event. The result is verified locally before it is
     * trusted: a relay would reject a bad signature with an opaque error, and a signer
     * that swaps in a different pubkey would otherwise publish under a name the user
     * never chose.
     */
    suspend fun signEvent(
        kind: Int,
        content: String,
        tags: List<List<String>>,
        createdAt: Long,
        expectedPubkey: String,
        timeout: Duration = REQUEST_TIMEOUT,
    ): NostrEvent {
        val unsigned = buildJsonObject {
            put("kind", kind)
            put("content", content)
            put("created_at", createdAt)
            put("pubkey", expectedPubkey)
            putJsonArray("tags") {
                for (tag in tags) add(buildJsonArray { for (v in tag) add(v) })
            }
        }.toString()

        val signedJson = request("sign_event", listOf(unsigned), timeout)
        val event = parseNostrEvent(signedJson) ?: throw Nip46Exception("The signer returned a malformed event")
        event.signingProblem(expectedPubkey)?.let { throw Nip46Exception(it) }
        return event
    }

    private suspend fun request(method: String, params: List<String>, timeout: Duration): String {
        val requestId = secureRandomBytes(16).toHex()
        val plaintext = buildJsonObject {
            put("id", requestId)
            put("method", method)
            putJsonArray("params") { for (p in params) add(p) }
        }.toString()

        val event = clientKey.signEvent(
            kind = KIND_NOSTR_CONNECT,
            content = Nip44.encrypt(plaintext, conversationKey),
            tags = listOf(listOf("p", remoteSignerPubkey)),
            createdAt = Clock.System.now().epochSeconds,
        )
        val response = roundTrip(requestId, event, timeout)

        val error = response["error"]?.asStringOrNull()
        if (!error.isNullOrBlank()) throw Nip46Exception(error)
        return response["result"]?.asStringOrNull()
            ?: throw Nip46Exception("The signer's reply had no result")
    }

    /**
     * Sends [event] to every relay and returns the first decrypted reply carrying
     * [requestId]. The subscription is opened before the request is sent so a fast signer
     * cannot answer into a void.
     */
    private suspend fun roundTrip(requestId: String, event: NostrEvent, timeout: Duration): JsonObject {
        val subscriptionId = secureRandomBytes(8).toHex()
        val subscribe = buildJsonArray {
            add("REQ")
            add(subscriptionId)
            add(
                buildJsonObject {
                    putJsonArray("kinds") { add(KIND_NOSTR_CONNECT) }
                    putJsonArray("#p") { add(clientKey.publicKeyHex) }
                    putJsonArray("authors") { add(remoteSignerPubkey) }
                    // A small window back, so a reply racing the subscription is not lost
                    // but stale replies from earlier sessions are.
                    put("since", Clock.System.now().epochSeconds - 10)
                }
            )
        }.toString()
        val publish = """["EVENT",${event.toJson()}]"""

        val reply = withTimeoutOrNull(timeout) {
            runCatching {
                channelFlow {
                    for (relay in relays) {
                        launch {
                            // A relay that is down or rejects us simply contributes nothing;
                            // the others carry on. Only silence from all of them is an error.
                            runCatching {
                                httpClient.webSocket(relay) {
                                    send(Frame.Text(subscribe))
                                    send(Frame.Text(publish))
                                    for (frame in incoming) {
                                        if (frame !is Frame.Text) continue
                                        val response = parseReply(frame.readText(), subscriptionId, requestId)
                                            ?: continue
                                        if (response.isAuthUrl()) {
                                            response["error"]?.asStringOrNull()?.let(onAuthUrl)
                                            continue
                                        }
                                        this@channelFlow.send(response)
                                        break
                                    }
                                }
                            }
                        }
                    }
                }.first()
            }.getOrElse {
                // A timeout or a cancelled publish must propagate as what it is, not be
                // relabelled as a network failure.
                if (it is CancellationException) throw it
                throw Nip46Exception("Could not reach any of the signer's relays", it)
            }
        }
        return reply ?: throw Nip46Exception("The signer did not respond in time")
    }

    /**
     * `["EVENT", <sub>, <event>]` whose content decrypts to a reply with our request id.
     * Anything else -- EOSE, OK, NOTICE, other subscriptions, undecryptable events -- is
     * null. An event that fails to decrypt was not from the signer we are talking to,
     * whatever its pubkey claims.
     */
    private fun parseReply(text: String, subscriptionId: String, requestId: String): JsonObject? =
        runCatching {
            val array = json.parseToJsonElement(text).jsonArray
            if (array.size < 3) return null
            if (array[0].jsonPrimitive.content != "EVENT") return null
            if (array[1].jsonPrimitive.content != subscriptionId) return null
            val event = array[2].jsonObject
            if (event["pubkey"]?.asStringOrNull() != remoteSignerPubkey) return null
            val content = event["content"]?.asStringOrNull() ?: return null
            val reply = json.parseToJsonElement(Nip44.decrypt(content, conversationKey)).jsonObject
            if (reply["id"]?.asStringOrNull() != requestId) return null
            reply
        }.getOrNull()

    private fun JsonObject.isAuthUrl(): Boolean = this["result"]?.asStringOrNull() == "auth_url"

    private fun JsonElement.asStringOrNull(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString || it.content != "null" }?.content

    companion object {
        private val HEX64 = Regex("^[0-9a-f]{64}$")

        // Connecting usually means the user is switching to their signer app to approve
        // the pairing, so it gets longer than an ordinary request.
        val CONNECT_TIMEOUT: Duration = 120.seconds
        val REQUEST_TIMEOUT: Duration = 60.seconds
    }
}

class Nip46Exception(message: String, cause: Throwable? = null) : Exception(message, cause)
