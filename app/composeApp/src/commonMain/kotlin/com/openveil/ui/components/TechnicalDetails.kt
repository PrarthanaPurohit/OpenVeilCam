package com.openveil.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.openveil.ui.theme.OpenVeilColors
import com.openveil.ui.theme.OpenVeilShapes
import com.openveil.ui.theme.OpenVeilTheme
import com.openveil.ui.theme.Spacing

/**
 * Collapsed-by-default technical section.
 *
 * The premise of the product is that a normal user never needs to read a SHA-256, but
 * someone verifying a contested photo absolutely does. Collapsing rather than omitting
 * serves both.
 */
@Composable
fun TechnicalDetailsAccordion(
    modifier: Modifier = Modifier,
    title: String = "Technical details",
    initiallyExpanded: Boolean = false,
    content: @Composable () -> Unit,
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    val chevronAngle by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")

    GlassCard(modifier = modifier, contentPadding = 0.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    onClickLabel = if (expanded) "Collapse $title" else "Expand $title",
                ) { expanded = !expanded }
                .padding(Spacing.md),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionLabel(title)
            MaterialSymbol(
                OpenVeilIcon.ExpandMore,
                contentDescription = null,
                tint = OpenVeilColors.OnSurfaceVariant,
                modifier = Modifier.rotate(chevronAngle),
            )
        }
        AnimatedVisibility(expanded) {
            Column(
                modifier = Modifier.padding(start = Spacing.md, end = Spacing.md, bottom = Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) { content() }
        }
    }
}

/**
 * A labelled machine value -- hash, event id, npub, URL. Monospace, wrapping, and
 * copyable, because these are values people paste into other tools to check a claim.
 */
@Composable
fun TechnicalDetailRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    Column(modifier = modifier.fillMaxWidth()) {
        Text(label, style = OpenVeilTheme.type.metadata, color = OpenVeilColors.OnSurfaceVariant)
        Spacer(Modifier.height(Spacing.xs))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(OpenVeilShapes.small)
                .background(OpenVeilColors.Surface)
                .border(1.dp, OpenVeilColors.OutlineVariant.copy(alpha = 0.4f), OpenVeilShapes.small)
                .clickable(onClickLabel = "Copy $label") {
                    clipboard.setText(AnnotatedString(value))
                }
                .padding(Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                value,
                style = OpenVeilTheme.type.metadata,
                color = OpenVeilColors.OnSurface,
                modifier = Modifier.weight(1f),
            )
            MaterialSymbol(
                OpenVeilIcon.ContentCopy,
                contentDescription = null,
                size = 16.dp,
                tint = OpenVeilColors.Primary,
            )
        }
    }
}

/** Two-column spec-sheet pair: small mono caption above a readable value. */
@Composable
fun SpecPair(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            label.uppercase(),
            style = OpenVeilTheme.type.metadata,
            color = OpenVeilColors.Outline,
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(value, style = OpenVeilTheme.type.bodySm, color = OpenVeilColors.OnSurface)
    }
}
