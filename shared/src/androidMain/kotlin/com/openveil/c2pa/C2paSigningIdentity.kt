package com.openveil.c2pa

/** A certificate chain and its private key, in PEM form. */
data class C2paCredentials(
    val certificatePem: String,
    val privateKeyPem: String,
)

/**
 * Supplies the key material C2PA signs with.
 *
 * Kept behind an interface because the production answer is not the development one: a
 * real deployment needs a CA-issued certificate and a key held in hardware, reached
 * through `Signer.withCallback` so the private key never enters the process. Swapping
 * that in should not require touching [AndroidC2paService].
 */
interface C2paSigningIdentity {
    fun load(): C2paCredentials?
}

/**
 * DEVELOPMENT ONLY. Loads a self-signed certificate and its private key from the app's
 * own resources.
 *
 * Two things are true of this and must not be forgotten:
 *
 *  1. The private key ships inside the APK. Anyone with the app can extract it and sign
 *     images that claim to be OpenVeil captures. It grants no authority -- the
 *     certificate is self-signed and chains to nothing -- but it must be replaced with a
 *     hardware-held, CA-issued identity before any real release.
 *  2. Validators will report Content Credentials signed with it as *Valid* but not
 *     *Trusted*: the signature is cryptographically sound and the tamper-evidence is
 *     real, but nothing vouches for who the signer is. That is the correct and honest
 *     posture for a development build, and the UI reports it that way rather than
 *     showing an unqualified tick.
 *
 * Regenerate with `bash tools/generate-dev-cert.sh`. The key is git-ignored, so a fresh
 * clone has no signing identity until that script is run -- which is deliberate.
 */
class DevCertSigningIdentity : C2paSigningIdentity {

    private val cached: C2paCredentials? by lazy {
        val cert = readResource(CERT_RESOURCE)
        val key = readResource(KEY_RESOURCE)
        if (cert == null || key == null) null else C2paCredentials(cert, key)
    }

    override fun load(): C2paCredentials? = cached

    private fun readResource(path: String): String? =
        javaClass.classLoader?.getResourceAsStream(path)?.use { it.readBytes().decodeToString() }

    private companion object {
        const val CERT_RESOURCE = "c2pa/dev_signing_cert.pem"
        const val KEY_RESOURCE = "c2pa/dev_signing_key.pem"
    }
}
