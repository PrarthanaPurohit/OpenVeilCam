package com.openveil.publish

import com.openveil.crypto.hexToBytes
import com.openveil.crypto.sha256Hex
import com.openveil.domain.model.PublishError
import com.openveil.domain.model.PublishStatus
import com.openveil.domain.model.SignedAsset
import com.openveil.net.createHttpClient
import com.openveil.nostr.KIND_FILE_METADATA
import com.openveil.nostr.NostrIdentityRepository
import com.openveil.nostr.computeEventId
import com.openveil.nostr.nip46.LinkedAccountRepository
import com.openveil.testing.FIXTURE_JPEG
import com.openveil.testing.FakeBlossomClient
import com.openveil.testing.FakeC2paService
import com.openveil.testing.FakeNostrClient
import com.openveil.testing.FakeSignerApp
import com.openveil.testing.InMemoryFileStorage
import com.openveil.testing.InMemorySecureStorage
import com.openveil.testing.fixtureCapture
import com.openveil.testing.fixturePhoto
import fr.acinq.secp256k1.Secp256k1
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pipeline's contract, stage by stage, with every collaborator faked.
 *
 * What these protect: the ordering rules in [PublishPhotoUseCase]'s docs (hash after
 * signing, master on disk before upload, master deleted only after a relay accepts), the
 * resume-from-where-it-stopped behaviour of retry, and the two-key model -- the device
 * key always attests, whichever key the user chose to publish under.
 */
class PublishPhotoUseCaseTest {

    private val c2pa = FakeC2paService()
    private val blossom = FakeBlossomClient()
    private val nostr = FakeNostrClient()
    private val files = InMemoryFileStorage()
    private val secure = InMemorySecureStorage()
    private val identities = NostrIdentityRepository(secure)
    private val signerApp = FakeSignerApp()
    private val linked = LinkedAccountRepository(secure, createHttpClient(), signerApp = signerApp)

    private fun useCase(companionNote: Boolean = true, withLinking: Boolean = true) = PublishPhotoUseCase(
        c2pa = c2pa,
        blossom = blossom,
        nostr = nostr,
        identityRepository = identities,
        fileStorage = files,
        deviceName = "Test Phone",
        publishCompanionNote = companionNote,
        linkedAccounts = if (withLinking) linked else null,
    )

    private fun newJob(caption: String? = null) = PublishJob(captured = fixtureCapture(), photo = fixturePhoto(caption = caption))

    private val expectedSignedBytes = FakeC2paService.sign(FIXTURE_JPEG)

    // ---- sign ------------------------------------------------------------------------

    @Test
    fun sign_hashes_the_signed_bytes_and_writes_the_master_before_finishing() = runTest {
        val states = useCase().sign(newJob()).toList()

        assertEquals(
            listOf(PublishStatus.C2PA_SIGNING, PublishStatus.C2PA_SIGNED),
            states.map { it.photo.status },
        )
        val signed = states.last()
        assertContentEquals(expectedSignedBytes, assertNotNull(signed.signed).bytes)
        assertEquals(sha256Hex(expectedSignedBytes), signed.photo.sha256, "x must be the hash of the SIGNED bytes")
        assertEquals(sha256Hex(FIXTURE_JPEG), signed.photo.originalSha256, "ox is the pre-signing capture")
        assertNotEquals(signed.photo.sha256, signed.photo.originalSha256)
        assertEquals("urn:uuid:fake-manifest", signed.photo.c2paManifestId)
        assertEquals(expectedSignedBytes.size.toLong(), signed.photo.fileSize)
        assertEquals("Test Phone", signed.photo.captureDevice)
        assertNull(signed.photo.error)

        val path = assertNotNull(signed.photo.localPath, "the master must be on disk before any upload")
        assertContentEquals(expectedSignedBytes, files.files[path])
    }

    @Test
    fun sign_binds_the_manifest_to_the_device_identity() = runTest {
        val device = identities.getOrCreate()

        val signed = useCase().sign(newJob()).toList().last()

        val context = c2pa.contexts.single()
        assertEquals(device.npub, context.npub)
        assertEquals(device.publicKeyHex, context.nostrPubkeyHex)
        assertEquals(1, context.width)
        assertEquals(1, context.height)
        assertEquals("Test Phone", context.captureDevice)
        assertEquals(device.publicKeyHex, signed.photo.nostrPubkey)
    }

    @Test
    fun sign_is_idempotent_for_an_already_signed_job() = runTest {
        val already = newJob().copy(signed = SignedAsset(expectedSignedBytes, "image/jpeg", "m"))

        val states = useCase().sign(already).toList()

        assertEquals(listOf(already), states)
        assertEquals(0, c2pa.signCalls, "a signed job must not be re-signed")
    }

    @Test
    fun sign_failure_is_reported_and_nothing_is_written() = runTest {
        c2pa.failWith = PublishError.C2PA_FAILED

        val states = useCase().sign(newJob()).toList()

        val last = states.last()
        assertEquals(PublishStatus.FAILED, last.photo.status)
        assertEquals(PublishError.C2PA_FAILED, last.photo.error)
        assertNull(last.signed)
        assertTrue(files.files.isEmpty(), "no master is written for a failed signature")
    }

    @Test
    fun sign_fails_cleanly_when_the_device_identity_cannot_be_read() = runTest {
        secure.seed("nostr.private_key", ByteArray(31))

        val last = useCase().sign(newJob()).toList().last()

        assertEquals(PublishStatus.FAILED, last.photo.status)
        assertEquals(PublishError.NOSTR_SIGNING_FAILED, last.photo.error)
        assertEquals(0, c2pa.signCalls)
    }

    // ---- publish: the happy path ---------------------------------------------------------

    @Test
    fun publish_runs_every_stage_in_order_and_ends_published() = runTest {
        val states = useCase().publish(newJob(caption = "A note")).toList()

        assertEquals(
            listOf(
                PublishStatus.C2PA_SIGNING,
                PublishStatus.C2PA_SIGNED,
                PublishStatus.UPLOADING_BLOSSOM,
                PublishStatus.BLOSSOM_UPLOADED,
                PublishStatus.PUBLISHING_NOSTR,
                PublishStatus.PUBLISHED,
            ),
            states.map { it.photo.status },
        )
        val final = states.last()
        assertTrue(final.photo.isPublished)
        assertNull(final.photo.error)
        assertEquals(listOf(nostr.relayUrl), final.photo.acceptedRelays)
        assertNotNull(final.photo.blossomUrl)
        assertNotNull(final.photo.nostrEventId)
    }

    @Test
    fun publish_uploads_exactly_the_signed_bytes_under_their_hash() = runTest {
        useCase().publish(newJob()).toList()

        val (asset, hash) = blossom.uploads.single()
        assertContentEquals(expectedSignedBytes, asset.bytes)
        assertEquals(sha256Hex(expectedSignedBytes), hash)
    }

    @Test
    fun publish_emits_a_nip94_event_signed_by_the_device_that_names_itself_as_attester() = runTest {
        val device = identities.getOrCreate()

        val final = useCase().publish(newJob(caption = "  Rooftop, dawn  ")).toList().last()

        val event = nostr.ofKind(KIND_FILE_METADATA).single()
        assertEquals(device.publicKeyHex, event.pubkey)
        assertEquals(event.id, final.photo.nostrEventId)
        assertEquals(device.publicKeyHex, final.photo.nostrPubkey)
        assertEquals(computeEventId(event.pubkey, event.createdAt, event.kind, event.tags, event.content), event.id)
        assertTrue(Secp256k1.verifySchnorr(event.sig.hexToBytes(), event.id.hexToBytes(), device.publicKey))

        assertEquals(final.photo.blossomUrl, event.tag("url"))
        assertEquals(sha256Hex(expectedSignedBytes), event.tag("x"))
        assertEquals(sha256Hex(FIXTURE_JPEG), event.tag("ox"))
        assertEquals("1x1", event.tag("dim"))
        assertEquals(device.publicKeyHex, event.tag("device"), "the attesting key is always the device tag")
        assertEquals("Rooftop, dawn", event.tag("alt"), "caption is trimmed")
        assertEquals("Rooftop, dawn", event.content)
    }

    @Test
    fun publish_uses_the_default_content_when_there_is_no_caption() = runTest {
        useCase().publish(newJob(caption = "   ")).toList()

        val event = nostr.ofKind(KIND_FILE_METADATA).single()
        assertEquals("Captured with OpenVeil", event.content)
        assertNull(event.tag("alt"))
    }

    @Test
    fun publish_adds_a_companion_note_carrying_the_image_url() = runTest {
        val final = useCase().publish(newJob(caption = "Hello")).toList().last()

        val note = nostr.ofKind(1).single()
        assertTrue(note.content.startsWith("Hello\n\n"))
        assertTrue(note.content.endsWith(final.photo.blossomUrl!!))
        assertEquals(nostr.ofKind(KIND_FILE_METADATA).single().pubkey, note.pubkey)
    }

    @Test
    fun publish_can_skip_the_companion_note() = runTest {
        useCase(companionNote = false).publish(newJob()).toList()

        assertEquals(listOf(KIND_FILE_METADATA), nostr.published.map { it.kind })
    }

    @Test
    fun a_rejected_companion_note_does_not_fail_the_publish() = runTest {
        nostr.rejectKinds += 1

        val final = useCase().publish(newJob()).toList().last()

        assertEquals(PublishStatus.PUBLISHED, final.photo.status)
        assertEquals(2, nostr.published.size, "the note was attempted")
    }

    @Test
    fun publish_deletes_the_local_master_only_once_published() = runTest {
        var pathWhilePublishing: String? = null
        var final: PublishJob? = null

        useCase().publish(newJob()).collect { state ->
            if (state.photo.status != PublishStatus.PUBLISHED) {
                assertTrue(files.deleted.isEmpty(), "nothing may be deleted while still ${state.photo.status}")
            }
            if (state.photo.status == PublishStatus.PUBLISHING_NOSTR) {
                pathWhilePublishing = assertNotNull(state.photo.localPath)
            }
            final = state
        }

        assertNull(final!!.photo.localPath)
        assertEquals(listOf(pathWhilePublishing), files.deleted)
        assertTrue(files.files.isEmpty())
    }

    // ---- publish: failure and retry --------------------------------------------------

    @Test
    fun an_upload_failure_keeps_the_master_and_records_where_to_resume() = runTest {
        blossom.failWith = PublishError.BLOSSOM_UPLOAD_FAILED

        val states = useCase().publish(newJob()).toList()

        val last = states.last()
        assertEquals(PublishStatus.FAILED, last.photo.status)
        assertEquals(PublishError.BLOSSOM_UPLOAD_FAILED, last.photo.error)
        assertEquals(PublishStatus.C2PA_SIGNED, last.photo.error!!.resumeAt)
        assertNotNull(last.signed, "the signature survives for the retry")
        assertNotNull(last.photo.localPath)
        assertTrue(files.deleted.isEmpty(), "a failed upload must never delete the only copy")
        assertTrue(nostr.published.isEmpty())
    }

    @Test
    fun retry_after_an_upload_failure_does_not_sign_again() = runTest {
        blossom.failWith = PublishError.BLOSSOM_UPLOAD_FAILED
        val failed = useCase().publish(newJob()).toList().last()

        blossom.failWith = null
        val final = useCase().publish(failed).toList().last()

        assertEquals(PublishStatus.PUBLISHED, final.photo.status)
        assertEquals(1, c2pa.signCalls, "signing is not repeated on retry")
        assertEquals(2, blossom.uploads.size)
    }

    @Test
    fun a_relay_failure_keeps_the_upload_and_the_master() = runTest {
        nostr.failWith = PublishError.NOSTR_PUBLISH_FAILED

        val last = useCase().publish(newJob()).toList().last()

        assertEquals(PublishStatus.FAILED, last.photo.status)
        assertEquals(PublishError.NOSTR_PUBLISH_FAILED, last.photo.error)
        assertEquals(PublishStatus.BLOSSOM_UPLOADED, last.photo.error!!.resumeAt)
        assertNotNull(last.photo.blossomUrl, "the upload result is kept so retry can skip it")
        assertNull(last.photo.nostrEventId)
        assertNotNull(last.photo.localPath)
        assertTrue(files.deleted.isEmpty())
    }

    @Test
    fun retry_after_a_relay_failure_does_not_upload_again() = runTest {
        nostr.failWith = PublishError.NOSTR_PUBLISH_FAILED
        val failed = useCase().publish(newJob()).toList().last()

        nostr.failWith = null
        val states = useCase().publish(failed).toList()

        assertEquals(
            listOf(PublishStatus.PUBLISHING_NOSTR, PublishStatus.PUBLISHED),
            states.map { it.photo.status },
            "retry re-enters at the relay stage, nothing earlier",
        )
        assertEquals(1, blossom.uploads.size, "Blossom already holds the bytes")
        assertEquals(1, c2pa.signCalls)
        assertEquals(sha256Hex(expectedSignedBytes), nostr.ofKind(KIND_FILE_METADATA).last().tag("x"))
    }

    @Test
    fun a_job_that_already_has_an_event_id_is_not_republished() = runTest {
        val first = useCase().publish(newJob()).toList().last()
        val relayCalls = nostr.published.size

        val again = useCase().publish(first).toList()

        assertEquals(PublishStatus.PUBLISHED, again.last().photo.status)
        assertEquals(relayCalls, nostr.published.size, "an accepted event must not be duplicated on the relays")
        assertEquals(first.photo.nostrEventId, again.last().photo.nostrEventId)
    }

    @Test
    fun the_servers_reported_hash_is_authoritative() = runTest {
        // The server is trusted for what it will actually serve; the pipeline records that
        // rather than the locally computed value, and the event's x tag follows it.
        blossom.reportHash = "ab".repeat(32)

        val final = useCase().publish(newJob()).toList().last()

        assertEquals("ab".repeat(32), final.photo.sha256)
        assertEquals("ab".repeat(32), nostr.ofKind(KIND_FILE_METADATA).single().tag("x"))
    }

    // ---- publish as a linked account -------------------------------------------------

    @Test
    fun publishing_as_a_linked_account_signs_events_with_the_users_key_but_attests_with_the_device() = runTest {
        val device = identities.getOrCreate()
        linked.linkSignerApp()
        val job = newJob(caption = "Mine").copy(publishAs = PublishAs.LINKED_ACCOUNT)

        val final = useCase().publish(job).toList().last()

        assertEquals(PublishStatus.PUBLISHED, final.photo.status)
        val event = nostr.ofKind(KIND_FILE_METADATA).single()
        assertEquals(signerApp.userKey.publicKeyHex, event.pubkey, "the event is the user's")
        assertEquals(signerApp.userKey.publicKeyHex, final.photo.nostrPubkey)
        assertEquals(device.publicKeyHex, event.tag("device"), "but the device tag still names the attesting key")
        assertTrue(Secp256k1.verifySchnorr(event.sig.hexToBytes(), event.id.hexToBytes(), signerApp.userKey.publicKey))

        // The Content Credential and the upload are the device's regardless of attribution.
        assertEquals(device.npub, c2pa.contexts.single().npub)
        assertEquals(signerApp.userKey.publicKeyHex, nostr.ofKind(1).single().pubkey, "the note follows the same signer")
        assertEquals(2, signerApp.signed.size)
    }

    @Test
    fun publishing_as_a_linked_account_fails_cleanly_when_none_is_linked() = runTest {
        val job = newJob().copy(publishAs = PublishAs.LINKED_ACCOUNT)

        val last = useCase().publish(job).toList().last()

        assertEquals(PublishStatus.FAILED, last.photo.status)
        assertEquals(PublishError.LINKED_SIGNER_FAILED, last.photo.error)
        assertNotNull(last.photo.blossomUrl, "the upload had already succeeded and is kept")
        assertTrue(nostr.published.isEmpty())
        assertTrue(files.deleted.isEmpty())
    }

    @Test
    fun publishing_as_a_linked_account_fails_cleanly_when_this_build_has_no_linking() = runTest {
        val job = newJob().copy(publishAs = PublishAs.LINKED_ACCOUNT)

        val last = useCase(withLinking = false).publish(job).toList().last()

        assertEquals(PublishError.LINKED_SIGNER_FAILED, last.photo.error)
    }

    @Test
    fun a_signer_that_refuses_is_a_retryable_signing_failure_not_a_lost_photo() = runTest {
        linked.linkSignerApp()
        signerApp.refuse = true
        val job = newJob().copy(publishAs = PublishAs.LINKED_ACCOUNT)

        val last = useCase().publish(job).toList().last()

        assertEquals(PublishStatus.FAILED, last.photo.status)
        assertEquals(PublishError.LINKED_SIGNER_FAILED, last.photo.error)
        assertEquals(PublishStatus.BLOSSOM_UPLOADED, last.photo.error!!.resumeAt)
        assertNotNull(last.photo.blossomUrl)
        assertNotNull(last.photo.localPath)
        assertTrue(nostr.published.isEmpty(), "nothing reaches a relay without a signature")

        // The user approves in the signer app and retries: only the relay stage runs.
        signerApp.refuse = false
        val final = useCase().publish(last).toList().last()
        assertEquals(PublishStatus.PUBLISHED, final.photo.status)
        assertEquals(signerApp.userKey.publicKeyHex, final.photo.nostrPubkey)
        assertEquals(1, blossom.uploads.size)
    }

    @Test
    fun switching_back_to_the_device_after_linking_publishes_as_the_device() = runTest {
        val device = identities.getOrCreate()
        linked.linkSignerApp()

        val final = useCase().publish(newJob().copy(publishAs = PublishAs.DEVICE)).toList().last()

        assertEquals(device.publicKeyHex, nostr.ofKind(KIND_FILE_METADATA).single().pubkey)
        assertEquals(device.publicKeyHex, final.photo.nostrPubkey)
        assertTrue(signerApp.signed.isEmpty(), "the linked signer is not consulted")
    }
}
