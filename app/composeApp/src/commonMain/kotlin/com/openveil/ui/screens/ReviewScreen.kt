package com.openveil.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.openveil.ui.components.CaptionField
import com.openveil.ui.components.ChecklistRow
import com.openveil.ui.components.GlassCard
import com.openveil.ui.components.MaterialSymbol
import com.openveil.ui.components.OpenVeilButton
import com.openveil.ui.components.OpenVeilIcon
import com.openveil.ui.components.OpenVeilSecondaryButton
import com.openveil.ui.components.SectionLabel
import com.openveil.ui.components.SpecPair
import com.openveil.ui.components.VerificationBadge
import com.openveil.ui.components.VerificationState
import com.openveil.ui.components.decodeImageForDisplay
import com.openveil.ui.theme.OpenVeilColors
import com.openveil.ui.theme.OpenVeilShapes
import com.openveil.ui.theme.OpenVeilTheme
import com.openveil.ui.theme.Spacing

/**
 * What the Review screen knows about the capture.
 *
 * [signingComplete] is live state, not decoration: signing is kicked off the moment the
 * shutter fires and runs while the user is reading this screen, so the checklist flips on
 * its own. That is also why Publish stays disabled until it is true -- there is nothing to
 * upload until the signed master exists.
 */
data class ReviewUiState(
    val imageBytes: ByteArray,
    val signingComplete: Boolean,
    val signingFailed: Boolean = false,
    /** EXIF the camera actually reported. Absent entries are simply not shown. */
    val exif: Map<String, String> = emptyMap(),
    /** Optional note the photographer is writing. Published with the photo, not signed into it. */
    val caption: String = "",
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is ReviewUiState &&
            imageBytes.contentEquals(other.imageBytes) &&
            signingComplete == other.signingComplete &&
            signingFailed == other.signingFailed &&
            caption == other.caption &&
            exif == other.exif)

    override fun hashCode(): Int =
        imageBytes.contentHashCode() * 31 + signingComplete.hashCode()
}

/**
 * The decision point: what was captured, what has been signed so far, and whether to
 * publish it irreversibly.
 *
 * The photo is shown complete rather than cropped to fit. Approving a frame whose edges
 * you never saw is precisely the failure this screen exists to prevent.
 */
@Composable
fun ReviewScreen(
    state: ReviewUiState,
    onRetake: () -> Unit,
    onPublish: () -> Unit,
    onClose: () -> Unit,
    onCaptionChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    var actionBarHeight by remember { mutableStateOf(0.dp) }
    val scroll = rememberScrollState()

    Box(modifier = modifier.fillMaxSize().background(OpenVeilColors.Background)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(380.dp)
                    .background(OpenVeilColors.SurfaceContainerLowest)
            ) {
                val bitmap = remember(state.imageBytes) { decodeImageForDisplay(state.imageBytes) }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = "The photo you just captured",
                        modifier = Modifier.fillMaxSize(),
                    // Fit, never Crop. Every screen that shows the photo must show all
                    // of it: this is the screen where the user decides to publish
                    // irreversibly, and a crop here means approving a frame whose edges
                    // they never saw. Letterboxing is the honest trade.
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Box(Modifier.fillMaxSize().background(OpenVeilColors.SurfaceContainer))
                }

                VerificationBadge(
                    state = when {
                        state.signingFailed -> VerificationState.Failed
                        state.signingComplete -> VerificationState.Ready
                        else -> VerificationState.InProgress
                    },
                    label = when {
                        state.signingFailed -> "Signing failed"
                        state.signingComplete -> "Signed"
                        else -> "Signing"
                    },
                    subject = "Content Credentials",
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(Spacing.md),
                )
            }

            Column(
                modifier = Modifier.padding(
                    start = Spacing.containerMargin,
                    end = Spacing.containerMargin,
                    top = Spacing.lg,
                ),
                verticalArrangement = Arrangement.spacedBy(Spacing.lg),
            ) {
                GlassCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MaterialSymbol(
                            OpenVeilIcon.ShieldLock,
                            contentDescription = null,
                            tint = OpenVeilColors.Primary,
                            filled = true,
                        )
                        Spacer(Modifier.width(Spacing.sm))
                        Text(
                            "This photo can be verified",
                            style = OpenVeilTheme.type.headlineSm,
                            color = OpenVeilColors.OnSurface,
                        )
                    }
                    Spacer(Modifier.height(Spacing.sm))
                    Text(
                        "Your photo is cryptographically signed with Content Credentials before " +
                            "it is published, so anyone can check where it came from.",
                        style = OpenVeilTheme.type.bodyMd,
                        color = OpenVeilColors.OnSurfaceVariant,
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    SectionLabel("Publishing checklist")
                    ChecklistRow("Original capture", done = true)
                    ChecklistRow(
                        "Content Credentials",
                        done = state.signingComplete,
                        inProgress = !state.signingComplete && !state.signingFailed,
                    )
                    ChecklistRow("Published to Nostr", done = false)
                }

                CaptionField(value = state.caption, onValueChange = onCaptionChange)

                if (state.exif.isNotEmpty()) {
                    GlassCard {
                        SectionLabel("Capture data")
                        Spacer(Modifier.height(Spacing.sm))
                        // Only fields the camera actually reported. The reference design
                        // showed placeholder lens/ISO values and a GPS coordinate; OpenVeil
                        // never reads location, so that row does not exist.
                        state.exif.entries.chunked(2).forEach { pair ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = Spacing.xs),
                                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                            ) {
                                pair.forEach { (label, value) ->
                                    SpecPair(label, value, Modifier.weight(1f))
                                }
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }

                // Exactly clears the fixed action bar, measured below. A hardcoded
                // guess breaks as soon as the navigation-bar inset or the user's font
                // scale differs from the device it was tuned on, leaving the last card
                // permanently unreachable.
                Spacer(Modifier.height(actionBarHeight + Spacing.lg))
            }
        }

        // Fixed action bar.
        // SurfaceContainerLow, not Surface: Surface and Background are the same colour in
        // this palette, so a bar painted with it is invisible and content scrolling
        // underneath collides with the buttons. The hairline gives it a real edge.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .onSizeChanged { actionBarHeight = with(density) { it.height.toDp() } }
                .background(OpenVeilColors.SurfaceContainerLow)
                .imePadding(),
        ) {
            HorizontalDivider(color = OpenVeilColors.GlassBorder)
            Column(
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(Spacing.containerMargin),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    // 1:1.7 rather than 1:2 -- "Retake" plus its icon does not fit a
                    // one-third slot, and the primary action still reads as dominant.
                    Box(Modifier.weight(1f)) {
                        OpenVeilSecondaryButton("Retake", onRetake, icon = OpenVeilIcon.Replay)
                    }
                    Box(Modifier.weight(1.7f)) {
                        OpenVeilButton(
                            text = if (state.signingComplete) "Publish photo" else "Signing...",
                            onClick = onPublish,
                            icon = OpenVeilIcon.Publish,
                            enabled = state.signingComplete,
                        )
                    }
                }
            }
        }

        // Close floats over the photo, so once the photo scrolls away it would sit on
        // top of card text. Same treatment as the details screen: the bar fades to opaque
        // as the image leaves, keeping both the control and what is behind it readable.
        val topBarAlpha = with(density) { (scroll.value / 240.dp.toPx()).coerceIn(0f, 1f) }
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(OpenVeilColors.Background.copy(alpha = topBarAlpha))
        ) {
            Box(Modifier.statusBarsPadding().padding(Spacing.sm)) {
                com.openveil.ui.components.CameraControlButton(
                    icon = OpenVeilIcon.Close,
                    contentDescription = "Discard and close",
                    onClick = onClose,
                )
            }
        }
    }
}
