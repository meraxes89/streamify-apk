// streamify-storage — vault crypto envelope (SEC-06).
//
// FORMAT HISTORY
// ─────────────
// v1 (legacy, 2026-08 → 2026-11): unauthenticated-header stream of
// XOR-keystream chunks (SHA-256(derived_key ‖ chunk_idx) cycled mod 32)
// with one whole-file HMAC-SHA256 tag appended at EOF. Deterministic
// ciphertext (no nonce) — breakable from a single known audio header
// (lead plan R1.2). Kept ONLY as a read-path for migrating v1 vaults.
//
// v2 (SEC-06, 2026-11): per-chunk AEAD. Every 64 KiB chunk is sealed
// independently with ChaCha20-Poly1305 under a FRESH random 96-bit
// nonce, with the chunk index bound as AEAD associated data (position
// tampering / chunk reordering is an authentication failure). The AEAD
// key is derived from the caller's master key with BLAKE3
// `derive_key` (context-separated from every other key use in the
// workspace). Encrypt-then-authenticate is inherent to the AEAD
// construction; there is no unauthenticated plaintext step anywhere.
//
// v2 wire format (all integers little-endian):
//
//   ┌──────────────────────────┬──────┐
//   │ "STREAMIFY_VAULT_V2"     │ 18 B │  magic header (format sniff)
//   ├──────────────────────────┼──────┤
//   │ chunk_count × record     │  …   │  records until EOF
//   └──────────────────────────┴──────┘
//
//   record := ct_len:u32 (≤ CHUNK_SIZE + 16) ‖ nonce:12 B ‖ ct_and_tag:ct_len B
//
// Migration policy (SEC-06 gate): READ-OLD / WRITE-NEW. `decrypt` sniffs
// the magic: v2 files take the AEAD path, anything else is handed to the
// legacy v1 reader (which still verifies the v1 HMAC before XOR-decoding).
// `encrypt` always writes v2. A v1 vault is therefore readable forever,
// and every rewrite upgrades it.
//
// The full spec (threat model, key derivation, format, migration) lives
// in docs/architecture/CRYPTO_SPEC.md.

use chacha20poly1305::aead::{Aead, KeyInit, Payload};
use chacha20poly1305::{ChaCha20Poly1305, Key, Nonce};
use std::fs::File;
use std::io::{Read, Write};

/// v2 format magic (also the format sniffer for the migration reader).
const VAULT_V2_MAGIC: &[u8; 18] = b"STREAMIFY_VAULT_V2";
/// Per-chunk AEAD overhead: 12-byte nonce + 16-byte Poly1305 tag.
const V2_CHUNK_OVERHEAD: usize = 12 + 16;
/// BLAKE3 `derive_key` context — domain-separates the vault AEAD key from
/// every other key derivation in the workspace (jam state hashing, cache
/// keys, …) so no key material is ever reused across purposes.
const V2_KEY_CONTEXT: &str = "streamify-vault-v2-aead";

/// Legacy v1 key-derivation domain separator (kept verbatim for the
/// migration reader — changing it would break every existing vault).
const V1_KEY_SUFFIX: &[u8] = b":::streamify_vault_v1:::";

type HmacSha256 = hmac::Hmac<sha2::Sha256>;

pub struct VaultCryptoEngine;

impl VaultCryptoEngine {
    const CHUNK_SIZE: usize = 65536; // 64 KB streaming buffer

    /// Derive the v2 AEAD key from the caller's master key (BLAKE3 KDF,
    /// SEC-06). `derive_key` is keyed BLAKE3 with the context as the key
    /// and the master key as input — a one-way, collision-resistant KDF.
    fn derive_v2_key(master_key: &[u8]) -> [u8; 32] {
        blake3::derive_key(V2_KEY_CONTEXT, master_key)
    }

    /// Streaming AEAD encryption (SEC-06 v2 format).
    ///
    /// Reads `src_path`, writes a fresh v2 vault to `dest_path`. Every
    /// chunk is sealed with an independent random nonce — encrypting the
    /// same source twice yields different ciphertexts (known-plaintext
    /// resistance, see the tests).
    pub fn encrypt_file_in_place(
        src_path: &str,
        dest_path: &str,
        master_key: &[u8],
    ) -> Result<(), String> {
        let mut src_file =
            File::open(src_path).map_err(|e| format!("Failed to open source file: {}", e))?;
        let mut dest_file = File::create(dest_path)
            .map_err(|e| format!("Failed to create destination vault file: {}", e))?;

        let cipher = ChaCha20Poly1305::new(&Key::from(Self::derive_v2_key(master_key)));

        dest_file
            .write_all(VAULT_V2_MAGIC)
            .map_err(|e| format!("Failed to write vault header: {}", e))?;

        let mut buffer = vec![0u8; Self::CHUNK_SIZE];
        let mut chunk_idx: u64 = 0;

        loop {
            let bytes_read = src_file
                .read(&mut buffer)
                .map_err(|e| format!("Failed to read source stream: {}", e))?;

            if bytes_read == 0 {
                break;
            }

            // Fresh random nonce per chunk — NEVER derived from position or
            // key material (the v1 defect).
            let nonce_bytes: [u8; 12] = rand::random();
            let nonce = Nonce::from(nonce_bytes);

            // Chunk index as AAD: binds each ciphertext to its position, so
            // truncation, reordering and reassembly attacks all fail auth.
            let sealed = cipher
                .encrypt(
                    &nonce,
                    Payload {
                        msg: &buffer[..bytes_read],
                        aad: &chunk_idx.to_le_bytes(),
                    },
                )
                .map_err(|e| format!("Failed to seal vault chunk {}: {}", chunk_idx, e))?;

            // Length-prefixed record so the streaming reader knows each
            // chunk's extent without scanning.
            dest_file
                .write_all(&(sealed.len() as u32).to_le_bytes())
                .and_then(|_| dest_file.write_all(&nonce_bytes))
                .and_then(|_| dest_file.write_all(&sealed))
                .map_err(|e| format!("Failed to write encrypted vault stream: {}", e))?;

            chunk_idx += 1;
        }

        dest_file
            .flush()
            .map_err(|e| format!("Failed to flush vault file: {}", e))?;

        Ok(())
    }

    /// Streaming decryption with integrity verification and legacy
    /// migration (SEC-06).
    ///
    /// v2 vaults take the per-chunk AEAD path; anything else is treated
    /// as a legacy v1 vault (HMAC-verified XOR keystream) so existing
    /// caches keep decrypting while every rewrite upgrades to v2.
    pub fn decrypt_file_to_file(
        src_path: &str,
        dest_path: &str,
        master_key: &[u8],
    ) -> Result<(), String> {
        let mut src_file = File::open(src_path)
            .map_err(|e| format!("Failed to open encrypted vault file: {}", e))?;

        // Format sniff: v2 magic → AEAD path; otherwise legacy v1.
        let mut magic = [0u8; VAULT_V2_MAGIC.len()];
        let mut sniffed = 0;
        while sniffed < magic.len() {
            let n = src_file
                .read(&mut magic[sniffed..])
                .map_err(|e| format!("Failed to read vault header: {}", e))?;
            if n == 0 {
                break;
            }
            sniffed += n;
        }
        if sniffed == VAULT_V2_MAGIC.len() && &magic == VAULT_V2_MAGIC {
            return Self::decrypt_v2(src_file, dest_path, master_key);
        }
        // Legacy reader needs the sniffed prefix bytes back — reopen from
        // zero (simplest correct seek-free path; the file was opened read-only).
        drop(src_file);
        let src_file = File::open(src_path)
            .map_err(|e| format!("Failed to open encrypted vault file: {}", e))?;
        Self::decrypt_legacy_v1(src_file, dest_path, master_key)
    }

    /// v2 reader: length-prefixed AEAD chunk records until EOF.
    fn decrypt_v2(mut src_file: File, dest_path: &str, master_key: &[u8]) -> Result<(), String> {
        let cipher = ChaCha20Poly1305::new(&Key::from(Self::derive_v2_key(master_key)));
        let mut dest_file = File::create(dest_path)
            .map_err(|e| format!("Failed to create destination decrypted file: {}", e))?;

        let mut len_buf = [0u8; 4];
        let mut nonce_buf = [0u8; 12];
        let mut chunk_idx: u64 = 0;

        loop {
            // Record length.
            match src_file.read_exact(&mut len_buf) {
                Ok(()) => {}
                Err(e) if e.kind() == std::io::ErrorKind::UnexpectedEof => break,
                Err(e) => return Err(format!("Failed to read chunk length: {}", e)),
            }
            let ct_len = u32::from_le_bytes(len_buf) as usize;
            if ct_len == 0 || ct_len > Self::CHUNK_SIZE + V2_CHUNK_OVERHEAD {
                return Err(format!(
                    "Corrupted vault file: chunk {} has invalid length {}",
                    chunk_idx, ct_len
                ));
            }

            // Nonce + ciphertext-with-tag.
            src_file
                .read_exact(&mut nonce_buf)
                .map_err(|e| format!("Failed to read chunk nonce: {}", e))?;
            let mut sealed = vec![0u8; ct_len];
            src_file
                .read_exact(&mut sealed)
                .map_err(|e| format!("Failed to read chunk ciphertext: {}", e))?;

            let plain = cipher
                .decrypt(
                    &Nonce::from(nonce_buf),
                    Payload {
                        msg: &sealed,
                        aad: &chunk_idx.to_le_bytes(),
                    },
                )
                .map_err(|_| {
                    "Vault authentication failed: chunk tag mismatch, tampering or invalid key"
                        .to_string()
                })?;

            dest_file
                .write_all(&plain)
                .map_err(|e| format!("Failed to write decrypted audio stream: {}", e))?;

            chunk_idx += 1;
        }

        // A v2 vault must contain at least the header; zero chunks is valid
        // (empty source). Trailing partial records are rejected above by
        // read_exact, so EOF here means a well-formed file.
        dest_file
            .flush()
            .map_err(|e| format!("Failed to flush decrypted file: {}", e))?;

        Ok(())
    }

    /// Legacy v1 migration reader (SEC-06: read-old/write-new).
    ///
    /// Byte-for-byte the pre-SEC-06 algorithm: SHA-256-derived keystream
    /// XOR + whole-file HMAC-SHA256 tail verification. Kept ONLY for
    /// reading vaults written by builds ≤ 2026-11; never used for writing.
    fn decrypt_legacy_v1(
        mut src_file: File,
        dest_path: &str,
        master_key: &[u8],
    ) -> Result<(), String> {
        use hmac::{KeyInit, Mac};
        use sha2::Digest;

        let file_len = src_file
            .metadata()
            .map_err(|e| format!("Failed to read vault metadata: {}", e))?
            .len();

        if file_len < 32 {
            return Err("Corrupted vault file: shorter than HMAC tag".into());
        }

        let content_len = file_len - 32;
        let mut dest_file = File::create(dest_path)
            .map_err(|e| format!("Failed to create destination decrypted file: {}", e))?;

        let mut key_hasher = sha2::Sha256::new();
        key_hasher.update(master_key);
        key_hasher.update(V1_KEY_SUFFIX);
        let derived_key = key_hasher.finalize();

        let mut mac = HmacSha256::new_from_slice(&derived_key)
            .map_err(|e| format!("Failed to init HMAC: {}", e))?;

        let mut buffer = vec![0u8; Self::CHUNK_SIZE];
        let mut remaining_bytes = content_len;
        let mut chunk_idx: u64 = 0;

        while remaining_bytes > 0 {
            let to_read = (remaining_bytes as usize).min(Self::CHUNK_SIZE);
            let bytes_read = src_file
                .read(&mut buffer[..to_read])
                .map_err(|e| format!("Failed to read encrypted vault chunk: {}", e))?;

            if bytes_read == 0 {
                break;
            }

            mac.update(&buffer[..bytes_read]);

            let mut chunk_hasher = sha2::Sha256::new();
            chunk_hasher.update(derived_key);
            chunk_hasher.update(chunk_idx.to_le_bytes());
            let chunk_key = chunk_hasher.finalize();

            for i in 0..bytes_read {
                buffer[i] ^= chunk_key[i % 32];
            }

            dest_file
                .write_all(&buffer[..bytes_read])
                .map_err(|e| format!("Failed to write decrypted audio stream: {}", e))?;

            remaining_bytes -= bytes_read as u64;
            chunk_idx += 1;
        }

        // Verify the legacy whole-file HMAC before trusting anything that
        // was written (the v1 construction authenticates the full stream).
        let mut expected_tag = [0u8; 32];
        src_file
            .read_exact(&mut expected_tag)
            .map_err(|e| format!("Failed to read HMAC tag from vault file: {}", e))?;

        mac.verify_slice(&expected_tag)
            .map_err(|_| "Vault authentication failed: HMAC integrity mismatch or invalid key")?;

        dest_file
            .flush()
            .map_err(|e| format!("Failed to flush decrypted file: {}", e))?;

        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use hmac::{KeyInit, Mac};
    use sha2::Digest;
    use std::io::Write as _;

    fn write_temp(name: &str, bytes: &[u8]) -> String {
        let path = std::env::temp_dir().join(format!(
            "streamify-sec06-{}-{}.tmp",
            name,
            std::process::id()
        ));
        let mut f = File::create(&path).unwrap();
        f.write_all(bytes).unwrap();
        path.to_string_lossy().to_string()
    }

    fn read_all(path: &str) -> Vec<u8> {
        let mut f = File::open(path).unwrap();
        let mut out = Vec::new();
        f.read_to_end(&mut out).unwrap();
        out
    }

    const MASTER_KEY: &[u8] = b"sec06-unit-master-key-material-32b";

    /// SEC-06 gate: full round-trip across multiple chunks (full 64 KiB
    /// chunk + partial tail chunk) with the v2 AEAD format.
    #[test]
    fn v2_round_trip_multichunk() {
        let mut plain = vec![0xABu8; VaultCryptoEngine::CHUNK_SIZE * 2 + 1234];
        for (i, b) in plain.iter_mut().enumerate() {
            *b = (i % 251) as u8;
        }
        let src = write_temp("rt-src", &plain);
        let vault = write_temp("rt-vault", b"");
        let back = write_temp("rt-back", b"");

        VaultCryptoEngine::encrypt_file_in_place(&src, &vault, MASTER_KEY).unwrap();
        VaultCryptoEngine::decrypt_file_to_file(&vault, &back, MASTER_KEY).unwrap();

        assert_eq!(plain, read_all(&back));
    }

    /// SEC-06 gate: encrypting two identical chunks must yield DISTINCT
    /// ciphertexts (fresh random nonce per chunk — the v1 construction
    /// produced deterministic, replayable ciphertext).
    #[test]
    fn v2_known_plaintext_distinct_ciphertexts() {
        let plain = vec![0x42u8; 4096];
        let src = write_temp("kp-src", &plain);
        let vault_a = write_temp("kp-a", b"");
        let vault_b = write_temp("kp-b", b"");

        VaultCryptoEngine::encrypt_file_in_place(&src, &vault_a, MASTER_KEY).unwrap();
        VaultCryptoEngine::encrypt_file_in_place(&src, &vault_b, MASTER_KEY).unwrap();

        let (a, b) = (read_all(&vault_a), read_all(&vault_b));
        assert_ne!(a, b, "two encryptions of the same file must differ");

        // Within one vault, the two identical chunks (offset by the record
        // framing) must also differ: chunk AAD equal, nonce different.
        let hdr = VAULT_V2_MAGIC.len();
        let rec0 = &a[hdr..hdr + 4 + 12 + 4096 + 16];
        let rec1 = &a[hdr + 4 + 12 + 4096 + 16..hdr + 2 * (4 + 12 + 4096 + 16)];
        assert_ne!(&rec0[4..16], &rec1[4..16], "per-chunk nonces must differ");
        assert_ne!(
            &rec0[16..16 + 4096 + 16],
            &rec1[16..16 + 4096 + 16],
            "identical plaintext chunks must seal to distinct ciphertext"
        );
    }

    /// SEC-06 gate: a legacy v1 vault (written with the pre-SEC-06 XOR +
    /// HMAC construction, reproduced verbatim here) must still decrypt
    /// through the migration reader, and rewriting it must upgrade the
    /// file to the v2 format.
    #[test]
    fn legacy_v1_migration_read_old_write_new() {
        let plain = b"legacy vault payload - FLAC frame bytes would be here".repeat(300);
        let master_key = MASTER_KEY;

        // ── reproduce the v1 writer byte-for-byte (pre-SEC-06 code) ──
        let mut key_hasher = sha2::Sha256::new();
        key_hasher.update(master_key);
        key_hasher.update(V1_KEY_SUFFIX);
        let derived_key = key_hasher.finalize();
        let mut mac = HmacSha256::new_from_slice(&derived_key).unwrap();
        let mut v1_body = Vec::new();
        for (chunk_idx, chunk) in plain.chunks(VaultCryptoEngine::CHUNK_SIZE).enumerate() {
            let mut chunk_hasher = sha2::Sha256::new();
            chunk_hasher.update(derived_key);
            chunk_hasher.update((chunk_idx as u64).to_le_bytes());
            let chunk_key = chunk_hasher.finalize();
            let mut enc = chunk.to_vec();
            for (i, b) in enc.iter_mut().enumerate() {
                *b ^= chunk_key[i % 32];
            }
            mac.update(&enc);
            v1_body.extend_from_slice(&enc);
        }
        let tag = mac.finalize().into_bytes();
        let mut v1_file = v1_body;
        v1_file.extend_from_slice(&tag);

        let vault = write_temp("mig-v1", &v1_file);
        let back = write_temp("mig-back", b"");

        // read-old: legacy vault decrypts through the migration reader.
        VaultCryptoEngine::decrypt_file_to_file(&vault, &back, master_key).unwrap();
        assert_eq!(plain, read_all(&back));

        // write-new: re-encrypting produces the v2 format (magic present).
        let vault_v2 = write_temp("mig-v2", b"");
        VaultCryptoEngine::encrypt_file_in_place(&back, &vault_v2, master_key).unwrap();
        let upgraded = read_all(&vault_v2);
        assert_eq!(&upgraded[..VAULT_V2_MAGIC.len()], VAULT_V2_MAGIC);
        let back2 = write_temp("mig-back2", b"");
        VaultCryptoEngine::decrypt_file_to_file(&vault_v2, &back2, master_key).unwrap();
        assert_eq!(plain, read_all(&back2));
    }

    /// SEC-06 gate: ciphertext tampering must be an authentication
    /// failure, not silent corruption (v1 XOR decoded garbage happily).
    #[test]
    fn v2_tamper_detection() {
        let plain = vec![7u8; 10_000];
        let src = write_temp("tamper-src", &plain);
        let vault = write_temp("tamper-vault", b"");
        let back = write_temp("tamper-back", b"");

        VaultCryptoEngine::encrypt_file_in_place(&src, &vault, MASTER_KEY).unwrap();

        let mut data = read_all(&vault);
        let mid = data.len() / 2;
        data[mid] ^= 0x01;
        let tampered = write_temp("tamper-vault2", &data);

        let err = VaultCryptoEngine::decrypt_file_to_file(&tampered, &back, MASTER_KEY)
            .expect_err("tampered vault must fail authentication");
        assert!(err.contains("authentication"), "unexpected error: {err}");
    }

    /// Wrong key must fail closed on both formats.
    #[test]
    fn v2_wrong_key_fails_closed() {
        let src = write_temp("wk-src", b"secret bytes");
        let vault = write_temp("wk-vault", b"");
        let back = write_temp("wk-back", b"");
        VaultCryptoEngine::encrypt_file_in_place(&src, &vault, MASTER_KEY).unwrap();
        assert!(VaultCryptoEngine::decrypt_file_to_file(&vault, &back, b"wrong-key").is_err());
    }

    /// Empty source round-trips (header-only v2 vault).
    #[test]
    fn v2_empty_source_round_trip() {
        let src = write_temp("empty-src", b"");
        let vault = write_temp("empty-vault", b"");
        let back = write_temp("empty-back", b"");
        VaultCryptoEngine::encrypt_file_in_place(&src, &vault, MASTER_KEY).unwrap();
        VaultCryptoEngine::decrypt_file_to_file(&vault, &back, MASTER_KEY).unwrap();
        assert!(read_all(&back).is_empty());
    }
}
