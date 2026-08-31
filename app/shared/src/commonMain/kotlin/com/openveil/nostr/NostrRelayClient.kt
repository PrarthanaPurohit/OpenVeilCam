package com.openveil.nostr

import com.openveil.domain.model.AppResult
import com.openveil.domain.model.PublishError
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Relays to publish to.
 *
 * Kept in one place rather than scattered through the code so the set can be changed
 * without hunting for string literals.
 */
data class NostrConfig(
    // Matched to the OpenVeilCam Raspberry Pi publisher (src/publisher.rs RELAYS) so
    // captures from the camera and from this app land on the same relays and can be
    // discovered together. Keep the two lists in sync.
    val relayUrls: List<String> = listOf(
        "wss://relay.damus.io",
        "wss://nos.lol",
        "wss://relay.primal.net",
        "wss://relay.snort.social",
        "wss://nostr.mom",
    ),
    val publishTimeout: Duration = 12.seconds,
)

/**
 * Result of offering one event to one relay.
 *
 * Recorded per relay rather than collapsed into a boolean because which relays actually
 * hold an event determines the relay hints in its shareable NIP-19 identifier. Pointing a
 * reader at a relay that rejected the event sends them somewhere it provably is not.
 */
data class RelayOutcome(
    val relayUrl: String,
    val accepted: Boolean,
    /** The relay's own message; shown only in diagnostics, never as user-facing copy. */
    val message: String? = null,
)

/** Aggregate result of publishing one event to every configured relay. */
data class PublishOutcome(val outcomes: List<RelayOutcome>) {
    val acceptedRelays: List<String> get() = outcomes.filter { it.accepted }.map { it.relayUrl }

    /**
     * Nostr has no consensus: an event exists once any relay holds it. Requiring all
     * relays to accept would make the most unreliable one define success.
     */
    val isSuccess: Boolean get() = acceptedRelays.isNotEmpty()
}

/**
 * Publishes signed events to Nostr relays.
 *
 * Hand-rolled behind this interface because no maintained Kotlin Multiplatform Nostr
 * library exists. The surface is deliberately narrow so a library could replace it later.
 */
interface NostrClient {
    suspend fun publish(event: NostrEvent): AppResult<PublishOutcome>
}

/**
 * A [NostrClient] over Ktor WebSockets.
 *
 * Speaks NIP-01 directly: sends an EVENT frame and waits for the matching OK frame,
 * ignoring the NOTICE and EOSE frames relays interleave.
 */
class KtorNostrClient(
    private val httpClient: HttpClient,
    private val config: NostrConfig = NostrConfig(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) : NostrClient {

    /**
     * Publishes to every configured relay concurrently and succeeds if at least one
     * returns OK. Relays are independent, so a slow or dead one must not hold up or fail
     * the whole publish -- hence the per-relay timeout rather than one global deadline.
     */
    override suspend fun publish(event: NostrEvent): AppResult<PublishOutcome> = coroutineScope {
        val message = """["EVENT",${event.toJson()}]"""

        val outcomes = config.relayUrls
            .map { relay -> async { publishToRelay(relay, event.id, message) } }
            .awaitAll()

        val result = PublishOutcome(outcomes)
        if (result.isSuccess) {
            AppResult.Success(result)
        } else {
            AppResult.Failure(
                PublishError.NOSTR_PUBLISH_FAILED,
                outcomes.joinToString("; ") { "${it.relayUrl}: ${it.message ?: "no response"}" },
            )
        }
    }

    private suspend fun publishToRelay(
        relayUrl: String,
        eventId: String,
        message: String,
    ): RelayOutcome {
        val outcome = withTimeoutOrNull(config.publishTimeout) {
            runCatching {
                var result = RelayOutcome(relayUrl, accepted = false, message = "no OK received")
                httpClient.webSocket(relayUrl) {
                    send(Frame.Text(message))
                    for (frame in incoming) {
                        if (frame !is Frame.Text) continue
                        val parsed = parseOk(frame.readText(), eventId) ?: continue
                        result = RelayOutcome(relayUrl, parsed.first, parsed.second)
                        break
                    }
                }
                result
            }.getOrElse { throwable ->
                RelayOutcome(relayUrl, accepted = false, message = throwable.message ?: "connection failed")
            }
        }
        return outcome ?: RelayOutcome(relayUrl, accepted = false, message = "timed out")
    }

    /**
     * Parses `["OK", <event_id>, <true|false>, <message>]`.
     *
     * Returns null for any other message, and for an OK about a different event -- relays
     * send NOTICE and EOSE frames too, and treating one of those as our result would
     * report a success that never happened.
     */
    private fun parseOk(text: String, expectedEventId: String): Pair<Boolean, String?>? =
        runCatching {
            val array = json.parseToJsonElement(text).jsonArray
            if (array.size < 3) return null
            if (array[0].jsonPrimitive.content != "OK") return null
            if (array[1].jsonPrimitive.content != expectedEventId) return null
            val accepted = array[2].jsonPrimitive.content.toBooleanStrictOrNull() ?: return null
            val message = array.getOrNull(3)?.jsonPrimitive?.content
            accepted to message
        }.getOrNull()
}
