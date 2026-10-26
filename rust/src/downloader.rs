use sha2::{Digest, Sha256};
use std::fs::{File, OpenOptions};
use std::io::{Read, Write};
use std::path::Path;

pub struct StreamDownloader;

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

        let agent = ureq::AgentBuilder::new()
            .timeout_connect(std::time::Duration::from_secs(8))
            .timeout_read(std::time::Duration::from_secs(15))
            .build();

        // 1. Fetch content-length headers
        let head_resp = agent
            .get(stream_url)
            .set("User-Agent", "Mozilla/5.0 (Linux; Android 11) Streamify/1.0")
            .call()
            .map_err(|e| format!("Failed to initiate HTTP stream: {}", e))?;

        let total_bytes = head_resp
            .header("Content-Length")
            .and_then(|l| l.parse::<u64>().ok())
            .unwrap_or(0);

        let mut file = OpenOptions::new()
            .create(true)
            .write(true)
            .truncate(true)
            .open(target_path)
            .map_err(|e| format!("Failed to create destination audio file: {}", e))?;

        let mut hasher = Sha256::new();
        let mut reader = head_resp.into_reader();
        let mut buffer = vec![0u8; chunk_size_bytes.clamp(16384, 1048576)];
        let mut downloaded_bytes: u64 = 0;

        loop {
            match reader.read(&mut buffer) {
                Ok(0) => break, // EOF
                Ok(bytes_read) => {
                    let chunk = &buffer[..bytes_read];
                    file.write_all(chunk)
                        .map_err(|e| format!("Failed to write audio stream: {}", e))?;
                    hasher.update(chunk);
                    downloaded_bytes += bytes_read as u64;

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
                Err(e) => {
                    return Err(format!("Stream interrupted during download: {}", e));
                }
            }
        }

        file.flush()
            .map_err(|e| format!("Failed to flush audio stream to disk: {}", e))?;

        let hash_result = hasher.finalize();
        // digest 0.11 Array no longer implements LowerHex — encode manually.
        let sha256_hex: String = hash_result.iter().map(|b| format!("{:02x}", b)).collect();

        progress_callback(DownloadProgress {
            bytes_downloaded: downloaded_bytes,
            total_bytes: downloaded_bytes,
            percent: 100.0,
            is_complete: true,
            error: None,
        });

        Ok(sha256_hex)
    }

    /// Fast in-place chunk verify
    pub fn verify_file_integrity(file_path: &str, expected_sha256: &str) -> bool {
        let mut file = match File::open(file_path) {
            Ok(f) => f,
            Err(_) => return false,
        };

        let mut hasher = Sha256::new();
        let mut buffer = [0u8; 65536];

        loop {
            match file.read(&mut buffer) {
                Ok(0) => break,
                Ok(n) => hasher.update(&buffer[..n]),
                Err(_) => return false,
            }
        }

        // digest 0.11 Array no longer implements LowerHex — encode manually.
        let calculated_hex: String = hasher.finalize().iter().map(|b| format!("{:02x}", b)).collect();
        calculated_hex.eq_ignore_ascii_case(expected_sha256)
    }
}
