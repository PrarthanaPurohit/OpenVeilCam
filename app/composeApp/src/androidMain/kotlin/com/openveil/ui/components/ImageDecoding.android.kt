package com.openveil.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

actual fun decodeImageForDisplay(bytes: ByteArray): ImageBitmap? =
    runCatching {
        // inSampleSize keeps a 12 MP capture from allocating ~48 MB just to fill a
        // phone-sized preview. Display only -- the original bytes are untouched.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)

        var sample = 1
        while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) {
            sample *= 2
        }

        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: return@runCatching null

        // BitmapFactory ignores the EXIF orientation tag, so a portrait capture decodes
        // sideways. Applying it here rotates the on-screen pixels only; the ByteArray this
        // was decoded from is never modified and stays the thing that gets signed, hashed
        // and uploaded.
        decoded.applyExif(readExifTransform(bytes)).asImageBitmap()
    }.getOrNull()

private fun Bitmap.applyExif(transform: ExifTransform): Bitmap {
    if (transform == ExifTransform.None) return this

    val matrix = Matrix().apply {
        if (transform.rotationDegrees != 0) postRotate(transform.rotationDegrees.toFloat())
        if (transform.flipHorizontal) postScale(-1f, 1f)
    }
    return runCatching {
        Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
            .also { if (it !== this) recycle() }
    }.getOrDefault(this)
}
