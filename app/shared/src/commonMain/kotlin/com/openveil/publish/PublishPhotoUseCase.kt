package com.openveil.publish

import com.openveil.blossom.BlossomClient
import com.openveil.crypto.sha256Hex
import com.openveil.domain.model.AppResult
import com.openveil.domain.model.CapturedImage
import com.openveil.domain.model.Photo
import com.openveil.domain.model.PublishError
import com.openveil.domain.model.PublishStatus
import com.openveil.domain.model.SignedAsset
import com.openveil.domain.service.C2paService
import com.openveil.domain.service.C2paSigningContext
import com.openveil.domain.service.FileStorage
import com.openveil.nostr.KIND_FILE_METADATA
import com.openveil.nostr.NostrClient
import com.openveil.nostr.NostrIdentityRepository
import com.openveil.nostr.buildCompanionNoteContent
import com.openveil.nostr.buildNip94Tags
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Clock

/**
 * One photo moving through the pipeline, with everything learned so far.
 *
 * The nullable fields are the resume points. A retry inspects them rather than starting
 * over, which is what makes "Retry publication" after a relay failure avoid re-uploading
 * several megabytes that Blossom already holds.
 */
data class PublishJob(
    val captured: CapturedImage,
    val signed: SignedAsset? = null,
    val photo: Photo,
)

/**
 * Capture -> Content Credentials -> hash -> Blossom -> NIP-94 -> relays.
 *
 * There is no transaction spanning these systems, so each stage commits its result into
 * [PublishJob] before the next begins. That is what makes a failure recoverable instead
 * of forcing the user to start again with a photo they cannot retake.
 *
 * Ordering that must not change:
 *  - the hash is taken **after** signing, over the signed bytes, because those are the
 *    bytes Blossom stores and the bytes a verifier will re-hash;
 *  - the signed master is on disk **before** the upload starts, so a crash mid-upload
 *    cannot lose the only copy.
 */
class PublishPhotoUseCase(
    private val c2pa: C2paService,
    private val blossom: BlossomClient,
    private val nostr: NostrClient,
    private val identityRepository: NostrIdentityRepository,
    private val fileStorage: FileStorage,
    private val deviceName: String?,
    private val publishCompanionNote: Boolean = true,
) {

    /**
     * Creates the Content Credential.
     *
     * Run eagerly when the shutter fires rather than when Publish is pressed, so the cost
     * is absorbed while the user is reading the review screen. Idempotent: a job that is
     * already signed passes straight through.
     */
    fun sign(job: PublishJob): Flow<PublishJob> = flow {
        if (job.signed != null) {
            emit(job)
            return@flow
        }

        emit(job.withStatus(PublishStatus.C2PA_SIGNING))

        val identity = resolveIdentity()
        if (identity == null) {
            emit(job.failed(PublishError.NOSTR_SIGNING_FAILED))
            return@flow
        }
        val context = C2paSigningContext(
            npub = identity.npub,
            nostrPubkeyHex = identity.publicKeyHex,
            width = job.captured.width,
            height = job.captured.height,
            captureDevice = deviceName,
        )

        when (val signed = c2pa.signImage(job.captured, context)) {
            is AppResult.Failure -> emit(job.failed(signed.error))
            is AppResult.Success -> {
                val asset = signed.value
                // Hash the SIGNED bytes. Hashing the capture instead would publish a
                // fingerprint that does not match the file anyone downloads.
                val publishedHash = sha256Hex(asset.bytes)
                val originalHash = sha256Hex(job.captured.bytes)
                val path = fileStorage.writeSignedMaster(job.photo.id, asset.bytes)

                emit(
                    job.copy(
                        signed = asset,
                        photo = job.photo.copy(
                            localPath = path,
                            sha256 = publishedHash,
                            originalSha256 = originalHash,
                            c2paManifestId = asset.manifestId,
                            mimeType = asset.mimeType,
                            width = job.captured.width,
                            height = job.captured.height,
                            fileSize = asset.bytes.size.toLong(),
                            captureDevice = deviceName,
                            nostrPubkey = identity.publicKeyHex,
                            status = PublishStatus.C2PA_SIGNED,
                            error = null,
                            updatedAt = Clock.System.now(),
                        ),
                    )
                )
            }
        }
    }

    /**
     * Uploads and publishes, resuming from wherever [job] left off.
     *
     * Emits after every stage so the progress timeline reflects real state rather than an
     * animation.
     */
    fun publish(job: PublishJob): Flow<PublishJob> = flow {
        var current = job

        // Stage 1: sign, if a previous attempt did not get that far.
        if (current.signed == null) {
            sign(current).collect { current = it; emit(it) }
            if (current.photo.status == PublishStatus.FAILED) return@flow
        }
        val asset = current.signed ?: run {
            emit(current.failed(PublishError.C2PA_FAILED)); return@flow
        }
        val publishedHash = current.photo.sha256 ?: sha256Hex(asset.bytes)

        // Stage 2: Blossom. Skipped outright if a previous attempt already stored it --
        // this is the guard that makes retrying a failed relay publish cheap.
        if (current.photo.blossomUrl == null) {
            emit(current.withStatus(PublishStatus.UPLOADING_BLOSSOM).also { current = it })

            when (val uploaded = blossom.upload(asset, publishedHash)) {
                is AppResult.Failure -> {
                    emit(current.failed(uploaded.error))
                    return@flow
                }
                is AppResult.Success -> {
                    current = current.copy(
                        photo = current.photo.copy(
                            blossomUrl = uploaded.value.url,
                            // Trust the server's reported hash: it is authoritative for
                            // what it will actually serve.
                            sha256 = uploaded.value.sha256,
                            fileSize = uploaded.value.size.takeIf { it > 0 }
                                ?: current.photo.fileSize,
                            status = PublishStatus.BLOSSOM_UPLOADED,
                            error = null,
                            updatedAt = Clock.System.now(),
                        ),
                    )
                    emit(current)
                }
            }
        }

        val uploadUrl = current.photo.blossomUrl ?: run {
            emit(current.failed(PublishError.BLOSSOM_UPLOAD_FAILED)); return@flow
        }

        // Stage 3: Nostr. Also guarded -- re-publishing an event we already got an OK for
        // would put a duplicate on the relays.
        if (current.photo.nostrEventId == null) {
            emit(current.withStatus(PublishStatus.PUBLISHING_NOSTR).also { current = it })

            val identity = resolveIdentity()
            if (identity == null) {
                emit(current.failed(PublishError.NOSTR_SIGNING_FAILED))
                return@flow
            }
            val now = Clock.System.now().epochSeconds
            val caption = current.photo.caption?.trim()?.takeIf { it.isNotEmpty() }
            val event = identity.signEvent(
                kind = KIND_FILE_METADATA,
                content = caption ?: "Captured with OpenVeil",
                tags = buildNip94Tags(
                    upload = com.openveil.domain.model.BlossomUploadResult(
                        url = uploadUrl,
                        sha256 = current.photo.sha256 ?: publishedHash,
                        size = current.photo.fileSize,
                        mimeType = current.photo.mimeType,
                        serverUrl = uploadUrl,
                    ),
                    originalSha256 = current.photo.originalSha256,
                    width = current.photo.width,
                    height = current.photo.height,
                    altText = caption,
                ),
                createdAt = now,
            )

            when (val published = nostr.publish(event)) {
                is AppResult.Failure -> {
                    emit(current.failed(published.error))
                    return@flow
                }
                is AppResult.Success -> {
                    current = current.copy(
                        photo = current.photo.copy(
                            nostrEventId = event.id,
                            nostrPubkey = identity.publicKeyHex,
                            acceptedRelays = published.value.acceptedRelays,
                            status = PublishStatus.PUBLISHED,
                            error = null,
                            updatedAt = Clock.System.now(),
                        ),
                    )
                }
            }

            // A plain note so the capture is visible in ordinary Nostr clients, which
            // render kind 1 but not kind 1063. Best-effort: the photo is already
            // published and verifiable without it, so a failure here is not a failure of
            // the pipeline.
            if (publishCompanionNote) {
                runCatching {
                    nostr.publish(
                        identity.signEvent(
                            kind = 1,
                            content = buildCompanionNoteContent(uploadUrl, caption),
                            tags = emptyList(),
                            createdAt = now,
                        )
                    )
                }
            }
        }

        // Only now is it safe to drop the local master.
        current.photo.localPath?.let { fileStorage.deleteSignedMaster(it) }
        current = current.copy(
            photo = current.photo.copy(
                localPath = null,
                status = PublishStatus.PUBLISHED,
                updatedAt = Clock.System.now(),
            ),
        )
        emit(current)
    }

    /**
     * Reads the device identity, returning null rather than throwing.
     *
     * The repository raises if a key exists but cannot be decrypted -- deliberately, so it
     * never silently mints a replacement. That has to surface here as an ordinary publish
     * failure with the photo preserved, not as an exception that tears down the pipeline
     * and loses the capture.
     */
    private suspend fun resolveIdentity() =
        runCatching { identityRepository.getOrCreate() }.getOrNull()

    private fun PublishJob.withStatus(status: PublishStatus) =
        copy(photo = photo.copy(status = status, error = null, updatedAt = Clock.System.now()))

    private fun PublishJob.failed(error: PublishError) = copy(
        photo = photo.copy(
            status = PublishStatus.FAILED,
            error = error,
            updatedAt = Clock.System.now(),
        ),
    )
}
