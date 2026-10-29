//! Resolver → audio SPSC integration smoke test — Discussion #120,
//! Part C.3.
//!
//! Proves the full data-plane wiring the modularization must preserve:
//! a stream resolved through the typed resolver surface (the same
//! UniFFI namespace function the shells call) carries accurate
//! playback metadata, and audio derived from that resolution flows
//! through streamify-audio's SPSC-style producer→HAL-consumer ring
//! with **no buffer tearing and no underruns**:
//!
//!   * **Tearing** would surface as a discontinuity in a frame-index
//!     ramp written by the producer and verified frame-by-frame at the
//!     consumer — every pulled frame must carry exactly its global
//!     frame position, once and in order.
//!   * **Underruns** would surface as a starved HAL pull (a pull that
//!     silence-fills its tail); with a 2048-frame prebuffer and a
//!     steady 1:1 produce:pull cadence the occupancy never falls below
//!     1536 frames, so a single starved callback is a hard failure.
//!
//! Offline by design (the CI matrix must never depend on YouTube
//! reachability or the bot-wall): the resolve is served from the
//! resolver's persistent memo layer — the exact path a repeat resolve
//! takes in production after a first cold resolve — seeded with the
//! format set a real rustypipe player query would have produced.

use streamify_audio::virtual_hal::{AdversarialModes, VirtualHalConfig, VirtualHalSink};
use streamify_contract::{resolve_stream_formats, set_resolver_cache_db};
use streamify_resolver::json::ResolvedStreamFormat;
use streamify_storage::resolver_cache;

/// Canonical engine playback geometry (the Rust audio pipeline's
/// canonical 48 kHz stereo contract).
const RATE: u32 = 48_000;
const CHANNELS: usize = 2;
const DURATION_SEC: u32 = 10;
/// Producer prebuffer before the first HAL pull (frames). With the
/// 1:1 steady-state cadence below this keeps occupancy in
/// [1536, 2560] against the 4096-frame ring — far from both edges.
const PREBUFFER_FRAMES: usize = 2048;
/// Steady-state cadence: one 512-frame producer write per 512-frame HAL
/// pull (the producer leads the consumer by the prebuffer forever).
const QUANTUM_FRAMES: usize = 512;

#[test]
fn resolver_output_flows_into_the_spsc_ring_without_tearing_or_underruns() {
    // ── 1. Bind the resolver's persistent memo database (the shell's
    // init-time `set_resolver_cache_db` call; leaked path — the bind is
    // a process-lifetime singleton). ─────────────────────────────────
    let dir = tempfile::tempdir().expect("temp dir");
    let db_file = dir.keep().join("resolver_audio_smoke.db");
    let leaked = db_file.to_string_lossy().into_owned();
    set_resolver_cache_db(leaked.clone()).expect("cache db bind");

    // ── 2. Seed the memo layer with a prior resolve's format set (a
    // valid 11-char video id; m4a audio-only, sustained 128 kbps,
    // 10 s — the row a real rustypipe player query produces). ────────
    let video_id = "sm0k3rng01A";
    let formats = vec![ResolvedStreamFormat {
        url: "https://rr5---sn-xyz.googlevideo.com/videoplayback?itag=140&...".to_string(),
        mime_type: "audio/mp4; codecs=mp4a.40.2".to_string(),
        bitrate: 128_000,
        duration_sec: DURATION_SEC,
        is_audio_only: true,
    }];
    let json = serde_json::to_string(&formats).expect("format envelope");
    resolver_cache::put_stream_formats(video_id, &json, 3_600);

    // ── 3. Resolve through the CONTRACT surface (the exact namespace
    // function the Kotlin/Swift shells call; Tier-1 cache hit — the
    // production repeat-resolve path). The metadata must map across
    // the FFI contract unchanged. ────────────────────────────────────
    let resolved = resolve_stream_formats(video_id.to_string()).expect("cached resolve");
    assert_eq!(resolved.len(), 1, "exactly the seeded format row");
    let track = &resolved[0];
    assert!(track.is_audio_only, "audio-only rows keep the head slot");
    assert_eq!(track.mime_type, "audio/mp4; codecs=mp4a.40.2");
    assert_eq!(
        track.bitrate, 128_000,
        "sustained bitrate maps across the contract"
    );
    assert_eq!(
        track.duration_sec, DURATION_SEC,
        "duration maps across the contract (the buffer manager sizes from this)"
    );

    // ── 4. The frame budget the buffer manager derives from the
    // RESOLVED metadata (not from a side channel): duration × rate. ──
    let total_frames = track.duration_sec as usize * RATE as usize;
    assert_eq!(total_frames, 480_000, "10 s at 48 kHz");

    // ── 5. The SPSC-style ring: producer writes interleaved frames,
    // simulated HAL pulls consume quanta (streamify-audio's
    // VirtualHalSink — the deterministic twin of the native lock-free
    // AudioSink; one driver thread by contract, same Lamport geometry
    // as production). ───────────────────────────────────────────────
    let config = VirtualHalConfig {
        sample_rate: RATE,
        channels: CHANNELS as u8,
        capacity_frames: 4096,
        callback_frames: QUANTUM_FRAMES,
        modes: AdversarialModes::NONE,
        ..Default::default()
    };
    let mut sink = VirtualHalSink::new(config).expect("virtual HAL sink");

    // Ramp continuity marker: frame f carries value f on every channel.
    // A torn or dropped frame fails the equality below; an out-of-order
    // frame fails it twice.
    let mut produced: usize = 0;
    let mut consumed: usize = 0;

    // Prebuffer.
    write_ramp(&mut sink, 0, PREBUFFER_FRAMES);
    produced += PREBUFFER_FRAMES;

    // Steady state: one quantum write, one quantum pull.
    let mut dst = vec![0.0f32; QUANTUM_FRAMES * CHANNELS];
    while consumed < total_frames {
        // The producer writes FIRST in each cycle: occupancy stays
        // within [PREBUFFER - QUANTUM, PREBUFFER] frames.
        write_ramp(&mut sink, produced, QUANTUM_FRAMES);
        produced += QUANTUM_FRAMES;

        let callback = sink.run_callback_into(&mut dst);
        assert_eq!(
            callback.frames_supplied, QUANTUM_FRAMES,
            "every HAL pull must be satisfied in full"
        );
        assert!(
            !callback.starved,
            "buffer underrun: the resolver-fed ring starved a HAL pull at frame {consumed}"
        );

        // Frame-for-frame continuity: the pulled ramp must equal the
        // global frame positions — no tearing, no duplication, no
        // reordering.
        for (i, sample) in dst.iter().enumerate() {
            let expected = (consumed + i / CHANNELS) as f32;
            assert_eq!(
                *sample, expected,
                "torn frame at pulled position {i} (global frame {})",
                consumed + i / CHANNELS
            );
        }
        consumed += QUANTUM_FRAMES;
    }

    // The main loop ends mid-prebuffer (produced = total_frames +
    // PREBUFFER + one quantum, because each cycle writes before it
    // pulls): drain the remainder — whole quanta first, then one
    // partial pull sized to the exact remainder (the ring's pull path
    // takes any whole-frame quantum) — confirming the ring drains
    // cleanly with no residual corruption and NO starvation: this is
    // the end-of-stream contract, not a mid-playback underrun.
    assert!(
        produced > consumed,
        "the prebuffer remainder must still be in the ring"
    );
    while produced - consumed >= QUANTUM_FRAMES {
        let callback = sink.run_callback_into(&mut dst);
        assert!(!callback.starved, "drain phase must not underrun");
        assert_eq!(callback.frames_supplied, QUANTUM_FRAMES);
        for (i, sample) in dst.iter().enumerate() {
            let expected = (consumed + i / CHANNELS) as f32;
            assert_eq!(*sample, expected, "torn frame in the drain phase");
        }
        consumed += QUANTUM_FRAMES;
    }
    let remainder = produced - consumed;
    if remainder > 0 {
        let mut tail = vec![0.0f32; remainder * CHANNELS];
        let callback = sink.run_callback_into(&mut tail);
        assert!(!callback.starved, "the final partial pull must not underrun");
        assert_eq!(callback.frames_supplied, remainder);
        for (i, sample) in tail.iter().enumerate() {
            let expected = (consumed + i / CHANNELS) as f32;
            assert_eq!(*sample, expected, "torn frame in the final partial pull");
        }
        consumed += remainder;
    }

    // ── 6. Final accounting: everything the resolver-metadata-sized
    // producer wrote was delivered, exactly once, with zero silenced
    // tails and zero overruns. ───────────────────────────────────────
    let stats = sink.stats();
    assert_eq!(stats.frames_written as usize, produced);
    assert_eq!(stats.frames_pulled as usize, produced);
    assert_eq!(
        consumed,
        produced,
        "consumer accounts for every produced frame exactly once"
    );
    // The ring delivered the ENTIRE track (the buffer budget derived
    // from the resolver's metadata): the track's own frames are a
    // strict subset of everything that crossed the ring —
    // produced == PREBUFFER + ceil(total/quantum) * quantum.
    assert!(stats.frames_pulled as usize >= total_frames);
    assert_eq!(stats.frames_silenced, 0, "no silence-fill ever happened");
    assert_eq!(stats.starved_callbacks, 0, "zero underrun callbacks");
    assert_eq!(stats.overrun_events, 0, "zero overrun events");
}

/// One producer-side ramp write: `frames` interleaved frames starting at
/// global frame `from`, every channel carrying the frame index (the
/// continuity marker the consumer verifies frame-for-frame). A short
/// write (ring full) is a hard failure — the cadence guarantees
/// headroom.
fn write_ramp(sink: &mut VirtualHalSink, from: usize, frames: usize) {
    let mut samples = Vec::with_capacity(frames * CHANNELS);
    for f in from..from + frames {
        for _ch in 0..CHANNELS {
            samples.push(f as f32);
        }
    }
    let written = sink.write_producer_frames(&samples);
    assert_eq!(
        written,
        frames * CHANNELS,
        "producer writes must never be short (ring headroom is guaranteed by cadence)"
    );
}
