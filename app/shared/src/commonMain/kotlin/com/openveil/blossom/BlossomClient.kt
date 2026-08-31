package com.openveil.blossom

import com.openveil.crypto.toBase64UrlNoPad
import com.openveil.domain.model.AppResult
import com.openveil.domain.model.BlossomUploadResult
import com.openveil.domain.model.PublishError
import com.openveil.domain.model.SignedAsset
import com.openveil.nostr.KIND_BLOSSOM_AUTH
import com.openveil.nostr.NostrIdentity
import com.openveil.nostr.buildBlossomAuthTags
import com.openveil.nostr.toJson
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Clock

/**
 * Blossom servers to try, in order.
 *
 * A single hard-coded server would make one operator's outage look like a bug in the app,
 * so the client falls through the list. Keep these in config rather than scattered
 * through the code.
 */
data class BlossomConfig(
    // blossom.band first, matching OpenVeilCam's BLOSSOM_SERVER, so both publishers
    // store blobs on the same host. The rest are fallbacks this app adds so one
    // operator's outage does not read to the user as a bug.
    val servers: List<String> = listOf(
        "https://blossom.band",
        "https://blossom.primal.net",
        "https://nostr.download",
    ),
    /** How long an upload authorization stays valid. Short, since it is minted per request. */
    val authTtlSeconds: Long = 300,
)

/**
 * Uploads and retrieves blobs on Blossom servers.
 *
 * Blossom is content-addressed: a blob's URL contains its SHA-256, so a server cannot
 * substitute different bytes without serving them at a URL that contradicts itself. That
 * property is what makes the published hash checkable by a third party.
 */
interface BlossomClient {
    suspend fun upload(asset: SignedAsset, sha256: String): AppResult<BlossomUploadResult>
    suspend fun get(sha256: String, serverUrl: String? = null): AppResult<ByteArray>
}

/**
 * BUD-01/BUD-02 client.
 *
 * The Authorization header is a kind-24242 Nostr event, base64url-encoded without
 * padding, behind the `Nostr` scheme. Neither the header nor the signature is ever logged.
 */
class KtorBlossomClient(
    private val httpClient: HttpClient,
    private val identity: () -> NostrIdentity?,
    private val config: BlossomConfig = BlossomConfig(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) : BlossomClient {

    override suspend fun upload(asset: SignedAsset, sha256: String): AppResult<BlossomUploadResult> {
        val id = identity()
            ?: return AppResult.Failure(PublishError.BLOSSOM_AUTH_FAILED, "no Nostr identity")

        val authHeader = try {
            buildAuthHeader(id, verb = "upload", sha256 = sha256, reason = "Upload Blob")
        } catch (e: Throwable) {
            return AppResult.Failure(PublishError.BLOSSOM_AUTH_FAILED, "auth event signing failed", e)
        }

        var lastDetail: String? = null
        for (server in config.servers) {
            val attempt = runCatching {
                httpClient.put("${server.trimEnd('/')}/upload") {
                    header(HttpHeaders.Authorization, authHeader)
                    contentType(ContentType.parse(asset.mimeType))
                    setBody(asset.bytes)
                }
            }

            val response = attempt.getOrElse { throwable ->
                // Network-level failure: try the next server rather than surfacing one
                // operator's outage as a publish failure.
                lastDetail = "network failure contacting $server: ${throwable::class.simpleName}"
                null
            } ?: continue

            if (response.status.isSuccess()) {
                val parsed = parseDescriptor(response.bodyAsText(), server, fallbackSha = sha256)
                if (parsed != null) {
                    // The server is authoritative for what it stored. If it reports a
                    // different hash than we uploaded, the bytes changed in flight and
                    // publishing that hash would produce an unverifiable photo.
                    if (!parsed.sha256.equals(sha256, ignoreCase = true)) {
                        lastDetail = "server $server returned hash ${parsed.sha256}, expected $sha256"
                        continue
                    }
                    return AppResult.Success(parsed)
                }
                lastDetail = "could not parse blob descriptor from $server"
                continue
            }

            lastDetail = "$server returned ${response.status.value}${reasonOf(response)}"
            if (response.status.value == 401 || response.status.value == 403) {
                return AppResult.Failure(PublishError.BLOSSOM_AUTH_FAILED, lastDetail)
            }
        }

        return AppResult.Failure(PublishError.BLOSSOM_UPLOAD_FAILED, lastDetail)
    }

    override suspend fun get(sha256: String, serverUrl: String?): AppResult<ByteArray> {
        val servers = serverUrl?.let { listOf(it) } ?: config.servers
        var lastDetail: String? = null
        for (server in servers) {
            val result = runCatching {
                val response = httpClient.get("${server.trimEnd('/')}/$sha256")
                check(response.status.isSuccess()) { "HTTP ${response.status.value}" }
                response.body<ByteArray>()
            }
            result.onSuccess { return AppResult.Success(it) }
            lastDetail = "failed to fetch from $server: ${result.exceptionOrNull()?.message}"
        }
        return AppResult.Failure(PublishError.BLOSSOM_UPLOAD_FAILED, lastDetail)
    }

    /**
     * `Authorization: Nostr <base64url-no-pad(event json)>` per BUD-11.
     * created_at is nudged into the past and expiration into the future, both of which
     * the spec requires and servers do enforce.
     */
    private fun buildAuthHeader(
        identity: NostrIdentity,
        verb: String,
        sha256: String?,
        reason: String,
    ): String {
        val now = Clock.System.now().epochSeconds
        val event = identity.signEvent(
            kind = KIND_BLOSSOM_AUTH,
            content = reason,
            tags = buildBlossomAuthTags(verb, sha256, now + config.authTtlSeconds),
            createdAt = now - 1,
        )
        return "Nostr " + event.toJson().encodeToByteArray().toBase64UrlNoPad()
    }

    private fun parseDescriptor(
        body: String,
        server: String,
        fallbackSha: String,
    ): BlossomUploadResult? = runCatching {
        val obj = json.parseToJsonElement(body).jsonObject
        BlossomUploadResult(
            url = obj["url"]!!.jsonPrimitive.content,
            sha256 = obj["sha256"]?.jsonPrimitive?.content ?: fallbackSha,
            size = obj["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
            mimeType = obj["type"]?.jsonPrimitive?.content ?: "image/jpeg",
            serverUrl = server,
        )
    }.getOrNull()

    /** X-Reason is a human-readable diagnostic only; never parsed for control flow. */
    private fun reasonOf(response: HttpResponse): String =
        response.headers["X-Reason"]?.let { " ($it)" } ?: ""
}
