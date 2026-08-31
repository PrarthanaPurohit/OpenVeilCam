package com.openveil.domain.model

import kotlin.time.Instant

/**
 * Bytes straight off the camera, before any provenance work.
 *
 * [bytes] is the encoder's own output and must never be re-encoded, resized, or
 * round-tripped through a bitmap. Everything downstream -- the C2PA manifest, the
 * SHA-256, the bytes Blossom stores -- is anchored to this exact byte sequence.
 */
class CapturedImage(
    val bytes: ByteArray,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val capturedAt: Instant,
) {
    val dimensions: String get() = "${width}x$height"

    /**
     * Deliberately not a data class. The generated equals/hashCode would compare the
     * ByteArray by identity, which reads as a correctness bug waiting to happen, and
     * a generated toString would dump multi-megabyte image bytes into logs.
     */
    override fun toString(): String =
        "CapturedImage($mimeType, $dimensions, ${bytes.size} bytes, at=$capturedAt)"
}
