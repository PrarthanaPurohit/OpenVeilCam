package com.openveil

import com.openveil.blossom.BlossomConfig
import com.openveil.blossom.KtorBlossomClient
import com.openveil.crypto.hexToBytes
import com.openveil.crypto.sha256Hex
import com.openveil.domain.model.AppResult
import com.openveil.domain.model.SignedAsset
import com.openveil.net.createHttpClient
import com.openveil.nostr.KIND_FILE_METADATA
import com.openveil.nostr.KtorNostrClient
import com.openveil.nostr.NostrConfig
import com.openveil.nostr.NostrIdentity
import com.openveil.nostr.buildCompanionNoteContent
import com.openveil.nostr.buildNip94Tags
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Talks to real Blossom servers and real Nostr relays.
 *
 * OPT-IN, via `-Dopenveil.liveIntegration=true`, so ordinary builds stay hermetic:
 *
 * ```
 * gradlew :shared:testAndroidHostTest -Dopenveil.liveIntegration=true \
 *     --tests "com.openveil.LivePipelineIntegrationTest"
 * ```
 *
 * Lives in androidHostTest rather than commonTest, and uses [runBlocking] rather than
 * `runTest`, on purpose: `runTest` drives a virtual clock, so any `withTimeoutOrNull`
 * around real socket I/O expires the instant the coroutine suspends. Under `runTest`
 * every relay reported "timed out" in under a second while the relays were in fact
 * reachable.
 */
class LivePipelineIntegrationTest {

    /** A genuinely valid 1x1 JPEG, so servers that sniff content type accept it. */
    private val fixtureJpeg = (
        "ffd8ffe000104a46494600010101006000600000ffdb004300080606070605080707070909080a0c" +
            "140d0c0b0b0c1912130f141d1a1f1e1d1a1c1c20242e2720222c231c1c2837292c3031343434" +
            "1f27393d38323c2e333432ffc0000b080001000101011100ffc4001400010000000000000000" +
            "0000000000000009ffc40014100100000000000000000000000000000000ffda000801" +
            "0100003f002a9fffd9"
        ).hexToBytes()

    private val enabled: Boolean
        get() = System.getProperty("openveil.liveIntegration") == "true"

    private fun throwawayIdentity(): NostrIdentity {
        // Fresh key per run; never persisted, never reused, never the user's identity.
        val key = Random.nextBytes(32)
        key[0] = (key[0].toInt() and 0x7F).toByte()
        if (key.all { it == 0.toByte() }) key[31] = 1
        return NostrIdentity(key)
    }

    @Test
    fun uploads_to_blossom_and_serves_byte_identical_content() {
        if (!enabled) return
        runBlocking {
            val http = createHttpClient()
            val identity = throwawayIdentity()
            val client = KtorBlossomClient(http, identity = { identity }, config = BlossomConfig())

            val expectedHash = sha256Hex(fixtureJpeg)
            val uploaded = client.upload(SignedAsset(fixtureJpeg, "image/jpeg", null), expectedHash)
            assertTrue(
                uploaded is AppResult.Success,
                "upload failed: ${(uploaded as? AppResult.Failure)?.detail}",
            )

            val descriptor = uploaded.value
            println("Blossom URL: ${descriptor.url}")
            assertEquals(expectedHash, descriptor.sha256, "server hash must match uploaded bytes")

            val fetched = client.get(expectedHash, descriptor.serverUrl)
            assertTrue(fetched is AppResult.Success, "could not read the blob back")

            // The invariant the product rests on: what the server serves is exactly what we
            // signed and exactly what we publish the hash of.
            assertContentEquals(fixtureJpeg, fetched.value, "stored bytes differ from uploaded")
            assertEquals(expectedHash, sha256Hex(fetched.value))
        }
    }

    @Test
    fun publishes_nip94_and_companion_note_that_relays_accept() {
        if (!enabled) return
        runBlocking {
            val http = createHttpClient()
            val identity = throwawayIdentity()
            val blossom = KtorBlossomClient(http, identity = { identity })
            val nostr = KtorNostrClient(http, NostrConfig())

            val hash = sha256Hex(fixtureJpeg)
            val uploaded = blossom.upload(SignedAsset(fixtureJpeg, "image/jpeg", null), hash)
            assertTrue(uploaded is AppResult.Success, "upload failed")

            val now = Clock.System.now().epochSeconds
            val nip94 = identity.signEvent(
                kind = KIND_FILE_METADATA,
                content = "OpenVeil integration test fixture",
                tags = buildNip94Tags(uploaded.value, hash, 1, 1, "1x1 automated test fixture"),
                createdAt = now,
            )
            val note = identity.signEvent(
                kind = 1,
                content = buildCompanionNoteContent(uploaded.value.url),
                tags = emptyList(),
                createdAt = now,
            )

            println("npub:        ${identity.npub}")
            println("nip94 event: ${nip94.id}")
            println("note  event: ${note.id}")

            val nip94Result = nostr.publish(nip94)
            val noteResult = nostr.publish(note)

            listOf("kind1063" to nip94Result, "kind1" to noteResult).forEach { (label, res) ->
                when (res) {
                    is AppResult.Success ->
                        res.value.outcomes.forEach {
                            println("  $label ${it.relayUrl}: accepted=${it.accepted} ${it.message ?: ""}")
                        }
                    is AppResult.Failure -> println("  $label failed: ${res.detail}")
                }
            }

            assertTrue(nip94Result is AppResult.Success, "no relay accepted the kind-1063 event")
            assertTrue(noteResult is AppResult.Success, "no relay accepted the kind-1 note")
        }
    }
}
