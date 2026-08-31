package com.openveil.ui.camera

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.openveil.domain.service.CameraService

/**
 * Platform camera, created and remembered by the composition.
 *
 * The controller is what the screen talks to; the preview surface is supplied separately
 * by [CameraViewfinder]. Keeping them apart is what lets [com.openveil.ui.screens.CameraScreen]
 * stay free of CameraX and AVFoundation types -- the composable never touches a platform
 * camera API, it just renders a surface and calls capture().
 */
@Composable
expect fun rememberCameraController(): CameraService

/** The live preview surface. Renders nothing meaningful until permission is granted. */
@Composable
expect fun CameraViewfinder(controller: CameraService, modifier: Modifier)
