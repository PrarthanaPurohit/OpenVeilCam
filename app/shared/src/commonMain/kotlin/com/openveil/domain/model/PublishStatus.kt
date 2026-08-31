package com.openveil.domain.model

/**
 * Where a photo is in the capture -> publish pipeline.
 *
 * The order here is the order the pipeline advances through, and the UI maps each value
 * to a row in the publishing timeline. Persisting this (rather than treating publishing
 * as one atomic call) is what makes per-stage retry possible: there is no transaction
 * spanning C2PA, Blossom and Nostr, so intermediate state has to be explicit.
 */
enum class PublishStatus {
    CAPTURED,
    C2PA_SIGNING,
    C2PA_SIGNED,
    UPLOADING_BLOSSOM,
    BLOSSOM_UPLOADED,
    PUBLISHING_NOSTR,
    PUBLISHED,
    FAILED,
    ;

    val isTerminal: Boolean get() = this == PUBLISHED || this == FAILED
    val isInFlight: Boolean
        get() = this == C2PA_SIGNING || this == UPLOADING_BLOSSOM || this == PUBLISHING_NOSTR
}

/**
 * Why a publish stopped. Each value maps to specific user-facing copy and, critically, to
 * the stage a retry should resume from -- never to a restart of the whole pipeline.
 */
enum class PublishError {
    CAMERA_FAILED,
    C2PA_FAILED,
    HASH_FAILED,
    BLOSSOM_AUTH_FAILED,
    BLOSSOM_UPLOAD_FAILED,
    NOSTR_SIGNING_FAILED,
    NOSTR_PUBLISH_FAILED,
    NO_NETWORK,
    ;

    /** The status a retry should re-enter at. */
    val resumeAt: PublishStatus
        get() = when (this) {
            CAMERA_FAILED, C2PA_FAILED, HASH_FAILED -> PublishStatus.CAPTURED
            BLOSSOM_AUTH_FAILED, BLOSSOM_UPLOAD_FAILED, NO_NETWORK -> PublishStatus.C2PA_SIGNED
            NOSTR_SIGNING_FAILED, NOSTR_PUBLISH_FAILED -> PublishStatus.BLOSSOM_UPLOADED
        }
}
