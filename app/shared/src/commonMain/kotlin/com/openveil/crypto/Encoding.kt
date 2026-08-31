package com.openveil.crypto

private const val HEX_DIGITS = "0123456789abcdef"

/** Lowercase hex. Nostr ids, pubkeys, signatures and the NIP-94 hash tags all use this. */
fun ByteArray.toHex(): String {
    val out = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xFF
        out.append(HEX_DIGITS[v ushr 4]).append(HEX_DIGITS[v and 0x0F])
    }
    return out.toString()
}

/** Parses lowercase or uppercase hex. Throws on odd length or non-hex characters. */
fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "hex string must have an even length, was $length" }
    val out = ByteArray(length / 2)
    for (i in out.indices) {
        val hi = hexDigit(this[i * 2])
        val lo = hexDigit(this[i * 2 + 1])
        out[i] = ((hi shl 4) or lo).toByte()
    }
    return out
}

private fun hexDigit(c: Char): Int = when (c) {
    in '0'..'9' -> c - '0'
    in 'a'..'f' -> c - 'a' + 10
    in 'A'..'F' -> c - 'A' + 10
    else -> throw IllegalArgumentException("not a hex digit: $c")
}

private const val B64URL_ALPHABET =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

/**
 * Base64url **without** padding, as required for the Blossom `Authorization: Nostr <token>`
 * header (BUD-11 specifies the JWT-style unpadded alphabet).
 *
 * Emitting standard base64, or leaving `=` padding on, produces an opaque 401 from the
 * server rather than a useful error, so this is deliberately not the stdlib encoder.
 */
fun ByteArray.toBase64UrlNoPad(): String {
    if (isEmpty()) return ""
    val out = StringBuilder((size + 2) / 3 * 4)
    var i = 0
    while (i + 2 < size) {
        val n = ((this[i].toInt() and 0xFF) shl 16) or
            ((this[i + 1].toInt() and 0xFF) shl 8) or
            (this[i + 2].toInt() and 0xFF)
        out.append(B64URL_ALPHABET[(n ushr 18) and 0x3F])
        out.append(B64URL_ALPHABET[(n ushr 12) and 0x3F])
        out.append(B64URL_ALPHABET[(n ushr 6) and 0x3F])
        out.append(B64URL_ALPHABET[n and 0x3F])
        i += 3
    }
    when (size - i) {
        1 -> {
            val n = (this[i].toInt() and 0xFF) shl 16
            out.append(B64URL_ALPHABET[(n ushr 18) and 0x3F])
            out.append(B64URL_ALPHABET[(n ushr 12) and 0x3F])
        }
        2 -> {
            val n = ((this[i].toInt() and 0xFF) shl 16) or ((this[i + 1].toInt() and 0xFF) shl 8)
            out.append(B64URL_ALPHABET[(n ushr 18) and 0x3F])
            out.append(B64URL_ALPHABET[(n ushr 12) and 0x3F])
            out.append(B64URL_ALPHABET[(n ushr 6) and 0x3F])
        }
    }
    return out.toString()
}
