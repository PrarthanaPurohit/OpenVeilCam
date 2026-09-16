package com.openveil.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.openveil.ui.components.GlassCard
import com.openveil.ui.components.MaterialSymbol
import com.openveil.ui.components.OpenVeilIcon
import com.openveil.ui.components.SectionLabel
import com.openveil.ui.components.StatusRow
import com.openveil.ui.components.VerificationBadge
import com.openveil.ui.components.VerificationState
import com.openveil.ui.theme.OpenVeilColors
import com.openveil.ui.theme.OpenVeilShapes
import com.openveil.ui.theme.OpenVeilTheme
import com.openveil.ui.theme.Sizes
import com.openveil.ui.theme.Spacing

/**
 * Real identity state for the Home card.
 *
 * Every field here is observed, never assumed. The reference design hardcoded three green
 * checkmarks; an always-green indicator teaches people to stop reading indicators, which
 * is the exact instinct a provenance app needs them to keep.
 */
data class IdentityStatus(
    val nostr: VerificationState,
    val contentCredentials: VerificationState,
    val storage: VerificationState,
    /** bech32 npub, shown truncated. Null until the key has been generated. */
    val npub: String? = null,
    /** npub of the user's own linked Nostr account. Null when none is linked, which is the default. */
    val linkedNpub: String? = null,
)

/** True when this component is doing its job, as opposed to pending or broken. */
private val VerificationState.isSatisfied: Boolean
    get() = this == VerificationState.Verified ||
        this == VerificationState.Stored ||
        this == VerificationState.Published ||
        this == VerificationState.Ready

/**
 * Landing screen: device readiness at a glance, and the capture action.
 *
 * Every status here is observed rather than assumed. An always-green indicator teaches
 * people to stop reading indicators, which is the instinct this product most needs them to
 * keep.
 */
@Composable
fun HomeScreen(
    identity: IdentityStatus,
    onOpenCamera: () -> Unit,
    onLinkAccount: () -> Unit,
    onUnlinkAccount: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val components = listOf(identity.nostr, identity.contentCredentials, identity.storage)
    val allReady = components.all { it.isSatisfied }
    val anyFailed = components.any { it == VerificationState.Failed }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(OpenVeilColors.Background)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.containerMargin),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        Spacer(Modifier.height(Spacing.xs))

        BrandHeader(allReady = allReady, anyFailed = anyFailed)

        CaptureHero(onOpenCamera = onOpenCamera, ready = allReady)

        GlassCard {
            SectionLabel("Your identity")
            Spacer(Modifier.height(Spacing.md))
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                StatusRow(
                    icon = OpenVeilIcon.VpnKey,
                    title = "Nostr key",
                    state = identity.nostr,
                )
                StatusRow(
                    icon = OpenVeilIcon.ShieldLock,
                    title = "Content Credentials",
                    state = identity.contentCredentials,
                )
                StatusRow(
                    icon = OpenVeilIcon.CloudDone,
                    title = "Blossom storage",
                    state = identity.storage,
                )
            }

            // The npub gets its own row rather than sitting as sub-text under "Nostr key":
            // it is the one value on this screen someone will want to hand to another
            // person, so it needs to be readable and copyable, not decoration.
            if (identity.npub != null) {
                Spacer(Modifier.height(Spacing.md))
                HorizontalDivider(color = OpenVeilColors.OutlineVariant.copy(alpha = 0.4f))
                Spacer(Modifier.height(Spacing.md))
                NpubRow(identity.npub)
            }
        }

        // Separate card, not a fourth status row. The rows above are things the device
        // must have; this is something the user may choose to add, and putting it beside
        // them would make "not linked" read as a fault.
        LinkedAccountCard(
            linkedNpub = identity.linkedNpub,
            onLink = onLinkAccount,
            onUnlink = onUnlinkAccount,
        )

        HowItWorks()

        Spacer(Modifier.height(Spacing.lg))
    }
}

@Composable
private fun BrandHeader(allReady: Boolean, anyFailed: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MaterialSymbol(
                OpenVeilIcon.Camera,
                contentDescription = null,
                size = 26.dp,
                tint = OpenVeilColors.Primary,
            )
            Spacer(Modifier.width(Spacing.sm))
            Text(
                "OpenVeil",
                style = OpenVeilTheme.type.headlineMd,
                color = OpenVeilColors.OnSurface,
            )
        }

        // One glanceable answer to "can I trust this device right now", instead of making
        // the user read three rows and combine them.
        VerificationBadge(
            state = when {
                anyFailed -> VerificationState.Failed
                allReady -> VerificationState.Ready
                else -> VerificationState.Pending
            },
            label = when {
                anyFailed -> "Attention"
                allReady -> "Ready"
                else -> "Checking"
            },
            subject = "Device status",
        )
    }
}

/**
 * The one thing this screen exists for.
 *
 * The whole panel is a single tap target with the ring as its visual anchor, rather than a
 * small button inside a decorative card -- it is the primary action of the app and should
 * be hard to miss and hard to miss-tap.
 */
@Composable
private fun CaptureHero(onOpenCamera: () -> Unit, ready: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(OpenVeilShapes.large)
            .background(
                Brush.verticalGradient(
                    listOf(
                        OpenVeilColors.Primary.copy(alpha = 0.16f),
                        OpenVeilColors.SurfaceContainerLow,
                    )
                )
            )
            .border(1.dp, OpenVeilColors.Primary.copy(alpha = 0.24f), OpenVeilShapes.large)
            .clickable(onClickLabel = "Open camera", onClick = onOpenCamera)
            .padding(vertical = Spacing.xl, horizontal = Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Concentric rings rather than a drop shadow: shadows read as elevation, and this
        // needs to read as a lens.
        Box(contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(132.dp).clip(CircleShape)
                    .background(OpenVeilColors.Primary.copy(alpha = 0.10f))
            )
            Box(
                Modifier.size(108.dp).clip(CircleShape)
                    .background(OpenVeilColors.Primary.copy(alpha = 0.18f))
            )
            Box(
                Modifier.size(84.dp).clip(CircleShape).background(OpenVeilColors.Primary),
                contentAlignment = Alignment.Center,
            ) {
                MaterialSymbol(
                    OpenVeilIcon.PhotoCamera,
                    contentDescription = null,
                    size = 38.dp,
                    tint = OpenVeilColors.OnPrimary,
                    filled = true,
                )
            }
        }

        Spacer(Modifier.height(Spacing.lg))

        Text(
            "Capture a verifiable photo",
            style = OpenVeilTheme.type.headlineSm,
            color = OpenVeilColors.OnSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            if (ready) {
                "Signed the moment it is taken, then published so anyone can check it."
            } else {
                // Never block the shutter on setup state -- a photo that cannot be
                // published is still worth capturing, and the scene will not wait.
                "Setup is still finishing. You can capture now and publish once it is ready."
            },
            style = OpenVeilTheme.type.bodySm,
            color = OpenVeilColors.OnSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(Spacing.lg))

        Row(
            modifier = Modifier
                .clip(OpenVeilShapes.full)
                .background(OpenVeilColors.Primary)
                .padding(horizontal = Spacing.lg, vertical = Spacing.gutter),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Open camera",
                style = OpenVeilTheme.type.button,
                color = OpenVeilColors.OnPrimary,
            )
            Spacer(Modifier.width(Spacing.sm))
            MaterialSymbol(
                OpenVeilIcon.ArrowForward,
                contentDescription = null,
                size = 18.dp,
                tint = OpenVeilColors.OnPrimary,
            )
        }
    }
}

/**
 * The optional link to the user's own Nostr account.
 *
 * Unlinked is the default and is presented as a perfectly good state, not a gap: the
 * device identity publishes fine on its own, and for many of the people this app is
 * built for, *not* tying captures to a public persona is the point.
 */
@Composable
private fun LinkedAccountCard(
    linkedNpub: String?,
    onLink: () -> Unit,
    onUnlink: () -> Unit,
) {
    GlassCard {
        SectionLabel("Your Nostr account")
        Spacer(Modifier.height(Spacing.sm))

        if (linkedNpub == null) {
            Text(
                "Photos publish under this device's key. Link your own account to publish " +
                    "under your name instead -- you choose per photo.",
                style = OpenVeilTheme.type.bodySm,
                color = OpenVeilColors.OnSurfaceVariant,
            )
            Spacer(Modifier.height(Spacing.md))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(OpenVeilShapes.small)
                    .clickable(onClickLabel = "Link your Nostr account", onClick = onLink)
                    .defaultMinSize(minHeight = Sizes.minTouchTarget)
                    .padding(vertical = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MaterialSymbol(
                    OpenVeilIcon.Link,
                    contentDescription = null,
                    size = 20.dp,
                    tint = OpenVeilColors.Primary,
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    "Link with a bunker",
                    style = OpenVeilTheme.type.bodyMd,
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
        } else {
            Text(
                "Linked through your remote signer. Your key never enters this app.",
                style = OpenVeilTheme.type.bodySm,
                color = OpenVeilColors.OnSurfaceVariant,
            )
            Spacer(Modifier.height(Spacing.md))
            Row(
                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = Sizes.minTouchTarget),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "LINKED NPUB",
                        style = OpenVeilTheme.type.metadata,
                        color = OpenVeilColors.Outline,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        truncateNpub(linkedNpub),
                        style = OpenVeilTheme.type.metadata,
                        color = OpenVeilColors.OnSurface,
                    )
                }
                Text(
                    "Unlink",
                    style = OpenVeilTheme.type.button,
                    color = OpenVeilColors.Error,
                    modifier = Modifier
                        .clip(OpenVeilShapes.full)
                        .clickable(onClickLabel = "Unlink your Nostr account", onClick = onUnlink)
                        .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                )
            }
        }
    }
}

@Composable
private fun NpubRow(npub: String) {
    val clipboard = LocalClipboardManager.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(OpenVeilShapes.small)
            .clickable(onClickLabel = "Copy your npub") {
                clipboard.setText(AnnotatedString(npub))
            }
            // 4dp of padding left this well under the 48dp minimum target, and the
            // ripple barely showed. Same fix as the details row on the success screen.
            .defaultMinSize(minHeight = Sizes.minTouchTarget)
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "YOUR NPUB",
                style = OpenVeilTheme.type.metadata,
                color = OpenVeilColors.Outline,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                truncateNpub(npub),
                style = OpenVeilTheme.type.metadata,
                color = OpenVeilColors.OnSurface,
            )
        }
        MaterialSymbol(
            OpenVeilIcon.ContentCopy,
            contentDescription = null,
            size = 18.dp,
            tint = OpenVeilColors.Primary,
        )
    }
}

/**
 * What the app actually does, in three lines.
 *
 * This is not filler for empty space: the product's value is a chain of claims most people
 * have never met, and someone who does not know what "Content Credentials" or "Nostr" buy
 * them cannot judge whether the green badges above are worth anything.
 */
@Composable
private fun HowItWorks() {
    GlassCard {
        SectionLabel("How it works")
        Spacer(Modifier.height(Spacing.md))
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            HowItWorksStep(
                step = "1",
                title = "Signed at capture",
                body = "Content Credentials are attached before the photo leaves the camera.",
            )
            HowItWorksStep(
                step = "2",
                title = "Stored by its own hash",
                body = "The file is addressed by its SHA-256, so a swapped image cannot hide.",
            )
            HowItWorksStep(
                step = "3",
                title = "Published to Nostr",
                body = "A signed record anyone can check, on relays you do not control.",
            )
        }
    }
}

@Composable
private fun HowItWorksStep(step: String, title: String, body: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(OpenVeilColors.Primary.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(step, style = OpenVeilTheme.type.metadata, color = OpenVeilColors.Primary)
        }
        Spacer(Modifier.width(Spacing.md))
        Column {
            Text(title, style = OpenVeilTheme.type.bodySm, color = OpenVeilColors.OnSurface)
            Spacer(Modifier.height(2.dp))
            // bodySm, not metadata: the mono face is reserved for machine-generated values
            // (hashes, npubs, event ids). Prose set in mono reads as terminal output.
            Text(body, style = OpenVeilTheme.type.bodySm, color = OpenVeilColors.OnSurfaceVariant)
        }
    }
}

/** npub1abc...wxyz -- enough to recognise, short enough to sit under a row title. */
internal fun truncateNpub(npub: String): String =
    if (npub.length <= 20) npub else "${npub.take(12)}...${npub.takeLast(8)}"
