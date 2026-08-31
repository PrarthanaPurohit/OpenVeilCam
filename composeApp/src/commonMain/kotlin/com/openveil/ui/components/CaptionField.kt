package com.openveil.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.openveil.ui.theme.OpenVeilColors
import com.openveil.ui.theme.OpenVeilShapes
import com.openveil.ui.theme.OpenVeilTheme
import com.openveil.ui.theme.Spacing

/**
 * Optional note attached to a photo before it is published.
 *
 * Built on [BasicTextField] rather than Material's `OutlinedTextField` so the field
 * inherits the app's own surface, border and type scale instead of dragging in Material's
 * container colours, which do not match this palette.
 *
 * The limit is enforced here rather than validated on submit: silently truncating at
 * publish time, or having a relay reject an oversized event, are both worse than simply
 * refusing the extra keystroke.
 */
@Composable
fun CaptionField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    maxCharacters: Int = MAX_CAPTION_CHARACTERS,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val keyboard = LocalSoftwareKeyboardController.current

    val borderColor = if (focused) {
        OpenVeilColors.Primary.copy(alpha = 0.7f)
    } else {
        OpenVeilColors.OutlineVariant.copy(alpha = 0.6f)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionLabel("Add a note")
            // The counter only appears as the limit gets close; showing it from an empty
            // field reads as a demand for brevity that nobody asked for.
            if (value.length > maxCharacters - 100) {
                Text(
                    "${value.length} / $maxCharacters",
                    style = OpenVeilTheme.type.metadata,
                    color = if (value.length >= maxCharacters) {
                        OpenVeilColors.Error
                    } else {
                        OpenVeilColors.OnSurfaceVariant
                    },
                )
            }
        }

        Spacer(Modifier.height(Spacing.sm))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 96.dp)
                .clip(OpenVeilShapes.medium)
                .background(OpenVeilColors.SurfaceContainerLow)
                .border(1.dp, borderColor, OpenVeilShapes.medium)
                .padding(Spacing.md),
        ) {
            if (value.isEmpty()) {
                Text(
                    "What is happening here?",
                    style = OpenVeilTheme.type.bodyMd,
                    color = OpenVeilColors.Outline,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = { if (it.length <= maxCharacters) onValueChange(it) },
                modifier = Modifier.fillMaxWidth(),
                textStyle = OpenVeilTheme.type.bodyMd.copy(color = OpenVeilColors.OnSurface),
                cursorBrush = SolidColor(OpenVeilColors.Primary),
                interactionSource = interactionSource,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
            )
        }

        Spacer(Modifier.height(Spacing.sm))

        // Said plainly, and before the fact. Nostr has no reliable delete: NIP-09 deletion
        // requests are advisory and relays may ignore them, so a published note is
        // effectively permanent. Someone writing a location or a name here needs to know
        // that before they press Publish, not after.
        Text(
            "This note is published publicly alongside the photo and cannot be edited or " +
                "deleted afterwards. The photo's signature does not vouch for it.",
            style = OpenVeilTheme.type.bodySm,
            color = OpenVeilColors.OnSurfaceVariant,
        )
    }
}

/**
 * Generous enough for a paragraph of context, short of the size where relays start
 * rejecting events.
 */
const val MAX_CAPTION_CHARACTERS = 500
