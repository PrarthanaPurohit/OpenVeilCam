package com.openveil.ui.camera

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.openveil.ui.screens.CameraPermissionState

@Composable
actual fun rememberCameraPermission(): CameraPermissionController = remember {
    object : CameraPermissionController {
        // The iOS camera is not implemented yet, so reporting Granted would show an
        // empty viewfinder with no explanation.
        override val state = CameraPermissionState.Denied
        override fun request() = Unit
        override fun openSettings() = Unit
    }
}
