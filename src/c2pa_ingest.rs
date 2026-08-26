//! Ingest C2PA Content Credentials from an asset produced by *any* device.
//!
//! This is deliberately read-only: OpenVeil does not mint C2PA manifests and
//! does not own the signing keys involved. A Leica, a Pixel, or the C2PA
//! project's own test fixtures all arrive here the same way. What OpenVeil
//! adds is the distribution layer — see [`crate::bridge`].

use anyhow::{Context as _, Result};
use c2pa::Reader;
use sha2::{Digest, Sha256};
use std::path::Path;

/// Everything OpenVeil needs from an asset's C2PA manifest store in order to
/// publish a detached, independently checkable record of it.
#[derive(Debug, Clone)]
pub struct C2paSummary {
    /// Overall verdict: `Invalid`, `Valid`, or `Trusted`.
    ///
    /// `Valid` means the cryptography checks out. `Trusted` additionally means
    /// the signing cert chains to the C2PA trust list. OpenVeil treats `Valid`
    /// as sufficient and resolves trust against Nostr identities instead —
    /// that distinction is the whole architectural argument.
    pub validation_state: String,
    /// Software/hardware that produced the claim, e.g. `"Adobe Photoshop"`.
    pub claim_generator: Option<String>,
    /// Certificate subject that signed the manifest.
    pub issuer: Option<String>,
    /// Certificate common name, often more readable than the issuer DN.
    pub common_name: Option<String>,
    /// RFC3339 signing time, when a trusted timestamp is present.
    pub signed_time: Option<String>,
    /// Label of the active manifest within the store.
    pub manifest_label: Option<String>,
    /// Human-readable asset title recorded in the manifest.
    pub title: Option<String>,
    /// The SDK's manifest JSON exactly as produced, for human inspection.
    ///
    /// Not suitable for hashing — see [`Self::manifest_canonical`].
    pub manifest_json_raw: String,
    /// Canonical manifest JSON: verifier-local `validation_*` keys removed and
    /// every object key sorted recursively. This is what gets published, and
    /// what [`Self::manifest_sha256`] covers.
    pub manifest_canonical: String,
    /// SHA-256 over `manifest_canonical` — the Nostr-side lookup key.
    pub manifest_sha256: String,
}

impl C2paSummary {
    /// True when the SDK considers the manifest cryptographically sound,
    /// whether or not it chains to the official C2PA trust list.
    pub fn is_valid(&self) -> bool {
        matches!(self.validation_state.as_str(), "Valid" | "Trusted")
    }

    /// True only when the signer chains to a C2PA trust-list root.
    pub fn is_trust_listed(&self) -> bool {
        self.validation_state == "Trusted"
    }
}

/// Read and validate the C2PA manifest store embedded in `path`.
///
/// Returns `Ok(None)` when the file simply carries no manifest, which is the
/// common case and not an error — the caller decides whether to proceed.
pub fn read_manifest(path: &Path) -> Result<Option<C2paSummary>> {
    // `Reader::default()` uses the default `Context`; `Reader::from_file` is
    // deprecated in 0.90 in favour of context-carrying constructors.
    let reader = match Reader::default().with_file(path) {
        Ok(r) => r,
        // No manifest present is an expected outcome, not a failure.
        Err(c2pa::Error::JumbfNotFound) => return Ok(None),
        Err(e) => {
            return Err(anyhow::Error::new(e))
                .with_context(|| format!("reading C2PA manifest from {}", path.display()))
        }
    };

    let manifest_json_raw = reader.json();
    let manifest_canonical = canonicalize_manifest(&manifest_json_raw)
        .context("canonicalizing manifest JSON")?;
    let manifest_sha256 = {
        let mut h = Sha256::new();
        h.update(manifest_canonical.as_bytes());
        hex::encode(h.finalize())
    };

    let validation_state = format!("{:?}", reader.validation_state());

    let active = reader.active_manifest();

    let claim_generator = active.and_then(|m| {
        m.claim_generator_info.as_ref().and_then(|infos| {
            infos.first().map(|i| match &i.version {
                Some(v) => format!("{} {}", i.name, v),
                None => i.name.clone(),
            })
        })
    });

    let manifest_label = active.and_then(|m| m.label()).map(str::to_string);
    let title = active.and_then(|m| m.title()).map(str::to_string);

    let sig = active.and_then(|m| m.signature_info());
    let issuer = sig.and_then(|s| s.issuer.clone());
    let common_name = sig.and_then(|s| s.common_name.clone());
    let signed_time = sig.and_then(|s| s.time.clone());

    Ok(Some(C2paSummary {
        validation_state,
        claim_generator,
        issuer,
        common_name,
        signed_time,
        manifest_label,
        title,
        manifest_json_raw,
        manifest_canonical,
        manifest_sha256,
    }))
}

/// Produce a stable, verifier-independent serialization of a manifest store.
///
/// Two things make the SDK's own `Reader::json()` unusable as a hash input:
///
/// 1. **Key order is not stable.** Vendor-specific entries inside
///    `claim_generator_info` (`com.adobe.aca-version`, `org.cai.c2pa_rs`, …)
///    come out of a hash map, so their order changes between runs of the same
///    binary on the same file. Observed directly, not theorised.
/// 2. **`validation_*` keys describe the verifier, not the asset.** Whether a
///    signer reads as `signingCredential.untrusted` depends on which trust list
///    the local machine has configured, so including those fields would make
///    two honest verifiers disagree about the hash of the same credential.
///
/// Dropping the validation keys and sorting the rest recursively fixes both.
fn canonicalize_manifest(raw: &str) -> Result<String> {
    let mut value: serde_json::Value = serde_json::from_str(raw)?;
    if let Some(obj) = value.as_object_mut() {
        obj.retain(|k, _| !k.starts_with("validation"));
    }
    Ok(canonical_json(&value))
}

/// Recursively serialize with object keys in sorted order.
///
/// Done by hand rather than relying on `serde_json`'s map type: whether that
/// preserves insertion order or sorts depends on the `preserve_order` feature,
/// which any crate in the dependency graph can turn on via feature unification.
fn canonical_json(value: &serde_json::Value) -> String {
    match value {
        serde_json::Value::Object(map) => {
            let mut keys: Vec<&String> = map.keys().collect();
            keys.sort();
            let fields: Vec<String> = keys
                .into_iter()
                .map(|k| {
                    let key = serde_json::Value::String(k.clone()).to_string();
                    format!("{}:{}", key, canonical_json(&map[k]))
                })
                .collect();
            format!("{{{}}}", fields.join(","))
        }
        serde_json::Value::Array(items) => {
            let elems: Vec<String> = items.iter().map(canonical_json).collect();
            format!("[{}]", elems.join(","))
        }
        scalar => scalar.to_string(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn canonical_json_sorts_keys_regardless_of_input_order() {
        let a = r#"{"z":1,"a":{"y":2,"b":3}}"#;
        let b = r#"{"a":{"b":3,"y":2},"z":1}"#;
        assert_eq!(canonicalize_manifest(a).unwrap(), canonicalize_manifest(b).unwrap());
    }

    #[test]
    fn canonical_json_drops_verifier_local_validation_keys() {
        let with = r#"{"manifests":{},"validation_state":"Valid","validation_status":[1]}"#;
        let without = r#"{"manifests":{}}"#;
        assert_eq!(
            canonicalize_manifest(with).unwrap(),
            canonicalize_manifest(without).unwrap()
        );
    }
}
