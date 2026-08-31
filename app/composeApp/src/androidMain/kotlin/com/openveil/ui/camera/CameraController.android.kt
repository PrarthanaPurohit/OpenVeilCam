package com.openveil.ui.camera

import android.content.Context
import android.os.Build
import android.view.OrientationEventListener
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.openveil.domain.model.AppResult
import com.openveil.domain.model.CapturedImage
import com.openveil.domain.model.PublishError
import com.openveil.domain.service.CameraFlash
import com.openveil.domain.service.CameraLens
import com.openveil.domain.service.CameraService
import com.openveil.ui.components.readExifTransform
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.time.Clock

@Composable
actual fun rememberCameraController(): CameraService {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    return remember(context, lifecycleOwner) {
        AndroidCameraController(context, lifecycleOwner)
    }
}

@Composable
actual fun CameraViewfinder(controller: CameraService, modifier: Modifier) {
    val android = controller as? AndroidCameraController ?: return
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PreviewView(ctx).apply {
                // PERFORMANCE mode keeps the preview off the compositing path that
                // COMPATIBLE uses; the preview is never animated.
                implementationMode = PreviewView.ImplementationMode.PERFORMANCE
                // FIT_CENTER, not FILL_CENTER. FILL crops the preview to the shape of the
                // screen, so a tall phone hid the top and bottom of a 4:3 frame while the
                // capture kept them -- the photo came back with content the photographer
                // never saw. For a provenance camera that is a hazard, not a cosmetic
                // issue: an unseen bystander, document or landmark at the edge of frame
                // gets signed and published irreversibly. Letterboxing costs some screen
                // area and buys the guarantee that the viewfinder is the photograph.
                scaleType = PreviewView.ScaleType.FIT_CENTER
            }
        },
        update = { view -> android.bind(view) },
        onRelease = { android.unbind() },
    )
}

/**
 * CameraX-backed capture.
 *
 * Captures straight to an in-memory buffer and hands back the JPEG exactly as the encoder
 * produced it. There is deliberately no bitmap decode, no rotation fix-up and no
 * re-compression anywhere in this path: the C2PA manifest signed moments later binds to
 * these bytes, so touching them would invalidate the credential.
 */
private class AndroidCameraController(
    context: Context,
    private val lifecycleOwner: LifecycleOwner,
) : CameraService {

    private val appContext = context.applicationContext
    private var imageCapture: ImageCapture? = null
    private var previewUseCase: Preview? = null
    private var provider: ProcessCameraProvider? = null
    private var currentLens: CameraLens = CameraLens.BACK
    private var currentFlash: CameraFlash = CameraFlash.OFF

    /**
     * Keeps [ImageCapture.setTargetRotation] in step with how the phone is actually held.
     *
     * MainActivity declares `configChanges="orientation|screenSize|..."`, so the activity
     * is never recreated on rotation and CameraX never hears about it through the normal
     * configuration path -- without this listener every capture is tagged with whatever
     * orientation happened to be current when the camera was bound, which is why a
     * portrait photo came out on its side.
     *
     * Only the EXIF orientation tag changes as a result. The pixels the encoder emits are
     * left exactly as they are, because those are the bytes C2PA signs.
     */
    private val orientationListener = object : OrientationEventListener(appContext) {
        override fun onOrientationChanged(orientation: Int) {
            if (orientation == ORIENTATION_UNKNOWN) return
            val rotation = when (orientation) {
                in 45 until 135 -> Surface.ROTATION_270
                in 135 until 225 -> Surface.ROTATION_180
                in 225 until 315 -> Surface.ROTATION_90
                else -> Surface.ROTATION_0
            }
            imageCapture?.targetRotation = rotation
        }
    }

    fun bind(view: PreviewView) {
        val future = ProcessCameraProvider.getInstance(appContext)
        future.addListener({
            val cameraProvider = runCatching { future.get() }.getOrNull() ?: return@addListener
            provider = cameraProvider

            // One aspect ratio for both use cases. Left to their own defaults the
            // preview and the capture can resolve to different ratios, which reintroduces
            // the mismatch from the other direction.
            val resolutionSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .build()

            val preview = Preview.Builder()
                .setResolutionSelector(resolutionSelector)
                .build()
                .also { it.surfaceProvider = view.surfaceProvider }
            val capture = ImageCapture.Builder()
                .setResolutionSelector(resolutionSelector)
                // Prioritise fidelity over shutter latency: this is a provenance camera,
                // and the captured bytes are the artifact being attested to.
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setFlashMode(currentFlash.toCameraX())
                .build()

            runCatching {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    currentLens.toSelector(),
                    preview,
                    capture,
                )
            }.onSuccess {
                previewUseCase = preview
                imageCapture = capture
                if (orientationListener.canDetectOrientation()) orientationListener.enable()
            }
        }, ContextCompat.getMainExecutor(appContext))
    }

    fun unbind() {
        orientationListener.disable()
        runCatching { provider?.unbindAll() }
        imageCapture = null
        previewUseCase = null
    }

    override suspend fun capture(): AppResult<CapturedImage> {
        val capture = imageCapture
            ?: return AppResult.Failure(PublishError.CAMERA_FAILED, "camera not bound")

        return suspendCancellableCoroutine { continuation ->
            capture.takePicture(
                ContextCompat.getMainExecutor(appContext),
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        val result = runCatching { image.toCapturedImage() }
                        image.close()
                        continuation.resume(
                            result.fold(
                                onSuccess = { AppResult.Success(it) },
                                onFailure = {
                                    AppResult.Failure(
                                        PublishError.CAMERA_FAILED,
                                        it.message ?: "could not read captured frame",
                                        it,
                                    )
                                },
                            )
                        )
                    }

                    override fun onError(exception: ImageCaptureException) {
                        continuation.resume(
                            AppResult.Failure(
                                PublishError.CAMERA_FAILED,
                                exception.message ?: "capture failed",
                                exception,
                            )
                        )
                    }
                },
            )
        }
    }

    override fun setFlash(flash: CameraFlash) {
        currentFlash = flash
        imageCapture?.flashMode = flash.toCameraX()
    }

    override fun setLens(lens: CameraLens) {
        if (lens == currentLens) return
        currentLens = lens
        // Rebinding happens on the next composition of the viewfinder; unbind now so the
        // old selector is not left holding the camera.
        unbind()
    }

    /**
     * Reads the JPEG buffer out of the frame verbatim.
     *
     * CameraX delivers JPEG output as a single plane; the buffer is copied, not decoded.
     */
    private fun ImageProxy.toCapturedImage(): CapturedImage {
        val buffer = planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        // The buffer dimensions describe the stored pixels, which for a portrait capture
        // are still landscape -- the EXIF tag is what turns them upright. Report the
        // dimensions as the image will actually be presented, since that is what the
        // details screen shows and what goes into the NIP-94 `dim` tag for other clients.
        val upright = readExifTransform(bytes).swapsDimensions
        return CapturedImage(
            bytes = bytes,
            mimeType = "image/jpeg",
            width = if (upright) height else width,
            height = if (upright) width else height,
            capturedAt = Clock.System.now(),
        )
    }

    private fun CameraFlash.toCameraX(): Int = when (this) {
        CameraFlash.OFF -> ImageCapture.FLASH_MODE_OFF
        CameraFlash.ON -> ImageCapture.FLASH_MODE_ON
        CameraFlash.AUTO -> ImageCapture.FLASH_MODE_AUTO
    }

    private fun CameraLens.toSelector(): CameraSelector = when (this) {
        CameraLens.BACK -> CameraSelector.DEFAULT_BACK_CAMERA
        CameraLens.FRONT -> CameraSelector.DEFAULT_FRONT_CAMERA
    }
}

/** Manufacturer and model, recorded in the C2PA manifest as the capture device. */
fun currentDeviceName(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
