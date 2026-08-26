use anyhow::{Context, Result};
use base64::{engine::general_purpose::STANDARD as B64, Engine};
use device_signer::identity::DeviceIdentity;
use nostr::{EventId, PublicKey, Tag};
use sha2::Digest;
use tracing::info;

/// NIP-80 domain-separation prefixes (from the NIP's *title*, so they never
/// change even if the NIP number does).
const M_BIND_DOMAIN: &[u8] = b"hamp/binding/v1";
const M_CAP_DOMAIN: &[u8] = b"hamp/capture/v1";

/// The hardware ("ECDSA") key info for a NIP-80 `kind:11080` Device
/// Announcement's `hardware_key` object.
pub struct HardwareKeyInfo {
    pub alg: &'static str,
    pub pubkey_b64: String,
    pub binding_sig_b64: String,
}

pub struct NostreyeSigner {
    identity: DeviceIdentity,
}

impl NostreyeSigner {
    pub fn new(camera_id: Option<String>) -> Result<Self> {
        let identity =
            DeviceIdentity::new(camera_id).context("Failed to initialise DeviceIdentity")?;
        info!("DeviceIdentity initialised");
        info!("  Nostr pubkey (hex) : {}", identity.info.nostr_pubkey_hex);
        info!("  npub               : {}", identity.info.nostr_npub);
        info!("  Ethereum address   : {}", identity.info.eth_address);
        Ok(Self { identity })
    }

    /// Return the device's Nostr public key in `npub1…` bech32 format.
    pub fn npub(&self) -> &str {
        &self.identity.info.nostr_npub
    }

    /// Return the device's Nostr public key as a lowercase hex string.
    pub fn pubkey_hex(&self) -> &str {
        &self.identity.info.nostr_pubkey_hex
    }

    /// Sign a NIP-01 kind 0 metadata (profile) event.
    /// Content is JSON: name, display_name, about, picture.
    pub fn sign_metadata(&self, name: &str, display_name: &str, about: &str, picture: &str) -> Result<SignedEvent> {
        let content = serde_json::json!({
            "name": name,
            "display_name": display_name,
            "about": about,
            "picture": picture,
        });
        self.sign_event(0, &serde_json::to_string(&content)?, vec![])
    }

    pub fn sign_text_note(&self, content: &str, extra_tags: Vec<Tag>) -> Result<SignedEvent> {
        //  Build the serialised event commitment (NIP-01 §4) 
        let created_at = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_secs();

        let pubkey_hex = &self.identity.info.nostr_pubkey_hex;

        // Serialise tags to JSON array
        let tags_json: Vec<serde_json::Value> = extra_tags
            .iter()
            .map(|t| {

                let tag_str = format!("{:?}", t); 
                serde_json::Value::Array(vec![serde_json::Value::String(tag_str)])
            })
            .collect();

        let commitment = serde_json::json!([
            0,
            pubkey_hex,
            created_at,
            1,        
            tags_json,
            content,
        ]);
        let commitment_str = serde_json::to_string(&commitment)?;

        // Compute event ID = SHA-256(commitment) 
        let mut hasher = sha2::Sha256::new();
        hasher.update(commitment_str.as_bytes());
        let event_id_bytes: [u8; 32] = hasher.finalize().into();
        let event_id_hex = hex::encode(event_id_bytes);

        // Sign with device Schnorr key 
        let sig_hex = self
            .identity
            .sign_nostr_event(&event_id_bytes)
            .map_err(|e| anyhow::anyhow!("Schnorr signing failed: {:?}", e))?;

        info!("Signed Nostr event");
        info!("  event_id : {}", event_id_hex);
        info!("  sig      : {}…", &sig_hex[..16]);

        let event_json = serde_json::json!({
            "id":         event_id_hex,
            "pubkey":     pubkey_hex,
            "created_at": created_at,
            "kind":       1,
            "tags":       serde_json::Value::Array(vec![]),
            "content":    content,
            "sig":        sig_hex,
        });

        Ok(SignedEvent {
            id: event_id_hex,
            pubkey: pubkey_hex.clone(),
            created_at,
            kind: 1,
            content: content.to_string(),
            sig: sig_hex,
            json: serde_json::to_string_pretty(&event_json)?,
        })
    }

    /// Generic NIP-01 event signer for any `kind`.
    /// `tags` is a list of tag arrays, e.g. `[["url", "https://…"], ["m", "image/jpeg"]]`.
    pub fn sign_event(&self, kind: u64, content: &str, tags: Vec<Vec<String>>) -> Result<SignedEvent> {
        let created_at = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_secs();
        self.sign_event_at(kind, content, tags, created_at)
    }

    /// Same as [`sign_event`](Self::sign_event) but with an explicit
    /// `created_at`, needed wherever another signature (e.g. NIP-80 `hwsig`)
    /// must bind the exact same timestamp as the event.
    pub fn sign_event_at(
        &self,
        kind: u64,
        content: &str,
        tags: Vec<Vec<String>>,
        created_at: u64,
    ) -> Result<SignedEvent> {
        let pubkey_hex = &self.identity.info.nostr_pubkey_hex;

        let tags_json: Vec<serde_json::Value> = tags
            .iter()
            .map(|t| {
                serde_json::Value::Array(
                    t.iter().map(|s| serde_json::Value::String(s.clone())).collect(),
                )
            })
            .collect();

        let commitment = serde_json::json!([0, pubkey_hex, created_at, kind, tags_json, content]);
        let commitment_str = serde_json::to_string(&commitment)?;

        let mut hasher = sha2::Sha256::new();
        hasher.update(commitment_str.as_bytes());
        let event_id_bytes: [u8; 32] = hasher.finalize().into();
        let event_id_hex = hex::encode(event_id_bytes);

        let sig_hex = self
            .identity
            .sign_nostr_event(&event_id_bytes)
            .map_err(|e| anyhow::anyhow!("Schnorr signing failed: {:?}", e))?;

        info!("Signed event kind={} id={}", kind, event_id_hex);

        let event_json = serde_json::json!({
            "id":         event_id_hex,
            "pubkey":     pubkey_hex,
            "created_at": created_at,
            "kind":       kind,
            "tags":       serde_json::Value::Array(tags_json),
            "content":    content,
            "sig":        sig_hex,
        });

        Ok(SignedEvent {
            id: event_id_hex,
            pubkey: pubkey_hex.clone(),
            created_at,
            kind,
            content: content.to_string(),
            sig: sig_hex,
            json: serde_json::to_string_pretty(&event_json)?,
        })
    }

    /// Sign a NIP-80 `kind:11080` Device Announcement (replaceable).
    /// `model` is the camera's reported model string, if known.
    pub fn sign_device_announcement(&self, model: Option<&str>) -> Result<SignedEvent> {
        let hw = self.hardware_key_info()?;

        let mut content = serde_json::Map::new();
        if let Some(m) = model {
            content.insert("model".to_string(), serde_json::Value::String(m.to_string()));
        }
        content.insert(
            "hardware_key".to_string(),
            serde_json::json!({
                "alg": hw.alg,
                "pubkey": hw.pubkey_b64,
                "binding_sig": hw.binding_sig_b64,
            }),
        );

        self.sign_event(
            11080,
            &serde_json::to_string(&serde_json::Value::Object(content))?,
            vec![],
        )
    }

    /// Sign a NIP-80 `kind:1080` Capture Attestation. `canonical_hash` is the
    /// `px1` (or other `c14n`) canonical hash of the media; `file_hash` is
    /// the SHA-256 of the exact encoded bytes; `urls` are fetch locations.
    pub fn sign_capture_attestation(
        &self,
        canonical_hash: &[u8; 32],
        file_hash: &[u8; 32],
        c14n: &str,
        mime: &str,
        dim: Option<(u32, u32)>,
        urls: &[String],
    ) -> Result<SignedEvent> {
        let created_at = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_secs();

        let hwsig = self.sign_hwsig(c14n, canonical_hash, created_at)?;

        let mut tags = vec![
            vec!["x".to_string(), hex::encode(canonical_hash), "canonical".to_string()],
            vec!["x".to_string(), hex::encode(file_hash), "file".to_string()],
            vec!["c14n".to_string(), c14n.to_string()],
            vec!["m".to_string(), mime.to_string()],
        ];
        if let Some((w, h)) = dim {
            tags.push(vec!["dim".to_string(), format!("{}x{}", w, h)]);
        }
        for url in urls {
            tags.push(vec!["url".to_string(), url.clone()]);
        }
        tags.push(vec!["hwsig".to_string(), hwsig]);

        self.sign_event_at(1080, "{}", tags, created_at)
    }

    /// Build and sign a Blossom upload-auth event (kind 24242, BUD-01).
    /// Returns compact JSON suitable for base64-encoding into the Authorization header.
    pub fn sign_blossom_auth(&self, sha256_hex: &str, size: u64) -> Result<String> {
        let expiry = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_secs()
            + 600;

        let tags = vec![
            vec!["t".to_string(), "upload".to_string()],
            vec!["x".to_string(), sha256_hex.to_string()],
            vec!["size".to_string(), size.to_string()],
            vec!["expiration".to_string(), expiry.to_string()],
        ];

        let event = self.sign_event(24242, "Upload image", tags)?;
        // Blossom requires compact (non-pretty) JSON
        let obj: serde_json::Value = serde_json::from_str(&event.json)?;
        Ok(serde_json::to_string(&obj)?)
    }

    /// Hardware-key info for a NIP-80 `kind:11080` Device Announcement:
    /// `alg`, the SEC1-compressed secp256k1 pubkey, and a `binding_sig` over
    /// `M_bind = "hamp/binding/v1" || device pubkey`, binding this ECDSA key
    /// to the device's Nostr identity.
    ///
    /// `device-signer` currently derives this ECDSA key and the Nostr
    /// Schnorr key from the same seed, so today this carries no root of
    /// trust independent of the device key itself — it becomes meaningful
    /// once backed by a real secure element (e.g. an ATECC608).
    pub fn hardware_key_info(&self) -> Result<HardwareKeyInfo> {
        let pubkey_bytes = hex::decode(&self.identity.info.eth_pubkey_hex)
            .context("invalid hardware pubkey hex")?;
        let device_pubkey_bytes = hex::decode(&self.identity.info.nostr_pubkey_hex)
            .context("invalid device pubkey hex")?;

        let mut m_bind = M_BIND_DOMAIN.to_vec();
        m_bind.extend_from_slice(&device_pubkey_bytes);
        let mut hasher = sha2::Sha256::new();
        hasher.update(&m_bind);
        let digest: [u8; 32] = hasher.finalize().into();

        let sig_hex = self
            .identity
            .sign_hash_ecdsa(&digest)
            .map_err(|e| anyhow::anyhow!("hardware-key binding signature failed: {:?}", e))?;
        let sig_bytes = hex::decode(&sig_hex).context("invalid binding signature hex")?;

        Ok(HardwareKeyInfo {
            alg: "ES256K",
            pubkey_b64: B64.encode(&pubkey_bytes),
            binding_sig_b64: B64.encode(&sig_bytes),
        })
    }

    /// Sign `M_cap = "hamp/capture/v1" || c14n || 0x00 || canonical_hash || uint64_be(created_at)`
    /// with the hardware ECDSA key, returning base64 `hwsig` for a
    /// NIP-80 `kind:1080` Capture Attestation.
    pub fn sign_hwsig(&self, c14n: &str, canonical_hash: &[u8; 32], created_at: u64) -> Result<String> {
        let mut m_cap = M_CAP_DOMAIN.to_vec();
        m_cap.extend_from_slice(c14n.as_bytes());
        m_cap.push(0x00);
        m_cap.extend_from_slice(canonical_hash);
        m_cap.extend_from_slice(&created_at.to_be_bytes());

        let mut hasher = sha2::Sha256::new();
        hasher.update(&m_cap);
        let digest: [u8; 32] = hasher.finalize().into();

        let sig_hex = self
            .identity
            .sign_hash_ecdsa(&digest)
            .map_err(|e| anyhow::anyhow!("hwsig signing failed: {:?}", e))?;
        let sig_bytes = hex::decode(&sig_hex).context("invalid hwsig hex")?;

        Ok(B64.encode(&sig_bytes))
    }

    /// Verify that a [`SignedEvent`]'s `sig` was produced by its claimed
    pub fn verify_event(event: &SignedEvent) -> Result<bool> {
        // Tags are part of the NIP-01 id commitment, so they have to be read
        // back off the event. Assuming an empty tag array here made this
        // return `false` for every tagged event — i.e. every NIP-94 record.
        let parsed: serde_json::Value =
            serde_json::from_str(&event.json).context("SignedEvent.json was not valid JSON")?;
        let tags = parsed
            .get("tags")
            .cloned()
            .unwrap_or_else(|| serde_json::Value::Array(vec![]));

        // Recompute event ID commitment
        let commitment = serde_json::json!([
            0,
            event.pubkey,
            event.created_at,
            event.kind,
            tags,
            event.content,
        ]);
        let commitment_str = serde_json::to_string(&commitment)?;
        let mut hasher = sha2::Sha256::new();
        hasher.update(commitment_str.as_bytes());
        let computed_id: [u8; 32] = hasher.finalize().into();
        let computed_id_hex = hex::encode(computed_id);

        if computed_id_hex != event.id {
            return Ok(false);
        }

        // Use nostr crate to verify Schnorr signature
        let pubkey = PublicKey::from_hex(&event.pubkey)
            .map_err(|e| anyhow::anyhow!("Invalid pubkey: {}", e))?;
        let event_id = EventId::from_hex(&event.id)
            .map_err(|e| anyhow::anyhow!("Invalid event id: {}", e))?;

        let sig_bytes = hex::decode(&event.sig).context("Invalid signature hex")?;
        let schnorr_sig = nostr::secp256k1::schnorr::Signature::from_slice(&sig_bytes)
            .map_err(|e| anyhow::anyhow!("Invalid signature bytes: {}", e))?;

        let secp = nostr::secp256k1::Secp256k1::new();
        let msg = nostr::secp256k1::Message::from_digest(*event_id.as_bytes());
        let xonly = nostr::secp256k1::XOnlyPublicKey::from_slice(pubkey.as_bytes())
            .unwrap_or_else(|_| nostr::secp256k1::XOnlyPublicKey::from_slice(&pubkey.to_bytes()).unwrap());

        match secp.verify_schnorr(&schnorr_sig, &msg, &xonly) {
            Ok(()) => Ok(true),
            Err(_) => Ok(false),
        }
    }
}

#[derive(Debug, Clone)]
pub struct SignedEvent {
    pub id: String,
    pub pubkey: String,
    pub created_at: u64,
    pub kind: u64,
    pub content: String,
    pub sig: String,
    pub json: String,
}
