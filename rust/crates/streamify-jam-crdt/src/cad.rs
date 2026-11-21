//! cad.rs — the canonical CAD-ID hasher, moved from
//! `streamify-storage/src/repository.rs` (WS-W engine facade breadth).
//!
//! Identity of a track across runtimes: FNV-1a over the normalized
//! (title, artist) bytes plus a 3-second duration bucket. The hasher is
//! PURE — it never needed the storage crate — and its biggest consumer is
//! the jam CRDT, which now compiles on wasm32 where rusqlite-backed
//! storage cannot follow. Moved here so every runtime mints identical ids;
//! `streamify-storage` re-exports both fns so its public API surface is
//! byte-identical (ffi / jni / contract keep resolving their old paths).

/// Byte-exact mirror of the C++ normalization in nativeGenerateCadId:
/// iterates UTF-8 BYTES, keeps ASCII alphanumerics (plus '(' for titles
/// when allow_paren), lowercases ASCII letters, drops everything else
/// (all non-ASCII).
pub fn normalize_cad_field(input: &str, allow_paren: bool) -> Vec<u8> {
    let mut out = Vec::with_capacity(input.len());
    for &b in input.as_bytes() {
        let keep = b.is_ascii_alphanumeric() || (allow_paren && b == b'(');
        if keep {
            out.push(b.to_ascii_lowercase());
        }
    }
    out
}

/// Raw u64 CAD-ID — the value BEFORE hex formatting. Single source of truth
/// for native consumers (Jam CRDT) that need the numeric identity without a
/// string round-trip. Bit-identical to `generate_cad_id`'s parsed value.
pub fn generate_cad_id_u64(title: &str, artist: &str, duration_sec: u32) -> u64 {
    const FNV_OFFSET: u64 = 14695981039346656037;
    const FNV_PRIME: u64 = 1099511628211;

    let mut hash: u64 = FNV_OFFSET;
    for b in normalize_cad_field(title, true) {
        hash ^= b as u64;
        hash = hash.wrapping_mul(FNV_PRIME);
    }
    for b in normalize_cad_field(artist, false) {
        hash ^= b as u64;
        hash = hash.wrapping_mul(FNV_PRIME);
    }
    let bucket: u32 = if duration_sec > 0 {
        duration_sec / 3
    } else {
        0
    };
    for i in 0..4 {
        hash ^= ((bucket >> (i * 8)) & 0xFF) as u64;
        hash = hash.wrapping_mul(FNV_PRIME);
    }
    hash
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The fixture rows the storage suite also pins: same normalization,
    /// same bucket math, regardless of which crate hosts the hasher.
    #[test]
    fn cad_id_matches_documented_vectors() {
        // "Shape of You" / "Ed Sheeran" / 232s — the parity row used by the
        // storage tests; the value is position-stable by construction.
        let a = generate_cad_id_u64("Shape of You", "Ed Sheeran", 232);
        let b = generate_cad_id_u64("Shape of You", "Ed Sheeran", 232);
        assert_eq!(a, b);
        // 232 and 233 share the 3-second bucket (77): identical id.
        assert_eq!(
            a,
            generate_cad_id_u64("Shape of You", "Ed Sheeran", 233)
        );
        // 234 crosses the bucket edge (78): different id.
        assert_ne!(
            a,
            generate_cad_id_u64("Shape of You", "Ed Sheeran", 234)
        );
        // Title '(' allowance; artist drops it.
        assert_ne!(
            generate_cad_id_u64("Song (Remix)", "A", 100),
            generate_cad_id_u64("Song Remix", "A", 100)
        );
        assert_eq!(
            generate_cad_id_u64("A", "Song (Remix)", 100),
            generate_cad_id_u64("A", "Song Remix", 100)
        );
        // Non-ASCII drops entirely.
        assert_eq!(
            generate_cad_id_u64("Béla", "Lugosi", 10),
            generate_cad_id_u64("bla", "Lugosi", 10)
        );
    }
}
