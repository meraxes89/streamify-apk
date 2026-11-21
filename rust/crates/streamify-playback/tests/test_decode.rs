//! The decode-stage suite: the symphonia backend's incremental-feed
//! protocol against the checked-in fixtures, its geometry discovery,
//! its EOS semantics and its failure-closed behavior on hostile input
//! (the RFC §5.2 attack-surface gate).

use std::path::PathBuf;
use std::sync::Arc;

use streamify_core::{AudioErrorKind, EngineError};
use streamify_playback::{FrameBuf, SymphoniaDecoder, TrackDecoder};

fn fixture(name: &str) -> Arc<Vec<u8>> {
    let mut path = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
    path.push("../../../fixtures/audio");
    path.push(name);
    Arc::new(std::fs::read(&path).unwrap_or_else(|e| panic!("{}: {e}", path.display())))
}

/// Feed the decoder in fixed chunks (the producer's segmented cadence)
/// and collect the interleaved output.
fn decode_chunked(bytes: &[u8], chunk: usize) -> (Vec<f32>, SymphoniaDecoder) {
    let mut decoder = SymphoniaDecoder::new().unwrap();
    let mut out = FrameBuf::with_capacity(65_536, 2).unwrap();
    for piece in bytes.chunks(chunk) {
        decoder.push_range(piece, &mut out).unwrap();
    }
    decoder.finish(&mut out).unwrap();
    (out.as_slice().to_vec(), decoder)
}

// ─────────────────────────────────────────────────────────────────────────────
// The protocol-correctness gate: chunked == whole-file
// ─────────────────────────────────────────────────────────────────────────────

/// THE incremental-feed proof: decoding in small, deliberately
/// packet-misaligned chunks must produce bit-identical PCM to decoding
/// the whole file at once — the rewind protocol recovers every torn
/// frontier exactly. Both fixtures (48 kHz passthrough geometry and
/// 44.1 kHz) run at three chunk sizes including one far below the
/// packet size (the worst-case tearing cadence).
#[test]
fn chunked_feed_matches_whole_file_decode() {
    for name in [
        "playback_selftest_440hz_48k.wav",
        "playback_selftest_440hz_44k1.wav",
    ] {
        let bytes = fixture(name);
        let (whole, whole_decoder) = decode_chunked(&bytes, usize::MAX / 2);
        assert!(whole_decoder.is_eos(), "{name}: whole-file feed reaches EOS");
        for chunk in [1024usize, 4096, 64 * 1024] {
            let (chunked, chunked_decoder) = decode_chunked(&bytes, chunk);
            assert!(chunked_decoder.is_eos(), "{name}@{chunk}: EOS reached");
            assert_eq!(
                whole, chunked,
                "{name}@{chunk}: chunked decode must be bit-identical to whole-file"
            );
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Geometry + content gates
// ─────────────────────────────────────────────────────────────────────────────

/// The decoder reports the container's geometry only after the probe
/// completes (the first partial header ranges report None), and the
/// reported rate/channels match the fixture.
#[test]
fn geometry_is_discovered_after_the_probe() {
    let bytes = fixture("playback_selftest_440hz_48k.wav");
    let mut decoder = SymphoniaDecoder::new().unwrap();
    let mut out = FrameBuf::with_capacity(4096, 2).unwrap();

    // A sub-header range discovers nothing yet.
    decoder.push_range(&bytes[..16], &mut out).unwrap();
    assert!(decoder.geometry().is_none());
    assert!(!decoder.is_eos());

    // The NEXT consecutive range (header remainder + first data)
    // completes the probe; one full 1152-frame packet (9216 bytes of
    // data) must arrive before the first frames decode.
    decoder.push_range(&bytes[16..32_768], &mut out).unwrap();
    let geometry = decoder.geometry().expect("probe completed");
    assert_eq!(geometry.sample_rate_hz, 48_000);
    assert_eq!(geometry.channels, 2);
    assert!(!out.is_empty(), "the first data packets decoded");
}

/// The 44.1 kHz fixture reports its native rate (the resample stage —
/// not the decoder — canonicalizes).
#[test]
fn geometry_reports_native_input_rate() {
    let bytes = fixture("playback_selftest_440hz_44k1.wav");
    let (_, decoder) = decode_chunked(&bytes, 64 * 1024);
    let geometry = decoder.geometry().unwrap();
    assert_eq!(geometry.sample_rate_hz, 44_100);
    assert_eq!(geometry.channels, 2);
}

/// The 48 kHz fixture decodes to the exact frame count the header
/// declares (72 000 frames = 1.5 s), with a 440 Hz dominant tone
/// (Goertzel probe) and sane amplitude after the LUFS stage is NOT in
/// play (the decoder emits raw decode; loudness is a producer stage).
#[test]
fn wav_fixture_decodes_exact_frames_and_tone() {
    let bytes = fixture("playback_selftest_440hz_48k.wav");
    let (samples, decoder) = decode_chunked(&bytes, 32 * 1024);
    assert_eq!(decoder.geometry().unwrap().sample_rate_hz, 48_000);
    assert_eq!(samples.len(), 72_000 * 2, "exact declared frame count");

    // Goertzel magnitude at 440 Hz vs a neighboring bin (probe the
    // left channel).
    let goertzel = |freq: f64| -> f64 {
        let coeff = 2.0 * std::f64::consts::PI * freq / 48_000.0;
        let mut s1 = 0.0f64;
        let mut s2 = 0.0f64;
        for sample in samples.iter().step_by(2) {
            let s = *sample as f64 + (2.0 * coeff.cos()) * s1 - s2;
            s2 = s1;
            s1 = s;
        }
        s1 * s1 + s2 * s2 - (2.0 * coeff.cos()) * s1 * s2
    };
    let at_440 = goertzel(440.0);
    let at_500 = goertzel(500.0);
    assert!(
        at_440 > at_500 * 100.0,
        "dominant energy at 440 Hz (440: {at_440:.3}, 500: {at_500:.3})"
    );

    // Amplitude sanity: the fixture was written at 0.5 (-6 dBFS); the
    // decode must preserve it within quantization.
    let peak = samples.iter().step_by(2).fold(0.0f32, |a, s| a.max(s.abs()));
    assert!((peak - 0.5).abs() < 0.01, "peak amplitude {peak}");
}

// ─────────────────────────────────────────────────────────────────────────────
// Failure-closed gates (RFC §5.2: untrusted container parsing)
// ─────────────────────────────────────────────────────────────────────────────

/// Garbage bytes fail as the typed `Audio::FormatUnsupported` error —
/// never a panic, never silent acceptance. Deterministic across seeds.
#[test]
fn garbage_input_fails_closed_typed() {
    for seed in [0u64, 1, 42, 0xDEAD_BEEF] {
        let mut rng = seed;
        let mut garbage = vec![0u8; 8192];
        for byte in garbage.iter_mut() {
            rng = rng.wrapping_mul(6_364_136_223_846_793_005).wrapping_add(1_442_695_040_888_963_407);
            *byte = (rng >> 33) as u8;
        }
        let mut decoder = SymphoniaDecoder::new().unwrap();
        let mut out = FrameBuf::with_capacity(4096, 2).unwrap();
        match decoder.push_range(&garbage, &mut out) {
            Err(EngineError::Audio {
                kind: AudioErrorKind::FormatUnsupported,
                ..
            }) => {}
            Err(other) => panic!("seed {seed}: wrong error {other:?}"),
            Ok(()) => {
                // Some random prefixes can parse as a container header
                // start; the finish() on a garbage tail must still fail
                // closed or produce nothing.
                let result = decoder.finish(&mut out);
                assert!(
                    result.is_err() || out.is_empty(),
                    "seed {seed}: garbage must not decode audio"
                );
            }
        }
    }
}

/// A truncated fixture (header present, data cut mid-stream) decodes
/// its full prefix — symphonia's RIFF reader clamps the data chunk to
/// the source's actual byte length, so the final short packet is
/// served, not lost — and reports EOS cleanly after `finish`: a
/// partial download never hangs, never loops, never invents frames
/// beyond the feed.
#[test]
fn truncated_source_decodes_prefix_and_finishes() {
    let bytes = fixture("playback_selftest_440hz_48k.wav");
    let cut = &bytes[..44 + 19_200 * 8]; // header + 0.4 s (19 200 frames) of data
    let (samples, decoder) = decode_chunked(cut, 8192);
    assert!(decoder.is_eos());
    assert_eq!(
        samples.len(),
        19_200 * 2,
        "every fed frame decodes (byte_len-clamped tail packet included)"
    );
}
