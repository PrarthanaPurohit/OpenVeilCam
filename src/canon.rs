use anyhow::Result;
use image::{DynamicImage, GenericImageView};
use sha2::{Digest, Sha256};

/// NIP-80 `px1` canonicalization identifier.
pub const C14N_PX1: &str = "px1";

pub struct Px1Canonical {
    pub hash_hex: String,
    pub width: u32,
    pub height: u32,
}

/// Canonicalize an encoded still image per NIP-80's `px1`:
/// decode with the reference decoder (ICC profiles and EXIF orientation
/// ignored — the raster is taken exactly as decoded), reduce to 8-bit RGB,
/// then serialize as
/// `"PX1" || uint32_be(width) || uint32_be(height) || 0x03 || row-major RGB8 samples`
/// and SHA-256 that serialization.
///
/// This function *is* the pinned reference decoder this NIP requires: px1's
/// canonical hash is only meaningful to the extent implementations reproduce
/// this exact byte-for-byte behavior.
pub fn canonicalize_px1(encoded: &[u8]) -> Result<Px1Canonical> {
    let img = image::load_from_memory(encoded)?;
    let (width, height) = img.dimensions();
    let rgb = to_rgb8_px1(&img);

    let mut buf = Vec::with_capacity(3 + 4 + 4 + 1 + rgb.len());
    buf.extend_from_slice(b"PX1");
    buf.extend_from_slice(&width.to_be_bytes());
    buf.extend_from_slice(&height.to_be_bytes());
    buf.push(0x03);
    buf.extend_from_slice(&rgb);

    let mut hasher = Sha256::new();
    hasher.update(&buf);
    let hash_hex = hex::encode(hasher.finalize());

    Ok(Px1Canonical { hash_hex, width, height })
}

/// Reduce any `image`-crate color type to row-major RGB8 per px1's rules:
/// 16-bit samples take the high byte (`v >> 8`), grayscale is replicated to
/// RGB, and alpha is dropped without compositing. Handled per-variant (rather
/// than via `DynamicImage::to_rgb8`) so 16-bit reduction matches the spec's
/// truncation rule exactly instead of the crate's own internal scaling.
fn to_rgb8_px1(img: &DynamicImage) -> Vec<u8> {
    let (w, h) = img.dimensions();
    let mut out = Vec::with_capacity((w as usize) * (h as usize) * 3);

    match img {
        DynamicImage::ImageLuma8(buf) => {
            for p in buf.pixels() {
                let v = p.0[0];
                out.extend_from_slice(&[v, v, v]);
            }
        }
        DynamicImage::ImageLumaA8(buf) => {
            for p in buf.pixels() {
                let v = p.0[0];
                out.extend_from_slice(&[v, v, v]);
            }
        }
        DynamicImage::ImageRgb8(buf) => {
            out.extend_from_slice(buf.as_raw());
        }
        DynamicImage::ImageRgba8(buf) => {
            for p in buf.pixels() {
                out.extend_from_slice(&[p.0[0], p.0[1], p.0[2]]);
            }
        }
        DynamicImage::ImageLuma16(buf) => {
            for p in buf.pixels() {
                let v = (p.0[0] >> 8) as u8;
                out.extend_from_slice(&[v, v, v]);
            }
        }
        DynamicImage::ImageLumaA16(buf) => {
            for p in buf.pixels() {
                let v = (p.0[0] >> 8) as u8;
                out.extend_from_slice(&[v, v, v]);
            }
        }
        DynamicImage::ImageRgb16(buf) => {
            for p in buf.pixels() {
                out.extend_from_slice(&[
                    (p.0[0] >> 8) as u8,
                    (p.0[1] >> 8) as u8,
                    (p.0[2] >> 8) as u8,
                ]);
            }
        }
        DynamicImage::ImageRgba16(buf) => {
            for p in buf.pixels() {
                out.extend_from_slice(&[
                    (p.0[0] >> 8) as u8,
                    (p.0[1] >> 8) as u8,
                    (p.0[2] >> 8) as u8,
                ]);
            }
        }
        // Rgb32F/Rgba32F and any future variants: outside px1's defined scope
        // (JPEG baseline/progressive 8-bit, PNG standard color types).
        other => out.extend_from_slice(other.to_rgb8().as_raw()),
    }

    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn px1_is_deterministic_and_matches_dimensions() {
        // 2x1 red/blue PNG, RGB8, no alpha.
        let mut img = image::RgbImage::new(2, 1);
        img.put_pixel(0, 0, image::Rgb([255, 0, 0]));
        img.put_pixel(1, 0, image::Rgb([0, 0, 255]));
        let mut encoded = Vec::new();
        image::DynamicImage::ImageRgb8(img)
            .write_to(&mut std::io::Cursor::new(&mut encoded), image::ImageFormat::Png)
            .unwrap();

        let a = canonicalize_px1(&encoded).unwrap();
        let b = canonicalize_px1(&encoded).unwrap();
        assert_eq!(a.hash_hex, b.hash_hex);
        assert_eq!(a.width, 2);
        assert_eq!(a.height, 1);
    }

    /// px1's actual guarantee, stated as a test so it cannot quietly drift.
    ///
    /// It binds the *decoded raster*, so re-wrapping the same pixels in a
    /// different lossless container leaves the hash alone — that is what makes
    /// it survive EXIF/ICC stripping. It says nothing about lossy re-encoding:
    /// a JPEG pass changes pixels and therefore changes the hash. Anything
    /// claiming to survive platform re-compression needs a perceptual hash or
    /// a watermark, which px1 is not.
    #[test]
    fn px1_binds_pixels_not_containers() {
        let mut img = image::RgbImage::new(8, 8);
        for (x, y, px) in img.enumerate_pixels_mut() {
            *px = image::Rgb([(x * 32) as u8, (y * 32) as u8, 128]);
        }
        let dynamic = image::DynamicImage::ImageRgb8(img);

        let encode = |fmt: image::ImageFormat| {
            let mut buf = Vec::new();
            dynamic
                .write_to(&mut std::io::Cursor::new(&mut buf), fmt)
                .unwrap();
            buf
        };

        let png_once = encode(image::ImageFormat::Png);
        let png_twice = {
            // Decode and re-encode losslessly: different bytes, same raster.
            let decoded = image::load_from_memory(&png_once).unwrap();
            let mut buf = Vec::new();
            decoded
                .write_to(&mut std::io::Cursor::new(&mut buf), image::ImageFormat::Png)
                .unwrap();
            buf
        };

        let a = canonicalize_px1(&png_once).unwrap();
        let b = canonicalize_px1(&png_twice).unwrap();
        assert_eq!(a.hash_hex, b.hash_hex, "lossless re-wrap must preserve px1");

        // Lossy re-encode: px1 does NOT survive, by design and by arithmetic.
        let jpeg = encode(image::ImageFormat::Jpeg);
        let c = canonicalize_px1(&jpeg).unwrap();
        assert_ne!(
            a.hash_hex, c.hash_hex,
            "JPEG re-encode changes pixels, so px1 is expected to differ"
        );
    }
}
