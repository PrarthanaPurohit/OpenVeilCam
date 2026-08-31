package com.openveil.ui.components

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Decodes captured or signed JPEG bytes for display.
 *
 * This is a DISPLAY-ONLY path. The decoded bitmap must never be re-encoded and fed back
 * into the pipeline: the C2PA manifest binds to the exact byte sequence produced by the
 * camera's encoder, so a re-encode silently invalidates the Content Credential and breaks
 * the "hash equals uploaded bytes" invariant. Always keep the original ByteArray as the
 * source of truth and treat this purely as pixels for the screen.
 */
expect fun decodeImageForDisplay(bytes: ByteArray): ImageBitmap?
