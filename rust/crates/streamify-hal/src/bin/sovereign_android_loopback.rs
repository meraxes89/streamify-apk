//! Sovereign HAL Phase 2 (Step 1): the ANDROID Oboe loopback player.
//!
//! The end-to-end leg of the Android AudioFlinger gates (API 26/29/34/
//! 35): this binary runs ON THE GUEST (pushed via adb, executed via
//! `adb shell`), decodes the real Opus fixture through the production
//! TrackDecoder chain, installs the frozen ring, opens the Oboe output
//! bracket (C++ side, `libstreamify_native_core.so`) and plays through
//! the guest's audioserver while CI captures AudioFlinger's mixed
//! output (`adb exec-out screencap`-style pull of a guest-side capture
//! file) and FFT-verifies it with the shared host verifier.
//!
//! The binary ALSO prints the DAC presentation-clock drift verdict from
//! Oboe's `AudioStream::getTimestamp` (the D2 gate on Android): the
//! C++ bracket polls it at 10 Hz on a controller thread and republishes
//! through `sovereign_oboe_dac_clock` — the `record_dac_clock` bridge.
//!
//! CLI contract (stable — CI scripts against it):
//!
//!   sovereign-android-loopback --file <fixture> [--seconds N] [--json]
//!   [--api 1|2]   1 = Oboe/AAudio first (default), 2 = force OpenSL ES
//!
//! * `--file`    an Opus (Ogg or raw) or AAC fixture the TrackDecoder
//!               chain decodes (the REAL codec path, not a synth tone).
//! * `--seconds` play duration (default 6; the fixture loops to cover).
//! * `--json`    machine-readable summary (exit code still the verdict).
//!
//! Exit codes: 0 = played cleanly (0 underrun frames, ring stats
//! healthy, clock drift within ±100 ppm); 1 = verification failed;
//! 2 = usage/decode error; 3 = bracket open failed (the ladder's cue).
//!
//! ANDROID-ONLY: the binary is `// Desktop builds compile this to an empty translation unit: the extern
// block resolves only against the NDK-built libstreamify_native_core.so
// (the C++ Oboe bracket). Workspace-wide builds still parse every bin —
// an empty-crate `main` keeps them green without per-platform stanzas.
#[cfg(not(target_os = "android"))]
fn main() {
    eprintln!("sovereign_android_loopback: Android-only binary (rebuild for aarch64-linux-android)");
    std::process::exit(2);
}

#[cfg(target_os = "android")]
mod android_main_inner {
// (ANDROID-ONLY: desktop builds compile the shim `main` above — the
// extern block resolves only against the NDK-built bracket .so.)
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};

use streamify_core::{AudioErrorKind, EngineError};
use streamify_playback::ring_export;
use streamify_playback::{
    FrameBuf, OpusTier, OpusTrackDecoder, SpscAudioRing, SymphoniaDecoder, TrackDecoder,
};

struct Args {
    file: String,
    seconds: f64,
    json: bool,
    /// 1 = Oboe/AAudio first (the ladder's top rung), 2 = force
    /// OpenSL ES (the second rung — API 26 + OEM quirks).
    api: u32,
}

fn parse_args() -> Result<Args, String> {
    let mut args = std::env::args().skip(1);
    let mut file = None;
    let mut seconds = 6.0f64;
    let mut json = false;
    let mut api = 1u32;
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
            "--api" => {
                api = args
                    .next()
                    .and_then(|s| s.parse().ok())
                    .ok_or("--api needs 1 (AAudio) or 2 (OpenSL ES)")?;
                if api != 1 && api != 2 {
                    return Err("--api must be 1 or 2".into());
                }
            }
            other => return Err(format!("unknown arg {other}")),
        }
    }
    Ok(Args {
        file: file.ok_or("--file <fixture> required")?,
        seconds,
        json,
        api,
    })
}

fn decode_fixture(path: &str) -> Result<Vec<f32>, EngineError> {
    let bytes = std::fs::read(path).map_err(|e| EngineError::Audio {
        kind: AudioErrorKind::Init,
        device_id: Some(format!("read {path}: {e}")),
    })?;
    let is_opus = bytes.len() > 19 && (&bytes[0..4] == b"OggS" || &bytes[0..8] == b"OpusHead");
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

// ── The C++ bracket's C ABI (oboe_output_bracket.cpp) ───────────────────────
#[repr(C)]
struct DacClockSample {
    frame: u64,
    rate: u32,
    monotonic_ns: u64,
}

extern "C" {
    fn sovereign_oboe_open(audio_api: i32) -> i32;
    fn sovereign_oboe_close();
    fn sovereign_oboe_callbacks() -> u64;
    fn sovereign_oboe_underrun_frames() -> u64;
    fn sovereign_oboe_ring_occupancy() -> u64;
    fn sovereign_oboe_dac_clock(frame: *mut u64, rate: *mut u32, ns: *mut u64) -> i32;
    fn sovereign_oboe_stats_wire(dst: *mut u8, dst_bytes: i32) -> i32;
    fn sovereign_oboe_api_route() -> i32;
}

/// One (frame_position, monotonic_ns) pair from the bracket's clock
/// mailbox — the `record_dac_clock` sample shape (DacPresentationClock
/// minus the Rust-side struct; the C edge is the boundary).
#[derive(Clone, Copy, Debug)]
struct ClockPoint {
    frame: u64,
    ns: u64,
}

/// The drift validation (the D2 acceptance window, ±100 ppm): least
/// squares over the (monotonic_ns, frame) pairs against the nominal
/// rate — the same shape `validate_drift_window` holds on desktop,
/// re-implemented here against the C edge's samples because the hal
/// crate's DacClockSlot is cpal-side.
fn drift_ppm(samples: &[ClockPoint], nominal_rate: u32) -> Option<(f64, f64)> {
    if samples.len() < 2 {
        return None;
    }
    let n = samples.len() as f64;
    let mut sum_t = 0f64;
    let mut sum_f = 0f64;
    let mut sum_tt = 0f64;
    let mut sum_tf = 0f64;
    for s in samples {
        let t = s.ns as f64 / 1e9;
        let f = s.frame as f64;
        sum_t += t;
        sum_f += f;
        sum_tt += t * t;
        sum_tf += t * f;
    }
    let denom = n * sum_tt - sum_t * sum_t;
    if denom.abs() < 1e-12 {
        return None;
    }
    let slope = (n * sum_tf - sum_t * sum_f) / denom;  // frames per second
    let ppm = (slope - nominal_rate as f64) / nominal_rate as f64 * 1e6;
    // Mean absolute residual (seconds) — the sync tightness proxy.
    let intercept = (sum_f - slope * sum_t) / n;
    let mut resid = 0f64;
    for s in samples {
        let t = s.ns as f64 / 1e9;
        resid += (s.frame as f64 - (slope * t + intercept)).abs();
    }
    let mean_resid_frames = resid / n;
    let sync_ns = mean_resid_frames / nominal_rate as f64 * 1e9;
    Some((ppm, sync_ns))
}

pub(crate) fn main() {
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
    // 2. Loop the PCM across the play window (the Phase 1 round-42
    //    lesson: the device clock pulls realtime regardless of how
    //    much audio exists — an uncovered tail reads as underruns).
    let loop_target = ((args.seconds.ceil() as usize) + 1) * 48_000 * 2;
    let mut pcm = pcm;
    if pcm.len() < loop_target {
        let unit = pcm.len();
        let mut loops = 1usize;
        while pcm.len() < loop_target {
            pcm.extend_from_within(0..unit);
            loops += 1;
        }
        eprintln!("fixture looped ×{loops} to cover {} s", args.seconds);
    }
    // 3. Install the ring + keep-full producer (the r41 discipline:
    //    write whatever is free; sleep only when full).
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
    let p_ring = Arc::clone(&ring);
    let producer = std::thread::spawn(move || {
        let mut pos = 0usize;
        while pos < pcm.len() && !producer_done.load(Ordering::Relaxed) {
            let free = p_ring.capacity() - p_ring.occupancy();
            if free == 0 {
                std::thread::sleep(Duration::from_millis(2));
                continue;
            }
            let take = (free * 2).min(pcm.len() - pos);
            let wrote = p_ring.write_frames(&pcm[pos..pos + take]);
            pos += wrote;
            if wrote == 0 {
                std::thread::sleep(Duration::from_millis(2));
            }
        }
    });
    // 4. Open the Oboe bracket (the ladder: AAudio first, OpenSL on
    //    explicit request — the caller walks the rungs).
    let open = unsafe { sovereign_oboe_open(args.api as i32) };
    if open != 0 {
        eprintln!("oboe bracket open failed (code {open})");
        if args.json {
            println!("{{\"verdict\": \"no-endpoint\", \"reason\": \"oboe open failed ({open})\"}}");
        }
        return 3;
    }
    let route = unsafe { sovereign_oboe_api_route() };
    eprintln!(
        "oboe bracket open: route={}",
        match route {
            1 => "AAudio",
            2 => "OpenSL ES",
            _ => "unknown",
        }
    );
    // 5. Play for --seconds, sampling the DAC clock mailbox (50 ms
    //    poll — the main thread; the bracket's controller polls
    //    getTimestamp itself at 10 Hz).
    let deadline = Instant::now() + Duration::from_secs_f64(args.seconds);
    let mut clock_points: Vec<ClockPoint> = Vec::new();
    let clock_count = Arc::new(AtomicU64::new(0));
    let mut last_frame = u64::MAX;
    while Instant::now() < deadline {
        std::thread::sleep(Duration::from_millis(50));
        let mut frame = 0u64;
        let mut rate = 0u32;
        let mut ns = 0u64;
        if unsafe { sovereign_oboe_dac_clock(&mut frame, &mut rate, &mut ns) } == 1
            && frame != last_frame
        {
            last_frame = frame;
            clock_count.fetch_add(1, Ordering::Relaxed);
            clock_points.push(ClockPoint { frame, ns });
        }
        if done.load(Ordering::Relaxed) && ring.occupancy() == 0 {
            break;
        }
    }
    // 6. Tear down + collect the verdict inputs.
    done.store(true, Ordering::Relaxed);
    let _ = producer.join();
    let callbacks = unsafe { sovereign_oboe_callbacks() };
    let underrun_frames = unsafe { sovereign_oboe_underrun_frames() };
    let mut wire = [0u8; 96];
    let wire_ok = unsafe { sovereign_oboe_stats_wire(wire.as_mut_ptr(), 96) } == 1;
    unsafe { sovereign_oboe_close() };
    ring_export::clear();
    // The 96-byte wire: magic u32 LE at offset 0 (0x51AB0001) +
    // frames_written u64 LE at 32 + frames_read u64 LE at 40 +
    // underrun_events u32 LE at 60 (the frozen layout, sink.rs).
    let (frames_written, frames_read, underrun_events) = if wire_ok {
        let magic = u32::from_le_bytes([wire[0], wire[1], wire[2], wire[3]]);
        if magic != 0x51AB_0001 {
            eprintln!("stats wire magic mismatch: {magic:#x}");
            (0, 0, 0)
        } else {
            let rd = |off: usize| -> u64 {
                u64::from_le_bytes(wire[off..off + 8].try_into().unwrap())
            };
            (rd(32), rd(40), u32::from_le_bytes(wire[60..64].try_into().unwrap()) as u64)
        }
    } else {
        eprintln!("stats wire unavailable (ring detached?)");
        (0, 0, 0)
    };
    let (ppm, sync_ns) = drift_ppm(&clock_points, 48_000).unwrap_or((f64::NAN, 0.0));
    let drift_ok = ppm.is_finite() && ppm.abs() <= 100.0;
    let verdict = underrun_frames == 0 && wire_ok && drift_ok;
    eprintln!(
        "engine: callbacks={callbacks} clock_samples={} underrun_frames={underrun_frames} \
         frames_written={frames_written} frames_read={frames_read} wire_ok={wire_ok} \
         ppm={ppm:.3} sync_ns={sync_ns:.0}",
        clock_count.load(Ordering::Relaxed)
    );
    if args.json {
        println!(
            "{{\"verdict\": \"{}\", \"route\": {}, \"frames\": {}, \"underruns\": {}, \
             \"underrun_events\": {}, \"clock_ppm\": {}, \"clock_sync_ns\": {}, \
             \"clock_samples\": {}, \"callbacks\": {}}}",
            if verdict { "pass" } else { "fail" },
            match route {
                1 => "\"AAudio\"",
                2 => "\"OpenSL ES\"",
                _ => "\"unknown\"",
            },
            frames_written,
            underrun_frames,
            underrun_events,
            ppm,
            sync_ns,
            clock_count.load(Ordering::Relaxed),
            callbacks,
        );
    }
    if verdict {
        0
    } else {
        1
    }
}

}


#[cfg(target_os = "android")]
fn main() {
    android_main_inner::main()
}
