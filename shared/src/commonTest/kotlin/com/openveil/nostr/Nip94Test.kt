package com.openveil.nostr

import com.openveil.crypto.hexToBytes
import com.openveil.domain.model.BlossomUploadResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Nip94Test {

    private val upload = BlossomUploadResult(
        url = "https://blossom.band/abc.jpg",
        sha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        size = 4821932,
        mimeType = "image/jpeg",
        serverUrl = "https://blossom.band",
    )

    private fun List<List<String>>.value(name: String) =
        firstOrNull { it[0] == name }?.get(1)

    @Test
    fun includes_every_tag_nip94_requires() {
        val tags = buildNip94Tags(upload, originalSha256 = "aa", width = 4032, height = 3024, altText = null)
        // url, m, x, ox are all mandatory per NIP-94.
        assertEquals(upload.url, tags.value("url"))
        assertEquals("image/jpeg", tags.value("m"))
        assertEquals(upload.sha256, tags.value("x"))
        assertEquals("aa", tags.value("ox"))
    }

    /**
     * `x` must be the hash the *server* reported, not a locally remembered value. If those
     * ever diverge the published hash would not match the stored bytes and every
     * verification downstream fails.
     */
    @Test
    fun x_tag_comes_from_the_upload_result() {
        val tags = buildNip94Tags(upload, originalSha256 = null, width = 1, height = 1, altText = null)
        assertEquals(upload.sha256, tags.value("x"))
    }

    @Test
    fun ox_falls_back_to_x_when_no_original_hash_is_known() {
        val tags = buildNip94Tags(upload, originalSha256 = null, width = 0, height = 0, altText = null)
        assertEquals(upload.sha256, tags.value("ox"))
    }

    @Test
    fun dim_is_width_x_height_and_omitted_when_unknown() {
        assertEquals(
            "4032x3024",
            buildNip94Tags(upload, null, 4032, 3024, null).value("dim"),
        )
        assertNull(buildNip94Tags(upload, null, 0, 0, null).value("dim"))
    }

    @Test
    fun alt_text_is_omitted_when_blank() {
        assertNull(buildNip94Tags(upload, null, 1, 1, "").value("alt"))
        assertNull(buildNip94Tags(upload, null, 1, 1, null).value("alt"))
        assertEquals("a cat", buildNip94Tags(upload, null, 1, 1, "a cat").value("alt"))
    }

    @Test
    fun blossom_auth_tags_match_bud11() {
        val tags = buildBlossomAuthTags("upload", "abc123", expiresAtEpochSeconds = 1_700_000_300L)
        assertEquals("upload", tags.value("t"))
        assertEquals("abc123", tags.value("x"))
        assertEquals("1700000300", tags.value("expiration"))
    }

    @Test
    fun blossom_auth_omits_x_when_no_hash_applies() {
        val tags = buildBlossomAuthTags("list", null, 1L)
        assertNull(tags.value("x"))
        assertEquals("list", tags.value("t"))
    }

    /**
     * BUD-11: created_at must be in the past and expiration in the future. Getting either
     * backwards produces an opaque 401 rather than a useful error, so it is pinned here.
     */
    @Test
    fun signed_blossom_auth_event_has_past_created_at_and_future_expiration() {
        val identity = NostrIdentity(
            "0000000000000000000000000000000000000000000000000000000000000001".hexToBytes()
        )
        val now = 1_700_000_000L
        val event = identity.signEvent(
            kind = KIND_BLOSSOM_AUTH,
            content = "Upload Blob",
            tags = buildBlossomAuthTags("upload", "abc", now + 300),
            createdAt = now - 1,
        )

        assertEquals(24242, event.kind)
        assertTrue(event.createdAt < now, "created_at must be in the past")
        assertTrue(event.tag("expiration")!!.toLong() > now, "expiration must be in the future")
        assertTrue(event.content.isNotBlank(), "content must be human readable")
    }

    // --- caption ---------------------------------------------------------------

    @Test
    fun caption_leads_the_companion_note_and_the_url_follows() {
        val note = buildCompanionNoteContent("https://example.com/a.jpg", "Police at the gate")

        // Order matters: a reader should see what was said before how it was made.
        assertTrue(note.startsWith("Police at the gate"), "caption should lead, was: $note")
        assertTrue(note.contains("Captured with OpenVeil"))
        assertTrue(note.trimEnd().endsWith("https://example.com/a.jpg"))
    }

    @Test
    fun companion_note_falls_back_when_there_is_no_caption() {
        val expected = "Captured with OpenVeil\n\nhttps://example.com/a.jpg"
        assertEquals(expected, buildCompanionNoteContent("https://example.com/a.jpg"))
        assertEquals(expected, buildCompanionNoteContent("https://example.com/a.jpg", null))
        // Whitespace-only input must not produce a note with a blank first line.
        assertEquals(expected, buildCompanionNoteContent("https://example.com/a.jpg", "   "))
    }

    @Test
    fun caption_is_trimmed_so_stray_whitespace_never_reaches_a_relay() {
        val note = buildCompanionNoteContent("https://example.com/a.jpg", "  spaced out \n")
        assertTrue(note.startsWith("spaced out"), "was: $note")
    }

    @Test
    fun caption_becomes_the_alt_text_for_accessibility_and_inline_rendering() {
        val tags = buildNip94Tags(
            upload = upload,
            originalSha256 = null,
            width = 100,
            height = 200,
            altText = "A crowd on the bridge",
        )
        assertEquals("A crowd on the bridge", tags.first { it[0] == "alt" }[1])

        // NIP-92 imeta is what ordinary clients read to render the image inline, so the
        // description has to be repeated there rather than only in the top-level tag.
        val imeta = tags.first { it[0] == "imeta" }
        assertTrue(imeta.contains("alt A crowd on the bridge"), "imeta was: $imeta")
    }

    @Test
    fun no_alt_tag_is_emitted_without_a_caption() {
        val tags = buildNip94Tags(upload, null, 100, 200, altText = null)
        assertTrue(tags.none { it[0] == "alt" }, "an empty alt tag is worse than none")
    }
}
