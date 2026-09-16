package com.openveil.nostr

import com.openveil.crypto.hexToBytes
import com.openveil.crypto.toHex
import fr.acinq.secp256k1.Secp256k1
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Pinned to the official NIP-44 v2 vectors (paulmillr/nip44, nip44.vectors.json).
 *
 * These are the ground truth. If a remote signer cannot read our requests, this file is
 * the first place to look: it says whether we are wrong or they are.
 */
class Nip44Test {

    private fun xOnly(secHex: String): ByteArray =
        Secp256k1.pubkeyCreate(secHex.hexToBytes()).copyOfRange(1, 33)

    @Test
    fun conversation_key_matches_vectors() {
        val vectors = listOf(
            Triple(
                "315e59ff51cb9209768cf7da80791ddcaae56ac9775eb25b6dee1234bc5d2268",
                "c2f9d9948dc8c7c38321e4b85c8558872eafa0641cd269db76848a6073e69133",
                "3dfef0ce2a4d80a25e7a328accf73448ef67096f65f79588e358d9a0eb9013f1",
            ),
            Triple(
                "a1e37752c9fdc1273be53f68c5f74be7c8905728e8de75800b94262f9497c86e",
                "03bb7947065dde12ba991ea045132581d0954f042c84e06d8c00066e23c1a800",
                "4d14f36e81b8452128da64fe6f1eae873baae2f444b02c950b90e43553f2178b",
            ),
        )
        for ((sec1, pub2, expected) in vectors) {
            assertEquals(expected, Nip44.conversationKey(sec1.hexToBytes(), pub2.hexToBytes()).toHex())
        }
    }

    @Test
    fun conversation_key_is_symmetric() {
        val sec1 = "0000000000000000000000000000000000000000000000000000000000000001"
        val sec2 = "0000000000000000000000000000000000000000000000000000000000000002"
        val ab = Nip44.conversationKey(sec1.hexToBytes(), xOnly(sec2))
        val ba = Nip44.conversationKey(sec2.hexToBytes(), xOnly(sec1))
        assertEquals("c41c775356fd92eadc63ff5a0dc1da211b268cbea22316767095b2871ea1412d", ab.toHex())
        assertEquals(ab.toHex(), ba.toHex())
    }

    @Test
    fun message_keys_match_vector() {
        val keys = Nip44.messageKeys(
            "a1a3d60f3470a8612633924e91febf96dc5366ce130f658b1f0fc652c20b3b54".hexToBytes(),
            "e1e6f880560d6d149ed83dcc7e5861ee62a5ee051f7fde9975fe5d25d2a02d72".hexToBytes(),
        )
        assertEquals("f145f3bed47cb70dbeaac07f3a3fe683e822b3715edb7c4fe310829014ce7d76", keys.chachaKey.toHex())
        assertEquals("c4ad129bb01180c0933a160c", keys.chachaNonce.toHex())
        assertEquals("027c1db445f05e2eee864a0975b0ddef5b7110583c8c192de3732571ca5838c4", keys.hmacKey.toHex())
    }

    @Test
    fun padded_length_matches_vectors() {
        val vectors = listOf(
            16 to 32, 32 to 32, 33 to 64, 37 to 64, 45 to 64, 49 to 64, 64 to 64, 65 to 96,
            100 to 128, 111 to 128, 200 to 224, 250 to 256, 320 to 320, 383 to 384,
            384 to 384, 400 to 448, 500 to 512, 512 to 512, 515 to 640, 700 to 768,
            800 to 896, 900 to 1024, 1020 to 1024, 65536 to 65536,
        )
        for ((unpadded, padded) in vectors) {
            assertEquals(padded, Nip44.calcPaddedLen(unpadded), "padded length of $unpadded")
        }
    }

    private data class EncryptVector(val ck: String, val nonce: String, val plaintext: String, val payload: String)

    private val encryptVectors = listOf(
        EncryptVector(
            "c41c775356fd92eadc63ff5a0dc1da211b268cbea22316767095b2871ea1412d",
            "0000000000000000000000000000000000000000000000000000000000000001",
            "a",
            "AgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABee0G5VSK0/9YypIObAtDKfYEAjD35uVkHyB0F4DwrcNaCXlCWZKaArsGrY6M9wnuTMxWfp1RTN9Xga8no+kF5Vsb",
        ),
        EncryptVector(
            "c41c775356fd92eadc63ff5a0dc1da211b268cbea22316767095b2871ea1412d",
            "f00000000000000000000000000000f00000000000000000000000000000000f",
            "🍕🫃",
            "AvAAAAAAAAAAAAAAAAAAAPAAAAAAAAAAAAAAAAAAAAAPSKSK6is9ngkX2+cSq85Th16oRTISAOfhStnixqZziKMDvB0QQzgFZdjLTPicCJaV8nDITO+QfaQ61+KbWQIOO2Yj",
        ),
        EncryptVector(
            "3e2b52a63be47d34fe0a80e34e73d436d6963bc8f39827f327057a9986c20a45",
            "b635236c42db20f021bb8d1cdff5ca75dd1a0cc72ea742ad750f33010b24f73b",
            "表ポあA鷗ŒéＢ逍Üßªąñ丂㐀𠀀",
            "ArY1I2xC2yDwIbuNHN/1ynXdGgzHLqdCrXUPMwELJPc7s7JqlCMJBAIIjfkpHReBPXeoMCyuClwgbT419jUWU1PwaNl4FEQYKCDKVJz+97Mp3K+Q2YGa77B6gpxB/lr1QgoqpDf7wDVrDmOqGoiPjWDqy8KzLueKDcm9BVP8xeTJIxs=",
        ),
        EncryptVector(
            "d5a2f879123145a4b291d767428870f5a8d9e5007193321795b40183d4ab8c2b",
            "b20989adc3ddc41cd2c435952c0d59a91315d8c5218d5040573fc3749543acaf",
            "ability🤝的 ȺȾ",
            "ArIJia3D3cQc0sQ1lSwNWakTFdjFIY1QQFc/w3SVQ6yvbG2S0x4Yu86QGwPTy7mP3961I1XqB6SFFTzqDZZavhxoWMj7mEVGMQIsh2RLWI5EYQaQDIePSnXPlzf7CIt+voTD",
        ),
    )

    @Test
    fun encrypt_matches_vectors() {
        for (v in encryptVectors) {
            assertEquals(
                v.payload,
                Nip44.encrypt(v.plaintext, v.ck.hexToBytes(), v.nonce.hexToBytes()),
                "encrypting ${v.plaintext}",
            )
        }
    }

    @Test
    fun decrypt_matches_vectors() {
        for (v in encryptVectors) {
            assertEquals(v.plaintext, Nip44.decrypt(v.payload, v.ck.hexToBytes()), "decrypting ${v.plaintext}")
        }
    }

    @Test
    fun round_trips_with_a_random_nonce() {
        val ck = "c41c775356fd92eadc63ff5a0dc1da211b268cbea22316767095b2871ea1412d".hexToBytes()
        val message = """{"id":"1","method":"sign_event","params":["{}"]}"""
        assertEquals(message, Nip44.decrypt(Nip44.encrypt(message, ck), ck))
    }

    @Test
    fun rejects_unknown_versions() {
        assertFailsWith<Nip44Exception> {
            Nip44.decrypt(
                "#Atqupco0WyaOW2IGDKcshwxI9xO8HgD/P8Ddt46CbxDbrhdG8VmJdU0MIDf06CUvEvdnr1cp1fiMtlM/GrE92xAc1K5odTpCzUB+mjXgbaqtntBUbTToSUoT0ovrlPwzGjyp",
                "ca2527a037347b91bea0c8a30fc8d9600ffd81ec00038671e3a0f0cb0fc9f642".hexToBytes(),
            )
        }
        assertFailsWith<Nip44Exception> {
            Nip44.decrypt(
                "AK1AjUvoYW3IS7C/BGRUoqEC7ayTfDUgnEPNeWTF/reBZFaha6EAIRueE9D1B1RuoiuFScC0Q94yjIuxZD3JStQtE8JMNacWFs9rlYP+ZydtHhRucp+lxfdvFlaGV/sQlqZz",
                "36f04e558af246352dcf73b692fbd3646a2207bd8abd4b1cd26b234db84d9481".hexToBytes(),
            )
        }
    }

    @Test
    fun rejects_a_bad_mac() {
        assertFailsWith<Nip44Exception> {
            Nip44.decrypt(
                "Agn/l3ULCEAS4V7LhGFM6IGA17jsDUaFCKhrbXDANholyySBfeh+EN8wNB9gaLlg4j6wdBYh+3oK+mnxWu3NKRbSvQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                "cff7bd6a3e29a450fd27f6c125d5edeb0987c475fd1e8d97591e0d4d8a89763c".hexToBytes(),
            )
        }
    }

    @Test
    fun rejects_bad_padding() {
        assertFailsWith<Nip44Exception> {
            Nip44.decrypt(
                "Anq2XbuLvCuONcr7V0UxTh8FAyWoZNEdBHXvdbNmDZHB573MI7R7rrTYftpqmvUpahmBC2sngmI14/L0HjOZ7lWGJlzdh6luiOnGPc46cGxf08MRC4CIuxx3i2Lm0KqgJ7vA",
                "5254827d29177622d40a7b67cad014fe7137700c3c523903ebbe3e1b74d40214".hexToBytes(),
            )
        }
    }

    @Test
    fun rejects_bad_lengths() {
        val ck = "5cd2d13b9e355aeb2452afbd3786870dbeecb9d355b12cb0a3b6e9da5744cd35".hexToBytes()
        for (payload in listOf("", "Ag==", "AqxgToSh3H7iLYRJjoWAM+vSv/Y1mgNlm6OWWjOYUClrFF8=")) {
            assertFailsWith<Nip44Exception>("payload of length ${payload.length}") { Nip44.decrypt(payload, ck) }
        }
    }

    @Test
    fun rejects_empty_and_oversized_plaintext() {
        val ck = ByteArray(32) { 1 }
        assertFailsWith<Nip44Exception> { Nip44.encrypt("", ck) }
        assertFailsWith<Nip44Exception> { Nip44.encrypt("x".repeat(65536), ck) }
    }
}
