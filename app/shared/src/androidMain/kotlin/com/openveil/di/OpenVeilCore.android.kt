package com.openveil.di

import android.content.Context
import com.openveil.blossom.KtorBlossomClient
import com.openveil.c2pa.AndroidC2paService
import com.openveil.c2pa.DevCertSigningIdentity
import com.openveil.net.createHttpClient
import com.openveil.nostr.KtorNostrClient
import com.openveil.nostr.NostrIdentityRepository
import com.openveil.publish.PublishPhotoUseCase
import com.openveil.storage.AndroidFileStorage
import com.openveil.storage.AndroidSecureStorage
import kotlinx.coroutines.runBlocking

/**
 * Builds the Android object graph.
 *
 * Explicit constructor wiring rather than a DI container: the graph is small, and being
 * able to read in one screenful exactly which objects touch the private key is worth more
 * here than the indirection a container would add.
 */
fun createOpenVeilCore(
    context: Context,
    deviceName: String,
    appVersion: String,
): OpenVeilCore {
    val appContext = context.applicationContext
    val fileStorage = AndroidFileStorage(appContext)
    val secureStorage = AndroidSecureStorage(appContext)
    val identityRepository = NostrIdentityRepository(secureStorage)
    val httpClient = createHttpClient()

    val c2paService = AndroidC2paService(
        signingIdentity = DevCertSigningIdentity(),
        appVersion = appVersion,
    )

    val blossomClient = KtorBlossomClient(
        httpClient = httpClient,
        // Blossom mints an auth token per request and needs the key synchronously. The
        // repository caches after the first read, so this blocks only once, on a single
        // Keystore unwrap.
        identity = { runBlocking { identityRepository.getOrCreate() } },
    )

    return OpenVeilCore(
        publishPhotoUseCase = PublishPhotoUseCase(
            c2pa = c2paService,
            blossom = blossomClient,
            nostr = KtorNostrClient(httpClient),
            identityRepository = identityRepository,
            fileStorage = fileStorage,
            deviceName = deviceName,
        ),
        identityRepository = identityRepository,
        c2paService = c2paService,
        fileStorage = fileStorage,
    )
}
