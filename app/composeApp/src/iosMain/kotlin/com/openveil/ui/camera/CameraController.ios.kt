package com.openveil.ui.camera

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.openveil.domain.model.AppResult
import com.openveil.domain.model.CapturedImage
import com.openveil.domain.model.PublishError
import com.openveil.domain.service.CameraFlash
import com.openveil.domain.service.CameraLens
import com.openveil.domain.service.CameraService

/**
 * iOS capture is not implemented in this build; the AVFoundation session and the
 * c2pa-swift bridge both land together when the project reaches a Mac.
 *
 * This returns a failure rather than throwing, so an iOS build runs and reports the gap
 * through the normal error path instead of crashing at the shutter.
 */
private object UnimplementedCameraService : CameraService {
    override suspend fun capture(): AppResult<CapturedImage> =
        AppResult.Failure(PublishError.CAMERA_FAILED, "camera is not yet implemented on iOS")

    override fun setFlash(flash: CameraFlash) = Unit
    override fun setLens(lens: CameraLens) = Unit
}

@Composable
actual fun rememberCameraController(): CameraService = UnimplementedCameraService

@Composable
actual fun CameraViewfinder(controller: CameraService, modifier: Modifier) = Unit
