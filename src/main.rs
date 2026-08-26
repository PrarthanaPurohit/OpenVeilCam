use openveil_cam::{c2pa_sign, camera, canon, publisher, signer};

use anyhow::Result;
use std::io::Write;
use tracing::{error, info};
use tracing_subscriber::EnvFilter;

use tokio::io::{AsyncBufReadExt, BufReader};

const CAPTURE_PATH: &str = "/tmp/nostreye_capture.jpg";
/// The capture after a C2PA manifest is embedded. This, not [`CAPTURE_PATH`],
/// is what gets uploaded and published — it is the one carrying the credential.
const SIGNED_PATH: &str = "/tmp/nostreye_capture_c2pa.jpg";
const PROFILE_SENT_FLAG: &str = "/home/prarthana/.hardware_identity/.profile_published";
const ANNOUNCEMENT_SENT_FLAG: &str = "/home/prarthana/.hardware_identity/.announcement_published";

#[tokio::main]
async fn main() -> Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(
            EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("info")),
        )
        .init();

    println!("\n╔══════════════════════════════════════════════════╗");
    println!("║       nostreye-cam  ·  RPi Camera + Nostr        ║");
    println!("╚══════════════════════════════════════════════════╝\n");

    let cameras = camera::list_cameras()?;

    if cameras.is_empty() {
        error!("No cameras found — make sure your camera is connected and rpicam-still works");
        println!("\n[!] No cameras detected. You can still use this session for identity only.\n");
    } else {
        println!("┌─ Detected Cameras ─────────────────────────────────┐");
        for cam in &cameras {
            println!("│  [{:>2}]  {}", cam.index, cam.id);
        }
        println!("└────────────────────────────────────────────────────┘\n");
    }

    info!("Initialising hardware-linked DeviceIdentity…");
    let camera_label = cameras
        .first()
        .map(|c| format!("cam-{}", &c.id.chars().take(12).collect::<String>()));
    let signer = signer::NostreyeSigner::new(camera_label.clone())?;

    println!("┌─ Device Identity ──────────────────────────────────┐");
    println!("│  npub   : {}", signer.npub());
    println!("│  pubkey : {}", signer.pubkey_hex());
    println!("└────────────────────────────────────────────────────┘\n");

    // The C2PA credential identity: a second, P-256 key derived from the same
    // hardware entropy, because C2PA does not permit secp256k1. Set up once at
    // startup so a capture never pays for certificate minting.
    let c2pa_identity = c2pa_sign::C2paIdentity::load_or_create(
        &c2pa_sign::default_identity_dir(),
        camera_label,
        signer.npub(),
    )?;
    println!("┌─ C2PA Credential Identity ─────────────────────────┐");
    println!("│  alg    : ES256 (P-256), derived from device entropy");
    println!("│  subject: {}", signer.npub());
    println!("│  trust  : self-signed — Valid, not C2PA trust-listed");
    println!("└────────────────────────────────────────────────────┘\n");

    if std::path::Path::new(PROFILE_SENT_FLAG).exists() {
        println!("┌─ Profile (kind 0) ───────────────────────────────────┐");
        println!("│  Already published on first run — skipping.");
        println!("└────────────────────────────────────────────────────┘\n");
    } else {
        info!("First run — publishing profile (kind 0) to relays…");
        let profile = signer.sign_metadata(
            "nostreye",
            "Nostreye Camera",
            "RPi camera captures signed and published to Nostr",
            "",
        )?;
        let profile_results = publisher::broadcast_event(&profile, publisher::RELAYS).await;
        let ok_count = profile_results.iter().filter(|(_, a)| *a).count();
        println!("┌─ Profile (kind 0) ───────────────────────────────────┐");
        println!("│  Broadcast to {} relays: {} accepted", profile_results.len(), ok_count);
        println!("└────────────────────────────────────────────────────┘\n");
        if ok_count > 0 {
            let _ = std::fs::write(PROFILE_SENT_FLAG, profile_results.len().to_string());
        }
    }

    if std::path::Path::new(ANNOUNCEMENT_SENT_FLAG).exists() {
        println!("┌─ Device Announcement (kind 11080, NIP-80) ───────────┐");
        println!("│  Already published on first run — skipping.");
        println!("└────────────────────────────────────────────────────┘\n");
    } else {
        info!("First run — publishing NIP-80 device announcement (kind 11080)…");
        let model = cameras.first().map(|c| c.id.as_str());
        let announcement = signer.sign_device_announcement(model)?;
        let announcement_results =
            publisher::broadcast_event(&announcement, publisher::RELAYS).await;
        let ok_count = announcement_results.iter().filter(|(_, a)| *a).count();
        println!("┌─ Device Announcement (kind 11080, NIP-80) ───────────┐");
        println!(
            "│  Broadcast to {} relays: {} accepted",
            announcement_results.len(),
            ok_count
        );
        println!("└────────────────────────────────────────────────────┘\n");
        if ok_count > 0 {
            let _ = std::fs::write(ANNOUNCEMENT_SENT_FLAG, announcement_results.len().to_string());
        }
    }

    println!("Commands:  capture | snap | photo  — take a picture and publish to Nostr");
    println!("           help                    — show this again");
    println!("           quit | exit             — leave\n");

    let camera_index = cameras.first().map(|c| c.index).unwrap_or(0);
    let camera_model = cameras.first().map(|c| c.id.clone());
    let have_camera = !cameras.is_empty();

    let mut stdin = BufReader::new(tokio::io::stdin());
    let mut line = String::new();

    loop {
        print!("nostreye> ");
        std::io::stdout().flush()?;

        line.clear();
        let n = stdin.read_line(&mut line).await?;
        if n == 0 {
            println!();
            break;
        }

        let cmd = line.trim();
        if cmd.is_empty() {
            continue;
        }

        let lower = cmd.to_ascii_lowercase();
        match lower.as_str() {
            "quit" | "exit" | "q" => {
                println!("Goodbye.");
                break;
            }
            "help" | "?" | "h" => {
                println!("  capture | snap | photo  — capture via rpicam-still and publish");
                println!("  help                    — this text");
                println!("  quit | exit             — exit\n");
            }
            "capture" | "snap" | "photo" | "shot" => {
                if !have_camera {
                    println!("[!] No camera — cannot capture.\n");
                    continue;
                }
                if let Err(e) = capture_and_publish(
                    &signer,
                    &c2pa_identity,
                    camera_index,
                    camera_model.as_deref(),
                )
                .await
                {
                    error!("Capture/publish failed: {:#}", e);
                    println!("[!] {}\n", e);
                }
            }
            other => {
                println!("Unknown command {:?}. Type help for commands.\n", other);
            }
        }
    }

    info!("nostreye-cam session ended.");
    Ok(())
}

async fn capture_and_publish(
    signer: &signer::NostreyeSigner,
    c2pa_identity: &c2pa_sign::C2paIdentity,
    camera_index: usize,
    camera_model: Option<&str>,
) -> Result<()> {
    info!("Capturing frame → {}", CAPTURE_PATH);
    let fi = camera::capture_frame(camera_index, CAPTURE_PATH)?;
    println!(
        "✓  Captured {} bytes ({}x{}) → {}",
        fi.file_size, fi.width, fi.height, CAPTURE_PATH
    );

    let jpeg = std::fs::read(CAPTURE_PATH)?;

    // Credential the frame before anything else touches it. px1 is computed
    // from the original, but a manifest is a metadata box rather than a pixel
    // edit, so the same hash describes the signed asset too.
    let px1 = canon::canonicalize_px1(&jpeg)?;
    let info = c2pa_sign::CaptureInfo {
        npub: signer.npub(),
        pubkey_hex: signer.pubkey_hex(),
        camera_model,
        px1_hash: &px1.hash_hex,
        width: px1.width,
        height: px1.height,
    };

    // A field camera that publishes nothing is worse than one that publishes
    // an uncredentialed frame, so a signing failure degrades instead of
    // aborting — but it says so loudly, because the credential is the point.
    let to_publish = match c2pa_sign::sign_capture(&jpeg, &info, c2pa_identity) {
        Ok(signed) => {
            std::fs::write(SIGNED_PATH, &signed)?;
            println!(
                "✓  C2PA credential embedded ({} → {} bytes) → {}",
                jpeg.len(),
                signed.len(),
                SIGNED_PATH
            );
            signed
        }
        Err(e) => {
            error!("C2PA signing failed: {:#}", e);
            println!("[!] C2PA signing FAILED: {e}");
            println!("[!] Publishing this frame WITHOUT Content Credentials.");
            jpeg
        }
    };

    println!("┌─ Publishing to Nostr (NIP-80) ───────────────────────┐");
    match publisher::publish_image(
        &to_publish,
        signer,
        publisher::BLOSSOM_SERVER,
        publisher::RELAYS,
    )
    .await
    {
        Ok(result) => {
            println!("│  Image URL : {}", result.image_url);
            for (relay, ok) in &result.relay_results {
                let status = if *ok { "✓ accepted" } else { "✗ rejected" };
                println!("│  {:14} {}", status, relay);
            }
        }
        Err(e) => {
            error!("Publish failed: {:#}", e);
            println!("│  [!] Publish failed: {}", e);
        }
    }
    println!("└────────────────────────────────────────────────────┘\n");

    Ok(())
}
