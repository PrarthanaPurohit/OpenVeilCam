package com.openveil.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.openveil.ui.components.CameraControlButton
import com.openveil.ui.components.MaterialSymbol
import com.openveil.ui.components.OpenVeilButton
import com.openveil.ui.components.OpenVeilIcon
import com.openveil.ui.components.ShutterButton
import com.openveil.ui.theme.OpenVeilColors
import com.openveil.ui.theme.OpenVeilTheme
import com.openveil.ui.theme.Spacing

/**
 * Flash setting as the camera screen presents it, carrying its own icon and accessible
 * label so the control has no separate lookup table to fall out of step with.
 */
enum class FlashMode(val icon: OpenVeilIcon, val label: String) {
    Off(OpenVeilIcon.FlashOff, "Flash off"),
    On(OpenVeilIcon.FlashOn, "Flash on"),
    Auto(OpenVeilIcon.FlashAuto, "Flash automatic"),
    ;

    fun next(): FlashMode = when (this) {
        Off -> On
        On -> Auto
        Auto -> Off
    }
}

/**
 * Camera permission, in the states the UI has to tell apart.
 *
 * [Denied] and [PermanentlyDenied] are separate because they need different affordances:
 * one can be re-requested in place, the other only in system settings, and offering a
 * button that silently does nothing is worse than offering none.
 */
sealed interface CameraPermissionState {
    data object Granted : CameraPermissionState

    /** Denied but re-requestable -- show a rationale and a retry. */
    data object Denied : CameraPermissionState

    /** Denied permanently; only the system settings screen can change it now. */
    data object PermanentlyDenied : CameraPermissionState
}

/**
 * Full-bleed camera screen.
 *
 * The viewfinder is passed in as a slot so this file stays free of CameraX/AVFoundation:
 * the platform supplies the preview surface, and capture is invoked through
 * [onCapture] which routes to CameraService, never to a platform API from a composable.
 *
 * Compared with the reference design this drops the VIDEO/PRO mode selector, the gallery
 * thumbnail, the filters button, and the corner framing brackets. The MVP is photo-only
 * with no gallery and no filters, so those controls led nowhere -- and the brackets only
 * added furniture over a viewfinder whose whole job is to show the scene.
 */
@Composable
fun CameraScreen(
    permission: CameraPermissionState,
    flashMode: FlashMode,
    isCapturing: Boolean,
    onCapture: () -> Unit,
    onClose: () -> Unit,
    onToggleFlash: () -> Unit,
    onSwitchLens: () -> Unit,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewfinder: @Composable BoxScope.() -> Unit,
) {
    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        if (permission is CameraPermissionState.Granted) {
            viewfinder()
            // Keeps white controls legible against a bright scene.
            Box(Modifier.fillMaxSize().background(OpenVeilColors.PreviewScrim))
        } else {
            CameraPermissionPrompt(
                permission = permission,
                onRequestPermission = onRequestPermission,
                onOpenSettings = onOpenSettings,
                modifier = Modifier.fillMaxSize(),
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = Spacing.containerMargin, vertical = Spacing.sm),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            CameraControlButton(OpenVeilIcon.Close, "Close camera", onClose)

            if (permission is CameraPermissionState.Granted) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    CameraControlButton(flashMode.icon, flashMode.label, onToggleFlash)
                    CameraControlButton(OpenVeilIcon.FlipCameraIos, "Switch camera", onSwitchLens)
                }
            }
        }

        if (permission is CameraPermissionState.Granted) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(180.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))
                        )
                    ),
            ) {
                ShutterButton(
                    onClick = onCapture,
                    enabled = !isCapturing,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = Spacing.lg),
                )
            }
        }
    }
}

@Composable
private fun CameraPermissionPrompt(
    permission: CameraPermissionState,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val permanent = permission is CameraPermissionState.PermanentlyDenied
    Column(
        modifier = modifier.padding(Spacing.xl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MaterialSymbol(
            OpenVeilIcon.PhotoCamera,
            contentDescription = null,
            size = 48.dp,
            tint = OpenVeilColors.OnSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.lg))
        Text(
            "Camera access needed",
            style = OpenVeilTheme.type.headlineSm,
            color = OpenVeilColors.OnSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.sm))
        Text(
            if (permanent) {
                "OpenVeil needs the camera to capture photos. Enable it in system settings to continue."
            } else {
                "OpenVeil signs photos at the moment they are taken, so it needs access to your camera."
            },
            style = OpenVeilTheme.type.bodyMd,
            color = OpenVeilColors.OnSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.lg))
        OpenVeilButton(
            text = if (permanent) "Open settings" else "Allow camera",
            onClick = if (permanent) onOpenSettings else onRequestPermission,
        )
    }
}
