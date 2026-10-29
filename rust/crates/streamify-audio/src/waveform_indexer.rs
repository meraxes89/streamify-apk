//! waveform_indexer.rs — waveform amplitude peak generator,
//! peak-preserving RMS downsampler & compact binary cache indexer
//! (feat/phase5-rust-gesture-physics-waveform, directive §2.2).
//!
//! # Amplitude extraction & downsampling
//!
//! [`generate_waveform`] ingests a raw decoded PCM byte stream
//! (16-bit signed little-endian samples — the canonical Android
//! `MediaCodec` decode output) and reduces it to a normalized visual
//! bar array of `N ∈ [100, 500]` buckets:
//!
//! * **RMS energy** per bucket: `√(Σs²/n)` — f64 accumulators, so
//!   hours-long tracks keep their precision;
//! * **local peak** per bucket: `max|s|`;
//! * **peak-preserving blend**: `amp = max(RMS, 0.75·peak)` — a steady
//!   tone renders at its RMS level (0.75·peak ≈ 1.06·RMS for a sine),
//!   while a drum hit or vocal attack occupying a small fraction of
//!   its bucket (pure RMS would flatten it by √n) still spikes at
//!   75% of its true peak: transients stay visually crisp.
//!
//! Bars are normalized against the loudest bucket (`255` = global
//! max, silence = all-zero — a global-max scale rather than fixed
//! full-scale so quiet tracks still fill the waveform view).
//!
//! # Compact binary cache
//!
//! The `STWF` cache format is a fixed 10-byte header plus the raw
//! amplitude bytes:
//!
//! ```text
//! [Magic "STWF": 4B][DurationMs: 4B u32 LE][BucketCount: 2B u16 LE][Amplitudes: N bytes]
//! ```
//!
//! [`decode_cache_view`] parses the header and borrows the amplitude
//! slice in place — zero-copy retrieval for track pre-buffering;
//! [`save_cache`] / [`load_cache`] round-trip it through disk. Byte
//! layout is exact (no padding, no alignment requirements), asserted
//! by byte-level round-trip tests.

use std::fs;
use std::path::Path;

/// Cache file magic: `b"STWF"` (STreamify WaveForm).
pub const CACHE_MAGIC: [u8; 4] = *b"STWF";

/// Smallest legal visual bucket count (directive §2.2).
pub const MIN_BUCKETS: usize = 100;

/// Largest legal visual bucket count (directive §2.2).
pub const MAX_BUCKETS: usize = 500;

/// PCM duration bookkeeping default when the caller does not know the
/// decode sample rate (CD-quality).
pub const DEFAULT_SAMPLE_RATE_HZ: u32 = 44_100;

/// `[Magic:4B][DurationMs:4B][BucketCount:2B]` — fixed header size.
pub const CACHE_HEADER_LEN: usize = 10;

/// Weight of the local peak in the peak-preserving blend
/// `max(RMS, SCALE·peak)`.
const TRANSIENT_PEAK_SCALE: f32 = 0.75;

/// Failure modes of waveform generation and cache handling.
#[derive(Debug)]
pub enum WaveformError {
    /// The audio payload was empty (zero-length).
    EmptyAudio,
    /// PCM byte length is odd — a partial sample frame.
    OddByteLength,
    /// Bucket count outside `[MIN_BUCKETS, MAX_BUCKETS]`.
    InvalidBucketCount(usize),
    /// Cache blob does not start with the `STWF` magic.
    InvalidCacheMagic,
    /// Cache blob shorter than the fixed header.
    TruncatedCache,
    /// Cache blob length does not match its declared bucket count.
    CacheLengthMismatch {
        /// `CACHE_HEADER_LEN + declared bucket count`.
        expected: usize,
        /// Bytes actually present.
        actual: usize,
    },
    /// Disk I/O failure while saving or loading a cache.
    Io(std::io::Error),
}

impl core::fmt::Display for WaveformError {
    fn fmt(&self, f: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
        match self {
            WaveformError::EmptyAudio => f.write_str("empty audio payload"),
            WaveformError::OddByteLength => f.write_str("odd PCM byte length (partial sample)"),
            WaveformError::InvalidBucketCount(n) => {
                write!(f, "bucket count {n} outside [{MIN_BUCKETS}, {MAX_BUCKETS}]")
            }
            WaveformError::InvalidCacheMagic => f.write_str("invalid cache magic (expected STWF)"),
            WaveformError::TruncatedCache => f.write_str("cache blob shorter than its header"),
            WaveformError::CacheLengthMismatch { expected, actual } => {
                write!(
                    f,
                    "cache length mismatch: expected {expected} bytes, got {actual}"
                )
            }
            WaveformError::Io(e) => write!(f, "cache I/O failure: {e}"),
        }
    }
}

impl std::error::Error for WaveformError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            WaveformError::Io(e) => Some(e),
            _ => None,
        }
    }
}

impl From<std::io::Error> for WaveformError {
    fn from(e: std::io::Error) -> Self {
        WaveformError::Io(e)
    }
}

/// Bucket count inside the legal `[MIN_BUCKETS, MAX_BUCKETS]` range.
fn bucket_count_valid(n: usize) -> bool {
    (MIN_BUCKETS..=MAX_BUCKETS).contains(&n)
}

/// Per-bucket accumulator: f64 energy + f32 peak (see module docs).
#[derive(Default, Clone, Copy)]
struct BucketAcc {
    sum_sq: f64,
    peak: f32,
    count: u64,
}

/// Generate a normalized visual waveform bar array from raw PCM.
///
/// `pcm_bytes` is 16-bit signed little-endian sample data (mono; a
/// stereo stream should be downmixed or interleaved — the waveform
/// view does not distinguish channels). `bucket_count` must lie in
/// `[MIN_BUCKETS, MAX_BUCKETS]`. Returns exactly `bucket_count` bytes,
/// each `0..=255`.
///
/// If the clip is shorter than the bucket count, the time-proportional
/// bucket boundaries leave interior buckets empty (each bucket spans
/// less than one sample of time): empty buckets render as zero while
/// the covered ones still carry the correct energy. Single pass,
/// O(len).
pub fn generate_waveform(pcm_bytes: &[u8], bucket_count: usize) -> Result<Vec<u8>, WaveformError> {
    if !bucket_count_valid(bucket_count) {
        return Err(WaveformError::InvalidBucketCount(bucket_count));
    }
    if pcm_bytes.is_empty() {
        return Err(WaveformError::EmptyAudio);
    }
    if !pcm_bytes.len().is_multiple_of(2) {
        return Err(WaveformError::OddByteLength);
    }
    let sample_count = pcm_bytes.len() / 2;

    let mut buckets = vec![BucketAcc::default(); bucket_count];
    let mut offset = 0usize;
    for (b, acc) in buckets.iter_mut().enumerate() {
        // Bucket b covers samples [b·n/N, (b+1)·n/N) — integer math,
        // no per-sample division, even distribution of the remainder.
        // Compute in u64: on 32-bit targets (armeabi-v7a) `(b+1) * n`
        // overflows usize for any track longer than ~3 min @ 44.1 kHz
        // (n ≥ 2^32/500), silently wrapping in release and skipping or
        // overrunning bucket boundaries — invisible to the 64-bit host
        // test runner. u64 keeps the math exact for any pcm_bytes length
        // addressable in memory (≤ 2^32 samples ⇒ product ≤ 2^41).
        let end = ((b as u64 + 1) * sample_count as u64 / bucket_count as u64) as usize;
        while offset < end {
            let s = i16::from_le_bytes([pcm_bytes[2 * offset], pcm_bytes[2 * offset + 1]]);
            let s_f = s as f32;
            acc.sum_sq += (s_f as f64) * (s_f as f64);
            acc.peak = acc.peak.max(s_f.abs());
            acc.count += 1;
            offset += 1;
        }
    }

    // Peak-preserving blend per bucket, then global-max normalization.
    let mut max_amp = 0.0f32;
    let mut blended = vec![0.0f32; bucket_count];
    for (acc, out) in buckets.iter().zip(blended.iter_mut()) {
        if acc.count == 0 {
            continue; // empty bucket stays silent
        }
        let rms = (acc.sum_sq / acc.count as f64).sqrt() as f32;
        let amp = rms.max(TRANSIENT_PEAK_SCALE * acc.peak);
        max_amp = max_amp.max(amp);
        *out = amp;
    }
    let scale = if max_amp > 0.0 { 255.0 / max_amp } else { 0.0 };
    Ok(blended
        .iter()
        .map(|a| (a * scale).round().clamp(0.0, 255.0) as u8)
        .collect())
}

/// Track duration in milliseconds for `sample_count` PCM frames at
/// `sample_rate_hz` (saturating at `u32::MAX`).
pub fn duration_ms_for_samples(sample_count: usize, sample_rate_hz: u32) -> u32 {
    if sample_rate_hz == 0 {
        return 0;
    }
    // saturating_mul: u64::MAX frames × 1000 must not overflow debug
    // builds; the clamp to u32::MAX dominates either way.
    ((sample_count as u64).saturating_mul(1000) / sample_rate_hz as u64).min(u32::MAX as u64) as u32
}

/// Zero-copy parsed view of a cache blob: the header fields plus the
/// amplitude bytes borrowed straight from the input.
#[derive(Debug, Clone, Copy)]
pub struct WaveformCacheView<'a> {
    /// Track duration from the cache header.
    pub duration_ms: u32,
    /// `amplitudes.len()` from the cache header.
    pub bucket_count: usize,
    /// The `N` amplitude bytes, borrowed from the parsed blob.
    pub amplitudes: &'a [u8],
}

/// Parse and validate a cache blob, borrowing the amplitudes in place
/// (zero-copy retrieval). Requires the exact declared length.
pub fn decode_cache_view(bytes: &[u8]) -> Result<WaveformCacheView<'_>, WaveformError> {
    if bytes.len() < CACHE_HEADER_LEN {
        return Err(WaveformError::TruncatedCache);
    }
    if bytes[..CACHE_MAGIC.len()] != CACHE_MAGIC {
        return Err(WaveformError::InvalidCacheMagic);
    }
    let duration_ms = u32::from_le_bytes([bytes[4], bytes[5], bytes[6], bytes[7]]);
    let bucket_count = u16::from_le_bytes([bytes[8], bytes[9]]) as usize;
    if !bucket_count_valid(bucket_count) {
        return Err(WaveformError::InvalidBucketCount(bucket_count));
    }
    let expected = CACHE_HEADER_LEN + bucket_count;
    if bytes.len() != expected {
        return Err(WaveformError::CacheLengthMismatch {
            expected,
            actual: bytes.len(),
        });
    }
    Ok(WaveformCacheView {
        duration_ms,
        bucket_count,
        amplitudes: &bytes[CACHE_HEADER_LEN..],
    })
}

/// Owned cache record (decode of a blob or load from disk).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct WaveformCache {
    /// Track duration from the cache header.
    pub duration_ms: u32,
    /// The `N ∈ [100, 500]` amplitude bytes.
    pub amplitudes: Vec<u8>,
}

/// Serialize a cache blob: `[STWF:4B][duration_ms:4B LE][N:2B LE][amps]`.
pub fn encode_cache(duration_ms: u32, amplitudes: &[u8]) -> Result<Vec<u8>, WaveformError> {
    if !bucket_count_valid(amplitudes.len()) {
        return Err(WaveformError::InvalidBucketCount(amplitudes.len()));
    }
    let mut blob = Vec::with_capacity(CACHE_HEADER_LEN + amplitudes.len());
    blob.extend_from_slice(&CACHE_MAGIC);
    blob.extend_from_slice(&duration_ms.to_le_bytes());
    blob.extend_from_slice(&(amplitudes.len() as u16).to_le_bytes());
    blob.extend_from_slice(amplitudes);
    Ok(blob)
}

/// Parse a cache blob into an owned record.
pub fn decode_cache(bytes: &[u8]) -> Result<WaveformCache, WaveformError> {
    let view = decode_cache_view(bytes)?;
    Ok(WaveformCache {
        duration_ms: view.duration_ms,
        amplitudes: view.amplitudes.to_vec(),
    })
}

/// Write a cache blob to `path` (single `fs::write`, disk-cached for
/// lightning-fast retrieval during track pre-buffering).
pub fn save_cache(
    path: impl AsRef<Path>,
    duration_ms: u32,
    amplitudes: &[u8],
) -> Result<(), WaveformError> {
    let blob = encode_cache(duration_ms, amplitudes)?;
    fs::write(path, blob)?;
    Ok(())
}

/// Read and parse the cache file at `path`.
pub fn load_cache(path: impl AsRef<Path>) -> Result<WaveformCache, WaveformError> {
    let bytes = fs::read(path)?;
    decode_cache(&bytes)
}
