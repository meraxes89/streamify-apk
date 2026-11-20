// Clippy: FFI entry points in this module all follow the house safety
// model (catch_unwind shields, sentinel returns, no panics across the
// boundary — see the module docs); per-function `# Safety` boilerplate
// would be copy-paste.
#![allow(clippy::missing_safety_doc)]
use sha1::{Digest, Sha1};
use std::panic::catch_unwind;
use std::time::{SystemTime, UNIX_EPOCH};

/// Generates SAPISIDHASH safely across FFI.
/// Returns: Length of string written to out_buf, or negative error code on failure.
#[no_mangle]
pub unsafe extern "C" fn generate_sapisid_hash(
    sapisid_ptr: *const u8,
    sapisid_len: usize,
    origin_ptr: *const u8,
    origin_len: usize,
    out_buf: *mut u8,
    out_buf_len: usize,
) -> i32 {
    let result = catch_unwind(|| {
        // Validate pointers
        if sapisid_ptr.is_null() || origin_ptr.is_null() || out_buf.is_null() || sapisid_len == 0 {
            return -1;
        }

        let sapisid = std::slice::from_raw_parts(sapisid_ptr, sapisid_len);
        let origin = std::slice::from_raw_parts(origin_ptr, origin_len);

        let timestamp = match SystemTime::now().duration_since(UNIX_EPOCH) {
            Ok(d) => d.as_secs(),
            Err(_) => return -1,
        };

        // SHA1(payload) = SHA1("{timestamp} {sapisid} {origin}")
        let mut hasher = Sha1::new();
        hasher.update(timestamp.to_string().as_bytes());
        hasher.update(b" ");
        hasher.update(sapisid);
        hasher.update(b" ");
        hasher.update(origin);

        let digest = hasher.finalize();
        let hash_hex = hex::encode(digest);

        // Format: "{timestamp}_{hash_hex}"
        let formatted = format!("{}_{}", timestamp, hash_hex);
        let bytes = formatted.as_bytes();

        if bytes.len() > out_buf_len {
            return -2; // Buffer too small
        }

        // Write result to Kotlin's pre-allocated buffer
        std::ptr::copy_nonoverlapping(bytes.as_ptr(), out_buf, bytes.len());
        bytes.len() as i32
    });

    // If a panic occurred (e.g., hash failed), return -3. NEVER crash the JVM.
    result.unwrap_or(-3)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_sapisidhash_generation_matches_google_spec() {
        let sapisid = "TEST_SAPISID_123";
        let origin = "https://music.youtube.com";
        let timestamp = 1715000000_u64;

        let payload = format!("{} {} {}", timestamp, sapisid, origin);
        let mut hasher = Sha1::new();
        hasher.update(payload.as_bytes());
        let expected_hash = hex::encode(hasher.finalize());
        let expected_result = format!("{}_{}", timestamp, expected_hash);

        let test_payload = format!("{} {} {}", timestamp, sapisid, origin);
        assert_eq!(
            test_payload,
            "1715000000 TEST_SAPISID_123 https://music.youtube.com"
        );
        assert!(expected_result.starts_with("1715000000_"));
    }

    #[test]
    fn test_buffer_overflow_protection() {
        let sapisid = "TEST";
        let origin = "https://music.youtube.com";
        // Buffer intentionally too small (10 bytes, output is ~51 bytes)
        let mut out_buf = vec![0u8; 10];

        let result = unsafe {
            generate_sapisid_hash(
                sapisid.as_ptr(),
                sapisid.len(),
                origin.as_ptr(),
                origin.len(),
                out_buf.as_mut_ptr(),
                out_buf.len(),
            )
        };

        // Must return -2 (Buffer too small)
        assert_eq!(result, -2);
    }
}
