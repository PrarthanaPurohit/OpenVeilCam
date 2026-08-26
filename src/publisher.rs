use anyhow::{anyhow, Context, Result};
use base64::{engine::general_purpose::STANDARD as B64, Engine};
use futures_util::{SinkExt, StreamExt};
use sha2::Digest;
use tokio_tungstenite::{connect_async, tungstenite::Message};
use tracing::{info, warn};

use crate::canon;
use crate::signer::{NostreyeSigner, SignedEvent};

/// Public Blossom server and Nostr relays used by default.
pub const BLOSSOM_SERVER: &str = "https://blossom.band";
pub const RELAYS: &[&str] = &[
    "wss://relay.damus.io",
    "wss://nos.lol",
    "wss://relay.primal.net",
    "wss://relay.snort.social",
    "wss://nostr.mom",
];

pub struct PublishResult {
    pub image_url: String,
    /// Per-relay result: (relay_url, accepted)
    pub relay_results: Vec<(String, bool)>,
}

/// Broadcast a signed event to all relays. Returns per-relay (url, accepted).
pub async fn broadcast_event(event: &SignedEvent, relays: &[&str]) -> Vec<(String, bool)> {
    let mut results = Vec::new();
    for &relay in relays {
        match publish_to_relay(event, relay).await {
            Ok(accepted) => {
                info!("Relay {} → {}", relay, if accepted { "accepted" } else { "rejected" });
                results.push((relay.to_string(), accepted));
            }
            Err(e) => {
                warn!("Relay {} error: {:#}", relay, e);
                results.push((relay.to_string(), false));
            }
        }
    }
    results
}

/// Upload `jpeg_data` to Blossom, sign a NIP-80 `kind:1080` Capture
/// Attestation, a NIP-94 (`kind:1063`) event pointing at it via `imeta`, and
/// a plain `kind:1` note, then broadcast all three to `relays`.
pub async fn publish_image(
    jpeg_data: &[u8],
    signer: &NostreyeSigner,
    blossom_server: &str,
    relays: &[&str],
) -> Result<PublishResult> {
    // SHA-256 of the exact encoded bytes — NIP-80 `file` hash, NIP-94 `x` tag,
    // and Blossom auth all key off this.
    let file_hash: [u8; 32] = {
        let mut h = sha2::Sha256::new();
        h.update(jpeg_data);
        h.finalize().into()
    };
    let file_hash_hex = hex::encode(file_hash);

    // NIP-80 `px1` canonical hash — keys off decoded pixel content, not
    // container bytes.
    let canon = canon::canonicalize_px1(jpeg_data).context("px1 canonicalization failed")?;
    let canonical_hash: [u8; 32] = hex::decode(&canon.hash_hex)
        .context("invalid canonical hash hex")?
        .try_into()
        .map_err(|_| anyhow!("canonical hash was not 32 bytes"))?;

    // Build Blossom auth event (kind 24242) and upload
    let auth_json = signer.sign_blossom_auth(&file_hash_hex, jpeg_data.len() as u64)?;
    let auth_b64 = B64.encode(auth_json.as_bytes());

    info!("Uploading {} bytes to {}", jpeg_data.len(), blossom_server);
    let image_url = upload_to_blossom(jpeg_data, &auth_b64, blossom_server, "image/jpeg").await?;
    info!("Blossom upload OK → {}", image_url);

    // NIP-80 kind:1080 Capture Attestation
    let attestation = signer.sign_capture_attestation(
        &canonical_hash,
        &file_hash,
        canon::C14N_PX1,
        "image/jpeg",
        Some((canon.width, canon.height)),
        std::slice::from_ref(&image_url),
    )?;
    info!("NIP-80 kind:1080 capture attestation signed: {}", attestation.id);

    // NIP-94 (kind 1063) event, pointing at the attestation via `imeta`
    // (NIP-92 imeta: one tag, each subsequent element a "key value" string).
    let imeta_tag = vec![
        "imeta".to_string(),
        format!("url {}", image_url),
        "m image/jpeg".to_string(),
        format!("x {}", file_hash_hex),
        format!("dim {}x{}", canon.width, canon.height),
        format!("attestation {}", attestation.id),
    ];
    let content = "📷 nostreye capture".to_string();
    let tags = vec![
        vec!["url".to_string(), image_url.clone()],
        vec!["m".to_string(), "image/jpeg".to_string()],
        vec!["x".to_string(), file_hash_hex.clone()],
        vec!["size".to_string(), jpeg_data.len().to_string()],
        vec!["dim".to_string(), format!("{}x{}", canon.width, canon.height)],
        imeta_tag,
    ];
    let nip94 = signer.sign_event(1063, &content, tags)?;
    info!("NIP-94 event signed: {}", nip94.id);

    // Kind 1 with image URL — visible in normal clients (Damus, Primal, Snort)
    let note_content = format!("📷 nostreye capture\n\n{}", image_url);
    let note = signer.sign_event(1, &note_content, vec![])?;
    info!("Kind 1 image post signed: {}", note.id);

    // Broadcast kind:1080, kind:1063, and kind:1
    let r_attestation = broadcast_event(&attestation, relays).await;
    let r1 = broadcast_event(&note, relays).await;
    let r2 = broadcast_event(&nip94, relays).await;
    // Use kind 1 results for display (all three should match; take first non-empty)
    let relay_results = if !r1.is_empty() {
        r1
    } else if !r_attestation.is_empty() {
        r_attestation
    } else {
        r2
    };

    Ok(PublishResult { image_url, relay_results })
}

/// PUT arbitrary bytes to a Blossom server and return the URL.
pub async fn upload_to_blossom(
    data: &[u8],
    auth_b64: &str,
    server: &str,
    mime: &str,
) -> Result<String> {
    let client = reqwest::Client::builder()
        .timeout(std::time::Duration::from_secs(120))
        .build()?;

    let url = format!("{}/upload", server.trim_end_matches('/'));
    let resp = client
        .put(&url)
        .header("Authorization", format!("Nostr {}", auth_b64))
        .header("Content-Type", mime)
        .body(data.to_vec())
        .send()
        .await
        .context("Blossom PUT request failed")?;

    let status = resp.status();
    let body: serde_json::Value = resp
        .json()
        .await
        .context("Blossom response was not JSON")?;

    if !status.is_success() {
        return Err(anyhow!("Blossom HTTP {}: {}", status, body));
    }

    body["url"]
        .as_str()
        .map(|s| s.to_string())
        .ok_or_else(|| anyhow!("No 'url' field in blossom response: {}", body))
}

/// Connect to a Nostr relay via WebSocket, send the event, and wait for an OK.
async fn publish_to_relay(event: &SignedEvent, relay_url: &str) -> Result<bool> {
    let event_obj: serde_json::Value =
        serde_json::from_str(&event.json).context("Failed to parse event JSON")?;
    let wire = serde_json::to_string(&serde_json::json!(["EVENT", event_obj]))?;

    let (mut ws, _) = connect_async(relay_url)
        .await
        .with_context(|| format!("WebSocket connect to {} failed", relay_url))?;

    ws.send(Message::Text(wire)).await.context("WS send failed")?;

    let accepted = tokio::time::timeout(std::time::Duration::from_secs(10), async {
        while let Some(frame) = ws.next().await {
            let text = match frame.context("WS read error")? {
                Message::Text(t) => t,
                Message::Close(_) => break,
                _ => continue,
            };
            if let Ok(serde_json::Value::Array(fields)) = serde_json::from_str(&text) {
                if fields.first().and_then(|v| v.as_str()) == Some("OK") {
                    return Ok(fields.get(2).and_then(|v| v.as_bool()).unwrap_or(false));
                }
            }
        }
        Err(anyhow!("Relay closed without OK"))
    })
    .await
    .context("Relay response timed out")??;

    ws.close(None).await.ok();
    Ok(accepted)
}
