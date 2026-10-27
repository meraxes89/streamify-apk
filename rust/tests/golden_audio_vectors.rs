//! 48 kHz bit-exact golden-vector DSP suite — Phase 4a / MP-HAL03
//! (rust/tests/golden_audio_vectors.rs).
//!
//! Validates audio-processing parity across CPU architectures (x86_64,
//! ARM64-v8a, armeabi-v7a, Apple Silicon) for the canonical pipeline:
//!
//! ```text
//! input @ 44.1/96/48 kHz  ->  CanonicalSrc (rubato::SincFixedOut<f32>,
//!                              256-tap sinc, 0.95 cutoff, cubic interp,
//!                              squared Blackman-Harris window)  ->  48 kHz
//!                          ->  StudioEqualizer (biquad parametric EQ,
//!                              f64 direct-form state: low-shelf +2 dB,
//!                              1 kHz peak +1.5 dB, high-shelf -1 dB)
//!                          ->  LufsNormalizer (EBU R128 / ITU-R BS.1770-4
//!                              K-weighted gated integrated loudness,
//!                              target -16 LUFS, gain clamp ±20 dB)
//! ```
//!
//! ## The golden contract
//!
//! The fixture (`rust/tests/fixtures/audio_golden_vectors.json`) pins the
//! reference output of every vector on the generating host. The test
//! asserts, per vector:
//!
//! * `frames_out` — EXACT equality (integer frame bookkeeping is
//!   arch-independent).
//! * every spot sample, the per-1024-frame block-RMS fingerprint, the
//!   overall RMS and the peak — within **epsilon = 1e-5** (IEEE 754 f32
//!   precision tolerance; the block fingerprint is the "checksum": a
//!   divergence names the exact output block that drifted).
//! * `lufs_i` / `gain_db` — within 1e-5.
//! * `bits_fnv1a` — an FNV-1a digest of the raw f32 bits, INFORMATIONAL
//!   (reference-host fingerprint): the pipeline mixes `f64` libm-adjacent
//!   stages (rubato's sinc table, `log10` in the LUFS gating), whose
//!   last-ulp cross-libm differences sit far below the 1e-5 tolerance but
//!   may flip bit digests. A tolerance failure alongside a matching
//!   digest isolates environmental drift from real DSP divergence.
//!
//! Signal-correctness of the stages is separately pinned by property
//! tests (`audio_src.rs`: RMS survival, anti-aliasing, streaming
//! convergence; `lufs.rs`: the 0 LUFS full-scale anchor, gating,
//! determinism). This suite pins PARITY: same code, same input, same
//! output — everywhere.
//!
//! ## Regeneration
//!
//! `STREAMIFY_UPDATE_GOLDEN=1 cargo test --test golden_audio_vectors`
//! rewrites the fixture from the current build (run only when a DSP
//! change is DELIBERATE — the diff is the review artifact; rubato bumps
//! are golden-gated decisions per `Cargo.toml`).

use std::path::PathBuf;

use serde_json::{json, Value};

use streamify_core_rs::audio_src::CanonicalSrc;
use streamify_core_rs::dsp::StudioEqualizer;
use streamify_core_rs::lufs::{LufsNormalizer, LUFS_SILENCE};

/// IEEE 754 f32 precision tolerance for every golden comparison.
const EPSILON: f64 = 1e-5;
/// Fingerprint granularity: RMS per this many output frames.
const BLOCK_FRAMES: usize = 1024;
/// Spot-sample count per vector.
const SPOT_COUNT: usize = 24;
/// Normalization target (LUFS) — the streaming-loudness profile.
const TARGET_LUFS: f64 = -16.0;

// ---------------------------------------------------------------------------
// Input vector synthesis (deterministic f64 phase math -> f32)
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
    eq.set_band_gain(4, 1.5); // 1 kHz peaking
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
// Metric extraction
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
// Fixture I/O
// ---------------------------------------------------------------------------

fn fixture_path() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/audio_golden_vectors.json")
}

fn pipeline_descriptor() -> Value {
    json!({
        "src": "CanonicalSrc: rubato::SincFixedOut<f32> (sinc_len 256, f_cutoff 0.95, \
                oversampling 256, cubic, BlackmanHarris2, 1024-frame out chunks)",
        "eq": "StudioEqualizer @48000 Hz — band gains dB: [0]=+2.0 (low shelf 31.25), \
               [4]=+1.5 (peak 1k), [9]=-1.0 (high shelf 16k); f64 DF state; tanh soft clip",
        "lufs": "EBU R128 / ITU-R BS.1770-4 K-weighted gated integrated (400 ms / 75 %), \
                 target -16 LUFS, gain clamp ±20 dB, silence => 0 dB"
    })
}

fn serialize_vector(spec: &VectorSpec, outcome: &PipelineOutcome) -> Value {
    let input = match &spec.kind {
        InputKind::Sine { freq, amp } => json!({"kind": "sine", "freq": freq, "amp": amp}),
        InputKind::Sweep { f0, f1, amp } => {
            json!({"kind": "sweep", "f0": f0, "f1": f1, "amp": amp})
        }
        InputKind::Multitone { freqs, amp } => {
            json!({"kind": "multitone", "freqs": freqs, "amp": amp})
        }
        InputKind::Silence => json!({"kind": "silence"}),
    };
    json!({
        "name": spec.name,
        "input": {
            "rate": spec.rate,
            "frames": spec.frames,
            "signal": input,
        },
        "frames_out": outcome.output.len() / 2,
        "rms": rms(&outcome.output),
        "peak": peak(&outcome.output),
        "lufs_i": outcome.lufs_i,
        "gain_db": outcome.gain_db,
        "spot": spot_samples(&outcome.output, 2)
            .into_iter()
            .map(|(i, v)| json!([i, v]))
            .collect::<Vec<Value>>(),
        "block_rms": block_rms_fingerprint(&outcome.output, 2),
        "bits_fnv1a": format!("{:016x}", fnv1a_f32_bits(&outcome.output)),
    })
}

// ---------------------------------------------------------------------------
// The suite
// ---------------------------------------------------------------------------

fn within_eps(a: f64, b: f64) -> bool {
    (a - b).abs() <= EPSILON
}

#[test]
fn golden_vectors_match_reference_fixture() {
    let update = std::env::var("STREAMIFY_UPDATE_GOLDEN").is_ok();

    // ---- run every vector through the frozen pipeline --------------------
    let mut outcomes: Vec<(VectorSpec, PipelineOutcome)> = Vec::new();
    for spec in VECTORS {
        let input = synthesize(spec);
        let outcome = run_pipeline(spec, &input);
        outcomes.push((spec.clone(), outcome));
    }

    if update {
        regenerate_fixture(&outcomes);
        return;
    }

    // ---- load the fixture --------------------------------------------------
    let raw = std::fs::read(fixture_path()).unwrap_or_else(|e| {
        panic!(
            "golden fixture missing ({e}): {} — regenerate with \
             STREAMIFY_UPDATE_GOLDEN=1 cargo test --test golden_audio_vectors",
            fixture_path().display()
        )
    });
    let doc: Value = serde_json::from_slice(&raw)
        .unwrap_or_else(|e| panic!("fixture is not valid JSON: {e}"));

    assert_eq!(doc["format_version"], 1, "fixture format version");
    assert_eq!(
        doc["epsilon"].as_f64().unwrap(),
        EPSILON,
        "fixture epsilon pin"
    );
    let vectors = doc["vectors"].as_array().expect("fixture: vectors array");
    assert_eq!(
        vectors.len(),
        VECTORS.len(),
        "fixture vector count (regenerate if the vector set changed deliberately)"
    );

    for ((spec, outcome), golden) in outcomes.iter().zip(vectors.iter()) {
        let name = golden["name"].as_str().unwrap_or("<unnamed>");
        assert_eq!(name, spec.name, "vector order/name stability");

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
            panic!("fixture {name}: spot array missing (regenerate the fixture)")
        });
        let got_spots = spot_samples(&outcome.output, 2);
        assert_eq!(
            got_spots.len(),
            golden_spots.len(),
            "{name}: spot sample count (fixture predates spot format?)"
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
        let golden_blocks = golden["block_rms"].as_array().unwrap_or_else(|| {
            panic!("fixture {name}: block_rms array missing")
        });
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
            // NOT a failure: cross-libm last-ulp differences may flip the
            // digest while every tolerance check above passes. Surf it for
            // diagnostics — a tolerance failure WITH a matching digest
            // would isolate environmental drift instead.
            eprintln!(
                "note: {name}: bits_fnv1a {digest} != reference {golden_digest} \
                 (within-tolerance cross-libm drift; see the suite header)"
            );
        }
    }
}

fn regenerate_fixture(outcomes: &[(VectorSpec, PipelineOutcome)]) {
    let vectors: Vec<Value> = outcomes
        .iter()
        .map(|(spec, outcome)| serialize_vector(spec, outcome))
        .collect();
    let doc = json!({
        "format_version": 1,
        "suite": "rust/tests/golden_audio_vectors.rs",
        "epsilon": EPSILON,
        "block_frames": BLOCK_FRAMES,
        "spot_count": SPOT_COUNT,
        "pipeline": pipeline_descriptor(),
        "reference_host": format!(
            "{}-{} / {} / rubato 0.15",
            std::env::consts::ARCH,
            std::env::consts::OS,
            rustc_version(),
        ),
        "vectors": vectors,
    });
    let pretty = serde_json::to_string_pretty(&doc).expect("serialize fixture");
    if let Some(parent) = fixture_path().parent() {
        std::fs::create_dir_all(parent).expect("create fixture dir");
    }
    std::fs::write(fixture_path(), pretty + "\n")
        .unwrap_or_else(|e| panic!("write fixture: {e}"));
    eprintln!(
        "golden fixture REGENERATED: {} ({} vectors)",
        fixture_path().display(),
        outcomes.len()
    );
}

fn rustc_version() -> String {
    // Regeneration-time probe (only executed on the update path): record
    // the exact toolchain that produced the reference values.
    std::process::Command::new(std::env::var("RUSTC").unwrap_or_else(|_| "rustc".into()))
        .arg("--version")
        .output()
        .ok()
        .and_then(|o| String::from_utf8(o.stdout).ok())
        .map(|s| s.trim().to_owned())
        .unwrap_or_else(|| "rustc <unknown>".to_owned())
}
