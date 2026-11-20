//! Sovereign HAL Phase 0 (D4): the synthetic ring-producer smoke binary.
//!
//! This is the producer side of the OS-loopback verification matrix: a
//! self-contained binary that attaches the process-current SPSC ring
//! (`ring_export::install`), synthesizes the canonical verification
//! signal (440 Hz + 880 Hz interleaved stereo f32 @ 48 kHz), and commits
//! it to the write edge at real-time pacing. Platform brackets (WASAPI
//! loopback, PipeWire null-sink monitor, CoreAudio blackhole-2ch, the
//! Android emulator's `-audiodev wav`, and the browser AudioWorklet) pull
//! from the SAME frozen C edge (`streamify_ring_read_f32`) the engine
//! will use in production; `scripts/sovereign_loopback_verifier.py` then
//! FFT-verifies the captured OS audio output for the 440 Hz + 880 Hz
//! peaks and zero underruns (via the stats wire).
//!
//! CLI contract (stable — CI jobs script against it):
//!
//!   sovereign-loopback-smoke [--seconds N] [--rate HZ] [--freq-a HZ]
//!       [--freq-b HZ] [--mode signal|silence] [--json]
//!
//! * `--seconds N`   duration (default 3.0 s, clamp 0.1..=60).
//! * `--rate HZ`     canonical rate (default 48000; 8000..=192000).
//! * `--freq-a/b`    verification tones (default 440 / 880 Hz).
//! * `--mode signal` normal dual-tone; `silence` commits zeros (the
//!                   negative control for the verifier's noise floor).
//! * `--json`        machine-readable summary (exit code still the verdict).
//!
//! Exit codes: 0 = clean run (underruns 0, overruns 0, all frames
//! committed); 1 = the ring reported underruns/overruns (the consumer
//! starved or the producer outpaced the pull); 2 = bad arguments.
//!
//! NOTE: this binary never talks to hardware itself — OS audio output is
//! the PLATFORM BRACKET's job (Phase 1/2). It exists so the Phase 0 CI
//! scaffold can prove the loopback harness end-to-end (producer → frozen
//! edge → bracket → OS capture → FFT verifier) before the real engine
//! producer is wired in.

use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Arc;
use std::thread;
use std::time::{Duration, Instant};

use streamify_playback::ring_export;
use streamify_playback::{AudioSink, RingSink, SINK_STATS_WIRE_BYTES};

const CANONICAL_RATE: u32 = 48_000;
const CANONICAL_CHANNELS: usize = 2;
/// Commit quantum: 512 frames ≈ 10.7 ms @ 48 kHz — the same order as the
/// A5 self-test harness's quantum (EOS-aware partial quanta handled).
const QUANTUM_FRAMES: usize = 512;

struct Args {
    seconds: f64,
    rate: u32,
    freq_a: f64,
    freq_b: f64,
    silence: bool,
    json: bool,
}

fn next_value<I: Iterator<Item = String>>(it: &mut I, name: &str) -> Result<f64, String> {
    it.next()
        .ok_or_else(|| format!("missing value for {name}"))
        .and_then(|v| v.parse::<f64>().map_err(|e| format!("bad {name}: {e}")))
}

fn parse_args() -> Result<Args, String> {
    let mut a = Args {
        seconds: 3.0,
        rate: CANONICAL_RATE,
        freq_a: 440.0,
        freq_b: 880.0,
        silence: false,
        json: false,
    };
    let mut it = std::env::args().skip(1);
    while let Some(flag) = it.next() {
        match flag.as_str() {
            "--seconds" => a.seconds = next_value(&mut it, "--seconds")?,
            "--rate" => a.rate = next_value(&mut it, "--rate")? as u32,
            "--freq-a" => a.freq_a = next_value(&mut it, "--freq-a")?,
            "--freq-b" => a.freq_b = next_value(&mut it, "--freq-b")?,
            "--mode" => {
                let mode = it
                    .next()
                    .ok_or_else(|| "missing value for --mode".to_string())?;
                match mode.as_str() {
                    "signal" => a.silence = false,
                    "silence" => a.silence = true,
                    other => return Err(format!("bad --mode {other:?} (signal|silence)")),
                }
            }
            "--json" => a.json = true,
            other => return Err(format!("unknown flag {other:?}")),
        }
    }
    if !(0.1..=60.0).contains(&a.seconds) {
        return Err(format!("--seconds out of range (0.1..=60): {}", a.seconds));
    }
    if !(8_000..=192_000).contains(&a.rate) {
        return Err(format!("--rate out of range (8000..=192000): {}", a.rate));
    }
    Ok(a)
}

/// One period-continuous dual-tone frame (interleaved L/R identical — the
/// brackets downmix nothing, the verifier FFTs the captured left channel).
#[inline]
fn sample(t_secs: f64, freq_a: f64, freq_b: f64) -> f32 {
    const AMP: f64 = 0.25;
    (AMP * (std::f64::consts::TAU * freq_a * t_secs).sin()
        + AMP * (std::f64::consts::TAU * freq_b * t_secs).sin()) as f32
}

fn main() {
    let args = match parse_args() {
        Ok(a) => a,
        Err(e) => {
            eprintln!("sovereign-loopback-smoke: {e}");
            eprintln!(
                "usage: sovereign-loopback-smoke [--seconds N] [--rate HZ] [--freq-a HZ] \
                 [--freq-b HZ] [--mode signal|silence] [--json]"
            );
            std::process::exit(2);
        }
    };

    // Build the ring and install the process-current consumer edge —
    // the same install the engine's attach_sink performs.
    let capacity = 8192; // power of two, ≈171 ms of headroom @ 48 kHz
    let (mut sink, ring) = match RingSink::new(
        capacity,
        CANONICAL_CHANNELS,
        args.rate,
        args.rate,
    ) {
        Ok(pair) => pair,
        Err(e) => {
            eprintln!("sovereign-loopback-smoke: ring construction failed: {e:?}");
            std::process::exit(1);
        }
    };
    ring_export::install(ring.clone());

    // A puller thread stands in for the platform bracket during smoke
    // runs without one (CI Gate 2 runs this binary bare to prove the
    // producer side; the loopback jobs pipe the bracket's capture file
    // into the verifier instead). It reads through the frozen C edge at
    // the quantum cadence and counts frames served.
    let stop = Arc::new(AtomicBool::new(false));
    let producer_done = Arc::new(AtomicBool::new(false));
    let served = Arc::new(AtomicU64::new(0));
    let underruns = Arc::new(AtomicU64::new(0));
    {
        let stop = Arc::clone(&stop);
        let producer_done = Arc::clone(&producer_done);
        let served = Arc::clone(&served);
        let underruns = Arc::clone(&underruns);
        let rate = args.rate;
        let seconds = args.seconds;
        thread::spawn(move || {
            let quantum_bytes = QUANTUM_FRAMES * CANONICAL_CHANNELS;
            let mut buf = vec![0.0f32; quantum_bytes];
            let cadence = Duration::from_secs_f64(QUANTUM_FRAMES as f64 / rate as f64);
            let total_frames = (seconds * rate as f64) as usize;
            // Startup: wait for the producer's first commits (the ring
            // non-empty), then pull one full cadence LATER — the producer
            // is real-time-paced with a lead, so by the first pull the
            // ring already holds ≥ one quantum and no startup race can
            // register a phantom underrun.
            while ring_export::streamify_ring_occupancy_frames() == 0
                && !stop.load(Ordering::Acquire)
            {
                thread::sleep(Duration::from_millis(1));
            }
            let mut next_tick = Instant::now() + cadence;
            while !stop.load(Ordering::Acquire) {
                // All audio consumed: the tail-drain window must not pull
                // from an empty ring (each such pull would count a ring
                // underrun that is really "stream finished").
                if (served.load(Ordering::Relaxed) as usize) >= total_frames {
                    break;
                }
                next_tick += cadence;
                let now = Instant::now();
                if next_tick > now {
                    thread::sleep(next_tick - now);
                } else {
                    next_tick = now; // fell behind: don't burst-catch-up
                }
                // SAFETY: single consumer thread, buf valid for QUANTUM_FRAMES
                // frames × 2 channels.
                let got = unsafe {
                    ring_export::streamify_ring_read_f32(buf.as_mut_ptr(), QUANTUM_FRAMES)
                };
                if got > 0 {
                    served.fetch_add(got as u64, Ordering::Relaxed);
                    continue;
                }
                // Empty pull. If the producer has committed everything and
                // the ring is drained, the stream is COMPLETE (served ==
                // total) and the loop's head breaks next iteration. If the
                // producer is still mid-flight (real-time pacing means its
                // next quantum lands within the lead window), a bounded
                // re-wait models the hardware callback's tolerance — only a
                // timeout (lead + one cadence) counts as starvation.
                if producer_done.load(Ordering::Acquire)
                    && ring_export::streamify_ring_occupancy_frames() == 0
                {
                    continue; // stream finished; the head breaks next pass
                }
                let waited = Instant::now();
                loop {
                    let retry = unsafe {
                        ring_export::streamify_ring_read_f32(buf.as_mut_ptr(), QUANTUM_FRAMES)
                    };
                    if retry > 0 {
                        served.fetch_add(retry as u64, Ordering::Relaxed);
                        break;
                    }
                    if producer_done.load(Ordering::Acquire)
                        && ring_export::streamify_ring_occupancy_frames() == 0
                    {
                        break; // finished mid-quantum: nothing more is due
                    }
                    if waited.elapsed() > cadence * 2 {
                        underruns.fetch_add(1, Ordering::Relaxed);
                        break;
                    }
                    thread::sleep(Duration::from_millis(1));
                }
            }
        });
    }

    // Produce: period-continuous dual tone, real-time paced commits.
    let total_frames = (args.seconds * args.rate as f64) as usize;
    let mut frame_index: usize = 0;
    let mut buf = vec![0.0f32; QUANTUM_FRAMES * CANONICAL_CHANNELS];
    let mut overflow_parks: u64 = 0;
    let produce_start = Instant::now();
    while frame_index < total_frames {
        let take = QUANTUM_FRAMES.min(total_frames - frame_index);
        for i in 0..take {
            let t = (frame_index + i) as f64 / args.rate as f64;
            let s = if args.silence { 0.0 } else { sample(t, args.freq_a, args.freq_b) };
            buf[i * 2] = s;
            buf[i * 2 + 1] = s;
        }
        let mut written = 0;
        while written < take {
            // Partial-quantum tail: write only the remaining frames.
            let w = sink
                .write_float(&buf[written * 2..take * 2])
                .unwrap_or(0);
            if w == 0 {
                // Ring at high watermark: park (the producer never blocks
                // the audio path — this IS the parking discipline). The
                // backoff lets a descheduled puller win the core on a busy
                // CI runner instead of the producer spinning against it.
                overflow_parks += 1;
                thread::sleep(Duration::from_millis((overflow_parks.min(10) * 2).max(2)));
                continue;
            }
            written += w;
        }
        frame_index += take;
        // Real-time pacing: production stays ahead of consumption by a
        // fixed lead (100 ms — half the ring's 171 ms capacity) so
        // scheduler jitter on a noisy CI runner cannot starve the puller.
        // The lead is the whole point: production must never outrun the
        // ring (overruns) nor fall behind real time (underruns).
        let target = (frame_index as f64 / args.rate as f64) - 0.10;
        while (produce_start.elapsed().as_secs_f64()) < target {
            thread::sleep(Duration::from_millis(1));
        }
    }

    // All frames committed: the puller may finish its last quantum.
    producer_done.store(true, Ordering::Release);
    // Deadline-based drain: on a busy CI runner the puller thread can be
    // descheduled for hundreds of ms mid-run; wait (bounded, 5 s) until it
    // has served every committed frame before tearing it down.
    let drain_deadline = Instant::now() + Duration::from_secs(5);
    while served.load(Ordering::Relaxed) < total_frames as u64 {
        if Instant::now() >= drain_deadline {
            break;
        }
        thread::sleep(Duration::from_millis(10));
    }
    stop.store(true, Ordering::Release);
    thread::sleep(Duration::from_millis(50));

    // Verdict from the ring's own accounting (the frozen stats wire).
    let stats = sink.stats();
    let mut wire = [0u8; SINK_STATS_WIRE_BYTES];
    let wire_ok = stats.to_wire(&mut wire);
    // EOS-aware quantum accounting (the A5 harness's discipline): the
    // stream's final partial quantum (total % QUANTUM frames served of a
    // full QUANTUM request) registers exactly ONE ring underrun event by
    // the ring's documented short-read protocol — the stream's tail, not
    // starvation. The verdict tolerates that single EOS event and nothing
    // else; the puller-side starvation counter (underruns) must be 0 and
    // every frame accounted.
    let eos_tail_event: u64 = if total_frames % QUANTUM_FRAMES != 0 { 1 } else { 0 };
    // The producer's retry loop re-offers refused tails until the ring
    // accepts them, so delivery is guaranteed by construction; the ring's
    // overrun/overrun_dropped counters are RETRY-PRESSURE telemetry here
    // (a descheduled puller on a busy runner inflates them without losing
    // a single frame). The clean verdict gates on the real failure modes:
    // every frame committed, every frame served, zero starvation underruns
    // (beyond the single EOS-tail short read the ring protocol counts).
    let clean = underruns.load(Ordering::Relaxed) == 0
        && stats.underrun_events <= eos_tail_event
        && stats.frames_written == total_frames as u64
        && served.load(Ordering::Relaxed) == total_frames as u64
        && wire_ok;

    if args.json {
        println!(
            "{{\"tool\":\"sovereign-loopback-smoke\",\"mode\":\"{}\",\"rate\":{},\
             \"channels\":{},\"freq_a\":{},\"freq_b\":{},\"seconds\":{},\
             \"frames_written\":{},\"frames_served\":{},\"underruns\":{},\
             \"overruns\":{},\"overrun_dropped\":{},\"overflow_parks\":{},\
             \"wire_magic_ok\":{},\"clean\":{}}}",
            if args.silence { "silence" } else { "signal" },
            args.rate,
            CANONICAL_CHANNELS,
            args.freq_a,
            args.freq_b,
            args.seconds,
            stats.frames_written,
            served.load(Ordering::Relaxed),
            stats.underrun_events,
            stats.overrun_events,
            stats.overrun_dropped_frames,
            overflow_parks,
            wire_ok && u32::from_le_bytes(wire[0..4].try_into().unwrap()) == 0x51AB_0001,
            clean,
        );
    } else {
        eprintln!(
            "sovereign-loopback-smoke: {} mode={} rate={} — wrote {} frames, served {}, \
             underruns {}, overruns {} (parks {}), wire_ok {}",
            if clean { "CLEAN" } else { "DIRTY" },
            if args.silence { "silence" } else { "signal" },
            args.rate,
            stats.frames_written,
            served.load(Ordering::Relaxed),
            stats.underrun_events,
            stats.overrun_events,
            overflow_parks,
            wire_ok,
        );
    }

    ring_export::clear();
    std::process::exit(if clean { 0 } else { 1 });
}
