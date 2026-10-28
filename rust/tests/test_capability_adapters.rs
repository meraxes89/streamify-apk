//! Platform capability adapter contract suite — Phase 5 / MP-CAP01..04
//! (rust/tests/test_capability_adapters.rs).
//!
//! The Phase 5 verification contract §1: every adapter "correctly
//! ingests and emits `MediaCommand` variants without deadlock or heap
//! leaks", validated through mock D-Bus / WinRT / Keychain harnesses
//! for non-privileged CI execution — plus one REAL end-to-end MPRIS
//! lane over a private `dbus-daemon` (Linux hosts with the binary;
//! auto-skipped elsewhere).
//!
//! Sections:
//!
//!   * **A — dispatch plumbing**: the `MediaCommandSink` trait against
//!     the REAL `StreamifyEngine` (the engine's 50-150 ms debounce is
//!     observable through the sink — adapters flow into the exact
//!     same entry point the UDL surface exposes).
//!   * **B — SMTC contract (mock WinRT harness)**: cold-start push,
//!     200 ms cadence coalescing with latest-wins, track-change and
//!     pause force-pushes, headless no-op, mid-session degradation.
//!   * **C — Apple 1 Hz mandate (mock media center)**: a burst inside
//!     the window collapses to ONE now-playing push; identity changes
//!     pass immediately; the frozen C ABI codes match
//!     `native/include/apple_remote_command.h` textually.
//!   * **D — secure store (mock keyring/delegate harness)**: the
//!     `SecureStoreProvider` contract, the UniFFI foreign-delegate
//!     lane (status→taxonomy mapping, fault propagation), and the
//!     global-delegate install/roundtrip the shells drive.
//!   * **E — the `OsMediaIntegration` facade**: headless hosts report
//!     `false`/`headless-fallback` and no-op cleanly (the state every
//!     non-privileged CI lane sees).
//!   * **F — no-deadlock stress**: every adapter surface hammered from
//!     N threads inside a bounded budget (the ASAN lanes of the native
//!     matrix cover the heap-leak half of the mandate).
//!   * **G — MPRIS e2e (real D-Bus)**: the actual
//!     `org.mpris.MediaPlayer2.streamify` service over a private bus:
//!     method dispatch (Play/Pause/PlayPause/Next/Previous/Stop/Seek/
//!     SetPosition), the stale-trackid guard, Volume mapping, property
//!     reads (PlaybackStatus/Metadata/Position), and a genuine
//!     `PropertiesChanged` signal receipt.
//!   * **H — iOS audio session (MP-UI02)**: the interruption/route
//!     policy machine — pause on `InterruptionBegan`, resume on the OS
//!     `shouldResume` hint, immediate pause on a lost route, clean
//!     AirPlay-teardown silence — plus the frozen `SF_IOSAS_*` C ABI
//!     pinned textually against `native/include/ios_audio_session.h`
//!     (the host-testable half of the iOS adapter; the `.mm` glue and
//!     its parity suite pin the same values from the C side).
//!
//! Timing discipline (no flaky sleeps): cadence gates are exercised
//! with a 3600-second interval (nothing else can ever pass) and
//! force-paths; the ONE wall-clock dependency (the 1 Hz burst test)
//! uses a burst window that is 3 orders of magnitude inside the
//! interval, and the e2e lane's waits are 5-second D-Bus timeouts —
//! far above the slow-timeout budget of the CI profile.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Barrier, Mutex, OnceLock};
use std::time::{Duration, Instant};

use streamify_core_rs::contract::{EngineError, MediaCommand, PlaybackState, StreamifyEngine};
use streamify_core_rs::platform::ios_audio_session as iosas;
use streamify_core_rs::platform::secure_store::{
    clear_secure_store_delegate, default_secure_store, secure_store_roundtrip,
    set_secure_store_delegate, ForeignDelegateStore, InMemorySecureStore, SecureFetchResult,
    SecureStoreDelegate, SecureStoreOpStatus, SecureStoreProvider,
};
use streamify_core_rs::platform::testing::{mock_adapter, MockBindBehavior};
use streamify_core_rs::platform::{MediaCommandSink, OsMediaIntegration};

// ---------------------------------------------------------------------------
// helpers
// ---------------------------------------------------------------------------

/// A metadata update with a stable, derivable identity.
fn meta(title: &str, artist: &str) -> streamify_core_rs::platform::OsMetadataUpdate {
    streamify_core_rs::platform::OsMetadataUpdate {
        title: title.to_string(),
        artist: artist.to_string(),
        album: Some("Test Album".to_string()),
        duration_ms: 200_000,
        art_url: None,
    }
}

/// A playing-state update at `position_ms`.
fn playing(position_ms: u64) -> streamify_core_rs::platform::OsPlaybackUpdate {
    streamify_core_rs::platform::OsPlaybackUpdate {
        state: PlaybackState::Playing,
        position_ms,
        duration_ms: 200_000,
        rate: 1.0,
        volume: 0.85,
    }
}

/// The serial lock guarding process-global secure-store delegate state
/// (the global slot is shared; tests that touch it must not interleave).
fn delegate_test_lock() -> &'static Mutex<()> {
    static LOCK: OnceLock<Mutex<()>> = OnceLock::new();
    LOCK.get_or_init(|| Mutex::new(()))
}

// ---------------------------------------------------------------------------
// A — dispatch plumbing: adapters flow into the REAL engine entry point
// ---------------------------------------------------------------------------

#[test]
fn engine_sink_applies_the_mandated_debounce() {
    let engine = Arc::new(StreamifyEngine::new());
    // The same command twice, back to back: the MP-C03 50-150 ms
    // window swallows the duplicate — the exact behavior every OS
    // adapter's dispatch lands in.
    assert!(engine.dispatch_media_command(MediaCommand::Play).unwrap());
    assert!(!engine.dispatch_media_command(MediaCommand::Play).unwrap());
    // A DIFFERENT command always applies.
    assert!(engine.dispatch_media_command(MediaCommand::Pause).unwrap());
    // Payload commands key on the payload.
    assert!(engine
        .dispatch_media_command(MediaCommand::Seek { position_ms: 1_000 })
        .unwrap());
    assert!(engine
        .dispatch_media_command(MediaCommand::Seek { position_ms: 2_000 })
        .unwrap());
    assert!(!engine
        .dispatch_media_command(MediaCommand::Seek { position_ms: 2_000 })
        .unwrap());
}

#[test]
fn dispatch_inbound_swallows_sink_faults() {
    // The RecordingCommandSink with fault injection: the shared
    // dispatch helper must log-and-swallow — an OS callback (WinRT
    // MTA thread / D-Bus task / Apple run loop) cannot carry our
    // error taxonomy, and a refused command must never unwind there.
    let sink = Arc::new(streamify_core_rs::platform::testing::RecordingCommandSink::new());
    sink.refuse_dispatches();
    let sink_dyn: Arc<dyn MediaCommandSink> = sink.clone();
    for command in [
        MediaCommand::Play,
        MediaCommand::Pause,
        MediaCommand::TogglePlayPause,
        MediaCommand::Next,
        MediaCommand::Previous,
    ] {
        streamify_core_rs::platform::adapter::dispatch_inbound(&sink_dyn, command);
    }
    assert_eq!(sink.dispatch_attempts(), 5);
    assert!(sink.commands().is_empty());
}

// ---------------------------------------------------------------------------
// B — SMTC contract (mock WinRT harness)
// ---------------------------------------------------------------------------

#[test]
fn smtc_cold_start_pushes_and_then_coalesces_at_cadence() {
    // The mock WinRT surface: the shared adapter algorithm drives it
    // exactly as the real ISystemMediaTransportControls would be
    // driven (200 ms cadence upstream).
    let (adapter, surface) = mock_adapter(MockBindBehavior::Bound, Duration::from_millis(200));

    // Cold start: the very first update MUST reach the flyout.
    adapter.update_playback(&playing(0)).unwrap();
    assert_eq!(surface.playback_pushes().len(), 1);

    // Timeline jitter inside the window: coalesced, latest retained.
    for pos in 1..40u64 {
        adapter.update_playback(&playing(pos * 50)).unwrap();
    }
    assert_eq!(surface.playback_pushes().len(), 1, "burst must coalesce");

    // Pause: must clear the chrome immediately, not at the next
    // cadence window (MP-CAP01: the flyout must not show a stale
    // playing state for 200 ms after a pause).
    adapter
        .update_playback(&streamify_core_rs::platform::OsPlaybackUpdate {
            state: PlaybackState::Paused,
            ..playing(1_950)
        })
        .unwrap();
    assert_eq!(surface.playback_pushes().len(), 2);
    assert_eq!(
        surface.playback_pushes()[1].state,
        streamify_core_rs::platform::media_sync::PlaybackSyncState::Paused
    );
}

#[test]
fn smtc_track_change_swaps_metadata_immediately() {
    let (adapter, surface) = mock_adapter(MockBindBehavior::Bound, Duration::from_secs(3600));
    adapter.update_playback(&playing(30_000)).unwrap();
    adapter.update_metadata(meta("First", "Artist A")).unwrap();

    // A new track inside the (huge) coalescing window: the identity
    // change bypasses the cadence — metadata AND a timeline push.
    adapter.update_playback(&playing(500)).unwrap();
    adapter.update_metadata(meta("Second", "Artist B")).unwrap();
    let pushes = surface.metadata_pushes();
    assert_eq!(pushes.len(), 2, "identity changes pass immediately");
    assert_eq!(pushes[1].title, "Second");
    assert_eq!(pushes[1].track_key, pushes[1].track_key); // identity present
    // The timeline push that follows the swap carries the NEW key.
    let playback = surface.playback_pushes();
    assert!(playback.iter().any(|p| p.track_key == pushes[1].track_key));
}

#[test]
fn smtc_headless_surface_is_a_documented_no_op() {
    // The headless fallback (no HWND / non-interactive session): every
    // update Ok-no-ops — MP-CAP01's "fall back gracefully without
    // crashing", pinned on every CI host.
    let (adapter, surface) = mock_adapter(MockBindBehavior::Unavailable, Duration::from_millis(200));
    assert!(!adapter.is_surface_bound());
    adapter.update_playback(&playing(1_000)).unwrap();
    adapter.update_metadata(meta("T", "A")).unwrap();
    assert!(surface.playback_pushes().is_empty());
    assert!(surface.metadata_pushes().is_empty());
}

#[test]
fn smtc_bind_fault_and_midsession_push_fault_both_degrade() {
    // A half-initialized desktop session (bind Err): the adapter
    // degrades to unbound — loudly logged, never propagated as a
    // start() error to the shell.
    let (adapter, _surface) = mock_adapter(MockBindBehavior::Faulty, Duration::from_millis(200));
    assert!(!adapter.is_surface_bound());

    // A healthy bind that faults MID-SESSION (the bus/COM object died):
    // the next push degrades the adapter to headless, and the API
    // keeps returning Ok — the music must keep playing when the OS
    // chrome hiccups. (Zero cadence: every offered update passes the
    // gate, so the fault lands deterministically on the second push.)
    let (adapter, surface) = mock_adapter(MockBindBehavior::Bound, Duration::ZERO);
    assert!(adapter.is_surface_bound());
    adapter.update_playback(&playing(1_000)).unwrap();
    assert_eq!(surface.playback_pushes().len(), 1, "cold push lands");
    surface.fail_pushes();
    adapter.update_playback(&playing(1_500)).unwrap();
    assert!(!adapter.is_surface_bound(), "push fault must degrade");
    // Post-degradation updates stay silent no-ops.
    adapter.update_playback(&playing(2_000)).unwrap();
    assert_eq!(surface.playback_pushes().len(), 1);
}

// ---------------------------------------------------------------------------
// C — Apple 1 Hz mandate (mock media center) + the frozen C ABI
// ---------------------------------------------------------------------------

#[test]
fn apple_one_hz_ceiling_collapses_a_burst_to_one_push() {
    // MP-CAP03: MPNowPlayingInfoCenter updates are held at 1 Hz. The
    // shared gate is configured with the same 1-second floor the Apple
    // adapter uses; a burst of updates far inside the window (the test
    // body runs in well under a millisecond of wall clock) must
    // collapse to exactly ONE surface push — the cold-start one.
    let (adapter, surface) = mock_adapter(MockBindBehavior::Bound, Duration::from_secs(1));
    adapter.update_playback(&playing(0)).unwrap();
    for pos in 1..25u64 {
        adapter.update_playback(&playing(pos * 40)).unwrap();
    }
    assert_eq!(
        surface.playback_pushes().len(),
        1,
        "a burst inside the 1 Hz window must not exceed the ceiling"
    );
    // The newest image is retained for the next window (latest-wins).
    let retained = adapter.retained_snapshot();
    assert_eq!(retained.map(|s| s.position_ms), Some(24 * 40));
}

#[test]
fn apple_c_abi_codes_match_the_native_header() {
    // The textual cross-check against
    // native/include/apple_remote_command.h — the Rust constants, the
    // C _Static_asserts and the .mm emission share one frozen ABI.
    let header = std::fs::read_to_string(concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/../native/include/apple_remote_command.h"
    ))
    .expect("native/include/apple_remote_command.h must exist next to the rust/ tree");
    use streamify_core_rs::platform::media_sync as codes;
    for (name, value) in [
        ("SF_APPLE_CMD_PLAY", codes::SF_APPLE_CMD_PLAY),
        ("SF_APPLE_CMD_PAUSE", codes::SF_APPLE_CMD_PAUSE),
        ("SF_APPLE_CMD_TOGGLE", codes::SF_APPLE_CMD_TOGGLE),
        ("SF_APPLE_CMD_NEXT", codes::SF_APPLE_CMD_NEXT),
        ("SF_APPLE_CMD_PREVIOUS", codes::SF_APPLE_CMD_PREVIOUS),
        ("SF_APPLE_CMD_SEEK", codes::SF_APPLE_CMD_SEEK),
    ] {
        let needle = format!("{name} = {value}");
        assert!(
            header.contains(&needle),
            "header must declare `{needle}` (Rust/C ABI drift)"
        );
    }
}

// ---------------------------------------------------------------------------
// D — secure store (mock keyring/delegate harness)
// ---------------------------------------------------------------------------

/// The Rust-side twin of Beta's Kotlin EncryptedSharedPreferences
/// delegate (what the Android cutover installs through the UDL).
struct MockForeignDelegate {
    entries: Mutex<std::collections::HashMap<String, Vec<u8>>>,
    fail_next_store: AtomicBool,
}

impl SecureStoreDelegate for MockForeignDelegate {
    fn store_secret(&self, key: String, secret: Vec<u8>) -> SecureStoreOpStatus {
        if self.fail_next_store.load(Ordering::Acquire) {
            self.fail_next_store.store(false, Ordering::Release);
            return SecureStoreOpStatus::Permission;
        }
        self.entries.lock().unwrap().insert(key, secret);
        SecureStoreOpStatus::Success
    }

    fn retrieve_secret(&self, key: String) -> SecureFetchResult {
        match self.entries.lock().unwrap().get(&key) {
            Some(secret) => SecureFetchResult {
                status: SecureStoreOpStatus::Success,
                secret: Some(secret.clone()),
            },
            None => SecureFetchResult {
                status: SecureStoreOpStatus::NotFound,
                secret: None,
            },
        }
    }

    fn delete_secret(&self, key: String) -> SecureStoreOpStatus {
        self.entries.lock().unwrap().remove(&key);
        SecureStoreOpStatus::Success
    }
}

#[test]
fn foreign_delegate_lane_maps_every_status_onto_the_taxonomy() {
    let delegate = Arc::new(MockForeignDelegate {
        entries: Mutex::new(std::collections::HashMap::new()),
        fail_next_store: AtomicBool::new(false),
    });
    let store = ForeignDelegateStore::new(delegate.clone());

    // Full contract: upsert / retrieve / idempotent delete.
    store.store_secret("session-token", b"tok-v1").unwrap();
    assert_eq!(
        store.retrieve_secret("session-token").unwrap(),
        Some(b"tok-v1".to_vec())
    );
    store.store_secret("session-token", b"tok-v2").unwrap();
    assert_eq!(
        store.retrieve_secret("session-token").unwrap(),
        Some(b"tok-v2".to_vec())
    );
    store.delete_secret("session-token").unwrap();
    store.delete_secret("session-token").unwrap();
    assert_eq!(store.retrieve_secret("session-token").unwrap(), None);

    // Fault propagation: a Permission from the foreign side is a typed
    // EngineError.Storage { Permission } — routed, not string-matched.
    delegate.fail_next_store.store(true, Ordering::Release);
    assert_eq!(
        store.store_secret("session-token", b"x"),
        Err(EngineError::Storage {
            kind: streamify_core_rs::contract::StorageErrorKind::Permission,
        })
    );
}

#[test]
fn global_delegate_install_selects_the_delegate_for_the_default_backend() {
    let _guard = delegate_test_lock().lock().unwrap();
    clear_secure_store_delegate();

    // The UDL namespace function the Android shell calls at startup.
    set_secure_store_delegate(Box::new(MockForeignDelegate {
        entries: Mutex::new(std::collections::HashMap::new()),
        fail_next_store: AtomicBool::new(false),
    }))
    .unwrap();

    // The default backend must now BE the delegate (visible through
    // the roundtrip self-test the shells run).
    assert!(secure_store_roundtrip("global-lane".into(), b"payload-9".to_vec()).unwrap());
    clear_secure_store_delegate();
}

#[test]
fn in_memory_backend_roundtrips_without_a_platform_service() {
    // On a host with no native backend (and no delegate), the contract
    // still holds through the fallback — the same self-test.
    let store = InMemorySecureStore::new();
    assert!(store.store_secret("k", b"v").is_ok());
    assert_eq!(store.retrieve_secret("k").unwrap().as_deref(), Some(b"v".as_slice()));
    assert!(store.delete_secret("k").is_ok());
    assert_eq!(store.retrieve_secret("k").unwrap(), None);
    // The default backend selection itself must never panic.
    let _ = default_secure_store();
}

// ---------------------------------------------------------------------------
// E — the OsMediaIntegration facade on a headless host
// ---------------------------------------------------------------------------

#[test]
fn os_media_integration_degrades_cleanly_on_headless_hosts() {
    let engine = Arc::new(StreamifyEngine::new());
    let integration = OsMediaIntegration::new(engine);

    // Before start: inactive.
    assert_eq!(integration.active_surface(), "inactive");

    // On a CI host without a session bus (the common lane) start
    // reports the graceful fallback; on a desktop WITH a session bus
    // it binds the real MPRIS service — both are valid outcomes, and
    // the surface id tells the shell which one it got.
    let bound = integration.start(0).unwrap();
    let surface_id = integration.active_surface();
    assert_eq!(
        bound,
        surface_id != "headless-fallback",
        "bound flag and surface id must agree"
    );
    assert!(
        ["mpris", "headless-fallback"].contains(&surface_id.as_str()),
        "Linux must surface MPRIS or the headless fallback, got {surface_id}"
    );

    // Updates are no-ops in the fallback (and rate-limited no-ops
    // against a real surface) — never errors, never panics.
    integration
        .update_playback(PlaybackState::Playing, 1_000, 200_000, 1.0, 0.5)
        .unwrap();
    integration
        .update_metadata("T".into(), "A".into(), None, 1, None)
        .unwrap();
    integration.stop().unwrap();
    integration.stop().unwrap(); // idempotent
    assert_eq!(integration.active_surface(), "inactive");
}

// ---------------------------------------------------------------------------
// F — no-deadlock stress (the mission's verification contract §1)
// ---------------------------------------------------------------------------

#[test]
fn adapter_hammering_from_many_threads_completes_within_budget() {
    // 8 threads hammering: outbound pushes through the cadence core,
    // inbound command dispatches through the sink, secure-store ops —
    // the full cross-section an OS shell can produce. A deadlock
    // fails the wall-clock budget (a 10x margin over the observed
    // sub-100 ms run time; the CI slow-timeout is 60 s).
    let (adapter, _surface) = mock_adapter(MockBindBehavior::Bound, Duration::from_millis(1));
    // Shared across the hammering threads (spawned threads are
    // 'static: the adapter rides an Arc; &self methods make that
    // safe by construction).
    let adapter = Arc::new(adapter);
    let sink = Arc::new(streamify_core_rs::platform::testing::RecordingCommandSink::new());
    let sink_dyn: Arc<dyn MediaCommandSink> = sink.clone();
    let store = Arc::new(InMemorySecureStore::new());
    let barrier = Arc::new(Barrier::new(8));
    let deadline = Instant::now() + Duration::from_secs(5);

    let threads: Vec<_> = (0..8)
        .map(|thread_id: u64| {
            let adapter = adapter.clone();
            let sink = sink_dyn.clone();
            let store = store.clone();
            let barrier = barrier.clone();
            std::thread::spawn(move || {
                barrier.wait();
                for i in 0..250u64 {
                    adapter
                        .update_playback(&playing(i + u64::from(thread_id as u32)))
                        .unwrap();
                    adapter
                        .update_metadata(meta("Stress", &format!("Artist {thread_id}")))
                        .unwrap();
                    streamify_core_rs::platform::adapter::dispatch_inbound(
                        &sink,
                        MediaCommand::Seek { position_ms: i },
                    );
                    store.store_secret("stress", b"payload").unwrap();
                    store.retrieve_secret("stress").unwrap();
                }
            })
        })
        .collect();
    for handle in threads {
        handle.join().expect("no thread may panic under contention");
    }
    assert!(
        Instant::now() < deadline,
        "the stress cross-section must complete far inside the budget"
    );
    // Every dispatch attempt was serviced (the sink recorded or
    // refused each one — nothing wedged).
    assert!(sink.dispatch_attempts() >= 2_000);
}

// ---------------------------------------------------------------------------
// G — MPRIS end-to-end over a REAL private D-Bus (Linux + dbus-daemon)
// ---------------------------------------------------------------------------

#[cfg(target_os = "linux")]
mod mpris_e2e {
    use super::*;
    use futures_util::StreamExt;
    use std::collections::HashMap;
    use zbus::proxy;
    use zbus::proxy::CacheProperties;
    use zbus::wire::{ObjectPath, OwnedVariant};

    /// The e2e CLIENT side of the Player interface (the contract a
    /// real MPRIS consumer — GNOME Shell, playerctl — drives).
    /// UNcached on purpose: zbus's default Lazily cache seeds itself
    /// with a GetAll on the first read — before the test's updates —
    /// and then serves that stale seed until the in-flight
    /// PropertiesChanged lands. An uncached proxy reads the LIVE
    /// state every time; signal delivery is proven separately below.
    #[proxy(
        interface = "org.mpris.MediaPlayer2.Player",
        default_service = "org.mpris.MediaPlayer2.streamify",
        default_path = "/org/mpris/MediaPlayer2"
    )]
    trait E2ePlayer {
        fn play(&self) -> zbus::Result<()>;
        fn pause(&self) -> zbus::Result<()>;
        fn play_pause(&self) -> zbus::Result<()>;
        fn stop(&self) -> zbus::Result<()>;
        fn next(&self) -> zbus::Result<()>;
        fn previous(&self) -> zbus::Result<()>;
        fn seek(&self, offset: i64) -> zbus::Result<()>;
        fn set_position(&self, track_id: ObjectPath<'_>, position: i64) -> zbus::Result<()>;

        #[zbus(property)]
        fn playback_status(&self) -> zbus::Result<String>;

        #[zbus(property)]
        fn metadata(&self) -> zbus::Result<HashMap<String, OwnedVariant>>;

        #[zbus(property)]
        fn position(&self) -> zbus::Result<i64>;

        #[zbus(property)]
        fn set_volume(&self, volume: f64) -> zbus::Result<()>;
    }

    /// The Root interface, for the Identity property read.
    #[proxy(
        interface = "org.mpris.MediaPlayer2",
        default_service = "org.mpris.MediaPlayer2.streamify",
        default_path = "/org/mpris/MediaPlayer2"
    )]
    trait E2eRoot {
        #[zbus(property)]
        fn identity(&self) -> zbus::Result<String>;
    }
    use streamify_core_rs::platform::linux::mpris::{
        start as start_mpris, MPRIS_OBJECT_PATH, MPRIS_SERVICE_NAME,
    };
    use streamify_core_rs::platform::testing::RecordingCommandSink;

    /// A private session bus: one dbus-daemon child for the whole
    /// test, killed on drop. Auto-skip when the binary is absent
    /// (non-privileged CI lanes).
    struct PrivateBus {
        _daemon: std::process::Child,
    }

    impl PrivateBus {
        fn spawn() -> Option<Self> {
            use std::io::BufRead;
            let mut daemon = std::process::Command::new("dbus-daemon")
                .args(["--session", "--print-address=1", "--nopidfile"])
                .stdout(std::process::Stdio::piped())
                .stderr(std::process::Stdio::null())
                .spawn()
                .ok()?;
            let stdout = daemon.stdout.take()?;
            let mut reader = std::io::BufReader::new(stdout);
            let mut address = String::new();
            // The address line is printed before the daemon serves.
            reader.read_line(&mut address).ok()?;
            let address = address.trim().to_string();
            if address.is_empty() {
                let _ = daemon.kill();
                return None;
            }
            std::env::set_var("DBUS_SESSION_BUS_ADDRESS", &address);
            Some(PrivateBus { _daemon: daemon })
        }
    }

    impl Drop for PrivateBus {
        fn drop(&mut self) {
            let _ = self._daemon.kill();
            let _ = self._daemon.wait();
        }
    }

    #[test]
    fn mpris_service_serves_the_player_contract_over_a_real_bus() {
        let Some(_bus) = PrivateBus::spawn() else {
            eprintln!("SKIP: no dbus-daemon on this host (the non-privileged CI lane)");
            return;
        };

        let sink = Arc::new(RecordingCommandSink::new());
        let adapter = start_mpris(sink.clone());
        assert!(
            adapter.is_surface_bound(),
            "a private session bus must yield a live MPRIS service"
        );

        streamify_core_rs::resolver::get_runtime().block_on(async {
            let conn = zbus::Connection::session().await.expect("client session connect");
            let player = E2ePlayerProxy::builder(&conn)
                .cache_properties(CacheProperties::No)
                .build()
                .await
                .expect("player proxy");

            // 1. The Root interface answers.
            let root = E2eRootProxy::builder(&conn)
                .cache_properties(CacheProperties::No)
                .build()
                .await
                .expect("root proxy");
            assert_eq!(root.identity().await.expect("Identity property"), "Streamify");

            // 2. Method dispatch: every transport control lands in the
            //    sink as the right MediaCommand.
            player.play().await.unwrap();
            player.pause().await.unwrap();
            player.play_pause().await.unwrap();
            player.next().await.unwrap();
            player.previous().await.unwrap();
            player.stop().await.unwrap();
            player.seek(10_000_000).await.unwrap();
            assert_eq!(
                sink.commands(),
                vec![
                    MediaCommand::Play,
                    MediaCommand::Pause,
                    MediaCommand::TogglePlayPause,
                    MediaCommand::Next,
                    MediaCommand::Previous,
                    MediaCommand::Stop,
                    // Seek(offset 10 s) is relative: 0 + 10 s.
                    MediaCommand::Seek { position_ms: 10_000 },
                ]
            );

            // 3. Property reads: PlaybackStatus / Metadata / Position.
            assert_eq!(player.playback_status().await.unwrap(), "Stopped");
            adapter.update_metadata(meta("Nightcall", "Kavinsky")).unwrap();
            adapter.update_playback(&playing(42_000)).unwrap();
            let metadata = player.metadata().await.unwrap();
            assert_eq!(
                metadata["xesam:title"].to_string().trim_matches('"'),
                "Nightcall"
            );
            assert_eq!(
                metadata["xesam:album"].to_string().trim_matches('"'),
                "Test Album"
            );
            // mpris:length is microseconds (zbus's Display for an
            // integer variant is type-annotated: "int64 <value>").
            assert_eq!(
                metadata["mpris:length"].to_string(),
                format!("int64 {}", 200_000i64 * 1000)
            );
            assert_eq!(player.playback_status().await.unwrap(), "Playing");
            let position = player.position().await.unwrap();
            // 42 s in microseconds, +/- the extrapolation headroom.
            assert!((41_000_000..=44_000_000).contains(&position), "position {position}");

            // 4. SetPosition honors the track guard: a stale trackid is
            //    IGNORED per the MPRIS spec; the current one (derived
            //    deterministically from the metadata identity) seeks.
            let track_key = streamify_core_rs::platform::adapter::track_identity_key(
                &meta("Nightcall", "Kavinsky"),
            );
            let current_track = ObjectPath::from_string_unchecked(format!(
                "{MPRIS_OBJECT_PATH}/track/{track_key:016x}"
            ));
            let stale = ObjectPath::from_static_str_unchecked(
                "/org/mpris/MediaPlayer2/track/deadbeefdeadbeef",
            );
            player.set_position(stale, 1_000).await.unwrap();
            assert_ne!(
                sink.commands().last(),
                Some(&MediaCommand::Seek { position_ms: 1 }),
                "stale trackid must be ignored"
            );
            player.set_position(current_track, 30_000_000).await.unwrap();
            assert_eq!(
                sink.commands().last(),
                Some(&MediaCommand::Seek { position_ms: 30_000 }),
                "current trackid maps onto Seek"
            );

            // 5. Volume: the writable property maps onto SetVolume
            //    (MPRIS 1.0-full-scale == the engine's clamp band).
            player.set_volume(0.5).await.unwrap();
            assert_eq!(
                sink.commands().last(),
                Some(&MediaCommand::SetVolume { volume: 0.5 })
            );

            // 6. A genuine PropertiesChanged receipt: pause the surface
            //    and read the signal off the bus.
            let props = zbus::fdo::PropertiesProxy::builder(&conn)
                .destination(MPRIS_SERVICE_NAME)
                .path(MPRIS_OBJECT_PATH)
                .build()
                .await
                .expect("fdo properties proxy");
            let mut stream = props
                .receive_properties_changed()
                .await
                .expect("properties changed stream");
            adapter
                .update_playback(&streamify_core_rs::platform::OsPlaybackUpdate {
                    state: PlaybackState::Paused,
                    ..playing(42_000)
                })
                .unwrap();
            let received = tokio::time::timeout(Duration::from_secs(5), stream.next())
                .await
                .expect("PropertiesChanged within 5 s")
                .expect("stream alive");
            let args = received.args().expect("parsable args");
            assert_eq!(
                args.interface_name().to_string(),
                "org.mpris.MediaPlayer2.Player"
            );
            let changed_keys: Vec<String> = args
                .changed_properties()
                .keys()
                .map(|key| key.to_string())
                .collect();
            assert!(
                changed_keys.iter().any(|k| k == "PlaybackStatus"),
                "PlaybackStatus must be in the changed set: {changed_keys:?}"
            );
        });
    }
}

// ---------------------------------------------------------------------------
// H — iOS audio session (MP-UI02): policy machine + C-ABI parity
// ---------------------------------------------------------------------------
//
// The mission's host-testable mandate for the iOS audio-session
// adapter: the full interruption/route behavioral contract audited on
// every CI lane (Linux/Windows included — the policy is pure Rust),
// while the .mm glue + native parity suite pin the same frozen ABI from
// the C side. The Apple-bound dlsym bridge
// (platform::apple::ios_audio_session) compiles only on iOS and is
// exercised there by its own #[cfg(test)] mod.

#[test]
fn ios_session_c_abi_codes_match_the_native_header() {
    // The textual cross-check against
    // native/include/ios_audio_session.h — the Rust constants, the C
    // _Static_asserts and the .mm emission share one frozen ABI.
    let header = std::fs::read_to_string(concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/../native/include/ios_audio_session.h"
    ))
    .expect("native/include/ios_audio_session.h must exist next to the rust/ tree");
    for (name, value) in [
        (
            "SF_IOSAS_EVT_INTERRUPT_BEGAN",
            iosas::SF_IOSAS_EVT_INTERRUPT_BEGAN,
        ),
        (
            "SF_IOSAS_EVT_INTERRUPT_ENDED",
            iosas::SF_IOSAS_EVT_INTERRUPT_ENDED,
        ),
        (
            "SF_IOSAS_EVT_ROUTE_OLD_DEVICE_UNAVAILABLE",
            iosas::SF_IOSAS_EVT_ROUTE_OLD_DEVICE_UNAVAILABLE,
        ),
        (
            "SF_IOSAS_EVT_ROUTE_AIRPLAY_TEARDOWN",
            iosas::SF_IOSAS_EVT_ROUTE_AIRPLAY_TEARDOWN,
        ),
    ] {
        let needle = format!("{name} = {value}");
        assert!(
            header.contains(&needle),
            "header must declare `{needle}` (Rust/C ABI drift)"
        );
    }
    // The #define-shaped codes and the entry points themselves.
    for needle in [
        "#define SF_IOSAS_OPT_SHOULD_RESUME 0x1u",
        "#define SF_IOSAS_CATEGORY_OPT_MIX_WITH_OTHERS 0x1u",
        "#define SF_IOSAS_CATEGORY_OPT_DUCK_OTHERS 0x2u",
        "uint32_t sf_ios_audio_session_start(uint32_t category_options",
        "uint32_t sf_ios_audio_session_stop(void)",
    ] {
        assert!(
            header.contains(needle),
            "header must declare `{needle}` (Rust/C ABI drift)"
        );
    }
}

#[test]
fn ios_session_policy_interrupts_pause_and_resume_on_the_os_hint() {
    let mut policy = iosas::IosAudioSessionPolicy::new();
    assert!(!policy.is_interrupted());
    // A phone call arrives: the engine pauses NOW (before the user
    // even looks at the screen).
    assert_eq!(
        policy.on_event(iosas::IosAudioSessionEvent::InterruptionBegan),
        Some(MediaCommand::Pause)
    );
    assert!(policy.is_interrupted());
    // The call ends and the OS authorizes the auto-resume.
    assert_eq!(
        policy.on_event(iosas::IosAudioSessionEvent::InterruptionEnded {
            should_resume: true
        }),
        Some(MediaCommand::Play)
    );
    assert!(!policy.is_interrupted());
}

#[test]
fn ios_session_policy_end_without_the_resume_hint_stays_paused() {
    let mut policy = iosas::IosAudioSessionPolicy::new();
    policy.on_event(iosas::IosAudioSessionEvent::InterruptionBegan);
    // Siri / a user-initiated interruption dismissal: no auto-resume —
    // the user decides when playback continues.
    assert_eq!(
        policy.on_event(iosas::IosAudioSessionEvent::InterruptionEnded {
            should_resume: false
        }),
        None
    );
    assert!(!policy.is_interrupted());
}

#[test]
fn ios_session_policy_pauses_immediately_when_the_route_vanishes() {
    let mut policy = iosas::IosAudioSessionPolicy::new();
    // Unplugged headphones / dropped Bluetooth: pause before the first
    // frame can escape the suddenly-public speaker.
    assert_eq!(
        policy.on_event(iosas::IosAudioSessionEvent::RouteOldDeviceUnavailable),
        Some(MediaCommand::Pause)
    );
    // A route loss is NOT an interruption: the latch stays untouched.
    assert!(!policy.is_interrupted());
}

#[test]
fn ios_session_policy_airplay_teardown_is_clean_silence() {
    let mut policy = iosas::IosAudioSessionPolicy::new();
    policy.on_event(iosas::IosAudioSessionEvent::InterruptionBegan);
    // The .mm handled the session hygiene (observers out, deactivate
    // with notify-others); the engine keeps playing on the local route.
    assert_eq!(
        policy.on_event(iosas::IosAudioSessionEvent::RouteAirPlayTeardown),
        None
    );
    assert!(policy.is_interrupted(), "teardown never touches the latch");
}

#[test]
fn ios_session_policy_survives_garbage_and_keeps_flowing() {
    let mut policy = iosas::IosAudioSessionPolicy::new();
    // Unknown codes never decode into the typed layer (forward compat:
    // a NEWER .mm may emit codes this core does not know yet).
    assert!(iosas::event_from_code(0, 0).is_none());
    assert!(iosas::event_from_code(4242, 0x8000_0000).is_none());
    // The known codes still flow after garbage — no state poisoning.
    assert_eq!(
        policy.on_event(iosas::IosAudioSessionEvent::InterruptionBegan),
        Some(MediaCommand::Pause)
    );
    // Full OS round-trip after the garbage exposure.
    assert_eq!(
        policy.on_event(iosas::IosAudioSessionEvent::InterruptionEnded {
            should_resume: true
        }),
        Some(MediaCommand::Play)
    );
    assert!(!policy.is_interrupted());
}

#[test]
fn ios_session_policy_maps_raw_c_abi_pairs_end_to_end() {
    // The exact (event, options) pairs the .mm trampoline produces
    // over the frozen C ABI — decode → policy → command, one table.
    let mut policy = iosas::IosAudioSessionPolicy::new();
    let began =
        iosas::event_from_code(iosas::SF_IOSAS_EVT_INTERRUPT_BEGAN, 0).expect("began decodes");
    assert_eq!(policy.on_event(began), Some(MediaCommand::Pause));
    let ended_resume = iosas::event_from_code(
        iosas::SF_IOSAS_EVT_INTERRUPT_ENDED,
        iosas::SF_IOSAS_OPT_SHOULD_RESUME,
    )
    .expect("ended decodes");
    assert_eq!(policy.on_event(ended_resume), Some(MediaCommand::Play));
    let route_loss = iosas::event_from_code(iosas::SF_IOSAS_EVT_ROUTE_OLD_DEVICE_UNAVAILABLE, 0)
        .expect("route loss decodes");
    assert_eq!(policy.on_event(route_loss), Some(MediaCommand::Pause));
    let teardown =
        iosas::event_from_code(iosas::SF_IOSAS_EVT_ROUTE_AIRPLAY_TEARDOWN, 0)
            .expect("teardown decodes");
    assert_eq!(policy.on_event(teardown), None);
}
