# OpenVeilCam

[openveil.world](https://openveil.world)

OpenVeil is a set of cameras that prove their photographs were not altered, and publish
that proof somewhere no single party can quietly withdraw it. Every capture leaves the
device carrying a C2PA manifest bound to its exact bytes, is stored content-addressed on
Blossom, and is announced in a signed NIP-94 event on Nostr relays.

This repository holds two implementations of that idea, sharing one protocol design:

| Component | Platform | |
|---|---|---|
| **`openveil-cam`** | Raspberry Pi | Rust. Fixed-install camera. Repository root. |
| **`app/`** | Android today; iOS, desktop and web planned | Kotlin Multiplatform. See [app/README.md](app/README.md). |

Both publish to the same relays and the same Blossom servers under the same event kinds,
so a capture from either is discoverable and verifiable the same way.

<img width="1917" height="857" alt="Image" src="https://github.com/user-attachments/assets/b72174b0-a326-46ad-a4ab-741baaa1cafa" />

<img width="1918" height="853" alt="Image" src="https://github.com/user-attachments/assets/14742acb-9d38-443b-8576-c809ad18f5c5" />

<img width="1912" height="856" alt="Image" src="https://github.com/user-attachments/assets/c4a2df72-b8fe-49ef-b2ff-574563c57e4f" />

## Content Credentials on capture (`openveil-cam`)

Every frame the Pi captures leaves with a real C2PA manifest embedded in it,
signed by the device. Any C2PA tool — `c2patool`, Adobe's Verify, the browser
extensions — reads it without knowing that Nostr exists.

The manifest asserts `c2pa.created` with a `digitalCapture` source type: these
pixels came off a sensor, not out of a generator or an editor. Alongside it
sits a `world.openveil.nostr` assertion carrying the device's npub, its hex
pubkey, and the `px1` hash.

### The two keys

The Nostr identity is secp256k1. C2PA does not permit that curve — the spec
allows only the NIST P-curves, RSA-PSS and Ed25519 — so the credential is
signed by a **separate P-256 key derived from the same hardware entropy**. Both
keys are re-derived on every run and neither is written to disk; only the
self-signed certificate is persisted, at `~/.hardware_identity/c2pa_cert.pem`.

The npub is the certificate's subject *and* is repeated inside the manifest,
while the NIP-94 event published afterwards points back at the asset. The
binding holds from either direction, so losing one does not break the other.

### Why it reads `Valid` and not `Trusted`

The certificate is self-signed, so validators report `Valid` — the signature is
cryptographically sound — but not `Trusted`, which would require chaining to
the C2PA trust list via their Conformance Program. That is the intended
posture, and the same argument the bridge makes below: cryptographic validity
comes from C2PA, trust comes from the Nostr identity.

Embedding a manifest adds a JUMBF box without touching pixels, so the `px1`
hash is identical before and after signing — the Nostr side can key off it
either way. Pinned by `c2pa_sign::tests::signing_preserves_the_px1_hash`.

## C2PA bridge (`c2pa-bridge`)

Where the section above *mints* credentials for this device's own captures,
the bridge *distributes* credentials minted by someone else's hardware.

Content Credentials ([C2PA](https://c2pa.org)) already ship in hardware from
Leica, Nikon, Canon, Fujifilm and Panasonic. OpenVeil does not re-implement
that. What C2PA lacks is somewhere decentralized to *put* a manifest: the
credential is embedded in the asset, so a platform re-encode destroys it, and
C2PA's own remedy — a remote manifest URL — points at a single HTTP endpoint.

`c2pa-bridge` is the distribution layer. It ingests an asset signed by any
C2PA-capable device (no keys of our own involved), stores the asset and a
detached copy of its manifest on Blossom, and publishes a Nostr event binding
them together. It needs no camera and no Raspberry Pi.

```bash
# Read and validate a manifest
cargo run --bin c2pa-bridge -- inspect photo.jpg

# Build the credential event (dry run: nothing is uploaded or broadcast)
cargo run --bin c2pa-bridge -- publish photo.jpg --out event.json

# Actually upload to Blossom and broadcast to relays
cargo run --bin c2pa-bridge -- publish photo.jpg --live

# Re-derive every binding from the asset, trusting nothing in the event
cargo run --bin c2pa-bridge -- verify photo.jpg event.json
```

### The event

A plain NIP-94 (`kind:1063`) file-metadata event with namespaced `c2pa-*`
tags. **No new event kinds and no new NIP** — unknown tags are ignored by
clients that do not understand them, so this ships without ratifying anything.

| Tag | Meaning |
|---|---|
| `url`, `m`, `x`, `size`, `dim` | Standard NIP-94 fields |
| `px1` | Pixel-canonical hash (see scope below) |
| `c2pa-state` | `Valid` / `Trusted` / `Invalid` as observed at ingest |
| `c2pa-manifest` | Blossom URL of the detached manifest |
| `c2pa-manifest-hash` | SHA-256 of the canonical manifest JSON |
| `c2pa-generator`, `c2pa-issuer`, `c2pa-signed-at` | Signer metadata |

`c2pa-state` is a convenience, not an authority: `verify` re-validates the
manifest locally and ignores what the event claims.

### Trust model

C2PA's "Trusted" state requires the signing certificate to chain to the
official C2PA Trust List via its Conformance Program — a centralized PKI.
OpenVeil treats `Valid` (cryptographically sound) as sufficient and resolves
*trust* against Nostr identities instead, so provenance does not depend on a
corporate CA. `verify` reports trust-list status separately rather than
conflating it with validity.

### What `px1` does and does not do

`px1` hashes the decoded raster rather than the file bytes, so it survives
EXIF/ICC stripping and lossless container rewrapping — which is what makes a
detached manifest findable after metadata is stripped. It does **not** survive
lossy re-encoding, resizing or cropping; surviving platform re-compression
needs a perceptual hash or watermark, which px1 is not. Both properties are
pinned by `canon::tests::px1_binds_pixels_not_containers`.

## Project Structure

The Rust crate lives at the repository root; the multiplatform application is a
self-contained Gradle build under `app/`. They share no build system and are developed
independently, so `cargo build` at the root behaves exactly as it always has.

```
.
├── src/, Cargo.toml, deploy.sh   Raspberry Pi firmware (Rust)
├── app/                          Kotlin Multiplatform application
│   ├── shared/                     domain and protocol logic, platform-neutral
│   ├── composeApp/                 Compose Multiplatform UI
│   └── androidApp/                 Android host  (iOS, desktop and web to follow)
└── .github/workflows/            Path-filtered CI: app changes do not trigger
                                  firmware builds, and vice versa
```

- `OpenVeilCam`: A Rust application that discovers the Pi camera, captures stills to JPEG, embeds a device-signed C2PA manifest, generates cryptographic signatures via hardware-linked identity (`OpenVeilSigner`), and publishes to Nostr via Blossom and relays.
- `c2pa-bridge`: Publishes Content Credentials minted by *other* devices. Needs no camera.
- `deploy.sh`: Deploys and builds `OpenVeilCam` from your development environment to a Raspberry Pi over SSH.
- `app/`: The handheld client. Same pipeline, same protocol. Signs at the shutter, uploads
  to Blossom, publishes NIP-94, and can re-verify a capture against its stored bytes on
  device. Android runs today; the domain layer is already platform-neutral so iOS, desktop
  and web are additive. Documentation, including how a third party verifies a capture
  without trusting this project, is in [app/docs/](app/docs/).

## Prerequisites

**Target device (Raspberry Pi):**
- Compatible camera module (e.g. IMX708 on RPi 5)
- Rust toolchain (`rustc`, `cargo`)

Network connectivity is required for publishing images to Nostr.

## Deployment

Edit the `RPI` variable in `deploy.sh` to match your device's SSH address, then:

```bash
./deploy.sh
```

This copies source files and runs `cargo build` on the Pi.

## Usage

On the Raspberry Pi:

```bash
cargo run
```

### Process flow

Capture uses the Pi’s standard still capture CLI (`rpicam-still` on `PATH`, e.g. from Raspberry Pi OS `rpicam-apps` / `libcamera-apps`).

1. **Detect cameras** — Lists cameras and parses the tool’s output.
2. **Capture** — Writes a JPEG to `/tmp/nostreye_capture.jpg` (1920×1080, quality 95).
3. **Device identity** — Initialises hardware-linked identity (secp256k1) using `device-signer`.
4. **Profile (kind 0)** — Signs and broadcasts a metadata event so your npub shows a profile (name, display_name, about) across Nostr clients. Sent first so relays have the profile before any other events.
5. **Content Credentials** — Embeds a device-signed C2PA manifest and writes the result to `/tmp/nostreye_capture_c2pa.jpg`. From here on it is the *signed* file that gets hashed, uploaded and published. If signing fails the run continues on the unsigned frame and says so loudly, since a camera that publishes nothing is worse than one that publishes an uncredentialed frame.
6. **Frame integrity** — Computes ECDSA signature over the JPEG bytes (attestation).
7. **Publish** — Uploads the image to Blossom (BUD-01 auth), then broadcasts:
   - **Kind 1** — Text note with the image URL (visible in Damus, Primal, Snort, etc.).
   - **Kind 1063** — NIP-94 file-metadata event with URL, SHA256, dimensions, and ECDSA attestation.
   Relays: `relay.damus.io`, `nos.lol`, `relay.primal.net`, `relay.snort.social`, `nostr.mom`.

### Viewing the captured image

Copy from the Pi to your machine:

```bash
scp user@<rpi-ip>:/tmp/nostreye_capture_c2pa.jpg .
```

That is the credentialed file — drop it into [Content Credentials Verify](https://contentcredentials.org/verify) or run `cargo run --bin c2pa-bridge -- inspect` on it. The unsigned original stays at `/tmp/nostreye_capture.jpg`.

### Viewing in Nostr clients

Add your npub (printed at startup) to Damus, Primal, Snort, or any Nostr client. The profile (kind 0) and image posts (kind 1 with URL) will appear in your feed and on your profile page.

### Keys and nsec

The device uses `device-signer` to derive a deterministic secp256k1 key from hardware entropy (CPU serial, MAC, machine ID) and a persisted salt. The secret is derived on demand for signing and **is not exported as nsec** — this is by design to avoid leaking the key.

**What you can see:**
- **npub** — Printed at startup (`Device Identity`). Use this to follow your device from any Nostr client.
- **pubkey (hex)** — Same key in hex; useful for relay filters and event lookups.

**If you need nsec** (e.g. to import into Damus, Primal, or another client):
- `device-signer` does not expose the raw secret. The key lives only in memory during signing.
- Options: extend [device-signer](https://github.com/prarthanapurohit/device-signer) to add an `nsec()` or `export_secret()` method (requires changing the crate’s API and accepting the security tradeoff), or use a separate software-backed key for Nostr and keep the hardware identity only for attestation.

## Core libraries

- **device-signer** — Hardware-linked identity; Schnorr and ECDSA signing.
- **c2pa** — Content Credentials: minting manifests on capture, reading them in the bridge.
- **p256 / openssl** — The ES256 credential key and its self-signed certificate.
- **nostr** — Event building and verification.
- **reqwest** — HTTP client (Blossom upload).
- **tokio-tungstenite** — WebSocket client (relay publish).
- **tokio** — Async runtime.
