package com.openveil.crypto

import java.security.SecureRandom

private val secureRandom = SecureRandom()

actual fun secureRandomBytes(size: Int): ByteArray =
    ByteArray(size).also { secureRandom.nextBytes(it) }
