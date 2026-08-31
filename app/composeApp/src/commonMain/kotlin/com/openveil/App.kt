package com.openveil

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.openveil.domain.model.AppResult
import com.openveil.domain.model.PublishStatus
import com.openveil.domain.service.CameraFlash
import com.openveil.nostr.nostrEventLink
import com.openveil.ui.camera.CameraViewfinder
import com.openveil.ui.camera.rememberCameraController
import com.openveil.ui.camera.rememberCameraPermission
import com.openveil.ui.capture.rememberCaptureCoordinator
import com.openveil.ui.components.rememberShareHandler
import com.openveil.ui.components.rememberUrlOpener
import com.openveil.ui.navigation.PlatformBackHandler
import com.openveil.ui.navigation.Screen
import com.openveil.ui.navigation.rememberNavigator
import com.openveil.ui.rememberAppDependencies
import com.openveil.ui.screens.CameraScreen
import com.openveil.ui.screens.CredentialCheck
import com.openveil.ui.screens.FlashMode
import com.openveil.ui.screens.HomeScreen
import com.openveil.ui.screens.PhotoDetailsScreen
import com.openveil.ui.screens.PublishingScreen
import com.openveil.ui.screens.PublishingUiState
import com.openveil.ui.screens.ReviewScreen
import com.openveil.ui.screens.ReviewUiState
import com.openveil.ui.screens.SuccessScreen
import com.openveil.ui.screens.SuccessUiState
import com.openveil.ui.theme.OpenVeilColors
import com.openveil.ui.theme.OpenVeilTheme
import kotlinx.coroutines.launch

/**
 * Application root: theme, navigation, and the wiring between screens and the pipeline.
 *
 * Holds no business logic. Screens receive plain state and callbacks, so each one can be
 * rendered from a preview or a test without a camera, a relay, or a signing key.
 */
@Composable
fun App() {
    OpenVeilTheme {
        val dependencies = rememberAppDependencies()
        val navigator = rememberNavigator()
        val coordinator = rememberCaptureCoordinator(dependencies)
        val share = rememberShareHandler()
        val openUrl = rememberUrlOpener()
        val scope = rememberCoroutineScope()

        LaunchedEffect(Unit) { coordinator.refreshIdentity() }

        PlatformBackHandler(enabled = navigator.canGoBack) { navigator.back() }

        val job = coordinator.job
        val status = job?.photo?.status

        // Publishing is a one-way transition: once a relay accepts, there is nothing to
        // go "back" to, so the success screen replaces the progress screen.
        LaunchedEffect(status) {
            if (status == PublishStatus.PUBLISHED && navigator.current is Screen.Publishing) {
                navigator.replaceWith(Screen.Success(job.photo.id))
            }
        }

        Box(Modifier.fillMaxSize().background(OpenVeilColors.Background)) {
            when (val screen = navigator.current) {
                Screen.Home -> HomeScreen(
                    identity = coordinator.identity,
                    onOpenCamera = { navigator.navigateTo(Screen.Camera) },
                )

                Screen.Camera -> CameraRoute(
                    onCaptured = { navigator.replaceWith(Screen.Review(it)) },
                    onClose = { navigator.back() },
                    coordinator = coordinator,
                )

                is Screen.Review -> {
                    val current = job
                    if (current == null) {
                        LaunchedEffect(Unit) { navigator.popToHome() }
                    } else {
                        ReviewScreen(
                            state = ReviewUiState(
                                imageBytes = current.captured.bytes,
                                signingComplete = current.signed != null,
                                signingFailed = current.photo.status == PublishStatus.FAILED,
                                exif = current.captureFacts(),
                                caption = current.photo.caption.orEmpty(),
                            ),
                            onRetake = {
                                coordinator.discard()
                                navigator.replaceWith(Screen.Camera)
                            },
                            onPublish = {
                                coordinator.publish()
                                navigator.replaceWith(Screen.Publishing(current.photo.id))
                            },
                            onClose = {
                                coordinator.discard()
                                navigator.popToHome()
                            },
                            onCaptionChange = coordinator::setCaption,
                        )
                    }
                }

                is Screen.Publishing -> {
                    val current = job
                    if (current == null) {
                        LaunchedEffect(Unit) { navigator.popToHome() }
                    } else {
                        PublishingScreen(
                            state = PublishingUiState(
                                imageBytes = current.signed?.bytes ?: current.captured.bytes,
                                status = current.photo.status,
                                error = current.photo.error,
                            ),
                            onRetry = coordinator::retry,
                            onSaveForLater = { navigator.popToHome() },
                        )
                    }
                }

                is Screen.Success -> {
                    val current = job
                    if (current == null) {
                        LaunchedEffect(Unit) { navigator.popToHome() }
                    } else {
                        SuccessScreen(
                            state = SuccessUiState(
                                imageBytes = current.signed?.bytes ?: current.captured.bytes,
                                format = current.photo.mimeType.substringAfter('/').uppercase(),
                                megapixels = current.photo.megapixels(),
                                relayCount = current.photo.acceptedRelays.size,
                            ),
                            onViewDetails = {
                                navigator.navigateTo(Screen.PhotoDetails(current.photo.id))
                            },
                            onShare = { share(current.photo.shareText()) },
                            onDone = {
                                coordinator.discard()
                                navigator.popToHome()
                            },
                        )
                    }
                }

                is Screen.PhotoDetails -> {
                    val current = job
                    if (current == null) {
                        LaunchedEffect(Unit) { navigator.popToHome() }
                    } else {
                        var check by remember { mutableStateOf<CredentialCheck>(CredentialCheck.Checking) }

                        // Re-verify against the bytes actually held, rather than trusting
                        // that signing succeeded earlier.
                        LaunchedEffect(current.photo.id) {
                            val bytes = current.signed?.bytes
                            check = if (bytes == null) {
                                CredentialCheck.NotPresent
                            } else {
                                when (val result = dependencies.c2paService.verify(bytes)) {
                                    is com.openveil.domain.service.C2paVerification.Valid ->
                                        CredentialCheck.Verified(result.trusted)
                                    is com.openveil.domain.service.C2paVerification.Invalid ->
                                        CredentialCheck.Invalid
                                    com.openveil.domain.service.C2paVerification.NotPresent ->
                                        CredentialCheck.NotPresent
                                    is com.openveil.domain.service.C2paVerification.Error ->
                                        CredentialCheck.Error(result.reason)
                                }
                            }
                        }

                        PhotoDetailsScreen(
                            photo = current.photo,
                            imageBytes = current.signed?.bytes ?: current.captured.bytes,
                            capturedAtLabel = current.captured.capturedAt.toString(),
                            credentialCheck = check,
                            onBack = { navigator.back() },
                            onShare = { share(current.photo.shareText()) },
                            onOpenUrl = openUrl,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraRoute(
    onCaptured: (String) -> Unit,
    onClose: () -> Unit,
    coordinator: com.openveil.ui.capture.CaptureCoordinator,
) {
    val permission = rememberCameraPermission()
    val controller = rememberCameraController()
    val scope = rememberCoroutineScope()
    var flashMode by remember { mutableStateOf(FlashMode.Off) }
    var capturing by remember { mutableStateOf(false) }

    CameraScreen(
        permission = permission.state,
        flashMode = flashMode,
        isCapturing = capturing,
        onCapture = {
            if (!capturing) {
                capturing = true
                scope.launch {
                    when (val result = controller.capture()) {
                        is AppResult.Success -> {
                            coordinator.onCaptured(result.value)
                            capturing = false
                            onCaptured(result.value.capturedAt.toEpochMilliseconds().toString())
                        }
                        is AppResult.Failure -> capturing = false
                    }
                }
            }
        },
        onClose = onClose,
        onToggleFlash = {
            flashMode = flashMode.next()
            controller.setFlash(flashMode.toDomain())
        },
        onSwitchLens = { },
        onRequestPermission = permission::request,
        onOpenSettings = permission::openSettings,
    ) {
        CameraViewfinder(controller, Modifier.fillMaxSize())
    }
}

private fun FlashMode.toDomain(): CameraFlash = when (this) {
    FlashMode.Off -> CameraFlash.OFF
    FlashMode.On -> CameraFlash.ON
    FlashMode.Auto -> CameraFlash.AUTO
}

/** Only facts the capture actually reported -- no placeholder lens or ISO, and no GPS. */
private fun com.openveil.publish.PublishJob.captureFacts(): Map<String, String> = buildMap {
    put("Resolution", "${captured.width} x ${captured.height}")
    put("Format", captured.mimeType.substringAfter('/').uppercase())
    photo.captureDevice?.let { put("Device", it) }
    put("Size", "${captured.bytes.size / 1024} KB")
}

private fun com.openveil.domain.model.Photo.megapixels(): String {
    val mp = (width.toLong() * height.toLong()) / 1_000_000.0
    val rounded = kotlin.math.round(mp * 10).toLong()
    return "${rounded / 10}.${rounded % 10} MP"
}

/** Shares the public, independently checkable references -- never a local path. */
private fun com.openveil.domain.model.Photo.shareText(): String = buildString {
    append("Verifiable photo captured with OpenVeil")
    blossomUrl?.let { append("\n\n").append(it) }
    nostrEventId?.let { append("\n\nNostr event: ").append(it) }
}
