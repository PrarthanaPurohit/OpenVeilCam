package com.openveil.nostr.nip55

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.openveil.nostr.NostrEvent
import com.openveil.nostr.computeEventId
import com.openveil.nostr.parseNostrEvent
import com.openveil.nostr.signingProblem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Launches an Intent for a result and suspends until it comes back.
 *
 * Only an Activity can do this, so the UI layer provides the implementation; the signer
 * logic below stays testable and Activity-free.
 */
fun interface IntentResultLauncher {
    suspend fun launch(intent: Intent): IntentResult
}

data class IntentResult(val resultCode: Int, val data: Intent?)

/**
 * NIP-55: signing through Amber (or any app answering the `nostrsigner:` scheme).
 *
 * Two channels, tried in order:
 *  1. The signer's ContentProvider, which answers silently when the user has told the
 *     signer to remember this permission. This is what makes publishing not require an
 *     app switch every time.
 *  2. An Intent, which opens the signer's UI for approval.
 *
 * Every signed event is verified locally before it is returned, exactly as with a remote
 * bunker. The signer runs in another process and is trusted with the key, not with the
 * correctness of what it hands back.
 */
class AndroidSignerApp(
    private val context: Context,
    private val launcher: IntentResultLauncher,
) : ExternalSignerApp {

    override val isInstalled: Boolean
        get() = context.packageManager
            .queryIntentActivities(Intent(Intent.ACTION_VIEW, Uri.parse(SCHEME)), 0)
            .isNotEmpty()

    override suspend fun getPublicKey(permissions: List<SignerPermission>): ExternalSignerAccount {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(SCHEME)).apply {
            putExtra("type", "get_public_key")
            putExtra("permissions", permissionsJson(permissions))
        }
        val data = launcher.launch(intent).dataOrThrow()
        val pubkey = normalizeSignerPubkey(data.getStringExtra("result"))
            ?: throw ExternalSignerException("The signer did not return a usable public key")
        // Amber reports which package answered; fall back to the component that did.
        val packageName = data.getStringExtra("package")?.takeIf { it.isNotBlank() }
            ?: data.component?.packageName
            ?: throw ExternalSignerException("The signer did not identify itself")
        return ExternalSignerAccount(pubkey, packageName)
    }

    override suspend fun signEvent(
        kind: Int,
        content: String,
        tags: List<List<String>>,
        createdAt: Long,
        account: ExternalSignerAccount,
    ): NostrEvent {
        val id = computeEventId(account.userPubkeyHex, createdAt, kind, tags, content)
        val unsigned = buildJsonObject {
            put("id", id)
            put("pubkey", account.userPubkeyHex)
            put("created_at", createdAt)
            put("kind", kind)
            putJsonArray("tags") { for (tag in tags) add(buildJsonArray { for (v in tag) add(v) }) }
            put("content", content)
            put("sig", "")
        }.toString()

        when (val silent = withContext(Dispatchers.IO) { signSilently(account, unsigned) }) {
            is SilentResult.Signed -> return verified(silent.eventJson, account)
            SilentResult.AlwaysRejected ->
                throw ExternalSignerException("Your signer is set to always reject this request")
            SilentResult.NeedsPrompt -> Unit
        }

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("$SCHEME$unsigned")).apply {
            `package` = account.packageName
            putExtra("type", "sign_event")
            putExtra("id", id)
            putExtra("current_user", account.userPubkeyHex)
        }
        val data = launcher.launch(intent).dataOrThrow()
        val signedJson = data.getStringExtra("event")
            ?: throw ExternalSignerException("The signer did not return the signed event")
        return verified(signedJson, account)
    }

    private sealed interface SilentResult {
        data class Signed(val eventJson: String) : SilentResult
        data object AlwaysRejected : SilentResult
        data object NeedsPrompt : SilentResult
    }

    /**
     * `content://<package>.SIGN_EVENT`, with the payload carried in the projection slot --
     * that is where the spec's reference code puts it, whatever the parameter is named.
     */
    private fun signSilently(account: ExternalSignerAccount, unsignedJson: String): SilentResult {
        val cursor = runCatching {
            context.contentResolver.query(
                Uri.parse("content://${account.packageName}.SIGN_EVENT"),
                arrayOf(unsignedJson, "", account.userPubkeyHex),
                null, null, null,
            )
        }.getOrNull() ?: return SilentResult.NeedsPrompt

        cursor.use {
            if (it.getColumnIndex("rejected") > -1) return SilentResult.AlwaysRejected
            if (!it.moveToFirst()) return SilentResult.NeedsPrompt
            val eventColumn = it.getColumnIndex("event")
            if (eventColumn < 0) return SilentResult.NeedsPrompt
            val eventJson = it.getString(eventColumn) ?: return SilentResult.NeedsPrompt
            return SilentResult.Signed(eventJson)
        }
    }

    private fun verified(eventJson: String, account: ExternalSignerAccount): NostrEvent {
        val event = parseNostrEvent(eventJson)
            ?: throw ExternalSignerException("The signer returned a malformed event")
        event.signingProblem(account.userPubkeyHex)?.let { throw ExternalSignerException(it) }
        return event
    }

    private fun IntentResult.dataOrThrow(): Intent {
        if (resultCode != Activity.RESULT_OK) throw ExternalSignerException("The signer app closed without answering")
        val data = this.data ?: throw ExternalSignerException("The signer app returned nothing")
        if (data.getBooleanExtra("rejected", false)) throw ExternalSignerException("You rejected the request in your signer")
        return data
    }

    private fun permissionsJson(permissions: List<SignerPermission>): String = buildJsonArray {
        for (p in permissions) {
            add(buildJsonObject {
                put("type", p.type)
                if (p.kind != null) put("kind", p.kind)
            })
        }
    }.toString()

    private companion object {
        const val SCHEME = "nostrsigner:"
    }
}
