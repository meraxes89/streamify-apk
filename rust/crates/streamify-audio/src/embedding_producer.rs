//! embedding_producer.rs — the offline Sonic Fingerprint v1 producer
//! (WS-D D2, AHEAD.md Wave 3).
//!
//! Composes the three production-grade primitives that already live in
//! this crate into ONE pure-Rust, ingest-time embedding producer with
//! **zero machine-learning runtime and zero cloud dependencies**:
//!
//! * [`lufs::LufsAnalyzer`] — ITU-R BS.1770-4 integrated loudness
//!   (the K-weighted gated measurement, 48 kHz canonical);
//! * [`dsp`] — the radix-2 FFT spectrum engine + the 10-band
//!   `StudioEqualizer` octave centers (31.25 Hz .. 16 kHz) the spectral
//!   lanes are banded against;
//! * [`waveform_indexer::generate_waveform`] — the peak-preserving RMS
//!   bucketing the 64-lane waveform envelope reuses verbatim.
//!
//! On top of those it derives the remaining [`AcousticEmbeddingVector`]
//! lanes with classical, deterministic DSP: onset-energy-flux tempo
//! tracking (autocorrelation + parabolic peak interpolation with
//! octave correction), Krumhansl-Schmuckler key profiles over a
//! 12-bin chroma (mapped onto the Camelot wheel), spectral centroid /
//! flatness, crest factor, zero-crossing rate and the composite
//! danceability / energy / valence / acousticness proxies. Every
//! formula is fixed scalar math — same input, same output, bit for
//! bit, on one platform; across platforms the only wiggle room is the
//! last ULP of `sin`/`cos`/`log10`/`sqrt` (the documented ε).
//!
//! # Budget
//!
//! The WS-D D2 budget is **≤ 200 ms per 30 s reference clip on a
//! mid-range 2020 phone CPU**. The producer's cost shape: one O(n)
//! LUFS pass, one O(n) mono/waveform pass, ≤ 64 STFT windows of 1024
//! samples and an O(n) downsampled onset envelope — measured on the
//! CI host at release speed by `examples/embedding_bench.rs`
//! (median-of-21), with the headroom documented there.
//!
//! # Input contract
//!
//! Canonical 48 kHz interleaved stereo `f32` (the playback data
//! plane's staging-buffer geometry — post-`CanonicalSrc`). Other rates
//! are rejected: producers resample BEFORE this stage, exactly like
//! the loudness stage. Clips longer than 30 s analyze their first
//! 30 s (the reference-clip contract); the embedding is a pure
//! function of content, never of feed chunking.

use crate::dsp::SpectrumVisualizer;
use crate::lufs::LufsAnalyzer;
use crate::waveform_indexer::generate_waveform;

use streamify_core::acoustic_embedding::{layout, AcousticEmbeddingVector};
use streamify_core::{
    AudioErrorKind, EngineError, ACOUSTIC_EMBEDDING_VERSION, WAVEFORM_BUCKET_COUNT,
};

/// The reference-clip analysis window (seconds) — D2's contract unit.
pub const REFERENCE_CLIP_SECONDS: u32 = 30;

/// Canonical analysis rate (LUFS stage geometry).
pub const ANALYSIS_RATE_HZ: u32 = 48_000;

/// STFT window length (samples) for the fused spectral stage — fine
/// enough frequency resolution (11.7 Hz bins) that low chord roots
/// survive the bin→pitch-class mapping, coarse enough that ≤ 24
/// windows fit the D2 budget with room to spare.
const SPECTRAL_WINDOW: usize = 4096;

/// Maximum spectral windows analyzed (spread evenly) — chroma, the
/// 10-band energies, centroid and flatness all feed from this ONE
/// pass (one FFT per window, shared bins).
const MAX_SPECTRAL_WINDOWS: usize = 16;

/// Onset-envelope frame length at the downsampled rate.
const ONSET_FRAME: usize = 128;

/// Downsampling factor for the onset envelope (48 kHz → 6 kHz).
const ONSET_DOWNSAMPLE: usize = 8;

/// Onset frame hop in seconds at the downsampled rate.
const ONSET_HOP_SEC: f64 = (ONSET_FRAME * ONSET_DOWNSAMPLE) as f64 / ANALYSIS_RATE_HZ as f64;

/// Krumhansl-Schmuckler major profile (C-rooted, chroma order).
const KS_MAJOR: [f64; 12] = [
    6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88,
];

/// Krumhansl-Schmuckler minor profile (C-rooted, chroma order).
const KS_MINOR: [f64; 12] = [
    6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17,
];

/// Chroma bin lower bound (Hz) — below this, bins are percussive thump,
/// not pitch.
const CHROMA_MIN_HZ: f64 = 55.0;

/// Chroma bin upper bound (Hz) — above this, harmonics get sparse.
const CHROMA_MAX_HZ: f64 = 2200.0;

/// Frequency of analysis bin 0's center (DC) through bin k:
/// `k * rate / fft_len`.
#[inline]
fn bin_hz(bin: usize, fft_len: usize) -> f64 {
    bin as f64 * ANALYSIS_RATE_HZ as f64 / fft_len as f64
}

/// Camelot wheel number for a major key of pitch class `pc`
/// (0 = C). C major = 8B, G = 9B, D = 10B … B = 1B.
fn camelot_major(pc: usize) -> u8 {
    let n = (pc * 7 + 8) % 12;
    if n == 0 {
        12
    } else {
        n as u8
    }
}

/// Camelot wheel number for a minor key of pitch class `pc`
/// (0 = C). Relative major sits 3 semitones up and shares the number:
/// A minor = 8A (C major = 8B).
fn camelot_minor(pc: usize) -> u8 {
    camelot_major((pc + 3) % 12)
}

/// Produce the 128-dim acoustic embedding for one reference clip.
///
/// `interleaved_stereo` is canonical 48 kHz interleaved stereo `f32`
/// (L, R, L, R, …). Longer clips analyze their first 30 s. Deterministic
/// for a fixed input; see the module docs for the ε story.
pub fn produce_acoustic_embedding(
    interleaved_stereo: &[f32],
    sample_rate_hz: u32,
) -> Result<AcousticEmbeddingVector, EngineError> {
    if sample_rate_hz != ANALYSIS_RATE_HZ {
        return Err(EngineError::Audio {
            kind: AudioErrorKind::FormatUnsupported,
            device_id: None,
        });
    }
    if !interleaved_stereo.len().is_multiple_of(2) {
        return Err(EngineError::Audio {
            kind: AudioErrorKind::FormatUnsupported,
            device_id: None,
        });
    }

    let max_frames = REFERENCE_CLIP_SECONDS as usize * ANALYSIS_RATE_HZ as usize;
    let frames = (interleaved_stereo.len() / 2).min(max_frames);

    // ── Stage 1: BS.1770-4 integrated loudness (lufs.rs) ──────────────
    let mut analyzer = LufsAnalyzer::new()?;
    analyzer.process(&interleaved_stereo[..frames * 2]);
    let integrated_lufs = analyzer.integrated_lufs();

    // ── Stage 2+3a: mono downmix + time stats + envelope PCM (ONE pass) ─
    // A single sweep over the interleaved frames computes the mono mix,
    // the peak/RMS/crest/ZCR statistics, the amplitude histogram (the
    // dynamic-range quantiles) AND the 16-bit LE PCM the waveform
    // indexer consumes — separate passes over 1.44 M samples cost
    // ~15 ms the D2 budget cannot afford.
    let mut mono = Vec::with_capacity(frames);
    let mut pcm16 = Vec::with_capacity(frames * 2);
    let mut hist = [0u32; HISTOGRAM_BINS];
    let mut peak = 0.0f64;
    let mut sum_sq = 0.0f64;
    let mut crossings = 0u64;
    let mut prev_sign_pos = true;
    for frame in interleaved_stereo[..frames * 2].as_chunks::<2>().0 {
        let m = (f64::from(frame[0]) + f64::from(frame[1])) * 0.5;
        peak = peak.max(m.abs());
        sum_sq += m * m;
        let sign_pos = m >= 0.0;
        if sign_pos != prev_sign_pos {
            crossings += 1;
        }
        prev_sign_pos = sign_pos;
        let mono_f = m as f32;
        hist[(m.abs().clamp(0.0, 1.0) * (HISTOGRAM_BINS - 1) as f64).round() as usize] += 1;
        mono.push(mono_f);
        // Quantize once, push both LE bytes — extend_from_slice per
        // sample costs a call + length check 1.44 M times over a 30 s
        // clip; two pushes are measurably cheaper.
        let q = ((mono_f as f64).clamp(-1.0, 1.0) * 32767.0) as i16;
        let le = q.to_le_bytes();
        pcm16.push(le[0]);
        pcm16.push(le[1]);
    }
    let rms = if frames > 0 {
        (sum_sq / frames as f64).sqrt()
    } else {
        0.0
    };
    let crest_db = if rms > 1e-9 {
        20.0 * (peak / rms).log10()
    } else {
        0.0
    };
    let zcr = if frames > 1 {
        crossings as f64 / (frames - 1) as f64
    } else {
        0.0
    };

    // Dynamic range: P95 − P10 of the amplitude distribution, from the
    // fused 4096-bin histogram (±0.02% resolution — a full sort of a
    // 30 s clip costs ~110 ms, a third of the whole D2 budget, for
    // zero perceptual gain).
    let p95 = hist_quantile(&hist, frames, 0.95);
    let p10 = hist_quantile(&hist, frames, 0.10);
    let dynamic_range = (p95 - p10).clamp(0.0, 1.0);

    // ── Stage 3b: waveform envelope (waveform_indexer.rs, verbatim) ───
    // The indexer clamps bucket counts to [100, 500]; the contract
    // carries 64 lanes, so we generate 128 buckets and peak-preserve
    // down to 64 (max of adjacent pairs — the indexer's own
    // peak-preserving philosophy, coarser grid).
    let envelope: Vec<u8> = if frames > 0 {
        let buckets = generate_waveform(&pcm16, WAVEFORM_BUCKET_COUNT * 2).map_err(|_| {
            EngineError::Audio {
                kind: AudioErrorKind::FormatUnsupported,
                device_id: None,
            }
        })?;
        buckets
            .chunks(2)
            .map(|pair| pair.iter().copied().max().unwrap_or(0))
            .collect()
    } else {
        vec![0u8; WAVEFORM_BUCKET_COUNT]
    };

    // ── Stage 4: fused spectral pass (dsp.rs FFT + EQ band centers) ───
    // ONE FFT per window feeds chroma, the 10-band energies, centroid
    // and flatness — with the Hann window, the bin→band map and the
    // bin→pitch-class spread precomputed once (the naive per-bin
    // log-distance band search alone cost ~9 ms over the clip).
    let mut chroma = [0.0f64; 12];
    let mut band_energy = [0.0f64; 10];
    let mut centroid_num = 0.0f64;
    let mut centroid_den = 0.0f64;
    let mut log_sum = 0.0f64;
    let mut lin_sum = 0.0f64;
    let mut bin_count = 0u64;
    let window_count = if frames == 0 {
        0
    } else {
        // Evenly spread, deterministic window positions; at least one.
        ((frames / SPECTRAL_WINDOW) + 1).clamp(1, MAX_SPECTRAL_WINDOWS)
    };
    if window_count > 0 {
        let bin_total = SPECTRAL_WINDOW / 2;
        let hann = SpectrumVisualizer::hann_window(SPECTRAL_WINDOW);
        // bin → EQ band (StudioEqualizer octave centers; band 0 spans
        // DC, band 9 from the top center's upper octave to Nyquist).
        let centers = crate::dsp::StudioEqualizer::FREQUENCIES;
        let band_lut: Vec<u8> = (0..bin_total)
            .map(|bin| {
                let hz = bin_hz(bin, SPECTRAL_WINDOW);
                if hz < centers[0] * std::f64::consts::FRAC_1_SQRT_2 {
                    0
                } else if hz >= centers[9] * std::f64::consts::SQRT_2 {
                    9
                } else {
                    let mut best = 1u8;
                    let mut best_dist = f64::MAX;
                    for (i, c) in centers.iter().enumerate().take(10) {
                        let d = (hz / c).abs().ln();
                        if d < best_dist {
                            best_dist = d;
                            best = i as u8;
                        }
                    }
                    best
                }
            })
            .collect();
        // Chroma bin range and per-bin fractional pitch-class spread.
        let chroma_lo = (CHROMA_MIN_HZ / bin_hz(1, SPECTRAL_WINDOW)).ceil() as usize;
        let chroma_hi =
            ((CHROMA_MAX_HZ / bin_hz(1, SPECTRAL_WINDOW)).floor() as usize).min(bin_total - 1);
        let pc_spread: Vec<(usize, usize, f64)> = (chroma_lo..=chroma_hi)
            .map(|bin| {
                let midi = 69.0 + 12.0 * (bin_hz(bin, SPECTRAL_WINDOW) / 440.0).log2();
                let pc_f = midi.rem_euclid(12.0);
                let lo = pc_f.floor() as usize % 12;
                let hi = (lo + 1) % 12;
                (lo, hi, pc_f - pc_f.floor())
            })
            .collect();

        for w in 0..window_count {
            let start = if window_count <= 1 {
                0
            } else {
                (w * (frames.saturating_sub(SPECTRAL_WINDOW)).max(1)) / (window_count - 1)
            };
            let end = (start + SPECTRAL_WINDOW).min(frames);
            if end < SPECTRAL_WINDOW / 2 {
                break;
            }
            let power = SpectrumVisualizer::power_spectrum(&mono[start..end], &hann);
            let mut total = 0.0f64;
            let mut band_acc = [0.0f64; 10];
            for (bin, p) in power.iter().enumerate() {
                let p = *p;
                total += p;
                band_acc[band_lut[bin] as usize] += p;
                let hz = bin_hz(bin, SPECTRAL_WINDOW);
                centroid_num += hz * p;
                centroid_den += p;
                log_sum += (p + 1e-12).ln();
                lin_sum += p + 1e-12;
                bin_count += 1;
            }
            for (i, (lo, hi, frac)) in pc_spread.iter().enumerate() {
                let p = power[chroma_lo + i];
                chroma[*hi] += p * frac;
                chroma[*lo] += p * (1.0 - frac);
            }
            let norm = total.max(1e-12);
            for (acc, b) in band_acc.iter().zip(band_energy.iter_mut()) {
                *b += acc / norm;
            }
        }
    }
    let windows_f = window_count.max(1) as f64;
    let centroid_khz = if centroid_den > 1e-12 {
        (centroid_num / centroid_den) / 1000.0
    } else {
        0.0
    };
    let flatness = if bin_count > 0 && lin_sum > 0.0 {
        let geo = (log_sum / bin_count as f64).exp();
        let ari = lin_sum / bin_count as f64;
        (geo / ari).clamp(0.0, 1.0)
    } else {
        0.0
    };
    // Band shares (0..1 per band) → dB-normalized lane values.
    let band_lanes: [f32; 10] = core::array::from_fn(|i| {
        let share = (band_energy[i] / windows_f).clamp(1e-7, 1.0);
        let db = 10.0 * share.log10();
        ((db + 30.0) / 30.0).clamp(0.0, 1.0) as f32
    });

    // ── Stage 5: key detection (Krumhansl-Schmuckler → Camelot) ──────
    let (camelot_number, camelot_letter, major_margin) = detect_key(&chroma);

    // ── Stage 6: onset-energy-flux tempo tracking ─────────────────────
    let (bpm, bpm_confidence, beat_strength, onset_rate) = detect_tempo(&mono, frames);

    // ── Stage 7: composites + assembly ────────────────────────────────
    let rms_mean = rms.clamp(0.0, 1.0) as f32;
    let brightness = (centroid_khz / 5.0).clamp(0.0, 1.0) as f32;
    let upper_energy = band_lanes[6..10].iter().sum::<f32>() / 4.0;
    let energy = (0.6 * rms_mean + 0.4 * upper_energy).clamp(0.0, 1.0);
    let danceability = (0.5 * beat_strength
        + 0.3 * (onset_rate / 3.0).clamp(0.0, 1.0)
        + 0.2 * (1.0 - ((bpm - 120.0).abs() / 80.0).clamp(0.0, 1.0)))
    .clamp(0.0, 1.0);
    // Valence: major-ness margin (-1..1) re-based to 0..1, blended
    // with spectral brightness.
    let valence = (0.6 * (major_margin * 0.5 + 0.5) + 0.4 * brightness).clamp(0.0, 1.0);
    let acousticness = ((1.0 - flatness as f32) * 0.7 + (1.0 - zcr as f32) * 0.3).clamp(0.0, 1.0);

    let mut out = AcousticEmbeddingVector::neutral();
    out.set_lane(layout::BPM, if bpm > 0.0 { bpm } else { 0.0 });
    out.set_lane(layout::CAMELOT_NUMBER, camelot_number as f32);
    out.set_lane(
        layout::CAMELOT_LETTER,
        camelot_letter.unwrap_major_minor() as u8 as f32,
    );
    out.set_lane(
        layout::INTEGRATED_LUFS,
        if integrated_lufs.is_finite() {
            integrated_lufs as f32
        } else {
            -70.0
        },
    );
    out.set_lane(layout::CREST_FACTOR_DB, crest_db.clamp(0.0, 24.0) as f32);
    for (i, lane) in band_lanes.iter().enumerate() {
        out.set_lane(layout::SPECTRAL_BAND_FIRST + i, *lane);
    }
    out.set_lane(
        layout::SPECTRAL_CENTROID_KHZ,
        centroid_khz.clamp(0.0, 12.0) as f32,
    );
    out.set_lane(layout::SPECTRAL_FLATNESS, flatness as f32);
    out.set_lane(layout::ZERO_CROSSING_RATE, zcr.clamp(0.0, 1.0) as f32);
    out.set_lane(layout::RMS_MEAN, rms_mean);
    out.set_lane(layout::DYNAMIC_RANGE, dynamic_range);
    out.set_lane(layout::ONSET_RATE, onset_rate.clamp(0.0, 12.0));
    out.set_lane(layout::BEAT_STRENGTH, beat_strength);
    out.set_lane(layout::BPM_CONFIDENCE, bpm_confidence);
    out.set_lane(layout::DANCEABILITY, danceability);
    out.set_lane(layout::ENERGY, energy);
    out.set_lane(layout::VALENCE, valence);
    out.set_lane(layout::ACOUSTICNESS, acousticness);
    for (i, amp) in envelope.iter().enumerate().take(WAVEFORM_BUCKET_COUNT) {
        out.set_lane(layout::WAVEFORM_FIRST + i, f32::from(*amp) / 255.0);
    }
    out.set_lane(layout::LAYOUT_VERSION, ACOUSTIC_EMBEDDING_VERSION as f32);
    out.validate().map_err(|_| EngineError::Audio {
        kind: AudioErrorKind::FormatUnsupported,
        device_id: None,
    })?;
    Ok(out)
}

/// Amplitude histogram bin count (the `[0, 1]` domain split into
/// ±0.024%-resolution bins).
const HISTOGRAM_BINS: usize = 4096;

/// Quantile from a precomputed `[0, 1]` amplitude histogram of `count`
/// samples (ties resolve toward the bin's left edge, consistently).
fn hist_quantile(hist: &[u32; HISTOGRAM_BINS], count: usize, q: f64) -> f32 {
    if count == 0 {
        return 0.0;
    }
    let target = (q.clamp(0.0, 1.0) * count as f64).ceil().max(1.0) as u64;
    let mut acc = 0u64;
    for (bin, bucket) in hist.iter().enumerate() {
        acc += u64::from(*bucket);
        if acc >= target {
            return (bin as f32 + 0.5) / HISTOGRAM_BINS as f32;
        }
    }
    1.0
}

/// Krumhansl-Schmuckler key detection over the accumulated chroma.
///
/// Returns `(camelot_number, major?, margin)` where `margin` is the
/// normalized correlation of the winner (the valence proxy's major-ness
/// signal is `winner_correlation - best_opposite_mode_correlation`).
fn detect_key(chroma: &[f64; 12]) -> (u8, KeyMode, f32) {
    let total: f64 = chroma.iter().sum();
    let normalized: [f64; 12] = if total > 1e-12 {
        core::array::from_fn(|i| chroma[i] / total)
    } else {
        [1.0 / 12.0; 12]
    };
    let mut best_corr = f64::NEG_INFINITY;
    let mut best_pc = 0usize;
    let mut best_mode = KeyMode::Major;
    let mut best_major = f64::NEG_INFINITY;
    let mut best_minor = f64::NEG_INFINITY;
    for rotation in 0..12 {
        let rotated: [f64; 12] = core::array::from_fn(|i| normalized[(i + rotation) % 12]);
        let corr_major = pearson(&rotated, &KS_MAJOR);
        let corr_minor = pearson(&rotated, &KS_MINOR);
        if corr_major > best_major {
            best_major = corr_major;
        }
        if corr_minor > best_minor {
            best_minor = corr_minor;
        }
        if corr_major > best_corr {
            best_corr = corr_major;
            best_pc = rotation;
            best_mode = KeyMode::Major;
        }
        if corr_minor > best_corr {
            best_corr = corr_minor;
            best_pc = rotation;
            best_mode = KeyMode::Minor;
        }
    }
    let margin = ((best_major - best_minor) as f32 / 2.0).clamp(-1.0, 1.0);
    let number = match best_mode {
        KeyMode::Major => camelot_major(best_pc),
        KeyMode::Minor => camelot_minor(best_pc),
    };
    (number, best_mode, margin)
}

/// Key mode domain (major / minor side of the Camelot wheel).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum KeyMode {
    /// The `A` side (major keys).
    Major,
    /// The `B` side (minor keys).
    Minor,
}

impl KeyMode {
    fn unwrap_major_minor(self) -> streamify_core::acoustic_embedding::CamelotLetter {
        match self {
            KeyMode::Major => streamify_core::acoustic_embedding::CamelotLetter::Major,
            KeyMode::Minor => streamify_core::acoustic_embedding::CamelotLetter::Minor,
        }
    }
}

/// Pearson correlation of two 12-vectors (deterministic f64).
fn pearson(a: &[f64; 12], b: &[f64; 12]) -> f64 {
    let ma = a.iter().sum::<f64>() / 12.0;
    let mb = b.iter().sum::<f64>() / 12.0;
    let mut num = 0.0;
    let mut da = 0.0;
    let mut db = 0.0;
    for i in 0..12 {
        let x = a[i] - ma;
        let y = b[i] - mb;
        num += x * y;
        da += x * x;
        db += y * y;
    }
    let denom = (da * db).sqrt();
    if denom > 1e-12 {
        num / denom
    } else {
        0.0
    }
}

/// Onset-energy-flux tempo tracking over an 8×-downsampled envelope.
///
/// Returns `(bpm, confidence, beat_strength, onset_rate)`; bpm is 0.0
/// when the clip carries no detectable pulse (confidence < 0.1).
fn detect_tempo(mono: &[f32], frames: usize) -> (f32, f32, f32, f32) {
    // 8× box-average downsample (crude but adequate anti-alias for an
    // energy envelope) at 6 kHz.
    let ds_len = frames / ONSET_DOWNSAMPLE;
    if ds_len < ONSET_FRAME * 4 {
        return (0.0, 0.0, 0.0, 0.0);
    }
    let mut downsampled = vec![0.0f64; ds_len];
    for (i, slot) in downsampled.iter_mut().enumerate() {
        let mut acc = 0.0;
        for j in 0..ONSET_DOWNSAMPLE {
            acc += f64::from(mono[i * ONSET_DOWNSAMPLE + j]);
        }
        *slot = acc / ONSET_DOWNSAMPLE as f64;
    }

    // Frame energies (non-overlapping 128-sample frames ≈ 21.3 ms).
    let frame_count = ds_len / ONSET_FRAME;
    let mut energies = Vec::with_capacity(frame_count);
    for f in 0..frame_count {
        let mut acc = 0.0;
        for s in &downsampled[f * ONSET_FRAME..(f + 1) * ONSET_FRAME] {
            acc += s * s;
        }
        energies.push(acc);
    }

    // Onset strength = positive energy flux.
    let mut flux: Vec<f64> = Vec::with_capacity(energies.len());
    flux.push(0.0);
    for i in 1..energies.len() {
        flux.push((energies[i] - energies[i - 1]).max(0.0));
    }

    // Onset rate: flux peaks above 1.5× the running local mean.
    let duration_sec = frames as f64 / ANALYSIS_RATE_HZ as f64;
    let mut onsets = 0u64;
    let window = 8usize;
    for i in 1..flux.len() {
        let lo = i.saturating_sub(window);
        let mean = flux[lo..i].iter().sum::<f64>() / (i - lo) as f64;
        if flux[i] > 1.5 * mean.max(1e-12) && flux[i] > 1e-9 {
            onsets += 1;
        }
    }
    let onset_rate = if duration_sec > 0.0 {
        (onsets as f64 / duration_sec) as f32
    } else {
        0.0
    };

    // Autocorrelation of the flux envelope over BPM lags 60..200
    // (period 0.3 s .. 1.0 s expressed in ~21.3 ms frames).
    let min_lag = (((60.0 / 200.0) / ONSET_HOP_SEC).round() as usize).max(2);
    let max_lag = ((1.0 / ONSET_HOP_SEC).round() as usize).min(flux.len() / 2);
    let mean_flux = flux.iter().sum::<f64>() / flux.len() as f64;
    let centered: Vec<f64> = flux.iter().map(|v| v - mean_flux).collect();
    if max_lag <= min_lag || centered.len() <= max_lag + 2 {
        // Clip too short to resolve a 60..200 BPM period.
        return (0.0, 0.0, 0.0, onset_rate);
    }
    let mut best_lag = 0usize;
    let mut best_val = 0.0f64;
    let mut corr_sum = 0.0f64;
    let mut corr_count = 0u64;
    let mut corr = vec![0.0f64; max_lag + 1];
    for lag in min_lag..=max_lag {
        let mut acc = 0.0;
        for t in 0..(centered.len() - lag) {
            acc += centered[t] * centered[t + lag];
        }
        let val = acc / (centered.len() - lag) as f64;
        corr[lag] = val;
        corr_sum += val;
        corr_count += 1;
        if val > best_val {
            best_val = val;
            best_lag = lag;
        }
    }
    if best_lag == 0 || best_val <= 0.0 {
        return (0.0, 0.0, 0.0, onset_rate);
    }

    // Parabolic interpolation for sub-hop period precision.
    let (y_prev, y_cur, y_next) = (
        corr[best_lag - 1],
        corr[best_lag],
        corr[best_lag + 1.min(corr.len() - 1)],
    );
    let denom = y_prev - 2.0 * y_cur + y_next;
    let delta = if denom.abs() > 1e-12 {
        0.5 * (y_prev - y_next) / denom
    } else {
        0.0
    };
    let period_sec = (best_lag as f64 + delta.clamp(-0.5, 0.5)) * ONSET_HOP_SEC;
    if period_sec <= 0.0 {
        return (0.0, 0.0, 0.0, onset_rate);
    }
    let mut bpm = 60.0 / period_sec;

    // Octave correction into the 70..180 comfort band.
    while bpm < 70.0 {
        bpm *= 2.0;
    }
    while bpm > 180.0 {
        bpm *= 0.5;
    }

    let mean_corr = corr_sum / corr_count.max(1) as f64;
    let confidence = if best_val > 0.0 && mean_corr > 0.0 {
        (best_val / (mean_corr * 6.0)).clamp(0.0, 1.0) as f32
    } else {
        0.0
    };
    let beat_strength = if best_val > 0.0 {
        // Normalized against the lag-0 autocorrelation (signal power).
        let power: f64 = centered.iter().map(|v| v * v).sum::<f64>() / centered.len() as f64;
        if power > 1e-12 {
            (best_val / power).clamp(0.0, 1.0) as f32
        } else {
            0.0
        }
    } else {
        0.0
    };
    if confidence < 0.1 {
        return (0.0, confidence, beat_strength, onset_rate);
    }
    (bpm as f32, confidence, beat_strength, onset_rate)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::f64::consts::TAU;

    /// Deterministic f64 sine pair generator (stereo interleaved).
    fn tone(frames: usize, freq: f64, amp: f64) -> Vec<f32> {
        let mut out = Vec::with_capacity(frames * 2);
        let mut phase = 0.0f64;
        for _ in 0..frames {
            let s = (TAU * phase).sin() * amp;
            out.push(s as f32);
            out.push(s as f32);
            phase += freq / ANALYSIS_RATE_HZ as f64;
        }
        out
    }

    /// A deterministic music-like fixture: A-minor chord pad (A3, C4,
    /// E4 + one octave harmonic), a 124 BPM kick pulse and 8 Hz hat
    /// noise bursts — the same signal the CI bench lane measures.
    pub(crate) fn synthetic_music_clip(seconds: u32) -> Vec<f32> {
        let frames = seconds as usize * ANALYSIS_RATE_HZ as usize;
        let mut out = Vec::with_capacity(frames * 2);
        let mut kick_phase = 0.0f64;
        let beat_period = 60.0 / 124.0;
        let mut hat_phase = 0.0f64;
        let mut lcg = 0x2545F4914F6CDD1Du64; // deterministic LCG for hats
        for i in 0..frames {
            let t = i as f64 / ANALYSIS_RATE_HZ as f64;
            let mut s = 0.0f64;
            // Chord pad: A3 220, C4 261.63, E4 329.63 (+ soft harmonic).
            s += (TAU * 220.0 * t).sin() * 0.22;
            s += (TAU * 261.63 * t).sin() * 0.20;
            s += (TAU * 329.63 * t).sin() * 0.18;
            s += (TAU * 440.0 * t).sin() * 0.06;
            // Kick: 55 Hz thump decaying at each beat.
            if kick_phase >= beat_period {
                kick_phase -= beat_period;
            }
            if kick_phase < 0.10 {
                let env = (-kick_phase / 0.05).exp();
                s += (TAU * 55.0 * kick_phase).sin() * env * 0.5;
            }
            kick_phase += 1.0 / ANALYSIS_RATE_HZ as f64;
            // Hats: noise bursts at 8 Hz (offbeat shimmer).
            if hat_phase >= 0.125 {
                hat_phase -= 0.125;
            }
            if hat_phase < 0.012 {
                lcg = lcg
                    .wrapping_mul(6364136223846793005)
                    .wrapping_add(1442695040888963407);
                let noise = ((lcg >> 33) as f64 / u32::MAX as f64) * 2.0 - 1.0;
                s += noise * 0.08 * (1.0 - hat_phase / 0.012);
            }
            hat_phase += 1.0 / ANALYSIS_RATE_HZ as f64;
            let clamped = s.clamp(-1.0, 1.0) as f32;
            out.push(clamped);
            out.push(clamped);
        }
        out
    }

    #[test]
    fn histogram_quantiles_match_sorted_quantiles_within_one_bin() {
        // Deterministic pseudo-random amplitudes in [0, 1].
        let mut lcg = 0x853C49E6748FEA9Bu64;
        let samples: Vec<f32> = (0..100_000)
            .map(|_| {
                lcg = lcg
                    .wrapping_mul(6364136223846793005)
                    .wrapping_add(1442695040888963407);
                ((lcg >> 40) as f32 / (1u64 << 24) as f32).min(1.0)
            })
            .collect();
        let mut hist = [0u32; HISTOGRAM_BINS];
        for s in &samples {
            hist[((s.clamp(0.0, 1.0) as f64) * (HISTOGRAM_BINS - 1) as f64).round() as usize] += 1;
        }
        let mut sorted = samples.clone();
        sorted.sort_by(|a, b| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal));
        for q in [0.10, 0.95] {
            let pos = q * (sorted.len() - 1) as f64;
            let exact = sorted[pos as usize];
            let hist_q = hist_quantile(&hist, samples.len(), q);
            assert!(
                (hist_q - exact).abs() <= 2.0 / HISTOGRAM_BINS as f32,
                "q={q}: histogram {hist_q} vs exact {exact}"
            );
        }
        assert_eq!(hist_quantile(&hist, 0, 0.5), 0.0);
    }

    #[test]
    fn producer_is_bit_deterministic() {
        let clip = synthetic_music_clip(6);
        let a = produce_acoustic_embedding(&clip, ANALYSIS_RATE_HZ).unwrap();
        let b = produce_acoustic_embedding(&clip, ANALYSIS_RATE_HZ).unwrap();
        assert_eq!(a, b, "same input must produce bit-identical embeddings");
        // And the wire image round-trips.
        let wire = a.to_le_bytes();
        assert_eq!(AcousticEmbeddingVector::from_le_bytes(&wire).unwrap(), a);
    }

    #[test]
    fn rejects_non_canonical_rate_and_odd_samples() {
        let clip = synthetic_music_clip(1);
        assert!(produce_acoustic_embedding(&clip, 44_100).is_err());
        assert!(produce_acoustic_embedding(&clip[..7], ANALYSIS_RATE_HZ).is_err());
        assert!(produce_acoustic_embedding(&[], ANALYSIS_RATE_HZ).is_ok());
    }

    #[test]
    fn full_scale_sine_measures_zero_lufs_and_a_key() {
        // BS.1770-4 anchor: FS 997 Hz stereo sine ≈ 0 LUFS.
        let clip = tone(ANALYSIS_RATE_HZ as usize, 997.0, 1.0);
        let e = produce_acoustic_embedding(&clip, ANALYSIS_RATE_HZ).unwrap();
        assert!(
            (e.integrated_lufs() - 0.0).abs() < 0.6,
            "997 Hz FS sine LUFS: {}",
            e.integrated_lufs()
        );
        // A pure sine has no pulse: low tempo confidence.
        assert!(e.lane(layout::BPM_CONFIDENCE) < 0.5 || e.bpm() == 0.0);
        // Chroma of a 997 Hz sine lands near B5 (997 ≈ B5): the KS
        // winner must be SOME key — we only pin finiteness + range.
        let (n, _) = e.camelot();
        assert!((1..=12).contains(&n));
    }

    #[test]
    fn synthetic_music_detects_tempo_key_and_loudness() {
        let clip = synthetic_music_clip(REFERENCE_CLIP_SECONDS);
        let e = produce_acoustic_embedding(&clip, ANALYSIS_RATE_HZ).unwrap();
        // The kick pulses at 124 BPM — the tracker must land close.
        assert!(
            (e.bpm() - 124.0).abs() <= 4.0,
            "tempo tracked {} (expected ~124)",
            e.bpm()
        );
        assert!(e.lane(layout::BPM_CONFIDENCE) > 0.2, "confidence");
        assert!(e.lane(layout::BEAT_STRENGTH) > 0.05, "beat strength");
        // The pad is an A-minor chord: key = A minor = Camelot 8A.
        let (n, letter) = e.camelot();
        assert_eq!((n, letter), (8, streamify_core::CamelotLetter::Minor));
        // Loudness of a ~-10 dBFS composite sits well inside the gate.
        assert!(
            (-30.0..=-3.0).contains(&e.integrated_lufs()),
            "LUFS {}",
            e.integrated_lufs()
        );
        // Every lane finite (validate() already asserts; belt & braces).
        assert!(e.dims.iter().all(|v| v.is_finite()));
        // The envelope must be non-degenerate: normalized to the global
        // max (255 → 1.0) with visible variance between pad-only and
        // kick-driven buckets.
        let waveform: Vec<f32> = (0..WAVEFORM_BUCKET_COUNT)
            .map(|i| e.lane(layout::WAVEFORM_FIRST + i))
            .collect();
        let max = waveform.iter().cloned().fold(0.0f32, f32::max);
        let min = waveform.iter().cloned().fold(1.0f32, f32::min);
        assert!(max > 0.9, "envelope max {max}");
        assert!(min < 0.8 * max, "envelope variance min {min} max {max}");
    }

    #[test]
    fn camelot_wheel_mapping_matches_the_canonical_wheel() {
        // Pitch classes: C=0, G=7, D=2, A=9, E=4, B=11, F#=6, Db=1,
        // Ab=8, Eb=3, Bb=10, F=5.
        let majors = [
            (0usize, 8u8), // C  = 8B
            (7, 9),        // G  = 9B
            (2, 10),       // D  = 10B
            (9, 11),       // A  = 11B
            (4, 12),       // E  = 12B
            (11, 1),       // B  = 1B
            (6, 2),        // F# = 2B
            (1, 3),        // Db = 3B
            (8, 4),        // Ab = 4B
            (3, 5),        // Eb = 5B
            (10, 6),       // Bb = 6B
            (5, 7),        // F  = 7B
        ];
        for (pc, expect) in majors {
            assert_eq!(camelot_major(pc), expect, "major pc {pc}");
        }
        // Relative pairs share the number on the Camelot wheel:
        // Am = 8A (C = 8B), Em = 9A (G = 9B), Bm = 10A (D = 10B),
        // F#m = 11A (A = 11B), Cm = 5A (Eb = 5B), Gm = 6A (Bb = 6B),
        // Dm = 7A (F = 7B).
        let minors = [
            (9usize, 8u8), // Am
            (4, 9),        // Em
            (11, 10),      // Bm
            (6, 11),       // F#m
            (0, 5),        // Cm
            (7, 6),        // Gm
            (2, 7),        // Dm
        ];
        for (pc, expect) in minors {
            assert_eq!(camelot_minor(pc), expect, "minor pc {pc}");
        }
    }
}
