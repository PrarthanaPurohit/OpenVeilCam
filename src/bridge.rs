//! Publish an ingested C2PA credential to Nostr + Blossom.
//!
//! Deliberately uses **no new event kinds**. The record is a plain NIP-94
//! (`kind:1063`) file-metadata event carrying namespaced `c2pa-*` tags, so
//! any existing NIP-94 client can read the parts it understands and ignore
//! the rest. Nothing here needs a new NIP to ship.
//!
//! The point of the exercise: a C2PA manifest embedded in an asset dies the
//! moment a platform re-encodes the file. Publishing the manifest separately,
//! keyed by content hash, makes the credential survive its asset.

use anyhow::{Context, Result};
use base64::{engine::general_purpose::STANDARD as B64, Engine};
use sha2::{Digest, Sha256};
use tracing::info;

use crate::c2pa_ingest::C2paSummary;
use crate::canon;
use crate::publisher::{broadcast_event, upload_to_blossom};
use crate::signer::{NostreyeSigner, SignedEvent};

pub struct BridgeResult {
    pub asset_url: Option<String>,
    pub manifest_url: Option<String>,
    pub event: SignedEvent,
    pub relay_results: Vec<(String, bool)>,
    pub px1_hash: String,
    pub file_sha256: String,
}

/// Upload the asset and its manifest to Blossom, then broadcast a NIP-94
/// event binding the two together.
///
/// With `dry_run`, nothing leaves the machine: the event is still built and
/// signed so it can be inspected, but no upload or relay traffic occurs.
pub async fn publish_credential(
    asset: &[u8],
    mime: &str,
    summary: &C2paSummary,
    signer: &NostreyeSigner,
    blossom_server: &str,
    relays: &[&str],
    dry_run: bool,
) -> Result<BridgeResult> {
    let file_sha256 = {
        let mut h = Sha256::new();
        h.update(asset);
        hex::encode(h.finalize())
    };

    // px1 keys off decoded pixels rather than container bytes, so the record
    // stays findable after EXIF/ICC stripping or a container rewrap. It does
    // NOT survive re-compression — see the README's honest scope note.
    let px1 = canon::canonicalize_px1(asset).context("px1 canonicalization failed")?;

    let (asset_url, manifest_url) = if dry_run {
        (None, None)
    } else {
        let asset_auth = signer.sign_blossom_auth(&file_sha256, asset.len() as u64)?;
        info!("Uploading asset ({} bytes) to {}", asset.len(), blossom_server);
        let a_url = upload_to_blossom(asset, &B64.encode(asset_auth.as_bytes()), blossom_server, mime)
            .await
            .context("asset upload to Blossom failed")?;
        info!("Asset → {}", a_url);

        // The detached manifest: this is the part that outlives re-encoding.
        let manifest_bytes = summary.manifest_canonical.as_bytes();
        let manifest_auth =
            signer.sign_blossom_auth(&summary.manifest_sha256, manifest_bytes.len() as u64)?;
        info!("Uploading detached manifest ({} bytes)", manifest_bytes.len());
        let m_url = upload_to_blossom(
            manifest_bytes,
            &B64.encode(manifest_auth.as_bytes()),
            blossom_server,
            "application/json",
        )
        .await
        .context("manifest upload to Blossom failed")?;
        info!("Manifest → {}", m_url);

        (Some(a_url), Some(m_url))
    };

    let mut tags: Vec<Vec<String>> = vec![
        vec!["m".into(), mime.into()],
        vec!["x".into(), file_sha256.clone()],
        vec!["size".into(), asset.len().to_string()],
        vec!["dim".into(), format!("{}x{}", px1.width, px1.height)],
        // Pixel-canonical lookup key. Just a tag — no NIP required.
        vec!["px1".into(), px1.hash_hex.clone()],
        // C2PA verdict as observed at ingest time. Downstream clients are
        // expected to re-verify rather than trust this assertion.
        vec!["c2pa-state".into(), summary.validation_state.clone()],
        vec!["c2pa-manifest-hash".into(), summary.manifest_sha256.clone()],
    ];

    if let Some(url) = &asset_url {
        tags.insert(0, vec!["url".into(), url.clone()]);
    }
    if let Some(url) = &manifest_url {
        tags.push(vec!["c2pa-manifest".into(), url.clone()]);
    }
    if let Some(g) = &summary.claim_generator {
        tags.push(vec!["c2pa-generator".into(), g.clone()]);
    }
    if let Some(i) = &summary.issuer {
        tags.push(vec!["c2pa-issuer".into(), i.clone()]);
    }
    if let Some(t) = &summary.signed_time {
        tags.push(vec!["c2pa-signed-at".into(), t.clone()]);
    }

    let content = match (&summary.claim_generator, &summary.issuer) {
        (Some(g), Some(i)) => format!("C2PA credential from {g}, signed by {i}"),
        (Some(g), None) => format!("C2PA credential from {g}"),
        _ => "C2PA credential".to_string(),
    };

    let event = signer.sign_event(1063, &content, tags)?;
    info!("NIP-94 credential event signed: {}", event.id);

    let relay_results = if dry_run {
        Vec::new()
    } else {
        broadcast_event(&event, relays).await
    };

    Ok(BridgeResult {
        asset_url,
        manifest_url,
        event,
        relay_results,
        px1_hash: px1.hash_hex,
        file_sha256,
    })
}
