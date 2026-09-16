package com.openveil.nostr

import com.openveil.crypto.hexToBytes
import com.openveil.crypto.sha256
import com.openveil.crypto.toHex
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A Nostr event (NIP-01).
 *
 * [id] is the SHA-256 of the canonical serialization, and [sig] is a BIP-340 Schnorr
 * signature over that id. Both are lowercase hex.
 */
data class NostrEvent(
    val id: String,
    val pubkey: String,
    val createdAt: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
    val sig: String,
) {
    /** First value of the first tag with this name, or null. */
    fun tag(name: String): String? =
        tags.firstOrNull { it.size >= 2 && it[0] == name }?.get(1)
}

/**
 * Canonical NIP-01 serialization used to derive the event id:
 *
 * ```
 * [0,<pubkey>,<created_at>,<kind>,<tags>,<content>]
 * ```
 *
 * Hand-written on purpose. A general-purpose JSON encoder gives no guarantee about field
 * order, whitespace, or which characters it escapes, and any of those differences changes
 * the hash. A wrong id means every relay rejects the event with no useful diagnostic, so
 * this must not be delegated.
 */
internal fun canonicalSerialization(
    pubkey: String,
    createdAt: Long,
    kind: Int,
    tags: List<List<String>>,
    content: String,
): String {
    val sb = StringBuilder()
    sb.append("[0,\"").append(pubkey).append("\",").append(createdAt).append(',').append(kind)
    sb.append(",[")
    tags.forEachIndexed { i, tag ->
        if (i > 0) sb.append(',')
        sb.append('[')
        tag.forEachIndexed { j, value ->
            if (j > 0) sb.append(',')
            sb.append('"')
            escapeInto(sb, value)
            sb.append('"')
        }
        sb.append(']')
    }
    sb.append("],\"")
    escapeInto(sb, content)
    sb.append("\"]")
    return sb.toString()
}

/**
 * The exact escape set NIP-01 mandates -- and nothing more. Escaping additional
 * characters (for example emitting  for other control codes, as many JSON encoders
 * do) yields a different hash and therefore a different, invalid event id.
 */
private fun escapeInto(sb: StringBuilder, value: String) {
    for (c in value) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\b' -> sb.append("\\b")
            '\u000C' -> sb.append("\\f")
            else -> sb.append(c)
        }
    }
}

/** SHA-256 of the canonical serialization, lowercase hex. */
fun computeEventId(
    pubkey: String,
    createdAt: Long,
    kind: Int,
    tags: List<List<String>>,
    content: String,
): String = sha256(
    canonicalSerialization(pubkey, createdAt, kind, tags, content).encodeToByteArray()
).toHex()

/** Serializes a signed event to the wire JSON relays expect. */
fun NostrEvent.toJson(): String {
    val sb = StringBuilder()
    sb.append("{\"id\":\"").append(id)
    sb.append("\",\"pubkey\":\"").append(pubkey)
    sb.append("\",\"created_at\":").append(createdAt)
    sb.append(",\"kind\":").append(kind)
    sb.append(",\"tags\":[")
    tags.forEachIndexed { i, tag ->
        if (i > 0) sb.append(',')
        sb.append('[')
        tag.forEachIndexed { j, value ->
            if (j > 0) sb.append(',')
            sb.append('"')
            escapeJson(sb, value)
            sb.append('"')
        }
        sb.append(']')
    }
    sb.append("],\"content\":\"")
    escapeJson(sb, content)
    sb.append("\",\"sig\":\"").append(sig).append("\"}")
    return sb.toString()
}

/**
 * Wire-format escaping. Unlike [escapeInto] this also escapes remaining control
 * characters, because the transport must be valid JSON -- but it is never used to compute
 * an id.
 */
private fun escapeJson(sb: StringBuilder, value: String) {
    for (c in value) {
        when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c == '\b' -> sb.append("\\b")
            c == '\u000C' -> sb.append("\\f")
            c < ' ' -> {
                sb.append("\\u")
                val hex = c.code.toString(16)
                repeat(4 - hex.length) { sb.append('0') }
                sb.append(hex)
            }
            else -> sb.append(c)
        }
    }
}

/**
 * Parses a signed event from wire JSON. Null on anything malformed; the caller decides
 * whether that is an error worth reporting.
 */
fun parseNostrEvent(text: String): NostrEvent? = runCatching {
    val obj = kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject
    NostrEvent(
        id = obj["id"]!!.jsonPrimitive.content.lowercase(),
        pubkey = obj["pubkey"]!!.jsonPrimitive.content.lowercase(),
        createdAt = obj["created_at"]!!.jsonPrimitive.content.toLong(),
        kind = obj["kind"]!!.jsonPrimitive.content.toInt(),
        tags = obj["tags"]!!.jsonArray.map { tag -> tag.jsonArray.map { it.jsonPrimitive.content } },
        content = obj["content"]!!.jsonPrimitive.content,
        sig = obj["sig"]!!.jsonPrimitive.content.lowercase(),
    )
}.getOrNull()

/**
 * Why an event handed back by an external signer must not be trusted, or null if it is
 * sound: right author, id that matches its own fields, signature that verifies.
 *
 * Every signer integration runs this before publishing. A relay would reject a bad event
 * with an opaque error, and a signer that swapped in another pubkey would otherwise
 * publish under a name the user never chose.
 */
fun NostrEvent.signingProblem(expectedPubkey: String): String? {
    if (pubkey != expectedPubkey) return "The signer signed with a different key"
    if (computeEventId(pubkey, createdAt, kind, tags, content) != id) {
        return "The signer returned an event with a wrong id"
    }
    val valid = runCatching {
        fr.acinq.secp256k1.Secp256k1.verifySchnorr(sig.hexToBytes(), id.hexToBytes(), pubkey.hexToBytes())
    }.getOrDefault(false)
    return if (valid) null else "The signer returned an invalid signature"
}
