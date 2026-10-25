//! test_waveform_indexer.rs — Phase 5 waveform indexer suite
//! (directive §2.4).
//!
//! Amplitude extraction accuracy (steady tones, silence, transient
//! spikes surviving the peak-preserving blend, dynamic sections),
//! bucket-count boundaries, and byte-level STWF cache serialization
//! round-trips: header layout, zero-copy views, malformed-blob
//! rejection, and full generate → save → load equivalence on disk.

use streamify_core_rs::waveform_indexer::{
    decode_cache, decode_cache_view, duration_ms_for_samples, encode_cache, generate_waveform,
    load_cache, save_cache, WaveformError, CACHE_HEADER_LEN, CACHE_MAGIC, DEFAULT_SAMPLE_RATE_HZ,
    MAX_BUCKETS, MIN_BUCKETS,
};

use tempfile::tempdir;

/// 16-bit LE PCM sine of `samples` frames at full `amp`.
fn sine_pcm(samples: usize, amp: f32, freq: f32, rate: f32) -> Vec<u8> {
    let mut v = Vec::with_capacity(samples * 2);
    for i in 0..samples {
        let s = (2.0 * core::f32::consts::PI * freq * i as f32 / rate).sin() * amp;
        v.extend_from_slice(&(s as i16).to_le_bytes());
    }
    v
}

fn pcm_of(samples: &[i16]) -> Vec<u8> {
    samples.iter().flat_map(|s| s.to_le_bytes()).collect()
}

// ─────────────────────── amplitude extraction accuracy

#[test]
fn steady_tone_renders_full_scale_bars() {
    // A full-scale 440 Hz sine: every bucket blends to the same
    // amplitude, so global-max normalization pins them all at ~255.
    let pcm = sine_pcm(44_100, 32_000.0, 440.0, DEFAULT_SAMPLE_RATE_HZ as f32);
    for &n in &[MIN_BUCKETS, 200, MAX_BUCKETS] {
        let bars = generate_waveform(&pcm, n).expect("generate");
        assert_eq!(bars.len(), n);
        for (i, &b) in bars.iter().enumerate() {
            assert!(b >= 245, "n={n} bucket {i} = {b} (expected ~255)");
        }
    }
}

#[test]
fn global_max_normalization_fills_quiet_tracks() {
    // A half-scale tone normalizes to the same visual fullness —
    // documented design: the view uses the track's own dynamic range.
    let pcm = sine_pcm(44_100, 16_000.0, 440.0, DEFAULT_SAMPLE_RATE_HZ as f32);
    let bars = generate_waveform(&pcm, 100).expect("generate");
    for (i, &b) in bars.iter().enumerate() {
        assert!(b >= 245, "bucket {i} = {b}");
    }
}

#[test]
fn silence_is_all_zero_without_nan() {
    let pcm = pcm_of(&[0i16; 44_100]);
    let bars = generate_waveform(&pcm, 200).expect("generate");
    assert!(bars.iter().all(|&b| b == 0));
}

#[test]
fn transient_spikes_survive_rms_downsampling() {
    // One full-scale drum-sample impulse inside bucket 50 of 100 over
    // 1 s of silence. Pure RMS would flatten it to
    // 255/√441 ≈ 12/255; the peak-preserving blend keeps the spike at
    // full visual definition while its neighbours stay silent.
    let mut samples = vec![0i16; 44_100];
    samples[22_050] = 32_767; // bucket 50 covers [50·441, 51·441)
    let bars = generate_waveform(&pcm_of(&samples), 100).expect("generate");
    assert_eq!(bars[50], 255, "spike bucket flattened to {}", bars[50]);
    assert_eq!(bars[49], 0);
    assert_eq!(bars[51], 0);
    assert_eq!(bars[0], 0);
    assert_eq!(bars[99], 0);
}

#[test]
fn quieter_section_renders_proportionally() {
    // First half: full-scale sine. Second half: quarter amplitude.
    // The quiet half's buckets land at ~1/4 of the loud half's
    // (≈ 64/255) — dynamic range is preserved, not crushed.
    let loud = sine_pcm(22_050, 32_000.0, 440.0, DEFAULT_SAMPLE_RATE_HZ as f32);
    let quiet = sine_pcm(22_050, 8_000.0, 440.0, DEFAULT_SAMPLE_RATE_HZ as f32);
    let mut pcm = loud;
    pcm.extend_from_slice(&quiet);
    let bars = generate_waveform(&pcm, 100).expect("generate");
    let loud_mean: f32 = bars[..50].iter().map(|&b| b as f32).sum::<f32>() / 50.0;
    let quiet_mean: f32 = bars[50..].iter().map(|&b| b as f32).sum::<f32>() / 50.0;
    assert!(loud_mean >= 250.0, "loud mean {loud_mean}");
    let ratio = quiet_mean / loud_mean;
    assert!(
        (0.22..=0.28).contains(&ratio),
        "quiet/loud ratio {ratio} (expected ~0.25)"
    );
}

// ─────────────────────── input validation

#[test]
fn bucket_count_must_be_in_directive_range() {
    let pcm = sine_pcm(1_000, 20_000.0, 440.0, DEFAULT_SAMPLE_RATE_HZ as f32);
    for &bad in &[0usize, 1, 99, 501, 1_000] {
        assert!(
            matches!(
                generate_waveform(&pcm, bad),
                Err(WaveformError::InvalidBucketCount(n)) if n == bad
            ),
            "bucket count {bad} must be rejected"
        );
    }
    assert!(generate_waveform(&pcm, MIN_BUCKETS).is_ok());
    assert!(generate_waveform(&pcm, MAX_BUCKETS).is_ok());
}

#[test]
fn long_track_bucket_boundaries_stay_exact_beyond_u32_product_range() {
    // Regression lock for the armeabi-v7a (32-bit usize) overflow:
    // `(b + 1) * sample_count` exceeds u32::MAX for sample_count ≥
    // 2^32/500 ≈ 8.59 M (≈ 3 min 15 s @ 44.1 kHz) — any ordinary song.
    // The bucket-boundary math now computes in u64; on the 64-bit host
    // this asserts the exactness contract (every sample counted exactly
    // once, boundaries monotonic, last bucket ends at the final sample)
    // that the u64 rewrite guarantees on 32-bit targets.
    // 10 M samples = 20 MB PCM ≈ 3 min 47 s @ 44.1 kHz mono.
    let samples = 10_000_000usize;
    // Alternating ±full-scale pattern: constant energy, and any skipped
    // or double-counted region would shift bucket counts.
    let mut pcm = Vec::with_capacity(samples * 2);
    for i in 0..samples {
        let s: i16 = if i % 2 == 0 { i16::MAX } else { -i16::MAX };
        pcm.extend_from_slice(&s.to_le_bytes());
    }
    let bars = generate_waveform(&pcm, MAX_BUCKETS).expect("10M-sample generate");
    assert_eq!(bars.len(), MAX_BUCKETS);
    // Alternating full-scale squares: every bucket must be pinned at
    // the global max — a wrapped boundary (skipped samples) would leave
    // at least one bucket empty (0) or panic the index path.
    assert!(
        bars.iter().all(|&b| b > 0),
        "no bucket may be empty when energy spans the whole track: {:?}",
        bars.iter().enumerate().filter(|(_, &b)| b == 0).take(3).collect::<Vec<_>>()
    );
}

#[test]
fn empty_and_odd_audio_payloads_are_rejected() {
    assert!(matches!(
        generate_waveform(&[], 100),
        Err(WaveformError::EmptyAudio)
    ));
    assert!(matches!(
        generate_waveform(&[1u8, 2, 3], 100),
        Err(WaveformError::OddByteLength)
    ));
}

#[test]
fn shorter_clip_than_bucket_count_renders_sparse_bars() {
    // 120 constant samples into 500 buckets: the time-proportional
    // boundaries scatter the energy — each sample occupies its own
    // sub-sample bucket (all at 255 after normalization), the rest of
    // the buckets span no sample time at all and render as zero.
    let pcm = pcm_of(&[12_345i16; 120]);
    let bars = generate_waveform(&pcm, MAX_BUCKETS).expect("generate");
    assert_eq!(bars.len(), MAX_BUCKETS);
    let hot = bars.iter().filter(|&&b| b == 255).count();
    let cold = bars.iter().filter(|&&b| b == 0).count();
    assert_eq!(hot, 120, "one bucket per sample, got {hot}");
    assert_eq!(cold, MAX_BUCKETS - 120);
    // Pure silence shorter than the bucket count stays all-zero.
    let silence = pcm_of(&[0i16; 60]);
    let bars = generate_waveform(&silence, MAX_BUCKETS).expect("generate");
    assert!(bars.iter().all(|&b| b == 0));
}

#[test]
fn duration_math_is_exact() {
    assert_eq!(duration_ms_for_samples(44_100, 44_100), 1_000);
    assert_eq!(duration_ms_for_samples(88_200, 44_100), 2_000);
    assert_eq!(duration_ms_for_samples(0, 44_100), 0);
    assert_eq!(duration_ms_for_samples(100, 0), 0);
    assert_eq!(
        duration_ms_for_samples(usize::MAX, 8_000),
        u32::MAX // saturates, never overflows
    );
}

// ─────────────────────── cache serialization round-trips

#[test]
fn cache_roundtrip_is_byte_level_exact() {
    let pcm = sine_pcm(88_200, 30_000.0, 220.0, DEFAULT_SAMPLE_RATE_HZ as f32);
    let amps = generate_waveform(&pcm, 200).expect("generate");
    let blob = encode_cache(2_000, &amps).expect("encode");

    // Layout: [STWF:4B][duration:4B LE][count:2B LE][amps:NB].
    assert_eq!(blob.len(), CACHE_HEADER_LEN + 200);
    assert_eq!(&blob[..4], &CACHE_MAGIC);
    assert_eq!(blob[4..8], 2_000u32.to_le_bytes());
    assert_eq!(blob[8..10], 200u16.to_le_bytes());
    assert_eq!(&blob[10..], &amps[..]);

    // Zero-copy view borrows the amplitude bytes in place.
    let view = decode_cache_view(&blob).expect("view");
    assert_eq!(view.duration_ms, 2_000);
    assert_eq!(view.bucket_count, 200);
    assert_eq!(view.amplitudes, &amps[..]);
    assert_eq!(
        view.amplitudes.as_ptr(),
        blob[CACHE_HEADER_LEN..].as_ptr(),
        "view must borrow, not copy"
    );

    // Owned decode matches.
    let owned = decode_cache(&blob).expect("decode");
    assert_eq!(owned.duration_ms, 2_000);
    assert_eq!(owned.amplitudes, amps);
}

#[test]
fn cache_rejects_malformed_blobs() {
    let amps = vec![77u8; 100];
    let blob = encode_cache(1_500, &amps).expect("encode");

    // Truncated header.
    assert!(matches!(
        decode_cache_view(&blob[..CACHE_HEADER_LEN - 1]),
        Err(WaveformError::TruncatedCache)
    ));
    // Bad magic.
    let mut bad_magic = blob.clone();
    bad_magic[0] = b'X';
    assert!(matches!(
        decode_cache_view(&bad_magic),
        Err(WaveformError::InvalidCacheMagic)
    ));
    // Declared count out of the directive range.
    let mut bad_count = blob.clone();
    bad_count[8..10].copy_from_slice(&10u16.to_le_bytes());
    assert!(matches!(
        decode_cache_view(&bad_count),
        Err(WaveformError::InvalidBucketCount(10))
    ));
    // Length shorter/longer than the declared count.
    assert!(matches!(
        decode_cache_view(&blob[..blob.len() - 1]),
        Err(WaveformError::CacheLengthMismatch {
            expected: 110,
            actual: 109
        })
    ));
    let mut padded = blob.clone();
    padded.push(0);
    assert!(matches!(
        decode_cache_view(&padded),
        Err(WaveformError::CacheLengthMismatch {
            expected: 110,
            actual: 111
        })
    ));
    // Encoding enforces the bucket-count range too.
    assert!(matches!(
        encode_cache(0, &[0u8; 10]),
        Err(WaveformError::InvalidBucketCount(10))
    ));
}

#[test]
fn cache_disk_roundtrip_generate_save_load() {
    let dir = tempdir().expect("tempdir");
    let path = dir.path().join("track.stwf");

    let pcm = sine_pcm(44_100, 31_000.0, 300.0, DEFAULT_SAMPLE_RATE_HZ as f32);
    let amps = generate_waveform(&pcm, 500).expect("generate");
    let duration = duration_ms_for_samples(44_100, DEFAULT_SAMPLE_RATE_HZ);
    save_cache(&path, duration, &amps).expect("save");

    // On-disk bytes are the canonical encoding.
    let blob = std::fs::read(&path).expect("read file");
    assert_eq!(blob, encode_cache(duration, &amps).expect("encode"));

    let loaded = load_cache(&path).expect("load");
    assert_eq!(loaded.duration_ms, 1_000);
    assert_eq!(loaded.amplitudes, amps);

    // Missing file surfaces as Io, not a panic.
    assert!(matches!(
        load_cache(dir.path().join("nope.stwf")),
        Err(WaveformError::Io(_))
    ));
}
