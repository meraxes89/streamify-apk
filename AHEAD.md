# AHEAD.md — The Lead Maintainer's Engineering Directive

> **BEHIND.md** enumerated the 27 product gaps that put us behind Spotify. **AHEAD.md** enumerates the engineering work that lies ahead of us — sequenced, assigned, and pointed at a direction no incumbent can follow us into.
>
> **Audience:** the engineering team (ENG-1 … ENG-7) and reviewers.
> **Status:** ACTIVE — supersedes the sequencing (not the content) of `ROADMAP_PHASES.md` Phases 5+.
> **In-repo:** checked into the repository with the first WS-A delivery (#148). Task rows carry a ✅ status marker once their ACs pass in CI, with evidence — per the footer rule, claims invalidated by a merged PR are updated in the same commit.
> **Method:** every claim below was verified against the tree at `09a4e20b` (TRUTH-02) with file-level evidence. No aspirational boxes. Where I say something is missing, it is missing; where I say it is world-class, it is world-class.
> **Task ID convention:** `WS-<letter><number>` (workstream + task). Dependencies are explicit. Estimates are 2-week sprints for one engineer unless marked pair-work.

---

## 0. The One-Sentence Diagnosis

**We have built a world-class engine with no drivetrain attached — the most sophisticated audio pipeline in this repository has never played a single audible note outside Android's legacy Media3 fallback, our iOS app is a beautiful showroom running on fixtures, and our genuinely novel innovation (an explainable, server-free taste engine) is half-wired and sitting on the bench.**

Everything in this document flows from fixing that in the right order: **make it play, make it honest, make it sing.**

---

## 1. State of the Union (Evidence-Based)

### 1.1 Layer inventory

| Layer | Path | LOC | Verdict |
|---|---|---|---|
| Android app (Kotlin/Compose) | `app/` | ~76,800 main | **Production-grade.** 25+ features real, security-hardened, no UI-only stubs found. Principal debts: god-classes, zero UI tests on the 3 largest screens, scraper PO-token fragility. |
| Rust sovereign core | `rust/` (8 crates, +`streamify-playback` since #151) | ~48,500 src | **Production-grade.** 429 tests, dual-language golden vectors, 32-peer chaos harness, zero TODOs. Weak spots: the audio data plane is shipped through the sink edge (A3/A4, #151) with the Apple HAL caller landed (WS-B B4: the `ring_export` C ABI + the CoreAudio rust-ring mount) but the Android/desktop HAL callers still pending (C1/C5 per `docs/architecture/engines/13_playback_dataplane.md`); recommendation stack untested; resolver coverage outlier. |
| Native C++20 | `native/` | ~23,100 impl | **Rigor without reach.** RT-safety proofs, cache-line discipline, frozen ABIs — but **6 of 10 test suites never run in CI** (the CoreAudio render-core audit joined the sanitizer matrix in WS-B B4/C3), WASAPI is never compiled anywhere, and the JNI sink bridge never landed. |
| iOS/macOS (SwiftUI) | `iosApp/` | ~34,600 src | **First-note candidate.** 241 tests, real UniFFI lane CI-proven AND selected at runtime (`AppServices` + `FeatureLibraryLoader`); all 14 ViewModels on live lanes (search/home/queue/EQ/jam live; feed-type screens honest-empty until the engine grows those surfaces); the CoreAudio data plane assembled end-to-end (Swift mount → C++ bracket → the Rust ring's frozen C ABI — no AVPlayer anywhere); a checked-in host app target (app + widget extension, CarPlay scene manifest, Live Activities); zero PreviewCatalog references in production paths (lint-enforced). The remaining truth: audible-on-device verification (WS-B B3/B4 device gates) and Gate 4 remain unexecuted. |
| Desktop (CMP) | `desktop/` + `shared/` | ~2,000 + ~2,400 | **Polished facade over a silent engine.** Shell, tray, shortcuts, packaging all real; PLAYING state produces no sound. |
| Supabase backend | `supabase/` | ~2,200 SQL | **Half-hardened.** Jam lease/RPC, pgvector, admin RPCs, 11-assertion CI suite — but 7 telemetry/edge tables still ship `OR TRUE` / `USING (TRUE)` RLS. |
| CI (hub-and-spoke DAG) | `.github/workflows/` | 11 files | **Genuinely excellent.** One blocking gate, build-once fan-out, gitleaks at t=0, truth lint, nightly fuzz + KVM emulator + resolver canary. |
| Legacy (frozen) | `legacy/` | ~7.6 MB | Correctly quarantined; nothing references it. |

### 1.2 The five hard truths

1. **The audio data plane does not exist outside Android.** The Rust engine's `apply_media_command` drove the lifecycle state machine only through `09a4e20b` — `Seek | Next | Previous` were literal no-ops ("effects deferred to the queue engine phase"). **The transport slice is now closed (#148):** Seek/Next/Previous drive a queue-scoped transport (cursor + position target, wrap-around, duration-clamped seeks) over the staged typed queue, contract-tested in `test_uniffi_contract.rs`; the *rendering* half (producer → sink) remains open and is specified in `docs/architecture/engines/13_playback_dataplane.md`. `native/include/streamify_audio_sinks.h` specifies a 96-byte wire layout and 7 frozen symbols for a `jni_bridge_audio_sinks.cpp` **that was never written** (verified: `native/jni/` contains only `jni_bridge.cc` and `jni_bridge_dsp.cc`). The WASAPI/PipeWire/ALSA C facades (`wasapi_pull_adapter.cpp:535`, `pipewire_driver.cpp:606`) still have **zero production callers** — desktop "PLAYING" produces silence. The CoreAudio facade now has production callers (WS-B B4: `PlaybackViewModel` mounts `CoreAudioRenderCore` → `streamify_coreaudio_start_rust_ring` pulls from the engine's exported Rust ring); its audible-on-device proof is the pending WS-B gate.
2. **iOS truth stopped at CI — closed on the code plane (WS-B, this PR).** `UniFFIFeatureLibrary` now exists and `AppServices.library` selects it automatically whenever the real engine lane is linked (`FeatureLibraryLoader`); the shell's search routes through the 300 ms-debounced `SearchViewModel` over live FFI `searchTracks`; all 14 ViewModels ride live lanes; production paths hold zero `PreviewCatalog` references (enforced by `scripts/preview_catalog_guard.py` in preflight). The truth that REMAINS: on-device audibility (the B3/B4 device gates), and the feed-type engine surfaces (community/stats/updates/profile/ingestion render honest empties until SW-ENG1-03's expanded contract lands).
3. **Security hardening stopped 60% of the way.** SEC-01..10 were found, fixed, and regression-tested — exemplary. But: 7 telemetry/edge tables in `supabase/schema.sql` (26 `OR TRUE`/`USING (TRUE)` matches) let any anon caller read/overwrite any user's listening patterns and **poison the Project Titan consensus mesh**; the compromised service_role key and 5 ZhipuAI keys were removed from the tree but **never rotated/revoked at the provider**; the GOV-05 release environment holds **zero signing secrets**; and the new `release.yml` has **never executed end-to-end**.
4. **The truth program only lints one direction.** TRUTH-02's drift-catcher verifies checked-boxes-point-at-real-files. It misses: `A_Checkbox.md` is ~80% *unchecked-but-done*; `docs/playbooks/TODO.md` has 12 checked boxes pointing at 12 nonexistent paths; `supabase/supabase.md` still teaches the pre-SEC-10 email-grant `is_admin()` — a working reintroduction vector for the exact vulnerability SEC-09/10 killed.
5. **Our innovation is half-wired.** The "AI-free algorithmic recommendation" thesis — our actual differentiator — consumes a 128-dim acoustic embedding that **nothing in the workspace produces** (`continuum_engine.rs:11` is the only mention). `neuro_queue.rs` + `continuum_engine.rs` (483 LOC) have **zero tests** and are reachable only through raw C-ABI/JNI with an implicit, undocumented layout (`vec[0]`=BPM, `vec[1]`=artist-hash). The tested `GroupBlendScorer` (`radio_scorer.rs`) has no caller in any product surface. Meanwhile the acoustic analysis primitives needed to build embeddings (LUFS, spectral DSP, waveform energy) already exist, production-grade, in `streamify-audio`.

### 1.3 What is genuinely world-class — protect it

- **The Jam mesh**: 32-peer loopback chaos harness with loss/jitter injection and ≤300 ms convergence gates (`rust/crates/streamify-jam/tests/test_mesh_gossip_32peers.rs`, 783 LOC); PlumTree gossip with 4 documented protocol refinements; CRDT queue with dual-language golden-vector wire freeze. *No consumer music app does this serverlessly.*
- **The RT discipline**: zero-allocation audio thread, cacheline-aligned SPSC ringbuffer with generational flush, `AllocGuard` zero-alloc proofs, VirtualHAL adversarial DAC double, <5 ms DAC presentation-clock acceptance.
- **The crypto**: SEC-06 per-64-KiB-chunk ChaCha20-Poly1305 AEAD vault with BLAKE3 derivation and read-old/write-new migration — audited and tamper-tested.
- **The honesty infrastructure**: TRUTH lint in preflight, docs-ledger checks, `jni_parity_check.py` (source + `nm` on the shipped `.so`), the resp5/resp6 architect-review culture. We are, culturally, a team that documents its own gaps. AHEAD.md extends that tradition.

---

## 2. Strategic Direction — "The Sovereign Listening Engine"

Our founding thesis (`PHILOSOPHY.md`) is that premium features are the baseline entitlement of every listener. Our technical thesis — the Sovereign Core — put all business logic in Rust and bound platforms to it directly. The multiplatform migration proved the *architecture*. AHEAD's job is to prove the *promise*: **one engine, every speaker; taste without a server; truth all the way down.**

### Pillar 1 — MAKE IT PLAY: one engine, every speaker
Complete the audio data plane so the Sovereign Core actually renders audio on Android (Rust path, deleting the Media3 fallback per MP-CUT02), desktop (WASAPI/PipeWire/ALSA), and Apple (CoreAudio). The metric that matters: **hours of audible playback per platform per day, on the Rust engine.**

### Pillar 2 — MAKE IT SING: taste without a server
Close the loop on the industry's only fully-explainable, fully-offline recommendation stack. We do not ship an ML runtime, we do not upload taste to a cloud, and every recommendation carries a human-readable reason ("128 BPM, Camelot +1 from your 9pm slot, 3 roommates liked it"). This is the anti-algorithm: **Spotify cannot follow us here without betraying its business model.**

### Pillar 3 — MAKE IT HONEST: truth all the way down
TRUTH-03 makes the truth program bidirectional (unchecked-but-done is also drift), finishes SEC-10b, baptizes the release pipeline, and turns the 7 CI-dead native test suites into live gates. An open project that claims "every PR links the doc it changes" must not have a reference doc that teaches its own worst vulnerability.

### Guardrails (inherited, non-negotiable)
No paywalls. No ads. No telemetry-based profiling. Correctness-over-velocity at frozen ABIs. Documentation is part of done. The six `PHILOSOPHY.md` non-negotiables apply to every task below; any task that conflicts with them is malformed.

---

## 3. Workstream Assignments

Seven workstreams, seven engineers. Dependencies are called out; where a workstream can start independently it says so. Each task carries acceptance criteria (AC) — a task is not done until its ACs pass in CI, per repo convention.

---

### WS-A — ENG-1 "Drivetrain" · *The audio data plane in the Rust engine*
**Mission:** make `MediaCommand::Play` produce sound. Own `rust/crates/streamify-contract`, the new `streamify-playback` module, and the producer/decoder/sink pipeline. This is the highest-leverage workstream in the repo: WS-B, WS-C, and the MP-CUT cutover all wait on it.

| ID | Task | Detail & files | AC | Est. | Deps |
|---|---|---|---|---|---|
| **A1** ✅ | **Design the playback pipeline RFC** | ✅ done (#148): `docs/architecture/engines/13_playback_dataplane.md` — four-plane architecture, producer spec (incl. the decode dependency decision), the Rust-side `AudioSink` trait mirroring `native/include/streamify_audio_sinks.h`, frozen-ABI review notes, metrics. Original brief: producer (fetch → decode → resample via existing `audio_src.rs`) → `AudioSink` write edge → platform HAL. Define the Rust-side `AudioSink` trait (WriteFloat, latency, clock) that the C++ registry (`native/include/streamify_audio_sinks.h`) already mirrors. Frozen ABI review per non-negotiable #3. | RFC merged; UDL delta drafted (RFC §9); ABI review recorded (RFC §6.3) | S | — |
| **A2** ✅ | **Implement Seek/Next/Previous + queue-scoped transport in `apply_media_command`** | ✅ done (#148): the no-op arm is replaced — `Seek` re-anchors the position target (clamped to the current row's duration when known), `Next`/`Previous` move the queue cursor (wrap-around mirroring `get_next_track`'s circular loopback) and reset the position; the typed queue (`QueueLane`: rows + cursor under one lock) is the transport's queue truth; `clear_queue` resets the transport. UDL-exposed getters (`get_current_queue_item`, `get_playback_position_ms`) are Rust-side until A4. | Contract tests in `streamify-contract/tests/test_uniffi_contract.rs` cover seek/next/prev round-trips; state rev bumps verified — 4 tests, green | M | A1 |
| **A3** | **Build the stream producer** — ✅ shipped (#151) | HTTP range-fetch (reuse `downloader.rs` resume logic) → Opus/AAC decode → `SincFixedOut` 48 kHz resample → LUFS chain (existing `lufs.rs`) → sink write edge. Zero-allocation on the audio edge; buffer via the existing ring discipline. Cancellation-safe on track skip. | Golden-vector test extended: decode→resample→LUFS path pinned vs new fixtures; skip-under-load stress test (100 rapid skips, no leak, no underrun) | L | A1 |
| **A4** | **Expose the sink + transport through UniFFI** — ✅ shipped (#151) | UDL additions: `attach_sink`, `detach_sink`, `play_track(track_id)`, `buffer_progress()` events through the existing event dispatcher thread. Keep `CONTRACT_VERSION` bump additive (v2) — Swift/Kotlin bindings must regenerate cleanly. | `test_uniffi_contract.rs` + `scripts/build_ios_xcframework.sh` regen green; checked-in `shared/src/desktopMain/kotlin/uniffi/streamify/streamify.kt` drift check passes | M | A3 |
| **A5** | **Live-audio self-test harness ("engine says 440")** — ✅ shipped (#151) | A `virtual_hal`-backed test double sink that verifies end-to-end: `play_track` → producer → sink → measurable PCM. This is the gate every platform integration (WS-B/WS-C) runs against before touching hardware. | `cargo test -p streamify-contract live_audio_selftest` green in CI; harness reusable as desktop smoke test | M | A3 |
| **A6** ✅ | **Reconcile the release panic policy** | ✅ done (#148): decision recorded — **keep `panic="abort"` for shipped artifacts** (MP-C06/MP-C07 consensus; reversal triggers + the non-host-target fallback documented), per-profile `ffi_shield` semantics table in the RFC §8, the cfg-split executable probe `src/bin/panic_probe.rs` (unwind leg: marshals to `EngineError::Internal`, exit 0; abort leg: SIGABRT, verified locally — exit 134), and the contract tests `tests/test_panic_policy.rs` pinning both the marshaling path and the release-profile abort policy. Original brief: `panic="abort"` (`rust/Cargo.toml [profile.release]`) makes every `catch_unwind` FFI shield release-inert — the tested marshaling path cannot fire in shipped artifacts. Either gate abort to non-host targets or ship a crash-reporter contract test. Document the decision in the RFC from A1. | Decision recorded; panic-path contract test exists for the chosen profile; `ffi_shield` semantics documented per-profile | S | A1 |

**WS-A exit criteria:** `StreamifyEngine` plays, pauses, seeks, and transitions tracks audibly through a VirtualHAL sink with zero allocation on the audio edge, contract-tested on all three bindings.

---

### WS-B — ENG-2 "Apple Integration" · *From showroom to first note*
**Mission:** flip the iOS/macOS app from fixtures to the real engine and make it play through CoreAudio. The seams are pre-cut (`EngineGateway`, `FeatureLibrary`, `CoreAudioRenderCore`); this is assembly, not invention.

| ID | Task | Detail & files | AC | Est. | Deps |
|---|---|---|---|---|---|
| **B1** ✅ | **Implement `UniFFIFeatureLibrary`** — ✅ shipped (#155) | ✅ done (#155): `ViewModels/Support/UniFFIFeatureLibrary.swift` over the shared `UniFFIEngineGateway` (search/home/queue/EQ/jam lanes; honest-empty defaults for the contract's not-yet-existing surfaces); `AppServices` now holds ONE shared engine gateway and `FeatureLibraryLoader` performs the switch. CI-proven by `UniFFIContractTests` (lane selection, EQ mapping, queue-event synthesis). Original brief: the documented-but-missing type behind `#if canImport(streamifyFFI)` (`FeatureLibrary.swift:13-15`). Implement over `UniFFIEngineGateway`; keep `PreviewFeatureLibrary` for previews/tests only. | `AppServices.library` (FeatureLibrary.swift:373) selects the real lane when the framework is linked; preview lane intact for SwiftUI previews | M | A4 |
| **B2** ✅ | **Wire the 9 orphaned ViewModels** — ✅ shipped (#155) | ✅ done (#155): all 9 VMs constructed by their screens over live lanes; `RootShellView` queue truth is the engine's queue (`QueueViewModel` + derived projection), search rides the 300 ms-debounced `SearchViewModel`, the play action routes into the engine (`play(track:)` / `playStaged`); `scripts/preview_catalog_guard.py` (mirroring `docs_ledger_check.py`, wired into preflight) enforces zero production-path PreviewCatalog references — clean. Original brief: Home, Search, Queue, Community, StatsWrapped, UpdatesHub, UserProfile, Ingestion, Settings VMs onto the real library. Replace the no-op search callbacks at `RootShellView.swift:526-544` with `SearchViewModel`; queue truth from `gateway.queue()` instead of `PreviewCatalog.queueState` (`RootShellView.swift:53`). | Zero `PreviewCatalog` references in production code paths (previews/tests exempt — enforce with a lint script mirroring `docs_ledger_check.py`); `RootShellView` play action routes into the engine | L | B1 |
| **B3** | **Map `resolve_stream_formats` + play a track** — code landed (#155), device gate pending | Landed (#155): the gateway maps resolve + a session memo + the persistent resolver cache (`set_resolver_cache_db` → app-support SQLite); `play(track:)` performs resolve → stage → attach → `play_track` with staged-track snapshot enrichment (now-playing metadata + lock-screen surfaces). The AC (first audible track from a Home tap) requires hardware — pending the on-device verification recorded in the PR. Original brief: The UDL already exposes it; `UniFFIEngineGateway.swift` doesn't map it. Add the mapping + resolve cache; wire `play(track:)` → resolve → `play_track` (A4). | First real track plays from Home tap → Now Playing with real metadata | M | A4, B1 |
| **B4** | **CoreAudio data plane** — bracket landed (#155), audible gate pending | Landed (#155), no AVPlayer anywhere: `PlaybackViewModel` mounts `CoreAudioRenderCore` (`startRustRing`) + the 10 Hz `DacClockPublisher` drain → `record_dac_clock`; the native bracket is `RenderFrameSource` (C++ pull edge) → `streamify_coreaudio_start_rust_ring` → the Rust ring's frozen C ABI (`streamify-playback/src/ring_export.rs`, installed by `attach_sink`); the xcframework build compiles+probes the bracket symbols in every slice. `coreaudio_render_core_test_suite` joined the sanitizer matrix (C3) — 41/41 green locally. The AC's audible-on-device leg + the live-path interruption/route-change runs are pending hardware. Original brief: Instantiate `CoreAudioRenderCore` (currently test-only) from `PlaybackViewModel`; register the Apple sink against the engine sink edge (A4); drive via the existing `IOSAudioSessionAdapter` + `DacClockPublisher`. Interim AVPlayer path is **not** acceptable — we built the CoreAudio path for this. | Audible playback on device; interruption/route-change tests (already written) pass against the live path; `coreaudio_render_core_test_suite` (currently CI-dead — see C3) green | L | A4 |
| **B5** ✅ | **Host app target in-repo** — ✅ shipped (#155) | ✅ done (#155): `iosApp/Host/` — a committed `StreamifyHost.xcodeproj` (objectVersion 77 synchronized folders, app + widget extension, App Group, CarPlay scene manifest, `NSSupportsLiveActivities`, `streamify://` deep links, background audio) + `project.yml` (XcodeGen source of truth). CI builds it from a fresh clone in the Apple lane ("Host app build gate", simulator destination, signing disabled); `iosApp/Host/README-host-target.md` documents the real-lane staging + device capabilities. Original brief: No `.xcodeproj` exists; the README documents a manual 5-minute husk creation. Check in a minimal host target (or Tuist manifest) with widget extension, App Group, CarPlay scene manifest, `NSSupportsLiveActivities`, `streamify://` deep links. | `git clone` → `xcodebuild build` produces an installable app without manual project creation; capabilities wired | M | — (parallel) |
| **B6** | **Activate the dormant platform surface** — wired (#155), Gate 4 honestly NOT executed | Wired (#155): the CarPlay scene delegate is public + manifest-instantiated with `router` assigned by the shell; the Live Activity controller starts/updates/ends on the admitted push cadence with `endAllStale()` at cold launch; the widget extension target hosts the (now public) bundle. Gate 4 remains unexecuted — the PR attaches the runbook (`docs/playbooks/gate4_fluidity_runbook.md`) instead of a fabricated report, per the truth program; the menu-bar extra stays dormant (Wave 3). Original brief: CarPlay (`CarPlaySceneDelegate.router` unassigned), Live Activity (`PlaybackLiveActivityController` never started), menu-bar extra. Then run **Gate 4** (Instruments 120 Hz fluidity) which has never been executed (`SWIFT_MIGRATION_PLAN.MD:176,215`). | CarPlay renders browse/playback from real library; Live Activity updates during playback; Gate 4 report attached to the PR with measured frame pacing | L | B2, B4 |
| **B7** | **Downloads on Apple** — wired (#155), airplane-mode gate pending | Wired (#155): `DownloadViewModel` (manifest-indexed track correlation, progress aggregation, pause/resume/retry over the service's resume-data discipline with a remove-race sweep, real storage card) + `DownloadsView` on the model with the recovery banner; offline rows play through the SAME engine path via a `file://` resolved URL (the fetcher's new local-file range lane in `playback_engine.rs`). The AC's airplane-mode leg requires hardware — pending the on-device verification recorded in the PR. Original brief: `BackgroundDownloadService` (429 LOC, orphaned) wired into `DownloadsView` (currently `PreviewCatalog.downloads`) with resume-data recovery. | Download a track → airplane mode → plays offline | M | B3 |

**WS-B exit criteria:** a fresh clone builds an app that plays a real resolved stream through CoreAudio with working lock-screen controls, Live Activity, and one offline track.

---

### WS-C — ENG-3 "Native JNI & HAL" · *Ship the drivetrain's mounting brackets*
**Mission:** land the missing JNI sink bridge, switch on the orphaned verification suites, and finish the platform HAL story. This workstream is mostly *activation* of excellent existing code.

| ID | Task | Detail & files | AC | Est. | Deps |
|---|---|---|---|---|---|
| **C1** ✅ | **Land `native/src/jni_bridge_audio_sinks.cpp`** — ✅ shipped (#154): the bridge implements all 7 frozen symbols over the `SinkRegistry` (opaque handles, negative create sentinels per the Kotlin-side contract, `GetPrimitiveArrayCritical` with lengths resolved before pinning, JNI_SHIELD on every export); `NativeAudioSink.kt` moved back into the main tree exactly as the frozen contract specified (AppGraph installs it on debug/`USE_RUST_AUDIO` only — MP-HAL07 preserved at the install point); `jni_parity_check.py --source` green both directions (167 funs / 167 exports — the gate also gained the JNI `_1` name-escaping fix the first underscore-carrying class exposed); the round-trip AC is pinned by `jni_sink_bridge_test_suite` (63 checks, host stub JNIEnv, ASan/UBSan lane); the dogfood panel lights up with zero app changes above the seam. Original brief: the header is done (`streamify_audio_sinks.h`: 7 frozen symbols + the 96-byte wire; registry + serializer exist); the app side is waiting. **This is the single highest-leverage file in the repository.** | `jni_parity_check.py --source` green with new exports ✅; ringbuffer round-trip test through JNI ✅ (`jni_sink_bridge_test_suite`, C3 lane); dogfood panel renders live PCM telemetry ✅ (route probe resolves with the shipped symbols) | L | A1 (trait alignment) |
| **C2** | **Android Rust-audio cutover (MP-CUT01)** — ARMED (#154): C1 unblocked the lane; the execution contract is now `docs/playbooks/ANDROID_RUST_AUDIO_CUTOVER.md` (T-day arming steps, the telemetry table the dogfood panel already renders, go/no-go thresholds — crash-free ≥ baseline−0.05pp, underruns ≤ 0.5% of bursts, battery ≤ +3%/listening-hour — and the MP-CUT02 deletion checklist incl. the recorded terminal-state decision). Execution with ENG-6 (F2) starts on the next fleet cycle. Original brief: 2-week dogfood of `USE_RUST_AUDIO=true`, then MP-CUT02 30 days post-cutover. | Dogfood report (underruns/24h, battery delta, crash-free rate) published; after 30 days, Media3 fallback code deleted and `USE_RUST_AUDIO` flag removed | L | C1, A3 |
| **C3** ✅ | **Switch on the 7 CI-dead native suites** — ✅ shipped (#154): all seven are matrix shards in `_ci-engine.yml`'s `cpp-sanitizers` job (`native-dsp-phase-lock`, `native-virtual-hal`, `native-coreaudio-render-core`, `native-audio-sink-ringbuffer`, `native-desktop-hal-render-cores`, `native-capability-abi-parity`, `native-linux-hal-driver` — ALSA *null* PCM, no hardware needed) plus the two new WS-C lanes (`native-jni-sink-bridge`, `native-lufs-parity`): twelve shards total. Original brief: one-line shard entries; ~4,000 LOC of sleeping verification becomes a live gate. | All suites green in the sanitizer matrix for 5 consecutive nights; flakes triaged (first nights observed on this PR's CI + the following week) | S | — |
| **C4** ✅ (Windows lane) | **Windows/macOS native compile lanes** — ✅ Windows lane shipped (#154): the `windows-native` job in `_ci-engine.yml` configures the tree with VS 2022 and compiles `streamify_desktop_hal` + `streamify_wasapi_adapter` on every core-touching PR (the `if(WINWIN)` targets finally fire); the CMake flag block is now MSVC-conditional and the Android .so target is guarded on `ANDROID` so plain host configures work. The macOS leg belongs to the Apple spoke (ENG-2's lane — B4 carries it). Original brief: the WASAPI adapter was never compiled anywhere. | WASAPI adapter TU compiles on every PR touching `native/**` ✅ (CoreAudio stays with the Apple spoke) | M | — |
| **C5** | **Desktop audio wiring** — ENG-3 platform slice ✅ shipped (#154): the C facades now have a production compilation home AND a loadable artifact — `streamify_desktop_hal` (CMake target, Linux+Windows; the windows-native rot lane compiles it with MSVC) carrying the plain-C sink registry ABI (`include/desktop_hal.h`, the same frozen contract C1 binds) plus the WASAPI / PipeWire-ALSA facades; the JVM binds it through `DesktopHalBridge` (JNA, soft-probe degraded) and `DesktopJnaEngineSmokeTest` runs the AC round-trip through the real .so on both desktop CI legs. The engine-side relay onto the Rust `playback_ring()` is ENG-1's follow-up (the remaining half of the AC). Until that lands, desktop audio remains engine-silent — the HAL investment is now compiled, shipped and contract-tested instead of dead code. Original brief: call the existing C facades from the engine lane (Rust dlopen shim or JNA binding; ENG-1 owns the engine side, ENG-3 the platform shims). | Desktop app plays audible audio on Windows (WASAPI) and Linux (PipeWire/ALSA-null CI + real PipeWire manual check) — open, engine-side relay pending; `DesktopJnaEngineSmokeTest` extended with a sink round-trip ✅ | L | A4 |
| **C6** ✅ | **C++ JNI panic shielding** — ✅ shipped (#154): `native/jni/JniShield.h` defines the macro pair mirroring the Rust `catch_unwind` house model (try/catch → per-type sentinel, `return ;` spelling for void); ALL 87 `jni_bridge.cc` exports (incl. the six inside the trailing `extern "C"` block a naive prefix-matched codemod would have missed) + all 6 `jni_bridge_dsp.cc` exports + all 7 sink-bridge exports are shielded — 169 exports across 5 bridge TUs; `scripts/jni_panic_boundary_check.py` now enforces the C++ tree with the same `// panic-boundary-exempt:` escape hatch, and both retrofitted bridges pass a host `g++ -fsyntax-only` audit against a comprehensive stub `jni.h`. Original brief: one `catch(...)` across 1,799 LOC vs 67 `catch_unwind` sites in the Rust FFI. | Panic-boundary script enforces shields on all 87 exports ✅ (87 + 6 + 7 across the tree); static-review sign-off — this row | M | — |
| **C7** ✅ | **Run the two orphaned fuzzers** — ✅ shipped (#154): `nightly-deep-matrix.yml` now compiles and runs all three harnesses — `dsp_fuzzer_harness`, `audio_sink_fuzz_harness`, `frame_palette_fuzz_harness` — each against its OWN cached corpus (`libfuzzer-corpus-dsp/-audio-sink/-frame-palette`), 480s per harness inside the bumped 60-min job budget; every harness runs its full budget even after another finds a crash (more signal per night), and a crash in any one fails the job. Original brief: they cover the two most hostile-input-exposed surfaces (network packet parsing, untrusted bitmaps). | Both fuzzers running nightly with cached corpora ✅; ≥1 week crash-free — observed from the first post-merge nightly | S | — |
| **C8** ✅ | **LUFS consolidation** — ✅ shipped (#154): Rust `lufs.rs` is the designated canonical reference (recorded in `LufsNormalizer.h`); the C++ path is pinned to its golden vectors via a THREE-WAY pin — one fixture (`native/tests/lufs_golden_vectors.h` + the Rust data module) generated by `scripts/generate_lufs_golden_vectors.py` (the canonical algorithm mirrored op-for-op) or the real analyzer itself (`cargo run -p streamify-audio --example lufs_golden_vectors`), consumed by BOTH drift gates: `tests/test_lufs_golden_vectors.rs` (Rust, ε=0.01) and `lufs_parity_test_suite` (C++, ε=0.02; measured max delta 2.5e-05 LUFS). The consolidation ALSO caught and fixed a real drift: the C++ 48 kHz stage-1 `b2` literal was a typo (1.19839251015039 vs the ITU spec's 1.19839281085285). The fold-and-delete option stays open post-C2. Original brief: two independent BS.1770-4 implementations (f64 golden-pinned vs f32, ±12 vs ±20 dB clamps, any-rate). | Cross-implementation parity gate in CI ✅ (`native-lufs-parity` shard + the Rust gate) | M | C2 (for the fold option) |
| **C9** ✅ | **Fix `native/README.md` drift** — ✅ shipped (#154): the five phantom bridge TUs and their Kotlin classes are replaced with the consolidated reality (two `.cc` bridges + the Rust-side bridges, with the parity gate named as the enforcement); the sink-bridge row now documents the SEVEN landed symbols; the CI paragraph reflects the twelve C3 shards; the ABI note names the real underscore-escaped binding class; "desktop audio output: wired in C5" status added; `SharedInstances.h`'s stale `jni_bridge_native_dsp_engine.cc` reference fixed. `docs_ledger_check.py` (TRUTH-02) stays green. | TRUTH lint extended to `native/README.md` claims — the checked-ledger pass covers the README's file claims; doc matches tree ✅ | S | C1 |

**WS-C exit criteria:** Android renders through the hardened Rust path with Media3 deleted; desktop plays audibly; every native suite and all three fuzzers execute in CI on every relevant platform.

---

### WS-D — ENG-4 "Taste Engine" · *Make it sing — the explainable, serverless recommendation loop*
**Mission:** close the half-wired loop and ship our genuine differentiator. This is where the innovative direction lives (see §7 BET-1/BET-2).

| ID | Task | Detail & files | AC | Est. | Deps |
|---|---|---|---|---|---|
| **D1** | **Define the 128-dim embedding contract in Rust** | Today the layout is implicit and undocumented (`continuum_engine.rs:94-99` overloads `vec[0]`=BPM, `vec[1]`=artist-hash). Create a versioned `AcousticEmbedding` struct — BPM, key/Camelot, LUFS, spectral bands, waveform energy, danceability proxies — with a schema doc and cross-language contract tests. | Contract doc + `streamify-core` type + parity tests pinning layout for Kotlin/Swift; `continuum_engine.rs` migrated off raw overloads | M | — |
| **D2** | **Build the embedding producer (Sonic Fingerprint v1)** | The primitives already exist, production-grade, in `streamify-audio`: `lufs.rs` (BS.1770-4), `dsp.rs` (10-band EQ + spectrum), `waveform_indexer.rs` (peak-preserving RMS). Compose them into a pure-Rust, **ML-runtime-free** producer that runs at ingest time (Android ingestion worker exists; iOS pipeline is WS-B B2). | Producer crate + fixtures: embedding for 30s reference clips deterministic within ε; ≤200 ms/embedding on a mid-range 2020 phone (benchmark in CI) | L | D1 |
| **D3** | **Promote NeuroQueue + Continuum to first-class** | Both are currently raw C-ABI/JNI-only with **zero tests** and a deterministic sin-based pseudo-random for exploration (`continuum_engine.rs:109`). Expose through the UDL, add unit tests for all 5 brain states / circadian dayparting / 3.5 h satiation decay / 5-slot micro-arc, replace the sin-RNG with a seeded RNG. | UDL surface + tests; Android `ContinuumRadioEngine` consumes the engine lane (deleting its local twin); no `#[no_mangle]` remains for these features | M | D1 |
| **D4** | **Reason strings — the explainability surface** | Every recommendation carries a human-readable "why" composed from the scorer's feature attributions (BPM distance, Camelot distance, freshness, co-occurrence, room overlap). Expose in the UDL; render in queue rows and radio shelves. | A/B-able UI string per recommendation; snapshot tests for reason composition; zero network calls | M | D3 |
| **D5** | **Group Blend in Jam (live)** | The tested `GroupBlendScorer` (`radio_scorer.rs`, incl. <5 ms/500-candidate perf gate) has no product caller. Wire it into the Jam session: union of members' taste vectors drives a "Blend" queue lane with "3 others like this" badges — CRDT-native, serverless (BEHIND #15 + #25 done properly). | Blend lane inside a 8+ peer chaos test; badges in roster UI on Android; reason strings from D4 | L | D3, A2 |
| **D6** | **Daylist v2 (circadian, local-first)** | `ChronosProfiler` 4-slot circadian vectors + `DaylistScheduler` exist; unify on the engine side (daypart scoring in `neuro_queue`), feed from local history — **no telemetry upload required**. Time-bucketed home shelf refresh; share card via existing `TrackShareCard` pattern. | Daylist regenerates offline; golden test pins morning-vs-night rankings for a fixture profile | M | D3 |
| **D7** | **Anti-drift + taste-profile controls parity** | Surface `excludedFromTaste` flags end-to-end (BEHIND #27): repository flags, filter at score time, settings toggles. Ensures the taste engine is *steerable* — a trust feature, not just a tech feature. | Excluded tracks never appear in radio/daylist/blend outputs; regression test | S | D3 |

**WS-D exit criteria:** a listener gets radio, daylist, and jam-blend recommendations produced entirely on-device, each with a readable reason, each steerable, with the entire path contract-tested.

---

### WS-E — ENG-5 "Security & Release" · *Make it honest — close every open credential and ship a governed release*
**Mission:** finish what SEC-01..10 started and baptize the never-run release pipeline. Small diffs, enormous risk reduction.

| ID | Task | Detail & files | AC | Est. | Deps |
|---|---|---|---|---|---|
| **E1** | **SEC-10b: harden the 7 open tables** | `supabase/schema.sql:638-890`: own-row RLS for `user_listening_patterns`, `user_musical_chronotype`, `user_device_context`, `track_hook_telemetry`; authenticated + RPC-gated inserts for `track_cooccurrence_graph`, `track_markov_2nd`, and all three `edge_compute_*` tables (currently any anon caller can poison the Titan consensus mesh). Write the migration delta; extend `supabase/sql/tests/rls_test.sql` with anon/stranger assertions. | Extended RLS suite green in the `supabase-rls` CI job; a red-team checklist (read-someone-elses-patterns, forge-edge-result) all fail closed | M | — |
| **E2** | **Credential runbook closure (lead actions, ENG-5 prepares)** | Rotate the service_role key at the Supabase dashboard (the burned JWT lives in git history); revoke the 5 ZhipuAI keys at the provider; enter the 4 signing secrets into the `release` environment. ENG-5 prepares verification scripts + a `v1.1.0-rc1` dry-run. | SECURITY.md verification table fully green (currently "pending — requires dashboard access"); 401 proof for the old service_role key | S | — |
| **E3** | **Baptize the release pipeline** | `release.yml` has never run end-to-end; the only `v*` tag predates the rewrite. Ship `v1.1.0-rc1` through the full path: tag → spokes on tag SHA → protected `release` environment approval → TRUTH-01b manifest check → SHA256SUMS → signed artifacts. | Release page with APK + `.deb` + `.msi` + `.dmg` + xcframework, all manifest-verified; runbook updated with the actual friction points found | M | E2, C2 (want the Rust audio path in the RC) |
| **E4** | **Reconcile `.gitleaks.toml` with reality** | A second InnerTube key (`AIzaSyC1xlRQ…Z4`, `LyricsResolver.kt:36`) contradicts the toml's "only key, exactly two places" claim — any PR touching that file fails preflight. Route it through the SEC-07 env-override pattern (`resolver.rs:80-102`) and fix the toml narrative. Also centralize the 8 duplicated key constants across `CanvasScraperApi`/`ArtistClipsApi`/`ReleaseWatcherApi`/`LyricsResolver` into one `InnerTubeKeys` object (with ENG-6). | Single key constant; gitleaks narrative true; preflight green on a PR touching all four files | S | — |
| **E5** | **TRUTH-03: bidirectional drift-catcher** | Extend `scripts/docs_ledger_check.py`: (a) unchecked-but-done reconciliation for `A_Checkbox.md` (check the AMP/UFFI/SEC/GOV boxes with evidence notes, or retire the file as superseded); (b) add `docs/playbooks/TODO.md` + `CHANGELOG.md` to the curated sets (TODO.md's 12 checked boxes point at nonexistent `docs/01_*.md` paths — real manuals live in `docs/architecture/engines/`); (c) plain-text doc-path mentions in curated docs. | Lint fails on both directions of drift; current tree passes after ledger reconciliation | M | — |
| **E6** | **Rewrite `supabase/supabase.md` §5/§6** | Still teaches the pre-SEC-10 email-grant `is_admin()`/`handle_new_user()` as live schema — a contributor pasting the "reference" reintroduces SEC-09/10. Rewrite to the hardened schema; add to `CURATED_DOCS` with a stale-claim denylist on the old snippet. | Doc matches post-SEC-10 schema; denylist entry prevents regression | S | E1 |
| **E7** | **Version & ABI unification** | Desktop `packageVersion 1.2.0` vs Android `versionName 1.1.0` skew; `build-ref.yml` builds `armeabi-v7a` outside the beta ABI scope. Single version source in root `build.gradle.kts`; decide the ABI story for `build-ref` (gate behind input flag). | One `VERSION` constant feeds both; release tags consume it; `build-ref` ABI-gated | S | — |
| **E8** | **Per-route volume ACL + moderation finish (BEHIND #16, #18)** | The governance rails exist (`jam_governor.rs` Ed25519 signed directives, kicks, token buckets; `ControlPolicy` in the app). Finish: route classification (SPEAKER/BT/CAST) with `allowGuestVolume` enforcement; report frame → host action flow. | Guest volume intents ignored on locked routes; report→kick flow in chaos test; UI toggles in Jam settings | M | — |

**WS-E exit criteria:** zero `OR TRUE` RLS in the schema, all burned credentials rotated, and a signed, manifest-verified release shipped through the governed pipeline.

---

### WS-F — ENG-6 "Platform Convergence" · *Delete the duplication, decompose the gods*
**Mission:** finish the multiplatform cutover the MP plan started, and pay down the structural debt that makes every feature cost 2–3× what it should.

| ID | Task | Detail & files | AC | Est. | Deps |
|---|---|---|---|---|---|
| **F1** | **Finish MP-CUT: shared UI components on Android** | Android's *only* `:shared-android` consumer is the dogfood panel. `app/ui/components/MiniPlayerBar.kt` + `TrackListItem.kt` duplicate `shared/commonMain` counterparts (~400 LOC × 2 bug surface). Migrate MiniPlayerBar → TrackListItem → QueueList → PlaybackSeekbar; delete the app-side twins. | Zero duplicated component pairs; `shared/README.md` cutover table fully checked; screenshot parity on 3 device classes | M | — |
| **F2** | **Decompose `PlayerViewModel` (1,362 LOC) fully** | The pattern is already established (`PlayerTrackLoader`/`PlayerTicker`/`PlayerHousekeeping`); finish extracting search/queue/DSP concerns. Then section `JamEngine.kt` (2,063) into transport/authority/fold modules along the seams its own README describes. | No >800-LOC ViewModel/engine file (repo lint); behavior identical (existing 8 jam test files green, new Compose UI tests from F4) | L | — |
| **F3** | **JNI bridge modularization + parity freeze** | `jni_bridge.rs` (1,864 LOC, 63 exports) → domain files mirroring `jni_bridge_p2p.rs`; `jni_bridge.cc` (87 exports) likewise. Generate a symbol-parity test against Kotlin `NativeBridge` (160 external funs) to freeze the surface — `jni_parity_check.py` covers declarations, not semantics. | Parity test in CI; monoliths split; `KNOWN_DANGLING_EXPORTS` stays EMPTY | M | C1 |
| **F4** | **Compose UI tests for the 3 largest screens** | `JamSessionScreen` (1,538), `LibraryScreen` (1,447), `AdminDashboardScreen` (1,365) — currently zero UI coverage. The hermetic JVM harness already exists (MockNativeBridge, MOCK engine backend). Add a screenshot/Robolectric shard. | ≥70% user-flow coverage on the 3 screens; screenshot diffs gated in nightly | L | F2 (post-decomposition) |
| **F5** | **Dependency dedup + staged upgrades** | Cargo.lock carries duplicate majors (rand ×3, socket2 ×2, windows ×2, getrandom ×2) plus older pins (rand 0.8, rusqlite 0.31, uniffi 0.28.3). Unify; stage the uniffi 0.28→0.29 breaking bump on a branch. | Single major per crate in lockfile; uniffi spike PR with migration notes | M | — |
| **F6** | **Split `p2p_mesh.rs` (3,388 LOC)** | Four protocol layers in one file (wire/beacon/transport/governance routing). Split with per-layer tests; no behavior change. | 4 modules, each <1,000 LOC; 32-peer chaos suite green (it is the regression net) | M | — |
| **F7** | **WearOS watch app — ship or descope** | Phone-side GMS transport is real and tested (`wear/`) but **no watch module exists** in `settings.gradle.kts`. The feature reads half-delivered. Decision: build the minimal watch surface (transport + wrist queue + offline run cache via `wear_sync.rs`, which is already 2,673 LOC of tested protocol) or descope loudly in BEHIND.md. | Either a `:wear` module building in CI with 3 screens, or a descope ADR + BEHIND.md update | L | A4 (for engine lane) |
| **F8** | **Weft: promote or archive** | The vendored Triad-Protocol kernel (`weft/`, "PENDING REAL-DEVICE VERIFICATION") powers exactly one overlay (`QuantumSonicTokenController`). Either verify on-device and promote to a documented UI-backbone lane for high-frequency state (lyrics scroll, waveform), or freeze it as overlay-only with an ADR. | ADR merged; if promoted: lyrics scroll path uses Weft with frame-cursor tests on device | M | — |

**WS-F exit criteria:** one component library across Android/desktop, no god-classes, frozen JNI surface, and every "half-delivered" platform story either shipped or honestly descoped.

---

### WS-G — ENG-7 "Reliability & Fleet" · *Make resolution unkillable*
**Mission:** the resolver is our front door to the catalog and our single point of external fragility. Own its testability, its anti-rot fleet, and our PO-token posture.

| ID | Task | Detail & files | AC | Est. | Deps |
|---|---|---|---|---|---|
| **G1** | **Resolver integration suite (mock-server)** | `crates/streamify-resolver/tests/` is empty — 19 tests / 6,153 src LOC, the coverage outlier; the network-tier fallback ladder (`resolver.rs:724-1024`) is validated only by manual live examples. Build wiremock/httptest suites for the Innertube tier ladder, downloader resume, `spotify_ingest` parsing, tagger fixtures. | ≥90% of the fallback-ladder branches exercised hermetically; CI runtime +<90 s | L | — |
| **G2** | **PO-token posture (the existential scraper risk)** | `YouTubeStreamResolver.kt` uses signatureTimestamp + client fleet only; no `po_token` handling. YouTube's rolling enforcement will increasingly 403 this path. Evaluate: (a) BotGuard companion-binary protocol (rustypipe-botguard is already an optional dep with a documented rationale), (b) token-provider abstraction behind FleetConfig, (c) honest degradation ladder + user-visible diagnostics. Ship a decision ADR + implementation of the chosen path. | ADR merged; either PO-token generation working on Android, or a documented degradation UX + fleet telemetry that detects enforcement within 24 h | XL | — |
| **G3** | **Offline-fallback golden fixtures** | Pin the legacy Innertube tier (`resolver.rs::fetch_innertube_cdn`) with golden player/search fixtures so the rustypipe failure path is CI-verified, not just canary-verified. | Fallback tier exercised in every PR touching `rust/` | M | G1 |
| **G4** | **Canary fleet observability** | `resolver-canary.yml` self-heals `fleet-config.json` via reviewed PRs (GOV-05). Add: trend dashboard data (success rate per client per day committed to `testing-log`), alert thresholds, and a weekly fleet report doc. | Weekly report auto-generated; 3 consecutive nights of regression triggers an issue automatically | S | — |
| **G5** | **Android connected-test expansion** | Nightly KVM emulator runs the app; extend to cover: cold-start → play a cached fixture track (via G3 fixtures, no network), jam join over loopback, download → offline play. | Nightly connected suite green 5 consecutive nights; flakes <5% | M | G3, C1 |
| **G6** | **Crash/telemetry backbone** | We have no post-release crash reporting (the `panic="abort"` policy intersects here — see A6). Wire a minimal, privacy-preserving crash reporter (no taste data, per PHILOSOPHY) into the release channel from E3, feeding `AHEAD` metrics (§5). | Crash-free rate ≥99.5% measured on the RC population before v1.2.0 | M | A6, E3 |

**WS-G exit criteria:** the resolver fails gracefully and observably under any YouTube enforcement scenario we can simulate, and every fallback tier is CI-pinned.

---

## 4. Sequencing — Four Waves

```
Wave 1 · Weeks 1–4   "UNBLOCK & SECURE"
  A1 ✅(#148) A6 ✅(#148) │ B5 │ C1✅ C3✅ C4✅ C6✅ C7✅ C9✅ C2 armed │ E1 E2 E4 E5 E6 │ F3 F5 │ G1 G4
  Gate: 7 native suites live in CI ✅ · RLS red-team fails closed · sink bridge landed ✅
  (A2 also closed early — #148 — pulling Wave 2's start forward.)

Wave 2 · Weeks 3–10  "MAKE IT PLAY"          (overlaps Wave 1 from week 3)
  A2 ✅(#148) A3 A4 A5 │ B1 B2 B3 B4 B7 │ C2 C5 (platform slice ✅) │ F1 │ G3 G5
  Gate: AUDIBLE — Android plays via Rust sink (dogfood on), desktop plays via
        WASAPI/PipeWire, first iOS note through CoreAudio. Demo-or-it-didn't-happen.

Wave 3 · Weeks 8–16  "MAKE IT SING & CONVERGE"
  D1–D7 │ B6 │ F2 F4 F6 F8 │ G2 │ E3 E7 E8
  Gate: v1.2.0-rc1 ships through the governed release pipeline with the taste
        engine behind a feature flag; MP-CUT02 (Media3 deletion) executed.

Wave 4 · Weeks 14–24 "INNOVATE & SCALE"
  BET-1..BET-5 (§7) │ F7 │ G6 │ field pilots (32-peer jam at 3 real parties,
        desktop daily-driver dogfood program, WearOS if shipped in F7)
  Gate: v1.3.0 with two innovation bets GA'd and measured.
```

Critical path: **A1 → A3/A4 → C1 → C2** (Rust data plane → JNI sinks → Android cutover) and **A4 → B3/B4** (Apple first note). Everything else has slack.

---

## 5. Metrics & SLOs (what we will actually measure)

| Metric | Target | Source |
|---|---|---|
| Audible playback hours on Rust engine (per platform, daily) | Android ≥ 90% of playback hours by week 10; desktop/iOS nonzero by week 10 | Engine telemetry (local, privacy-preserving) |
| Audio underruns per 24 h of playback | < 2 (mobile), < 0.5 (desktop) | `frame_pacer_monitor` + VirtualHAL baselines |
| DAC presentation-clock error P99 | < 5 ms (existing gate, now on-device) | `DacClockPublisher` |
| Jam convergence: 1,000 ops @ 32 peers, 15% loss | median ≤ 300 ms (existing chaos gate, now in nightly on real LAN pilot) | Chaos suite + field pilot |
| Crash-free sessions (release channel) | ≥ 99.5% | G6 backbone |
| Resolver success rate (fleet canary) | ≥ 95% 7-day rolling; enforcement detected < 24 h | G4 dashboard |
| PR CI turnaround (path-filtered) | < 15 min p95 (resp7 target was <5m for touched-path PRs — re-measure and hold the line) | CI timings |
| Recommendation explainability | 100% of radio/blend/daylist picks carry a reason string; 0 network calls in the taste path | D4 contract tests |
| Truth lint | Passes **bidirectionally** on every PR | E5 |
| Test debt | 0 CI-dead suites; 0 never-run fuzzers; 3 flagship screens ≥70% UI coverage | C3, C7, F4 |

---

## 6. Risk Register (top 10)

| # | Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|---|
| R1 | YouTube PO-token enforcement kills core playback | **High** (chronic, external) | Critical | G2 is a whole workstream: token-provider abstraction, BotGuard companion-binary option, degradation UX, 24 h fleet detection |
| R2 | A1–A4 data-plane design slips → Waves 2–4 cascade | Medium | Critical | A1 RFC is week-1; C3/C5 (ENG-3) can build sink shims against the frozen header *before* the engine side lands |
| R3 | service_role / Zhipu keys exploited before rotation | Low (removed from tree, but live credentials) | High | E2 is a *lead dashboard action* — do it in week 1, before any Wave-1 code |
| R4 | Titan/telemetry table poisoning via open RLS | Medium | High | E1 closes it; the `edge_compute_*` forged-result path is the scariest |
| R5 | Release pipeline fails on first real run (never exercised) | Medium | Medium | E3 ships a throwaway `rc1` deliberately early to surface friction |
| R6 | LUFS dual-implementation drift produces audibly different loudness across platforms | Medium | Medium (brand: "studio-grade") | C8 parity gate / consolidation |
| R7 | 32-peer nightly chaos flakes erode trust in CI | Medium | Medium | Existing mutex-group + retries discipline; keep flakes <5% and quarantine, never disable |
| R8 | Apple host-target + capabilities work (B5/B6) blocks TestFlight | Medium | Medium | B5 starts week 1 precisely to de-risk; manual README path remains the fallback |
| R9 | Scope creep in WS-D (taste engine) delays it past Wave 3 | Medium | Medium | D-tasks are individually shippable; feature-flag the whole engine lane |
| R10 | Single-maintainer bus factor (CODEOWNERS routes everything to @meraxes89) | Certain (structural) | High | This document exists partly to fix it: 7 workstreams with written ACs = executable without any single head. Flip `require_code_owner_review` when ≥2 engineers land their first WS |

---

## 7. Innovation Bets — Where We Lead, Not Follow

These are the directions that make Streamify * categorically different* rather than incrementally cheaper. Each builds exclusively on assets we already own.

### BET-1 · The Sonic Fingerprint — explainable taste, zero ML, zero cloud
**Thesis:** every streaming incumbent's moat is a server-side ML profile of you — opaque, non-portable, and monetized against you. Our moat is the opposite: a **fully-local acoustic embedding** (WS-D D1/D2) built from BS.1770-4 loudness, spectral bands, Camelot key, and waveform energy — the same primitives our mastering chain already uses — feeding an **explainable** ranker (D3/D4). "This song because: 96 BPM, A-minor → +1 Camelot from your evening slot, energy matches your run history."
- Why incumbents can't follow: their business model *is* the cloud profile. Ours forbids it (PHILOSOPHY #3, Privacy is a feature).
- First three tasks: D1, D2, D4. Ship behind a flag in Wave 3.

### BET-2 · The Blend Jam — group taste as a live, serverless protocol
**Thesis:** Spotify Blend is a playlist refreshed daily on their servers. Ours is a **live CRDT lane inside a Jam session** (D5): taste vectors union across the mesh, the queue re-ranks in near-real-time as people join/leave, every pick badged and explained. Nobody has shipped multi-user taste aggregation as a peer-to-peer protocol.
- Why we can: the 32-peer mesh, the CRDT queue, the blend scorer, and the chaos harness **already exist and are tested**. This is composition of our two crown jewels.
- Field pilot in Wave 4: three real parties, measured.

### BET-3 · Mesh Library — your catalog peer-to-peer
**Thesis:** `chunk_swarmer.rs` is a BitTorrent-discipline engine (rarest-first, endgame, BLAKE3-verified, resumable) currently used for jam chunks. Point it at *library sync* (BEHIND #43): two devices on a LAN converge their offline vaults with byte-range integrity, no server, no upload. Combined with the Connect gateway, "your music follows you" becomes literal.
- Tasks: extend swarm manifest to library items; `ElasticStorageAllocator` policy for vault budgets; LAN discovery reuse.

### BET-4 · Wrapped, always — the local listener ledger
**Thesis:** Wrapped is a marketing event; ours is a **living local artifact**. The Chronos profiler (circadian slots, satiation decay, co-occurrence, Markov chains — all already in the schema and Rust) renders a continuously-updated, exportable, share-card-able listener ledger. Zero telemetry upload; the insights and the data never leave the device.
- Tasks: stats surface over the existing `user_track_plays` local mirror; share cards via the existing `TrackShareCard`/`QrShareCard` patterns.

### BET-5 · The Sovereign Engine SDK — publish the contract
**Thesis:** we already maintain a golden-vector-verified cross-language contract (`streamify.udl`, dual-language jam wire freeze, golden audio vectors). Publish a stabilized "Streamify Engine SDK" — the UDL, the bindings, the VirtualHAL test double, and the self-test harness (A5) — so third parties can build clients (CLI, web, headless, car PCs) against the same engine. Open ecosystems compound; closed apps don't.
- Precondition: CONTRACT_VERSION discipline proven through A4's v2 bump. Tasks in Wave 4 after the data plane stabilizes.

---

## 8. Non-Goals

- No paywalls, ads, or telemetry profiling — ever (PHILOSOPHY #1–2).
- No on-device ML runtimes; BET-1's whole point is that DSP + statistics + explanation beats opaque embeddings for our use case.
- No server-side taste computation; Supabase remains auth/presence/lease/telemetry-opt-in only.
- No podcast/audiobook/video-platform expansion (BEHIND.md already stripped these; do not re-litigate).
- No new platforms (TV/console) before Waves 1–3 close; `media/sync` route classes land with C5 so the door stays open.

---

## 9. Appendix — Evidence Index

Every load-bearing claim in this document, with its source:

| Claim | Evidence |
|---|---|
| Engine was control-plane only; Seek/Next/Prev no-ops at `09a4e20b` | closed by #148: `rust/crates/streamify-contract/src/contract.rs` (`QueueLane`, `apply_media_command` transport arms) + `tests/test_uniffi_contract.rs` (4 transport tests); spec in `docs/architecture/engines/13_playback_dataplane.md` §4 |
| JNI audio-sinks bridge missing; header/wire/registry done | `native/include/streamify_audio_sinks.h` (96-byte layout, 7 symbols) vs `native/jni/` listing (2 files only) |
| WASAPI/PipeWire facades have no callers (CoreAudio's caller landed in WS-B) | `native/audio/wasapi_pull_adapter.cpp:535`, `pipewire_driver.cpp:606`; the CoreAudio caller: `iosApp/.../PlaybackViewModel.swift` → `CoreAudioRenderCore.startRustRing` → `coreaudio_pull_adapter.cpp` |
| 6 of 10 native suites CI-dead; WASAPI never compiled (CoreAudio suite revived in WS-B/C3) | `.github/workflows/_ci-engine.yml` runs dsp/simd/telemetry/coreaudio-render-core shards; no WIN32 CMake lane exists |
| iOS runtime is fixtures; 9/14 VMs orphaned — closed by WS-B (#155) | `FeatureLibraryLoader` + `UniFFIFeatureLibrary.swift` (lane switch), `RootShellView.swift` (engine queue truth, live search, engine play path); enforcement: `scripts/preview_catalog_guard.py` |
| No host Xcode project — closed by WS-B (#155) | `iosApp/Host/StreamifyHost.xcodeproj` (+ `project.yml`, widget extension, capabilities; CI "Host app build gate") |
| 7 open RLS tables; Titan poisoning path | `supabase/schema.sql:638-890` (`OR TRUE` ×4, `USING (TRUE)` inserts on co-occurrence/Markov/edge tables) |
| Credential rotations pending; release secrets empty | `SECURITY.md` verification table ("pending — requires dashboard access"); `release.yml` env has no secrets entered |
| Release pipeline never executed | only tag `v1.0.0` (2026-08-12) predates rewritten `release.yml` |
| Embedding consumed but never produced | `continuum_engine.rs:11` (sole mention); primitives at `streamify-audio/src/{lufs,dsp,waveform_indexer}.rs` |
| NeuroQueue/Continuum untested; sin-RNG | `neuro_queue.rs` (292 L), `continuum_engine.rs:109` — 0 tests, raw C-ABI |
| GroupBlendScorer tested, no product caller | `streamify-resolver/src/radio_scorer.rs` |
| LUFS implemented twice, divergent | `rust/.../lufs.rs` (f64, ±20 dB, golden) vs `native/dsp/LufsNormalizer.cc` (f32, ±12 dB) |
| C++ JNI panic shielding absent | 1 `catch(...)` in `native/jni/jni_bridge.cc` (1,799 LOC, 87 exports) vs 67 `catch_unwind` in `rust/.../jni_bridge.rs` |
| Two un-allowlisted InnerTube keys; gitleaks narrative false | `.gitleaks.toml` header vs `LyricsResolver.kt:36` (+8 constants across 3 scraper files) |
| Truth lint one-directional; drifted docs | `scripts/docs_ledger_check.py` `LEDGER_FILES`; `docs/playbooks/TODO.md` 12 dead paths; `A_Checkbox.md` unchecked-but-done; `supabase/supabase.md` §5/§6 |
| Resolver coverage outlier; empty tests dir | `crates/streamify-resolver/` — 19 tests / 6,153 src LOC; no `tests/` |
| PO-token absent | `app/.../data/network/YouTubeStreamResolver.kt` (signatureTimestamp + fleet only) |
| MP-CUT01/02 pending | `MULTIPLATFORM_CHECKBOX.MD` last two unchecked boxes |
| Version skew | `app/build.gradle.kts` (`1.1.0`) vs `desktop/build.gradle.kts:83` (`1.2.0`) |
| Wear half-delivered | `wear/` phone-side only; no `:wear` module in `settings.gradle.kts` |
| Fuzzers orphaned | `nightly-deep-matrix.yml:49` runs only `dsp_fuzzer_harness` |
| God-classes | `JamEngine.kt` 2,063 · `NativeBridge.kt` 1,616 · `StreamifyEngineBridge.kt` 1,588 · `PlayerViewModel.kt` 1,362 · `p2p_mesh.rs` 3,388 · `jni_bridge.rs` 1,864 · `jni_bridge.cc` 1,799 |

---

*This document is itself subject to the truth program: any PR that invalidates a claim in §1 or §9 must update AHEAD.md in the same commit. When a wave's gate passes, check it here with evidence — in both directions, as TRUTH-03 demands.*
