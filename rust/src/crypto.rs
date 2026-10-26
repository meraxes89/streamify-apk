use hmac::{Hmac, Mac};
use sha2::{Digest, Sha256};
use std::fs::File;
use std::io::{Read, Write};

type HmacSha256 = Hmac<Sha256>;

pub struct VaultCryptoEngine;

impl VaultCryptoEngine {
    const CHUNK_SIZE: usize = 65536; // 64 KB streaming buffer

    /// High-throughput streaming cipher with HMAC-SHA256 authentication (Zero JVM Allocation)
    pub fn encrypt_file_in_place(
        src_path: &str,
        dest_path: &str,
        master_key: &[u8],
    ) -> Result<(), String> {
        let mut src_file =
            File::open(src_path).map_err(|e| format!("Failed to open source file: {}", e))?;
        let mut dest_file = File::create(dest_path)
            .map_err(|e| format!("Failed to create destination vault file: {}", e))?;

        // Derive keystream & HMAC key
        let mut key_hasher = Sha256::new();
        key_hasher.update(master_key);
        key_hasher.update(b":::streamify_vault_v1:::");
        let derived_key = key_hasher.finalize();

        let mut mac = HmacSha256::new_from_slice(&derived_key)
            .map_err(|e| format!("Failed to init HMAC: {}", e))?;

        let mut buffer = vec![0u8; Self::CHUNK_SIZE];
        let mut chunk_idx: u64 = 0;

        loop {
            let bytes_read = src_file
                .read(&mut buffer)
                .map_err(|e| format!("Failed to read source stream: {}", e))?;

            if bytes_read == 0 {
                break;
            }

            // Derive per-chunk nonce keystream
            let mut chunk_hasher = Sha256::new();
            chunk_hasher.update(derived_key);
            chunk_hasher.update(chunk_idx.to_le_bytes());
            let chunk_key = chunk_hasher.finalize();

            // Vectorized XOR keystream encryption
            for i in 0..bytes_read {
                buffer[i] ^= chunk_key[i % 32];
            }

            mac.update(&buffer[..bytes_read]);
            dest_file
                .write_all(&buffer[..bytes_read])
                .map_err(|e| format!("Failed to write encrypted vault stream: {}", e))?;

            chunk_idx += 1;
        }

        // Append 32-byte HMAC tag at EOF
        let tag = mac.finalize().into_bytes();
        dest_file
            .write_all(&tag)
            .map_err(|e| format!("Failed to write HMAC tag: {}", e))?;

        dest_file
            .flush()
            .map_err(|e| format!("Failed to flush vault file: {}", e))?;

        Ok(())
    }

    /// High-throughput streaming decryption & integrity verification
    pub fn decrypt_file_to_file(
        src_path: &str,
        dest_path: &str,
        master_key: &[u8],
    ) -> Result<(), String> {
        let mut src_file = File::open(src_path)
            .map_err(|e| format!("Failed to open encrypted vault file: {}", e))?;
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

        let mut key_hasher = Sha256::new();
        key_hasher.update(master_key);
        key_hasher.update(b":::streamify_vault_v1:::");
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

            let mut chunk_hasher = Sha256::new();
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

        // Verify HMAC
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
