package com.openveil.domain.model

import kotlin.time.Instant

/**
 * A photo as it moves through the pipeline, plus everything learned about it along the way.
 *
 * The nullable fields double as idempotency guards: a non-null [blossomUrl] means the
 * upload already succeeded and a retry must not re-upload, and a non-null [nostrEventId]
 * means the event is already out and must not be re-published. See PublishPhotoUseCase.
 *
 * In this build the instance lives in a ViewModel for the session. When the local queue
 * lands it becomes the row that gets persisted, unchanged.
 */
data class Photo(
    val id: String,
    /** Where the C2PA-signed master sits on disk. The only copy until Blossom accepts it. */
    val localPath: String? = null,
    val blossomUrl: String? = null,
    /** SHA-256 of the signed bytes. Must equal what Blossom stored and the NIP-94 `x` tag. */
    val sha256: String? = null,
    /**
     * SHA-256 of the pre-signing capture, published as NIP-94 `ox`. OpenVeil applies no
     * transformation after signing, so consumers will see this differ from [sha256] only
     * by the manifest the signer embedded.
     */
    val originalSha256: String? = null,
    val nostrEventId: String? = null,
    val nostrPubkey: String? = null,
    val c2paManifestId: String? = null,
    val mimeType: String = "image/jpeg",
    val width: Int = 0,
    val height: Int = 0,
    val fileSize: Long = 0,
    val captureDevice: String? = null,
    /**
     * Optional note the photographer wrote before publishing.
     *
     * Deliberately NOT part of the C2PA manifest. The manifest attests a provenance fact --
     * these pixels came off a sensor unaltered -- while a caption is an editorial claim no
     * cryptography can check. Binding it into the credential that renders as "Verified"
     * would invite readers to treat the words as verified too, which is exactly the
     * confusion this product exists to prevent. It is still authenticated: it travels
     * inside a Nostr event signed by the device key, so it is provably from that npub.
     */
    val caption: String? = null,
    val status: PublishStatus = PublishStatus.CAPTURED,
    val error: PublishError? = null,
    /** Relays that returned OK. Publishing counts as successful with at least one. */
    val acceptedRelays: List<String> = emptyList(),
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    val dimensions: String get() = "$width x $height"

    val isPublished: Boolean get() = status == PublishStatus.PUBLISHED
}
