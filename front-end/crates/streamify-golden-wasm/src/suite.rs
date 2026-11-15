//! W1.7 — the golden-vector parity suite, mirrored 1:1 from
//! `rust/crates/streamify-audio/tests/golden_audio_vectors.rs` onto
//! wasm32-unknown-unknown (headless Chrome via wasm-bindgen-test).
//!
//! ## What is mirrored, what is not
//!
//! Mirrored exactly (the DSP contract):
//! * the 5 canonical input vectors (deterministic f64 phase math → f32);
//! * the pipeline `CanonicalSrc (rubato SincFixedOut, 256-tap, 0.95
//!   cutoff, cubic, squared Blackman-Harris) → StudioEqualizer
//!   (band 0 +2 dB, band 4 +1.5 dB, band 9 −1 dB) → LufsNormalizer
//!   (BS.1770-4, target −16 LUFS, ±20 dB clamp)`;
//! * the assertion set: `frames_out` EXACT; `rms` / `peak` / `lufs_i` /
//!   `gain_db`, 24 spot samples and the per-1024-frame block-RMS
//!   fingerprint within **ε = 1e-5**; `bits_fnv1a` informational.
//!
//! Deliberately NOT mirrored (native-only concerns):
//! * fixture REGENERATION — wasm has no filesystem, and the reference
//!   values are regenerated on native hosts only
//!   (`STREAMIFY_UPDATE_GOLDEN=1 cargo test --test golden_audio_vectors`
//!   in the frozen workspace). This suite embeds the frozen fixture at
//!   compile time via `include_bytes!` from that same path — it can
//!   only ever verify, never rewrite.
//! * the reference-host probe (`rustc_version`) — meaningless on wasm.
//!
//! ## Why the ε tolerance carries the parity claim
//!
//! wasm32-unknown-unknown links Rust's compiler-builtins libm, not the
//! host glibc — `sin`/`powf`/`log10` last-ulp differences accumulate
//! through the SRC kernel and the LUFS gating far below the 1e-5
//! tolerance (and may flip the informational FNV-1a bit digest, which is
//! exactly why the native suite keeps it non-failing). A failure HERE
//! with the native suite green means the browser build's DSP genuinely
//! diverged — that is the W1.7 gate.
//!
//! One `#[wasm_bindgen_test]` per vector (not one looping test): CI
//! output names the failing vector, and each stays well inside the
//! runner's per-test budget.

use wasm_bindgen_test::wasm_bindgen_test;

use serde_json::Value;

use streamify_audio::audio_src::CanonicalSrc;
use streamify_audio::dsp::StudioEqualizer;
use streamify_audio::lufs::{LufsNormalizer, LUFS_SILENCE};

/// IEEE 754 f32 precision tolerance for every golden comparison — pinned
/// by the fixture header and asserted at load.
const EPSILON: f64 = 1e-5;
/// Fingerprint granularity: RMS per this many output frames.
const BLOCK_FRAMES: usize = 1024;
/// Spot-sample count per vector.
const SPOT_COUNT: usize = 24;
/// Normalization target (LUFS) — the streaming-loudness profile.
const TARGET_LUFS: f64 = -16.0;

/// The frozen native reference fixture — the SAME file the native suite
/// reads at runtime (`rust/crates/streamify-audio/tests/fixtures/
/// audio_golden_vectors.json`), embedded at compile time. Read-only
/// reference into the frozen tree by design (see Cargo.toml header).
const FIXTURE: &[u8] = include_bytes!(
    "../../../../rust/crates/streamify-audio/tests/fixtures/audio_golden_vectors.json"
);

// ---------------------------------------------------------------------------
// Input vector synthesis (deterministic f64 phase math -> f32)
// — mirrored verbatim from the native suite
// ---------------------------------------------------------------------------

#[derive(Debug, Clone)]
enum InputKind {
    Sine { freq: f64, amp: f64 },
    Sweep { f0: f64, f1: f64, amp: f64 },
    Multitone { freqs: &'static [f64], amp: f64 },
    Silence,
}

#[derive(Debug, Clone)]
struct VectorSpec {
    name: &'static str,
    rate: u32,
    frames: usize,
    kind: InputKind,
}

const VECTORS: &[VectorSpec] = &[
    VectorSpec {
        name: "sine_997hz_44k1",
        rate: 44_100,
        frames: 44_100,
        kind: InputKind::Sine { freq: 997.0, amp: 0.5 },
    },
    VectorSpec {
        name: "sweep_20_20k_44k1",
        rate: 44_100,
        frames: 44_100,
        kind: InputKind::Sweep { f0: 20.0, f1: 20_000.0, amp: 0.4 },
    },
    VectorSpec {
        name: "multitone_96k",
        rate: 96_000,
        frames: 96_000,
        kind: InputKind::Multitone {
            freqs: &[315.0, 1000.0, 3150.0],
            amp: 0.2,
        },
    },
    VectorSpec {
        name: "sine_440hz_48k_passthrough",
        rate: 48_000,
        frames: 48_000,
        kind: InputKind::Sine { freq: 440.0, amp: 0.6 },
    },
    VectorSpec {
        name: "silence_44k1",
        rate: 44_100,
        frames: 22_050,
        kind: InputKind::Silence,
    },
];

/// Synthesize the interleaved stereo f32 input for one vector.
fn synthesize(spec: &VectorSpec) -> Vec<f32> {
    let mut out = Vec::with_capacity(spec.frames * 2);
    match &spec.kind {
        InputKind::Sine { freq, amp } => {
            let mut phase = 0.0f64;
            for _ in 0..spec.frames {
                let s = (std::f64::consts::TAU * phase).sin() * amp;
                out.push(s as f32);
                out.push(s as f32);
                phase += freq / f64::from(spec.rate);
            }
        }
        InputKind::Sweep { f0, f1, amp } => {
            // Logarithmic sweep; the analytic phase integral keeps the
            // sweep deterministic without feedback accumulation.
            let ratio = f1 / f0;
            let t_total = spec.frames as f64 / f64::from(spec.rate);
            let c = f0 * t_total / ratio.ln();
            for n in 0..spec.frames {
                let t = n as f64 / f64::from(spec.rate);
                let phase = c * (ratio.powf(t / t_total) - 1.0);
                let s = (std::f64::consts::TAU * phase).sin() * amp;
                out.push(s as f32);
                out.push(s as f32);
            }
        }
        InputKind::Multitone { freqs, amp } => {
            let mut phases = vec![0.0f64; freqs.len()];
            for _ in 0..spec.frames {
                let mut s = 0.0f64;
                for (p, f) in phases.iter_mut().zip(freqs.iter()) {
                    s += (std::f64::consts::TAU * *p).sin() * amp;
                    *p += f / f64::from(spec.rate);
                }
                out.push(s as f32);
                out.push(s as f32);
            }
        }
        InputKind::Silence => {
            out.resize(spec.frames * 2, 0.0);
        }
    }
    out
}

// ---------------------------------------------------------------------------
// The golden pipeline (frozen descriptor — mirrors the fixture header)
// — mirrored verbatim from the native suite
// ---------------------------------------------------------------------------

struct PipelineOutcome {
    output: Vec<f32>,
    lufs_i: Option<f64>,
    gain_db: f64,
}

fn run_pipeline(spec: &VectorSpec, input: &[f32]) -> PipelineOutcome {
    // Stage 1: producer-side polyphase sinc SRC to canonical 48 kHz.
    let mut src = CanonicalSrc::new(spec.rate, 2).unwrap();
    let mut canonical = Vec::new();
    src.process(input, &mut canonical);
    src.flush(&mut canonical);

    // Stage 2: biquad parametric EQ (f64 direct-form state).
    let mut eq = StudioEqualizer::new(48_000.0);
    eq.set_band_gain(0, 2.0); // 31.25 Hz low shelf
    eq.set_band_gain(4, 1.5); // 500 Hz peaking (FREQUENCIES[4] = 500.0)
    eq.set_band_gain(9, -1.0); // 16 kHz high shelf
    eq.process_buffer_interleaved(&mut canonical, 2);

    // Stage 3: BS.1770-4 loudness normalization to the target.
    let mut normalizer = LufsNormalizer::new(TARGET_LUFS).unwrap();
    let report = normalizer.process_interleaved(&mut canonical);
    PipelineOutcome {
        output: canonical,
        lufs_i: if report.measured_lufs == LUFS_SILENCE {
            None
        } else {
            Some(report.measured_lufs)
        },
        gain_db: report.applied_gain_db,
    }
}

// ---------------------------------------------------------------------------
// Metric extraction — mirrored verbatim from the native suite
// ---------------------------------------------------------------------------

fn rms(samples: &[f32]) -> f64 {
    if samples.is_empty() {
        return 0.0;
    }
    let sum: f64 = samples.iter().map(|s| f64::from(*s) * f64::from(*s)).sum();
    (sum / samples.len() as f64).sqrt()
}

fn peak(samples: &[f32]) -> f64 {
    samples
        .iter()
        .map(|s| f64::from(*s).abs())
        .fold(0.0f64, f64::max)
}

fn block_rms_fingerprint(output: &[f32], channels: usize) -> Vec<f64> {
    output
        .chunks(BLOCK_FRAMES * channels)
        .map(rms)
        .collect()
}

fn spot_samples(output: &[f32], channels: usize) -> Vec<(usize, f64)> {
    let frames = output.len() / channels;
    let mut spots = Vec::with_capacity(SPOT_COUNT);
    for i in 0..SPOT_COUNT {
        // Deterministic spread (the +7 offset avoids the t=0 DC edge).
        let frame = (i * frames / SPOT_COUNT) + 7;
        if frame < frames {
            spots.push((frame * channels, f64::from(output[frame * channels])));
        }
    }
    spots
}

fn fnv1a_f32_bits(samples: &[f32]) -> u64 {
    let mut h: u64 = 0xcbf2_9ce4_8422_2325;
    for s in samples {
        for b in s.to_bits().to_le_bytes() {
            h ^= u64::from(b);
            h = h.wrapping_mul(0x0000_0100_0000_01b3);
        }
    }
    h
}

// ---------------------------------------------------------------------------
// The suite
// ---------------------------------------------------------------------------

fn within_eps(a: f64, b: f64) -> bool {
    (a - b).abs() <= EPSILON
}

/// Parse the frozen fixture and validate its header pins.
fn load_fixture() -> Vec<Value> {
    let doc: Value = serde_json::from_slice(FIXTURE)
        .unwrap_or_else(|e| panic!("embedded fixture is not valid JSON: {e}"));
    assert_eq!(doc["format_version"], 1, "fixture format version");
    assert_eq!(
        doc["epsilon"].as_f64().unwrap(),
        EPSILON,
        "fixture epsilon pin"
    );
    let vectors = doc["vectors"].as_array().expect("fixture: vectors array").clone();
    assert_eq!(
        vectors.len(),
        VECTORS.len(),
        "fixture vector count (the native suite owns regeneration)"
    );
    vectors
}

/// Run one vector through the mirrored pipeline and assert it against the
/// golden entry — the native suite's per-vector body, verbatim.
fn assert_vector(spec: &VectorSpec, golden: &Value) {
    let name = golden["name"].as_str().unwrap_or("<unnamed>");
    assert_eq!(name, spec.name, "vector order/name stability");

    let input = synthesize(spec);
    let outcome = run_pipeline(spec, &input);

    // 1. Output length: EXACT.
    let golden_frames = golden["frames_out"].as_u64().unwrap() as usize;
    let got_frames = outcome.output.len() / 2;
    assert_eq!(
        got_frames, golden_frames,
        "{name}: output frame count {got_frames} != golden {golden_frames}"
    );

    // 2. Scalar summaries within epsilon.
    let g_rms = golden["rms"].as_f64().unwrap();
    assert!(
        within_eps(rms(&outcome.output), g_rms),
        "{name}: rms {} vs golden {g_rms}",
        rms(&outcome.output)
    );
    let g_peak = golden["peak"].as_f64().unwrap();
    assert!(
        within_eps(peak(&outcome.output), g_peak),
        "{name}: peak {} vs golden {g_peak}",
        peak(&outcome.output)
    );
    let g_gain = golden["gain_db"].as_f64().unwrap();
    assert!(
        within_eps(outcome.gain_db, g_gain),
        "{name}: gain_db {} vs golden {g_gain}",
        outcome.gain_db
    );

    // 3. Integrated loudness within epsilon (None for silence).
    match (&outcome.lufs_i, golden["lufs_i"].as_f64()) {
        (Some(got), Some(want)) => assert!(
            within_eps(*got, want),
            "{name}: lufs_i {got} vs golden {want}"
        ),
        (None, None) => {}
        (got, want) => panic!("{name}: lufs_i presence mismatch: {got:?} vs {want:?}"),
    }

    // 4. Spot samples: dense per-sample parity probes within epsilon.
    let golden_spots = golden["spot"].as_array().unwrap_or_else(|| {
        panic!("fixture {name}: spot array missing")
    });
    let got_spots = spot_samples(&outcome.output, 2);
    assert_eq!(
        got_spots.len(),
        golden_spots.len(),
        "{name}: spot sample count"
    );
    for (i, ((idx, value), g)) in got_spots.iter().zip(golden_spots.iter()).enumerate() {
        let g_idx = g[0].as_u64().unwrap() as usize;
        let g_val = g[1].as_f64().unwrap();
        assert_eq!(*idx, g_idx, "{name}: spot {i} index");
        assert!(
            within_eps(*value, g_val),
            "{name}: spot {i} (frame {}) value {value} vs golden {g_val}",
            idx / 2
        );
    }

    // 5. Block-RMS fingerprint: the per-block golden checksum.
    let golden_blocks = golden["block_rms"]
        .as_array()
        .unwrap_or_else(|| panic!("fixture {name}: block_rms array missing"));
    let got_blocks = block_rms_fingerprint(&outcome.output, 2);
    assert_eq!(
        got_blocks.len(),
        golden_blocks.len(),
        "{name}: block fingerprint length"
    );
    for (i, b) in got_blocks.iter().enumerate() {
        let want = golden_blocks[i].as_f64().unwrap();
        assert!(
            within_eps(*b, want),
            "{name}: block {i} rms {b} vs golden {want} (first diverging block)"
        );
    }

    // 6. Informational bit digest (reference-host fingerprint).
    let digest = format!("{:016x}", fnv1a_f32_bits(&outcome.output));
    let golden_digest = golden["bits_fnv1a"].as_str().unwrap_or("<missing>");
    if digest != golden_digest {
        // NOT a failure (see the module header): wasm32's compiler-builtins
        // libm may flip last-ulp bits while every tolerance check above
        // passes. Surf it for diagnostics.
        wasm_bindgen_test::console_log!(
            "note: {name}: bits_fnv1a {digest} != native reference {golden_digest} \
             (within-tolerance cross-libm drift; see the suite header)"
        );
    }
}

// One test per vector: CI names the failing vector, and each stays well
// inside the wasm-bindgen-test per-test budget.

#[wasm_bindgen_test]
fn golden_wasm_sine_997hz_44k1() {
    assert_vector(&VECTORS[0], &load_fixture()[0]);
}

#[wasm_bindgen_test]
fn golden_wasm_sweep_20_20k_44k1() {
    assert_vector(&VECTORS[1], &load_fixture()[1]);
}

#[wasm_bindgen_test]
fn golden_wasm_multitone_96k() {
    assert_vector(&VECTORS[2], &load_fixture()[2]);
}

#[wasm_bindgen_test]
fn golden_wasm_sine_440hz_48k_passthrough() {
    assert_vector(&VECTORS[3], &load_fixture()[3]);
}

#[wasm_bindgen_test]
fn golden_wasm_silence_44k1() {
    assert_vector(&VECTORS[4], &load_fixture()[4]);
}

/// The fixture header itself: embedded bytes must be the frozen native
/// suite's own fixture (same vector set, same epsilon pin) — a guard
/// against accidentally pointing include_bytes! at a stale copy.
#[wasm_bindgen_test]
fn golden_wasm_fixture_pins() {
    let fixture = load_fixture();
    for (spec, golden) in VECTORS.iter().zip(fixture.iter()) {
        assert_eq!(golden["name"].as_str().unwrap(), spec.name);
        assert_eq!(golden["input"]["rate"].as_u64().unwrap(), u64::from(spec.rate));
        assert_eq!(
            golden["input"]["frames"].as_u64().unwrap(),
            spec.frames as u64
        );
    }
}
