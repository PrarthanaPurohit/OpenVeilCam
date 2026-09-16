package com.openveil.nostr

import com.openveil.crypto.chacha20
import com.openveil.crypto.constantTimeEquals
import com.openveil.crypto.hkdfExpand
import com.openveil.crypto.hkdfExtract
import com.openveil.crypto.hmacSha256
import com.openveil.crypto.secureRandomBytes
import fr.acinq.secp256k1.Secp256k1
import kotlin.io.encoding.Base64

/**
 * NIP-44 v2 encrypted payloads.
 *
 * This is the envelope every NIP-46 message travels in, so the remote-signer flow is only
 * as sound as this file. Implemented against the official test vectors (see
 * `Nip44Test`) rather than against another client's behaviour.
 *
 * Only v2 is produced or accepted. v1 was withdrawn and a client that still accepts it
 * can be downgraded into it.
 */
object Nip44 {
    private const val VERSION: Byte = 2
    private val SALT = "nip44-v2".encodeToByteArray()

    private const val MIN_PLAINTEXT = 1
    private const val MAX_PLAINTEXT = 65535

    // Payload bounds from the spec: version + nonce + minimum block + mac, and the
    // base64 of that. Rejecting on length before touching the MAC keeps the failure modes
    // for malformed input identical regardless of content.
    private const val MIN_PAYLOAD_B64 = 132
    private const val MAX_PAYLOAD_B64 = 87472
    private const val MIN_PAYLOAD_RAW = 99
    private const val MAX_PAYLOAD_RAW = 65603

    /**
     * The symmetric key shared by two parties. Symmetric in the parties:
     * `conversationKey(a, B) == conversationKey(b, A)`.
     *
     * The ECDH output is the raw x coordinate of `a * B`, **not** libsecp256k1's default
     * ECDH, which hashes the compressed point. `pubKeyTweakMul` gives the unhashed point.
     */
    fun conversationKey(privateKey: ByteArray, publicKeyXOnly: ByteArray): ByteArray {
        require(privateKey.size == 32) { "private key must be 32 bytes" }
        require(publicKeyXOnly.size == 32) { "public key must be 32 bytes (x-only)" }
        // Lift x-only to a compressed point. Either parity works because the shared x
        // coordinate is the same for P and -P.
        val compressed = byteArrayOf(0x02) + publicKeyXOnly
        val shared = Secp256k1.pubKeyTweakMul(compressed, privateKey)
        val sharedX = shared.copyOfRange(1, 33)
        return hkdfExtract(salt = SALT, ikm = sharedX)
    }

    /** Per-message keys derived from the conversation key and a fresh 32-byte nonce. */
    internal class MessageKeys(val chachaKey: ByteArray, val chachaNonce: ByteArray, val hmacKey: ByteArray)

    internal fun messageKeys(conversationKey: ByteArray, nonce: ByteArray): MessageKeys {
        require(conversationKey.size == 32) { "conversation key must be 32 bytes" }
        require(nonce.size == 32) { "nonce must be 32 bytes" }
        val keys = hkdfExpand(prk = conversationKey, info = nonce, length = 76)
        return MessageKeys(
            chachaKey = keys.copyOfRange(0, 32),
            chachaNonce = keys.copyOfRange(32, 44),
            hmacKey = keys.copyOfRange(44, 76),
        )
    }

    fun encrypt(plaintext: String, conversationKey: ByteArray): String =
        encrypt(plaintext, conversationKey, secureRandomBytes(32))

    /** Deterministic form, exposed for the test vectors. Never reuse a nonce in production. */
    internal fun encrypt(plaintext: String, conversationKey: ByteArray, nonce: ByteArray): String {
        val keys = messageKeys(conversationKey, nonce)
        val padded = pad(plaintext)
        val ciphertext = chacha20(keys.chachaKey, keys.chachaNonce, padded)
        val mac = hmacSha256(keys.hmacKey, nonce + ciphertext)
        return Base64.encode(byteArrayOf(VERSION) + nonce + ciphertext + mac)
    }

    /** Throws [Nip44Exception] on any malformed or unauthenticated payload. */
    fun decrypt(payload: String, conversationKey: ByteArray): String {
        if (payload.startsWith("#")) throw Nip44Exception("unknown encryption version")
        if (payload.length !in MIN_PAYLOAD_B64..MAX_PAYLOAD_B64) {
            throw Nip44Exception("invalid payload length: ${payload.length}")
        }
        val data = try {
            Base64.decode(payload)
        } catch (e: IllegalArgumentException) {
            throw Nip44Exception("invalid base64", e)
        }
        if (data.size !in MIN_PAYLOAD_RAW..MAX_PAYLOAD_RAW) {
            throw Nip44Exception("invalid data length: ${data.size}")
        }
        if (data[0] != VERSION) throw Nip44Exception("unknown encryption version ${data[0]}")

        val nonce = data.copyOfRange(1, 33)
        val ciphertext = data.copyOfRange(33, data.size - 32)
        val mac = data.copyOfRange(data.size - 32, data.size)

        val keys = messageKeys(conversationKey, nonce)
        val expectedMac = hmacSha256(keys.hmacKey, nonce + ciphertext)
        if (!constantTimeEquals(mac, expectedMac)) throw Nip44Exception("invalid MAC")

        val padded = chacha20(keys.chachaKey, keys.chachaNonce, ciphertext)
        return unpad(padded)
    }

    /**
     * Padded length: messages are rounded up to hide their exact size. Under 32 bytes
     * everything is 32; above that, chunks grow with the next power of two.
     */
    internal fun calcPaddedLen(unpaddedLen: Int): Int {
        require(unpaddedLen >= 1) { "unpadded length must be positive" }
        if (unpaddedLen <= 32) return 32
        // floor(log2(n - 1)) + 1, i.e. the smallest power of two strictly greater than n - 1.
        val nextPower = 1 shl (32 - (unpaddedLen - 1).countLeadingZeroBits())
        val chunk = if (nextPower <= 256) 32 else nextPower / 8
        return chunk * ((unpaddedLen - 1) / chunk + 1)
    }

    private fun pad(plaintext: String): ByteArray {
        val unpadded = plaintext.encodeToByteArray()
        val len = unpadded.size
        if (len !in MIN_PLAINTEXT..MAX_PLAINTEXT) throw Nip44Exception("invalid plaintext size: $len")
        val prefix = byteArrayOf((len ushr 8).toByte(), len.toByte())
        val suffix = ByteArray(calcPaddedLen(len) - len)
        return prefix + unpadded + suffix
    }

    private fun unpad(padded: ByteArray): String {
        if (padded.size < 2) throw Nip44Exception("invalid padding")
        val len = ((padded[0].toInt() and 0xFF) shl 8) or (padded[1].toInt() and 0xFF)
        if (len !in MIN_PLAINTEXT..MAX_PLAINTEXT) throw Nip44Exception("invalid padding")
        if (padded.size != 2 + calcPaddedLen(len)) throw Nip44Exception("invalid padding")
        return padded.copyOfRange(2, 2 + len).decodeToString(throwOnInvalidSequence = true)
    }
}

class Nip44Exception(message: String, cause: Throwable? = null) : Exception(message, cause)
