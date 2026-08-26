//! OpenVeil — provenance publishing for Nostr.
//!
//! The crate is split so that capture (Raspberry Pi, `camera`) is independent
//! of publishing (`publisher`) and of the two provenance halves: `c2pa_sign`
//! mints Content Credentials for what this device captured, `c2pa_ingest`
//! reads credentials other devices minted. The `c2pa-bridge` binary uses
//! everything *except* `camera` and `c2pa_sign`, so it runs on any machine
//! against files produced by any C2PA-capable device.

pub mod bridge;
pub mod c2pa_ingest;
pub mod c2pa_sign;
pub mod camera;
pub mod canon;
pub mod publisher;
pub mod signer;
