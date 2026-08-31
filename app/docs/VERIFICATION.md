# Verifying an OpenVeil capture

This document is the point of the project. Everything OpenVeil claims should be checkable
by someone who does not trust OpenVeil, its author, or the device that took the photograph.
If a claim here cannot be reproduced from the outside, it is not worth making.

---

## What the verification actually establishes

Read this before the procedure, because a provenance tool that is misunderstood is worse
than one that is absent.

| Question | Answered? | By what |
|---|---|---|
| Are these bytes exactly what was signed? | **Yes** | C2PA hard binding over the file |
| Has the image been edited since capture? | **Yes** | Any edit breaks that binding |
| Is the published file the one the event describes? | **Yes** | SHA-256 in the NIP-94 `x` tag |
| Was it published by the holder of a specific key? | **Yes** | BIP-340 signature on the event |
| Did the image come from a camera sensor? | **Asserted** | `digitalCapture` — a claim by the signer, not a proof |
| *Who* is the photographer? | **No** | Requires a CA-issued certificate; this build has none |
| *Where* was it taken? | **No** | Deliberately never recorded |
| Is the caption true? | **No** | Captions are outside the credential, on purpose |

The distinction between rows five and six is the one people get wrong. A valid Content
Credential proves **integrity** — that the file is unaltered since signing. It proves
**identity** only insofar as the signing certificate is vouched for by someone. In this
proof of concept the certificate is self-generated, so a validator will correctly report
*Valid but not Trusted*. The app displays it that way rather than showing a bare tick.

---

## What you need

A published capture gives you two things: a **Nostr event id** (or an `nevent` link) and a
**Blossom URL**. Either one is enough to start; the event contains the URL.

Tools used below: `curl`, `sha256sum`, and either `node` or `python3`. Optional but useful:
the [`c2patool`](https://github.com/contentauth/c2patool) CLI for manifest inspection.

---

## Step 1 — Fetch the event from a relay

Relays are independent and interchangeable. Ask one you choose, not one OpenVeil chose:

```bash
node -e '
const id = process.argv[1];
const ws = new WebSocket("wss://nos.lol");
ws.onopen = () => ws.send(JSON.stringify(["REQ", "v", { ids: [id] }]));
ws.onmessage = (m) => {
  const d = JSON.parse(m.data);
  if (d[0] === "EVENT") { console.log(JSON.stringify(d[2], null, 2)); ws.close(); }
  if (d[0] === "EOSE") { ws.close(); }
};
' <EVENT_ID>
```

You should see a kind `1063` event with `url`, `m`, `x`, `ox`, `size` and `dim` tags.

## Step 2 — Confirm the event id is genuine

A Nostr event id is the SHA-256 of a canonical serialisation of the event's own fields. If
someone altered any field, the id would no longer match:

```bash
node -e '
const crypto = require("crypto");
const e = JSON.parse(require("fs").readFileSync(0, "utf8"));
const ser = JSON.stringify([0, e.pubkey, e.created_at, e.kind, e.tags, e.content]);
const id = crypto.createHash("sha256").update(ser).digest("hex");
console.log(id === e.id ? "event id MATCHES" : `MISMATCH: ${id}`);
' < event.json
```

Relays verify this themselves and reject events that fail, so an event you received from a
relay has already passed — but checking locally means you do not have to take the relay's
word for it.

## Step 3 — Download the file and re-hash it

This is the load-bearing step. The `x` tag states the SHA-256 of the published file:

```bash
curl -sL "<URL_FROM_EVENT>" -o capture.jpg
sha256sum capture.jpg
```

Compare that digest to the `x` tag. **They must be identical.** If they differ, the file
being served is not the file the event describes, and nothing else matters.

Note that Blossom is content-addressed: the filename in the URL *is* the hash. A server
that wanted to serve you something else would have to serve it at a URL that contradicts
itself — which is exactly why this is checkable rather than a matter of trust.

## Step 4 — Validate the Content Credential

```bash
c2patool capture.jpg
```

Look for two separate things in the output:

- **`validation_results.activeManifest.failure` must be empty.** Entries here mean the
  bytes no longer match the manifest — the image was altered after signing.
- **A `signingCredential.untrusted` status is expected in this build** and does *not* mean
  tampering. It means the certificate chains to no recognised trust list. The integrity
  claim still holds; the identity claim does not.

Conflating those two is the single most damaging mistake a verifier can make, which is why
OpenVeil's own re-verification code separates them explicitly and the UI words them apart.

You should also see:

- a `c2pa.actions` assertion with `c2pa.created` and
  `digitalSourceType: ...digitalCapture`
- a `world.openveil.nostr` assertion naming the publishing key

## Step 5 — Close the loop between file and identity

The chain is only meaningful if both halves point at each other:

```bash
# The manifest names a Nostr public key...
c2patool capture.jpg | grep -A3 'world.openveil.nostr'

# ...and it must equal the pubkey that signed the event.
```

If the `pubkey` inside the C2PA assertion equals the `pubkey` of the Nostr event, then the
image and the publication are bound together. Neither can be substituted independently:
swapping the file breaks the hash in step 3, and swapping the event breaks this match.

---

## Interpreting the result

**All checks pass, certificate untrusted** — the expected outcome for this proof of
concept. You have established that the image is byte-identical to what was signed, that it
has not been altered, and that a specific key published it. You have *not* established who
holds that key.

**Hash mismatch in step 3** — treat the image as unverified. Either the file was modified
or you are being served a different file than the event describes.

**C2PA failure entries in step 4** — the image was altered after signing. This is the case
the whole system exists to detect.

**Event id mismatch in step 2** — the event was tampered with after signing, or was never
valid. A relay should not have accepted it.

---

## Reproducing the pipeline

The repository contains an opt-in integration test that performs a full round trip against
live infrastructure — upload to Blossom, read back, byte-compare, publish to relays, and
confirm acceptance:

```bash
./gradlew :shared:testAndroidHostTest -Dopenveil.liveIntegration=true \
    --tests "com.openveil.LivePipelineIntegrationTest"
```

It is opt-in so that ordinary builds stay hermetic and do not publish to public relays as a
side effect of running the test suite.
