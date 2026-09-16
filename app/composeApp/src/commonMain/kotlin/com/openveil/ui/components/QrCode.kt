package com.openveil.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.openveil.ui.theme.OpenVeilColors
import com.openveil.ui.theme.OpenVeilShapes
import com.openveil.ui.theme.OpenVeilTheme
import com.openveil.ui.theme.Spacing

/** A QR symbol as a square grid of dark/light modules, row-major. */
class QrMatrix(val size: Int, private val modules: BooleanArray) {
    init {
        require(modules.size == size * size) { "QR matrix must be square" }
    }

    fun isDark(x: Int, y: Int): Boolean = modules[y * size + x]
}

/** Encodes [text] as a QR symbol, or null when the platform cannot. */
expect fun encodeQr(text: String): QrMatrix?

/**
 * Draws a QR code. Always black on white with a quiet zone, whatever the theme: the
 * thing scanning it is a camera, not a person, and cameras do not appreciate dark mode.
 */
@Composable
fun QrCode(
    text: String,
    modifier: Modifier = Modifier,
    contentDescription: String = "QR code",
) {
    val matrix = remember(text) { encodeQr(text) }

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(OpenVeilShapes.medium)
            .background(Color.White)
            .padding(Spacing.md)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        if (matrix == null) {
            Text(
                "QR code unavailable on this platform",
                style = OpenVeilTheme.type.bodySm,
                color = OpenVeilColors.OnSurfaceVariant,
            )
        } else {
            Canvas(Modifier.fillMaxSize()) {
                // Snap the module size to whole pixels so rows do not alias into grey
                // bands, which some scanners misread.
                val module = kotlin.math.floor(size.minDimension / matrix.size)
                val offset = (size.minDimension - module * matrix.size) / 2f
                for (y in 0 until matrix.size) {
                    for (x in 0 until matrix.size) {
                        if (!matrix.isDark(x, y)) continue
                        drawRect(
                            color = Color.Black,
                            topLeft = Offset(offset + x * module, offset + y * module),
                            size = Size(module, module),
                        )
                    }
                }
            }
        }
    }
}

/**
 * A live camera view that reports the first QR code it reads.
 *
 * [onCode] may be called more than once if the code stays in frame; callers stop
 * composing this once they have what they need.
 */
@Composable
expect fun QrScannerView(onCode: (String) -> Unit, modifier: Modifier = Modifier)
