package com.openveil.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openveil.ui.theme.OpenVeilColors
import com.openveil.ui.theme.OpenVeilShapes
import com.openveil.ui.theme.OpenVeilTheme
import com.openveil.ui.theme.Sizes
import com.openveil.ui.theme.Spacing

/** Primary action: a full-width filled pill. One per screen. */
@Composable
fun OpenVeilButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: OpenVeilIcon? = null,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(Sizes.buttonHeight),
        shape = OpenVeilShapes.full,
        contentPadding = PaddingValues(horizontal = Spacing.gutter),
        colors = ButtonDefaults.buttonColors(
            containerColor = OpenVeilColors.PrimaryContainer,
            contentColor = OpenVeilColors.OnPrimaryContainer,
            disabledContainerColor = OpenVeilColors.SurfaceContainerHigh,
            disabledContentColor = OpenVeilColors.Outline,
        ),
    ) {
        if (icon != null) {
            MaterialSymbol(icon, contentDescription = null, size = 20.dp)
            Spacer(Modifier.size(Spacing.sm))
        }
        Text(text, style = OpenVeilTheme.type.button, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Secondary action: outlined pill. Pairs with [OpenVeilButton] inside [ActionRow]. */
@Composable
fun OpenVeilSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: OpenVeilIcon? = null,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(Sizes.buttonHeight),
        shape = OpenVeilShapes.full,
        border = BorderStroke(1.dp, OpenVeilColors.Outline),
        contentPadding = PaddingValues(horizontal = Spacing.gutter),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = OpenVeilColors.OnSurface,
            disabledContentColor = OpenVeilColors.Outline,
        ),
    ) {
        if (icon != null) {
            MaterialSymbol(icon, contentDescription = null, size = 20.dp)
            Spacer(Modifier.size(Spacing.sm))
        }
        Text(text, style = OpenVeilTheme.type.button, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Circular translucent control over the camera preview. Sized to [Sizes.cameraControl]
 * so it clears the 48dp minimum touch target even though the glyph is 24dp.
 */
@Composable
fun CameraControlButton(
    icon: OpenVeilIcon,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(Sizes.cameraControl)
            .clip(CircleShape)
            .background(OpenVeilColors.SurfaceContainerLow.copy(alpha = 0.4f))
            .clickable(onClickLabel = contentDescription, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        MaterialSymbol(icon, contentDescription = null, tint = Color.White)
    }
}

/**
 * The shutter: a white ring around a filled core that shrinks on press. This is the one
 * control on the camera screen that has to feel physical, so the press state is animated
 * rather than relying on a ripple.
 */
@Composable
fun ShutterButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val innerSize by animateDpAsState(
        targetValue = if (pressed) Sizes.shutterInnerPressed else Sizes.shutterInner,
        label = "shutterInner",
    )

    Box(
        modifier = modifier
            .size(Sizes.shutterOuter)
            .clip(CircleShape)
            .border(2.dp, Color.White, CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClickLabel = "Take photo",
                onClick = onClick,
            )
            .semantics { contentDescription = "Take photo" },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(innerSize)
                .clip(CircleShape)
                .background(if (enabled) Color.White else Color.White.copy(alpha = 0.4f))
        )
    }
}

/** Lays a secondary and primary action side by side at the design's 1:2 ratio. */
@Composable
fun ActionRow(
    modifier: Modifier = Modifier,
    secondary: @Composable () -> Unit,
    primary: @Composable () -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) { secondary() }
        Box(Modifier.weight(2f)) { primary() }
    }
}
