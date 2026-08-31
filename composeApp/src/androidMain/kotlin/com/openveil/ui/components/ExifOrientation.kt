package com.openveil.ui.components

import android.media.ExifInterface
import java.io.ByteArrayInputStream

/**
 * How a JPEG's EXIF orientation tag says the stored pixels should be presented.
 *
 * OpenVeil never rotates pixels. The C2PA manifest binds to the exact bytes the camera's
 * encoder produced, so decoding and re-encoding an upright copy would invalidate the
 * Content Credential and break the "published hash equals stored bytes" invariant. The
 * orientation therefore lives in the file's own metadata, and everything that *displays*
 * or *measures* the image has to read it -- which is what this exists for.
 */
internal data class ExifTransform(
    val rotationDegrees: Int,
    val flipHorizontal: Boolean,
) {
    /** True when the tag turns the image on its side, so width and height swap. */
    val swapsDimensions: Boolean get() = rotationDegrees == 90 || rotationDegrees == 270

    companion object {
        val None = ExifTransform(0, false)
    }
}

/**
 * Reads the EXIF orientation tag.
 *
 * Falls back to no transform for a file without EXIF, or one we cannot parse -- showing an
 * image unrotated is a far smaller failure than refusing to show it.
 */
internal fun readExifTransform(bytes: ByteArray): ExifTransform = runCatching {
    val exif = ByteArrayInputStream(bytes).use { ExifInterface(it) }
    when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
        ExifInterface.ORIENTATION_ROTATE_90 -> ExifTransform(90, false)
        ExifInterface.ORIENTATION_ROTATE_180 -> ExifTransform(180, false)
        ExifInterface.ORIENTATION_ROTATE_270 -> ExifTransform(270, false)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> ExifTransform(0, true)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> ExifTransform(180, true)
        // Transpose and transverse are a rotation plus a mirror -- rare, but a front
        // camera can produce them and they still swap the dimensions.
        ExifInterface.ORIENTATION_TRANSPOSE -> ExifTransform(90, true)
        ExifInterface.ORIENTATION_TRANSVERSE -> ExifTransform(270, true)
        else -> ExifTransform.None
    }
}.getOrDefault(ExifTransform.None)
