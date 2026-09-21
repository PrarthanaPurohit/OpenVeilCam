package com.openveil.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.openveil.di.createOpenVeilCore
import com.openveil.domain.service.C2paService
import com.openveil.domain.service.FileStorage
import com.openveil.nostr.NostrIdentityRepository
import com.openveil.nostr.nip46.LinkedAccountRepository
import com.openveil.publish.PublishPhotoUseCase
import com.openveil.ui.camera.currentDeviceName

private const val APP_VERSION = "0.1.0-beta.1"

@Composable
actual fun rememberAppDependencies(): AppDependencies {
    val context = LocalContext.current.applicationContext
    val signerLauncher = rememberSignerIntentLauncher()
    return remember(context) {
        val device = currentDeviceName()
        val core = createOpenVeilCore(context, device, APP_VERSION, intentLauncher = signerLauncher)

        object : AppDependencies {
            override val publishPhotoUseCase: PublishPhotoUseCase = core.publishPhotoUseCase
            override val identityRepository: NostrIdentityRepository = core.identityRepository
            override val linkedAccountRepository: LinkedAccountRepository = core.linkedAccountRepository
            override val c2paService: C2paService = core.c2paService
            override val fileStorage: FileStorage = core.fileStorage
            override val deviceName: String = device
        }
    }
}
