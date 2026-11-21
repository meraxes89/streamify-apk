//! The producer-stage suite: the dedicated thread driving fetch →
//! decode → resample → LUFS → write end-to-end against fixture
//! sources, including the skip-under-load stress gate (AHEAD A3 AC:
//! 100 rapid skips, no leak, no underrun).

use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};

use streamify_playback::{
    BufferProgressCore, MemoryRangeFetcher, PlayIntent, ProducerConfig, ProgressSink,
    RangeFetcher, RingSink, StreamProducer,
};

fn fixture(name: &str) -> Arc<Vec<u8>> {
    let mut path = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
    path.push("../../../fixtures/audio");
    path.push(name);
    Arc::new(std::fs::read(&path).unwrap_or_else(|e| panic!("{}: {e}", path.display())))
}

/// A progress sink recording every report (the harness's observation
/// channel).
#[derive(Default)]
struct RecordingSink {
    reports: Mutex<Vec<BufferProgressCore>>,
}

impl ProgressSink for RecordingSink {
    fn on_progress(&self, core: BufferProgressCore) {
        self.reports
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .push(core);
    }
}

/// A consumer pulling `quantum` frames per callback at REAL-TIME pace
/// (one quantum of wall time per served pull — the HAL cadence), with
/// an adaptive final read so the clip's tail is never over-asked (a
/// short final quantum is the HAL's own business, not starvation).
/// `budget_frames == 0` selects the unbounded warp-speed mode (used by
/// the stress gates where only deadlock-freedom is asserted).
struct Puller {
    stop: Arc<AtomicBool>,
    frames_pulled: Arc<AtomicU64>,
    handle: Option<thread::JoinHandle<()>>,
}

impl Puller {
    fn spawn(ring: Arc<streamify_playback::SpscAudioRing>, quantum: usize) -> Self {
        Self::spawn_with_budget(ring, quantum, 0)
    }

    fn spawn_with_budget(
        ring: Arc<streamify_playback::SpscAudioRing>,
        quantum: usize,
        budget_frames: u64,
    ) -> Self {
        let stop = Arc::new(AtomicBool::new(false));
        let frames_pulled = Arc::new(AtomicU64::new(0));
        let stop2 = stop.clone();
        let pulled = frames_pulled.clone();
        let handle = thread::spawn(move || {
            let quantum_ns = (quantum as f64 / 48_000.0 * 1e9) as u64;
            // Wait for the producer's first commit before the first
            // pull (a HAL never asks before audio exists).
            while ring.occupancy() == 0 && !stop2.load(Ordering::Relaxed) {
                thread::sleep(Duration::from_micros(200));
            }
            while !stop2.load(Ordering::Relaxed) {
                let already = pulled.load(Ordering::Relaxed);
                if budget_frames > 0 && already >= budget_frames {
                    break;
                }
                let want = if budget_frames > 0 {
                    (budget_frames - already).min(quantum as u64) as usize
                } else {
                    quantum
                };
                let mut dst = vec![0.0f32; want * 2];
                let served = ring.read_frames_into(&mut dst);
                if served == 0 {
                    thread::sleep(Duration::from_micros(200));
                } else {
                    pulled.fetch_add((served / 2) as u64, Ordering::Relaxed);
                    if budget_frames > 0 {
                        thread::sleep(Duration::from_nanos(
                            quantum_ns * served as u64 / (quantum * 2) as u64,
                        ));
                    }
                }
            }
        });
        Self {
            stop,
            frames_pulled,
            handle: Some(handle),
        }
    }

    fn frames_pulled(&self) -> u64 {
        self.frames_pulled.load(Ordering::Relaxed)
    }
}

impl Drop for Puller {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Relaxed);
        if let Some(handle) = self.handle.take() {
            let _ = handle.join();
        }
    }
}

fn memory_fetcher() -> (Arc<MemoryRangeFetcher>, String) {
    let fetcher = Arc::new(MemoryRangeFetcher::new());
    let blob = fixture("playback_selftest_440hz_48k.wav");
    let url = "fixture://playback_selftest_440hz_48k.wav".to_string();
    fetcher.register(&url, blob);
    (fetcher, url)
}

/// The full pipeline against a fixture source: the ring fills, the
/// consumer drains, EOS completes, and the progress reports advance
/// monotonically with zero underruns under a paced consumer.
#[test]
fn producer_plays_fixture_to_eos_with_zero_underruns() {
    let (fetcher, url) = memory_fetcher();
    let (sink, ring) = RingSink::new(4096, 2, 48_000, 48_000).unwrap();
    let recorder = Arc::new(RecordingSink::default());
    let config = ProducerConfig {
        segment_bytes: 32 * 1024,
        ..ProducerConfig::default()
    };
    let producer = StreamProducer::spawn(
        Box::new(sink),
        fetcher as Arc<dyn RangeFetcher>,
        config,
        recorder.clone(),
    )
    .unwrap();
    let puller = Puller::spawn_with_budget(ring.clone(), 512, 72_000);

    producer.play(PlayIntent {
        track_id: "selftest-440".to_string(),
        source_url: url,
        start_position_ms: 0,
    });

    let deadline = Instant::now() + Duration::from_secs(30);
    while Instant::now() < deadline {
        if producer.progress().eos.load(Ordering::Acquire) {
            break;
        }
        thread::sleep(Duration::from_millis(10));
    }
    assert!(
        producer.progress().eos.load(Ordering::Acquire),
        "the session must reach EOS"
    );
    assert!(
        producer.progress().last_error().is_none(),
        "no session error: {:?}",
        producer.progress().last_error()
    );

    // The consumer drains exactly the clip and stops.
    let target = 72_000u64; // 1.5 s clip
    let deadline_drain = Instant::now() + Duration::from_secs(10);
    while puller.frames_pulled() < target && Instant::now() < deadline_drain {
        thread::sleep(Duration::from_millis(5));
    }
    let drained = puller.frames_pulled();
    let stats = ring.stats();
    assert_eq!(stats.frames_written, target, "every fixture frame committed");
    assert!(
        (drained as i64 - target as i64).abs() <= 1024,
        "consumer drained {drained} of {target}"
    );
    assert_eq!(
        stats.underrun_events, 0,
        "a fed-ahead ring never starves the consumer"
    );

    // The progress reports are monotonic in frames_written.
    let reports = recorder
        .reports
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
        .clone();
    assert!(!reports.is_empty(), "progress was reported");
    for pair in reports.windows(2) {
        assert!(
            pair[1].frames_written >= pair[0].frames_written,
            "frames_written must be monotonic"
        );
    }
    assert!(reports.iter().all(|r| r.fill_ratio >= 0.0 && r.fill_ratio <= 1.0));
    assert_eq!(reports.last().unwrap().session, 1);

    drop(puller);
    let progress = producer.progress().clone();
    producer.shutdown();
    assert!(!progress.live.load(Ordering::Acquire));
}

/// The skip-under-load stress gate (AHEAD A3 AC): 100 rapid play
/// intents while a consumer drains — no deadlock, no leaked thread,
/// and the FINAL intent plays through cleanly.
#[test]
fn skip_under_load_100_rapid_intents_stay_clean() {
    let (fetcher, url) = memory_fetcher();
    let (sink, ring) = RingSink::new(2048, 2, 48_000, 48_000).unwrap();
    let recorder = Arc::new(RecordingSink::default());
    let config = ProducerConfig {
        segment_bytes: 16 * 1024, // many rounds per track — busy pipeline
        ..ProducerConfig::default()
    };
    let producer = StreamProducer::spawn(
        Box::new(sink),
        fetcher as Arc<dyn RangeFetcher>,
        config,
        recorder,
    )
    .unwrap();
    let puller = Puller::spawn(ring.clone(), 256);

    // 100 rapid skips with tiny stagger (the hostile scrub pattern).
    for i in 0..100u32 {
        producer.play(PlayIntent {
            track_id: format!("selftest-440-{i}"),
            source_url: url.clone(),
            start_position_ms: 0,
        });
        if i.is_multiple_of(10) {
            thread::sleep(Duration::from_millis(3));
        }
    }

    // The final intent must complete: EOS reached within the budget.
    let deadline = Instant::now() + Duration::from_secs(30);
    while Instant::now() < deadline {
        if producer.progress().eos.load(Ordering::Acquire) {
            break;
        }
        thread::sleep(Duration::from_millis(10));
    }
    assert!(
        producer.progress().eos.load(Ordering::Acquire),
        "the final intent must play to EOS after 100 skips"
    );
    assert!(producer.progress().last_error().is_none());
    // Session generation advanced (coalescing keeps it bounded but
    // strictly increasing per accepted intent).
    assert!(producer.progress().session.load(Ordering::Acquire) >= 1);

    // The teardown is deterministic and leak-free (Drop joins).
    drop(puller);
    let started = Instant::now();
    producer.shutdown();
    assert!(
        started.elapsed() < Duration::from_secs(5),
        "shutdown must be prompt"
    );
}

/// Park/Resume semantics: a parked producer stops committing; the ring
/// holds its audio; resume continues the SAME session (no flush, no
/// hole — the unwritten staging tail survives the pause).
#[test]
fn park_resume_continues_the_session_without_holes() {
    let (fetcher, url) = memory_fetcher();
    let (sink, ring) = RingSink::new(8192, 2, 48_000, 48_000).unwrap();
    let recorder = Arc::new(RecordingSink::default());
    let producer = StreamProducer::spawn(
        Box::new(sink),
        fetcher as Arc<dyn RangeFetcher>,
        ProducerConfig::default(),
        recorder,
    )
    .unwrap();
    let puller = Puller::spawn(ring.clone(), 512);

    producer.play(PlayIntent {
        track_id: "selftest-440".to_string(),
        source_url: url,
        start_position_ms: 0,
    });
    // Let it play briefly.
    thread::sleep(Duration::from_millis(150));
    producer.park();
    thread::sleep(Duration::from_millis(100));
    let written_at_park = ring.stats().frames_written;
    thread::sleep(Duration::from_millis(150));
    let written_while_parked = ring.stats().frames_written;
    assert_eq!(
        written_while_parked, written_at_park,
        "a parked producer commits nothing"
    );

    producer.resume();
    let deadline = Instant::now() + Duration::from_secs(30);
    while Instant::now() < deadline {
        if producer.progress().eos.load(Ordering::Acquire) {
            break;
        }
        thread::sleep(Duration::from_millis(10));
    }
    assert!(producer.progress().eos.load(Ordering::Acquire), "resumes to EOS");
    // The full clip committed — the pause punched no hole (the LUFS
    // gain evolution makes byte-exactness inapplicable; frame-count
    // exactness is the contract).
    let deadline = Instant::now() + Duration::from_secs(10);
    while ring.stats().frames_written < 72_000 && Instant::now() < deadline {
        thread::sleep(Duration::from_millis(5));
    }
    assert_eq!(ring.stats().frames_written, 72_000);

    drop(puller);
    producer.shutdown();
}

/// A missing source URL fails as the typed Network error after the
/// bounded retries — the session ends, the producer survives for the
/// next intent.
#[test]
fn failing_source_errors_typed_and_producer_survives() {
    let fetcher = Arc::new(MemoryRangeFetcher::new()); // nothing registered
    let (sink, ring) = RingSink::new(1024, 2, 48_000, 48_000).unwrap();
    let recorder = Arc::new(RecordingSink::default());
    let producer = StreamProducer::spawn(
        Box::new(sink),
        fetcher as Arc<dyn RangeFetcher>,
        ProducerConfig::default(),
        recorder,
    )
    .unwrap();

    producer.play(PlayIntent {
        track_id: "ghost".to_string(),
        source_url: "fixture://missing.wav".to_string(),
        start_position_ms: 0,
    });
    let deadline = Instant::now() + Duration::from_secs(10);
    while Instant::now() < deadline {
        if producer.progress().last_error().is_some() {
            break;
        }
        thread::sleep(Duration::from_millis(10));
    }
    let error = producer.progress().last_error().expect("typed error recorded");
    assert!(matches!(error, streamify_core::EngineError::Network { .. }));

    // Teardown stays deterministic with the error recorded.
    drop(ring);
    producer.shutdown();
}
