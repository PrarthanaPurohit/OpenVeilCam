package com.openveil.ui.components

import androidx.compose.runtime.Composable

/**
 * Opens an https link in whatever the platform considers the browser.
 *
 * Used for Nostr identifiers, which resolve through an ordinary web host so a recipient
 * needs no Nostr client to follow one.
 */
@Composable
expect fun rememberUrlOpener(): (String) -> Unit
