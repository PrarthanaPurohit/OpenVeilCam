package com.openveil.ui.camera

import androidx.compose.runtime.Composable
import com.openveil.ui.screens.CameraPermissionState

/**
 * Camera permission, as a small state machine the camera screen can render.
 *
 * "Permanently denied" is distinguished from "denied" because they need different
 * affordances: one can be re-requested in place, the other can only be resolved in system
 * settings, and offering a button that silently does nothing is worse than offering none.
 */
interface CameraPermissionController {
    val state: CameraPermissionState
    fun request()
    fun openSettings()
}

/**
 * Remembers camera permission state, requesting it once on first display rather than
 * making the user press a button just to reach the system dialog they would see anyway.
 */
@Composable
expect fun rememberCameraPermission(): CameraPermissionController
