package com.openveil.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.openveil.ui.capture.LinkState
import com.openveil.ui.components.CameraControlButton
import com.openveil.ui.components.GlassCard
import com.openveil.ui.components.MaterialSymbol
import com.openveil.ui.components.OpenVeilButton
import com.openveil.ui.components.OpenVeilIcon
import com.openveil.ui.components.OpenVeilSecondaryButton
import com.openveil.ui.components.QrCode
import com.openveil.ui.components.QrScannerView
import com.openveil.ui.components.SectionLabel
import com.openveil.ui.theme.OpenVeilColors
import com.openveil.ui.theme.OpenVeilShapes
import com.openveil.ui.theme.OpenVeilTheme
import com.openveil.ui.theme.Sizes
import com.openveil.ui.theme.Spacing

/** Everything the link screen renders from. */
data class LinkAccountUiState(
    val linkState: LinkState,
    /** A NIP-55 signer app is installed on this phone. */
    val signerAppAvailable: Boolean,
    /** Non-null while a `nostrconnect://` QR is on screen waiting for a signer. */
    val pairingUri: String?,
    val cameraPermission: CameraPermissionState,
)

/**
 * Pairs the app with the user's own signer, three ways:
 *  - a signer app on this phone (NIP-55, Amber) -- the smoothest, when it exists;
 *  - a `bunker://` link, pasted or scanned;
 *  - a `nostrconnect://` QR code we display for a signer on another device to scan.
 *
 * There is deliberately no field for an nsec. A pasted private key would sit in this
 * app's storage, and for the people this app is built for, a seized phone must not hand
 * over their whole Nostr identity along with the photos. Every path here grants the right
 * to ask for signatures and nothing more, and can be revoked from the signer's side.
 */
@Composable
fun LinkAccountScreen(
    state: LinkAccountUiState,
    onUseSignerApp: () -> Unit,
    onConnectBunker: (String) -> Unit,
    onShowPairingQr: () -> Unit,
    onCancelPairing: () -> Unit,
    onRequestCameraPermission: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var link by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    var scanError by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current
    val connecting = state.linkState == LinkState.Connecting

    if (scanning) {
        ScanBunkerQr(
            permission = state.cameraPermission,
            onRequestPermission = onRequestCameraPermission,
            onCode = { code ->
                if (code.trim().startsWith("bunker://", ignoreCase = true)) {
                    scanning = false
                    scanError = null
                    link = code.trim()
                    onConnectBunker(link)
                } else {
                    scanError = "That QR code is not a bunker link"
                }
            },
            error = scanError,
            onClose = { scanning = false; scanError = null },
            modifier = modifier,
        )
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(OpenVeilColors.Background)
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.containerMargin),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CameraControlButton(OpenVeilIcon.ArrowBack, "Go back", onBack)
            Spacer(Modifier.width(Spacing.md))
            Text(
                "Link your Nostr account",
                style = OpenVeilTheme.type.headlineMd,
                color = OpenVeilColors.OnSurface,
            )
        }

        GlassCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MaterialSymbol(
                    OpenVeilIcon.Key,
                    contentDescription = null,
                    tint = OpenVeilColors.Primary,
                    filled = true,
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    "Your key stays in your signer",
                    style = OpenVeilTheme.type.headlineSm,
                    color = OpenVeilColors.OnSurface,
                )
            }
            Spacer(Modifier.height(Spacing.sm))
            Text(
                "OpenVeil asks a signer -- Amber, nsec.app, nsecBunker or any NIP-46 " +
                    "bunker -- to sign each publication. Your private key is never typed " +
                    "into, stored by, or seen by this app.",
                style = OpenVeilTheme.type.bodyMd,
                color = OpenVeilColors.OnSurfaceVariant,
            )
        }

        if (state.pairingUri != null) {
            PairingQrCard(uri = state.pairingUri, onCancel = onCancelPairing)
        } else {
            if (state.signerAppAvailable) {
                Column {
                    SectionLabel("On this phone")
                    Spacer(Modifier.height(Spacing.sm))
                    MethodRow(
                        icon = OpenVeilIcon.ShieldLock,
                        title = "Use your signer app",
                        subtitle = "Amber is installed. It will ask you which account to use.",
                        enabled = !connecting,
                        onClick = onUseSignerApp,
                    )
                }
            }

            Column {
                SectionLabel("From a bunker link")
                Spacer(Modifier.height(Spacing.sm))
                BunkerLinkField(
                    value = link,
                    onValueChange = { link = it },
                    enabled = !connecting,
                    onDone = { if (link.isNotBlank()) onConnectBunker(link) },
                )
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    "Starts with bunker:// -- copy it from your signer, or scan its QR code.",
                    style = OpenVeilTheme.type.bodySm,
                    color = OpenVeilColors.Outline,
                )
                Spacer(Modifier.height(Spacing.md))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Box(Modifier.weight(1f)) {
                        OpenVeilSecondaryButton(
                            text = "Paste",
                            onClick = { clipboard.getText()?.text?.let { link = it.trim() } },
                            icon = OpenVeilIcon.ContentCopy,
                            enabled = !connecting,
                        )
                    }
                    Box(Modifier.weight(1f)) {
                        OpenVeilSecondaryButton(
                            text = "Scan QR",
                            onClick = { scanning = true },
                            icon = OpenVeilIcon.PhotoCamera,
                            enabled = !connecting,
                        )
                    }
                }
                Spacer(Modifier.height(Spacing.sm))
                OpenVeilButton(
                    text = if (connecting) "Connecting..." else "Connect",
                    onClick = { onConnectBunker(link) },
                    icon = OpenVeilIcon.Link,
                    enabled = !connecting && link.isNotBlank(),
                )
            }

            Column {
                SectionLabel("From another device")
                Spacer(Modifier.height(Spacing.sm))
                MethodRow(
                    icon = OpenVeilIcon.Image,
                    title = "Show a QR code to scan",
                    subtitle = "For a signer on your computer or another phone.",
                    enabled = !connecting,
                    onClick = onShowPairingQr,
                )
            }
        }

        val failure = state.linkState as? LinkState.Failed
        if (failure != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(OpenVeilShapes.medium)
                    .background(OpenVeilColors.Error.copy(alpha = 0.10f))
                    .padding(Spacing.md),
                verticalAlignment = Alignment.Top,
            ) {
                MaterialSymbol(
                    OpenVeilIcon.Error,
                    contentDescription = null,
                    size = 20.dp,
                    tint = OpenVeilColors.Error,
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(failure.message, style = OpenVeilTheme.type.bodySm, color = OpenVeilColors.OnSurface)
            }
        }

        if (connecting && state.pairingUri == null) {
            Text(
                "Waiting for your signer to approve the connection. Open it if it does not " +
                    "prompt you on its own.",
                style = OpenVeilTheme.type.bodySm,
                color = OpenVeilColors.OnSurfaceVariant,
            )
        }

        GlassCard {
            SectionLabel("Before you link")
            Spacer(Modifier.height(Spacing.sm))
            Text(
                "Publishing under your own account ties each photo you choose to publish " +
                    "that way to one public identity. The device key stays the default; you " +
                    "pick which to use on every photo, and you can unlink at any time.",
                style = OpenVeilTheme.type.bodySm,
                color = OpenVeilColors.OnSurfaceVariant,
            )
        }

        Spacer(Modifier.height(Spacing.lg))
    }
}

/** The `nostrconnect://` code, large, with what to do with it. */
@Composable
private fun PairingQrCard(uri: String, onCancel: () -> Unit) {
    GlassCard {
        SectionLabel("Scan with your signer")
        Spacer(Modifier.height(Spacing.md))
        QrCode(
            text = uri,
            modifier = Modifier.fillMaxWidth(),
            contentDescription = "Pairing QR code for your signer to scan",
        )
        Spacer(Modifier.height(Spacing.md))
        Text(
            "Open Amber, nsec.app or your bunker on the other device and scan this code. " +
                "This screen updates on its own once it connects.",
            style = OpenVeilTheme.type.bodySm,
            color = OpenVeilColors.OnSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Spacing.md))
        OpenVeilSecondaryButton(text = "Cancel", onClick = onCancel, icon = OpenVeilIcon.Close)
    }
}

/** Full-screen camera for reading a bunker:// QR. */
@Composable
private fun ScanBunkerQr(
    permission: CameraPermissionState,
    onRequestPermission: () -> Unit,
    onCode: (String) -> Unit,
    error: String?,
    onClose: () -> Unit,
    modifier: Modifier,
) {
    Box(modifier.fillMaxSize().background(Color.Black)) {
        when (permission) {
            CameraPermissionState.Granted -> QrScannerView(onCode = onCode, modifier = Modifier.fillMaxSize())
            else -> Column(
                modifier = Modifier.fillMaxSize().padding(Spacing.containerMargin),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "Camera access is needed to scan the code",
                    style = OpenVeilTheme.type.bodyMd,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(Spacing.md))
                OpenVeilButton(text = "Allow camera", onClick = onRequestPermission)
            }
        }

        Box(Modifier.align(Alignment.TopStart).systemBarsPadding().padding(Spacing.sm)) {
            CameraControlButton(OpenVeilIcon.Close, "Stop scanning", onClose)
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.6f))
                .systemBarsPadding()
                .padding(Spacing.containerMargin),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                error ?: "Point the camera at the bunker QR code shown by your signer",
                style = OpenVeilTheme.type.bodySm,
                color = if (error != null) OpenVeilColors.Error else Color.White,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun MethodRow(
    icon: OpenVeilIcon,
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(OpenVeilShapes.medium)
            .background(OpenVeilColors.SurfaceContainerLow)
            .border(1.dp, OpenVeilColors.OutlineVariant.copy(alpha = 0.6f), OpenVeilShapes.medium)
            .clickable(enabled = enabled, onClickLabel = title, onClick = onClick)
            .defaultMinSize(minHeight = Sizes.minTouchTarget)
            .padding(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MaterialSymbol(icon, contentDescription = null, size = 22.dp, tint = OpenVeilColors.Primary)
        Spacer(Modifier.width(Spacing.md))
        Column(Modifier.weight(1f)) {
            Text(title, style = OpenVeilTheme.type.bodyMd, color = OpenVeilColors.OnSurface)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = OpenVeilTheme.type.bodySm, color = OpenVeilColors.OnSurfaceVariant)
        }
        MaterialSymbol(OpenVeilIcon.ArrowForward, contentDescription = null, size = 18.dp, tint = OpenVeilColors.Primary)
    }
}

/** Single-line, no autocorrect: a bunker link is a machine string, and autocorrect eats it. */
@Composable
private fun BunkerLinkField(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    onDone: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val borderColor = if (focused) {
        OpenVeilColors.Primary.copy(alpha = 0.7f)
    } else {
        OpenVeilColors.OutlineVariant.copy(alpha = 0.6f)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(OpenVeilShapes.medium)
            .background(OpenVeilColors.SurfaceContainerLow)
            .border(1.dp, borderColor, OpenVeilShapes.medium)
            .padding(Spacing.md),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) {
            Text(
                "bunker://...",
                style = OpenVeilTheme.type.metadata,
                color = OpenVeilColors.Outline,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
            textStyle = OpenVeilTheme.type.metadata.copy(color = OpenVeilColors.OnSurface),
            cursorBrush = SolidColor(OpenVeilColors.Primary),
            interactionSource = interactionSource,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Go,
            ),
            keyboardActions = KeyboardActions(onGo = { onDone() }),
            maxLines = 3,
        )
    }
}
