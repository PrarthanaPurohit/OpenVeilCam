package com.openveil.storage

import android.content.Context
import com.openveil.domain.service.FileStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Signed masters in app-private storage.
 *
 * `filesDir` rather than external storage or MediaStore: a pending capture is not yet
 * something the user has chosen to publish, and putting it in the shared gallery would
 * expose it to every app on the device and to cloud photo backup before that decision is
 * made.
 */
class AndroidFileStorage(context: Context) : FileStorage {

    private val root = File(context.applicationContext.filesDir, "pending").apply { mkdirs() }

    override suspend fun writeSignedMaster(photoId: String, bytes: ByteArray): String =
        withContext(Dispatchers.IO) {
            val target = File(root, "$photoId.jpg")
            // Write to a temp file and rename, so an interrupted write cannot leave a
            // truncated "master" that would hash differently than what was signed.
            val temp = File(root, "$photoId.jpg.part")
            temp.writeBytes(bytes)
            if (target.exists()) target.delete()
            check(temp.renameTo(target)) { "could not finalise signed master for $photoId" }
            target.absolutePath
        }

    override suspend fun readSignedMaster(path: String): ByteArray? = withContext(Dispatchers.IO) {
        File(path).takeIf { it.exists() }?.readBytes()
    }

    override suspend fun deleteSignedMaster(path: String) = withContext(Dispatchers.IO) {
        File(path).delete()
        Unit
    }

    override suspend fun listPendingMasters(): List<String> = withContext(Dispatchers.IO) {
        root.listFiles { f -> f.isFile && f.name.endsWith(".jpg") }
            ?.map { it.absolutePath }
            .orEmpty()
    }
}
