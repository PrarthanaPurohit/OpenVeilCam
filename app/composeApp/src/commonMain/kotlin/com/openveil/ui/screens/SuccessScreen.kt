package com.openveil.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.openveil.ui.components.GlassCard
import com.openveil.ui.components.MaterialSymbol
import com.openveil.ui.components.OpenVeilButton
import com.openveil.ui.components.OpenVeilIcon
import com.openveil.ui.components.OpenVeilSecondaryButton
import com.openveil.ui.components.StatusRow
import com.openveil.ui.components.VerificationState
import com.openveil.ui.components.decodeImageForDisplay
import com.openveil.ui.theme.OpenVeilColors
import com.openveil.ui.theme.OpenVeilShapes
import com.openveil.ui.theme.OpenVeilTheme
import com.openveil.ui.theme.Sizes
import com.openveil.ui.theme.Spacing

/** Facts about a completed publication, all drawn from the real capture and result. */
data class SuccessUiState(
    val imageBytes: ByteArray,
    /** Real capture facts, e.g. "JPEG" and "12.2 MP" -- never a hardcoded "RAW 24MP". */
    val format: String,
    val megapixels: String,
    val relayCount: Int,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is SuccessUiState &&
            imageBytes.contentEquals(other.imageBytes) && format == other.format &&
            megapixels == other.megapixels && relayCount == other.relayCount)

    override fun hashCode(): Int = imageBytes.contentHashCode() * 31 + format.hashCode()
}

/** Confirmation that a photo is published and verifiable, with the route to its evidence. */
@Composable
fun SuccessScreen(
    state: SuccessUiState,
    onViewDetails: () -> Unit,
    onShare: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    var actionBarHeight by remember { mutableStateOf(0.dp) }

    Box(modifier = modifier.fillMaxSize().background(OpenVeilColors.Background)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.containerMargin),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(Spacing.lg))

            // 64dp, not 96dp: this is a confirmation the user passes through, not a
            // milestone screen. An oversized mark pushes the photo itself below the fold.
            Box(
                Modifier.size(64.dp).clip(CircleShape).background(OpenVeilColors.SuccessSubtle),
                contentAlignment = Alignment.Center,
            ) {
                MaterialSymbol(
                    OpenVeilIcon.CheckCircle,
                    contentDescription = null,
                    size = 36.dp,
                    tint = OpenVeilColors.Success,
                    filled = true,
                )
            }

            Spacer(Modifier.height(Spacing.md))
            Text(
                "Photo published",
                style = OpenVeilTheme.type.headlineMd,
                color = OpenVeilColors.OnSurface,
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                "Anyone can now verify this photo came from you, unaltered.",
                style = OpenVeilTheme.type.bodySm,
                color = OpenVeilColors.OnSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(Spacing.lg))

            PublishedPhoto(state)

            Spacer(Modifier.height(Spacing.lg))

            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    StatusRow(
                        icon = OpenVeilIcon.Verified,
                        title = "Content Credentials",
                        state = VerificationState.Verified,
                    )
                    StatusRow(
                        icon = OpenVeilIcon.CloudDone,
                        title = "Blossom",
                        state = VerificationState.Stored,
                    )
                    StatusRow(
                        icon = OpenVeilIcon.Public,
                        title = "Nostr",
                        state = VerificationState.Published,
                        supporting = if (state.relayCount == 1) {
                            "Accepted by 1 relay"
                        } else {
                            "Accepted by ${state.relayCount} relays"
                        },
                    )
                }

                // Details hangs off the summary rather than competing for space in the
                // action bar: this card states what happened, and tapping through is how
                // you get to the evidence for it.
                Spacer(Modifier.height(Spacing.md))
                HorizontalDivider(color = OpenVeilColors.OutlineVariant.copy(alpha = 0.4f))
                Spacer(Modifier.height(Spacing.xs))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(OpenVeilShapes.small)
                        .clickable(onClickLabel = "View technical details", onClick = onViewDetails)
                        // Padding must be symmetric and inside the clickable: a top-only
                        // pad made the ripple sit above the text with nothing beneath it,
                        // so the row read as half-highlighted. defaultMinSize also brings
                        // the target up to the 48dp minimum, which the text alone missed.
                        .defaultMinSize(minHeight = Sizes.minTouchTarget)
                        .padding(vertical = Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Hashes, links and verification",
                        style = OpenVeilTheme.type.bodySm,
                        color = OpenVeilColors.Primary,
                        modifier = Modifier.weight(1f),
                    )
                    MaterialSymbol(
                        OpenVeilIcon.ArrowForward,
                        contentDescription = null,
                        size = 18.dp,
                        tint = OpenVeilColors.Primary,
                    )
                }
            }

            // Exactly clears the fixed action bar, measured below.
            Spacer(Modifier.height(actionBarHeight + Spacing.lg))
        }

        // SurfaceContainerLow, not Surface: Surface and Background are the same colour in
        // this palette, so a bar painted with it is invisible and content scrolling
        // underneath collides with the buttons. The hairline gives it a real edge.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .onSizeChanged { actionBarHeight = with(density) { it.height.toDp() } }
                .background(OpenVeilColors.SurfaceContainerLow),
        ) {
            HorizontalDivider(color = OpenVeilColors.GlassBorder)
            // Done is the primary: it is how the flow ends, and the photo is already
            // published by the time this screen appears, so nothing here is destructive.
            Row(
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(Spacing.containerMargin),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Box(Modifier.weight(1f)) {
                    OpenVeilSecondaryButton("Share", onShare, icon = OpenVeilIcon.Share)
                }
                Box(Modifier.weight(1.4f)) {
                    OpenVeilButton("Done", onDone)
                }
            }
        }
    }
}

/**
 * The published photo, shown at its own aspect ratio.
 *
 * The previous fixed 4:5 frame cropped a 4:3 landscape capture down to a portrait slice --
 * on the one screen whose job is to show what was actually published. The ratio is taken
 * from the decoded image and only clamped at the extremes, so a panorama or a very tall
 * portrait cannot take over the screen.
 */
@Composable
private fun PublishedPhoto(state: SuccessUiState) {
    val bitmap = remember(state.imageBytes) { decodeImageForDisplay(state.imageBytes) } ?: return
    val ratio = (bitmap.width.toFloat() / bitmap.height.toFloat()).coerceIn(0.8f, 1.9f)

    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
            .clip(OpenVeilShapes.large)
            .background(OpenVeilColors.SurfaceContainerLow)
    ) {
        Image(
            bitmap = bitmap,
            contentDescription = "The photo you published",
            modifier = Modifier.fillMaxSize(),
            // The box already matches the image's ratio, so Fit and Crop agree -- except
            // at the clamp boundary for an extreme panorama, where Fit shows all of it.
            contentScale = ContentScale.Fit,
        )
        Text(
            "${state.format} · ${state.megapixels}",
            style = OpenVeilTheme.type.labelCaps,
            color = OpenVeilColors.OnSurface,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(Spacing.sm)
                .clip(OpenVeilShapes.full)
                .background(OpenVeilColors.SurfaceContainerHigh.copy(alpha = 0.85f))
                .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
        )
    }
}
