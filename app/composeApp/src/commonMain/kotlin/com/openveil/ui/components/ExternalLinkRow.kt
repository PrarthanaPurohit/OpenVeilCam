package com.openveil.ui.components

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openveil.ui.theme.OpenVeilColors
import com.openveil.ui.theme.OpenVeilShapes
import com.openveil.ui.theme.OpenVeilTheme
import com.openveil.ui.theme.Spacing

/**
 * A link out of the app, with copy as a first-class alternative.
 *
 * Tapping opens a browser, but the copy action is a visible control rather than a hidden
 * long-press: these links get pasted into messages and articles far more often than they
 * get followed, and a link the user cannot get *out* of the app is close to useless.
 */
@Composable
fun ExternalLinkRow(
    icon: OpenVeilIcon,
    title: String,
    url: String,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
) {
    val clipboard = LocalClipboardManager.current

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(OpenVeilShapes.small)
            .background(OpenVeilColors.Surface)
            .border(1.dp, OpenVeilColors.OutlineVariant.copy(alpha = 0.4f), OpenVeilShapes.small)
            .clickable(onClickLabel = "Open $title in a browser") { onOpen(url) }
            .padding(Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MaterialSymbol(icon, contentDescription = null, size = 20.dp, tint = OpenVeilColors.Primary)

        Column(Modifier.weight(1f)) {
            Text(title, style = OpenVeilTheme.type.bodySm, color = OpenVeilColors.OnSurface)
            if (supporting != null) {
                Text(
                    // Prose, so the sans face -- the mono below it is the machine value.
                    supporting,
                    style = OpenVeilTheme.type.bodySm,
                    color = OpenVeilColors.OnSurfaceVariant,
                )
            }
            Spacer(Modifier.height(Spacing.xs))
            Text(
                url,
                style = OpenVeilTheme.type.metadata,
                color = OpenVeilColors.Primary,
                maxLines = 1,
                // These identifiers are long by design (they carry relay hints), so the
                // row shows a recognisable prefix and the copy control supplies the rest.
                overflow = TextOverflow.Ellipsis,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            IconAction(
                icon = OpenVeilIcon.ContentCopy,
                contentDescription = "Copy link to $title",
                onClick = { clipboard.setText(AnnotatedString(url)) },
            )
            MaterialSymbol(
                OpenVeilIcon.OpenInNew,
                contentDescription = null,
                size = 16.dp,
                tint = OpenVeilColors.OnSurfaceVariant,
            )
        }
    }
}

/** A small tappable icon with its own accessible label and hit target. */
@Composable
private fun IconAction(
    icon: OpenVeilIcon,
    contentDescription: String,
    onClick: () -> Unit,
) {
    MaterialSymbol(
        icon,
        contentDescription = contentDescription,
        size = 16.dp,
        tint = OpenVeilColors.Primary,
        modifier = Modifier
            .clip(OpenVeilShapes.full)
            .clickable(onClickLabel = contentDescription, onClick = onClick)
            .padding(Spacing.xs),
    )
}
