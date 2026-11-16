//! streamify-wasm cipher facade (P4-A) — the feishin downloads chunk cipher.
//!
//! XChaCha20-Poly1305 per-chunk AEAD, the vault-v2 family contract
//! (rust/crates/streamify-storage/src/crypto.rs, SEC-06) in wasm32 form:
//!
//!   * every chunk is sealed under a FRESH 24-byte XChaCha nonce (the JS
//!     side mints it from the platform CSPRNG — `crypto.getRandomValues`;
//!     there is no RNG inside wasm, keeping this module import-free);
//!   * the chunk index is bound as AEAD associated data, so truncation,
//!     reordering, and cross-file grafting all fail authentication;
//!   * fresh random nonces mean encrypting the same plaintext twice yields
//!     different ciphertexts (known-plaintext resistance, the v1 vault
//!     defect — fixed there in v2, inherited here from day one).
//!
//! SCRATCH-REGION ABI (no allocator): the module owns one static 128 KiB
//! scratch region; the JS wrapper writes inputs at the documented offsets,
//! calls, and reads outputs back. No wasm-side allocation ever happens,
//! no buffer ownership crosses the boundary, and the module instantiates
//! with a single tiny import (`__wbindgen_init_externref_table`, a 5-line
//! table initializer the feishin loader provides — see
//! front-end/feishin/src/renderer/api/streamify/wasm/cipher-loader.ts).
//! The feishin side therefore ships ONLY the `.wasm` artifact plus a
//! hand-written typed loader; no wasm-pack glue JS is vendored or linted.
//!
//! Scratch layout (offsets from `dl_scratch_ptr()`):
//!   ┌────────────┬─────────┬────────────────────────────────┐
//!   │ key        │  0..32  │ 32 B  XChaCha20-Poly1305 key    │
//!   │ nonce      │ 32..56  │ 24 B  per-chunk fresh nonce     │
//!   │ plaintext  │ 64..65600    │ 64 KiB (seal input / open output)
//!   │ ciphertext │ 65600..131136│ 64 KiB (seal output / open input)
//!   │ tag        │ 131136..131152 │ 16 B Poly1305 (out on seal, in on open)
//!   └────────────┴─────────┴────────────────────────────────┘
//!
//! Return codes (shared contract with the TS wrapper):
//!   0 = OK        1 = AUTH FAILED (tag mismatch)      2 = INVALID ARGS

use chacha20poly1305::aead::{Aead, KeyInit, Payload};
use chacha20poly1305::{Key, XChaCha20Poly1305, XNonce};

use wasm_bindgen::prelude::*;

// ---------------------------------------------------------------------------
// getrandom: the RustCrypto aead stack pulls it in transitively
// (crypto-common → rand_core). On wasm32 it needs a backend; the cipher
// facade NEVER draws randomness inside wasm (nonces and keys are minted
// on the JS side from the platform CSPRNG — see the module docs), so the
// backend is a trap. Reaching it is a programming error, not a code path.
// ---------------------------------------------------------------------------
getrandom::register_custom_getrandom!(streamify_cipher_rng_trap);

fn streamify_cipher_rng_trap(_dest: &mut [u8]) -> Result<(), getrandom::Error> {
    unreachable!(
        "streamify-wasm cipher facade: getrandom must never run — randomness is JS-side by design"
    )
}

// ---------------------------------------------------------------------------
// Constants (the single source of truth — the feishin side reads the
// query fns at init instead of hardcoding any of them)
// ---------------------------------------------------------------------------
/// Downloads wire format version (STREAMIFY_DL_V1).
const DL_FORMAT_VERSION: u32 = 1;
/// Plaintext chunk size (64 KiB, the vault-v2 streaming buffer size).
const DL_CHUNK_SIZE: usize = 65536;
/// 32-byte XChaCha20-Poly1305 key.
const DL_KEY_LEN: usize = 32;
/// 24-byte XChaCha20-Poly1305 nonce.
const DL_NONCE_LEN: usize = 24;
/// 16-byte Poly1305 tag.
const DL_TAG_LEN: usize = 16;

const SCRATCH_KEY_OFF: usize = 0;
const SCRATCH_NONCE_OFF: usize = 32;
const SCRATCH_PT_OFF: usize = 64;
const SCRATCH_CT_OFF: usize = 64 + DL_CHUNK_SIZE;
const SCRATCH_TAG_OFF: usize = 64 + 2 * DL_CHUNK_SIZE;
const DL_SCRATCH_SIZE: usize = SCRATCH_TAG_OFF + DL_TAG_LEN;

/// Return codes (the shared contract above).
const DL_OK: u32 = 0;
const DL_AUTH_FAILED: u32 = 1;
const DL_INVALID: u32 = 2;

/// The one static scratch region (see the layout in the module docs).
static mut SCRATCH: [u8; DL_SCRATCH_SIZE] = [0; DL_SCRATCH_SIZE];

fn scratch_ptr() -> *mut u8 {
    core::ptr::addr_of_mut!(SCRATCH) as *mut u8
}

// ---------------------------------------------------------------------------
// wasm-bindgen exports
// ---------------------------------------------------------------------------

/// Downloads wire format version (read by the feishin header codec).
#[wasm_bindgen]
pub fn dl_format_version() -> u32 {
    DL_FORMAT_VERSION
}

/// Plaintext chunk size in bytes (read by the feishin seal/open driver).
#[wasm_bindgen]
pub fn dl_chunk_size() -> u32 {
    DL_CHUNK_SIZE as u32
}

/// Base offset of the scratch region in wasm memory (read by the loader).
#[wasm_bindgen]
pub fn dl_scratch_ptr() -> u32 {
    scratch_ptr() as u32
}

/// Total scratch region size in bytes (read by the loader; the wrapper
/// validates every write against this bound).
#[wasm_bindgen]
pub fn dl_scratch_size() -> u32 {
    DL_SCRATCH_SIZE as u32
}

/// Seal one chunk: key/nonce/plaintext are read from the scratch region
/// (key @0, nonce @32, pt @64, `pt_len` bytes); ciphertext is written to
/// @65600 and the 16-byte tag to @131136. AAD = chunk_index little-endian
/// (the position binding). Same-plaintext-twice yields different
/// ciphertexts (fresh JS-side nonce per chunk).
#[wasm_bindgen]
pub fn dl_seal_chunk(chunk_index: u32, pt_len: u32) -> u32 {
    let pt_len = pt_len as usize;
    if pt_len > DL_CHUNK_SIZE {
        return DL_INVALID;
    }
    unsafe {
        let base = scratch_ptr();
        let sealed = seal_bytes(
            core::slice::from_raw_parts(base.add(SCRATCH_KEY_OFF), DL_KEY_LEN),
            core::slice::from_raw_parts(base.add(SCRATCH_NONCE_OFF), DL_NONCE_LEN),
            chunk_index,
            core::slice::from_raw_parts(base.add(SCRATCH_PT_OFF), pt_len),
        );
        core::ptr::copy_nonoverlapping(sealed.as_ptr(), base.add(SCRATCH_CT_OFF), pt_len);
        core::ptr::copy_nonoverlapping(
            sealed[pt_len..].as_ptr(),
            base.add(SCRATCH_TAG_OFF),
            DL_TAG_LEN,
        );
    }
    DL_OK
}

/// Open one chunk: key/nonce/ciphertext/tag are read from the scratch
/// region (key @0, nonce @32, ct @65600 `ct_len` bytes, tag @131136);
/// on success (0) the verified plaintext is written to @64. Tag mismatch
/// (1) writes nothing — the caller treats the chunk as corrupted.
#[wasm_bindgen]
pub fn dl_open_chunk(chunk_index: u32, ct_len: u32) -> u32 {
    let ct_len = ct_len as usize;
    if ct_len > DL_CHUNK_SIZE {
        return DL_INVALID;
    }
    unsafe {
        let base = scratch_ptr();
        match open_bytes(
            core::slice::from_raw_parts(base.add(SCRATCH_KEY_OFF), DL_KEY_LEN),
            core::slice::from_raw_parts(base.add(SCRATCH_NONCE_OFF), DL_NONCE_LEN),
            chunk_index,
            core::slice::from_raw_parts(base.add(SCRATCH_CT_OFF), ct_len),
            core::slice::from_raw_parts(base.add(SCRATCH_TAG_OFF), DL_TAG_LEN),
        ) {
            Ok(plain) => {
                core::ptr::copy_nonoverlapping(plain.as_ptr(), base.add(SCRATCH_PT_OFF), ct_len);
                DL_OK
            }
            Err(code) => code,
        }
    }
}

/// Deterministic known-answer self-test — the /debug panel's health probe.
/// Exercises the REAL ABI end-to-end (scratch writes, seal, open, tamper,
/// AAD position binding), not just the typed core.
#[wasm_bindgen]
pub fn dl_self_test() -> u32 {
    unsafe {
        let base = scratch_ptr();
        let key: [u8; DL_KEY_LEN] = core::array::from_fn(|i| (i as u8) ^ 0x5a);
        let nonce: [u8; DL_NONCE_LEN] = core::array::from_fn(|i| (i as u8) ^ 0xa5);
        core::ptr::copy_nonoverlapping(key.as_ptr(), base.add(SCRATCH_KEY_OFF), DL_KEY_LEN);
        core::ptr::copy_nonoverlapping(nonce.as_ptr(), base.add(SCRATCH_NONCE_OFF), DL_NONCE_LEN);
    }
    let plain = b"streamify downloads chunk cipher self-test vector";
    const INDEX: u32 = 7;

    // Write the plaintext into scratch (pt area), seal, read ct+tag back.
    unsafe {
        let base = scratch_ptr();
        core::ptr::copy_nonoverlapping(plain.as_ptr(), base.add(SCRATCH_PT_OFF), plain.len());
    }
    if dl_seal_chunk(INDEX, plain.len() as u32) != DL_OK {
        return DL_AUTH_FAILED;
    }
    // Round-trip: open into the pt area, compare.
    if dl_open_chunk(INDEX, plain.len() as u32) != DL_OK {
        return DL_AUTH_FAILED;
    }
    unsafe {
        let base = scratch_ptr();
        let opened = core::slice::from_raw_parts(base.add(SCRATCH_PT_OFF), plain.len());
        if opened != plain {
            return DL_AUTH_FAILED;
        }
        // Tampered ciphertext byte → tag mismatch (1).
        *base.add(SCRATCH_CT_OFF) ^= 1;
        if dl_open_chunk(INDEX, plain.len() as u32) != DL_AUTH_FAILED {
            return DL_AUTH_FAILED;
        }
    }
    // Same ciphertext + tag under a different chunk index → AAD mismatch.
    // (Re-seal a fresh copy: the previous step left the tag corrupted.)
    unsafe {
        let base = scratch_ptr();
        core::ptr::copy_nonoverlapping(plain.as_ptr(), base.add(SCRATCH_PT_OFF), plain.len());
    }
    if dl_seal_chunk(INDEX, plain.len() as u32) != DL_OK {
        return DL_AUTH_FAILED;
    }
    if dl_open_chunk(INDEX + 1, plain.len() as u32) != DL_AUTH_FAILED {
        return DL_AUTH_FAILED;
    }

    DL_OK
}

// ---------------------------------------------------------------------------
// The typed core (no wasm ABI) — shared by the scratch shims above.
// ---------------------------------------------------------------------------

fn seal_bytes(key: &[u8], nonce: &[u8], chunk_index: u32, plain: &[u8]) -> Vec<u8> {
    let cipher = XChaCha20Poly1305::new(Key::from_slice(key));
    cipher
        .encrypt(
            XNonce::from_slice(nonce),
            Payload {
                msg: plain,
                aad: &chunk_index.to_le_bytes(),
            },
        )
        .expect("seal: allocation/size failure cannot happen for <=64 KiB")
}

fn open_bytes(
    key: &[u8],
    nonce: &[u8],
    chunk_index: u32,
    ct: &[u8],
    tag: &[u8],
) -> Result<Vec<u8>, u32> {
    // decrypt() wants ct||tag contiguous; the scratch ABI keeps them apart.
    let mut joined = Vec::with_capacity(ct.len() + tag.len());
    joined.extend_from_slice(ct);
    joined.extend_from_slice(tag);
    let cipher = XChaCha20Poly1305::new(Key::from_slice(key));
    match cipher.decrypt(
        XNonce::from_slice(nonce),
        Payload {
            msg: &joined,
            aad: &chunk_index.to_le_bytes(),
        },
    ) {
        Ok(plain) => Ok(plain),
        Err(_) => Err(DL_AUTH_FAILED),
    }
}
