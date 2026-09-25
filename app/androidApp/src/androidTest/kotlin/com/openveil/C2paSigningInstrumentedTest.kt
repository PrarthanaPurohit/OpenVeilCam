package com.openveil

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openveil.c2pa.AndroidC2paService
import com.openveil.c2pa.DevCertSigningIdentity
import com.openveil.crypto.hexToBytes
import com.openveil.domain.model.AppResult
import com.openveil.domain.model.CapturedImage
import com.openveil.domain.service.C2paSigningContext
import com.openveil.domain.service.C2paVerification
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Instant

/**
 * The real Content Credentials signer, on a real Android runtime.
 *
 * Everything else in the pipeline is covered by host tests with the signer faked, because
 * c2pa-android is JNI over c2pa-rs and only ships native libraries for Android ABIs. This
 * is the one place the actual manifest creation and validation run, and the one test that
 * fails when the APK was built without a signing identity -- which is a silent
 * "Signing failed" on the review screen otherwise.
 */
@RunWith(AndroidJUnit4::class)
class C2paSigningInstrumentedTest {

    /** A genuinely valid 1x1 JPEG. c2pa-rs parses the container, so it must be real. */
    private val fixtureJpeg = (
        "ffd8ffe000104a46494600010101006000600000ffdb004300080606070605080707070909080a0c" +
            "140d0c0b0b0c1912130f141d1a1f1e1d1a1c1c20242e2720222c231c1c2837292c3031343434" +
            "1f27393d38323c2e333432ffc0000b080001000101011100ffc4001400010000000000000000" +
            "0000000000000009ffc40014100100000000000000000000000000000000ffda000801" +
            "0100003f002a9fffd9"
        ).hexToBytes()

    private val capture = CapturedImage(fixtureJpeg, "image/jpeg", 1, 1, Instant.fromEpochSeconds(1_700_000_000))

    private val context = C2paSigningContext(
        npub = "npub1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqsx3rq3l",
        nostrPubkeyHex = "00".repeat(32),
        width = 1,
        height = 1,
        captureDevice = "Instrumented test",
    )

    private val service = AndroidC2paService(DevCertSigningIdentity(), appVersion = "test")

    @Test
    fun the_apk_carries_a_signing_identity() {
        // tools/generate-dev-cert.sh must have run before the build. Without it the app
        // installs and launches fine and every capture fails at the signing step.
        val credentials = DevCertSigningIdentity().load()
        assertNotNull("no C2PA signing identity in the APK; run tools/generate-dev-cert.sh and rebuild", credentials)
        assertTrue(credentials!!.certificatePem.contains("BEGIN CERTIFICATE"))
        assertTrue(credentials.privateKeyPem.contains("BEGIN PRIVATE KEY"))
        assertTrue(runBlocking { service.isSigningAvailable() })
    }

    @Test
    fun signs_and_the_result_validates_as_intact_but_untrusted() = runBlocking {
        val result = service.signImage(capture, context)

        assertTrue("signing failed: ${(result as? AppResult.Failure)?.detail}", result is AppResult.Success)
        val signed = (result as AppResult.Success).value
        assertEquals("image/jpeg", signed.mimeType)
        assertTrue("the manifest is embedded, so the file grows", signed.bytes.size > fixtureJpeg.size)
        assertTrue("still a JPEG", signed.bytes[0] == 0xff.toByte() && signed.bytes[1] == 0xd8.toByte())
        assertNotNull("active manifest id is readable back", signed.manifestId)

        val verification = service.verify(signed.bytes, "image/jpeg")
        assertTrue("expected Valid, got $verification", verification is C2paVerification.Valid)
        val valid = verification as C2paVerification.Valid
        assertEquals(signed.manifestId, valid.manifestId)
        // A development certificate chains to nothing on the C2PA trust list. Reporting
        // it as trusted would be the one thing this app must never do.
        assertFalse("a self-issued development certificate must not read as trusted", valid.trusted)
    }

    @Test
    fun the_unsigned_capture_has_no_credential() = runBlocking {
        assertEquals(C2paVerification.NotPresent, service.verify(fixtureJpeg, "image/jpeg"))
    }

    @Test
    fun altering_a_signed_image_breaks_its_credential() = runBlocking {
        val signed = (service.signImage(capture, context) as AppResult.Success).value.bytes
        val tampered = signed.copyOf()
        // Flip a byte inside the image data, after the manifest. The last two bytes are
        // the JPEG end marker; the ones before are entropy-coded scan data.
        val index = tampered.size - 4
        tampered[index] = (tampered[index].toInt() xor 0x01).toByte()
        assertNotEquals(signed.toList(), tampered.toList())

        val verification = service.verify(tampered, "image/jpeg")

        assertFalse("altered bytes must not validate: $verification", verification is C2paVerification.Valid)
        assertNull("an altered file must not look like it never had a credential", verification as? C2paVerification.NotPresent)
    }

    @Test
    fun the_manifest_names_the_nostr_key_that_will_announce_it() = runBlocking {
        val signed = (service.signImage(capture, context) as AppResult.Success).value.bytes

        // The manifest is JUMBF inside the JPEG; the Nostr assertion is stored as JSON, so
        // its npub appears verbatim in the bytes. This is the half of the binding that
        // lets someone holding only the image find the event that announced it.
        val text = signed.decodeToString(throwOnInvalidSequence = false)
        assertTrue("npub is in the manifest", text.contains(context.npub))
        assertTrue("assertion label is present", text.contains("world.openveil.nostr"))
    }
}
