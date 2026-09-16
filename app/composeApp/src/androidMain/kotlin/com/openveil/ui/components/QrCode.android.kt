package com.openveil.ui.components

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.util.concurrent.Executors

actual fun encodeQr(text: String): QrMatrix? = runCatching {
    val hints = mapOf(
        // The quiet zone is drawn by the composable, in dp, so it scales with the view.
        EncodeHintType.MARGIN to 0,
        // M is plenty for a phone screen held up to another phone; higher levels make
        // the symbol denser, which is the actual failure mode at this size.
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
    )
    val bits = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
    val size = bits.width
    QrMatrix(size, BooleanArray(size * size) { bits.get(it % size, it / size) })
}.getOrNull()

/**
 * CameraX preview plus an analysis stream decoded by ZXing.
 *
 * Its own small binding rather than a mode on the capture controller: the capture path
 * has hard guarantees about what the viewfinder shows versus what gets signed, and a QR
 * scanner has none of those concerns. Keeping them apart keeps those guarantees legible.
 */
@Composable
actual fun QrScannerView(onCode: (String) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnCode = rememberUpdatedState(onCode)
    val executor = remember { Executors.newSingleThreadExecutor() }
    val reader = remember {
        MultiFormatReader().apply {
            setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
        }
    }

    DisposableEffect(Unit) {
        onDispose { executor.shutdown() }
    }

    AndroidView(
        modifier = modifier,
        // Bound once, from the factory: an `update` lambda runs on every recomposition and
        // would re-bind the camera each time the parent's state changes.
        factory = { ctx ->
            val view = PreviewView(ctx).apply {
                implementationMode = PreviewView.ImplementationMode.PERFORMANCE
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
            val future = ProcessCameraProvider.getInstance(context.applicationContext)
            future.addListener({
                val provider = runCatching { future.get() }.getOrNull() ?: return@addListener
                val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                val analysis = ImageAnalysis.Builder()
                    // Drop frames we cannot keep up with; decoding a stale frame is
                    // worse than skipping it.
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(executor) { image -> image.use { decode(it, reader)?.let(latestOnCode.value) } } }
                runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                }
            }, ContextCompat.getMainExecutor(context))
            view
        },
        onRelease = {
            runCatching { ProcessCameraProvider.getInstance(context.applicationContext).get().unbindAll() }
        },
    )
}

/** Decodes the Y plane directly; QR needs luminance only, so there is no colour conversion. */
private fun decode(image: ImageProxy, reader: MultiFormatReader): String? {
    val plane = image.planes.firstOrNull() ?: return null
    val buffer = plane.buffer
    val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
    val source = PlanarYUVLuminanceSource(
        bytes, plane.rowStride, image.height, 0, 0, image.width, image.height, false,
    )
    return try {
        reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
    } catch (e: NotFoundException) {
        null
    } finally {
        reader.reset()
    }
}
