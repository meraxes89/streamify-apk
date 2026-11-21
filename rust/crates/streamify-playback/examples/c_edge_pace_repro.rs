// Repro #2: the exact player pattern through the FROZEN C EDGE —
// ring_export::install() + streamify_ring_read_f32 from a "consumer"
// thread at callback pace, while the player's producer pattern feeds.
// If this freezes (reads serve 0 forever / occupancy pins), the bug is
// in the export path; if it flows, the bug is in the engine/cpal layer.
use streamify_playback::ring_export;
use streamify_playback::SpscAudioRing;
use std::sync::Arc;
use std::time::{Duration, Instant};

fn main() {
    let ring = Arc::new(SpscAudioRing::new(65_536, 2, 48_000, 48_000).unwrap());
    ring_export::install(Arc::clone(&ring)); // exactly like the player
    let pcm: Vec<f32> = (0..144_000 * 2).map(|i| (i as f32 * 0.01).sin()).collect();
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
    // "Consumer": the C edge at callback pace (512 frames per ~10ms).
    let consumer = std::thread::spawn(move || {
        let mut dst = vec![0f32; 512 * 2];
        let mut served_total = 0u64;
        let mut calls = 0u64;
        let mut zero_serve_streak = 0u64;
        let start = Instant::now();
        while start.elapsed() < Duration::from_secs(2) {
            let served = unsafe { ring_export::streamify_ring_read_f32(dst.as_mut_ptr(), 512) };
            calls += 1;
            if served == 0 { zero_serve_streak += 1; } else { zero_serve_streak = 0; }
            served_total += served as u64;
            std::thread::sleep(Duration::from_millis(10));
        }
        (calls, served_total, zero_serve_streak)
    });
    let pw = producer.join().unwrap();
    let (calls, served, streak) = consumer.join().unwrap();
    println!("producer_pos={pw} c_edge_calls={calls} served_frames={served} longest_zero_streak={streak}");
    println!("occupancy_now={} channels_export={}", ring.occupancy(), unsafe_check());
    ring_export::clear();
}
fn unsafe_check() -> u32 { unsafe { std::mem::transmute::<u32, u32>(0) }.max(0) + unsafe_extern_channels() }
fn unsafe_extern_channels() -> u32 { ring_export_channels() }
#[allow(non_snake_case)]
fn ring_export_channels() -> u32 { extern "C" { fn streamify_ring_channels() -> u32; } unsafe { streamify_ring_channels() } }
