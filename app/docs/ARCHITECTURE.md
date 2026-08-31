# Architecture

How OpenVeil is put together, and — more usefully — *why*, since most of these decisions
are only defensible once you know what they are protecting against.

---

## Module layout

```
shared/       Domain and data. Platform-neutral API surface.
composeApp/   Compose Multiplatform UI. Depends on shared.
androidApp/   Android application host. Depends on both.
```

The three-module split is **forced**: since AGP 9, applying `com.android.application`
together with the Kotlin Multiplatform plugin is a hard error, so the application module
cannot itself be multiplatform. The two library modules use
`com.android.kotlin.multiplatform.library`.

It happens to be the right shape anyway. `shared` exposes exactly one assembled object:

```kotlin
class OpenVeilCore(
    val publishPhotoUseCase: PublishPhotoUseCase,
    val identityRepository: NostrIdentityRepository,
    val c2paService: C2paService,
    val fileStorage: FileStorage,
)
```

Ktor, the C2PA JNI classes, the Android Keystore and the secp256k1 bindings all stay behind
it. The UI module cannot reference an `HttpClient` even by accident, because it is not on
its compile classpath. That turns "no networking types in the UI layer" from a convention
people remember into a fact the compiler enforces.

### Package map (`shared`)

| Package | Responsibility |
|---|---|
| `crypto` | SHA-256, hex, base64url, bech32, CSPRNG. `expect`/`actual` per platform. |
| `nostr` | NIP-01 events, NIP-19 identifiers, NIP-94 tags, relay client, device identity |
| `blossom` | BUD-01/02 upload and retrieval, BUD-11 authorization |
| `c2pa` | Content Credential signing and verification (Android implementation) |
| `publish` | `PublishPhotoUseCase` — the pipeline itself |
| `storage` | Keystore-wrapped secrets, signed-master file storage |
| `domain` | Models and service interfaces. No implementations. |
| `di` | Hand-wired object graph |

Dependency injection is explicit constructor wiring rather than a container. The graph is a
handful of singletons, and being able to read in one screenful exactly which objects touch
the private key is worth more here than the indirection a container would buy.

---

## The publish pipeline

`PublishPhotoUseCase` emits a `Flow<PublishJob>`, one emission per state transition, which
the UI renders directly rather than animating a guess.

```
CAPTURED
   │  sign()  ── runs eagerly at the shutter, not at Publish
   ▼
C2PA_SIGNING ──► C2PA_SIGNED
   │  publish()
   ▼
UPLOADING_BLOSSOM ──► BLOSSOM_UPLOADED
   │
   ▼
PUBLISHING_NOSTR ──► PUBLISHED
                          │
   any stage ──► FAILED ──┘  (retry resumes; it does not restart)
```

### Ordering guarantees

Two orderings are load-bearing and must not be changed:

1. **The hash is taken after signing, over the signed bytes.** Hashing the capture instead
   would publish a fingerprint that does not match the file anyone downloads, and every
   downstream verification would fail.
2. **The signed master is written to disk before the upload begins.** A crash mid-upload
   must not lose the only copy of a photograph that cannot be retaken — the scene is gone.

### Idempotency

Each stage is guarded by the state already recorded on the `Photo`:

- `signed != null` → skip signing
- `blossomUrl != null` → skip upload
- `nostrEventId != null` → skip publish

This is what makes "Retry publication" after a relay failure avoid re-uploading several
megabytes Blossom already holds, and what stops a retry putting a duplicate event on relays
that already accepted one.

### Eager signing

Signing starts when the shutter fires, not when Publish is pressed, so the cost is absorbed
while the user is reading the review screen. The review checklist therefore reflects real
state — the Content Credentials row flips on its own — rather than being decoration. Publish
stays disabled until signing actually returns.

The consequence is that abandoning a capture must delete the signed master. `discard()`
cancels the signing job with `cancelAndJoin` *before* deleting, because cancellation is
cooperative and a job mid-write would otherwise recreate the file moments after removal.

### Relay success criterion

Nostr has no consensus: an event exists once any relay holds it. Publishing succeeds if **at
least one** relay returns `OK`. Requiring all of them would let the least reliable relay
define failure — and during development, two of the five default relays returned HTTP 503
for an extended period while the other three accepted every event.

---

## The invariant everything rests on

> The bytes that come out of the camera encoder are signed, hashed, uploaded and published
> **without ever being decoded and re-encoded.**

A C2PA manifest is hard-bound to an exact byte sequence. Decoding to a bitmap and
re-encoding produces a visually identical file whose credential no longer validates. So:

- Capture reads the JPEG buffer verbatim out of the `ImageProxy`.
- Signing streams bytes in and out of memory (`DataStream` → `ByteArrayStream`).
- Image **orientation is handled through the EXIF tag, never by rotating pixels.**
- `decodeImageForDisplay` is display-only and is documented as such; its output is never
  fed back into the pipeline.

---

## Platform boundaries

`expect`/`actual` is used only where a platform genuinely differs:

| Declaration | Android | iOS |
|---|---|---|
| `sha256` | `MessageDigest` | `CommonCrypto` |
| `secureRandomBytes` | `java.security.SecureRandom` | `SecRandomCopyBytes` |
| `CameraService` | CameraX | *not implemented* |
| `C2paService` | `c2pa-android` (JNI) | *not implemented* |
| `SecureStorage` | Keystore-wrapped AES-GCM | *not implemented* |
| `decodeImageForDisplay` | `BitmapFactory` + EXIF | *not implemented* |

iOS `actual` declarations exist and return typed failures rather than throwing, so an iOS
build compiles and reports the gap through the normal error path instead of crashing.

`secureRandomBytes` is deliberately **not** `kotlin.random.Random`, which is a deterministic
PRNG seeded from the clock and would make generated private keys predictable.

---

## Testing strategy

Tests concentrate where an error is silent rather than loud. A wrong colour is obvious; a
wrong byte in a canonical serialisation produces an event id that every relay rejects with
no useful diagnostic.

**Cross-checked against independent implementations.** The NIP-01 and NIP-19 encoders are
verified against reference implementations written separately from the spec (Python and
JavaScript respectively), not against themselves. An encoder tested only against its own
output passes just as happily with a wrong TLV layout.

**Live integration is opt-in.** `LivePipelineIntegrationTest` talks to real Blossom servers
and real relays, gated behind `-Dopenveil.liveIntegration=true` so ordinary builds stay
hermetic and running the suite never publishes to public relays as a side effect.

It also uses `runBlocking` rather than `runTest`, deliberately: `runTest` drives a virtual
clock, so any `withTimeoutOrNull` around real socket I/O expires the instant the coroutine
suspends. Under `runTest` every relay reported "timed out" in under a second while being
entirely reachable.

**Native library alignment is checked in CI.** `tools/check_16kb_alignment.py` reads the ELF
program headers of every 64-bit `.so` in the APK. This is not hypothetical: secp256k1-kmp
below 0.19.0 shipped 4 KB-aligned libraries, which Google Play rejects for Android 15+
targets, and this project was affected until the dependency was upgraded.

---

## Build tooling

Two generators keep bundled assets honest, and both are re-runnable:

- `tools/subset_icons.py` downloads the Material Symbols variable font, subsets it to the
  37 icons actually referenced, pins the variable axes, and **generates the `OpenVeilIcon`
  enum** so an icon that is not in the subset cannot be referenced from Kotlin. 10.6 MB
  becomes ~13 KB across two weights.
- `tools/build_text_fonts.py` pins Hanken Grotesk and JetBrains Mono to the weights the type
  scale uses and subsets them to Latin plus the punctuation the UI needs.
- `tools/generate-dev-cert.sh` produces the development C2PA identity. See
  [SECURITY.md](SECURITY.md) for why it emits a two-certificate chain rather than a
  self-signed leaf.
