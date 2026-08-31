package com.openveil.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Spacing scale from the reference design tokens. Use these instead of literal dp values
 * so density can be retuned in one place.
 */
object Spacing {
    /** 4dp -- the base unit everything else is a multiple of. */
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp

    /** 12dp -- gap between items in a grid or row. */
    val gutter: Dp = 12.dp
    val md: Dp = 16.dp

    /** 20dp -- horizontal page inset. Screens should use this, not [md] or [lg]. */
    val containerMargin: Dp = 20.dp
    val lg: Dp = 24.dp
    val xl: Dp = 40.dp
}

/** Corner radii from the design tokens. */
object OpenVeilShapes {
    val small = RoundedCornerShape(4.dp)
    val medium = RoundedCornerShape(8.dp)
    val large = RoundedCornerShape(12.dp)
    val full = RoundedCornerShape(percent = 50)
}

/** Fixed control dimensions, including the accessibility minimum touch target. */
object Sizes {
    /** Minimum touch target (spec 57). Any interactive element must clear this. */
    val minTouchTarget: Dp = 48.dp

    /** Height of the primary pill buttons in the reference. */
    val buttonHeight: Dp = 56.dp

    /** Outer ring of the camera shutter control. */
    val shutterOuter: Dp = 72.dp
    val shutterInner: Dp = 60.dp
    val shutterInnerPressed: Dp = 52.dp

    /** Circular icon buttons in the camera overlay. */
    val cameraControl: Dp = 48.dp
}
