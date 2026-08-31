# Security and threat model

OpenVeil is built for people for whom a photograph is evidence and a phone is a liability:
journalists, human rights investigators, and witnesses. That assumption drives the defaults
below. Where a convenience and a safety property conflict, this project chooses the safety
property and says so.

---

## Who this is for, and what they are up against

The assumed user may face:

- **Device seizure or search.** Anything readable on the phone can be read by someone else.
- **Coerced disclosure.** They may be compelled to unlock the device.
- **Deniability attacks.** An adversary claiming their genuine photograph is fabricated.
- **Deplatforming.** A hosting provider pressured into removing the material.

The last one is why publication is decentralised rather than to a service OpenVeil controls:
a verifier with a business model can be leaned on.

---

## What the current build protects, and what it does not

| Property | Status |
|---|---|
| Tamper-evidence for the image bytes | **Yes** — C2PA hard binding |
| Independent verifiability without trusting us | **Yes** — see [VERIFICATION.md](VERIFICATION.md) |
| Resistance to single-provider takedown | **Partial** — multiple relays; Blossom fallback |
| No location leakage | **Yes** — by construction, see below |
| Private key resists offline extraction | **Yes** — hardware-backed Keystore wrapping |
| Proof of *who* the photographer is | **No** — development certificate only |
| Protection against a compromised device | **No** — out of scope, as it is for any app |
| Anonymity of the publisher | **No** — an npub is a persistent pseudonym |

That last row deserves emphasis. Every capture from a device is published under the same
key, so captures are **linkable to each other**. That is the point — a body of work under
one identity is what makes a reputation — but it also means a user who needs unlinkable
publications is not served by this build.

---

## Deliberate design decisions

### No location, ever

The app does not declare `ACCESS_FINE_LOCATION` or `ACCESS_COARSE_LOCATION`, does not read
GPS, and writes no location assertion into the manifest.

This is a *structural* choice rather than a toggle. Publishing to Nostr is irreversible and
public, and a precise coordinate attached to a photograph is the single most harmful thing
this pipeline could leak. A setting that defaults to off can be flipped on by accident, by a
future contributor, or by a well-meaning "add EXIF passthrough" change. A permission that is
never requested cannot be misconfigured.

### The private key never leaves the device

The Nostr secret key is generated on-device from a platform CSPRNG and sealed with an
AES-256-GCM key held in the Android Keystore. The Keystore key is non-extractable, so the
ciphertext in `SharedPreferences` is useless without the device — copying the app's data
directory off the phone does not yield the key.

`setUserAuthenticationRequired(false)` is deliberate: publishing must work from a background
retry after the screen has locked, and the threat being defended against is *offline
extraction of app data*, not an attacker holding an already-unlocked phone.

### Failure to read a key is fatal, not recoverable

If a key exists but cannot be decrypted, `SecureStorage.getBytes` throws
`SecureStorageUnreadable` rather than returning `null`.

This matters more than it looks. Callers naturally treat `null` as "first run, generate
one" — so collapsing the two cases would silently mint a **new identity over the top of the
old one**, orphaning everything the user had ever published, with no way back. For someone
whose npub is their reputation, that is data loss disguised as a fresh start.

### Discarded captures are erased

Retaking or closing the review screen deletes the signed master from app storage. A photo
the user explicitly rejected must not stay readable on a device that may be searched. The
deletion happens after `cancelAndJoin` rather than a bare `cancel`, because cancellation is
cooperative and an in-flight signing job would otherwise recreate the file.

### Captions are outside the credential

A caption is published in the Nostr event, never in the C2PA manifest.

The manifest attests a provenance fact — these pixels came off a sensor, unaltered. A
caption is an editorial claim no cryptography can check. Binding it into the credential that
renders as "Verified" would invite readers to treat the words as verified too, which is
precisely the confusion this product exists to prevent. The caption is still authenticated:
it sits inside an event signed by the device key, so it is provably from that npub.

The UI states this to the user directly, and warns that Nostr deletion (NIP-09) is advisory
— a published note is effectively permanent.

### Nothing sensitive is logged

No private key, authorization header, or token is written to logs. C2PA failures are logged
at warning level with the library's own message, which describes the manifest or the
certificate and never echoes key material.

---

## The development signing identity

**The certificate this project ships with is not a trust anchor, and the app says so.**

`tools/generate-dev-cert.sh` produces a two-certificate chain: a local root CA and an
end-entity signing certificate issued by it. The chain structure is not cosmetic —
`c2pa-rs` rejects a bare self-signed certificate with `Signature: the certificate is
invalid`, because the C2PA certificate profile requires a true end-entity certificate
(`CA:FALSE`, issued by someone else) rather than one that is simultaneously its own issuer.

The generated leaf carries exactly the extensions C2PA requires:

```
basicConstraints  = critical, CA:FALSE
keyUsage          = critical, digitalSignature, nonRepudiation
extendedKeyUsage  = critical, emailProtection
```

The root chains to nothing anyone recognises, so validators report captures as **Valid but
not Trusted**: the tamper-evidence is real and independently checkable, but nothing vouches
for who signed. The app reports this distinction in words rather than showing an unqualified
green tick.

### Why the key is not in version control

Both halves are git-ignored. A signing key must never be committed — but note that the
*certificate* is excluded too, because a tracked certificate with an untracked key is worse
than neither: it looks usable and is not. They are generated as a matched pair, so a fresh
clone has no signing identity until the script is run. That is a deliberate speed bump, not
an oversight.

### What production requires

1. A **CA-issued certificate** from an authority on the C2PA trust list.
2. A **hardware-held private key**, reached through `Signer.withCallback` so the key never
   enters the process address space.
3. Release APKs signed with a real upload key rather than the debug key.

The `C2paSigningIdentity` interface exists so that (1) and (2) drop in without touching
`AndroidC2paService`.

---

## Supply chain

- Native libraries are verified to be **16 KB page aligned** on every push
  (`tools/check_16kb_alignment.py`), which is both a Play Store requirement for Android 15+
  and a canary for an unexpected dependency change.
- Dependency versions are pinned in a single version catalog (`gradle/libs.versions.toml`)
  with comments recording *why* a version is pinned where a naive upgrade would break.
- The C2PA implementation is the official Content Authenticity Initiative SDK, a JNI wrapper
  over `c2pa-rs`, rather than a reimplementation.
- Cryptography uses `secp256k1-kmp` (bindings to the audited libsecp256k1) rather than
  hand-rolled elliptic curve code. A pure-Kotlin BigInteger implementation was considered to
  avoid the native dependency and rejected: BigInteger is not constant-time, and BIP-340's
  deterministic nonces make timing leakage a private-key recovery risk.

---

## Reporting a vulnerability

Please open a security advisory through GitHub's private reporting rather than a public
issue, so a fix can be prepared before disclosure.
