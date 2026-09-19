package com.openveil.nostr

import com.openveil.domain.service.SecureStorageUnreadable
import com.openveil.testing.InMemorySecureStorage
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The device identity is generated exactly once and never replaced. Every test here is a
 * way that rule could silently break -- and each would orphan everything the key ever
 * published, with no way back for the user.
 */
class NostrIdentityRepositoryTest {

    private val storage = InMemorySecureStorage()

    @Test
    fun the_first_call_generates_and_persists_a_key() = runTest {
        val repo = NostrIdentityRepository(storage)
        assertFalse(repo.exists())

        val identity = repo.getOrCreate()

        assertTrue(repo.exists())
        assertEquals(32, storage.getBytes("nostr.private_key")!!.size)
        assertEquals(identity.publicKeyHex, NostrIdentity(storage.getBytes("nostr.private_key")!!).publicKeyHex)
    }

    @Test
    fun later_calls_return_the_same_identity_without_touching_storage_again() = runTest {
        val repo = NostrIdentityRepository(storage)
        val first = repo.getOrCreate()

        assertSame(first, repo.getOrCreate())
    }

    @Test
    fun a_restart_loads_the_stored_key_rather_than_minting_another() = runTest {
        val original = NostrIdentityRepository(storage).getOrCreate()

        val reloaded = NostrIdentityRepository(storage).getOrCreate()

        assertEquals(original.publicKeyHex, reloaded.publicKeyHex)
    }

    @Test
    fun concurrent_first_calls_agree_on_one_key() = runTest {
        val repo = NostrIdentityRepository(storage)

        val identities = (1..16).map { async { repo.getOrCreate() } }.awaitAll()

        assertEquals(1, identities.map { it.publicKeyHex }.toSet().size, "a race must not produce two identities")
        assertEquals(identities.first().publicKeyHex, NostrIdentityRepository(storage).getOrCreate().publicKeyHex)
    }

    @Test
    fun peek_never_creates_a_key() = runTest {
        val repo = NostrIdentityRepository(storage)

        assertNull(repo.peek())
        assertFalse(storage.contains("nostr.private_key"))

        val created = repo.getOrCreate()
        assertEquals(created.publicKeyHex, repo.peek()!!.publicKeyHex)
    }

    @Test
    fun a_stored_key_of_the_wrong_size_is_refused_not_overwritten() = runTest {
        storage.seed("nostr.private_key", ByteArray(16) { 1 })
        val repo = NostrIdentityRepository(storage)

        assertFailsWith<IllegalStateException> { repo.getOrCreate() }

        assertEquals(16, storage.getBytes("nostr.private_key")!!.size, "the unexpected value is left in place for recovery")
    }

    @Test
    fun an_unreadable_key_propagates_rather_than_minting_a_replacement() = runTest {
        NostrIdentityRepository(storage).getOrCreate()
        storage.unreadable += "nostr.private_key"
        val repo = NostrIdentityRepository(storage)

        assertFailsWith<SecureStorageUnreadable> { repo.getOrCreate() }

        assertFailsWith<SecureStorageUnreadable> { repo.getOrCreate() }
    }
}
