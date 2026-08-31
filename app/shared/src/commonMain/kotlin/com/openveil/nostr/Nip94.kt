package com.openveil.nostr

import com.openveil.domain.model.BlossomUploadResult

/** NIP-94 "file metadata" event kind. */
const val KIND_FILE_METADATA = 1063

/** Blossom authorization event kind (BUD-11). */
const val KIND_BLOSSOM_AUTH = 24242

/**
 * Builds the NIP-94 (kind 1063) tag set announcing a published photo.
 *
 * On `x` vs `ox`: NIP-94 defines `x` as the hash of the file being served and `ox` as the
 * hash of the original before any transformation. OpenVeil applies no transformation
 * after signing -- the bytes Blossom stores are byte-for-byte the bytes C2PA produced --
 * so `x` is the signed-file hash. `ox` carries the hash of the pre-signing capture, which
 * lets a verifier see that exactly one transformation (adding the manifest) occurred.
 *
 * `x` MUST equal the SHA-256 of what the server actually stored. If those diverge, every
 * downstream verification fails, so the value is taken from the upload result rather than
 * recomputed from a local variable that might have drifted.
 */
fun buildNip94Tags(
    upload: BlossomUploadResult,
    originalSha256: String?,
    width: Int,
    height: Int,
    altText: String?,
): List<List<String>> = buildList {
    add(listOf("url", upload.url))
    add(listOf("m", upload.mimeType))
    add(listOf("x", upload.sha256))
    // Fall back to x when there is no separate pre-signing hash: NIP-94 requires ox, and
    // claiming a different original than we can prove would be worse than repeating it.
    add(listOf("ox", originalSha256 ?: upload.sha256))
    add(listOf("size", upload.size.toString()))
    if (width > 0 && height > 0) {
        add(listOf("dim", "${width}x$height"))
    }
    if (!altText.isNullOrBlank()) {
        add(listOf("alt", altText))
    }
    // NIP-92 imeta: a single tag whose elements are "key value" strings. Ordinary Nostr
    // clients read this to render the image inline; without it a kind-1063 event shows up
    // as bare metadata. Matches the shape OpenVeilCam's Pi publisher emits.
    add(
        buildList {
            add("imeta")
            add("url ${upload.url}")
            add("m ${upload.mimeType}")
            add("x ${upload.sha256}")
            if (width > 0 && height > 0) add("dim ${width}x$height")
            if (!altText.isNullOrBlank()) add("alt $altText")
        }
    )
}

/**
 * Content for the companion kind-1 note.
 *
 * A kind-1063 event alone is invisible in Damus, Primal and Snort -- they render text
 * notes, not file metadata. OpenVeilCam publishes a plain note alongside for exactly this
 * reason, so a published capture is actually viewable rather than only machine-readable.
 */
fun buildCompanionNoteContent(imageUrl: String, caption: String? = null): String =
    if (caption.isNullOrBlank()) {
        "Captured with OpenVeil\n\n$imageUrl"
    } else {
        // The photographer's words lead; the attribution line follows, so a reader sees
        // what was said before how it was made.
        "${caption.trim()}\n\nCaptured with OpenVeil\n\n$imageUrl"
    }

/**
 * Builds the BUD-11 authorization event for a Blossom request.
 *
 * Three details are easy to get wrong and all of them surface as an opaque 401:
 *  - `created_at` MUST be in the past,
 *  - the `expiration` tag MUST be in the future,
 *  - `content` must be human-readable, because a server may show it to the user.
 */
fun buildBlossomAuthTags(
    verb: String,
    sha256: String?,
    expiresAtEpochSeconds: Long,
): List<List<String>> = buildList {
    add(listOf("t", verb))
    if (sha256 != null) add(listOf("x", sha256))
    add(listOf("expiration", expiresAtEpochSeconds.toString()))
}
