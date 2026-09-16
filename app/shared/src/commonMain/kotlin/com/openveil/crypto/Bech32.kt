package com.openveil.crypto

/**
 * Bech32 (BIP-173), used by NIP-19 to render a Nostr public key as `npub1...`.
 *
 * Encode is for the device's own npub. Decode exists for one reason: an Android signer app
 * (NIP-55) answers `get_public_key` with an npub, and we need the hex behind it.
 */
object Bech32 {
    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    // 25 bits, per BIP-173. A 26-bit mask here still encodes correctly -- the stray high
    // bit lands outside the six checksum groups -- but it breaks the `polymod == 1`
    // identity that decode relies on. Keep it exact.
    private const val GENERATOR_MASK = 0x1FFFFFF

    private val GENERATORS = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)

    private fun polymod(values: IntArray): Int {
        var chk = 1
        for (v in values) {
            val top = chk ushr 25
            chk = ((chk and GENERATOR_MASK) shl 5) xor v
            for (i in 0..4) {
                if (((top ushr i) and 1) != 0) chk = chk xor GENERATORS[i]
            }
        }
        return chk
    }

    private fun hrpExpand(hrp: String): IntArray {
        val out = IntArray(hrp.length * 2 + 1)
        hrp.forEachIndexed { i, c ->
            out[i] = c.code ushr 5
            out[i + hrp.length + 1] = c.code and 31
        }
        out[hrp.length] = 0
        return out
    }

    /** Regroups 8-bit bytes into the 5-bit groups bech32 encodes, padding the tail. */
    internal fun convertBits8to5(data: ByteArray): IntArray {
        var acc = 0
        var bits = 0
        val out = ArrayList<Int>(data.size * 8 / 5 + 1)
        for (b in data) {
            acc = (acc shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                out.add((acc ushr bits) and 31)
            }
        }
        if (bits > 0) out.add((acc shl (5 - bits)) and 31)
        return out.toIntArray()
    }

    /** 5-bit groups back to bytes. Rejects non-zero padding, as BIP-173 requires. */
    internal fun convertBits5to8(values: IntArray): ByteArray {
        var acc = 0
        var bits = 0
        val out = ArrayList<Byte>(values.size * 5 / 8)
        for (v in values) {
            acc = (acc shl 5) or v
            bits += 5
            while (bits >= 8) {
                bits -= 8
                out.add(((acc ushr bits) and 0xFF).toByte())
            }
        }
        require(bits < 5 && ((acc shl (8 - bits)) and 0xFF) == 0) { "invalid bech32 padding" }
        return out.toByteArray()
    }

    /**
     * Parses `hrp1data` and verifies the checksum. Throws [IllegalArgumentException] on
     * anything malformed; the message is safe to show.
     */
    fun decode(text: String): Pair<String, ByteArray> {
        require(text.length in 8..90) { "not a bech32 string" }
        require(text.none { it.code < 33 || it.code > 126 }) { "not a bech32 string" }
        val lower = text.lowercase()
        require(lower == text || text.uppercase() == text) { "bech32 must not mix case" }
        val separator = lower.lastIndexOf('1')
        require(separator in 1..(lower.length - 7)) { "not a bech32 string" }
        val hrp = lower.substring(0, separator)
        val values = IntArray(lower.length - separator - 1) {
            val index = CHARSET.indexOf(lower[separator + 1 + it])
            require(index >= 0) { "invalid bech32 character" }
            index
        }
        require(polymod(hrpExpand(hrp) + values) == 1) { "bech32 checksum does not match" }
        return hrp to convertBits5to8(values.copyOf(values.size - 6))
    }

    fun encode(hrp: String, data: ByteArray): String {
        val values = convertBits8to5(data)
        val checksumInput = hrpExpand(hrp) + values + IntArray(6)
        val polymod = polymod(checksumInput) xor 1
        val checksum = IntArray(6) { (polymod ushr (5 * (5 - it))) and 31 }

        val sb = StringBuilder(hrp.length + 1 + values.size + 6)
        sb.append(hrp).append('1')
        for (v in values) sb.append(CHARSET[v])
        for (v in checksum) sb.append(CHARSET[v])
        return sb.toString()
    }
}

/** NIP-19 `npub` for a 32-byte x-only public key. */
fun encodeNpub(xOnlyPubkey: ByteArray): String {
    require(xOnlyPubkey.size == 32) { "x-only pubkey must be 32 bytes, was ${xOnlyPubkey.size}" }
    return Bech32.encode("npub", xOnlyPubkey)
}

/** The 32-byte x-only public key inside a NIP-19 `npub`. */
fun decodeNpub(npub: String): ByteArray {
    val (hrp, data) = Bech32.decode(npub.trim())
    require(hrp == "npub") { "not an npub" }
    require(data.size == 32) { "npub does not contain a 32-byte key" }
    return data
}
