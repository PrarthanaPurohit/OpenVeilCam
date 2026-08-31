package com.openveil.ui.components

import androidx.compose.runtime.Composable

/**
 * Returns a handler that hands text to the platform share sheet.
 *
 * OpenVeil shares the public Blossom URL and Nostr event reference -- the things anyone
 * can use to independently verify the photo. It never shares a local file path or any
 * internal identifier.
 */
@Composable
expect fun rememberShareHandler(): (String) -> Unit
