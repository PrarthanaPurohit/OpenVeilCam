package com.openveil.domain.service

import com.openveil.domain.model.AppResult
import com.openveil.domain.model.CapturedImage
import com.openveil.domain.model.SignedAsset

/**
 * Identity a C2PA manifest vouches for, written into the manifest at signing time.
 *
 * This is what makes the binding bidirectional: someone holding only the image learns
 * which npub published it, and someone holding only the Nostr event can check that the
 * image's manifest names that same key. Neither half can be swapped for another without
 * the mismatch showing.
 */
data class C2paSigningContext(
    val npub: String,
    val nostrPubkeyHex: String,
    val width: Int,
    val height: Int,
    val captureDevice: String?,
)

/**
 * Outcome of validating an asset's Content Credential.
 *
 * [Valid] and [Invalid] answer a different question from `trusted`, and the difference is
 * the most important thing this type encodes: *Invalid* means the bytes no longer match
 * the manifest, while valid-but-untrusted means the image is intact and only the signer's
 * identity is unvouched-for. Reporting the second as the first would tell someone their
 * genuine photograph had been tampered with.
 */
sealed interface C2paVerification {
    /** A manifest is present and its hash binding matches the bytes we checked. */
    data class Valid(val manifestId: String?, val signerName: String?, val trusted: Boolean) :
        C2paVerification

    /** A manifest is present but does not match the bytes -- the image was altered. */
    data class Invalid(val reason: String) : C2paVerification

    data object NotPresent : C2paVerification

    data class Error(val reason: String) : C2paVerification
}

/**
 * Creates and checks Content Credentials.
 *
 * The signed bytes returned by [signImage] are canonical: the manifest's hard binding is
 * computed over them, so re-encoding, stripping metadata, or even rewriting a single byte
 * invalidates the credential. Everything downstream -- the SHA-256 published in NIP-94,
 * the blob Blossom stores -- must use exactly those bytes.
 */
interface C2paService {
    suspend fun signImage(
        image: CapturedImage,
        context: C2paSigningContext,
    ): AppResult<SignedAsset>

    /** Re-checks an asset, normally one just downloaded back from Blossom. */
    suspend fun verify(bytes: ByteArray, mimeType: String = "image/jpeg"): C2paVerification

    /** Whether a usable signing identity is available. Surfaced on the Home screen. */
    suspend fun isSigningAvailable(): Boolean
}
