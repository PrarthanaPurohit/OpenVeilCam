package com.openveil.domain.service

import com.openveil.domain.model.AppResult
import com.openveil.domain.model.CapturedImage

/** Flash behaviour for a capture. Maps onto the platform camera's own flash modes. */
enum class CameraFlash { OFF, ON, AUTO }

/** Which physical camera to bind. */
enum class CameraLens { BACK, FRONT }

/**
 * Capture, abstracted away from CameraX and AVFoundation.
 *
 * Implementations must return the encoder's own bytes untouched. Anything that decodes to
 * a bitmap and re-encodes breaks the guarantee the whole product rests on, because the
 * C2PA manifest is bound to the exact bytes the sensor pipeline produced.
 */
interface CameraService {
    suspend fun capture(): AppResult<CapturedImage>
    fun setFlash(flash: CameraFlash)
    fun setLens(lens: CameraLens)
}
