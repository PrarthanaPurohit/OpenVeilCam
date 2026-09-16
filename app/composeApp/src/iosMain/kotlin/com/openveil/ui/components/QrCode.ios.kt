package com.openveil.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/** QR rendering on iOS arrives with the rest of the iOS implementation; see the camera stub. */
actual fun encodeQr(text: String): QrMatrix? = null

@Composable
actual fun QrScannerView(onCode: (String) -> Unit, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Text("QR scanning is not yet implemented on iOS")
    }
}
