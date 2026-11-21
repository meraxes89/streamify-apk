//! Sovereign HAL Phase 1 (D3): the Opus + AAC decode gate.
//!
//! Pins the Phase 1 codec contract on checked-in fixtures:
//! * **Opus itag 251** (48 kHz, Ogg-Opus AND the server's raw
//!   #225-framing) decodes through BOTH tiers (libopus primary,
//!   symphonia-opus fallback) with FFT-verified 440 Hz (L) / 880 Hz (R)
//!   peaks and tier-level agreement.
//! * **AAC-LC itag 140** (44.1 kHz M4A) keeps decoding through
//!   symphonia and resamples through `CanonicalSrc` (rubato) — the
//!   48 kHz Opus lane pins `CanonicalSrc`'s lossless passthrough (the
//!   rubato bypass contract).
//! * **ebur128 oracle**: the golden-pinned `lufs.rs` integrated
//!   loudness matches ebur128 within ±0.1 LUFS on the decoded fixtures.

use std::path::PathBuf;

use streamify_audio::{CanonicalSrc, LufsAnalyzer, CANONICAL_RATE_HZ};
use streamify_playback::{
    FrameBuf, OpusTier, OpusTrackDecoder, SymphoniaDecoder, TrackDecoder,
};

fn fixture(name: &str) -> Vec<u8> {
    let mut path = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
    path.push("../../../fixtures/audio");
    path.push(name);
    std::fs::read(&path).unwrap_or_else(|e| panic!("{}: {e}", path.display()))
}

/// Decode `bytes` through a `TrackDecoder` in fixed chunks, returning
/// the interleaved output.
fn decode_chunked<D: TrackDecoder>(mut decoder: D, bytes: &[u8], chunk: usize) -> Vec<f32> {
    let mut out = FrameBuf::with_capacity(65_536, 2).unwrap();
    for piece in bytes.chunks(chunk) {
        decoder.push_range(piece, &mut out).unwrap();
    }
    decoder.finish(&mut out).unwrap();
    assert!(decoder.is_eos());
    out.as_slice().to_vec()
}

/// Radix-2 FFT peak check (the Python verifier's algorithm, in-test):
/// `freq` must be >20× the spectrum median in `left` (and, when
/// `right_freq` is given, that channel's peak too).
fn fft_peaks_ok(interleaved: &[f32], rate: u32, left_hz: f64, right_hz: f64) -> bool {
    let n = {
        let mut n = 1usize;
        while n * 2 <= interleaved.len() / 2 {
            n *= 2;
        }
        n
    };
    if n < 4096 {
        return false;
    }
    let mut ok = true;
    for (ch, hz) in [(0usize, left_hz), (1usize, right_hz)] {
        let mut re = vec![0.0f64; n];
        let mut im = vec![0.0f64; n];
        for i in 0..n {
            let s = interleaved[(i * 2 + ch) % interleaved.len()] as f64;
            re[i] = s * (0.5 * (1.0 - (2.0 * std::f64::consts::PI * i as f64 / (n - 1) as f64).cos()));
        }
        // bit-reversal + butterflies
        let mut j = 0usize;
        for i in 1..n {
            let mut bit = n >> 1;
            while j & bit != 0 {
                j ^= bit;
                bit >>= 1;
            }
            j |= bit;
            if i < j {
                re.swap(i, j);
                im.swap(i, j);
            }
        }
        let mut len = 2;
        while len <= n {
            let ang = -2.0 * std::f64::consts::PI / len as f64;
            let (wr, wi) = (ang.cos(), ang.sin());
            let mut i = 0;
            while i < n {
                let (mut cr, mut ci) = (1.0f64, 0.0f64);
                for k in 0..len / 2 {
                    let (ur, ui) = (re[i + k], im[i + k]);
                    let (xr, xi) = (re[i + k + len / 2], im[i + k + len / 2]);
                    let (vr, vi) = (xr * cr - xi * ci, xr * ci + xi * cr);
                    re[i + k] = ur + vr;
                    im[i + k] = ui + vi;
                    re[i + k + len / 2] = ur - vr;
                    im[i + k + len / 2] = ui - vi;
                    let ncr = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = ncr;
                }
                i += len;
            }
            len <<= 1;
        }
        let bin_hz = rate as f64 / n as f64;
        let mags: Vec<f64> = (0..n / 2).map(|i| (re[i].hypot(im[i]))).collect();
        let mut sorted = mags.clone();
        sorted.sort_by(|a, b| a.partial_cmp(b).unwrap());
        let median = sorted[n / 4] + 1e-12;
        let bin = (hz / bin_hz).round() as usize;
        // Search a ±2-bin window (the verifier's tolerance).
        let near: [usize; 5] = [bin - 2, bin - 1, bin, bin + 1, bin + 2];
        let peak = near
            .iter()
            .filter(|&&b| b < mags.len())
            .map(|&b| mags[b])
            .fold(0.0f64, f64::max);
        if peak <= 20.0 * median {
            eprintln!(
                "channel {ch}: {hz} Hz peak {peak:.3} vs median {median:.5} — FAIL"
            );
            ok = false;
        }
    }
    ok
}

// ─────────────────────────────────────────────────────────────────────────────
// Opus: both framings, both tiers
// ─────────────────────────────────────────────────────────────────────────────

#[test]
fn opus_ogg_decodes_both_tiers_with_tones() {
    let bytes = fixture("opus_dual_tone_48k.ogg");
    for tier in [OpusTier::Primary, OpusTier::Fallback] {
        let pcm = decode_chunked(OpusTrackDecoder::new(tier), &bytes, 4096);
        assert!(pcm.len() > 48_000, "tier {tier:?}: decoded {} samples", pcm.len());
        assert!(
            fft_peaks_ok(&pcm, 48_000, 440.0, 880.0),
            "tier {tier:?}: 440 Hz (L) / 880 Hz (R) peaks must verify"
        );
    }
}

#[test]
fn opus_raw_server_framing_decodes_both_tiers() {
    // The #225 EBML extractor shape: OpusHead + UInt16BE length-prefixed
    // packets. The chunk sizes tear packet boundaries deliberately.
    let bytes = fixture("opus_dual_tone_48k.raw_opus");
    for tier in [OpusTier::Primary, OpusTier::Fallback] {
        for chunk in [7usize, 512, 8192] {
            let pcm = decode_chunked(OpusTrackDecoder::new(tier), &bytes, chunk);
            assert!(pcm.len() > 48_000, "tier {tier:?}@{chunk}: too few samples");
            assert!(
                fft_peaks_ok(&pcm, 48_000, 440.0, 880.0),
                "tier {tier:?}@{chunk}: tones must verify through the raw framing"
            );
        }
    }
}

#[test]
fn opus_tiers_agree_within_codec_tolerance() {
    // libopus vs symphonia-opus on the SAME raw-framed packets: the
    // inter-tier tolerance (pure-Rust vs reference C decode) is loose
    // sample-wise but the integrated loudness must agree within 0.5 LU
    // and the geometry must match exactly.
    let bytes = fixture("opus_dual_tone_48k.raw_opus");
    let primary = decode_chunked(OpusTrackDecoder::new(OpusTier::Primary), &bytes, 8192);
    let fallback = decode_chunked(OpusTrackDecoder::new(OpusTier::Fallback), &bytes, 8192);
    // Geometry: both 48 kHz stereo, near-identical length (pre-skip
    // handling may differ by the trailing packet).
    let lp = primary.len();
    let lf = fallback.len();
    let drift = lp.abs_diff(lf);
    assert!(drift <= 5760 * 2, "lengths diverge: primary {lp} vs fallback {lf}");
    let n = lp.min(lf);
    // Loudness agreement.
    let lufs_p = integrated_lufs(&primary[..n]);
    let lufs_f = integrated_lufs(&fallback[..n]);
    assert!(
        (lufs_p - lufs_f).abs() <= 0.5,
        "tier loudness divergence: libopus {lufs_p:.3} vs symphonia-opus {lufs_f:.3}"
    );
}

#[test]
fn opus_geometry_is_48k_canonical() {
    let bytes = fixture("opus_dual_tone_48k.raw_opus");
    let mut dec = OpusTrackDecoder::new(OpusTier::Primary);
    let mut out = FrameBuf::with_capacity(1024, 2).unwrap();
    dec.push_range(&bytes[..64.min(bytes.len())], &mut out).unwrap();
    let g = dec.geometry().expect("geometry after OpusHead");
    assert_eq!(g.sample_rate_hz, 48_000, "Opus ALWAYS decodes at 48 kHz");
    assert_eq!(g.channels, 2);
}

#[test]
fn opus_rejects_non_opus_bytes() {
    let mut dec = OpusTrackDecoder::new(OpusTier::Primary);
    let mut out = FrameBuf::with_capacity(16, 2).unwrap();
    let garbage = [0xABu8; 64];
    assert!(dec.push_range(&garbage, &mut out).is_err());
}

// ─────────────────────────────────────────────────────────────────────────────
// AAC itag 140 + the rubato bypass contract
// ─────────────────────────────────────────────────────────────────────────────

#[test]
fn aac_44k1_decodes_and_resamples_to_48k() {
    let bytes = fixture("aac_dual_tone_44k1.m4a");
    let pcm44 = decode_chunked(SymphoniaDecoder::new().unwrap(), &bytes, 4096);
    // The AAC fixture is 44.1 kHz — CanonicalSrc must engage rubato and
    // produce the canonical 48 kHz stream with the tones intact.
    let mut src = CanonicalSrc::new(44_100, 2).unwrap();
    assert!(!src.is_passthrough(), "44.1 kHz MUST engage the resampler");
    let mut canonical = Vec::new();
    src.process(&pcm44, &mut canonical);
    src.flush(&mut canonical);
    assert!(!canonical.is_empty());
    assert!(
        fft_peaks_ok(&canonical, CANONICAL_RATE_HZ, 440.0, 880.0),
        "resampled AAC must keep the tones"
    );
}

#[test]
fn opus_48k_pins_the_rubato_bypass() {
    // The 48 kHz Opus lane (itag 251) feeds CanonicalSrc's lossless
    // passthrough: rubato NEVER runs (bit-exact, zero latency) — the
    // bypass contract.
    let bytes = fixture("opus_dual_tone_48k.raw_opus");
    let pcm = decode_chunked(OpusTrackDecoder::new(OpusTier::Primary), &bytes, 8192);
    let mut src = CanonicalSrc::new(48_000, 2).unwrap();
    assert!(src.is_passthrough(), "48 kHz MUST bypass rubato");
    let mut canonical = Vec::new();
    let consumed = src.process(&pcm, &mut canonical);
    assert_eq!(consumed, pcm.len(), "passthrough consumes every sample");
    assert_eq!(canonical, pcm, "passthrough is bit-exact");
}

// ─────────────────────────────────────────────────────────────────────────────
// The ebur128 LUFS oracle (golden lufs.rs parity)
// ─────────────────────────────────────────────────────────────────────────────

/// Integrated loudness via lufs.rs (the production analyzer).
fn integrated_lufs(interleaved: &[f32]) -> f64 {
    let mut analyzer = LufsAnalyzer::new().unwrap();
    analyzer.process(interleaved);
    analyzer.integrated_lufs()
}

/// Integrated loudness via ebur128 (the oracle).
fn ebur128_lufs(interleaved: &[f32]) -> f64 {
    let mut e = ebur128::EbuR128::new(2, 48_000, ebur128::Mode::I).unwrap();
    e.add_frames_f32(interleaved).unwrap();
    e.loudness_global().unwrap()
}

#[test]
fn lufs_matches_ebur128_oracle_within_0_1() {
    for name in [
        "opus_dual_tone_48k.raw_opus",
        "aac_dual_tone_44k1.m4a",
    ] {
        let bytes = fixture(name);
        let pcm = match name.starts_with("opus") {
            true => decode_chunked(OpusTrackDecoder::new(OpusTier::Primary), &bytes, 8192),
            false => {
                let pcm44 = decode_chunked(SymphoniaDecoder::new().unwrap(), &bytes, 4096);
                let mut src = CanonicalSrc::new(44_100, 2).unwrap();
                let mut canonical = Vec::new();
                src.process(&pcm44, &mut canonical);
                src.flush(&mut canonical);
                canonical
            }
        };
        let ours = integrated_lufs(&pcm);
        let oracle = ebur128_lufs(&pcm);
        assert!(
            (ours - oracle).abs() <= 0.1,
            "{name}: lufs.rs {ours:.3} vs ebur128 {oracle:.3} (±0.1 LUFS gate)"
        );
    }
}
