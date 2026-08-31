package com.openveil.net

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets
import kotlin.time.Duration.Companion.seconds

/**
 * The single HTTP client for Blossom uploads and relay sockets.
 *
 * No engine is named here: Ktor resolves one from whatever engine artifact is on the
 * platform's classpath (OkHttp on Android, Darwin on iOS), which keeps this file free of
 * expect/actual for no benefit.
 *
 * Logging is deliberately absent. Blossom requests carry a signed `Authorization: Nostr`
 * token, so a request logger would write credentials to logcat.
 */
fun createHttpClient(): HttpClient = HttpClient {
    install(WebSockets)
    install(HttpTimeout) {
        // Uploads are multi-megabyte over mobile networks; the request timeout has to be
        // generous or a slow connection looks like a failure.
        requestTimeoutMillis = 120.seconds.inWholeMilliseconds
        connectTimeoutMillis = 20.seconds.inWholeMilliseconds
        socketTimeoutMillis = 60.seconds.inWholeMilliseconds
    }
    expectSuccess = false
}
