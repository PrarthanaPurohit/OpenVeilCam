package com.openveil.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.openveil.nostr.nip55.ExternalSignerException
import com.openveil.nostr.nip55.IntentResult
import com.openveil.nostr.nip55.IntentResultLauncher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Bridges the NIP-55 signer's "launch an Intent and wait" onto Compose's activity-result
 * machinery.
 *
 * One request at a time, by mutex: the launcher has a single callback, so two overlapping
 * requests would be indistinguishable on return. In practice a publish makes its two
 * signing calls sequentially anyway.
 */
class SignerIntentLauncher : IntentResultLauncher {
    /** Set by [rememberSignerIntentLauncher] once the composition owns a launcher. */
    internal var launcher: ActivityResultLauncher<Intent>? = null

    private val oneAtATime = Mutex()
    private var pending: CompletableDeferred<IntentResult>? = null

    override suspend fun launch(intent: Intent): IntentResult = oneAtATime.withLock {
        val target = launcher ?: throw ExternalSignerException("The app is not in the foreground")
        val result = CompletableDeferred<IntentResult>()
        pending = result
        try {
            withContext(Dispatchers.Main.immediate) { target.launch(intent) }
            result.await()
        } finally {
            pending = null
        }
    }

    internal fun onResult(resultCode: Int, data: Intent?) {
        pending?.complete(IntentResult(resultCode, data))
    }
}

/**
 * The launcher for this composition. The object is remembered across recompositions so
 * the dependency graph can hold a stable reference; only the activity-result registration
 * underneath is composition-scoped.
 */
@Composable
fun rememberSignerIntentLauncher(): SignerIntentLauncher {
    val bridge = remember { SignerIntentLauncher() }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        bridge.onResult(result.resultCode, result.data)
    }
    bridge.launcher = launcher
    return bridge
}
