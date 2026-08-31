package com.openveil.di

import com.openveil.domain.service.C2paService
import com.openveil.domain.service.FileStorage
import com.openveil.nostr.NostrIdentityRepository
import com.openveil.publish.PublishPhotoUseCase

/**
 * The assembled domain layer, handed to the UI as a finished object.
 *
 * The UI module gets this and nothing else. Ktor, the C2PA JNI classes, the Keystore and
 * the secp256k1 bindings all stay behind it -- keeping the HTTP client off the UI's
 * compile classpath is what makes "no networking types in the UI layer" a fact the
 * compiler enforces rather than a convention.
 */
class OpenVeilCore(
    val publishPhotoUseCase: PublishPhotoUseCase,
    val identityRepository: NostrIdentityRepository,
    val c2paService: C2paService,
    /** Exposed so an abandoned capture's signed master can be deleted on discard. */
    val fileStorage: FileStorage,
)
