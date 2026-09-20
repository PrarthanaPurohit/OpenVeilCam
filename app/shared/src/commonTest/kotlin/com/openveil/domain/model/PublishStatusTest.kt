package com.openveil.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Each error's resume point is what "Retry publication" re-enters at. Getting one wrong
 * either repeats a multi-megabyte upload or, worse, skips a stage that never ran.
 */
class PublishStatusTest {

    @Test
    fun failures_before_signing_restart_from_the_capture() {
        for (e in listOf(PublishError.CAMERA_FAILED, PublishError.C2PA_FAILED, PublishError.HASH_FAILED)) {
            assertEquals(PublishStatus.CAPTURED, e.resumeAt, "$e")
        }
    }

    @Test
    fun upload_failures_keep_the_signature() {
        for (e in listOf(PublishError.BLOSSOM_AUTH_FAILED, PublishError.BLOSSOM_UPLOAD_FAILED, PublishError.NO_NETWORK)) {
            assertEquals(PublishStatus.C2PA_SIGNED, e.resumeAt, "$e")
        }
    }

    @Test
    fun relay_and_signer_failures_keep_the_upload() {
        for (e in listOf(PublishError.NOSTR_SIGNING_FAILED, PublishError.LINKED_SIGNER_FAILED, PublishError.NOSTR_PUBLISH_FAILED)) {
            assertEquals(PublishStatus.BLOSSOM_UPLOADED, e.resumeAt, "$e")
        }
    }

    @Test
    fun every_error_has_a_resume_point() {
        // A new PublishError without a branch in resumeAt would not compile, but this
        // documents that the mapping is total and stays that way.
        assertEquals(PublishError.entries.size, PublishError.entries.map { it.resumeAt }.size)
    }

    @Test
    fun only_published_and_failed_are_terminal() {
        assertEquals(
            setOf(PublishStatus.PUBLISHED, PublishStatus.FAILED),
            PublishStatus.entries.filter { it.isTerminal }.toSet(),
        )
    }

    @Test
    fun in_flight_means_a_stage_is_actually_running() {
        assertEquals(
            setOf(PublishStatus.C2PA_SIGNING, PublishStatus.UPLOADING_BLOSSOM, PublishStatus.PUBLISHING_NOSTR),
            PublishStatus.entries.filter { it.isInFlight }.toSet(),
        )
        assertFalse(PublishStatus.CAPTURED.isInFlight)
        assertTrue(PublishStatus.entries.none { it.isInFlight && it.isTerminal })
    }
}
