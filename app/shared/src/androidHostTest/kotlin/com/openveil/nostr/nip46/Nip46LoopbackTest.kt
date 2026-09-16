package com.openveil.nostr.nip46

import com.openveil.crypto.generatePrivateKey
import com.openveil.crypto.hexToBytes
import com.openveil.domain.service.SecureStorage
import com.openveil.net.createHttpClient
import com.openveil.nostr.KIND_FILE_METADATA
import com.openveil.nostr.Nip44
import com.openveil.nostr.NostrIdentity
import com.openveil.nostr.computeEventId
import com.openveil.nostr.toJson
import fr.acinq.secp256k1.Secp256k1
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.http.decodeURLQueryComponent
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Drives the real [Nip46Client] and [LinkedAccountRepository] against an in-process
 * relay that is also the bunker.
 *
 * This is the only test that exercises the wire protocol end to end -- request
 * encryption direction, tag shape, subscription filter, reply matching, the `auth_url`
 * detour, and local verification of what the signer hands back. If a real bunker cannot
 * talk to us, this test is the first thing to make fail.
 *
 * `runBlocking`, not `runTest`: real sockets with real timeouts, see
 * [com.openveil.LivePipelineIntegrationTest].
 */
class Nip46LoopbackTest {

    /** The bunker service key: signs replies. Distinct from the user, as with hosted bunkers. */
    private val bunkerKey = NostrIdentity(generatePrivateKey { Secp256k1.secKeyVerify(it) })

    /** The account the user actually publishes as. */
    private val userKey = NostrIdentity(generatePrivateKey { Secp256k1.secKeyVerify(it) })

    private val secret = "pairing-secret"
    private val json = Json { ignoreUnknownKeys = true }

    private var port = 0
    private lateinit var server: EmbeddedServer<*, *>

    /** Toggles for the fake bunker's behaviour, set per test. */
    private var sendAuthUrlFirst = false
    private var tamperSignedEvent = false
    private val authUrl = "https://bunker.example/approve?req=1"

    @BeforeTest
    fun startFakeRelayAndBunker() {
        port = ServerSocket(0).use { it.localPort }
        server = embeddedServer(CIO, port = port) {
            install(WebSockets)
            routing {
                webSocket("/") {
                    var subscriptionId: String? = null
                    try {
                        for (frame in incoming) {
                            if (frame !is Frame.Text) continue
                            val message = json.parseToJsonElement(frame.readText()).jsonArray
                            when (message[0].jsonPrimitive.content) {
                                "REQ" -> {
                                    subscriptionId = message[1].jsonPrimitive.content
                                    subscribers[this] = subscriptionId
                                }
                                "EVENT" -> {
                                    val event = message[1].jsonObject
                                    send(Frame.Text("""["OK","${event["id"]!!.jsonPrimitive.content}",true,""]"""))
                                    // A relay's actual job: fan the event out to everyone
                                    // subscribed, on whatever connection they hold.
                                    broadcast(event.toString())
                                    for (reply in bunkerReplies(event)) broadcast(reply)
                                }
                            }
                        }
                    } finally {
                        subscribers.remove(this)
                    }
                }
            }
        }.start(wait = false)
    }

    @AfterTest
    fun stop() {
        server.stop(gracePeriodMillis = 0, timeoutMillis = 500)
    }

    private val subscribers = java.util.concurrent.ConcurrentHashMap<io.ktor.websocket.WebSocketSession, String>()

    private suspend fun broadcast(eventJson: String) {
        for ((session, sub) in subscribers) {
            runCatching { session.send(Frame.Text("""["EVENT","$sub",$eventJson]""")) }
        }
    }

    /**
     * Decrypts a request to the bunker and produces the reply events, as JSON. Events
     * that are not for this bunker (wrong kind, or not decryptable with its key -- e.g.
     * a signer's own announcement being fanned out) produce nothing.
     */
    private fun bunkerReplies(request: kotlinx.serialization.json.JsonObject): List<String> {
        if (request["kind"]!!.jsonPrimitive.content.toInt() != KIND_NOSTR_CONNECT) return emptyList()
        if (request["pubkey"]!!.jsonPrimitive.content == bunkerKey.publicKeyHex) return emptyList()
        val clientPubkey = request["pubkey"]!!.jsonPrimitive.content
        val conversationKey = Nip44.conversationKey(bunkerKey.exportPrivateKey(), clientPubkey.hexToBytes())
        val body = runCatching {
            json.parseToJsonElement(
                Nip44.decrypt(request["content"]!!.jsonPrimitive.content, conversationKey)
            ).jsonObject
        }.getOrElse { return emptyList() }
        val id = body["id"]!!.jsonPrimitive.content
        val params = body["params"]!!.jsonArray.map { it.jsonPrimitive.content }

        val (result, error) = when (body["method"]!!.jsonPrimitive.content) {
            "connect" -> when {
                params[0] != bunkerKey.publicKeyHex -> null to "wrong remote signer pubkey"
                params[1] != secret -> null to "invalid secret"
                else -> "ack" to null
            }
            "get_public_key" -> userKey.publicKeyHex to null
            "sign_event" -> {
                val unsigned = json.parseToJsonElement(params[0]).jsonObject
                val signed = userKey.signEvent(
                    kind = unsigned["kind"]!!.jsonPrimitive.content.toInt(),
                    content = unsigned["content"]!!.jsonPrimitive.content,
                    tags = unsigned["tags"]!!.jsonArray.map { t -> t.jsonArray.map { it.jsonPrimitive.content } },
                    createdAt = unsigned["created_at"]!!.jsonPrimitive.content.toLong(),
                )
                val out = if (tamperSignedEvent) signed.copy(content = signed.content + " (edited)") else signed
                out.toJson() to null
            }
            else -> null to "unknown method"
        }

        val replies = mutableListOf<String>()
        if (sendAuthUrlFirst) {
            replies += replyEvent(clientPubkey, conversationKey, id, result = "auth_url", error = authUrl)
        }
        replies += replyEvent(clientPubkey, conversationKey, id, result, error)
        return replies
    }

    private fun replyEvent(
        clientPubkey: String,
        conversationKey: ByteArray,
        requestId: String,
        result: String?,
        error: String?,
    ): String {
        val plaintext = buildJsonObject {
            put("id", requestId)
            if (result != null) put("result", result)
            if (error != null) put("error", error)
        }.toString()
        return bunkerKey.signEvent(
            kind = KIND_NOSTR_CONNECT,
            content = Nip44.encrypt(plaintext, conversationKey),
            tags = listOf(listOf("p", clientPubkey)),
            createdAt = Clock.System.now().epochSeconds,
        ).toJson()
    }

    private fun bunkerLink(withSecret: String? = secret) = buildString {
        append("bunker://").append(bunkerKey.publicKeyHex)
        append("?relay=ws%3A%2F%2F127.0.0.1%3A").append(port).append("%2F")
        if (withSecret != null) append("&secret=").append(withSecret)
    }

    private fun repository(storage: SecureStorage = InMemorySecureStorage()) =
        LinkedAccountRepository(storage, createHttpClient())

    @Test
    fun links_discovers_the_user_pubkey_and_signs_verifiably() = runBlocking {
        val storage = InMemorySecureStorage()
        val repo = repository(storage)

        val account = withTimeout(20_000) { repo.link(bunkerLink()) }

        assertEquals(userKey.publicKeyHex, account.userPubkeyHex, "user pubkey comes from get_public_key")
        assertEquals(bunkerKey.publicKeyHex, account.remoteSignerPubkey)
        assertEquals(userKey.npub, account.npub)
        assertTrue(storage.contains("nostr.linked_account"), "session is persisted")

        // A fresh repository over the same storage must come back linked without re-pairing.
        val reloaded = repository(storage)
        assertEquals(account, reloaded.current())

        val signer = assertNotNull(reloaded.signer())
        assertEquals(userKey.publicKeyHex, signer.publicKeyHex)

        val tags = listOf(listOf("url", "https://blossom.example/x.jpg"), listOf("device", "ab".repeat(32)))
        val event = withTimeout(20_000) {
            signer.signEvent(KIND_FILE_METADATA, "Captured with OpenVeil", tags, 1_700_000_000L)
        }
        assertEquals(userKey.publicKeyHex, event.pubkey)
        assertEquals(tags, event.tags, "tags survive the JSON round trip through the signer")
        assertEquals(
            computeEventId(event.pubkey, event.createdAt, event.kind, event.tags, event.content),
            event.id,
        )
        assertTrue(Secp256k1.verifySchnorr(event.sig.hexToBytes(), event.id.hexToBytes(), userKey.publicKey))
    }

    @Test
    fun surfaces_auth_url_and_still_completes() = runBlocking {
        sendAuthUrlFirst = true
        val repo = repository()
        val seen = mutableListOf<String>()
        val collector = launch { repo.authUrls.collect { seen += it } }

        val account = withTimeout(20_000) { repo.link(bunkerLink()) }

        collector.cancel()
        assertEquals(userKey.publicKeyHex, account.userPubkeyHex)
        assertTrue(seen.isNotEmpty() && seen.all { it == authUrl }, "auth_url was surfaced: $seen")
    }

    @Test
    fun a_wrong_secret_is_refused_and_nothing_is_stored() = runBlocking {
        val storage = InMemorySecureStorage()
        val repo = repository(storage)

        val failure = assertFailsWith<Nip46Exception> {
            withTimeout(20_000) { repo.link(bunkerLink(withSecret = "nope")) }
        }
        assertEquals("invalid secret", failure.message)
        assertNull(repo.current())
        assertTrue(!storage.contains("nostr.linked_account"))
    }

    @Test
    fun a_tampered_reply_from_the_signer_is_rejected() = runBlocking<Unit> {
        val repo = repository()
        withTimeout(20_000) { repo.link(bunkerLink()) }
        tamperSignedEvent = true

        val signer = assertNotNull(repo.signer())
        assertFailsWith<Nip46Exception> {
            withTimeout(20_000) { signer.signEvent(1, "hello", emptyList(), 1_700_000_000L) }
        }
    }

    /**
     * The reverse flow: we show a nostrconnect:// link, the signer scans it and calls us.
     * The fake signer here parses the link exactly as a real one would.
     */
    @Test
    fun nostrconnect_pairing_is_initiated_by_the_signer() = runBlocking<Unit> {
        val storage = InMemorySecureStorage()
        val repo = LinkedAccountRepository(
            storage, createHttpClient(), pairingRelays = listOf("ws://127.0.0.1:$port/"),
        )
        val pairing = repo.startPairing()
        assertTrue(pairing.uri.startsWith("nostrconnect://${pairing.clientKey.publicKeyHex}?"))

        // "Scan" it: a signer reads the client pubkey, relay and secret out of the link.
        val query: Map<String, String> = pairing.uri.substringAfter('?').split('&').associate {
            it.substringBefore('=') to it.substringAfter('=').decodeURLQueryComponent()
        }
        assertEquals("ws://127.0.0.1:$port/", query["relay"])
        assertEquals(pairing.secret, query["secret"])
        assertEquals("sign_event:1063,sign_event:1", query["perms"])
        assertEquals("OpenVeil", query["name"])

        // The signer announces itself slightly after we start listening.
        val announce = launch {
            // A real signer is scanned after the QR is on screen; here, wait until the
            // relay actually holds our subscription, or a cold first connect loses the race.
            while (subscribers.isEmpty()) kotlinx.coroutines.delay(20)
            val conversationKey = Nip44.conversationKey(
                bunkerKey.exportPrivateKey(), pairing.clientKey.publicKeyHex.hexToBytes(),
            )
            val connectReply = replyEvent(
                pairing.clientKey.publicKeyHex, conversationKey, requestId = "signer-chosen",
                result = query["secret"], error = null,
            )
            createHttpClient().webSocket("ws://127.0.0.1:$port/") {
                send(Frame.Text("""["EVENT",$connectReply]"""))
                // Stay until the relay acknowledges, as a real signer would; closing the
                // socket the instant the frame is queued can drop it.
                for (frame in incoming) if (frame is Frame.Text && frame.readText().startsWith("[\"OK\"")) break
            }
        }

        val account = withTimeout(20_000) { repo.awaitPairing(pairing) }
        announce.join()

        assertEquals(LinkMethod.BUNKER, account.method)
        assertEquals(bunkerKey.publicKeyHex, account.remoteSignerPubkey, "signer identified by proving the secret")
        assertEquals(userKey.publicKeyHex, account.userPubkeyHex, "then asked for the user's pubkey")
        assertNotNull(repo.signer())
    }

    @Test
    fun nostrconnect_ignores_a_reply_without_the_secret() = runBlocking<Unit> {
        val repo = LinkedAccountRepository(
            InMemorySecureStorage(), createHttpClient(), pairingRelays = listOf("ws://127.0.0.1:$port/"),
        )
        val pairing = repo.startPairing()

        val impostor = launch {
            while (subscribers.isEmpty()) kotlinx.coroutines.delay(20)
            val conversationKey = Nip44.conversationKey(
                bunkerKey.exportPrivateKey(), pairing.clientKey.publicKeyHex.hexToBytes(),
            )
            val ack = replyEvent(pairing.clientKey.publicKeyHex, conversationKey, "x", result = "ack", error = null)
            createHttpClient().webSocket("ws://127.0.0.1:$port/") {
                send(Frame.Text("""["EVENT",$ack]"""))
                for (frame in incoming) if (frame is Frame.Text && frame.readText().startsWith("[\"OK\"")) break
            }
        }

        assertFailsWith<Nip46Exception> {
            pairing.awaitSigner(timeout = kotlin.time.Duration.parse("3s"))
        }
        impostor.join()
    }

    @Test
    fun unlink_forgets_the_session() = runBlocking {
        val storage = InMemorySecureStorage()
        val repo = repository(storage)
        withTimeout(20_000) { repo.link(bunkerLink()) }

        repo.unlink()

        assertNull(repo.current())
        assertNull(repo.signer())
        assertTrue(!storage.contains("nostr.linked_account"))
    }

    @Test
    fun a_malformed_link_fails_before_touching_the_network() = runBlocking<Unit> {
        assertFailsWith<IllegalArgumentException> { repository().link("nsec1notabunker") }
    }
}

/** Plain map; the real one is Keystore-wrapped, which is irrelevant to the protocol. */
private class InMemorySecureStorage : SecureStorage {
    private val values = mutableMapOf<String, ByteArray>()
    override suspend fun putBytes(key: String, value: ByteArray) { values[key] = value.copyOf() }
    override suspend fun getBytes(key: String): ByteArray? = values[key]?.copyOf()
    override suspend fun remove(key: String) { values.remove(key) }
    override suspend fun contains(key: String): Boolean = key in values
}
