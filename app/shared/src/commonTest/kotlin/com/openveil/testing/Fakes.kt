package com.openveil.testing

import com.openveil.crypto.generatePrivateKey
import com.openveil.crypto.hexToBytes
import com.openveil.crypto.sha256Hex
import com.openveil.domain.model.AppResult
import com.openveil.domain.model.BlossomUploadResult
import com.openveil.domain.model.CapturedImage
import com.openveil.domain.model.Photo
import com.openveil.domain.model.PublishError
import com.openveil.domain.model.PublishStatus
import com.openveil.domain.model.SignedAsset
import com.openveil.domain.service.C2paService
import com.openveil.domain.service.C2paSigningContext
import com.openveil.domain.service.C2paVerification
import com.openveil.domain.service.FileStorage
import com.openveil.domain.service.SecureStorage
import com.openveil.domain.service.SecureStorageUnreadable
import com.openveil.blossom.BlossomClient
import com.openveil.nostr.NostrClient
import com.openveil.nostr.NostrEvent
import com.openveil.nostr.NostrIdentity
import com.openveil.nostr.PublishOutcome
import com.openveil.nostr.RelayOutcome
import com.openveil.nostr.nip55.ExternalSignerAccount
import com.openveil.nostr.nip55.ExternalSignerApp
import com.openveil.nostr.nip55.ExternalSignerException
import com.openveil.nostr.nip55.SignerPermission
import fr.acinq.secp256k1.Secp256k1
import kotlin.time.Instant

/**
 * Hand-written fakes for the pipeline's collaborators.
 *
 * No mocking library: the interfaces are small, and a fake that records calls and can be
 * told to fail reads more clearly than a chain of stubbing DSL. Each fake keeps the
 * evidence a test needs (what was uploaded, what was signed) as plain fields.
 */

/** A genuinely valid 1x1 JPEG, the same fixture the live integration test uses. */
val FIXTURE_JPEG: ByteArray = (
    "ffd8ffe000104a46494600010101006000600000ffdb004300080606070605080707070909080a0c" +
        "140d0c0b0b0c1912130f141d1a1f1e1d1a1c1c20242e2720222c231c1c2837292c3031343434" +
        "1f27393d38323c2e333432ffc0000b080001000101011100ffc4001400010000000000000000" +
        "0000000000000009ffc40014100100000000000000000000000000000000ffda000801" +
        "0100003f002a9fffd9"
    ).hexToBytes()

fun freshIdentity(): NostrIdentity = NostrIdentity(generatePrivateKey { Secp256k1.secKeyVerify(it) })

fun fixtureCapture(bytes: ByteArray = FIXTURE_JPEG) = CapturedImage(
    bytes = bytes,
    mimeType = "image/jpeg",
    width = 1,
    height = 1,
    capturedAt = Instant.fromEpochSeconds(1_700_000_000),
)

fun fixturePhoto(id: String = "ov_test_1", caption: String? = null) = Photo(
    id = id,
    caption = caption,
    status = PublishStatus.CAPTURED,
    createdAt = Instant.fromEpochSeconds(1_700_000_000),
    updatedAt = Instant.fromEpochSeconds(1_700_000_000),
)

class InMemorySecureStorage : SecureStorage {
    private val values = mutableMapOf<String, ByteArray>()

    /** Keys whose reads must throw, simulating a Keystore whose wrapping key is gone. */
    val unreadable = mutableSetOf<String>()

    override suspend fun putBytes(key: String, value: ByteArray) { values[key] = value.copyOf() }
    override suspend fun getBytes(key: String): ByteArray? {
        if (key in unreadable) throw SecureStorageUnreadable(key)
        return values[key]?.copyOf()
    }
    override suspend fun remove(key: String) { values.remove(key) }
    override suspend fun contains(key: String): Boolean = key in values

    /** Writes raw bytes, bypassing any encoding, for corruption and legacy-format tests. */
    fun seed(key: String, value: ByteArray) { values[key] = value }
}

class InMemoryFileStorage : FileStorage {
    val files = mutableMapOf<String, ByteArray>()
    val deleted = mutableListOf<String>()

    override suspend fun writeSignedMaster(photoId: String, bytes: ByteArray): String {
        val path = "masters/$photoId.jpg"
        files[path] = bytes.copyOf()
        return path
    }
    override suspend fun readSignedMaster(path: String): ByteArray? = files[path]?.copyOf()
    override suspend fun deleteSignedMaster(path: String) {
        files.remove(path)
        deleted += path
    }
    override suspend fun listPendingMasters(): List<String> = files.keys.toList()
}

/**
 * Stands in for the C2PA SDK by appending a marker to the bytes, so "signed" output is
 * distinguishable from the capture and the pipeline's hash-after-signing rule is testable.
 */
class FakeC2paService(
    var failWith: PublishError? = null,
    var available: Boolean = true,
) : C2paService {
    val contexts = mutableListOf<C2paSigningContext>()
    var signCalls = 0
        private set

    override suspend fun signImage(image: CapturedImage, context: C2paSigningContext): AppResult<SignedAsset> {
        signCalls++
        contexts += context
        failWith?.let { return AppResult.Failure(it, "fake failure") }
        return AppResult.Success(SignedAsset(sign(image.bytes), image.mimeType, manifestId = "urn:uuid:fake-manifest"))
    }

    override suspend fun verify(bytes: ByteArray, mimeType: String): C2paVerification =
        if (bytes.size > MARKER.size && bytes.copyOfRange(bytes.size - MARKER.size, bytes.size).contentEquals(MARKER)) {
            C2paVerification.Valid(manifestId = "urn:uuid:fake-manifest", signerName = "fake", trusted = false)
        } else {
            C2paVerification.NotPresent
        }

    override suspend fun isSigningAvailable(): Boolean = available

    companion object {
        val MARKER = "<c2pa>".encodeToByteArray()
        fun sign(bytes: ByteArray): ByteArray = bytes + MARKER
    }
}

class FakeBlossomClient(
    var failWith: PublishError? = null,
    /** When set, the descriptor reports this hash instead of the real one. */
    var reportHash: String? = null,
    val serverUrl: String = "https://blossom.test",
) : BlossomClient {
    val uploads = mutableListOf<Pair<SignedAsset, String>>()
    val blobs = mutableMapOf<String, ByteArray>()

    override suspend fun upload(asset: SignedAsset, sha256: String): AppResult<BlossomUploadResult> {
        uploads += asset to sha256
        failWith?.let { return AppResult.Failure(it, "fake blossom failure") }
        val hash = reportHash ?: sha256Hex(asset.bytes)
        blobs[hash] = asset.bytes.copyOf()
        return AppResult.Success(
            BlossomUploadResult(
                url = "$serverUrl/$hash.jpg",
                sha256 = hash,
                size = asset.bytes.size.toLong(),
                mimeType = asset.mimeType,
                serverUrl = serverUrl,
            )
        )
    }

    override suspend fun get(sha256: String, serverUrl: String?): AppResult<ByteArray> =
        blobs[sha256]?.let { AppResult.Success(it.copyOf()) }
            ?: AppResult.Failure(PublishError.BLOSSOM_UPLOAD_FAILED, "not stored")
}

class FakeNostrClient(
    var failWith: PublishError? = null,
    /** Kinds the relay refuses, for testing that a rejected companion note is harmless. */
    val rejectKinds: MutableSet<Int> = mutableSetOf(),
    val relayUrl: String = "wss://relay.test",
) : NostrClient {
    val published = mutableListOf<NostrEvent>()

    override suspend fun publish(event: NostrEvent): AppResult<PublishOutcome> {
        published += event
        failWith?.let { return AppResult.Failure(it, "fake relay failure") }
        if (event.kind in rejectKinds) {
            return AppResult.Failure(PublishError.NOSTR_PUBLISH_FAILED, "kind ${event.kind} rejected")
        }
        return AppResult.Success(PublishOutcome(listOf(RelayOutcome(relayUrl, accepted = true))))
    }

    fun ofKind(kind: Int) = published.filter { it.kind == kind }
}

/**
 * A NIP-55 signer app that signs in-process with [userKey]. Records the permissions it
 * was asked for, and can be told to refuse, as Amber does when the user taps Reject.
 */
class FakeSignerApp(
    val userKey: NostrIdentity = freshIdentity(),
    override var isInstalled: Boolean = true,
    var refuse: Boolean = false,
    val packageName: String = "com.greenart7c3.nostrsigner",
) : ExternalSignerApp {
    val requestedPermissions = mutableListOf<List<SignerPermission>>()
    val signed = mutableListOf<NostrEvent>()

    override suspend fun getPublicKey(permissions: List<SignerPermission>): ExternalSignerAccount {
        requestedPermissions += permissions
        if (refuse) throw ExternalSignerException("User rejected")
        return ExternalSignerAccount(userKey.publicKeyHex, packageName)
    }

    override suspend fun signEvent(
        kind: Int,
        content: String,
        tags: List<List<String>>,
        createdAt: Long,
        account: ExternalSignerAccount,
    ): NostrEvent {
        if (refuse) throw ExternalSignerException("User rejected")
        require(account.packageName == packageName) { "request addressed to the wrong signer" }
        return userKey.signEvent(kind, content, tags, createdAt).also { signed += it }
    }
}
