package com.openveil.ui.capture

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.openveil.domain.model.CapturedImage
import com.openveil.domain.model.Photo
import com.openveil.domain.model.PublishStatus
import com.openveil.nostr.nip46.LinkedAccount
import com.openveil.publish.PublishAs
import com.openveil.publish.PublishJob
import com.openveil.ui.AppDependencies
import com.openveil.ui.components.VerificationState
import com.openveil.ui.screens.IdentityStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlin.random.Random
import kotlin.time.Clock

/**
 * Session state for one capture, and the driver for the publish pipeline.
 *
 * Signing is started the moment a photo is captured rather than when Publish is pressed,
 * so the wait is absorbed while the user is still deciding on the review screen. By the
 * time they commit, the Content Credential usually already exists and the pipeline skips
 * straight to upload.
 *
 * Held across recomposition by `remember`. MainActivity declares
 * `configChanges="orientation|screenSize|..."`, so a rotation does not recreate the
 * activity and an in-flight publish survives it.
 */
class CaptureCoordinator(
    private val dependencies: AppDependencies,
    private val scope: CoroutineScope,
) {
    var job by mutableStateOf<PublishJob?>(null)
        private set

    var identity by mutableStateOf(
        IdentityStatus(
            nostr = VerificationState.Pending,
            contentCredentials = VerificationState.Pending,
            storage = VerificationState.Pending,
        )
    )
        private set

    /** The user's own Nostr account, if they have linked one. Null is the default state. */
    var linkedAccount by mutableStateOf<LinkedAccount?>(null)
        private set

    var linkState by mutableStateOf<LinkState>(LinkState.Idle)
        private set

    private var signingJob: Job? = null
    private var publishJob: Job? = null

    /** Reads real identity state for the Home card. Nothing here is assumed. */
    fun refreshIdentity() {
        scope.launch {
            val nostrIdentity = runCatching { dependencies.identityRepository.getOrCreate() }.getOrNull()
            val signingReady = runCatching { dependencies.c2paService.isSigningAvailable() }
                .getOrDefault(false)
            linkedAccount = runCatching { dependencies.linkedAccountRepository.current() }.getOrNull()

            identity = IdentityStatus(
                nostr = if (nostrIdentity != null) VerificationState.Verified else VerificationState.Failed,
                contentCredentials =
                    if (signingReady) VerificationState.Verified else VerificationState.Failed,
                // Blossom is only truly known to work when an upload succeeds, so this
                // reports readiness rather than claiming a verified connection.
                storage = VerificationState.Ready,
                npub = nostrIdentity?.npub,
                linkedNpub = linkedAccount?.npub,
            )
        }
    }

    /**
     * Pairs with the user's remote signer. Slow by nature: the user typically has to
     * approve the pairing in their signer app, so this reports progress through
     * [linkState] rather than blocking anything.
     */
    fun linkAccount(bunkerLink: String, onLinked: () -> Unit) {
        if (linkState == LinkState.Connecting) return
        linkState = LinkState.Connecting
        scope.launch {
            runCatching { dependencies.linkedAccountRepository.link(bunkerLink) }
                .onSuccess { account -> onLinkedAccount(account); onLinked() }
                .onFailure { e ->
                    linkState = LinkState.Failed(e.message ?: "Could not connect to the signer")
                }
        }
    }

    /** Whether a NIP-55 signer app (Amber) is installed on this device. */
    val signerAppAvailable: Boolean get() = dependencies.linkedAccountRepository.signerAppAvailable

    /** Pairs with the signer app on this phone. Opens it for the user to pick an account. */
    fun linkWithSignerApp(onLinked: () -> Unit) {
        if (linkState == LinkState.Connecting) return
        linkState = LinkState.Connecting
        scope.launch {
            runCatching { dependencies.linkedAccountRepository.linkSignerApp() }
                .onSuccess { account -> onLinkedAccount(account); onLinked() }
                .onFailure { e -> linkState = LinkState.Failed(e.message ?: "Could not connect to the signer app") }
        }
    }

    /**
     * The `nostrconnect://` link currently being shown as a QR code, or null when not
     * pairing that way. Set by [startPairing]; cleared by success, failure or [cancelPairing].
     */
    var pairingUri by mutableStateOf<String?>(null)
        private set

    private var pairingJob: Job? = null

    /** Shows a QR for a signer on another device to scan, and waits for it to call back. */
    fun startPairing(onLinked: () -> Unit) {
        if (pairingJob?.isActive == true) return
        val pairing = dependencies.linkedAccountRepository.startPairing()
        pairingUri = pairing.uri
        linkState = LinkState.Connecting
        pairingJob = scope.launch {
            runCatching { dependencies.linkedAccountRepository.awaitPairing(pairing) }
                .onSuccess { account -> onLinkedAccount(account); onLinked() }
                .onFailure { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    linkState = LinkState.Failed(e.message ?: "No signer connected")
                }
            pairingUri = null
        }
    }

    fun cancelPairing() {
        pairingJob?.cancel()
        pairingJob = null
        pairingUri = null
        if (linkState == LinkState.Connecting) linkState = LinkState.Idle
    }

    private fun onLinkedAccount(account: LinkedAccount) {
        linkedAccount = account
        identity = identity.copy(linkedNpub = account.npub)
        linkState = LinkState.Idle
        pairingUri = null
    }

    fun unlinkAccount() {
        scope.launch {
            runCatching { dependencies.linkedAccountRepository.unlink() }
            linkedAccount = null
            identity = identity.copy(linkedNpub = null)
            // A capture in progress that was going to publish as the account falls back
            // to the device, rather than failing at publish time for a choice that no
            // longer exists.
            job?.let { if (it.publishAs == PublishAs.LINKED_ACCOUNT) job = it.copy(publishAs = PublishAs.DEVICE) }
        }
    }

    /** Which key signs the Nostr events for the current capture. Never sticky across captures. */
    fun setPublishAs(publishAs: PublishAs) {
        val current = job ?: return
        if (publishAs == PublishAs.LINKED_ACCOUNT && linkedAccount == null) return
        job = current.copy(publishAs = publishAs)
    }

    /** Called from the shutter. Starts Content Credential signing immediately. */
    fun onCaptured(image: CapturedImage) {
        val now = Clock.System.now()
        val photo = Photo(
            id = newPhotoId(),
            mimeType = image.mimeType,
            width = image.width,
            height = image.height,
            fileSize = image.bytes.size.toLong(),
            status = PublishStatus.CAPTURED,
            createdAt = now,
            updatedAt = now,
        )
        val fresh = PublishJob(captured = image, photo = photo)
        job = fresh

        signingJob?.cancel()
        signingJob = dependencies.publishPhotoUseCase.sign(fresh)
            // Signing runs while the user is on the Review screen, where they may already
            // be typing a note or choosing whose name to publish under. Those belong to
            // the UI, not to the signing flow, so they are carried over rather than
            // overwritten by a snapshot taken when the shutter fired.
            .onEach { signed ->
                val latest = job
                job = if (latest == null) signed else signed.copy(
                    publishAs = latest.publishAs,
                    photo = signed.photo.copy(caption = latest.photo.caption),
                )
            }
            .launchIn(scope)
    }

    /**
     * Records the note as it is typed.
     *
     * Held on the Photo rather than in screen state so it survives the move to the
     * publishing screen and is still there for a retry after a failed publish -- losing
     * someone's writing because a relay timed out would be its own small betrayal.
     */
    fun setCaption(caption: String) {
        val current = job ?: return
        job = current.copy(photo = current.photo.copy(caption = caption))
    }

    fun publish() {
        val current = job ?: return
        publishJob?.cancel()
        publishJob = dependencies.publishPhotoUseCase.publish(current)
            .onEach { job = it }
            .launchIn(scope)
    }

    /** Retry resumes from the recorded state; it never restarts completed stages. */
    fun retry() = publish()

    /**
     * Retake or close: abandons the capture and erases it.
     *
     * The signed master has to go with it. This app is built for people whose devices may
     * be searched, and a photo they explicitly chose to discard must not stay readable in
     * app storage. Deleting only after `cancelAndJoin` matters -- cancellation is
     * cooperative, so a signing job mid-write would otherwise recreate the file moments
     * after we removed it.
     */
    fun discard() {
        val abandoned = job
        job = null

        scope.launch {
            signingJob?.cancelAndJoin()
            publishJob?.cancelAndJoin()
            signingJob = null
            publishJob = null
            abandoned?.photo?.localPath?.let {
                runCatching { dependencies.fileStorage.deleteSignedMaster(it) }
            }
        }
    }

    private fun newPhotoId(): String {
        val stamp = Clock.System.now().toEpochMilliseconds()
        val suffix = Random.nextInt(0x10000, 0xFFFFF).toString(16)
        return "ov_${stamp}_$suffix"
    }
}

/**
 * Remembers a [CaptureCoordinator] scoped to the composition.
 *
 * MainActivity declares configChanges for orientation, so the activity is not recreated on
 * rotation and an in-flight publish survives it.
 */
@Composable
fun rememberCaptureCoordinator(dependencies: AppDependencies): CaptureCoordinator {
    val scope = rememberCoroutineScope()
    return remember(dependencies, scope) { CaptureCoordinator(dependencies, scope) }
}

/** Progress of pairing with a remote signer. */
sealed interface LinkState {
    data object Idle : LinkState
    data object Connecting : LinkState
    data class Failed(val message: String) : LinkState
}
