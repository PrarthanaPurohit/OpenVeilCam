package com.openveil.ui.capture

import com.openveil.blossom.BlossomClient
import com.openveil.crypto.generatePrivateKey
import com.openveil.crypto.hexToBytes
import com.openveil.crypto.sha256Hex
import com.openveil.domain.model.AppResult
import com.openveil.domain.model.BlossomUploadResult
import com.openveil.domain.model.CapturedImage
import com.openveil.domain.model.PublishError
import com.openveil.domain.model.PublishStatus
import com.openveil.domain.model.SignedAsset
import com.openveil.domain.service.C2paService
import com.openveil.domain.service.C2paSigningContext
import com.openveil.domain.service.C2paVerification
import com.openveil.domain.service.FileStorage
import com.openveil.domain.service.SecureStorage
import com.openveil.net.createHttpClient
import com.openveil.nostr.NostrClient
import com.openveil.nostr.NostrEvent
import com.openveil.nostr.NostrIdentity
import com.openveil.nostr.NostrIdentityRepository
import com.openveil.nostr.PublishOutcome
import com.openveil.nostr.RelayOutcome
import com.openveil.nostr.nip46.LinkedAccountRepository
import com.openveil.nostr.nip55.ExternalSignerAccount
import com.openveil.nostr.nip55.ExternalSignerApp
import com.openveil.nostr.nip55.ExternalSignerException
import com.openveil.nostr.nip55.SignerPermission
import com.openveil.publish.PublishAs
import com.openveil.publish.PublishPhotoUseCase
import com.openveil.ui.AppDependencies
import com.openveil.ui.components.VerificationState
import fr.acinq.secp256k1.Secp256k1
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Session-state rules the screens rely on. The pipeline itself is tested in :shared; here
 * the collaborators are the smallest fakes that let the real use case run to completion.
 */
class CaptureCoordinatorTest {

    private val fixture = (
        "ffd8ffe000104a46494600010101006000600000ffdb004300080606070605080707070909080a0c" +
            "140d0c0b0b0c1912130f141d1a1f1e1d1a1c1c20242e2720222c231c1c2837292c3031343434" +
            "1f27393d38323c2e333432ffc0000b080001000101011100ffc4001400010000000000000000" +
            "0000000000000009ffc40014100100000000000000000000000000000000ffda000801" +
            "0100003f002a9fffd9"
        ).hexToBytes()

    private fun capture() = CapturedImage(fixture, "image/jpeg", 1, 1, Instant.fromEpochSeconds(1_700_000_000))

    private val secure = object : SecureStorage {
        val values = mutableMapOf<String, ByteArray>()
        override suspend fun putBytes(key: String, value: ByteArray) { values[key] = value }
        override suspend fun getBytes(key: String): ByteArray? = values[key]
        override suspend fun remove(key: String) { values.remove(key) }
        override suspend fun contains(key: String): Boolean = key in values
    }

    private val files = object : FileStorage {
        val stored = mutableMapOf<String, ByteArray>()
        val deleted = mutableListOf<String>()
        override suspend fun writeSignedMaster(photoId: String, bytes: ByteArray): String =
            "masters/$photoId".also { stored[it] = bytes }
        override suspend fun readSignedMaster(path: String): ByteArray? = stored[path]
        override suspend fun deleteSignedMaster(path: String) { stored.remove(path); deleted += path }
        override suspend fun listPendingMasters(): List<String> = stored.keys.toList()
    }

    /** Signing completes only when the test releases it, so "still signing" is observable. */
    private val signingGate = CompletableDeferred<Unit>()

    private val c2pa = object : C2paService {
        var available = true
        override suspend fun signImage(image: CapturedImage, context: C2paSigningContext): AppResult<SignedAsset> {
            signingGate.await()
            return AppResult.Success(SignedAsset(image.bytes + byteArrayOf(0x7f), image.mimeType, "m"))
        }
        override suspend fun verify(bytes: ByteArray, mimeType: String): C2paVerification = C2paVerification.NotPresent
        override suspend fun isSigningAvailable(): Boolean = available
    }

    private val blossom = object : BlossomClient {
        override suspend fun upload(asset: SignedAsset, sha256: String) = AppResult.Success(
            BlossomUploadResult("https://blossom.test/$sha256.jpg", sha256Hex(asset.bytes), asset.bytes.size.toLong(), asset.mimeType, "https://blossom.test")
        )
        override suspend fun get(sha256: String, serverUrl: String?): AppResult<ByteArray> =
            AppResult.Failure(PublishError.BLOSSOM_UPLOAD_FAILED)
    }

    private val nostr = object : NostrClient {
        val published = mutableListOf<NostrEvent>()
        override suspend fun publish(event: NostrEvent): AppResult<PublishOutcome> {
            published += event
            return AppResult.Success(PublishOutcome(listOf(RelayOutcome("wss://relay.test", true))))
        }
    }

    private val userKey = NostrIdentity(generatePrivateKey { Secp256k1.secKeyVerify(it) })

    private val signerApp = object : ExternalSignerApp {
        override var isInstalled = true
        var refuse = false
        override suspend fun getPublicKey(permissions: List<SignerPermission>): ExternalSignerAccount {
            if (refuse) throw ExternalSignerException("User rejected")
            return ExternalSignerAccount(userKey.publicKeyHex, "signer.test")
        }
        override suspend fun signEvent(kind: Int, content: String, tags: List<List<String>>, createdAt: Long, account: ExternalSignerAccount) =
            userKey.signEvent(kind, content, tags, createdAt)
    }

    private val identities = NostrIdentityRepository(secure)
    private val linked = LinkedAccountRepository(secure, createHttpClient(), signerApp = signerApp)

    private val dependencies = object : AppDependencies {
        override val publishPhotoUseCase = PublishPhotoUseCase(c2pa, blossom, nostr, identities, files, "Test", linkedAccounts = linked)
        override val identityRepository = identities
        override val linkedAccountRepository = linked
        override val c2paService: C2paService = c2pa
        override val fileStorage: FileStorage = files
        override val deviceName = "Test"
    }

    /**
     * Drives the coordinator's coroutines, which live in `backgroundScope` so a test can
     * leave signing deliberately unfinished. `advanceUntilIdle` stops when only background
     * work remains; advancing time does not.
     */
    private fun TestScope.settle() {
        advanceTimeBy(1_000)
        runCurrent()
    }

    @Test
    fun capture_starts_signing_immediately_and_finishes_on_its_own() = runTest {
        val coordinator = CaptureCoordinator(dependencies, backgroundScope)

        coordinator.onCaptured(capture())
        settle()
        assertEquals(PublishStatus.C2PA_SIGNING, coordinator.job?.photo?.status)
        assertNull(coordinator.job?.signed)

        signingGate.complete(Unit)
        settle()
        assertEquals(PublishStatus.C2PA_SIGNED, coordinator.job?.photo?.status)
        assertNotNull(coordinator.job?.signed)
        assertEquals(1, files.stored.size, "the signed master is on disk before the user even presses Publish")
    }

    @Test
    fun a_note_typed_while_signing_is_not_overwritten_when_signing_completes() = runTest {
        val coordinator = CaptureCoordinator(dependencies, backgroundScope)
        coordinator.onCaptured(capture())
        settle()

        coordinator.setCaption("Typed during the wait")
        signingGate.complete(Unit)
        settle()

        assertEquals(PublishStatus.C2PA_SIGNED, coordinator.job?.photo?.status)
        assertEquals("Typed during the wait", coordinator.job?.photo?.caption)
    }

    @Test
    fun a_publish_as_choice_made_while_signing_is_kept() = runTest {
        val coordinator = CaptureCoordinator(dependencies, backgroundScope)
        coordinator.linkWithSignerApp {}
        settle()
        coordinator.onCaptured(capture())
        settle()

        coordinator.setPublishAs(PublishAs.LINKED_ACCOUNT)
        signingGate.complete(Unit)
        settle()

        assertEquals(PublishAs.LINKED_ACCOUNT, coordinator.job?.publishAs)
    }

    @Test
    fun publishing_as_a_linked_account_cannot_be_chosen_when_nothing_is_linked() = runTest {
        val coordinator = CaptureCoordinator(dependencies, backgroundScope)
        coordinator.onCaptured(capture())

        coordinator.setPublishAs(PublishAs.LINKED_ACCOUNT)

        assertEquals(PublishAs.DEVICE, coordinator.job?.publishAs)
    }

    @Test
    fun the_choice_is_never_sticky_across_captures() = runTest {
        val coordinator = CaptureCoordinator(dependencies, backgroundScope)
        coordinator.linkWithSignerApp {}
        settle()
        coordinator.onCaptured(capture())
        coordinator.setPublishAs(PublishAs.LINKED_ACCOUNT)
        assertEquals(PublishAs.LINKED_ACCOUNT, coordinator.job?.publishAs)

        coordinator.onCaptured(capture())

        assertEquals(PublishAs.DEVICE, coordinator.job?.publishAs, "every photo starts as the device, by design")
    }

    @Test
    fun unlinking_falls_an_in_flight_capture_back_to_the_device() = runTest {
        val coordinator = CaptureCoordinator(dependencies, backgroundScope)
        coordinator.linkWithSignerApp {}
        settle()
        coordinator.onCaptured(capture())
        coordinator.setPublishAs(PublishAs.LINKED_ACCOUNT)

        coordinator.unlinkAccount()
        settle()

        assertNull(coordinator.linkedAccount)
        assertNull(coordinator.identity.linkedNpub)
        assertEquals(PublishAs.DEVICE, coordinator.job?.publishAs)
    }

    @Test
    fun publish_runs_the_pipeline_to_completion_under_the_chosen_key() = runTest {
        val coordinator = CaptureCoordinator(dependencies, backgroundScope)
        coordinator.linkWithSignerApp {}
        settle()
        coordinator.onCaptured(capture())
        coordinator.setPublishAs(PublishAs.LINKED_ACCOUNT)
        coordinator.setCaption("Mine")
        signingGate.complete(Unit)
        settle()

        coordinator.publish()
        settle()

        val job = assertNotNull(coordinator.job)
        assertEquals(PublishStatus.PUBLISHED, job.photo.status)
        assertEquals(userKey.publicKeyHex, job.photo.nostrPubkey)
        assertEquals(userKey.publicKeyHex, nostr.published.first().pubkey)
        assertEquals(identities.getOrCreate().publicKeyHex, nostr.published.first().tag("device"))
        assertEquals("Mine", job.photo.caption)
        assertTrue(files.stored.isEmpty(), "the master is deleted once published")
    }

    @Test
    fun discard_erases_the_signed_master_even_if_signing_was_still_running() = runTest {
        val coordinator = CaptureCoordinator(dependencies, backgroundScope)
        coordinator.onCaptured(capture())
        settle()

        coordinator.discard()
        assertNull(coordinator.job, "the screen has nothing to show the instant discard is pressed")
        signingGate.complete(Unit)
        settle()

        assertTrue(files.stored.isEmpty(), "a discarded photo must not linger in app storage")
    }

    @Test
    fun discard_after_signing_deletes_the_master() = runTest {
        val coordinator = CaptureCoordinator(dependencies, backgroundScope)
        coordinator.onCaptured(capture())
        signingGate.complete(Unit)
        settle()
        assertEquals(1, files.stored.size)

        coordinator.discard()
        settle()

        assertTrue(files.stored.isEmpty())
        assertEquals(1, files.deleted.size)
    }

    @Test
    fun identity_status_reports_what_is_actually_ready() = runTest {
        c2pa.available = false
        val coordinator = CaptureCoordinator(dependencies, backgroundScope)

        coordinator.refreshIdentity()
        settle()

        val identity = coordinator.identity
        assertEquals(VerificationState.Verified, identity.nostr)
        assertEquals(VerificationState.Failed, identity.contentCredentials, "no signing identity must show as a fault, not a tick")
        assertEquals(VerificationState.Ready, identity.storage)
        assertEquals(identities.getOrCreate().npub, identity.npub)
        assertNull(identity.linkedNpub)
    }

    @Test
    fun linking_with_the_signer_app_updates_the_identity_card_and_calls_back() = runTest {
        val coordinator = CaptureCoordinator(dependencies, backgroundScope)
        var linkedCallback = false

        coordinator.linkWithSignerApp { linkedCallback = true }
        settle()

        assertTrue(linkedCallback)
        assertEquals(userKey.npub, coordinator.linkedAccount?.npub)
        assertEquals(userKey.npub, coordinator.identity.linkedNpub)
        assertEquals(LinkState.Idle, coordinator.linkState)
        assertTrue(coordinator.signerAppAvailable)
    }

    @Test
    fun a_refused_link_reports_the_signers_message_and_links_nothing() = runTest {
        signerApp.refuse = true
        val coordinator = CaptureCoordinator(dependencies, backgroundScope)
        var linkedCallback = false

        coordinator.linkWithSignerApp { linkedCallback = true }
        settle()

        assertFalse(linkedCallback)
        assertNull(coordinator.linkedAccount)
        assertEquals(LinkState.Failed("User rejected"), coordinator.linkState)
    }

    @Test
    fun a_second_link_attempt_is_ignored_while_one_is_in_progress() = runTest {
        // The signer app opens once; opening it twice on a double tap would confuse the
        // user and could leave two results racing to be stored.
        val coordinator = CaptureCoordinator(dependencies, backgroundScope)
        var callbacks = 0

        coordinator.linkWithSignerApp { callbacks++ }
        assertEquals(LinkState.Connecting, coordinator.linkState)
        coordinator.linkWithSignerApp { callbacks++ }
        settle()

        assertEquals(1, callbacks)
    }
}
