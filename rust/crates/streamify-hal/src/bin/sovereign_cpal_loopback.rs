//! Sovereign HAL Phase 1 (D5): the cpal OS-loopback player.
//!
//! The end-to-end leg of the desktop loopback gates: this binary plays
//! a REAL audio file (the Opus/AAC fixtures, decoded through the
//! production TrackDecoder chain) into the OS mixer through the cpal
//! output engine — the same engine the desktop app ships — while the
//! CI job captures the OS-mixed output (WASAPI loopback / BlackHole /
//! PipeWire null-sink monitor) and FFT-verifies it with
//! `scripts/sovereign_loopback_verifier.py`.
//!
//! The binary ALSO prints the DAC presentation-clock drift verdict: it
//! samples [`DacClockSlot`] during playback and validates the ±100 ppm
//! / <5 ms acceptance window (`validate_drift_window`) — the D2 gate.
//!
//! CLI contract (stable — CI scripts against it):
//!
//!   sovereign-cpal-loopback --file <fixture> [--seconds N] [--json]
//!
//! * `--file`    an Opus (Ogg or raw) or AAC fixture the TrackDecoder
//!               chain decodes (the REAL codec path, not a synth tone).
//! * `--seconds` cap playback duration (default: the file's length).
//! * `--json`    machine-readable summary (exit code still the verdict).
//!
//! Exit codes: 0 = played cleanly (underruns 0, ring stats healthy,
//! clock drift within ±100 ppm); 1 = verification failed; 2 = usage.

use cpal::traits::HostTrait;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};

use streamify_core::EngineError;
use streamify_hal::clock::validate_drift_window;
use streamify_hal::{OutputEngine, RouteOption};
use streamify_playback::ring_export;
use streamify_playback::{
    FrameBuf, OpusTier, OpusTrackDecoder, RingSink, SpscAudioRing, SymphoniaDecoder, TrackDecoder,
};

struct Args {
    file: String,
    seconds: f64,
    json: bool,
    /// Bypass the hygiene ranking: open the host's default output
    /// device directly (the CI legs' contract — the virtual loopback
    /// device IS the system default by the time the player runs).
    any_device: bool,
    /// Open a specific named device (the macOS leg's contract: the
    /// system default may be a DIFFERENT virtual device — BlackHole
    /// must be targeted by name).
    device: Option<String>,
}

fn parse_args() -> Result<Args, String> {
    let mut args = std::env::args().skip(1);
    let mut file = None;
    let mut seconds = 4.0f64;
    let mut json = false;
    let mut any_device = false;
    let mut device: Option<String> = None;
    while let Some(a) = args.next() {
        match a.as_str() {
            "--file" => file = args.next(),
            "--seconds" => {
                seconds = args
                    .next()
                    .and_then(|s| s.parse().ok())
                    .ok_or("--seconds needs a number")?;
            }
            "--json" => json = true,
            "--any-device" => any_device = true,
            "--device" => device = args.next(),
            _ => return Err(format!("unknown arg {a}")),
        }
    }
    Ok(Args {
        file: file.ok_or("--file required")?,
        seconds: seconds.clamp(0.5, 60.0),
        json,
        any_device,
        device,
    })
}

/// Decode a fixture through the production chain into canonical PCM
/// (48 kHz interleaved stereo f32). Opus (both framings) rides the
/// Phase 1 OpusTrackDecoder; AAC rides SymphoniaDecoder.
fn decode_fixture(path: &str) -> Result<Vec<f32>, EngineError> {
    let bytes = std::fs::read(path).map_err(|e| EngineError::Audio {
        kind: streamify_core::AudioErrorKind::Init,
        device_id: Some(format!("read {path}: {e}")),
    })?;
    let is_opus = bytes.len() > 19
        && (&bytes[0..4] == b"OggS" || &bytes[0..8] == b"OpusHead");
    let mut out = FrameBuf::with_capacity(65_536, 2)?;
    if is_opus {
        let mut dec = OpusTrackDecoder::new(OpusTier::Primary);
        dec.push_range(&bytes, &mut out)?;
        dec.finish(&mut out)?;
    } else {
        let mut dec = SymphoniaDecoder::new()?;
        dec.push_range(&bytes, &mut out)?;
        dec.finish(&mut out)?;
    }
    Ok(out.as_slice().to_vec())
}

fn main() {
    let code = run();
    std::process::exit(code);
}

fn run() -> i32 {
    let args = match parse_args() {
        Ok(a) => a,
        Err(e) => {
            eprintln!("usage error: {e}");
            return 2;
        }
    };
    // 1. Decode the fixture (the real codec path).
    let pcm = match decode_fixture(&args.file) {
        Ok(p) => p,
        Err(e) => {
            eprintln!("decode failed: {e:?}");
            return 2;
        }
    };
    if pcm.is_empty() {
        eprintln!("fixture decoded to zero samples");
        return 2;
    }
    // 2. Install the ring + the producer (RT-paced writer).
    // The fixture is ~3 s; the legs play 6 s. Loop the PCM to cover the
    // full window (round-42: the 3 s tail with an empty ring produced
    // ~144k underrun frames — exactly the fixture length — and failed
    // the macOS verdict; the device clock pulls 48 kHz realtime
    // regardless of how much audio exists).
    let loop_target_samples = (args.seconds.ceil() as usize + 1) * 48_000 * 2;
    let mut pcm = pcm;
    if pcm.len() < loop_target_samples {
        let unit = pcm.len();
        let mut loops = 1;
        while pcm.len() < loop_target_samples {
            pcm.extend_from_within(0..unit);
            loops += 1;
        }
        eprintln!("fixture looped ×{loops} to cover {} s", args.seconds);
    }
    let ring = match SpscAudioRing::new(65_536, 2, 48_000, 48_000) {
        Ok(r) => Arc::new(r),
        Err(e) => {
            eprintln!("ring init failed: {e:?}");
            return 2;
        }
    };
    ring_export::install(Arc::clone(&ring));
    let done = Arc::new(AtomicBool::new(false));
    let producer_done = Arc::clone(&done);
    let producer_pcm = Arc::new(pcm);
    let producer_ring = Arc::clone(&ring); // the closure's own handle
    let producer = std::thread::spawn(move || {
        let ring = producer_ring;
        let quantum = 2048 * 2; // lead-fill quantum (interleaved)
        let mut pos = 0usize;
        let start = Instant::now();
        // Lead-fill: the prime-then-connect discipline. write_frames
        // returns the SAMPLE count served (interleaved units).
        while pos < producer_pcm.len() && ring.occupancy() < 16_384 {
            let take = quantum.min(producer_pcm.len() - pos);
            let wrote = ring.write_frames(&producer_pcm[pos..pos + take]);
            pos += wrote;
            if wrote == 0 {
                break;
            }
        }
        // Keep-full feed (round-41: even the cushion-paced producer
        // starved BlackHole's bursts — the callback under-served nearly
        // every quantum; the runner's thread scheduling makes any
        // sleep-based pacing unreliable against a realtime device
        // thread). The producer now writes WHATEVER is free each pass
        // and only sleeps when the ring is completely full. The 65536-
        // frame capacity (1.37 s) IS the jitter absorber; the fixture
        // (144k frames = 3 s) is fully consumed by the callback at
        // realtime pace, so the producer only needs to stay ahead of
        // 48 kHz — trivial for a busy loop with full-ring parking.
        while pos < producer_pcm.len() && !producer_done.load(Ordering::Relaxed) {
            let free = ring.capacity() - ring.occupancy();
            if free == 0 {
                std::thread::sleep(Duration::from_millis(2));
                continue;
            }
            let take = (free * 2).min(producer_pcm.len() - pos);
            let wrote = ring.write_frames(&producer_pcm[pos..pos + take]);
            pos += wrote;
            if wrote == 0 {
                std::thread::sleep(Duration::from_millis(2));
            }
        }
        let _ = start;
    });
    // 3. Open the cpal output route.
    // Diagnostics: what does cpal see on this host? (per-host default
    // device + 48k-stereo capability) — the loopback legs' first log
    // lines when something goes sideways.
    for id in cpal::available_hosts() {
        let Ok(h) = cpal::host_from_id(id) else { continue };
        let def = h
            .default_output_device()
            .and_then(|d| {
                cpal::traits::DeviceTrait::description(&d)
                    .ok()
                    .map(|desc| desc.name().to_string())
            })
            .unwrap_or_else(|| "<none>".into());
        eprintln!("cpal-host {:?}: default_output = {def}", id);
        if let Ok(mut devices) = h.output_devices() {
            for d in devices.by_ref() {
                if let Ok(desc) = cpal::traits::DeviceTrait::description(&d) {
                    eprintln!("  device: {:?}", desc.name());
                }
            }
        }
    }

    let (mut engine, events) = OutputEngine::new(16);
    let route = if let Some(name) = args.device.as_deref() {
        RouteOption::Named(name.to_string())
    } else if args.any_device {
        RouteOption::Default
    } else {
        RouteOption::Auto
    };
    if let Err(why) = engine.start(&route) {
        ring_export::clear();
        eprintln!("cpal route open failed: {why}");
        if args.json {
            println!(
                "{{\"verdict\": \"no-endpoint\", \"reason\": {}}}",
                serde_json_quote(&why)
            );
        }
        // No endpoint is NOT a codec failure — but CI's loopback legs
        // MUST have the virtual device present, so surface exit 1.
        return 1;
    }
    // 4. Play for --seconds, sampling the clock slot.
    let deadline = Instant::now() + Duration::from_secs_f64(args.seconds);
    let mut clock_samples = Vec::new();
    let slot = engine.clock_slot();
    let mut last_seq = 0u64;
    let mut fired_reported = false;
    while Instant::now() < deadline {
        std::thread::sleep(Duration::from_millis(50));
        if !fired_reported && engine.callback_fired() {
            fired_reported = true;
            eprintln!("engine: first output callback fired (main-thread poll)");
        }
        if let Some((sample, seq)) = slot.sample() {
            if seq != last_seq {
                last_seq = seq;
                clock_samples.push(sample);
            }
        }
        if done.load(Ordering::Relaxed) && ring.occupancy() == 0 {
            break;
        }
    }
    let stats = {
        let mut wire = [0u8; 96];
        // SAFETY: valid stack buffer.
        unsafe { ring_export::streamify_ring_stats_wire(wire.as_mut_ptr(), wire.len()) };
        ring.stats()
    };
    done.store(true, Ordering::Relaxed);
    let _ = producer.join();
    engine.stop_stream();
    let events = events.drain();
    let underruns = stats.underrun_events + engine.underrun_frames();
    // 5. The verdict.
    let (ex_z, ex_s, ex_f) = engine.callback_exits();
    eprintln!(
        "engine: callback_count={} clock_samples={} exits(zero_frames/stop/full)={ex_z}/{ex_s}/{ex_f}",
        engine.callback_count(),
        clock_samples.len()
    );
    let drift_ok = match validate_drift_window(&clock_samples) {
        Ok((ppm, sync_ns)) => ppm.abs() <= 100.0 && sync_ns < 5_000_000,
        Err(why) => {
            eprintln!("clock window unusable: {why}");
            false
        }
    };
    let verdict = underruns == 0 && drift_ok;
    if args.json {
        let (ppm, sync_ns) = validate_drift_window(&clock_samples)
            .unwrap_or((f64::NAN, 0));
        println!(
            "{{\"verdict\": \"{}\", \"device\": {}, \"frames\": {}, \"underruns\": {}, \"clock_ppm\": {}, \"clock_sync_ns\": {}, \"events\": {}}}",
            if verdict { "pass" } else { "fail" },
            serde_json_quote(engine.active_device().unwrap_or("none")),
            stats.frames_written,
            underruns,
            ppm,
            sync_ns,
            serde_json_quote(&format!("{events:?}")),
        );
    } else {
        let span = clock_samples
            .first()
            .zip(clock_samples.last())
            .map(|(f, l)| (l.dac_frame_position - f.dac_frame_position, l.monotonic_ns - f.monotonic_ns))
            .unwrap_or((0, 0));
        eprintln!(
            "device={} frames_written={} underruns={} clock_samples={} first_frames={} first_ns={} last_frames={} last_ns={} span_frames={span:?} drift_ok={drift_ok}",
            engine.active_device().unwrap_or("none"),
            stats.frames_written,
            underruns,
            clock_samples.len(),
            clock_samples.first().map(|s| s.dac_frame_position).unwrap_or(0),
            clock_samples.first().map(|s| s.monotonic_ns).unwrap_or(0),
            clock_samples.last().map(|s| s.dac_frame_position).unwrap_or(0),
            clock_samples.last().map(|s| s.monotonic_ns).unwrap_or(0),
        );
    }
    ring_export::clear();
    if verdict {
        0
    } else {
        1
    }
}

/// Minimal JSON string quoting (no serde dependency in the binary).
fn serde_json_quote(s: &str) -> String {
    let mut out = String::with_capacity(s.len() + 2);
    out.push('"');
    for c in s.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            _ => out.push(c),
        }
    }
    out.push('"');
    out
}

// Silence the unused-RingSink import warning (the ring is used through
// ring_export + SpscAudioRing directly; RingSink remains the documented
// producer-side handle).
#[allow(dead_code)]
fn _ring_sink_marker() -> Option<RingSink> {
    None
}
