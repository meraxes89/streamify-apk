use reqwest::Client;
use sha2::{Digest, Sha256};
use std::fs::{File, OpenOptions};
use std::io::{Read, Write};
use std::path::Path;
use std::sync::OnceLock;
use std::time::Duration;

use crate::resolver::get_runtime;

pub struct StreamDownloader;

/// Streaming download client (multiplatform Phase 3 / MP-C14): the async
/// reqwest client carries the old ureq agent's hang-guard discipline —
/// 8 s connect, 15 s idle-read timeout — WITHOUT a total-request cap, so
/// legitimately large downloads never hit an artificial deadline. This
/// is a separate OnceLock client from the resolver's interactive one
/// (whose 30 s TOTAL timeout is right for API calls and wrong for a
/// multi-hundred-MB body); both run on the same shared tokio runtime.
static DOWNLOAD_CLIENT: OnceLock<Client> = OnceLock::new();

fn download_client() -> &'static Client {
    DOWNLOAD_CLIENT.get_or_init(|| {
        Client::builder()
            .user_agent("Mozilla/5.0 (Linux; Android 11) Streamify/1.0")
            .connect_timeout(Duration::from_secs(8))
            .read_timeout(Duration::from_secs(15))
            .build()
            .unwrap_or_else(|_| Client::new())
    })
}

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct DownloadProgress {
    pub bytes_downloaded: u64,
    pub total_bytes: u64,
    pub percent: f32,
    pub is_complete: bool,
    pub error: Option<String>,
}

impl StreamDownloader {
    /// Fast segmented audio range downloader with SHA-256 verification
    ///
    /// Multiplatform Phase 3 (MP-C14): the blocking ureq agent is retired;
    /// the body now streams through the shared async runtime
    /// (`resolver::get_runtime()`) with per-chunk progress, preserving the
    /// exact sync FFI signature (`rust_download_stream_direct` calls this
    /// from a plain thread — never from inside a tokio worker).
    pub fn download_stream_to_file<F>(
        stream_url: &str,
        dest_path: &str,
        chunk_size_bytes: usize,
        progress_callback: F,
    ) -> Result<String, String>
    where
        F: Fn(DownloadProgress),
    {
        let target_path = Path::new(dest_path);
        if let Some(parent) = target_path.parent() {
            let _ = std::fs::create_dir_all(parent);
        }

        let rt = get_runtime();
        let client = download_client().clone();
        let url = stream_url.to_string();
        let mut file = OpenOptions::new()
            .create(true)
            .write(true)
            .truncate(true)
            .open(target_path)
            .map_err(|e| format!("Failed to create destination audio file: {e}"))?;
        // One runtime entry for the whole transfer: the future is polled on
        // THIS thread (block_on from a sync caller), so the file writes and
        // the progress callback below run exactly where the old blocking
        // loop ran — no runtime hops per chunk, no Send bound needed on the
        // callback.
        let (downloaded_bytes, sha256_hex) = rt.block_on(async {
            let resp = client
                .get(&url)
                .send()
                .await
                .map_err(|e| format!("Failed to initiate HTTP stream: {e}"))?;
            let total_bytes = resp
                .headers()
                .get(reqwest::header::CONTENT_LENGTH)
                .and_then(|l| l.to_str().ok())
                .and_then(|l| l.parse::<u64>().ok())
                .unwrap_or(0);
            let mut body_stream = resp;

            let mut hasher = Sha256::new();
            let mut downloaded_bytes: u64 = 0;
            // Buffer small chunks into batched writes of at least this size
            // — the old reader loop's chunk_size discipline, preserved so
            // flash wear and syscall counts stay identical.
            let flush_target = chunk_size_bytes.clamp(16384, 1_048_576);
            let mut write_buf: Vec<u8> = Vec::with_capacity(flush_target);

            while let Some(chunk) = body_stream
                .chunk()
                .await
                .map_err(|e| format!("Stream interrupted during download: {e}"))?
            {
                write_buf.extend_from_slice(&chunk);
                if write_buf.len() >= flush_target {
                    file.write_all(&write_buf)
                        .map_err(|e| format!("Failed to write audio stream: {e}"))?;
                    hasher.update(&write_buf);
                    downloaded_bytes += write_buf.len() as u64;
                    write_buf.clear();

                    let percent = if total_bytes > 0 {
                        (downloaded_bytes as f32 / total_bytes as f32) * 100.0
                    } else {
                        0.0
                    };

                    progress_callback(DownloadProgress {
                        bytes_downloaded: downloaded_bytes,
                        total_bytes,
                        percent,
                        is_complete: false,
                        error: None,
                    });
                }
            }
            // Flush the sub-target tail.
            if !write_buf.is_empty() {
                file.write_all(&write_buf)
                    .map_err(|e| format!("Failed to write audio stream: {e}"))?;
                hasher.update(&write_buf);
                downloaded_bytes += write_buf.len() as u64;
            }
            file.flush()
                .map_err(|e| format!("Failed to flush audio stream to disk: {e}"))?;

            // digest 0.11 Array no longer implements LowerHex — encode
            // manually.
            let sha256_hex: String = hasher
                .finalize()
                .iter()
                .map(|b| format!("{:02x}", b))
                .collect();
            Ok::<(u64, String), String>((downloaded_bytes, sha256_hex))
        })?;

        progress_callback(DownloadProgress {
            bytes_downloaded: downloaded_bytes,
            total_bytes: downloaded_bytes,
            percent: 100.0,
            is_complete: true,
            error: None,
        });

        Ok(sha256_hex)
    }

    /// SHA-256 of a settled file.
    fn sha256_file(path: &Path) -> Option<String> {
        let mut file = File::open(path).ok()?;
        let mut hasher = Sha256::new();
        let mut buffer = [0u8; 65_536];
        loop {
            match file.read(&mut buffer) {
                Ok(0) => break,
                Ok(n) => hasher.update(&buffer[..n]),
                Err(_) => return None,
            }
        }
        // digest 0.11 Array no longer implements LowerHex — encode manually.
        Some(
            hasher
                .finalize()
                .iter()
                .map(|b| format!("{:02x}", b))
                .collect(),
        )
    }

    /// Fast in-place chunk verify
    pub fn verify_file_integrity(file_path: &str, expected_sha256: &str) -> bool {
        match Self::sha256_file(Path::new(file_path)) {
            Some(calculated) => calculated.eq_ignore_ascii_case(expected_sha256),
            None => false,
        }
    }
}
