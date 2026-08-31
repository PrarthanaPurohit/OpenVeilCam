package com.openveil.ui.navigation

import androidx.compose.runtime.Composable

/**
 * iOS has no system back button; navigation back is driven by on-screen affordances and
 * the edge-swipe gesture, which Compose Multiplatform handles at the container level.
 */
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // Intentionally empty.
}
