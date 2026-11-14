# Changelog

All notable changes to Streamify are documented in this file.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased] — WS-W G4.1: the deploy fix + the direct-born visitor (iterate-on-PR #167)

The phone kept reporting `no rung resolved … LOGIN_REQUIRED` on every rung for a full day after the G4 push — **because the G4 code never reached the phone**: `web-deploy.yml`'s branch list did not include `ws-w/g4-residential-direct`, so every push shipped CI-green but never deployed to Pages (the live bundle was still G3b — its rung labels `[VISIONOS]/[WEB+POT]/[WEB_SAFARI]`, with no `*` direct markers, are the forensic proof).

### Fixed
- **`web-deploy.yml` branch list** — `[main, 'ws-w/g4-residential-direct']` (the historical merged `ws-w/*` branches were pruned so a stray push can never redeploy stale code). Every push to the PR branch now deploys to Pages; merging deploys from `main`.
- **Direct-lane rung latency** — direct rungs now run 2 attempts (down from 3): the OK/LR flap was measured on the RELAY's shared-IP wall, not on direct verdicts; a licensed refusal from the phone's own IP is stable, and the wasted third attempt was pure pre-unlock silence.

### Added
- **The direct-born visitorData (`lib/ytSession.ts`)** — the G4 doc's deferred lever, pulled: the session's visitorData used to be born on the relay egress (youtubei.js mints it from `sw.js_data`, a www leg), making every "phone-egress" player call carry a datacenter-born visitor identity. `directBornVisitorData()` POSTs `/youtubei/v1/config` on the googleapis surface with NO visitor id — the `responseContext.visitorData` is a visitor BORN on the calling egress (live-verified: the endpoint mints one even from the relay's burned Cloudflare IP). It is persisted (30 d, direct births only) and fed into `Innertube.create({ visitor_data })` — youtubei.js honors the seed in both its fresh and cached session paths, so visitor, mint and player calls finally share ONE phone-born identity.
- **Honest lane attribution** — a `*-direct` rung whose fetch network-failed and relay-fallbacked now labels its verdict `@relay-egress`, and the terminal `no rung resolved` error carries `visitor=direct|relay|session`. A phone-side LOGIN_REQUIRED is now correctly attributable to either the direct lane being refused or the relay fallback answering.
- **The definitive-verdict stickiness** — a rung that got a real upstream refusal followed by a trailing network error is still ledgered (a trailing network failure can no longer launder a verdict into an un-ledgered rotation loop).
- **The G4 direct-lane canary (`resolver-canary.yml`)** — nightly, from a clean runner IP: the CORS preflight posture of `youtubei.googleapis.com` for foreign origins (the load-bearing unknown — the lab IP is blocked at that host), plus IOS/VISIONOS playability for `dQw4w9WgXcQ` and `DOGP2dYUGE0` (the phone-reported failure) on the googleapis surface, with the ACAO response header recorded per call. Non-gating (environmental, not registry rot) — detection and evidence.
- **`DOGP2dYUGE0` joins the g3b-test matrix** (the licensed Arpit Bala track the phone failed on) and the session row reports the visitor's birth provenance.

## [Unreleased] — WS-W G4: the residential-direct pivot (licensed playback from the phone's own IP)

### Added
- **`audio/sabr/sabrStreamPlayer.ts`** — the SABR playback lane: googlevideo's `SabrStream` (audio-only, AAC-preferred) piped into a MediaSource SourceBuffer; URL-less SABR descriptors (the modern IOS-client reality) now play on the element path with an MSE object URL. Egress: the RELAY — probe-measured, googlevideo's CORS is youtube.com-only, so foreign pages cannot POST to the SABR endpoint directly (kira routes googlevideo through its own bridge for the same reason); the IP-pin is tolerated on SABR POSTs (curl-verified from a mismatched IP).
- **The direct rungs** (`visionos-direct`, `ios-direct`, `ios-pot-direct`) — raw player POSTs to `youtubei.googleapis.com` (the CORS-open innertube surface youtubei.js itself defaults to), DIRECT from the phone's residential IP. Probe-verified: IOS returns playability OK for the licensed track that failed every G3b rung (`tixIb21MO5U`), even from the burned Cloudflare relay egress.
- **The flap-retry policy** — the relay egress's licensed-content verdicts are PROBABILISTIC (six byte-identical IOS POSTs: OK/LR/OK/LR/OK/OK), so every raw rung runs up to 3 attempts before ledgering; on the last local matrix this caught both licensed videos on `ios-direct` where the G3b ladder refused them everywhere.
- **`lib/ytSession.ts` dual-lane funnel** — youtubei.js `/youtubei/*` API calls are host-rewritten onto `youtubei.googleapis.com` and fetched DIRECT (network-failure → per-request relay fallback; HTTP verdicts never re-laned). The mint's WAA legs (jnn-pa, CORS-open) follow automatically — PO tokens now bind to the phone's IP, coherent with the player calls that use them.
- **The stale-search fix** (`lib/searchStore.ts`)** — a request-epoch guard: a superseded query's response can never land its results (the "searched blinding lights, a Bohemia track played" anomaly class).
- **`decisions/G4-residential-direct.md`** — the full diagnosis (13-client × 2-video live matrix: the relay egress is selectively burned for licensed music on every client), the OSS research (yt-dlp client policies, kira's SABR architecture, googlevideo/BgUtils), and the probe evidence for every rung.

### Changed
- **The resolver ladder is now direct-first** (`visionos-direct → ios-direct → ios-pot-direct → web-pot-direct → visionos-relay → web-safari-hls`): the licensed-music unlock is `IOS + player PO token` on the direct surface (yt-dlp's `not_required_with_player_token` waiver); the www-surface relay rungs remain as the standard-content lifeline and direct-hostile-network fallback. The relay itself is UNCHANGED and was NOT redeployed.
- **`searchMusic` rides the YouTube.js session** (`yt.search`, direct googleapis via the funnel rewrite) — one session envelope for search + playback; the Rust engine's relay-egressed `search_music` becomes the fallback lane.
- **`g3b-test.html` is the G4 matrix** — direct-lane CORS probe, three canonical resolves (standard / the phone-failure case / the G3b target), the instrumented mint, and the SABR + HLS lane element probes.

## [Unreleased] — WS-B "Apple Integration": from showroom to first note (B1–B7)

### Added
- **`UniFFIFeatureLibrary` (B1)** — the real feature lane over the shared `UniFFIEngineGateway`: live search, a search-composed home feed, snapshot-driven queue-event synthesis, the ten-band EQ mapping and host-side jam sessions. Honest-empty defaults for every surface the Rust contract does not expose yet (community, stats, updates, profiles, ingestion, lyrics, blend). `AppServices` now owns ONE engine per process and `FeatureLibraryLoader` performs the lane switch (CI-proven by new `UniFFIContractTests`).
- **The drivetrain v2 mapping (B3)** — `resolveStreamFormats` (+ session memo + the persistent resolver cache via `set_resolver_cache_db`), the full play path (`play(track:)`: resolve → stage → attach → `play_track`), `playStagedTrack` for queue-row taps, `attachSink`/`detachSink`/`bufferProgress`/`recordDacClock`, and staged-track snapshot enrichment (pushed snapshots now carry now-playing metadata).
- **The CoreAudio data plane (B4)** — `PlaybackViewModel` mounts `CoreAudioRenderCore` and the 10 Hz `DacClockPublisher` drain (hardware → `record_dac_clock`, never the reverse); the native bracket: an abstract `RenderFrameSource` pull edge in the RT core, `streamify_coreaudio_start_rust_ring()` in the pull adapter, and the Rust ring's frozen C ABI (`streamify-playback/src/ring_export.rs`, installed by `attach_sink`). No AVPlayer anywhere on the path.
- **The checked-in host app target (B5)** — `iosApp/Host/`: committed `StreamifyHost.xcodeproj` (app + widget extension, synchronized folders), CarPlay scene manifest, `NSSupportsLiveActivities`, `streamify://` deep links, background audio, App Group; `project.yml` as the XcodeGen source of truth; CI "Host app build gate" builds it from a fresh clone.
- **Platform surface activation (B6)** — the CarPlay scene delegate is public + manifest-instantiated with its router assigned by the shell; the Live Activity controller is driven on the admitted push cadence with a cold-launch `endAllStale()` sweep; `StreamifyWidgetBundle` is public for the extension target.
- **Downloads (B7)** — `DownloadViewModel` (manifest-indexed correlation, progress aggregation, resume-data verification with a remove-race sweep, real storage numbers) + the model-driven `DownloadsView` with offline play through the same engine path (`file://` resolved URL).
- **`scripts/preview_catalog_guard.py` (B2)** — the production-path fixture lint (mirrors `docs_ledger_check.py`): zero `PreviewCatalog` references outside previews/tests/comments, enforced in CI preflight.
- **`coreaudio_render_core_test_suite` in CI (C3)** — the Apple render core's host-lane RT audit joined the sanitizer matrix.
- **Gate 4 runbook** — `docs/playbooks/gate4_fluidity_runbook.md`: the Instruments procedure + evidence format (the gate itself remains honestly unexecuted).

### Changed
- **All 14 view models ride live lanes (B2)** — the 9 orphaned VMs (Home, Search, Queue, Community, StatsWrapped, UpdatesHub, UserProfile, Ingestion, Settings) are constructed by their screens; `RootShellView` takes queue truth from the engine (`QueueViewModel` + a derived projection), drives search through the 300 ms-debounced `SearchViewModel`, and routes every play action into the engine. Album/Artist detail pages source their rows from live search; the player/lyrics/queue panes lost their fixture defaults.
- **`UniFFIEngineGateway` push fan-out** — the snapshot stream supports multiple subscribers (transport ingest + the feature library's queue bridge) with late-subscriber hydration; fixes a latent single-consumer competition the second subscriber would have exposed.
- **`HttpRangeFetcher` local-file lane** — `file://` (and bare absolute-path) resolved URLs read ranges straight off disk: the airplane-mode offline path (B7).
- **`iosApp/README.md`** — the manual 5-minute host-husk recipe is retired in favor of the checked-in project.

### Verified on the host lane (this PR's CI + local runs)
- `cargo test -p streamify-contract --test test_live_audio_selftest` — 3/3 (engine says 440 through the producer + exported ring, resample leg, 100-intent skip-under-load with the ring export swapping on every attach/detach).
- `cargo test -p streamify-playback` (21) and `--test test_uniffi_contract` (21) green; `cargo check --workspace` clean.
- `coreaudio_render_core_test_suite` — 41/41 under the new `RenderFrameSource` abstraction (RT zero-alloc audit intact).
- `preview_catalog_guard.py` — clean (negative-tested).
- Pending hardware gates (recorded on the PR, not claimed): audible playback on device, interruption/route-change against the live path, CarPlay/Live Activity presentation, airplane-mode offline play, Gate 4 frame pacing.
## [Unreleased] — WS-A Drivetrain Wave 2: the audio data plane (A3/A4/A5, issue #151)

### Added
- **`streamify-playback` crate (A3)** — the producer-side audio data plane: the dedicated producer thread
  (segmented range fetch → decode → `CanonicalSrc` 48 kHz resample → BS.1770-4 LUFS chain → SPSC sink write edge),
  the Rust-side mirror of the frozen C++ sink ABI (`AudioSink` trait, cacheline-aligned `SpscAudioRing` with the
  cooperative `RequestFlush` generation handshake, the 96-byte `SinkStats` wire image), the codec-agnostic
  `TrackDecoder` trait + symphonia 0.6 backend with an incremental-feed checkpoint protocol, and the `RangeFetcher`
  abstraction (production HTTP lane: `StreamDownloader::fetch_stream_range`; hermetic lane: `LossyFixtureFetcher`
  with scripted loss/jitter). Zero allocation on the write edge (counting-allocator proof); skips handled
  cooperatively at stage boundaries; 100-rapid-skip stress green.
- **UniFFI surface (A4)** — `attach_sink`/`detach_sink`/`play_track`/`buffer_progress` + the `BufferProgress`
  dictionary; `CONTRACT_VERSION` bumped additively to 2; Kotlin bindings regenerated drift-free.
- **Live-audio self-test harness (A5)** — `test_live_audio_selftest.rs`: the "engine says 440" gate (FFT-verified
  dominant bin, zero underruns on both sink and VirtualHAL accounting, DAC-clock position truth), the 44.1 kHz
  resample leg, and the 100-skip-under-load stress. Reusable as the desktop smoke test (C5's AC).
- Golden-vector fixtures: `fixtures/audio/playback_selftest_440hz_{48k,44k1}.wav` +
  `scripts/generate_playback_fixtures.py` (deterministic 440 Hz stereo f32 LE).

### Changed
- `apply_media_command` lifecycle arms now drive the producer (Play→resume, Pause→park, Stop→stop-and-flush) and
  the transport arms emit play intents post-lock; `clear_queue` drops the producer session. Transport commands
  still never touch the lifecycle (A2 semantics preserved).

### Dependencies
- `symphonia 0.6` (aac/isomp4/ogg/pcm/wav features, MPL-2.0, pure Rust, decode-only). **Audit finding**: symphonia
  ships no Opus decoder in any release (upstream #8) — the RFC §5.2 fallback applies; the `TrackDecoder` trait
  keeps the Opus decision reversible. Full rationale in the issue #151 PR description.

## [Unreleased] — WS-A Drivetrain opens: playback data-plane RFC, transport commands, panic-policy reconciliation (#148)

### Added — the sovereign playback transport (AHEAD.md WS-A A2)
- `MediaCommand::Seek` / `Next` / `Previous` now drive a queue-scoped
  transport in `apply_media_command` instead of being acknowledged
  no-ops: the staged typed queue gains a transport cursor
  (`QueueLane`: rows + cursor behind one lock, so a cursor move and the
  row page it indexes are always one consistent generation), `Seek`
  re-anchors the position target (clamped to the current row's duration
  when known; unknown durations clamp nothing), `Next`/`Previous` move
  the cursor with wrap-around — mirroring the legacy `get_next_track`
  circular loopback — and reset the position; `clear_queue` resets the
  whole transport. Transport commands never touch the lifecycle state
  (a `Next` while paused stays paused) and every applied command bumps
  the snapshot revision per the MP-C03 acknowledgment contract.
- Rust-side typed getters `get_current_queue_item()` /
  `get_playback_position_ms()` pin the transport for the integration
  tests; UDL exposure lands with the A4 surface so the frozen bindings
  stay drift-free in this PR.
- Four contract tests in
  `rust/crates/streamify-contract/tests/test_uniffi_contract.rs`:
  walk/wrap/reset, seek round-trip + duration clamp + debounce,
  no-current-row no-ops, revision bumps + `clear_queue` reset.

### Added — panic-policy contract (AHEAD.md WS-A A6)
- Decision record (RFC §8): **keep `panic = "abort"` for shipped
  release artifacts** (the MP-C06/MP-C07 consensus), with the
  per-profile `ffi_shield` semantics table, the reversal triggers and
  the documented non-host-target fallback.
- `src/bin/panic_probe.rs`: the cfg-split executable probe — the
  unwind leg proves the shield marshals a deliberate panic into
  `EngineError::Internal` (exit 0, `SHIELD_MARSHALED_PANIC`); the abort
  leg terminates at the panic site (SIGABRT — verified locally, exit
  134).
- `tests/test_panic_policy.rs`: the marshaling contract for unwind
  configurations (runs the probe) plus the release-profile abort pin —
  the shipped crash semantics cannot silently drift.

### Documentation
- **New engine manual** `docs/architecture/engines/13_playback_dataplane.md`
  (AHEAD.md WS-A A1): the playback data-plane RFC — four-plane
  architecture (control / transport / producer / sink edge + HAL), the
  producer spec including the decode dependency decision, the Rust-side
  `AudioSink` trait mirroring the frozen
  `native/include/streamify_audio_sinks.h` ABI, the A4 UDL delta plan
  and the A5 "engine says 440" self-test harness spec.
- **AHEAD.md** (the lead maintainer's engineering directive) checked
  into the repository; WS-A A1/A2/A6 marked done with evidence, and the
  §1/§9 claims this PR invalidates updated in the same commit (the
  truth program's own rule).

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
