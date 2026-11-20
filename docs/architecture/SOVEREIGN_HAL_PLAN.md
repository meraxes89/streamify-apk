# SOVEREIGN HAL — The Canonical Program Spec (Phase 0 → Phase 3)

> **One engine, every speaker — shipped behind a flag, verified by OS loopback, immune to upstream.**
> Status: **Phase 0 (branch lock, immunity seams, 32-bit ring hardening, CI gates)** — this document.
> Branch strategy: all phases land via PRs into the long-lived `feat/sovereign-hal` integration branch.

| | |
|---|---|
| **Integration branch** | `feat/sovereign-hal` (long-lived; created at `38f87d69`) |
| **Phase 0 PR branch** | `feat/sovereign-hal-phase0` → targets `feat/sovereign-hal` (NEVER `main`) |
| **CI** | `.github/workflows/_ci-sovereign-hal.yml` — 4 gates, 9 jobs, triggered on `feat/sovereign-hal**` only |
| **Upstream docs** | `docs/architecture/engines/13_playback_dataplane.md` (the drivetrain RFC), `docs/playbooks/ANDROID_RUST_AUDIO_CUTOVER.md` (C2) |

---

## 1. The Branch Strategy & the Strict Zero-Deletion Policy

### 1.1 The integration branch

All Sovereign HAL work (Phase 0 → Phase 3) lands as PRs into `feat/sovereign-hal`.
**Nothing merges into `main` until actual OS-level audio output is proven** via real
hardware/virtual loopback capture across all 5 targets (§4). While other engineers ship
features on `main` at velocity, the HAL program never blocks them and is never blocked
by them: `feat/sovereign-hal` rebases/merges `main` on a regular cadence (the upstream
ritual), and every HAL PR runs the dedicated `_ci-sovereign-hal.yml` ladder in addition
to the standard hub.

### 1.2 Zero-deletion policy (the prime directive)

`media3` (Android), `libmpv` (desktop) and `HTMLAudioElement` (web) **remain intact as
automatic fallbacks inside `AudioRoutePolicy` for the entire program**. They are deleted
only in a separate hardening PR *after* `feat/sovereign-hal` merges to `main` AND the
live device soak passes. Until then:

- Every fallback path stays shippable at any commit (the zero-crash ladder, §3.2).
- The legacy engines' wiring is never edited destructively — the Sovereign lane WRAPS,
  it never replaces (the Koin seam, §3).
- A rollback at any point is "flip the flag off" — nothing else.

### 1.3 Merge-to-main checklist (the Phase 3 exit gate)

1. All 5 loopback targets green in `_ci-sovereign-hal.yml` (real captures, not scaffold
   warnings) for 5 consecutive nightly runs.
2. The fleet dogfood report (the C2 telemetry contract) published with the go thresholds
   met.
3. A rebase over `main` clean within one working day (the immunity fire drill, §5.3).

---

## 2. Phase Map

| Phase | Scope | Exit criterion |
|---|---|---|
| **0 — this PR** | Branch lock; 3-layer upstream immunity; armv7-a ring ABI hardening; the 4-gate CI ladder; the 5-platform loopback scaffold | All `_ci-sovereign-hal.yml` gates green on `feat/sovereign-hal-phase0` |
| **1 — Native brackets** | The engine-side relay (C5's missing half) on Windows/Linux; the Android AAudio bracket over the frozen JNI bridge; real loopback captures replacing the Phase 0 scaffold warnings | Real OS loopback captures verified by the shared verifier on Windows/Linux/Android |
| **2 — Web bracket** | The wasm32 producer port; the AudioWorklet SAB + MessagePort brackets (the harness's transports, wired to the real producer); Ogg-Opus framing (§6) | Browser loopback green in both transport modes; HTMLAudioElement stays the fallback |
| **3 — Cutover** | Per-platform flag flips after soak; the merge-to-main checklist; the separate deletion PR | §1.3 |

---

## 3. The 3-Layer Upstream-Immunity Contract

The Sovereign HAL consumes upstream SimpMusic through exactly three seams, each with a
mechanical gate. Everything else is ours, in `streamify-modules/`.

### Layer 1 — `core.patch` stays platform-enablement only

The patch against the `core` submodule is the ONLY place where an upstream bump forces a
textual conflict, so its surface is frozen by `scripts/check_core_patch.py` (wired into
`ci.yml` preflight AND `_ci-sovereign-hal.yml` Gate 1):

- **Allowlist** (recorded in the script, byte-reviewed): the wasmJs Gradle targets, the
  wasmJsMain `expect/actual` shims, the `Resource.kt` Dispatchers parity fix, and the
  four verified upstream quality/security fixes (`DatabaseDao` rename, `DatabaseModule`
  indexes + DI timing, `RepositoryModule` timing, `Ytmusic` log redaction).
- **Hard deny**: any hunk touching Media3/ExoPlayer internals, media-jvm/libmpv routing,
  audio sink/processor behavior, `MediaPlayerHandler` implementations, or playback
  control semantics — including tokens smuggled inside allowed files (the body scan).
- **Default deny**: everything else. Growing the allowlist is a deliberate, documented
  edit in the same PR (the friction is the feature).
- **Hygiene**: the patch must apply to the checked-out submodule (`git apply --check`).

### Layer 2 — the `SovereignMediaPlayerHandler` Koin seam (behavior lives outside)

The single canonical injection boundary: `streamify-modules/sovereign-player/` wraps the
platform's registered `MediaPlayerHandler` (the small, slow-moving upstream interface —
60+ members delegated via Kotlin interface delegation, a curated override set routed by
the flag). Mechanics:

- `installSovereignMediaPlayerHandler()` runs AFTER the platform handler module loads
  (Android `SimpMusicApplication`, desktop `DesktopApp`, web `initWebKoin`), captures
  the current binding under the `LegacyMediaPlayerHandler` qualifier and rebinds the
  interface to the wrapper. Idempotent (no wrapper stacking).
- **Flag-off (the shipped default)**: 1:1 delegation — byte-identical legacy behavior.
- **Flag-on + healthy probe**: playback control routes to the `SovereignPlaybackLane`
  (Phase 1/2 mount the real brackets; Phase 0's lane is the documented no-op).
- **Any failure** degrades to the delegate on the NEXT command (the zero-crash ladder).
- Upstream interface changes surface as compile errors in exactly ONE module — never
  merge conflicts across the tree.

### Layer 3 — the frozen ring ABI (4 + 7 symbols, compile-time pinned)

The consumer edge is frozen and now compile-time pinned on BOTH sides:

- `rust/crates/streamify-playback/src/ring_export.rs` — the 4 C symbols
  (`streamify_ring_read_f32`, `…_occupancy_frames`, `…_channels`, `…_stats_wire`),
  hardened in Phase 0 with checked-multiply guards on the 32-bit `usize` path.
- `native/include/streamify_audio_sinks.h` — the 7 JNI symbols + the 96-byte stats
  wire (magic `0x51AB0001`), pinned by `static_assert`s in
  `native/tests/test_ring_abi_conformance.cc` and `const _: () = assert!(...)` in
  `sink.rs` (offsets, sizes, cacheline alignment, and the
  `target_has_atomic = "64"` lock-freedom guard that fires at build time on any
  target lacking native 64-bit atomics).
- Any ABI change is additive-only with a layout-version bump, per the RFC §6.3 freeze.

---

## 4. The 5-Platform OS-Loopback Verification Matrix

Nothing merges to `main` until real OS audio output is captured and verified on all five
targets. The shared machinery (Phase 0 deliverables):

- **Producer**: `sovereign-loopback-smoke` (the synthetic ring producer binary — 440 Hz +
  880 Hz dual tone, 48 kHz f32, real-time pacing, its own underrun/overrun verdict).
- **Verifier**: `scripts/sovereign_loopback_verifier.py` (Hann-windowed radix-2 FFT,
  both-tone presence, noise-floor ratio, the silence negative control; self-tested).
- **Ladder**: `_ci-sovereign-hal.yml` Gate 4 — five jobs, one per target.

| # | Target | Capture pipeline | Phase 0 state |
|---|---|---|---|
| 4.1 | **Windows** | `wasapi_pull_adapter.cpp` → `IAudioCaptureClient` loopback | Scaffold: producer self-verdict green; the capture leg mounts in Phase 1 (reported warning, never a silent skip) |
| 4.2 | **macOS** | `CoreAudioRenderCore` → BlackHole-2ch | Scaffold + BlackHole presence probe |
| 4.3 | **Linux** | `pipewire_driver.cpp` → headless PipeWire `module-null-sink` monitor | Scaffold: null-sink loaded when a daemon runs |
| 4.4 | **Android** | QEMU `-audiodev wav` capturing real AudioFlinger mixer output — **APIs 26, 29, 34, 35** (`x86_64` emulator), with the `armeabi-v7a` lane carried by Gate 2b (QEMU user-mode cross-test of the ring/DSP suites) | Scaffold: emulator boots + wav backend armed on every API level |
| 4.5 | **Browser** | `AudioWorklet` bracket — **dual mode** (below) | Live: the in-page harness runs both transport modes under Playwright Chromium |

### 4.1 The browser dual-mode specification (Phase 2's contract, frozen now)

The browser bracket must work in BOTH transport modes, because the LAN deployment shape
(`http://192.168.1.39:3000`) is `isSecureContext === false`:

1. **SharedArrayBuffer mode** (`crossOriginIsolated === true` — the server sets
   COOP/COEP): the producer (Phase 2: the wasm32 engine producer) writes into a
   SAB-backed SPSC ring; the worklet's pull callback reads through the SAME
   release/acquire cursor protocol as the frozen C ABI. The harness proves this mode
   against an origin that sets the headers.
2. **MessagePort mode** (the every-browser fallback): zero-copy **transferable
   `Float32Array` chunks** posted to the worklet, staged into a lock-free queue the
   pull callback drains. This mode MUST pass on the plain-HTTP origin — it is the
   contract that makes the bracket deployable on any LAN.
3. **Verification** is in-page: a post-worklet `AnalyserNode` FFT (the same
   440 Hz + 880 Hz + zero-underrun contract as the file verifier), polled by Playwright
   (`window.__sovereignHalResult`); a skipped SAB mode (headers not effective) is
   REPORTED, and a failed MessagePort mode is a hard FAIL.
4. **HTMLAudioElement stays** the automatic fallback lane for browsers without
   AudioWorklet — the zero-deletion policy applies to the web plane too.

---

## 5. Ogg-Opus Container Framing (Phase 2/3 note, per the program directive)

`server/src/slices/stream/ebml-opus-extractor.js` (#225) demuxes Opus out of webm
server-side. Phase 2 pairs it with lightweight streaming **Ogg-Opus framing** so BOTH
Rust (`symphonia`'s Ogg demuxer + an Opus decoder behind the `TrackDecoder` trait — the
reversible decision the RFC §5.2 recorded) and the `HTMLAudioElement` fallback (native
Ogg-Opus in every modern browser) speak a standard container. The framing lands
server-side only; no client format fork — the resolver's m4a/AAC priority stays the
default lane until the Opus path passes the same golden-vector battery.

---

## 6. The Upstream Fire Drill (monthly, from Phase 1)

A dry-run merge of upstream `dev` into a throwaway branch off `feat/sovereign-hal`:
submodule bump → `core.patch` re-apply → `check_core_patch.py` →
`jni_parity_check.py --source` → compile `:streamify-sovereign-player`. If the drill
exceeds 30 minutes of human attention, that is a defect in a seam — filed and fixed as
a P1, not tolerated as process cost.

---

## 7. Phase 0 Evidence Index

| Claim | Evidence |
|---|---|
| D1 linter enforces the frozen allowlist | `scripts/check_core_patch.py` + the 7-case battery `scripts/check_core_patch_test.py` (both green locally; wired into `ci.yml` preflight + Gate 1) |
| The wrapper delegates 1:1 flag-off, routes flag-on, degrades on probe failure | `streamify-modules/sovereign-player/` + `SovereignPlayerTest.kt` (11 tests: the route ladder ×4, the wrapper's routing/fallback/release/player-surface contract ×5, the monitor ×2) |
| The 96-byte wire pinned at compile time, both sides | `sink.rs` const-assert block; `test_ring_abi_conformance.cc` static_assert block |
| 32-bit safety: lock-free u64 cursors + checked slice math | `sink.rs` `target_has_atomic` guard; `ring_export.rs` checked-multiply; `test_ring_abi_conformance.rs` `usize::MAX` case |
| Ring conformance + zero-alloc on the exported edge | `rust/.../tests/test_ring_abi_conformance.rs` (6/6 green locally) + the C++ twin (ALL PASSED on host g++) |
| The armv7-a audit answer | §7 below + Gate 2b (QEMU user-mode cross-test, `-march=armv7-a+neon`) |
| The loopback verifier is not vacuous | `scripts/sovereign_loopback_verifier_test.py` — the silence control FAILS signal mode |
| The browser dual-mode contract | `scripts/sovereign_hal_browser_harness.html` (node-syntax-checked; runs live in Gate 4.5) |
| The 4-gate ladder | `.github/workflows/_ci-sovereign-hal.yml` (9 jobs, YAML-validated) |

---

## 8. The armv7-a 32-Bit Ring Audit — Findings (Phase 0)

The audit's compile-time half shipped in this PR; the runtime half runs in Gate 2b.
Findings against the frozen ABI:

1. **Cacheline alignment is already target-correct on the C++ side.**
   `audio_sink_ringbuffer.h` uses `alignas(kCacheLineSize)` with the Apple-Silicon
   128-byte / everything-else 64-byte gate — NOT `std::hardware_destructive_interference_size`
   (which MSVC/clang disagree on and GCC reports as 64 even where the hardware line is
   128). The Rust twin uses `#[repr(align(64))] CacheLine<T>`. **No change needed**;
   both sides now pin it at compile time (static_assert / const assert ≥ 64).
2. **The u64 cursor protocol is lock-free on armv7-a by construction, now enforced.**
   armv7-a with NEON (the only Phase 0 v7a target) implies LDREXD/STREXD 64-bit atomics;
   the new `compile_error!` guard on `target_has_atomic = "64"` makes a misconfigured
   toolchain (e.g. v7 without NEON, or a future `-Z build-std` fallback) a BUILD
   failure rather than a hidden-lock RT violation. The C++ twin pins
   `std::atomic<uint64_t>::is_always_lock_free` the same way.
3. **One real 32-bit hazard found and fixed: the C edge's slice-length math.**
   `streamify_ring_read_f32` computed `max_frames * channels` as a bare `usize`
   multiply — on ILP32 with a hostile `max_frames > 2^30` this wraps and constructs a
   wildly wrong slice. Now a checked multiply + `isize::MAX` guard (silence, never UB);
   pinned by the `usize::MAX` case in the conformance suite.
4. **The wire itself is 32-bit safe by construction**: the 96-byte stats image is
   fixed-offset little-endian u32/u64/f32 — no `size_t` on the wire, no pointer-width
   fields. The capacity bound (`1 << 22` frames × 2 ch × 4 B = 32 MiB max slot array)
   fits `u32` and ILP32 `size_t`; pinned by const asserts both sides.
5. **The remaining runtime unknown** (Gate 2b's job): actual QEMU-emulated ILP32
   execution of the conformance suites — cursor arithmetic (`h - t` on wrapped u64),
   the `AtomicU64` codegen under NEON, and the AllocGuard/new-override behavior on
   32-bit glibc. The suite runs single-threaded under QEMU (`--test-threads=1`) to
   keep emulation deterministic; any failure there is a P0 finding for Phase 1.

---

## 9. What Phase 0 deliberately does NOT do

- No flag flips: `SovereignHalFlags.DEFAULT_ENABLED = false` everywhere (the panel
  toggle is developer-only).
- No bracket wiring: the probe reports "not wired", the lane is the documented no-op,
  the loopback capture legs report themselves as scaffolded — loudly, never silently.
- No `main` changes beyond the D1 preflight gate and the module wiring (all additive;
  zero deletions; legacy lanes byte-identical).
- No UDL / UniFFI surface changes: `CONTRACT_VERSION` stays v2 (the ABI freeze holds).
