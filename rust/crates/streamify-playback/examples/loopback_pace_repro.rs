// Local reproduction of the loopback player's producer+consumer pattern:
//   producer: paced writer (512-frame quanta, 8ms ticks)
//   consumer: 512-frame reads at ~46Hz (callback pace)
//   question: does the consumer ever observe served>0 after the first
//             reads? does occupancy-freeze happen? any underruns?
// Mirrors: SpscAudioRing::new(65536,2,48000,48000), write_frames(samples)
//          returns SAMPLES, read_frames_into(dst) returns SAMPLES.
use streamify_playback::sink::SpscAudioRing;
use std::sync::Arc;
use std::time::{Duration, Instant};

fn main() {
    let ring = Arc::new(SpscAudioRing::new(65_536, 2, 48_000, 48_000).unwrap());
    let pcm: Vec<f32> = (0..144_000 * 2).map(|i| (i as f32 * 0.01).sin()).collect();
    let (p_ring, c_ring) = (Arc::clone(&ring), Arc::clone(&ring));
    let producer = std::thread::spawn(move || {
        let quantum = 512 * 2;
        let mut pos = 0usize;
        while pos < pcm.len() && ring_full(&p_ring) < 8_192 {
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
    // Consumer at callback pace.
    let consumer = std::thread::spawn(move || {
        let mut dst = vec![0f32; 512 * 2];
        let mut served_total = 0usize;
        let mut reads = 0usize;
        let start = Instant::now();
        while start.elapsed() < Duration::from_secs(2) {
            let served = c_ring.read_frames_into(&mut dst);
            served_total += served / 2;
            reads += 1;
            std::thread::sleep(Duration::from_millis(10));
        }
        (reads, served_total, c_ring.stats().underrun_events)
    });
    let pw = producer.join().unwrap();
    let (reads, served, under) = consumer.join().unwrap();
    let s = ring.stats();
    println!("producer_pos_samples={pw} reads={reads} served_frames={served} underruns={under}");
    println!("stats: written={} read={} fullness={} overrun_events={}", s.frames_written, s.frames_read, s.current_fullness_frames, s.overrun_events);
}
fn ring_full(r: &SpscAudioRing) -> usize { r.occupancy() }
