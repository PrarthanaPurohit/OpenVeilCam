package com.openveil.nostr.nip46

import com.openveil.crypto.encodeNpub
import com.openveil.crypto.generatePrivateKey
import com.openveil.crypto.hexToBytes
import com.openveil.crypto.toHex
import com.openveil.domain.service.SecureStorage
import com.openveil.domain.service.SecureStorageUnreadable
import com.openveil.nostr.KIND_FILE_METADATA
import com.openveil.nostr.NostrEvent
import com.openveil.nostr.NostrIdentity
import com.openveil.nostr.NostrSigner
import com.openveil.nostr.nip55.ExternalSignerAccount
import com.openveil.nostr.nip55.ExternalSignerApp
import com.openveil.nostr.nip55.SignerPermission
import fr.acinq.secp256k1.Secp256k1
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** How a linked account signs. Shown in the UI, and decides which client to build. */
enum class LinkMethod {
    /** NIP-46 over relays, paired from a `bunker://` link or a scanned `nostrconnect://`. */
    BUNKER,

    /** NIP-55 signer app on this device (Amber). */
    SIGNER_APP,
}

/**
 * A Nostr account the user has linked. Public facts only; the conversation key material
 * stays inside the repository.
 */
data class LinkedAccount(
    /** The user's own pubkey -- what their captures will be published under. */
    val userPubkeyHex: String,
    val method: LinkMethod,
    /** [LinkMethod.BUNKER] only. */
    val remoteSignerPubkey: String? = null,
    val relays: List<String> = emptyList(),
    /** [LinkMethod.SIGNER_APP] only. */
    val signerPackage: String? = null,
) {
    val npub: String get() = encodeNpub(userPubkeyHex.hexToBytes())
}

/**
 * Owns the optional link to the user's own Nostr account.
 *
 * This is deliberately separate from [com.openveil.nostr.NostrIdentityRepository]. The
 * device identity is permanent and must never be replaced; a linked account is a session
 * the user can drop and re-establish at will, and losing it costs a re-pair, not a
 * published history. The two must not share failure handling.
 *
 * Three ways to pair, all ending in the same [LinkedAccount]:
 *  - [link]: a `bunker://` link, pasted or scanned;
 *  - [startPairing]: we show a `nostrconnect://` QR, a signer on another device scans it;
 *  - [linkSignerApp]: a NIP-55 signer app on this phone.
 *
 * In none of them does the user's private key come anywhere near this app.
 */
class LinkedAccountRepository(
    private val secureStorage: SecureStorage,
    private val httpClient: HttpClient,
    /** Null on platforms with no signer-app convention. */
    private val signerApp: ExternalSignerApp? = null,
    /** Relays offered in a `nostrconnect://` link. Should be ones common signers already use. */
    private val pairingRelays: List<String> = DEFAULT_PAIRING_RELAYS,
    private val appName: String = "OpenVeil",
) {
    private class Session(val account: LinkedAccount, val clientKey: NostrIdentity?) {
        var client: Nip46Client? = null
    }

    private val mutex = Mutex()
    private var cached: Session? = null
    private var loaded = false

    private val json = Json { ignoreUnknownKeys = true }

    private val _authUrls = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /**
     * URLs a bunker wants opened for the user to approve a request in a browser.
     * The UI collects this and opens them; the pending request completes once approved.
     */
    val authUrls: SharedFlow<String> = _authUrls.asSharedFlow()

    /** Whether [linkSignerApp] is an option on this device right now. */
    val signerAppAvailable: Boolean get() = signerApp?.isInstalled == true

    /** The linked account, or null when none is linked. */
    suspend fun current(): LinkedAccount? = mutex.withLock { loadLocked()?.account }

    /**
     * Pairs with a bunker from a `bunker://` link and records the session.
     *
     * The network round trips run outside the lock: a connect can wait a couple of
     * minutes for the user to approve in their signer app, and [current] must not be
     * frozen for that long.
     *
     * Throws [IllegalArgumentException] for a malformed link and [Nip46Exception] when
     * the signer refuses or cannot be reached. Both messages are safe to show.
     */
    suspend fun link(bunkerLink: String): LinkedAccount {
        val uri = BunkerUri.parse(bunkerLink)
        val clientKey = newClientKey()
        val client = newClient(clientKey, uri.remoteSignerPubkey, uri.relays)

        client.connect(uri.secret, permissions = BUNKER_PERMISSIONS)
        return completeBunkerLink(clientKey, client, uri.remoteSignerPubkey, uri.relays)
    }

    /**
     * Begins a `nostrconnect://` pairing. Show [NostrConnectPairing.uri] as a QR code,
     * then call [awaitPairing] with the same object; cancel the coroutine to abandon it.
     */
    fun startPairing(): NostrConnectPairing = NostrConnectPairing(
        httpClient = httpClient,
        clientKey = newClientKey(),
        relays = pairingRelays,
        appName = appName,
        permissions = BUNKER_PERMISSIONS,
    )

    suspend fun awaitPairing(pairing: NostrConnectPairing): LinkedAccount {
        val signerPubkey = pairing.awaitSigner()
        val client = newClient(pairing.clientKey, signerPubkey, pairing.relays)
        // No `connect` here: the signer initiated, and already considers us connected.
        return completeBunkerLink(pairing.clientKey, client, signerPubkey, pairing.relays)
    }

    /** Pairs with the signer app on this device. Throws [com.openveil.nostr.nip55.ExternalSignerException]. */
    suspend fun linkSignerApp(): LinkedAccount {
        val app = signerApp ?: throw IllegalStateException("No signer app on this platform")
        val account = app.getPublicKey(SIGNER_APP_PERMISSIONS)
        val linked = LinkedAccount(
            userPubkeyHex = account.userPubkeyHex,
            method = LinkMethod.SIGNER_APP,
            signerPackage = account.packageName,
        )
        mutex.withLock {
            secureStorage.putBytes(KEY_LINKED_ACCOUNT, encode(linked, clientKey = null))
            cached = Session(linked, clientKey = null)
            loaded = true
        }
        return linked
    }

    suspend fun unlink() = mutex.withLock {
        secureStorage.remove(KEY_LINKED_ACCOUNT)
        cached = null
        loaded = true
    }

    /** A signer for the linked account, or null when none is linked. */
    suspend fun signer(): NostrSigner? = mutex.withLock {
        val session = loadLocked() ?: return null
        val account = session.account
        when (account.method) {
            LinkMethod.BUNKER -> {
                val clientKey = session.clientKey ?: return null
                val client = session.client ?: newClient(
                    clientKey, account.remoteSignerPubkey ?: return null, account.relays,
                ).also { session.client = it }
                RemoteSigner(client, account.userPubkeyHex)
            }
            LinkMethod.SIGNER_APP -> {
                val app = signerApp ?: return null
                val packageName = account.signerPackage ?: return null
                SignerAppSigner(app, ExternalSignerAccount(account.userPubkeyHex, packageName))
            }
        }
    }

    private suspend fun completeBunkerLink(
        clientKey: NostrIdentity,
        client: Nip46Client,
        signerPubkey: String,
        relays: List<String>,
    ): LinkedAccount {
        val userPubkey = client.getPublicKey()
        val account = LinkedAccount(
            userPubkeyHex = userPubkey,
            method = LinkMethod.BUNKER,
            remoteSignerPubkey = signerPubkey,
            relays = relays,
        )
        mutex.withLock {
            secureStorage.putBytes(KEY_LINKED_ACCOUNT, encode(account, clientKey))
            cached = Session(account, clientKey).also { it.client = client }
            loaded = true
        }
        return account
    }

    private fun newClientKey() = NostrIdentity(generatePrivateKey { Secp256k1.secKeyVerify(it) })

    private fun newClient(clientKey: NostrIdentity, signerPubkey: String, relays: List<String>) =
        Nip46Client(
            httpClient = httpClient,
            clientKey = clientKey,
            remoteSignerPubkey = signerPubkey,
            relays = relays,
            onAuthUrl = { _authUrls.tryEmit(it) },
        )

    private suspend fun loadLocked(): Session? {
        if (loaded) return cached
        loaded = true
        // Unlike the device key, an unreadable link is not a catastrophe: the user pairs
        // again and nothing already published is affected. So it reads as "not linked"
        // rather than propagating.
        val bytes = try {
            secureStorage.getBytes(KEY_LINKED_ACCOUNT)
        } catch (e: SecureStorageUnreadable) {
            null
        } ?: return null
        cached = decode(bytes)
        return cached
    }

    private fun encode(account: LinkedAccount, clientKey: NostrIdentity?): ByteArray =
        buildJsonObject {
            put("method", account.method.name)
            put("user_pubkey", account.userPubkeyHex)
            if (clientKey != null) put("client_secret", clientKey.exportPrivateKey().toHex())
            account.remoteSignerPubkey?.let { put("signer_pubkey", it) }
            putJsonArray("relays") { for (r in account.relays) add(r) }
            account.signerPackage?.let { put("signer_package", it) }
        }.toString().encodeToByteArray()

    private fun decode(bytes: ByteArray): Session? = runCatching {
        val obj = json.parseToJsonElement(bytes.decodeToString()).jsonObject
        // Sessions written before `method` existed are all bunker sessions.
        val method = obj["method"]?.jsonPrimitive?.content?.let(LinkMethod::valueOf) ?: LinkMethod.BUNKER
        val account = LinkedAccount(
            userPubkeyHex = obj["user_pubkey"]!!.jsonPrimitive.content,
            method = method,
            remoteSignerPubkey = obj["signer_pubkey"]?.jsonPrimitive?.content,
            relays = obj["relays"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
            signerPackage = obj["signer_package"]?.jsonPrimitive?.content,
        )
        val clientKey = obj["client_secret"]?.jsonPrimitive?.content?.let { NostrIdentity(it.hexToBytes()) }
        Session(account, clientKey)
    }.getOrNull()

    private companion object {
        const val KEY_LINKED_ACCOUNT = "nostr.linked_account"

        /** Only what publishing needs: the file-metadata event and the companion note. */
        val BUNKER_PERMISSIONS = listOf("sign_event:$KIND_FILE_METADATA", "sign_event:1")
        val SIGNER_APP_PERMISSIONS = listOf(
            SignerPermission("sign_event", KIND_FILE_METADATA),
            SignerPermission("sign_event", 1),
        )

        /**
         * Where a `nostrconnect://` link tells the signer to meet us. nsec.app's relay is
         * the one most signers already hold a connection to; the others are fallbacks.
         */
        val DEFAULT_PAIRING_RELAYS = listOf(
            "wss://relay.nsec.app",
            "wss://relay.damus.io",
            "wss://nos.lol",
        )
    }
}

/** A [NostrSigner] whose key lives in a remote bunker. Every signature is a round trip. */
private class RemoteSigner(
    private val client: Nip46Client,
    override val publicKeyHex: String,
) : NostrSigner {
    override val npub: String = encodeNpub(publicKeyHex.hexToBytes())

    override suspend fun signEvent(
        kind: Int,
        content: String,
        tags: List<List<String>>,
        createdAt: Long,
    ): NostrEvent = client.signEvent(kind, content, tags, createdAt, expectedPubkey = publicKeyHex)
}

/** A [NostrSigner] backed by a signer app on this device. */
private class SignerAppSigner(
    private val app: ExternalSignerApp,
    private val account: ExternalSignerAccount,
) : NostrSigner {
    override val publicKeyHex: String get() = account.userPubkeyHex
    override val npub: String = encodeNpub(account.userPubkeyHex.hexToBytes())

    override suspend fun signEvent(
        kind: Int,
        content: String,
        tags: List<List<String>>,
        createdAt: Long,
    ): NostrEvent = app.signEvent(kind, content, tags, createdAt, account)
}
