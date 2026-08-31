package com.openveil.ui

import androidx.compose.runtime.Composable

/**
 * iOS wiring lands with the AVFoundation camera and the c2pa-swift bridge. The domain
 * layer above this point is already platform-neutral, so only these bindings are missing.
 */
@Composable
actual fun rememberAppDependencies(): AppDependencies =
    TODO("iOS dependency graph is not implemented in this build")
