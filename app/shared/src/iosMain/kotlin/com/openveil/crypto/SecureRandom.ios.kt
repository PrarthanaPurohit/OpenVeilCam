package com.openveil.crypto

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault

@OptIn(ExperimentalForeignApi::class)
actual fun secureRandomBytes(size: Int): ByteArray {
    val out = ByteArray(size)
    out.usePinned { pinned ->
        val status = SecRandomCopyBytes(kSecRandomDefault, size.toULong(), pinned.addressOf(0))
        check(status == 0) { "SecRandomCopyBytes failed with status $status" }
    }
    return out
}
