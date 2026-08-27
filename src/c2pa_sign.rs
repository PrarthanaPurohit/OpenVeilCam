//! Mint C2PA Content Credentials for images this device captured.
//!
//! This is the producing half of the project. [`crate::c2pa_ingest`] reads
//! credentials that other devices minted; this module mints our own. A capture
//! leaves here with a real C2PA manifest embedded in the JPEG, so any C2PA
//! tool — `c2patool`, Adobe's Verify, the browser extensions — can read it
//! without knowing anything about Nostr.
//!
//! # Why the C2PA key is not the Nostr key
//!
//! The device's Nostr identity is secp256k1. C2PA does not permit that curve:
//! [`SigningAlg`] allows only the NIST P-curves, RSA-PSS and Ed25519. So the
//! credential is signed by a *separate* P-256 key — but one derived from the
//! same hardware entropy as the Nostr key, so it is equally device-bound and
//! equally reproducible after a reflash. Nothing is stored but the
//! certificate; the private key is re-derived on every run and never written
//! to disk, matching how `device-signer` treats the Nostr secret.
//!
//! The two identities are tied together in both directions: the npub is the
//! certificate's subject and is repeated in a [`NOSTR_ASSERTION`] assertion
//! inside the manifest, while the NIP-94 event published later points back at
//! the asset. Neither direction depends on the other surviving.
//!
//! # Trust
//!
//! The certificate is self-signed, so validators report `Valid` — the
//! signature is cryptographically sound — but not `Trusted`, which would
//! require chaining to the C2PA trust list via their Conformance Program.
//! That is the intended posture: OpenVeil resolves trust against Nostr
//! identities rather than a corporate CA. See the README's trust model.

use anyhow::{bail, Context, Result};
use c2pa::{Builder, ClaimGeneratorInfo, SigningAlg};
use hkdf::Hkdf;
use openssl::asn1::Asn1Time;
use openssl::bn::BigNum;
use openssl::hash::MessageDigest;
use openssl::pkey::PKey;
use openssl::x509::extension::{
    AuthorityKeyIdentifier, BasicConstraints, ExtendedKeyUsage, KeyUsage, SubjectKeyIdentifier,
};
use openssl::x509::{X509Builder, X509NameBuilder, X509};
use p256::pkcs8::{EncodePrivateKey, LineEnding};
use serde_json::json;
use sha2::{Digest, Sha256};
use std::io::Cursor;
use std::path::{Path, PathBuf};
use tracing::info;

/// Domain separation for the credential key. Deliberately unlike anything
/// `device-signer` feeds its own KDF, so the P-256 key cannot collide with the
/// secp256k1 Nostr key even though both start from the same hardware bytes.
const HKDF_SALT: &[u8] = b"openveil/c2pa/es256/v1";
const HKDF_INFO: &[u8] = b"openveil-c2pa-signing-key";

/// Certificate lifetime. Long, because a camera in the field cannot renew one.
const CERT_VALID_DAYS: u32 = 3650;

/// Name recorded as the manifest's claim generator.
pub const CLAIM_GENERATOR: &str = "OpenVeilCam";

/// Assertion label binding a manifest to the Nostr identity that vouches for
/// it. Vendor-namespaced, so it needs no registration to be legal C2PA.
pub const NOSTR_ASSERTION: &str = "world.openveil.nostr";

/// Digital source type for "these pixels came off a sensor" — as opposed to a
/// generator or an editor. This is the assertion that carries the actual
/// provenance claim; everything else in the manifest is context.
const SOURCE_DIGITAL_CAPTURE: &str =
    "http://cv.iptc.org/newscodes/digitalsourcetype/digitalCapture";

/// Where the device keeps identity material, mirroring `device-signer`.
pub fn default_identity_dir() -> PathBuf {
    std::env::var_os("HOME")
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from("."))
        .join(".hardware_identity")
}

/// The device's C2PA signing identity: a hardware-derived P-256 key and the
/// self-signed certificate that carries it.
pub struct C2paIdentity {
    cert_pem: Vec<u8>,
    key_pem: Vec<u8>,
}

impl C2paIdentity {
    /// Load the stored certificate, minting one if it is absent or does not
    /// belong to this device.
    ///
    /// `npub` becomes the certificate subject, which is why it is required
    /// here rather than only at signing time.
    pub fn load_or_create(dir: &Path, camera_id: Option<String>, npub: &str) -> Result<Self> {
        let key = derive_signing_key(camera_id)?;
        let key_pem = key
            .to_pkcs8_pem(LineEnding::LF)
            .context("encoding the derived P-256 key as PKCS#8 PEM")?
            .as_bytes()
            .to_vec();

        std::fs::create_dir_all(dir)
            .with_context(|| format!("creating identity directory {}", dir.display()))?;
        let cert_path = dir.join("c2pa_cert.pem");

        // Reuse a stored certificate only if it actually belongs to the key we
        // just derived. A cert copied in from another device would otherwise
        // produce manifests whose signature does not verify — a confusing
        // failure to debug from the validator's side.
        let cert_pem = match std::fs::read(&cert_path) {
            Ok(pem) if cert_matches_key(&pem, &key_pem).unwrap_or(false) => {
                info!("C2PA certificate loaded from {}", cert_path.display());
                pem
            }
            _ => {
                let pem = self_signed_cert(&key_pem, npub)?;
                std::fs::write(&cert_path, &pem)
                    .with_context(|| format!("writing {}", cert_path.display()))?;
                info!("Minted self-signed C2PA certificate → {}", cert_path.display());
                pem
            }
        };

        Ok(Self { cert_pem, key_pem })
    }

    /// The certificate in PEM form, for display or for shipping alongside the
    /// manifest so a verifier can inspect the subject.
    pub fn cert_pem(&self) -> &[u8] {
        &self.cert_pem
    }

    /// Build a C2PA signer over this identity.
    ///
    /// No timestamp authority is configured: a TSA is an external HTTP
    /// dependency, and a capture that cannot reach one should still get a
    /// credential rather than failing outright.
    pub fn signer(&self) -> Result<c2pa::BoxedSigner> {
        c2pa::create_signer::from_keys(&self.cert_pem, &self.key_pem, SigningAlg::Es256, None)
            .context("building a C2PA signer from the device certificate")
    }
}

/// What the manifest should record about a capture.
pub struct CaptureInfo<'a> {
    pub npub: &'a str,
    pub pubkey_hex: &'a str,
    /// Camera as reported by `rpicam-still`, e.g. `imx708`.
    pub camera_model: Option<&'a str>,
    /// px1 hash of the capture. Embedding a manifest adds a JUMBF box without
    /// touching pixels, so this value is equally true before and after signing
    /// — which is what lets the Nostr side key off it.
    pub px1_hash: &'a str,
    pub width: u32,
    pub height: u32,
}

/// Embed a signed C2PA manifest into `jpeg` and return the new bytes.
///
/// The input is left untouched; the returned buffer is the asset that should
/// be uploaded and published, since it is the one carrying the credential.
pub fn sign_capture(
    jpeg: &[u8],
    info: &CaptureInfo<'_>,
    identity: &C2paIdentity,
) -> Result<Vec<u8>> {
    let signer = identity.signer()?;

    let mut builder = Builder::default();
    let mut generator = ClaimGeneratorInfo::new(CLAIM_GENERATOR);
    generator.set_version(env!("CARGO_PKG_VERSION"));
    builder.set_claim_generator_info(generator);
    builder.set_format("image/jpeg");

    let mut action = json!({
        "action": "c2pa.created",
        "digitalSourceType": SOURCE_DIGITAL_CAPTURE,
        "when": chrono::Utc::now().to_rfc3339(),
        "softwareAgent": {
            "name": CLAIM_GENERATOR,
            "version": env!("CARGO_PKG_VERSION"),
        },
    });
    if let Some(model) = info.camera_model {
        action["parameters"] = json!({ "world.openveil.camera": model });
    }
    builder
        .add_action(action)
        .map_err(anyhow::Error::new)
        .context("adding the c2pa.created action")?;

    // The bridge back to Nostr. A verifier reading the manifest learns which
    // npub to look up, and finds the px1 hash to search relays by.
    builder
        .add_assertion(
            NOSTR_ASSERTION,
            &json!({
                "npub": info.npub,
                "pubkey": info.pubkey_hex,
                "px1": info.px1_hash,
                "dim": format!("{}x{}", info.width, info.height),
            }),
        )
        .map_err(anyhow::Error::new)
        .context("adding the Nostr identity assertion")?;

    let mut source = Cursor::new(jpeg);
    let mut dest = Cursor::new(Vec::new());
    builder
        .sign(signer.as_ref(), "image/jpeg", &mut source, &mut dest)
        .map_err(anyhow::Error::new)
        .context("embedding the C2PA manifest into the capture")?;

    Ok(dest.into_inner())
}

/// Derive the credential key from hardware entropy.
fn derive_signing_key(camera_id: Option<String>) -> Result<p256::SecretKey> {
    let entropy = device_signer::HardwareEntropy::new(camera_id)
        .map_err(|e| anyhow::anyhow!("collecting hardware entropy: {e}"))?;
    key_from_entropy(&entropy.get_hardware_id())
}

/// Stretch arbitrary entropy into a valid P-256 secret scalar.
///
/// Split out from [`derive_signing_key`] so it can be tested without depending
/// on whatever hardware the test happens to run on.
fn key_from_entropy(entropy: &[u8]) -> Result<p256::SecretKey> {
    let hk = Hkdf::<Sha256>::new(Some(HKDF_SALT), entropy);

    // A P-256 secret has to land in [1, n-1]. Rejection-sample with a counter
    // rather than reducing mod n, which would bias the low end of the range.
    // Falling through all 64 attempts has probability far below 2^-1000.
    for counter in 0u32..64 {
        let mut okm = [0u8; 32];
        let info: Vec<u8> = [HKDF_INFO, &counter.to_be_bytes()[..]].concat();
        hk.expand(&info, &mut okm)
            .map_err(|e| anyhow::anyhow!("HKDF expand failed: {e}"))?;
        if let Ok(sk) = p256::SecretKey::from_slice(&okm) {
            return Ok(sk);
        }
    }
    bail!("no valid P-256 scalar derived from hardware entropy after 64 attempts")
}

/// Mint the device's self-signed certificate.
fn self_signed_cert(key_pem: &[u8], npub: &str) -> Result<Vec<u8>> {
    let pkey = PKey::private_key_from_pem(key_pem)
        .context("loading the derived key into OpenSSL")?;

    let mut subject = X509NameBuilder::new()?;
    subject.append_entry_by_text("O", "OpenVeil")?;
    subject.append_entry_by_text("OU", "OpenVeil Camera")?;
    // The Nostr identity *is* the subject name: that is where trust resolves,
    // so a verifier reading the cert sees the npub to look up.
    subject.append_entry_by_text("CN", npub)?;
    let subject = subject.build();

    // Serial derived from the subject, so re-minting for the same device is
    // reproducible. The top bit is cleared to keep the DER INTEGER positive.
    let mut serial_bytes: [u8; 16] = Sha256::digest(npub.as_bytes())[..16]
        .try_into()
        .expect("sha256 yields at least 16 bytes");
    serial_bytes[0] &= 0x7f;

    let mut cert = X509Builder::new()?;
    cert.set_version(2)?; // X.509 v3, zero-indexed
    cert.set_subject_name(&subject)?;
    cert.set_issuer_name(&subject)?; // self-signed: issuer == subject
    cert.set_pubkey(&pkey)?;
    let serial = BigNum::from_slice(&serial_bytes)?.to_asn1_integer()?;
    let not_before = Asn1Time::days_from_now(0)?;
    let not_after = Asn1Time::days_from_now(CERT_VALID_DAYS)?;
    cert.set_serial_number(&serial)?;
    cert.set_not_before(&not_before)?;
    cert.set_not_after(&not_after)?;

    // The C2PA certificate profile (spec §14.5) is strict here, and a mistake
    // costs more than it looks: the manifest reads as *invalid* rather than
    // merely untrusted. It wants an end-entity cert (BasicConstraints CA:FALSE),
    // digitalSignature key usage, an EKU that is present and is not
    // `anyExtendedKeyUsage`, and — easy to miss — an authority key identifier.
    cert.append_extension(BasicConstraints::new().critical().build()?)?;
    cert.append_extension(KeyUsage::new().critical().digital_signature().build()?)?;
    cert.append_extension(ExtendedKeyUsage::new().email_protection().build()?)?;

    // Subject first, then authority, in two separate steps. OpenSSL derives
    // the AKI keyid by reading the *issuer's* subject key identifier, and for
    // a self-signed cert the issuer is this same cert — so the SKI must
    // already be appended before the AKI is built, not merely queued up.
    let skid = {
        let ctx = cert.x509v3_context(None, None);
        SubjectKeyIdentifier::new().build(&ctx)?
    };
    cert.append_extension(skid)?;

    let akid = {
        let ctx = cert.x509v3_context(None, None);
        AuthorityKeyIdentifier::new().keyid(true).build(&ctx)?
    };
    cert.append_extension(akid)?;

    cert.sign(&pkey, MessageDigest::sha256())?;
    Ok(cert.build().to_pem()?)
}

/// True when `cert_pem` carries the public half of `key_pem`.
fn cert_matches_key(cert_pem: &[u8], key_pem: &[u8]) -> Result<bool> {
    let cert = X509::from_pem(cert_pem)?;
    let key = PKey::private_key_from_pem(key_pem)?;
    Ok(cert.public_key()?.public_eq(&key))
}

#[cfg(test)]
mod tests {
    use super::*;

    const TEST_ENTROPY: &[u8] = b"test-cpu-serial|test-mac|test-machine-id";
    const TEST_NPUB: &str = "npub1testtesttesttesttesttesttesttesttesttesttesttesttesttestte";

    fn test_identity() -> C2paIdentity {
        let key_pem = key_from_entropy(TEST_ENTROPY)
            .unwrap()
            .to_pkcs8_pem(LineEnding::LF)
            .unwrap()
            .as_bytes()
            .to_vec();
        let cert_pem = self_signed_cert(&key_pem, TEST_NPUB).unwrap();
        C2paIdentity { cert_pem, key_pem }
    }

    fn test_jpeg() -> Vec<u8> {
        let mut img = image::RgbImage::new(64, 48);
        for (x, y, px) in img.enumerate_pixels_mut() {
            *px = image::Rgb([(x * 4) as u8, (y * 5) as u8, 200]);
        }
        let mut buf = Vec::new();
        image::DynamicImage::ImageRgb8(img)
            .write_to(&mut Cursor::new(&mut buf), image::ImageFormat::Jpeg)
            .unwrap();
        buf
    }

    /// The key must be a pure function of the entropy: a device that reboots,
    /// or is reflashed, has to arrive at the same credential identity.
    #[test]
    fn key_derivation_is_deterministic_and_domain_separated() {
        let a = key_from_entropy(TEST_ENTROPY).unwrap();
        let b = key_from_entropy(TEST_ENTROPY).unwrap();
        assert_eq!(a.to_bytes(), b.to_bytes());

        let other = key_from_entropy(b"different-hardware").unwrap();
        assert_ne!(a.to_bytes(), other.to_bytes());
    }

    /// A certificate belongs to exactly one device.
    #[test]
    fn cert_matches_only_its_own_key() {
        let own_pem = key_from_entropy(TEST_ENTROPY)
            .unwrap()
            .to_pkcs8_pem(LineEnding::LF)
            .unwrap()
            .as_bytes()
            .to_vec();
        let cert = self_signed_cert(&own_pem, TEST_NPUB).unwrap();
        assert!(cert_matches_key(&cert, &own_pem).unwrap());

        let foreign_pem = key_from_entropy(b"someone-elses-pi")
            .unwrap()
            .to_pkcs8_pem(LineEnding::LF)
            .unwrap()
            .as_bytes()
            .to_vec();
        assert!(!cert_matches_key(&cert, &foreign_pem).unwrap());
    }

    /// The startup path: mint on first run, reuse afterwards, and refuse a
    /// certificate that belongs to some other device. Exercised here because
    /// this is what `main` actually calls, and a stale cert would otherwise
    /// only show up as manifests that fail to validate downstream.
    #[test]
    fn load_or_create_persists_and_rejects_a_foreign_cert() {
        let dir = std::env::temp_dir().join(format!("openveil-c2pa-test-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);

        let first = C2paIdentity::load_or_create(&dir, Some("e2e".into()), TEST_NPUB).unwrap();
        let cert_path = dir.join("c2pa_cert.pem");
        assert!(cert_path.exists(), "first run should persist a certificate");

        // Second run must reuse what is on disk, byte for byte.
        let second = C2paIdentity::load_or_create(&dir, Some("e2e".into()), TEST_NPUB).unwrap();
        assert_eq!(first.cert_pem(), second.cert_pem());

        // A cert carrying someone else's key is discarded and re-minted rather
        // than trusted, so the identity still signs with this device's key.
        let foreign_key = key_from_entropy(b"someone-elses-pi")
            .unwrap()
            .to_pkcs8_pem(LineEnding::LF)
            .unwrap()
            .as_bytes()
            .to_vec();
        let foreign_cert = self_signed_cert(&foreign_key, TEST_NPUB).unwrap();
        std::fs::write(&cert_path, &foreign_cert).unwrap();

        let third = C2paIdentity::load_or_create(&dir, Some("e2e".into()), TEST_NPUB).unwrap();
        assert_ne!(third.cert_pem(), foreign_cert, "foreign cert should not be kept");

        // Compared by key rather than by bytes: ECDSA signs with a random
        // nonce, so a re-minted certificate never byte-equals its predecessor
        // even when it carries exactly the same key. The key comes from this
        // machine's real entropy, since that is what `load_or_create` uses.
        let own_key = derive_signing_key(Some("e2e".into()))
            .unwrap()
            .to_pkcs8_pem(LineEnding::LF)
            .unwrap()
            .as_bytes()
            .to_vec();
        assert!(cert_matches_key(third.cert_pem(), &own_key).unwrap());

        std::fs::remove_dir_all(&dir).unwrap();
    }

    /// The end-to-end claim: a capture goes in without credentials and comes
    /// out with a manifest the C2PA reader validates, carrying the npub and
    /// the capture action. This is the whole point of the module, so it is
    /// asserted against the real reader rather than against our own structs.
    #[test]
    fn signed_capture_validates_and_carries_the_nostr_binding() {
        let identity = test_identity();
        let original = test_jpeg();

        let info = CaptureInfo {
            npub: TEST_NPUB,
            pubkey_hex: "deadbeef",
            camera_model: Some("imx708"),
            px1_hash: "00112233",
            width: 64,
            height: 48,
        };
        let signed = sign_capture(&original, &info, &identity).unwrap();

        assert_ne!(signed, original, "signing must embed a manifest");

        let reader = c2pa::Reader::default()
            .with_stream("image/jpeg", Cursor::new(&signed))
            .expect("the reader should find the manifest we just embedded");

        // Self-signed, so `Valid` (sound crypto) is the ceiling — `Trusted`
        // would need the C2PA trust list. Anything less means the certificate
        // profile is wrong.
        let state = format!("{:?}", reader.validation_state());
        assert_eq!(state, "Valid", "manifest did not validate: {}", reader.json());

        let json = reader.json();
        assert!(json.contains(NOSTR_ASSERTION), "missing Nostr assertion:\n{json}");
        assert!(json.contains(TEST_NPUB), "missing npub:\n{json}");
        assert!(json.contains("c2pa.created"), "missing capture action:\n{json}");
        assert!(json.contains("digitalCapture"), "missing source type:\n{json}");
    }

    /// px1 hashes decoded pixels, and a manifest is a metadata box, so signing
    /// must leave the px1 binding intact. The Nostr side keys off this.
    #[test]
    fn signing_preserves_the_px1_hash() {
        let identity = test_identity();
        let original = test_jpeg();
        let before = crate::canon::canonicalize_px1(&original).unwrap();

        let info = CaptureInfo {
            npub: TEST_NPUB,
            pubkey_hex: "deadbeef",
            camera_model: None,
            px1_hash: &before.hash_hex,
            width: before.width,
            height: before.height,
        };
        let signed = sign_capture(&original, &info, &identity).unwrap();
        let after = crate::canon::canonicalize_px1(&signed).unwrap();

        assert_eq!(before.hash_hex, after.hash_hex, "embedding a manifest must not touch pixels");
    }
}
