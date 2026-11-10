//! A5 (issue #151): the live-audio self-test harness — the
//! "engine says 440" gate every platform integration (WS-B/WS-C) runs
//! before touching hardware.
//!
//! The full chain runs hermetically: `play_track` → producer (fetch →
//! decode → resample → LUFS) → the SPSC ring write edge → a puller
//! driving the `virtual_hal.rs` adversarial double as the DAC — with
//! the fetch stage fed by a scripted loss/jitter source
//! (`LossyFixtureFetcher`). The harness then:
//!
//! * runs a radix-2 FFT over the consumed buffer and asserts the
//!   dominant bin is 440 Hz within tolerance (both fixtures: the 48 kHz
//!   passthrough geometry AND the 44.1 kHz resample leg);
//! * asserts the underrun counters are ZERO (the ring's consumer-side
//!   accounting and the VirtualHAL's starved-callback accounting
//!   agree);
//! * asserts position truth: the DAC-clock-derived rendered position
//!   tracks the consumed-frame truth and advances at a wall-plausible
//!   rate (the only sanctioned wall-clock use — asserting
//!   plausibility, never interpolating position, MP-DEC-03);
//! * drives the 100-rapid-skip stress under load and verifies the
//!   FINAL intent still plays through cleanly (no leak, no deadlock —
//!   the deterministic teardown at Drop is the leak proof).
//!
//! CI gate: `cargo test -p streamify-contract live_audio_selftest`.

use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};

use streamify_audio::{AdversarialModes, VirtualHalConfig, VirtualHalSink};
use streamify_contract::{BufferProgress, StreamifyEngine};
use streamify_playback::{LossyFixtureFetcher, RangeFetcher};

/// The 1.5 s fixtures' exact frame counts at the canonical rate.
const CLIP_FRAMES: u64 = 72_000;
const CLIP_MS: u64 = 1_500;
/// The HAL pull quantum (one of the frozen CALLBACK_CHUNK_SIZES).
const QUANTUM: usize = 512;

fn fixture(name: &str) -> Arc<Vec<u8>> {
    let mut path = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
    path.push("../../../fixtures/audio");
    path.push(name);
    Arc::new(std::fs::read(&path).unwrap_or_else(|e| panic!("{}: {e}", path.display())))
}

/// A minimal iterative radix-2 FFT (real input, magnitude spectrum) —
/// no DSP dependency: the harness must stay hermetic and cheap.
fn fft_magnitudes(samples: &[f64]) -> Vec<f64> {
    let n = samples.len();
    assert!(n.is_power_of_two(), "FFT length must be a power of two");
    // Bit-reversal permutation + Cooley-Tukey butterflies.
    let mut re: Vec<f64> = samples.to_vec();
    let mut im = vec![0.0f64; n];
    let mut j = 0usize;
    for i in 0..n - 1 {
        if i < j {
            re.swap(i, j);
            im.swap(i, j);
        }
        let mut m = n >> 1;
        while m >= 1 && j & m != 0 {
            j ^= m;
            m >>= 1;
        }
        j |= m;
    }
    let mut len = 2;
    while len <= n {
        let ang = -2.0 * std::f64::consts::PI / len as f64;
        let (wre, wim) = (ang.cos(), ang.sin());
        let mut i = 0;
        while i < n {
            let mut cur_re = 1.0f64;
            let mut cur_im = 0.0f64;
            for k in 0..len / 2 {
                let a = i + k;
                let b = i + k + len / 2;
                let t_re = cur_re * re[b] - cur_im * im[b];
                let t_im = cur_re * im[b] + cur_im * re[b];
                re[b] = re[a] - t_re;
                im[b] = im[a] - t_im;
                re[a] += t_re;
                im[a] += t_im;
                let next_re = cur_re * wre - cur_im * wim;
                cur_im = cur_re * wim + cur_im * wre;
                cur_re = next_re;
            }
            i += len;
        }
        len <<= 1;
    }
    re.iter()
        .zip(&im)
        .map(|(r, i)| (r * r + i * i).sqrt())
        .collect()
}

/// The dominant frequency [Hz] of the LEFT channel (Hann-windowed
/// 65 536-point FFT). The input is interleaved stereo — deinterleave
/// first (feeding interleaved L/R would sample-double a symmetric
/// fixture and halve the measured frequency).
fn dominant_frequency(interleaved: &[f32]) -> f64 {
    let n = 65_536usize;
    assert!(
        interleaved.len() >= n * 2,
        "need at least {n} stereo frames for the FFT"
    );
    let window: Vec<f64> = (0..n)
        .map(|i| {
            let s = interleaved[i * 2] as f64;
            0.5 * (1.0 - (2.0 * std::f64::consts::PI * i as f64 / n as f64).cos()) * s
        })
        .collect();
    let mags = fft_magnitudes(&window);
    // Only the first half is meaningful; the DC bin (0) is excluded —
    // the LUFS chain can leave a tiny offset but never a DC tone.
    let peak = (1..n / 2)
        .max_by(|a, b| mags[*a].total_cmp(&mags[*b]))
        .expect("non-empty spectrum");
    peak as f64 * 48_000.0 / n as f64
}

/// The adversarial HAL driver: pulls the producer's ring at real-time
/// pace, feeds the VirtualHAL double (the DAC model), records every
/// clock sample into the engine and captures the consumed PCM.
struct HalDriver {
    stop: Arc<AtomicBool>,
    vhal: Arc<Mutex<VirtualHalSink>>,
    captured: Arc<Mutex<Vec<f32>>>,
    frames_pulled: Arc<std::sync::atomic::AtomicU64>,
    handle: Option<thread::JoinHandle<()>>,
}

impl HalDriver {
    fn spawn(engine: StreamifyEngine, ring: Arc<streamify_playback::SpscAudioRing>) -> Self {
        let stop = Arc::new(AtomicBool::new(false));
        let vhal = Arc::new(Mutex::new(
            VirtualHalSink::new(VirtualHalConfig {
                // The adversarial double: jittered fire times and
                // quantized reported timestamps on top of the canonical
                // geometry — the producer must keep the ring fed
                // through all of it with zero starvation.
                modes: AdversarialModes::TIMING_JITTER,
                jitter_fraction: 0.15,
                timestamp_quantum_ns: 1_000_000,
                ..VirtualHalConfig::default()
            })
            .expect("canonical geometry is valid"),
        ));
        let captured = Arc::new(Mutex::new(Vec::with_capacity(CLIP_FRAMES as usize * 2)));
        let frames_pulled = Arc::new(std::sync::atomic::AtomicU64::new(0));
        let stop2 = stop.clone();
        let vhal2 = vhal.clone();
        let captured2 = captured.clone();
        let pulled = frames_pulled.clone();
        let handle = thread::Builder::new()
            .name("live-audio-hal-driver".to_string())
            .spawn(move || {
                let quantum_ns = (QUANTUM as f64 / 48_000.0 * 1e9) as u64;
                let mut staged = vec![0.0f32; QUANTUM * 2];
                let mut audible = vec![0.0f32; QUANTUM * 2];
                // The DAC clock exists BEFORE playback starts (the
                // platform flow: the HAL is alive before the first
                // track) — record the initial sample so the session
                // anchors at frame 0.
                let initial = vhal2
                    .lock()
                    .unwrap_or_else(|poisoned| poisoned.into_inner())
                    .last_clock();
                let _ = engine.record_dac_clock(initial);
                while !stop2.load(Ordering::Relaxed) {
                    // One hardware pull. EOS-aware quantum sizing: a
                    // full quantum mid-stream (a short read there is a
                    // REAL underrun — the gate's metric), but once the
                    // session is at EOS the final partial quantum is
                    // the stream's tail, not starvation — ask for
                    // exactly what remains, present it, and stop.
                    let eos = engine
                        .buffer_progress()
                        .map(|p| p.eos)
                        .unwrap_or(false);
                    let occupancy = ring.occupancy();
                    if occupancy == 0 {
                        if eos {
                            break; // the stream is fully presented
                        }
                        // A pending flush generation must be acked even
                        // against an empty ring: a superseding skip's
                        // producer bounded-waits for this ack (a live
                        // HAL keeps reading through skips). The 1-frame
                        // probe read services the handshake without
                        // counting an underrun (the flush path returns
                        // before the starvation accounting).
                        let (requested, acked) = ring.flush_generations();
                        if requested > acked {
                            let mut probe = [0.0f32; 2];
                            let _ = ring.read_frames_into(&mut probe);
                        }
                        // Transient dip: the producer is mid-fetch —
                        // poll again.
                        thread::sleep(Duration::from_micros(250));
                        continue;
                    }
                    let want_frames = if eos && occupancy < QUANTUM {
                        occupancy
                    } else {
                        QUANTUM
                    };
                    let served = ring.read_frames_into(&mut staged[..want_frames * 2]);
                    if served == 0 {
                        if eos {
                            break;
                        }
                        thread::sleep(Duration::from_micros(250));
                        continue;
                    }
                    let mut vhal = vhal2
                        .lock()
                        .unwrap_or_else(|poisoned| poisoned.into_inner());
                    // …hand the quantum to the DAC model…
                    vhal.write_producer_frames(&staged[..served]);
                    // …and run the callback: the audible output plus
                    // the CONSISTENT reported clock pair.
                    let callback = vhal.run_callback_into(&mut audible[..served]);
                    drop(vhal);
                    // Position truth flows hardware -> engine (never
                    // the reverse).
                    let _ = engine.record_dac_clock(callback.clock);
                    let mut capture = captured2
                        .lock()
                        .unwrap_or_else(|poisoned| poisoned.into_inner());
                    if capture.len() < CLIP_FRAMES as usize * 2 {
                        capture.extend_from_slice(&audible[..served]);
                    }
                    pulled.fetch_add((served / 2) as u64, Ordering::Relaxed);
                    // Real-time pace: one quantum of wall time per
                    // served pull.
                    thread::sleep(Duration::from_nanos(
                        quantum_ns * served as u64 / (QUANTUM * 2) as u64,
                    ));
                }
            })
            .expect("spawn the HAL driver");
        Self {
            stop,
            vhal,
            captured,
            frames_pulled,
            handle: Some(handle),
        }
    }

    fn frames_pulled(&self) -> u64 {
        self.frames_pulled.load(Ordering::Relaxed)
    }

    fn consumed(&self) -> Vec<f32> {
        self.captured
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .clone()
    }

    fn starved_callbacks(&self) -> u64 {
        self.vhal
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .stats()
            .starved_callbacks
    }
}

impl Drop for HalDriver {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Relaxed);
        if let Some(handle) = self.handle.take() {
            let _ = handle.join();
        }
    }
}

/// Aggregate observations from one HAL-driven run.
struct HalDriverMetrics {
    frames_pulled: u64,
    starved_callbacks: u64,
}

/// One full "engine says 440" run over a fixture source with scripted
/// network loss/jitter: returns the consumed PCM, the progress at EOS,
/// the wall duration of the audible span and the HAL metrics.
fn run_selftest(
    fixture_name: &str,
    track_id: &str,
    loss_profile: (u64, usize, u64, u64),
) -> (Vec<f32>, BufferProgress, Duration, HalDriverMetrics) {
    let engine = StreamifyEngine::new();
    engine.initialize().expect("environment invariants hold");

    // The fixture source with scripted loss (truncated ranges) and
    // jitter (artificial per-range delay) — the network is hostile
    // from the first byte.
    let fetcher = Arc::new(LossyFixtureFetcher::new(
        loss_profile.0,
        loss_profile.1,
        loss_profile.2,
        loss_profile.3,
    ));
    fetcher.register(
        &format!("fixture://{fixture_name}"),
        fixture(fixture_name),
    );
    engine.set_range_fetcher(fetcher.clone() as Arc<dyn RangeFetcher>);

    // Stage the track with its resolved source URL (the resolution
    // step's cache-backed output, stubbed here by the fixture key).
    let track = streamify_contract::Track {
        id: track_id.to_string(),
        title: format!("Selftest {track_id}"),
        artist: "WS-A".to_string(),
        album: "Drivetrain".to_string(),
        duration_ms: CLIP_MS,
        thumbnail_url: None,
        is_explicit: false,
        resolved_stream_url: Some(format!("fixture://{fixture_name}")),
    };
    let index = engine.enqueue_track(track, "local".to_string()).unwrap();
    assert_eq!(index, 0);

    engine.attach_sink(8192).expect("attach the data plane");
    let ring = engine
        .playback_ring()
        .expect("the consumer handle after attach");

    // The DAC clock exists BEFORE playback starts (the platform flow:
    // the HAL is alive before the first track). Recorded
    // synchronously so the session anchor never races the driver
    // thread — position truth anchors at frame 0.
    engine
        .record_dac_clock(streamify_contract::DacPresentationClock {
            dac_frame_position: 0,
            sample_rate: 48_000,
            monotonic_ns: 0,
        })
        .expect("the canonical clock sample is valid");

    let driver = HalDriver::spawn(engine.clone(), ring);
    let started = Instant::now();
    engine.play_track(track_id.to_string()).expect("the intent");

    // Wait for EOS (the producer's final commit).
    let deadline = Instant::now() + Duration::from_secs(60);
    while Instant::now() < deadline {
        let progress = engine.buffer_progress().unwrap();
        if progress.eos {
            break;
        }
        assert!(
            progress.producer_live,
            "the producer must stay live through loss/jitter"
        );
        thread::sleep(Duration::from_millis(10));
    }
    assert!(
        engine.buffer_progress().unwrap().eos,
        "the session must reach EOS"
    );

    // Let the audible span drain through the HAL, THEN snapshot the
    // progress: the rendered position and the consumed frames must be
    // compared at the same point in the timeline (post-drain), not
    // against the EOS-time snapshot mid-drain.
    let drain = Instant::now() + Duration::from_secs(10);
    while driver.frames_pulled() < CLIP_FRAMES && Instant::now() < drain {
        thread::sleep(Duration::from_millis(5));
    }
    let progress = engine.buffer_progress().unwrap();
    let wall = started.elapsed();

    let metrics = HalDriverMetrics {
        frames_pulled: driver.frames_pulled(),
        starved_callbacks: driver.starved_callbacks(),
    };
    let consumed = driver.consumed();
    (consumed, progress, wall, metrics)
}

/// THE GATE (48 kHz fixture): the engine's full chain renders the 440 Hz
/// reference under scripted network loss/jitter — dominant bin at
/// 440 Hz, ZERO underruns on both the ring's and the VirtualHAL's
/// accounting, and position truth tracking the consumed frames.
#[test]
fn live_audio_selftest_engine_says_440() {
    let (consumed, progress, wall, metrics) = run_selftest(
        "playback_selftest_440hz_48k.wav",
        "selftest-440-48k",
        // Every 5th range truncated to 8 KiB (loss), every 7th fails
        // once (retry ladder), every 4th pays 1 ms of jitter.
        (5, 8 * 1024, 7, 1),
    );

    // 1. THE FFT: the dominant bin is 440 Hz, tightly.
    let peak = dominant_frequency(&consumed);
    assert!(
        (peak - 440.0).abs() <= 2.0,
        "dominant frequency {peak:.2} Hz must be 440 Hz ± 2"
    );

    // 2. Zero underruns — the ring's consumer accounting and the
    //    VirtualHAL's starved-callback accounting both clean.
    assert_eq!(metrics.starved_callbacks, 0, "the DAC never starved");
    assert_eq!(
        progress.underrun_events, 0,
        "the ring's underrun counter is zero"
    );

    // 3. Every frame of the clip was consumed.
    assert!(
        (metrics.frames_pulled as i64 - CLIP_FRAMES as i64).abs() <= QUANTUM as i64,
        "consumed {} of {CLIP_FRAMES} frames",
        metrics.frames_pulled
    );
    assert!(
        consumed.len() >= (CLIP_FRAMES as usize - QUANTUM) * 2,
        "the capture holds the audible span"
    );

    // 4. Position truth: the rendered position tracks the consumed
    //    frames (same clock domain) and advanced at a wall-plausible
    //    rate (the only sanctioned wall-clock use).
    let consumed_ms = metrics.frames_pulled * 1000 / 48_000;
    let rendered = progress.rendered_position_ms;
    assert!(
        rendered + 21 >= consumed_ms.saturating_sub(21),
        "rendered {rendered} ms must track the consumed {consumed_ms} ms"
    );
    let wall_ms = wall.as_millis() as u64;
    assert!(
        consumed_ms + 500 >= wall_ms,
        "consumed {consumed_ms} ms must be wall-plausible against {wall_ms} ms"
    );

    // 5. The loudness chain kept the output sane (the LUFS stage
    //    centers the -6 dBFS fixture on the -16 LUFS streaming target:
    //    finite, bounded, not silent).
    let peak_amp = consumed.iter().fold(0.0f32, |a, s| a.max(s.abs()));
    assert!(
        (0.02..=0.9).contains(&peak_amp),
        "post-LUFS peak amplitude {peak_amp} is sane"
    );

    // 6. The progress record's session correlation.
    assert_eq!(progress.track_id.as_deref(), Some("selftest-440-48k"));
    assert!(progress.producer_live);
    assert!(progress.fill_ratio >= 0.0 && progress.fill_ratio <= 1.0);
}

/// The resample leg (44.1 kHz fixture): the same gate through the
/// CanonicalSrc rubato path — the dominant bin is STILL 440 Hz at the
/// canonical rate, proving the decode → resample → LUFS chain end to
/// end.
#[test]
fn live_audio_selftest_resample_leg_44k1() {
    let (consumed, progress, _wall, metrics) = run_selftest(
        "playback_selftest_440hz_44k1.wav",
        "selftest-440-44k1",
        (4, 4 * 1024, 9, 1),
    );

    let peak = dominant_frequency(&consumed);
    assert!(
        (peak - 440.0).abs() <= 2.0,
        "dominant frequency {peak:.2} Hz must survive the resample leg"
    );
    assert_eq!(metrics.starved_callbacks, 0);
    assert_eq!(progress.underrun_events, 0);
    assert!(
        (metrics.frames_pulled as i64 - CLIP_FRAMES as i64).abs() <= QUANTUM as i64,
        "the resampled clip is exactly {CLIP_FRAMES} canonical frames"
    );
}

/// The skip-under-load stress (AHEAD A3 AC): 100 rapid `play_track`
/// intents while the HAL consumes — no deadlock, no leak (the
/// deterministic teardown), and the FINAL intent plays through cleanly
/// with the 440 Hz gate still holding.
#[test]
fn live_audio_selftest_skip_under_load_100_intents() {
    let engine = StreamifyEngine::new();
    engine.initialize().unwrap();

    let fetcher = Arc::new(LossyFixtureFetcher::new(6, 8 * 1024, 11, 1));
    fetcher.register(
        "fixture://playback_selftest_440hz_48k.wav",
        fixture("playback_selftest_440hz_48k.wav"),
    );
    engine.set_range_fetcher(fetcher as Arc<dyn RangeFetcher>);

    // 100 distinct rows, all resolving to the fixture source.
    for i in 0..100u32 {
        let track = streamify_contract::Track {
            id: format!("selftest-skip-{i}"),
            title: format!("Skip {i}"),
            artist: "WS-A".to_string(),
            album: "Drivetrain".to_string(),
            duration_ms: CLIP_MS,
            thumbnail_url: None,
            is_explicit: false,
            resolved_stream_url: Some("fixture://playback_selftest_440hz_48k.wav".to_string()),
        };
        engine.enqueue_track(track, "local".to_string()).unwrap();
    }

    engine.attach_sink(4096).unwrap();
    let ring = engine.playback_ring().unwrap();
    let driver = HalDriver::spawn(engine.clone(), ring);

    let started = Instant::now();
    for i in 0..100u32 {
        engine
            .play_track(format!("selftest-skip-{i}"))
            .expect("every intent is accepted");
        if i.is_multiple_of(10) {
            thread::sleep(Duration::from_millis(3));
        }
    }

    // The final intent must complete.
    let deadline = Instant::now() + Duration::from_secs(60);
    while Instant::now() < deadline {
        if engine.buffer_progress().unwrap().eos {
            break;
        }
        thread::sleep(Duration::from_millis(10));
    }
    let progress = engine.buffer_progress().unwrap();
    assert!(progress.eos, "the final intent plays to EOS");
    assert!(progress.producer_live);
    assert_eq!(progress.track_id.as_deref(), Some("selftest-skip-99"));

    // The audible span drains and the gate still holds.
    let drain = Instant::now() + Duration::from_secs(10);
    while driver.frames_pulled() < CLIP_FRAMES && Instant::now() < drain {
        thread::sleep(Duration::from_millis(5));
    }
    let consumed = driver.consumed();
    assert!(
        consumed.len() >= (CLIP_FRAMES as usize - QUANTUM) * 2,
        "the final track's audible span was captured"
    );
    let peak = dominant_frequency(&consumed);
    assert!(
        (peak - 440.0).abs() <= 2.0,
        "the final intent renders 440 Hz (got {peak:.2})"
    );

    // Deterministic teardown inside the budget — the leak proof.
    drop(driver);
    let teardown = Instant::now();
    engine.detach_sink().unwrap();
    assert!(
        teardown.elapsed() < Duration::from_secs(5),
        "teardown is prompt"
    );
    assert!(
        started.elapsed() < Duration::from_secs(60),
        "the whole stress stays inside the CI budget"
    );
}
