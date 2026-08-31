package com.openveil.nostr

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The event id is the single most fragile value in the pipeline: relays reject a
 * mis-derived id with no useful diagnostic, so it fails as "nothing published" rather
 * than as an error.
 *
 * These expectations were produced by an independent implementation (Python, using its
 * own JSON encoder and hashlib) rather than by recording this code's own output, so the
 * test can actually catch a serialization mistake instead of enshrining one.
 */
class NostrEventTest {

    private val pubkey = "3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d"

    @Test
    fun event_id_matches_independent_implementation_simple() {
        assertEquals(
            "bd992ccfc15a3fa6cc214b72ed69402be77892690628c6507f7afab9ee100150",
            computeEventId(pubkey, 1700000000L, 1, emptyList(), "hello"),
        )
    }

    @Test
    fun event_id_matches_independent_implementation_for_nip94() {
        val tags = listOf(
            listOf("url", "https://blossom.band/abc.jpg"),
            listOf("m", "image/jpeg"),
            listOf("x", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
            listOf("ox", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
            listOf("size", "4821932"),
            listOf("dim", "4032x3024"),
        )
        assertEquals(
            "2443a892a8502657f8d115d7ecadc46fb42f366a0370e39268c3b327b20f33df",
            computeEventId(pubkey, 1723456789L, 1063, tags, "Captured with OpenVeil"),
        )
    }

    /** Quotes, backslashes, newlines and tabs are exactly the characters NIP-01 escapes. */
    @Test
    fun event_id_handles_escaped_characters() {
        assertEquals(
            "8174ca844f2b0a7c120c5625cbc55ccd9137264459c29356c24e89e6b244a611",
            computeEventId(
                pubkey,
                1700000000L,
                1,
                listOf(listOf("alt", "tab\there")),
                "line\nbreak \"quoted\" back\\slash",
            ),
        )
    }

    @Test
    fun canonical_serialization_has_no_whitespace_and_fixed_field_order() {
        val actual = canonicalSerialization(
            pubkey = pubkey,
            createdAt = 1700000000L,
            kind = 1,
            tags = listOf(listOf("a", "b")),
            content = "hi",
        )
        assertEquals("[0,\"$pubkey\",1700000000,1,[[\"a\",\"b\"]],\"hi\"]", actual)
    }

    @Test
    fun canonical_serialization_escapes_only_the_nip01_set() {
        val actual = canonicalSerialization(
            pubkey = pubkey,
            createdAt = 1L,
            kind = 1,
            tags = emptyList(),
            content = "a\"b\\c\nd\re\tf",
        )
        assertEquals("[0,\"$pubkey\",1,1,[],\"a\\\"b\\\\c\\nd\\re\\tf\"]", actual)
    }

    @Test
    fun wire_json_round_trips_field_names() {
        val event = NostrEvent(
            id = "aa", pubkey = pubkey, createdAt = 5L, kind = 1063,
            tags = listOf(listOf("url", "https://x/y")), content = "c", sig = "bb",
        )
        val json = event.toJson()
        assertEquals(
            "{\"id\":\"aa\",\"pubkey\":\"$pubkey\",\"created_at\":5,\"kind\":1063," +
                "\"tags\":[[\"url\",\"https://x/y\"]],\"content\":\"c\",\"sig\":\"bb\"}",
            json,
        )
    }

    @Test
    fun tag_lookup_returns_first_match_value() {
        val event = NostrEvent(
            id = "", pubkey = pubkey, createdAt = 0, kind = 1063,
            tags = listOf(listOf("m", "image/jpeg"), listOf("x", "abc"), listOf("x", "def")),
            content = "", sig = "",
        )
        assertEquals("image/jpeg", event.tag("m"))
        assertEquals("abc", event.tag("x"))
        assertEquals(null, event.tag("nope"))
    }
}
