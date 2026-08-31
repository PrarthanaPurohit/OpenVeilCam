package com.openveil.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.openveil.ui.theme.OpenVeilColors
import com.openveil.ui.theme.OpenVeilShapes
import com.openveil.ui.theme.Spacing

/**
 * The elevated card used throughout the design: a slightly lifted surface with a hairline
 * white border at 6% opacity. The reference calls this the "glass" treatment.
 *
 * Real blur is deliberately not used -- these cards sit on a flat background, so a blur
 * would cost a render pass and change nothing visible.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    contentPadding: androidx.compose.ui.unit.Dp = Spacing.md,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .clip(OpenVeilShapes.large)
            .background(OpenVeilColors.SurfaceContainerLow)
            .border(1.dp, OpenVeilColors.GlassBorder, OpenVeilShapes.large)
            .padding(contentPadding),
        content = content,
    )
}

/** Section heading: small uppercase mono, the design's recurring label treatment. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    androidx.compose.material3.Text(
        text = text.uppercase(),
        style = com.openveil.ui.theme.OpenVeilTheme.type.labelCaps,
        color = OpenVeilColors.OutlineVariant,
        modifier = modifier,
    )
}
