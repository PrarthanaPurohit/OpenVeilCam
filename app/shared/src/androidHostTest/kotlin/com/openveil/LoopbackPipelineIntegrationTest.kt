package com.openveil

import com.openveil.blossom.BlossomConfig
import com.openveil.blossom.KtorBlossomClient
import com.openveil.crypto.hexToBytes
import com.openveil.crypto.sha256Hex
import com.openveil.domain.model.AppResult
import com.openveil.domain.model.PublishError
import com.openveil.domain.model.PublishStatus
import com.openveil.net.createHttpClient
import com.openveil.nostr.KIND_BLOSSOM_AUTH
import com.openveil.nostr.KIND_FILE_METADATA
import com.openveil.nostr.KtorNostrClient
import com.openveil.nostr.NostrConfig
import com.openveil.nostr.NostrEvent
import com.openveil.nostr.NostrIdentityRepository
import com.openveil.nostr.parseNostrEvent
import com.openveil.nostr.signingProblem
import com.openveil.publish.PublishJob
import com.openveil.publish.PublishPhotoUseCase
import com.openveil.testing.FIXTURE_JPEG
import com.openveil.testing.FakeC2paService
import com.openveil.testing.InMemoryFileStorage
import com.openveil.testing.InMemorySecureStorage
import com.openveil.testing.fixtureCapture
import com.openveil.testing.fixturePhoto
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.ServerSocket
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * The whole publish pipeline over real sockets, against an in-process Blossom server and
 * in-process relays. Hermetic, so it runs on every CI build, unlike
 * [LivePipelineIntegrationTest] which needs the public internet and is opt-in.
 *
 * What this checks that the unit tests cannot: the BUD-11 Authorization header a real
 * server would validate, the exact bytes that go over the wire, NIP-01 OK matching against
 * interleaved relay chatter, per-relay timeouts, and fall-through between servers.
 *
 * `runBlocking`, not `runTest`: real I/O and real timeouts.
 */
class LoopbackPipelineIntegrationTest {

    private val json = Json { ignoreUnknownKeys = true }

    // ---- fake Blossom server ---------------------------------------------------------

    private class BlossomServer(val port: Int) {
        val blobs = mutableMapOf<String, ByteArray>()
        val authHeaders = CopyOnWriteArrayList<String?>()
        var uploads = 0
        /** Reply with this status and no descriptor. */
        var respondWith: HttpStatusCode? = null
        /** Report this sha256 instead of the real one, as a server that re-encodes would. */
        var reportHash: String? = null
        val baseUrl get() = "http://127.0.0.1:$port"
    }

    private lateinit var blossom: BlossomServer
    private lateinit var blossomEngine: EmbeddedServer<*, *>

    private fun startBlossom(): BlossomServer {
        val port = freePort()
        val server = BlossomServer(port)
        blossomEngine = embeddedServer(CIO, port = port) {
            routing {
                put("/upload") {
                    server.uploads++
                    server.authHeaders += call.request.header(HttpHeaders.Authorization)
                    server.respondWith?.let { call.respond(it); return@put }
                    val bytes = call.receive<ByteArray>()
                    val sha = server.reportHash ?: sha256Hex(bytes)
                    server.blobs[sha] = bytes
                    call.respondText(
                        """{"url":"${server.baseUrl}/$sha.jpg","sha256":"$sha","size":${bytes.size},"type":"image/jpeg","uploaded":${Clock.System.now().epochSeconds}}""",
                        ContentType.Application.Json,
                    )
                }
                get("/{sha}") {
                    val sha = call.parameters["sha"]!!.substringBefore('.')
                    val bytes = server.blobs[sha] ?: run { call.respond(HttpStatusCode.NotFound); return@get }
                    call.respondBytes(bytes, ContentType.Image.JPEG)
                }
            }
        }.start(wait = false)
        return server
    }

    // ---- fake relays -----------------------------------------------------------------

    private enum class RelayMode { ACCEPT, REJECT, SILENT }

    private class Relay(val port: Int, var mode: RelayMode) {
        val received = CopyOnWriteArrayList<NostrEvent>()
        val url get() = "ws://127.0.0.1:$port/"
    }

    private val relayEngines = mutableListOf<EmbeddedServer<*, *>>()

    private fun startRelay(mode: RelayMode): Relay {
        val relay = Relay(freePort(), mode)
        relayEngines += embeddedServer(CIO, port = relay.port) {
            install(WebSockets)
            routing {
                webSocket("/") {
                    // Real relays talk before they answer. A client that takes the first
                    // frame as its result would misread this.
                    send(Frame.Text("""["NOTICE","welcome"]"""))
                    for (frame in incoming) {
                        if (frame !is Frame.Text) continue
                        val message = json.parseToJsonElement(frame.readText()).jsonArray
                        if (message[0].jsonPrimitive.content == "EVENT") {
                            val event = assertNotNull(parseNostrEvent(message[1].jsonObject.toString()))
                            relay.received += event
                            when (relay.mode) {
                                RelayMode.ACCEPT -> {
                                    // An OK for some other event first: it must be ignored.
                                    send(Frame.Text("""["OK","${"00".repeat(32)}",false,"unrelated"]"""))
                                    send(Frame.Text("""["OK","${event.id}",true,""]"""))
                                }
                                RelayMode.REJECT -> send(Frame.Text("""["OK","${event.id}",false,"blocked: test relay refuses everything"]"""))
                                RelayMode.SILENT -> Unit
                            }
                        }
                    }
                }
            }
        }.start(wait = false)
        return relay
    }

    private fun freePort() = ServerSocket(0).use { it.localPort }

    @BeforeTest
    fun start() {
        blossom = startBlossom()
    }

    @AfterTest
    fun stop() {
        blossomEngine.stop(gracePeriodMillis = 0, timeoutMillis = 500)
        relayEngines.forEach { it.stop(gracePeriodMillis = 0, timeoutMillis = 500) }
    }

    // ---- wiring ----------------------------------------------------------------------

    private val c2pa = FakeC2paService()
    private val files = InMemoryFileStorage()
    private val identities = NostrIdentityRepository(InMemorySecureStorage())
    private val http = createHttpClient()

    private fun pipeline(
        servers: List<String> = listOf(blossom.baseUrl),
        relays: List<Relay>,
        relayTimeout: kotlin.time.Duration = 3.seconds,
    ) = PublishPhotoUseCase(
        c2pa = c2pa,
        blossom = KtorBlossomClient(
            httpClient = http,
            identity = { runBlocking { identities.getOrCreate() } },
            config = BlossomConfig(servers = servers, authTtlSeconds = 300),
        ),
        nostr = KtorNostrClient(http, NostrConfig(relayUrls = relays.map { it.url }, publishTimeout = relayTimeout)),
        identityRepository = identities,
        fileStorage = files,
        deviceName = "Loopback",
    )

    private fun newJob(caption: String? = null) = PublishJob(captured = fixtureCapture(), photo = fixturePhoto(caption = caption))

    private val signedBytes = FakeC2paService.sign(FIXTURE_JPEG)

    // ---- tests -----------------------------------------------------------------------

    @Test
    fun publishes_end_to_end_and_every_artifact_is_verifiable() = runBlocking {
        val good = startRelay(RelayMode.ACCEPT)
        val bad = startRelay(RelayMode.REJECT)
        val device = identities.getOrCreate()

        val final = pipeline(relays = listOf(good, bad)).publish(newJob(caption = "Loopback capture")).toList().last()

        assertEquals(PublishStatus.PUBLISHED, final.photo.status, "error: ${final.photo.error}")
        val hash = sha256Hex(signedBytes)

        // Blossom stored exactly the signed bytes, addressed by their hash.
        assertContentEquals(signedBytes, blossom.blobs[hash])
        assertEquals("${blossom.baseUrl}/$hash.jpg", final.photo.blossomUrl)
        assertEquals(hash, final.photo.sha256)

        // The BUD-11 authorization is a signed kind-24242 event a server can validate.
        val header = assertNotNull(blossom.authHeaders.single())
        assertTrue(header.startsWith("Nostr "))
        val token = header.removePrefix("Nostr ")
        assertTrue(!token.contains('='), "base64url without padding")
        val auth = assertNotNull(parseNostrEvent(Base64.getUrlDecoder().decode(token).decodeToString()))
        assertEquals(KIND_BLOSSOM_AUTH, auth.kind)
        assertEquals(device.publicKeyHex, auth.pubkey)
        assertNull(auth.signingProblem(device.publicKeyHex))
        assertEquals("upload", auth.tag("t"))
        assertEquals(hash, auth.tag("x"))
        val now = Clock.System.now().epochSeconds
        assertTrue(auth.createdAt <= now, "created_at must be in the past")
        assertTrue(auth.tag("expiration")!!.toLong() > now, "expiration must be in the future")

        // Every relay got the same two events, signed by the device, in order.
        for (relay in listOf(good, bad)) {
            assertEquals(listOf(KIND_FILE_METADATA, 1), relay.received.map { it.kind }, relay.url)
            for (event in relay.received) assertNull(event.signingProblem(device.publicKeyHex))
        }
        val nip94 = good.received.first()
        assertEquals(final.photo.nostrEventId, nip94.id)
        assertEquals(final.photo.blossomUrl, nip94.tag("url"))
        assertEquals(hash, nip94.tag("x"))
        assertEquals(sha256Hex(FIXTURE_JPEG), nip94.tag("ox"))
        assertEquals(device.publicKeyHex, nip94.tag("device"))
        assertEquals("Loopback capture", nip94.tag("alt"))
        assertTrue(good.received[1].content.endsWith(final.photo.blossomUrl!!))

        // Only the relay that said OK is named as holding the event.
        assertEquals(listOf(good.url), final.photo.acceptedRelays)

        // And the master is gone now that a relay holds the event.
        assertTrue(files.files.isEmpty())
    }

}
