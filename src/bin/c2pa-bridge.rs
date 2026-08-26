//! `c2pa-bridge` — ingest a C2PA-signed asset from any device and publish its
//! credential to Nostr + Blossom.
//!
//! No camera, no Raspberry Pi, no signing keys of our own: the input is a file
//! someone else's hardware signed. Run it against a Leica or Pixel capture, or
//! against the C2PA project's own test fixtures.
//!
//!     c2pa-bridge inspect <file>
//!     c2pa-bridge publish <file> [--out <event.json>] [--live]
//!     c2pa-bridge verify  <file> <event.json>
//!
//! `publish` is a dry run unless `--live` is passed.

use anyhow::{bail, Context, Result};
use openveil_cam::{bridge, c2pa_ingest, publisher, signer};
use sha2::{Digest, Sha256};
use std::path::{Path, PathBuf};
use tracing_subscriber::EnvFilter;

fn usage() -> ! {
    eprintln!(
        "c2pa-bridge — publish C2PA Content Credentials to Nostr + Blossom\n\n\
         USAGE:\n  \
           c2pa-bridge inspect <file>                     Read and validate the manifest\n  \
           c2pa-bridge publish <file> [--out F] [--live]  Build the NIP-94 credential event\n  \
           c2pa-bridge verify  <file> <event.json>        Re-derive and check every binding\n\n\
         `publish` is a DRY RUN by default: the event is signed and printed but\n\
         nothing is uploaded and no relay is contacted. Pass --live to publish.\n"
    );
    std::process::exit(2);
}

fn mime_for(path: &Path) -> &'static str {
    match path
        .extension()
        .and_then(|e| e.to_str())
        .map(|e| e.to_ascii_lowercase())
        .as_deref()
    {
        Some("png") => "image/png",
        Some("webp") => "image/webp",
        Some("avif") => "image/avif",
        Some("mp4") => "video/mp4",
        _ => "image/jpeg",
    }
}

fn print_summary(s: &c2pa_ingest::C2paSummary) {
    println!("┌─ C2PA Manifest ──────────────────────────────────────┐");
    println!("│  validation : {}", s.validation_state);
    println!("│  generator  : {}", s.claim_generator.as_deref().unwrap_or("—"));
    println!("│  issuer     : {}", s.issuer.as_deref().unwrap_or("—"));
    println!("│  signed at  : {}", s.signed_time.as_deref().unwrap_or("—"));
    println!("│  label      : {}", s.manifest_label.as_deref().unwrap_or("—"));
    println!("│  manifest#  : {}", s.manifest_sha256);
    println!("└──────────────────────────────────────────────────────┘");
}

/// Rebuild a [`signer::SignedEvent`] from published JSON so its signature can
/// be checked without trusting whoever handed us the file.
fn event_from_json(raw: &str) -> Result<signer::SignedEvent> {
    let v: serde_json::Value = serde_json::from_str(raw).context("event file is not valid JSON")?;
    let field = |k: &str| -> Result<String> {
        v.get(k)
            .and_then(|x| x.as_str())
            .map(str::to_string)
            .ok_or_else(|| anyhow::anyhow!("event JSON missing string field `{k}`"))
    };
    Ok(signer::SignedEvent {
        id: field("id")?,
        pubkey: field("pubkey")?,
        created_at: v
            .get("created_at")
            .and_then(|x| x.as_u64())
            .context("event JSON missing `created_at`")?,
        kind: v.get("kind").and_then(|x| x.as_u64()).context("event JSON missing `kind`")?,
        content: field("content")?,
        sig: field("sig")?,
        json: raw.to_string(),
    })
}

fn tag_value<'a>(v: &'a serde_json::Value, name: &str) -> Option<&'a str> {
    v.get("tags")?.as_array()?.iter().find_map(|t| {
        let arr = t.as_array()?;
        (arr.first()?.as_str()? == name).then(|| arr.get(1)?.as_str())?
    })
}

fn check(label: &str, ok: bool, detail: &str) -> bool {
    println!("│  [{}] {:<22} {}", if ok { "ok" } else { "FAIL" }, label, detail);
    ok
}

fn cmd_verify(asset_path: &Path, event_path: &Path) -> Result<()> {
    let raw = std::fs::read_to_string(event_path)
        .with_context(|| format!("reading {}", event_path.display()))?;
    let event = event_from_json(&raw)?;
    let parsed: serde_json::Value = serde_json::from_str(&raw)?;
    let asset = std::fs::read(asset_path)?;

    println!("┌─ Independent Verification ───────────────────────────┐");
    let mut all = true;

    // 1. The Nostr event is internally consistent and correctly signed.
    let sig_ok = signer::NostreyeSigner::verify_event(&event).unwrap_or(false);
    all &= check("nostr signature", sig_ok, &event.pubkey);

    // 2. The asset really is the one the event describes.
    let file_hash = hex::encode(Sha256::digest(&asset));
    let x_tag = tag_value(&parsed, "x").unwrap_or("");
    all &= check("file sha256", x_tag == file_hash, &file_hash[..32]);

    // 3. The pixel-canonical binding still holds.
    let px1 = openveil_cam::canon::canonicalize_px1(&asset)?;
    let px1_tag = tag_value(&parsed, "px1").unwrap_or("");
    all &= check("px1 binding", px1_tag == px1.hash_hex, &px1.hash_hex[..32]);

    // 4. The C2PA credential validates on *this* machine, from the asset
    //    itself — the event's own `c2pa-state` claim is not trusted.
    match c2pa_ingest::read_manifest(asset_path)? {
        Some(summary) => {
            all &= check("c2pa manifest", summary.is_valid(), &summary.validation_state);
            let m_tag = tag_value(&parsed, "c2pa-manifest-hash").unwrap_or("");
            all &= check(
                "manifest hash",
                m_tag == summary.manifest_sha256,
                &summary.manifest_sha256[..32],
            );
            if !summary.is_trust_listed() {
                println!("│  [--] {:<22} not on C2PA trust list", "trust");
            }
        }
        None => {
            all &= check("c2pa manifest", false, "no manifest in asset");
        }
    }

    println!("└──────────────────────────────────────────────────────┘");
    println!("\n{}", if all { "VERIFIED" } else { "VERIFICATION FAILED" });
    if !all {
        std::process::exit(1);
    }
    Ok(())
}

#[tokio::main]
async fn main() -> Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("warn")))
        .init();

    let args: Vec<String> = std::env::args().skip(1).collect();
    if args.is_empty() {
        usage();
    }

    let cmd = args[0].as_str();
    let path: PathBuf = match args.get(1) {
        Some(p) => PathBuf::from(p),
        None => usage(),
    };
    let live = args.iter().any(|a| a == "--live");
    let out = args
        .iter()
        .position(|a| a == "--out")
        .and_then(|i| args.get(i + 1))
        .map(PathBuf::from);

    if !path.exists() {
        bail!("no such file: {}", path.display());
    }

    if cmd == "verify" {
        let Some(ev) = args.get(2) else { usage() };
        return cmd_verify(&path, Path::new(ev));
    }

    let Some(summary) = c2pa_ingest::read_manifest(&path)
        .with_context(|| format!("ingesting {}", path.display()))?
    else {
        println!("No C2PA manifest found in {}.", path.display());
        println!("This file carries no Content Credentials — nothing to bridge.");
        return Ok(());
    };

    match cmd {
        "inspect" => {
            print_summary(&summary);
            println!("\nManifest JSON:\n{}", summary.manifest_json_raw);
            Ok(())
        }
        "publish" => {
            print_summary(&summary);

            let asset = std::fs::read(&path)?;
            let mime = mime_for(&path);

            // Our own Nostr identity — used only to attest "I observed this
            // credential", never to make provenance claims about the capture.
            let sk = signer::NostreyeSigner::new(None)
                .context("initialising device identity for the publishing key")?;
            println!("\nPublishing as: {}", sk.npub());
            if !live {
                println!("(dry run — pass --live to upload and broadcast)");
            }

            let res = bridge::publish_credential(
                &asset,
                mime,
                &summary,
                &sk,
                publisher::BLOSSOM_SERVER,
                publisher::RELAYS,
                !live,
            )
            .await?;

            println!("\n┌─ Bridged Record ─────────────────────────────────────┐");
            println!("│  file sha256 : {}", res.file_sha256);
            println!("│  px1 hash    : {}", res.px1_hash);
            println!("│  asset URL   : {}", res.asset_url.as_deref().unwrap_or("(dry run)"));
            println!("│  manifest URL: {}", res.manifest_url.as_deref().unwrap_or("(dry run)"));
            println!("│  event id    : {}", res.event.id);
            for (relay, ok) in &res.relay_results {
                println!("│  {:>10} {}", if *ok { "accepted" } else { "rejected" }, relay);
            }
            println!("└──────────────────────────────────────────────────────┘");

            if let Some(out) = out {
                std::fs::write(&out, &res.event.json)?;
                println!("\nEvent written to {}", out.display());
            } else {
                println!("\nNIP-94 event:\n{}", res.event.json);
            }
            Ok(())
        }
        _ => usage(),
    }
}
