package com.openveil.domain.service

/**
 * Local storage for the C2PA-signed master.
 *
 * The signed master is written before the upload is attempted and removed only once a
 * relay has accepted the event. Until then it is the only copy of a photo that can no
 * longer be reproduced -- the scene is gone -- so nothing in the pipeline may delete it
 * on a failure path.
 */
interface FileStorage {
    /** Persists the signed bytes and returns an opaque path handle. */
    suspend fun writeSignedMaster(photoId: String, bytes: ByteArray): String

    suspend fun readSignedMaster(path: String): ByteArray?

    /** Called only after publication is confirmed. */
    suspend fun deleteSignedMaster(path: String)

    /** Signed masters left behind by interrupted publishes. */
    suspend fun listPendingMasters(): List<String>
}
