package com.openveil.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.openveil.domain.model.Photo
import com.openveil.nostr.nevent
import com.openveil.nostr.nprofile
import com.openveil.nostr.nostrEventLink
import com.openveil.nostr.nostrProfileLink
import com.openveil.ui.components.CameraControlButton
import com.openveil.ui.components.ExternalLinkRow
import com.openveil.ui.components.GlassCard
import com.openveil.ui.components.OpenVeilIcon
import com.openveil.ui.components.SectionLabel
import com.openveil.ui.components.SpecPair
import com.openveil.ui.components.StatusRow
import com.openveil.ui.components.TechnicalDetailRow
import com.openveil.ui.components.TechnicalDetailsAccordion
import com.openveil.ui.components.VerificationBadge
import com.openveil.ui.components.VerificationState
import com.openveil.ui.components.decodeImageForDisplay
import com.openveil.ui.theme.OpenVeilColors
import com.openveil.ui.theme.OpenVeilTheme
import com.openveil.ui.theme.Spacing

/** Outcome of re-verifying the Content Credential against the stored bytes. */
sealed interface CredentialCheck {
    data object NotChecked : CredentialCheck
    data object Checking : CredentialCheck

    /**
     * The signature matches the bytes, so the image is intact.
     *
     * [trusted] is a separate question: it says whether the signing certificate chains to
     * a recognised trust list. An untrusted signer does NOT mean the photo was altered --
     * conflating the two would be the most damaging error this screen could make.
     */
    data class Verified(val trusted: Boolean) : CredentialCheck

    /** The bytes no longer match the manifest. The image was altered after signing. */
    data object Invalid : CredentialCheck
    data object NotPresent : CredentialCheck
    data class Error(val message: String) : CredentialCheck
}

/**
 * Everything independently checkable about a published photo: hashes, the C2PA
 * re-verification result, relay acceptance, and shareable NIP-19 links.
 *
 * This is the screen a sceptical reader is pointed at, so it shows the raw values rather
 * than a summary of them.
 */
@Composable
fun PhotoDetailsScreen(
    photo: Photo,
    imageBytes: ByteArray?,
    capturedAtLabel: String,
    credentialCheck: CredentialCheck,
    onBack: () -> Unit,
    onShare: () -> Unit,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()

    Box(modifier = modifier.fillMaxSize().background(OpenVeilColors.Background)) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
            if (imageBytes != null) {
                val bitmap = remember(imageBytes) { decodeImageForDisplay(imageBytes) }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = "The published photo",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(340.dp)
                            .background(OpenVeilColors.SurfaceContainerLowest),
                        // A hardcoded 4:3 frame cropped every portrait capture. Fit shows
                        // the whole published file, whatever shape it is.
                        contentScale = ContentScale.Fit,
                    )
                }
            }

            Column(
                modifier = Modifier.padding(Spacing.containerMargin),
                verticalArrangement = Arrangement.spacedBy(Spacing.lg),
            ) {
                GlassCard {
                    SectionLabel("Photo details")
                    Spacer(Modifier.height(Spacing.md))
                    Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(Spacing.md)) {
                        SpecPair("Captured", capturedAtLabel, Modifier.weight(1f))
                        SpecPair("Format", formatLabel(photo.mimeType), Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(Spacing.sm))
                    Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(Spacing.md)) {
                        SpecPair("Resolution", photo.dimensions, Modifier.weight(1f))
                        SpecPair("File size", formatBytes(photo.fileSize), Modifier.weight(1f))
                    }
                    if (photo.captureDevice != null) {
                        Spacer(Modifier.height(Spacing.sm))
                        SpecPair("Capture device", photo.captureDevice!!)
                    }
                }

                GlassCard {
                    SectionLabel("Content Credentials")
                    Spacer(Modifier.height(Spacing.md))
                    val state = when (credentialCheck) {
                        is CredentialCheck.Verified -> VerificationState.Verified
                        CredentialCheck.Checking -> VerificationState.InProgress
                        CredentialCheck.Invalid -> VerificationState.Failed
                        CredentialCheck.NotPresent -> VerificationState.Unavailable
                        is CredentialCheck.Error -> VerificationState.Failed
                        CredentialCheck.NotChecked -> VerificationState.Pending
                    }
                    VerificationBadge(
                        state = state,
                        label = credentialLabel(credentialCheck),
                        subject = "Content Credentials",
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    Text(
                        credentialExplanation(credentialCheck),
                        style = OpenVeilTheme.type.bodySm,
                        color = OpenVeilColors.OnSurfaceVariant,
                    )
                }

                GlassCard {
                    SectionLabel("Publication")
                    Spacer(Modifier.height(Spacing.md))
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        StatusRow(
                            icon = OpenVeilIcon.CloudDone,
                            title = "Blossom",
                            state = if (photo.blossomUrl != null) VerificationState.Stored
                            else VerificationState.Pending,
                        )
                        StatusRow(
                            icon = OpenVeilIcon.Public,
                            title = "Nostr",
                            state = if (photo.nostrEventId != null) VerificationState.Published
                            else VerificationState.Pending,
                            supporting = photo.acceptedRelays.firstOrNull(),
                        )
                    }
                }

                // Only rendered once the photo is actually on a relay -- an identifier for
                // an unpublished event would resolve to nothing anywhere.
                val eventLink = photo.nostrEventLink()
                val profileLink = photo.nostrProfileLink()
                if (eventLink != null || profileLink != null) {
                    GlassCard {
                        SectionLabel("Find it on Nostr")
                        Spacer(Modifier.height(Spacing.sm))
                        Text(
                            "These links carry relay hints, so they open in any Nostr client " +
                                "or in a plain browser.",
                            style = OpenVeilTheme.type.bodySm,
                            color = OpenVeilColors.OnSurfaceVariant,
                        )
                        Spacer(Modifier.height(Spacing.md))
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            eventLink?.let {
                                ExternalLinkRow(
                                    icon = OpenVeilIcon.Image,
                                    title = "This photo",
                                    supporting = "The published file metadata event",
                                    url = it,
                                    onOpen = onOpenUrl,
                                )
                            }
                            profileLink?.let {
                                ExternalLinkRow(
                                    icon = OpenVeilIcon.Person,
                                    title = "This camera",
                                    supporting = "Everything published by this device",
                                    url = it,
                                    onOpen = onOpenUrl,
                                )
                            }
                        }
                    }
                }

                TechnicalDetailsAccordion {
                    photo.sha256?.let { TechnicalDetailRow("SHA-256 (published file)", it) }
                    photo.originalSha256?.let { TechnicalDetailRow("SHA-256 (original capture)", it) }
                    photo.c2paManifestId?.let { TechnicalDetailRow("C2PA manifest ID", it) }
                    photo.nostrEventId?.let { TechnicalDetailRow("Nostr event ID", it) }
                    photo.nevent()?.let { TechnicalDetailRow("nevent (with relay hints)", it) }
                    photo.nprofile()?.let { TechnicalDetailRow("nprofile (with relay hints)", it) }
                    photo.nostrPubkey?.let { TechnicalDetailRow("Nostr public key", it) }
                    photo.blossomUrl?.let { TechnicalDetailRow("Blossom URL", it) }
                    TechnicalDetailRow("MIME type", photo.mimeType)
                }

                Spacer(Modifier.height(Spacing.xl))
            }
        }

        // The controls float over a scrolling column. Over the photo they should stay
        // transparent so nothing is hidden, but once the image has scrolled away card text
        // would pass straight under them -- so the bar fades to opaque as it goes. Fading
        // over the first 240dp of scroll lands it solid at roughly the point the image
        // leaves the top of the screen.
        val density = LocalDensity.current
        val barAlpha = with(density) {
            (scroll.value / 240.dp.toPx()).coerceIn(0f, 1f)
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(OpenVeilColors.Background.copy(alpha = barAlpha))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(Spacing.sm),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                CameraControlButton(OpenVeilIcon.ArrowBack, "Go back", onBack)
                CameraControlButton(OpenVeilIcon.IosShare, "Share photo", onShare)
            }
        }
    }
}

internal fun formatLabel(mimeType: String): String = when (mimeType) {
    "image/jpeg" -> "JPEG"
    "image/png" -> "PNG"
    "image/heic" -> "HEIC"
    else -> mimeType.substringAfter('/').uppercase()
}

/** Binary units, one decimal. 4_821_932 bytes -> "4.6 MB". */
internal fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "Unknown"
    val units = listOf("bytes", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return if (unit == 0) "$bytes bytes" else "${roundTo1dp(value)} ${units[unit]}"
}

private fun roundTo1dp(value: Double): String {
    val scaled = kotlin.math.round(value * 10).toLong()
    return "${scaled / 10}.${scaled % 10}"
}

internal fun credentialLabel(check: CredentialCheck): String = when (check) {
    is CredentialCheck.Verified -> "Verified"
    CredentialCheck.Checking -> "Checking"
    CredentialCheck.Invalid -> "Not valid"
    CredentialCheck.NotPresent -> "Not present"
    is CredentialCheck.Error -> "Couldn't check"
    CredentialCheck.NotChecked -> "Not checked"
}

internal fun credentialExplanation(check: CredentialCheck): String = when (check) {
    is CredentialCheck.Verified -> if (check.trusted) {
        "The signature matches this file, so the photo has not been altered since capture."
    } else {
        // Precise wording matters here. The tamper-evidence is real and holds; what is
        // missing is an answer to "who signed this", because a development certificate
        // chains to no recognised authority.
        "The signature matches this file, so the photo has not been altered since capture. " +
            "The signing certificate is not on a recognised trust list, so this confirms " +
            "the image is intact but not who created it."
    }
    CredentialCheck.Checking -> "Re-downloading the stored file and checking its signature."
    CredentialCheck.Invalid ->
        "The stored file does not match its signature. Treat this photo as unverified."
    CredentialCheck.NotPresent -> "This file carries no Content Credentials."
    is CredentialCheck.Error -> check.message
    CredentialCheck.NotChecked -> "Not yet re-checked against the stored file."
}
