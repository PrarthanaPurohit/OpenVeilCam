package com.openveil.crypto

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.refTo
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH

@OptIn(ExperimentalForeignApi::class)
actual fun sha256(bytes: ByteArray): ByteArray {
    val digest = ByteArray(CC_SHA256_DIGEST_LENGTH)
    if (bytes.isEmpty()) {
        // addressOf(0) on an empty array is undefined; hash the empty input explicitly.
        CC_SHA256(null, 0u, digest.refTo(0))
        return digest
    }
    bytes.usePinned { input ->
        digest.usePinned { output ->
            CC_SHA256(input.addressOf(0), bytes.size.toUInt(), output.addressOf(0))
        }
    }
    return digest
}
