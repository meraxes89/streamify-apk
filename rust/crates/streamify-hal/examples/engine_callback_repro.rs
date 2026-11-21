//! Repro #3: the engine's EXACT output-callback body (copied verbatim
//! from engine.rs build_stream) driven by a plain std thread at
//! callback pace, against the same producer pattern as the player.
//! If the clock slot stays empty or reads serve 0 here, the bug is in
//! the callback body itself (clock publish / frames_counter).
use streamify_core::DacPresentationClock;
use streamify_hal::{DacClockSlot, StopFlag, CANONICAL_CHANNELS};
use streamify_playback::ring_export;
use streamify_playback::SpscAudioRing;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};

pub(crate) struct EngineRt {
    pub clock: Arc<DacClockSlot>,
    pub underrun_frames: AtomicU64,
    pub frames_counter: AtomicU64,
    pub first_callback_done: AtomicBool,
    pub callback_count: AtomicU64,
    pub stop: StopFlag,
}

fn main() {
    let ring = Arc::new(SpscAudioRing::new(65_536, 2, 48_000, 48_000).unwrap());
    ring_export::install(Arc::clone(&ring));
    let pcm: Vec<f32> = (0..144_000 * 2).map(|i| (i as f32 * 0.01).sin()).collect();
    let rt = Arc::new(EngineRt {
        clock: Arc::new(DacClockSlot::default()),
        underrun_frames: AtomicU64::new(0),
        frames_counter: AtomicU64::new(0),
        first_callback_done: AtomicBool::new(false),
        callback_count: AtomicU64::new(0),
        stop: StopFlag::default(),
    });
    let p_ring = Arc::clone(&ring);
    let producer = std::thread::spawn(move || {
        let quantum = 512 * 2;
        let mut pos = 0usize;
        while pos < pcm.len() && p_ring.occupancy() < 8_192 {
            let take = quantum.min(pcm.len() - pos);
            let wrote = p_ring.write_frames(&pcm[pos..pos + take]);
            pos += wrote;
            if wrote == 0 { break; }
        }
        while pos < pcm.len() {
            let take = quantum.min(pcm.len() - pos);
            let wrote = p_ring.write_frames(&pcm[pos..pos + take]);
            pos += wrote;
            if wrote == 0 { std::thread::sleep(Duration::from_millis(10)); }
            else { std::thread::sleep(Duration::from_millis(8)); }
        }
        pos
    });
    let rt2 = Arc::clone(&rt);
    let consumer = std::thread::spawn(move || {
        let rate: u32 = 48_000;
        let mut dst = vec![0f32; 512 * 2];
        let start = Instant::now();
        let anchor_start = Instant::now();
        while start.elapsed() < Duration::from_secs(2) {
            let data = &mut dst;
            rt2.callback_count.fetch_add(1, Ordering::Relaxed);
            let _ = rt2.first_callback_done.swap(true, Ordering::Relaxed);
            let frames = data.len() / CANONICAL_CHANNELS as usize;
            if frames == 0 { continue; }
            if rt2.stop.get() { data.fill(0.0); continue; }
            let served = unsafe { ring_export::streamify_ring_read_f32(data.as_mut_ptr(), frames) };
            let served_samples = served * CANONICAL_CHANNELS as usize;
            if served_samples < data.len() {
                rt2.underrun_frames.fetch_add(((data.len() - served_samples) / 2) as u64, Ordering::Relaxed);
                for s in data[served_samples..].iter_mut() { *s = 0.0; }
            }
            rt2.frames_counter.fetch_add(frames as u64, Ordering::Relaxed);
            let presented = rt2.frames_counter.load(Ordering::Relaxed);
            let monotonic_ns = anchor_start.elapsed().as_nanos() as u64;
            rt2.clock.publish(&DacPresentationClock {
                dac_frame_position: presented,
                sample_rate: rate,
                monotonic_ns,
            });
            std::thread::sleep(Duration::from_millis(10));
        }
    });
    let slot = Arc::clone(&rt.clock);
    let mut last_seq = 0u64;
    let mut clock_samples = 0u64;
    let start = Instant::now();
    while start.elapsed() < Duration::from_secs(2) {
        std::thread::sleep(Duration::from_millis(50));
        if let Some((_, seq)) = slot.sample() {
            if seq != last_seq { last_seq = seq; clock_samples += 1; }
        }
    }
    let pw = producer.join().unwrap();
    consumer.join().unwrap();
    let s = ring.stats();
    println!("producer_pos={pw} callbacks={} clock_samples={clock_samples} last_seq={last_seq}", rt.callback_count.load(Ordering::Relaxed));
    println!("frames_written={} frames_read={} underrun_events={} underrun_frames={}", s.frames_written, s.frames_read, s.underrun_events, rt.underrun_frames.load(Ordering::Relaxed));
    ring_export::clear();
}
