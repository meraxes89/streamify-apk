// Clippy: FFI entry points in this module all follow the house safety
// model (catch_unwind shields, sentinel returns, no panics across the
// boundary — see the module docs); per-function `# Safety` boilerplate
// would be copy-paste.
#![allow(clippy::missing_safety_doc)]
use chacha20poly1305::aead::{Aead, KeyInit, Payload};
use chacha20poly1305::{ChaCha20Poly1305, Key, Nonce};
use std::panic::catch_unwind;

// ─── SEC-06: audio-cache chunk cipher ────────────────────────────────────
//
// FORMAT HISTORY
//   v1 (legacy): position-dependent XOR stream (`crypt_audio_chunk`) —
//     deterministic, unauthenticated, keystream recoverable from one
//     known plaintext chunk (lead plan R1.2). Survives ONLY as the
//     read path for caches written by builds ≤ 2026-11.
//   v2 (SEC-06): per-chunk ChaCha20-Poly1305 AEAD. Envelope:
//       [12 B random nonce][ciphertext][16 B Poly1305 tag]
//     The AEAD key is BLAKE3-derive_key'd from the caller's cache key
//     (context "streamify-cache-v2-aead"); the chunk id is bound as
//     associated data so chunks cannot be reordered or replayed across
//     positions. Sealed output is 28 bytes longer than the plaintext.
//
// Read-old/write-new migration policy mirrors the storage vault
// (see streamify-storage/src/crypto.rs and docs/architecture/CRYPTO_SPEC.md):
// existing v1 caches stay readable through `crypt_audio_chunk`; every
// fresh write uses `rust_seal_cache_chunk` / `rust_open_cache_chunk`.

/// BLAKE3 KDF context for the v2 cache AEAD key.
const CACHE_V2_KEY_CONTEXT: &str = "streamify-cache-v2-aead";
/// v2 envelope overhead: 12-byte nonce + 16-byte tag.
const CACHE_V2_OVERHEAD: usize = 12 + 16;

fn derive_cache_key(key: &[u8]) -> [u8; 32] {
    blake3::derive_key(CACHE_V2_KEY_CONTEXT, key)
}

/// SEC-06: seal one audio-cache chunk with ChaCha20-Poly1305.
///
/// Writes the v2 envelope `[nonce ‖ ct ‖ tag]` to `output_ptr` and
/// returns the sealed length (`input_len + 28`), or a negative sentinel:
///   -1 null/zero args · -2 capacity · -3 seal failure · -2 also on
///   non-representable lengths (see checks) · -99 on panic.
#[no_mangle]
pub unsafe extern "C" fn rust_seal_cache_chunk(
    input_ptr: *const u8,
    input_len: usize,
    output_ptr: *mut u8,
    output_cap: usize,
    key_ptr: *const u8,
    key_len: usize,
    chunk_id: u64,
) -> i64 {
    let result = catch_unwind(|| {
        if input_ptr.is_null() || output_ptr.is_null() || key_ptr.is_null() || key_len == 0 {
            return Ok(-1);
        }
        if input_len == 0 {
            return Ok(-2); // nothing to seal; a zero-length chunk has no v2 envelope
        }
        if input_len > i32::MAX as usize {
            return Ok(-2);
        }
        let sealed_len = input_len + CACHE_V2_OVERHEAD;
        if output_cap < sealed_len {
            return Ok(-2);
        }

        let input = std::slice::from_raw_parts(input_ptr, input_len);
        let output = std::slice::from_raw_parts_mut(output_ptr, sealed_len);
        let key = std::slice::from_raw_parts(key_ptr, key_len);

        let cipher = ChaCha20Poly1305::new(&Key::from(derive_cache_key(key)));
        let nonce_bytes: [u8; 12] = rand::random();
        let sealed = cipher
            .encrypt(
                &Nonce::from(nonce_bytes),
                Payload {
                    msg: input,
                    aad: &chunk_id.to_le_bytes(),
                },
            )
            .map_err(|_| ())?; // -3 below

        output[..12].copy_from_slice(&nonce_bytes);
        output[12..].copy_from_slice(&sealed);
        Ok(sealed_len as i64)
    });
    match result {
        Ok(Ok(n)) => n,
        Ok(Err(())) => -3,
        Err(_) => -99,
    }
}

/// SEC-06: open one v2-sealed audio-cache chunk.
///
/// Reads the envelope from `sealed_ptr`, verifies the Poly1305 tag with
/// `chunk_id` as AAD, writes the plaintext to `output_ptr` and returns
/// its length, or a negative sentinel:
///   -1 null/zero args · -2 malformed envelope / capacity · -3 auth
///   failure (tampering, wrong key, wrong chunk id) · -99 on panic.
#[no_mangle]
pub unsafe extern "C" fn rust_open_cache_chunk(
    sealed_ptr: *const u8,
    sealed_len: usize,
    output_ptr: *mut u8,
    output_cap: usize,
    key_ptr: *const u8,
    key_len: usize,
    chunk_id: u64,
) -> i64 {
    let result = catch_unwind(|| {
        if sealed_ptr.is_null() || output_ptr.is_null() || key_ptr.is_null() || key_len == 0 {
            return Ok(-1);
        }
        if sealed_len < CACHE_V2_OVERHEAD + 1 || sealed_len > (i32::MAX as usize) + CACHE_V2_OVERHEAD
        {
            return Ok(-2);
        }
        let plain_len = sealed_len - CACHE_V2_OVERHEAD;
        if output_cap < plain_len {
            return Ok(-2);
        }

        let sealed = std::slice::from_raw_parts(sealed_ptr, sealed_len);
        let output = std::slice::from_raw_parts_mut(output_ptr, plain_len);
        let key = std::slice::from_raw_parts(key_ptr, key_len);

        let cipher = ChaCha20Poly1305::new(&Key::from(derive_cache_key(key)));
        let plain = cipher
            .decrypt(
                &Nonce::from(<[u8; 12]>::try_from(&sealed[..12]).map_err(|_| ())?),
                Payload {
                    msg: &sealed[12..],
                    aad: &chunk_id.to_le_bytes(),
                },
            )
            .map_err(|_| ())?; // -3 below

        output.copy_from_slice(&plain);
        Ok(plain_len as i64)
    });
    match result {
        Ok(Ok(n)) => n,
        Ok(Err(())) => -3,
        Err(_) => -99,
    }
}

/// LEGACY MIGRATION READER (SEC-06): the pre-SEC-06 position-dependent
/// XOR "cipher". Retained byte-compatibly so audio caches written by
/// builds ≤ 2026-11 keep decrypting; every new write MUST use
/// [`rust_seal_cache_chunk`]. Not authenticated — callers re-reading a
/// legacy chunk should validate content (frame CRCs, decode) upstream.
#[no_mangle]
pub unsafe extern "C" fn crypt_audio_chunk(
    input_ptr: *const u8,
    output_ptr: *mut u8,
    len: usize,
    key_ptr: *const u8,
    key_len: usize,
    offset: u64,
) -> i32 {
    let result = catch_unwind(|| {
        if input_ptr.is_null()
            || output_ptr.is_null()
            || key_ptr.is_null()
            || len == 0
            || key_len == 0
        {
            return -1;
        }

        let input = std::slice::from_raw_parts(input_ptr, len);
        let output = std::slice::from_raw_parts_mut(output_ptr, len);
        let key = std::slice::from_raw_parts(key_ptr, key_len);

        // Legacy streaming XOR with position-dependent key rotation
        // (frozen verbatim for cache-format compatibility).
        for i in 0..len {
            let pos = offset + i as u64;
            let key_byte = key[(pos % (key.len() as u64)) as usize];
            let rotation = ((pos >> 8) & 0xFF) as u8;
            output[i] = input[i] ^ key_byte ^ rotation;
        }

        0
    });

    result.unwrap_or(-2)
}

#[cfg(test)]
mod tests {
    use super::*;

    const KEY: &[u8] = b"cache-cipher-unit-key";

    fn seal(plain: &[u8], chunk_id: u64) -> Vec<u8> {
        let mut out = vec![0u8; plain.len() + CACHE_V2_OVERHEAD];
        let n = unsafe {
            rust_seal_cache_chunk(
                plain.as_ptr(),
                plain.len(),
                out.as_mut_ptr(),
                out.len(),
                KEY.as_ptr(),
                KEY.len(),
                chunk_id,
            )
        };
        assert!(n > 0, "seal failed: {n}");
        out.truncate(n as usize);
        out
    }

    fn open(sealed: &[u8], chunk_id: u64) -> Result<Vec<u8>, i64> {
        let mut out = vec![0u8; sealed.len()];
        let n = unsafe {
            rust_open_cache_chunk(
                sealed.as_ptr(),
                sealed.len(),
                out.as_mut_ptr(),
                out.len(),
                KEY.as_ptr(),
                KEY.len(),
                chunk_id,
            )
        };
        if n < 0 {
            return Err(n);
        }
        out.truncate(n as usize);
        Ok(out)
    }

    /// SEC-06 gate: round-trip through seal/open.
    #[test]
    fn cache_v2_round_trip() {
        let plain: Vec<u8> = (0..96_000u32).map(|i| (i * 31 % 251) as u8).collect();
        let sealed = seal(&plain, 7);
        assert_eq!(sealed.len(), plain.len() + CACHE_V2_OVERHEAD);
        assert_eq!(open(&sealed, 7).unwrap(), plain);
    }

    /// SEC-06 gate: identical plaintext seals to distinct ciphertexts
    /// (fresh nonce per chunk — known-plaintext resistance).
    #[test]
    fn cache_v2_known_plaintext_distinct_ciphertexts() {
        let plain = vec![0x5Au8; 4096];
        let a = seal(&plain, 0);
        let b = seal(&plain, 0);
        assert_ne!(a, b, "same chunk sealed twice must differ");
        assert_ne!(&a[..12], &b[..12], "nonces must differ");
    }

    /// Tampering with any envelope byte (nonce, ct or tag) must fail auth.
    #[test]
    fn cache_v2_tamper_fails() {
        let plain = b"audio frame bytes".repeat(64);
        let sealed = seal(&plain, 3);
        for pos in [0usize, 12, sealed.len() - 1] {
            let mut evil = sealed.clone();
            evil[pos] ^= 0x80;
            assert_eq!(open(&evil, 3), Err(-3), "tamper at byte {pos} must fail");
        }
    }

    /// Chunk id is AAD: opening a chunk under a different id must fail
    /// (replay across positions is an authentication failure).
    #[test]
    fn cache_v2_chunk_reordering_fails() {
        let plain = b"chunk payload".repeat(32);
        let sealed = seal(&plain, 42);
        assert!(open(&sealed, 43).is_err());
    }

    /// Wrong key fails closed.
    #[test]
    fn cache_v2_wrong_key_fails() {
        let plain = b"more frame bytes".repeat(16);
        let sealed = seal(&plain, 1);
        let mut out = vec![0u8; sealed.len()];
        let wrong = b"not-the-cache-key";
        let n = unsafe {
            rust_open_cache_chunk(
                sealed.as_ptr(),
                sealed.len(),
                out.as_mut_ptr(),
                out.len(),
                wrong.as_ptr(),
                wrong.len(),
                1,
            )
        };
        assert_eq!(n, -3);
    }

    /// Legacy interop: the v1 XOR path still round-trips (migration
    /// reader for caches written by builds ≤ 2026-11).
    #[test]
    fn legacy_xor_reader_still_round_trips() {
        let plain: Vec<u8> = (0..8192u32).map(|i| (i % 256) as u8).collect();
        let mut enc = vec![0u8; plain.len()];
        let rc = unsafe {
            crypt_audio_chunk(
                plain.as_ptr(),
                enc.as_mut_ptr(),
                plain.len(),
                KEY.as_ptr(),
                KEY.len(),
                1_048_576,
            )
        };
        assert_eq!(rc, 0);
        assert_ne!(enc, plain);
        let mut back = vec![0u8; plain.len()];
        let rc = unsafe {
            crypt_audio_chunk(
                enc.as_ptr(),
                back.as_mut_ptr(),
                enc.len(),
                KEY.as_ptr(),
                KEY.len(),
                1_048_576,
            )
        };
        assert_eq!(rc, 0);
        assert_eq!(back, plain);
    }
}
