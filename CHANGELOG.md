# Changelog

All notable changes to Streamify are documented in this file.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased] — Horizon 0: security, CI-truth & governance closeout (PRs #145–#146)

### Security
- **SEC-06** — the offline vault and the audio-cache chunk cipher use real
  AEAD now: ChaCha20-Poly1305 with fresh random nonces, BLAKE3-derived
  keys and chunk-id AAD, replacing the repeating-key XOR "encryption".
  Legacy XOR chunks stay readable via a read-only migration path
  (read-old/write-new). Tamper and known-plaintext tests included;
  `docs/playbooks/CRYPTO_SPEC.md` documents the envelope.
- **SEC-07** — the hardcoded InnerTube key was purged from
  `streamify-resolver` into the env-overridable fleet-config path;
  the gitleaks allowlist was reconciled with what actually ships.
- **SEC-08** — the five pooled ZhipuAI API keys were removed from
  Kotlin; keys arrive via env/BuildConfig injection and an
  unconfigured build ships with AI features off (matching the C++
  bridge's env-var pattern).
- **SEC-09** — the display-name admin bypass (`displayName.contains(
  "sireen")`) was killed at all sites; `is_admin` is a server-side
  flag-only claim, fail-closed, with a regression test.
- **SEC-10** — Supabase RLS hardened: rate-limited track ingest, every
  `OR TRUE` policy dropped, `is_admin()` flag-only behind a trigger
  guard. `supabase/sql/tests/rls_test.sql` runs 11 assertions against a
  real Postgres+pgvector container in a new blocking CI job.

### Changed — CI truthfulness (TRUTH-01) & real-lane linking (IOS-02)
- The Apple-target engine check is now **blocking** on every non-fork
  event: a failing `aarch64-apple-ios` / `-sim` compile fails the job
  instead of printing "BLOCKED UPSTREAM (reported, not failed)".
- The Apple Unified lane builds `StreamifyCore.xcframework`, stages it
  into `iosApp/Frameworks/` and **links it before the test pass** — the
  28 real-lane UniFFI contract tests executed for the first time
  (SWIFT_MIGRATION_PLAN Gate 2). This took eleven rounds of CI fixes,
  each a latent Swift bug that had never compiled against a real SDK
  (bindgen SDK targets, the FFI module name `streamifyFFI`, type-name
  collisions, throwing FFI signatures, async test annotations, and the
  lib-prefix rule for xcframework static members that `swift build`
  requires to emit a linker flag).
- Release no longer tolerates a missing xcframework: the
  `continue-on-error` download was removed — every promised release
  artifact exists or the release fails.
- The three never-bootable macOS emulator legs in the nightly matrix
  (HV_UNSUPPORTED) were replaced by one KVM Android emulator lane
  (TEST-01 infrastructure). The Windows desktop probe-gate was deleted
  (the engine compiles and is consumed like Linux — closes RFX-02).
- The Apple lane followed GitHub's macos-14 runner retirement
  (2026-11-02) and migrated to macos-15.

### Governance (GOV-05)
- The resolver canary no longer force-pushes to `main` — rotations open
  reviewed pull requests from the evergreen `bot/fleet-rotation`
  branch (branch protection had been silently rejecting four
  consecutive canary pushes since 2026-10-31).
- `CODEOWNERS` review routing installed; the release signing secrets
  moved behind a protected `release` environment.

## [Unreleased] — Multiplatform program: contracts → workspace → desktop → Apple (PRs #110–#138)

### Added
- **Phase 3 contracts & engine hardening (PRs #110–#111)** — frozen
  multiplatform UniFFI contract surface (`DacPresentationClock`,
  reactive `EngineEventListener` with monotonic snapshot dispatch,
  debounced `MediaCommand` plane), SPSC ring hardening, Rubato 48 kHz
  sinc SRC with the golden-vector suite.
- **Rust core modularized into a 7-crate workspace (PR #126)** —
  `streamify-core / -audio / -jam / -resolver / -storage / -contract /
  -ffi`, replacing the monolith; pure-Rust RustyPipe stream extraction
  integrated end-to-end (JNI resolver lane, SQLite deobf cache,
  3-tier resolution with ISRC + fuzzy fallback).
- **Phase 4a/4b audio HALs (PRs #114–#121)** — Windows WASAPI
  IAudioClient3→shared→exclusive ladder, Linux PipeWire RT callback
  with dlopen'd ALSA fallback (frozen embedded ABI), Android AAudio
  float pull adapter, host-testable render cores with dedicated suites,
  desktop CI matrix + the <250 MiB installer size-budget gate.
- **Phase 5 OS capability adapters (PR #122)** — SMTC (Windows), MPRIS
  (Linux), Apple RemoteCommandCenter, and the cross-platform secure
  store delegation (DPAPI / Secret Service / Keychain / KeyStore).
- **Compose Multiplatform desktop shell (PR #123)** — responsive
  sidebar shell, checked-in UniFFI Kotlin proxies, JNA engine smoke
  suite, jpackage .deb/.msi/.dmg installers (DMG CI-verified, PR #130).
- **Apple Swift migration — Engineers 1–4 (PRs #133–#138)** — 180
  Swift files / 37 kLOC: the universal SPM package (iOS 17+ / macOS 14+),
  UniFFI typed-contract gateway + storage, SwiftUI design system and
  30-screen multiplatform shell, CoreAudio render core + audio-session
  and remote-command adapters, BLE/LAN jam discovery, state models and
  lyrics. 235 unit tests.
- **Unified Hub-and-Spoke CI DAG (PRs #125, #127, #129)** — 3-tier
  reusable workflows, single blocking `ci-gate`, path-filtered
  preflight, blocking gitleaks, build-once native fan-out; CI
  turnaround collapsed under 5 minutes.

### Changed
- Android track search and stream resolution route through the pure-Rust
  RustyPipe JNI lane; NewPipe and WebView surfaces purged (PRs #131–
  #132).
- `USE_RUST_AUDIO` dogfood toggle landed (default off — the cutover
  MP-CUT01/02 remains the open Horizon-1 work).

### Deprecated
- The monolithic `extreme-test-matrix.yml` workflow was retired in favor
  of the hub-and-spoke DAG; nightly deep lanes (fuzzing, mesh chaos) and
  the resolver canary live on as their own workflows.

## [Unreleased] — audit #44 P0-1: JNI ingress bridge + ABI parity gates

### Fixed
- `nativeP2pRegisterIncomingCallback` had **no native export** — the
  declared JNI symbol simply did not exist, so every call degraded to a
  swallowed `UnsatisfiedLinkError` and `NativeBridge.incomingFrames`
  NEVER received a frame from the real mesh: the entire Jam v4 ingress
  path was silent on devices (JVM tests never noticed — they exercise the
  `meshOverride` mock). Implemented in `rust/src/jni_bridge_p2p.rs`:
  a global-ref listener + JavaVM bridge, a frame forwarder subscribed to
  the new `MSG_JAM_APP` envelope, and a peer join/leave watcher, all
  stop-signal-driven and panic-contained per house rules.
- JamWire↔mesh **wire-type collision** (found while fixing the above):
  the Kotlin Jam vocabulary (Msg 1..=31) collides with the mesh's frozen
  control registry (OP=3 is MSG_CHUNK_DATA, CONTINUATION=4 is
  MSG_HEARTBEAT, TICK=1 masquerades as PTP_SYNC), so the JNI guard
  rejected most Jam egress as protocol spoofing. Native egress now folds
  the JamWire family into the app-visible envelope `MSG_JAM_APP` (0x20);
  the payload self-describes its sub-type. The mock/test seam is
  unchanged (raw types both directions).

### Added
- `scripts/jni_parity_check.py` — Kotlin `external fun` ↔ native JNI
  export parity tripwire, in two modes: `--source` (fast, fail-first in
  CI before the native build) and `--nm` (authoritative, on the built
  `.so` artifacts). Wired into `extreme-test-matrix.yml` (both modes) and
  `build-ref.yml` (`--nm`).
- `MeshNode::stop_signal()` — public subscription to the node's shutdown
  watch, required by the bridge tasks (and any future strong-`Arc`
  consumer) to tear down with the node.
- Rust integration test `test_jam_app_envelope.rs` (envelope legality,
  end-to-end routing, stop-driven teardown) and Kotlin
  `NativeBridgeWireTypeTest` (mapping + mock-seam raw types).

## [Unreleased] — Modular Architecture & Documentation Overhaul

Zero logic changes: a structural + documentation pass that carves the
monolith into layered, domain-scoped packages and documents every folder.

### Restructured (behavior-preserving package moves)
- `data/` root grab-bag (21 files) split into `repository/`, `persistence/`,
  `discovery/`, `ingestion/`, `telemetry/`, `lyrics/`, `native/`.
- `data/remote/` (16 files) split into `data/supabase/` (11), `data/spotify/`
  (3), `data/youtube/` (3), `data/update/`; `JamTrackCodec` moved to `jam/`.
- `service/` (25 files) reorganized into `media/` domains: `playback/`,
  `audio/`, `cache/`, `sync/`, `ingestion/`, `lyrics/`.
- 34 `Yt*` widgets grouped under `ui/components/yt/`.
- Radio strays (`ContinuumRadioEngine`, `UniversalCandidateBroker`) folded
  into `radio/`. `FuzzyTitleMatcherTest` moved to `data.discovery`.

### Kept stable (hard contracts honored)
- `data/NativeBridge` package unchanged (name-mangled JNI ABI).
- `data/models/`, `ui/models/` unchanged (ProGuard keep rules).
- No logic, behavior, resource, or manifest-permission changes; playback,
  sync, jam and UI behavior are byte-identical.

### CI
- JVM shard renamed `data-identity-fuzzy` -> `jvm-unit-suite` and now runs
  the **full** unit-test suite (no package filter), so reorganizations
  can't silently de-scope tests and new tests run automatically.

### Documentation
- 45 new folder-level READMEs (every Kotlin package + every repo directory).
- New guides: `docs/ARCHITECTURE.md`, `docs/CODEBASE_GUIDE.md`,
  `docs/ADD_A_FEATURE.md` (feature playbooks), refreshed
  `docs/CI-TESTING-GUIDE.md`.
- Root declutter: `goalarchitct.md` -> `docs/architecture/GOALS.md`,
  `MASTER-README.md` -> `docs/MASTER-STREAM-RESOLUTION.md`,
  `JAM-ENGINE.md` -> `docs/JAM-ENGINE.md`, historical logs -> `docs/history/`.

## [1.1.0] — Professional Polish & Performance Refactor

This release is a full-codebase quality pass: jank elimination, error-handling
discipline, dead-code removal and architectural decomposition. No user-facing
feature removals; the UI look-and-feel is preserved by design.

### Performance (UI)

- Every Compose lazy list now declares stable `key`s (21 previously keyless
  sites) — ending positional-reuse recomposition storms and enabling item
  animation and scroll restoration.
- Fixed a latent **duplicate-key crash**: Up Next / Play History can contain
  the same track twice; their keys are now index-qualified in QueueScreen and
  FullPlayerSheet.
- All always-on animations are now gated:
  - The branding badge shimmer is a one-shot sweep instead of a permanent
    frame loop on three primary screens.
  - Equalizer "now playing" indicators render a static silhouette while
    playback is paused (previously they animated forever due to a
    "current-track vs actually-playing" semantics bug), and disappear-free
    semantics on Queue/FullPlayer.
  - The pull-to-refresh spinner only animates while a refresh is actually in
    flight; the pull gesture itself is arc-progress only.
  - The Wrapped persona ring pulses a bounded number of times, then rests.
- The comments sheet no longer recomposes the entire player sheet five times
  per second: the 5 Hz position ticker is now collected inside the sheet's
  own recomposition scope.
- Album-art decode sizes are deterministic at small-cell call sites
  (TrackCard 150dp, TrackListItem 48dp) instead of relying on layout timing.
- The Jam session queue renders a bounded 8 rows collapsed with a
  "Show all N tracks" toggle instead of composing an unbounded distributed
  queue.
- Track-tap disk work (offline-vault probe, local-file existence check)
  moved off the main thread; playlist JSON parsing during pull-to-refresh
  moved to `Dispatchers.IO`.

### Robustness

- All 96 bare `printStackTrace()` sites across 36 files now route through
  the SLog facade (`SLog.st` with class + operation context) — failures are
  visible to the opt-in diagnostic spool, crash forensics and redaction
  pipeline.
- 33 of 37 unsafe `!!` assertions eliminated, including two genuinely
  dangerous ones: an unchecked OkHttp response body on the radio gzip path
  and a TOCTOU media-item dereference across a thread hop in the CDN
  renewal path. The remaining 4 are documented as guarded invariants.
- `CancellationException` is rethrown instead of swallowed in online-track
  resolution, restoring structured-cancellation correctness.
- The last raw `android.util.Log` call moved to SLog.

### Concurrency & Lifecycle

- SearchViewModel's speculative-prefetch scope is cancelled in `onCleared`
  (previously leaked SupervisorJob + IO threads per nav entry).
- PlaybackService CDN-token recovery runs on a service-lifecycle scope
  instead of a fresh unmanaged `CoroutineScope(...)` per player error.
- Explicit `kotlinx.coroutines.cancel` import added for the existing
  jam-scope cancellation call.

### Architecture

- **di/AppGraph** — single wiring point for the service-layer graph,
  owning the process-wide applicationScope, the init-order contract
  (SLog → fleet config → repositories → native async engines → services →
  authenticated resolution) and repository access. Application.onCreate is
  now app-level concerns only.
- **di/DispatcherProvider** — injectable dispatcher abstraction for
  deterministic testing.
- **TrackRepositoryApi** — interface over the track catalog; the four
  repository-consuming ViewModels depend on the abstraction while keeping
  their all-default constructors (reflective `viewModel()` wiring
  untouched).
- Single-writer rule for `TrackRepository.appContext` (owned by
  AppGraph.initialize).
- **SupabaseClient split**: the 2641-line god object is now a 518-line
  facade (session state, auth, HTTP/RPC plumbing, delegation stubs) over
  eight domain clients — Realtime, Stats, Tracks, Community, Jam, Admin,
  EdgeMesh, PlaylistSync — plus extracted wire models and the jam-track
  codec. Zero external call-site changes; 11 dead members deleted.
- **PlayerViewModel split**: 1816 lines of mixed concerns → ~1150 lines of
  cohesive playback control plus PlayerModels, PlayerTrackMedia (pure
  helpers), PlayerTrackLoader (loading pipeline), PlayerTicker (position
  and jam tickers) and PlayerHousekeeping (controller wiring, restore,
  lyric fetching) via same-package extensions. Public API unchanged.
- **FullPlayerSheet split**: the three self-contained landscape panes
  moved to FullPlayerSheetPanes.kt.

### Removed (dead code)

- `PlayerSeekBar` (unused legacy seekbar with the per-tick recomposition
  anti-pattern), `RecentPlayCard` and `PaletteExtractor` (zero call sites),
  `LatencyProbe` (never bound to the audio path; its estimate was
  permanently 0), 11 dead SupabaseClient members, 3 write-only
  PlayerViewModel state properties.

### Build

- versionName 1.1.0; lint safety net (`abortOnError = false`) so historical
  warnings inform instead of blocking release builds; official Kotlin code
  style; boot banner now reads `BuildConfig.VERSION_NAME` (was a hard-coded
  "1.0." prefix).

### Known issues (deliberately not actioned this release)

- Supabase credentials remain committed in `app/build.gradle.kts` per
  maintainer decision; rotation is strongly recommended before any public
  distribution.
- Release builds fall back to debug signing when no CI keystore is present.
- 128 silent/empty catch blocks remain (mostly intentional haptic/native
  cleanup fallbacks); the printStackTrace routing in this release covers
  the failure-visibility gap they represented.
- `runBlocking` in the NewPipe PO-token provider is retained: it mirrors
  upstream NewPipe's synchronous provider contract and blocks an IO-pool
  thread, not the main thread.
