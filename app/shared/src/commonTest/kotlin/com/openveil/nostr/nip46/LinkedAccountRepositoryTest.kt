package com.openveil.nostr.nip46

import com.openveil.crypto.hexToBytes
import com.openveil.crypto.toHex
import com.openveil.net.createHttpClient
import com.openveil.nostr.KIND_FILE_METADATA
import com.openveil.nostr.computeEventId
import com.openveil.nostr.nip55.ExternalSignerException
import com.openveil.nostr.nip55.SignerPermission
import com.openveil.testing.FakeSignerApp
import com.openveil.testing.InMemorySecureStorage
import com.openveil.testing.freshIdentity
import fr.acinq.secp256k1.Secp256k1
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The signer-app (NIP-55) path and the storage behaviour, which need no network.
 * The bunker (NIP-46) path is covered end to end by Nip46LoopbackTest.
 */
class LinkedAccountRepositoryTest {

    private val storage = InMemorySecureStorage()
    private val signerApp = FakeSignerApp()

    private fun repository(app: FakeSignerApp? = signerApp) =
        LinkedAccountRepository(storage, createHttpClient(), signerApp = app)

    @Test
    fun nothing_is_linked_by_default() = runTest {
        val repo = repository()
        assertNull(repo.current())
        assertNull(repo.signer())
    }

    @Test
    fun linking_the_signer_app_records_the_account_and_asks_only_for_publishing_permissions() = runTest {
        val account = repository().linkSignerApp()

        assertEquals(signerApp.userKey.publicKeyHex, account.userPubkeyHex)
        assertEquals(signerApp.userKey.npub, account.npub)
        assertEquals(LinkMethod.SIGNER_APP, account.method)
        assertEquals(signerApp.packageName, account.signerPackage)
        assertNull(account.remoteSignerPubkey)

        assertEquals(
            listOf(SignerPermission("sign_event", KIND_FILE_METADATA), SignerPermission("sign_event", 1)),
            signerApp.requestedPermissions.single(),
            "the app must not ask for more than it needs to publish",
        )
    }

    @Test
    fun a_linked_signer_app_survives_a_restart() = runTest {
        val linked = repository().linkSignerApp()

        val restarted = repository()

        assertEquals(linked, restarted.current())
        val signer = assertNotNull(restarted.signer())
        assertEquals(signerApp.userKey.publicKeyHex, signer.publicKeyHex)
        assertEquals(signerApp.userKey.npub, signer.npub)
    }

    @Test
    fun the_signer_app_signer_produces_events_that_verify_under_the_users_key() = runTest {
        repository().linkSignerApp()
        val signer = assertNotNull(repository().signer())

        val tags = listOf(listOf("url", "https://blossom.test/a.jpg"), listOf("device", "cd".repeat(32)))
        val event = signer.signEvent(KIND_FILE_METADATA, "hello", tags, 1_700_000_000L)

        assertEquals(signerApp.userKey.publicKeyHex, event.pubkey)
        assertEquals(tags, event.tags)
        assertEquals(computeEventId(event.pubkey, event.createdAt, event.kind, event.tags, event.content), event.id)
        assertTrue(Secp256k1.verifySchnorr(event.sig.hexToBytes(), event.id.hexToBytes(), signerApp.userKey.publicKey))
    }

    @Test
    fun the_signer_apps_refusal_propagates_and_leaves_nothing_linked() = runTest {
        signerApp.refuse = true
        val repo = repository()

        assertFailsWith<ExternalSignerException> { repo.linkSignerApp() }

        assertNull(repo.current())
        assertFalse(storage.contains("nostr.linked_account"))
    }

    @Test
    fun linking_a_signer_app_is_not_offered_where_none_exists() = runTest {
        val repo = repository(app = null)

        assertFalse(repo.signerAppAvailable)
        assertFailsWith<IllegalStateException> { repo.linkSignerApp() }
    }

    @Test
    fun availability_tracks_whether_the_app_is_installed() {
        signerApp.isInstalled = false
        assertFalse(repository().signerAppAvailable)
        signerApp.isInstalled = true
        assertTrue(repository().signerAppAvailable)
    }

    @Test
    fun unlinking_forgets_the_account_everywhere() = runTest {
        val repo = repository()
        repo.linkSignerApp()

        repo.unlink()

        assertNull(repo.current())
        assertNull(repo.signer())
        assertFalse(storage.contains("nostr.linked_account"))
        assertNull(repository().current(), "and a restart does not resurrect it")
    }

    @Test
    fun a_stored_link_the_device_can_no_longer_read_is_simply_not_linked() = runTest {
        // Unlike the device key, losing a link is recoverable: the user pairs again. So
        // this must read as "not linked" rather than throw, and must not block publishing
        // as the device.
        repository().linkSignerApp()
        storage.unreadable += "nostr.linked_account"

        val repo = repository()
        assertNull(repo.current())
        assertNull(repo.signer())
    }

    @Test
    fun a_corrupt_record_is_ignored_rather_than_crashing_startup() = runTest {
        storage.seed("nostr.linked_account", "{not json".encodeToByteArray())

        assertNull(repository().current())
    }

    @Test
    fun a_record_written_before_link_methods_existed_is_read_as_a_bunker_session() = runTest {
        val clientKey = freshIdentity()
        val signerPubkey = freshIdentity().publicKeyHex
        val userPubkey = freshIdentity().publicKeyHex
        val legacy = """{"user_pubkey":"$userPubkey","client_secret":"${clientKey.exportPrivateKey().toHex()}","signer_pubkey":"$signerPubkey","relays":["wss://relay.example"]}"""
        storage.seed("nostr.linked_account", legacy.encodeToByteArray())

        val account = assertNotNull(repository().current())

        assertEquals(LinkMethod.BUNKER, account.method)
        assertEquals(userPubkey, account.userPubkeyHex)
        assertEquals(signerPubkey, account.remoteSignerPubkey)
        assertEquals(listOf("wss://relay.example"), account.relays)
    }

    @Test
    fun a_signer_app_session_does_not_persist_any_key_material() = runTest {
        repository().linkSignerApp()

        val record = storage.getBytes("nostr.linked_account")!!.decodeToString()

        assertFalse(record.contains("client_secret"), "NIP-55 needs no conversation key, so none may be stored")
        assertTrue(record.contains(signerApp.userKey.publicKeyHex))
    }
}
