package com.openveil.c2pa

import com.openveil.domain.model.AppResult
import com.openveil.domain.model.CapturedImage
import com.openveil.domain.model.PublishError
import com.openveil.domain.model.SignedAsset
import com.openveil.domain.service.C2paService
import com.openveil.domain.service.C2paSigningContext
import com.openveil.domain.service.C2paVerification
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.contentauth.c2pa.Builder
import org.contentauth.c2pa.ByteArrayStream
import org.contentauth.c2pa.DataStream
import org.contentauth.c2pa.Reader
import org.contentauth.c2pa.Signer
import org.contentauth.c2pa.SigningAlgorithm

/**
 * Content Credentials via the official CAI Android SDK (JNI over c2pa-rs).
 *
 * Signing happens entirely in memory: the captured bytes go in as a [DataStream] and the
 * signed asset comes out of a [ByteArrayStream], so the original never round-trips
 * through a bitmap or an encoder. That matters because the manifest hard-binds to the
 * exact byte sequence -- decoding and re-encoding would produce a valid-looking file whose
 * credential no longer verifies.
 */
class AndroidC2paService(
    private val signingIdentity: C2paSigningIdentity,
    private val appVersion: String,
) : C2paService {

    override suspend fun signImage(
        image: CapturedImage,
        context: C2paSigningContext,
    ): AppResult<SignedAsset> = withContext(Dispatchers.Default) {
        val credentials = signingIdentity.load()
            ?: return@withContext AppResult.Failure(
                PublishError.C2PA_FAILED,
                "no C2PA signing identity available",
            )

        try {
            // Both Builder and Signer wrap native handles; `use` guarantees they are
            // released even if signing throws, otherwise this leaks off-heap memory on
            // every failed capture.
            Signer.fromKeys(
                certsPEM = credentials.certificatePem,
                privateKeyPEM = credentials.privateKeyPem,
                algorithm = SigningAlgorithm.ES256,
                tsaURL = null,
            ).use { signer ->
                Builder.fromJson(buildManifestJson(image, context)).use { builder ->
                    DataStream(image.bytes).use { source ->
                        ByteArrayStream().use { dest ->
                            builder.sign(image.mimeType, source, dest, signer)
                            val signed = dest.getData()

                            if (signed.isEmpty()) {
                                return@withContext AppResult.Failure(
                                    PublishError.C2PA_FAILED,
                                    "signer produced no output",
                                )
                            }
                            AppResult.Success(
                                SignedAsset(
                                    bytes = signed,
                                    mimeType = image.mimeType,
                                    manifestId = readManifestId(signed, image.mimeType),
                                )
                            )
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            // Diagnostics only. C2PA errors describe the manifest or the certificate, never
            // key material -- Signer.fromKeys does not echo the PEM back in its messages.
            Log.w(TAG, "C2PA signing failed: ${t::class.simpleName}: ${t.message}")
            AppResult.Failure(PublishError.C2PA_FAILED, t.message ?: t::class.simpleName, t)
        }
    }

    override suspend fun verify(bytes: ByteArray, mimeType: String): C2paVerification =
        withContext(Dispatchers.Default) {
            try {
                DataStream(bytes).use { stream ->
                    Reader.fromStream(mimeType, stream).use { reader ->
                        parseVerification(reader.detailedJson())
                    }
                }
            } catch (t: Throwable) {
                // c2pa-rs raises rather than returning a status when no manifest is
                // present, so an absent credential is not an error condition here.
                val message = t.message.orEmpty()
                if (message.contains("no claim", ignoreCase = true) ||
                    message.contains("JumbfNotFound", ignoreCase = true) ||
                    message.contains("ManifestNotFound", ignoreCase = true)
                ) {
                    C2paVerification.NotPresent
                } else {
                    C2paVerification.Error(message.ifBlank { "verification failed" })
                }
            }
        }

    override suspend fun isSigningAvailable(): Boolean =
        withContext(Dispatchers.Default) { signingIdentity.load() != null }

    /**
     * The manifest asserted at capture time.
     *
     * `c2pa.created` with a `digitalCapture` source type is the claim that actually
     * matters: it states these pixels came off a sensor rather than out of a generator or
     * an editor.
     */
    private fun buildManifestJson(
        image: CapturedImage,
        context: C2paSigningContext,
    ): String {
        val manifest = buildJsonObject {
            put("format", image.mimeType)
            put("title", "OpenVeil capture")
            putJsonArray("claim_generator_info") {
                add(
                    buildJsonObject {
                        put("name", CLAIM_GENERATOR)
                        put("version", appVersion)
                    }
                )
            }
            putJsonArray("assertions") {
                add(
                    buildJsonObject {
                        put("label", "c2pa.actions")
                        putJsonObject("data") {
                            putJsonArray("actions") {
                                add(
                                    buildJsonObject {
                                        put("action", "c2pa.created")
                                        put("digitalSourceType", SOURCE_DIGITAL_CAPTURE)
                                        putJsonObject("softwareAgent") {
                                            put("name", CLAIM_GENERATOR)
                                            put("version", appVersion)
                                        }
                                        if (context.captureDevice != null) {
                                            putJsonObject("parameters") {
                                                put("world.openveil.device", context.captureDevice)
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                )
                // The bridge back to Nostr. A verifier reading only the image learns which
                // npub to look up; a verifier reading only the event can check the image
                // names that same key. Neither side can be substituted silently.
                add(
                    buildJsonObject {
                        put("label", NOSTR_ASSERTION)
                        putJsonObject("data") {
                            put("npub", context.npub)
                            put("pubkey", context.nostrPubkeyHex)
                            put("dim", "${context.width}x${context.height}")
                        }
                    }
                )
            }
        }
        return manifest.toString()
    }

    private fun readManifestId(signed: ByteArray, mimeType: String): String? = runCatching {
        DataStream(signed).use { stream ->
            Reader.fromStream(mimeType, stream).use { reader ->
                json.parseToJsonElement(reader.json()).jsonObject["active_manifest"]
                    ?.jsonPrimitive?.content
            }
        }
    }.getOrNull()

    /**
     * Reads c2pa-rs validation results.
     *
     * The critical distinction, and the one this app exists to get right:
     *
     *  - a **failure** entry means the asset no longer matches its own manifest. The
     *    image was altered after signing. That is [C2paVerification.Invalid].
     *  - `signingCredential.untrusted` means the signature is cryptographically sound but
     *    the certificate does not chain to a trust list. Nothing was tampered with; we
     *    just cannot say who signed it. That is Valid with `trusted = false`.
     *
     * Conflating the two would tell someone their intact photo had been tampered with,
     * which is a worse failure than saying nothing at all.
     */
    private fun parseVerification(detailedJson: String): C2paVerification = runCatching {
        val root = json.parseToJsonElement(detailedJson).jsonObject
        val active = root["active_manifest"]?.jsonPrimitive?.contentOrNull

        val activeResults = root["validation_results"]?.jsonObject
            ?.get("activeManifest")?.jsonObject

        val codes = buildList {
            activeResults?.get("failure")?.jsonArray?.let { addAll(it.statusCodes()) }
            // Older shape, and where an untrusted-signer note tends to surface.
            root["validation_status"]?.jsonArray?.let { addAll(it.statusCodes()) }
        }

        val untrusted = codes.any { it.isTrustOnly() }
        val blocking = codes.filterNot { it.isTrustOnly() }

        if (blocking.isNotEmpty()) {
            return C2paVerification.Invalid(blocking.first())
        }

        val signerName = active?.let { id ->
            root["manifests"]?.jsonObject?.get(id)?.jsonObject
                ?.get("signature_info")?.jsonObject
                ?.get("issuer")?.jsonPrimitive?.contentOrNull
        }

        C2paVerification.Valid(
            manifestId = active,
            signerName = signerName,
            // A development certificate chains to nothing on the C2PA trust list, so this
            // is expected to be false until a real CA-issued identity is in place.
            trusted = !untrusted,
        )
    }.getOrElse { C2paVerification.Error(it.message ?: "could not read validation results") }

    private fun JsonArray.statusCodes(): List<String> =
        mapNotNull { it.jsonObject["code"]?.jsonPrimitive?.contentOrNull }

    /** Trust-list codes describe who signed, not whether the bytes are intact. */
    private fun String.isTrustOnly(): Boolean =
        contains("untrusted", ignoreCase = true) ||
            startsWith("signingCredential.untrusted") ||
            equals("signingCredential.ocsp.unknown", ignoreCase = true)

    private companion object {
        const val TAG = "OpenVeilC2PA"
        const val CLAIM_GENERATOR = "OpenVeil"
        const val NOSTR_ASSERTION = "world.openveil.nostr"

        /** IPTC digital source type asserting sensor capture, not synthesis. */
        const val SOURCE_DIGITAL_CAPTURE =
            "http://cv.iptc.org/newscodes/digitalsourcetype/digitalCapture"

        val json = Json { ignoreUnknownKeys = true }
    }
}
