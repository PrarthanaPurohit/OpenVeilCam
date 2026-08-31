package com.openveil.ui

import androidx.compose.runtime.Composable
import com.openveil.domain.service.C2paService
import com.openveil.domain.service.FileStorage
import com.openveil.nostr.NostrIdentityRepository
import com.openveil.publish.PublishPhotoUseCase

/**
 * Everything the UI needs from the domain layer, assembled once per platform.
 *
 * Hand-wired rather than assembled by a DI container: the object graph is a handful of
 * singletons, and an explicit constructor chain is easier to follow -- and to verify for
 * where the private key travels -- than annotations resolved at runtime.
 */
interface AppDependencies {
    val publishPhotoUseCase: PublishPhotoUseCase
    val identityRepository: NostrIdentityRepository
    val c2paService: C2paService
    val fileStorage: FileStorage

    /** Name recorded as the capture device in the C2PA manifest. */
    val deviceName: String
}

/** Builds and remembers the platform dependency graph for the composition's lifetime. */
@Composable
expect fun rememberAppDependencies(): AppDependencies
