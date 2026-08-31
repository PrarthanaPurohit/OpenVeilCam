package com.openveil.domain.model

/**
 * The C2PA-signed image. These bytes are canonical: the manifest's hash binding is
 * computed over them, so altering even one byte invalidates the Content Credential.
 *
 * The SHA-256 published in the NIP-94 event must be the hash of exactly [bytes], and
 * exactly [bytes] must be what Blossom stores.
 */
class SignedAsset(
    val bytes: ByteArray,
    val mimeType: String,
    val manifestId: String?,
) {
    override fun toString(): String =
        "SignedAsset($mimeType, ${bytes.size} bytes, manifest=$manifestId)"
}

/** Provenance claims written into the C2PA manifest at signing time. */
data class C2paMetadata(
    val title: String,
    val creator: String?,
    val applicationName: String,
    val applicationVersion: String,
)

/** What a Blossom server returns for a stored blob (BUD-02 blob descriptor). */
data class BlossomUploadResult(
    val url: String,
    val sha256: String,
    val size: Long,
    val mimeType: String,
    /** Which server accepted it -- recorded so retries and mirrors can be reasoned about. */
    val serverUrl: String,
)
