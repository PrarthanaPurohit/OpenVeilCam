package com.openveil.ui.camera

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.openveil.ui.screens.CameraPermissionState

private class AndroidCameraPermissionController(
    private val context: Context,
) : CameraPermissionController {

    /** Backing state, distinct from the [state] accessor so the two cannot alias. */
    var current by mutableStateOf(context.currentCameraPermission())

    /** Set by the composable that owns the permission launcher. */
    var requestPermission: () -> Unit = {}

    override val state: CameraPermissionState get() = current

    override fun request() = requestPermission()

    override fun openSettings() {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun onResult(granted: Boolean) {
        current = when {
            granted -> CameraPermissionState.Granted
            // Once the system stops offering a rationale, the user has chosen "don't ask
            // again" and only Settings can change the answer.
            context.findActivity()
                ?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) == false ->
                CameraPermissionState.PermanentlyDenied
            else -> CameraPermissionState.Denied
        }
    }
}

@Composable
actual fun rememberCameraPermission(): CameraPermissionController {
    val context = LocalContext.current
    val controller = remember(context) { AndroidCameraPermissionController(context) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
        onResult = controller::onResult,
    )
    controller.requestPermission = { launcher.launch(Manifest.permission.CAMERA) }

    // Ask on first display rather than making the user press a button just to reach the
    // system dialog they would see anyway.
    LaunchedEffect(Unit) {
        if (controller.current != CameraPermissionState.Granted) {
            launcher.launch(Manifest.permission.CAMERA)
        }
    }

    return controller
}

private fun Context.currentCameraPermission(): CameraPermissionState =
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED
    ) {
        CameraPermissionState.Granted
    } else {
        CameraPermissionState.Denied
    }

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
