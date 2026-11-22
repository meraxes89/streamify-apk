Principal Architect Review: Streamify Multiplatform Plan v27.10

Bottom line: The invariants are right, but the plan has one factual error that will bite in Phase 3, several unimplementable-looking omissions, and a capacity model that will not deliver the stated scope. Below is a structured refinement with specific decisions.

---

1. Critical Corrections (must fix before Phase 3)

1.1 UniFFI Kotlin binding mode is wrong for Android

The diagram and text say "Direct Kotlin UniFFI Bindings (JNA)" for both Android and Desktop. This is incorrect:

· Android must use JNI mode, not JNA. JNA adds ~800KB and is a second JNI layer on top of the first — which directly contradicts the spirit of your own "No Double-Bridge" invariant. UniFFI's Android target emits JNI bindings directly. Performance-critical playback control (seek, EQ parameter changes, media commands) at JNA call latency is measurable.
· Desktop JVM can use JNA — memory is plentiful and call frequency is low.

Decision: Split the binding strategy in the build matrix: target.'cfg(target_os = "android")' → JNI; desktop JVM → JNA. Update the diagram to reflect two distinct binding artifacts (streamify-android.aar with JNI, streamify-jvm.jar with JNA).

1.2 The UDL is misrepresented as a runtime layer

In §3's mermaid, KBridge & SBridge --> Contract --> Layer2 implies the UDL is a runtime hop. It isn't. streamify.udl is a codegen input; the runtime path is Kotlin/Swift binding → FFI → Rust core. Fix the diagram (UDL goes above the bindings as a build-time arrow, not between them and the core) — otherwise reviewers will keep asking what the UDL runtime layer does.

1.3 Panic safety across FFI is unaddressed

[Throws=EngineError] handles Result::Err, not panic!. A panicking Rust function unwinding into Swift/Kotlin is undefined behavior and will manifest as silent data corruption or hard crashes with no diagnostics.

Decision:

· Add panic = "abort" to [profile.release] in Cargo.toml.
· Wrap every UniFFI-exported function in std::panic::catch_unwind, converting the payload to EngineError::Internal { message, backtrace: Option<String> }. UniFFI has a hook for this (uniffi::setup_scaffolding! + #[uniffi::export(callback_interface)]); use the uniffi 0.28+ panic-capture feature if available.
· Add a fuzz harness (cargo-fuzz) that passes garbage bytes across every FFI entrypoint and asserts no process abort.
· Add an "FFI panic -> EngineError::Internal" test to the CI matrix on all 5 platforms.

1.4 Sample-rate handling and SRC are completely absent

RenderClock { sample_rate: u32 } implies rate awareness, but §4 never says:

· What is the canonical rate? Device rate? Content rate? Both change.
· Where does SRC happen? The DSP (Layer 3) or the HAL (Layer 4)? This is the single biggest source of "it sounds different on platform X" bugs.
· What SRC algorithm? Linear interpolation is unacceptable for an audiophile client; you need polyphase sinc (rubato::SincFixedOut or FftFixedOut).
· What happens when the device switches from 44.1kHz to 48kHz mid-stream? (This happens every time a user plugs in a USB DAC, changes AirPlay targets, or moves between Bluetooth and wired.)

The "Golden-Vector Audio Verification" claim ("bit-exact DSP... across x86_64, aarch64, Apple Silicon") is only meaningful if the SRC path is identical, i.e., if you force a common internal rate and resample once at the boundary. If each HAL runs at the device rate, there is nothing bit-exact to compare.

Decision:

· Canonical internal rate: 48kHz f32 stereo, in the ring buffer and DSP. All content (44.1kHz, 88.2kHz, 96kHz, 192kHz) is resampled to 48kHz using rubato::SincFixedOut on the producer side (network/decode thread).
· Output SRC to device rate happens at the very last stage, in the HAL callback, using the same rubato config. This is the only place rate conversion occurs, and it is the single audited code path across all platforms.
· Device rate change: Tear down and rebuild the stream. Expose EngineEvent::AudioDeviceChanged { new_rate } on the control plane. Do not attempt seamless mid-stream rate change — every platform handles this badly.
· Golden vectors are generated and compared at 48kHz only. Cross-rate testing is a separate suite with tolerance-based comparison (not bit-exact).
· Audiophile escape hatch: expose a "device-native rate" setting that bypasses output SRC (content must already be at device rate, or playback fails with EngineError::SampleRateMismatch). This is table stakes for an audiophile client and must be in the contract from day one.

1.5 HTTP/3 is premature and will cost you

reqwest HTTP/3 is experimental (--features http3), pulls in quinn, requires QUIC-specific TLS config, and adds CI complexity for a benefit (connection migration, 0-RTT) that matters to maybe 2% of your users on lossy mobile networks.

Decision: Ship v1 with HTTP/2 + TLS 1.3 over TCP. Gate HTTP/3 behind a feature flag and revisit after v1.0. Removes quinn from the dependency graph and lets you focus on the audio path, which is where the actual risk lies.

---

2. Missing Architectural Decisions (must decide before Phase 4)

2.1 Threading, async, and the audio thread contract

"dedicated, non-real-time worker thread" + "reqwest + tokio" is not a threading model. Specify:

Concern Decision
Tokio runtime One multi-threaded runtime owned by Rust, initialized in OnceLock<Runtime>.
SQLite rusqlite + spawn_blocking on a dedicated 1-thread blocking pool (not tokio's default). Prevents DB contention from starving network I/O.
Audio thread std::thread (not tokio), pinned, SCHED_FIFO/THREAD_TIME_CONSTRAINT_POLICY/MMCSS. Owns no tokio handles. Handle::block_on is banned by #![deny] lint.
FFI async Contract uses async fn; UniFFI maps to suspend fun (Kotlin) and async func (Swift). Synchronous FFI entrypoints must complete in <1ms (config lookup, capability query). Everything else is async.
UI thread Banned from blocking FFI. Enforced by a debug_assert on the calling thread ID in the FFI shim.

2.2 Media command flow — the reverse direction is missing

EngineEventListener (core → UI) and MediaCommandSink (core → OS) are described. The OS → core path (lockscreen play/pause, headphone button, media key, CarPlay/Android Auto) is completely absent. This is half of the media session contract.

Decision: Add a MediaCommandSource trait and Engine::on_media_command(cmd: MediaCommand) entrypoint. Specify:

· Commands dispatched on the control plane (never the audio thread).
· Debounce rapid commands (Bluetooth headset double-tap = 50–150ms of toggling must coalesce into one state change). This is a real source of bugs on every music app.
· MediaCommand enum: Play, Pause, TogglePlayPause, Next, Previous, Seek { position_ms }, SetVolume { level }, Stop.

2.3 iOS AVAudioSession policy

AVAudioSession integration is mentioned in one line. For an iOS audiophile client, this is a multi-week problem:

· Category/mode: .playback (required for background) + .default mode. Do not use .mixWithOthers (breaks exclusive hi-res output on some DACs) unless there's a user setting.
· Interruptions: AVAudioSession.interruptionNotification → on .began, save playback state; on .ended with .shouldResume, resume. Must handle the case where the user manually paused before the interruption.
· Route changes: .oldDeviceUnavailable (headphone unplug) → pause. .newDeviceAvailable (Bluetooth connect) → do not auto-resume (user may have unplugged deliberately).
· MPNowPlayingInfoCenter: update rate-limited to 1Hz; every key change throttles the lock screen.
· AirPlay: AVAudioSession.currentRoute changes require stream teardown, not just a format change.

Decision: Add an IOSAudioSessionAdapter deliverable to Phase 5 with a state diagram covering all of the above. This is roughly one engineer-week of work that the current plan silently omits.

2.4 Error taxonomy

[Throws=EngineError] needs structure, not a flat string. Every variant must be:

· #[non_exhaustive] (forward compat)
· Send + Sync + 'static
· Nested (so Swift gets exhaustive switch, Kotlin gets when)
· Map to LocalizedError (Swift) / typed exception (Kotlin) with stable error_code for telemetry.

Decision: Define in the UDL as a [Error] enum:

```
EngineError::Network { kind: NetworkErrorKind, status: Option<u16>, retry_after: Option<u32> }
EngineError::Audio { kind: AudioErrorKind, device_id: Option<String> }
EngineError::Storage { kind: StorageErrorKind }
EngineError::Crypto { kind: CryptoErrorKind }
EngineError::Internal { message: String, backtrace: Option<String> }
```

No Io(String) catch-all. If you can't classify it, it's Internal and it's a bug.

2.5 Storage encryption and key model

"Encrypted SQLite" — but:

· Which crate? rusqlite + sqlcipher? sqlx + sqlcipher? App-layer AES-GCM per row?
· Where does the key come from, and is it synced? If each device has its own key, storage is device-local (fine). If storage syncs, the key must be user-derived (offline access requires local cache), which is a much bigger design.

Decision:

· Device-local storage only. Per-device key from the OS keychain (KeyStore / Keychain / DPAPI / Secret Service).
· Use rusqlite + sqlcipher with the key passed via PRAGMA key at connection open. Keys are never logged, never serialized.
· Cloud sync (Jam) does not sync the DB. It syncs CRDT operations over HTTPS. This eliminates the key-sharing problem entirely and is also the correct architecture for a CRDT.
· Schema versioning: PRAGMA user_version + a migration runner in Rust. Add a "downgrade refused" check (newer schema -> refuse to open with old binary, rather than silently corrupting).

2.6 FFI contract versioning

UniFFI does not guarantee ABI stability across versions. If a Swift app built against streamify_core_rs v1.2 links against v1.3, you can get silent memory corruption.

Decision:

· Embed a contract_version: u32 in the FFI. Bindings check at startup and fail fast with a clear error if incompatible.
· Semver policy: any change to streamify.udl (even adding a method) is a minor bump; removing/renaming is a major bump.
· CI maintains a compatibility matrix: current core + last two binding versions must pass the "hello world" integration test.

2.7 CI/CD matrix

Not mentioned anywhere. This alone is 1–2 engineer-weeks.

Decision:

```
Build targets: {aarch64, x86_64} × {linux-gnu, macos, windows-msvc}
             + {arm64-v8a, x86_64} × android
             + {arm64-device, arm64-sim, x86_64-sim} × ios
Test matrix:  linux-x86_64 (full, incl. VirtualHAL)
              macos-aarch64 (full, incl. VirtualHAL)
              windows-x86_64 (full, incl. VirtualHAL)
              android-emulator (smoke)
              ios-simulator (smoke)
```

· VirtualHAL enables headless audio testing on all three desktop CI runners — this is the payoff for building it.
· Physical-device audio testing is nightly on a small self-hosted runner fleet (1 Android device, 1 iOS device, 1 USB DAC rig).
· Publish versioned artifacts: streamify-core-{platform}-{arch}-{version}.{ext} plus generated bindings as a separate artifact. Each app build fetches the matching version.

---

3. Refinements to Existing Decisions

3.1 The "No Double-Bridge" invariant — reframe the why

The "dual garbage-collector memory contention" argument is technically weak. UniFFI Swift bindings use Arc with automatic deinit — no GC is involved on the Swift side. The real reasons to avoid Kotlin/Native on iOS are:

· Binary size: +10–20MB for the Kotlin/Native runtime.
· Build time: Kotlin/Native compiles are 3–5× slower than Swift.
· Debugging: LLDB can't step into Kotlin frames meaningfully.
· CMP on iOS maturity: still catching up to SwiftUI for platform integration.

Reframe as: "Native SwiftUI on iOS for smaller binaries, faster builds, first-class Apple tooling, and no Kotlin/Native runtime dependency. The UI cost (two codebases) is accepted and tracked."

3.2 RenderClock → DacPresentationClock, and define the timestamp semantics

RenderClock collides conceptually with rendering (visual). Rename. More importantly, specify:

· monotonic_ns must be the OS monotonic clock (CLOCK_MONOTONIC, mach_absolute_time, QueryPerformanceCounter).
· The timestamp must be the estimated DAC presentation time of the first sample in the callback, i.e., callback_time + driver_latency. This is the hard part:
  · AAudio: AAudioStream_getTimestamp
  · CoreAudio: mach_absolute_time + kAudioDevicePropertyLatency + kAudioStreamPropertyLatency
  · WASAPI: IAudioClock::GetPosition + IAudioClient::GetStreamLatency
  · PipeWire: pw_time (now + delay)
· Acceptance criterion: <5ms error vs. a loopback cable + oscilloscope reference, measured per platform. Put this in Phase 4 for each HAL, not as a "future improvement."

3.3 Flush protocol must be fully specified

"Consumer-cooperative flush" via atomic flags is correct in spirit but under-specified. The failure mode is a torn frame (producer writes while consumer is mid-read after flush ack).

Decision: Full protocol, documented in audio_sink_ringbuffer.h:

```
Producer flush:
  1. write flush_generation.fetch_add(1, Relaxed)
  2. spin until flush_ack_generation == flush_generation (with timeout -> error)
  3. reset write cursor
Consumer callback:
  1. if flush_generation != last_seen_generation:
       reset read cursor
       flush_ack_generation.store(flush_generation)
  2. proceed with normal read
```

Add loom tests for SPSC + flush interaction under rapid concurrent flush + produce + consume. This is the highest-risk concurrency code in the plan and needs formal verification, not just "looks correct."

3.4 Cache-line alignment — use cfg-dependent, not a fixed constant

The plan says 128-byte on Apple Silicon (correct) and align_val_t{64} on Windows (also correct for x86). But the code must pick the right constant per target, not hard-code one:

```rust
#[cfg(any(target_os = "macos", target_os = "ios"))]
pub const CACHE_LINE: usize = 128;
#[cfg(not(any(target_os = "macos", target_os = "ios")))]
pub const CACHE_LINE: usize = 64;
```

Also: producer indices and consumer indices must live in separate cache lines, not just be aligned. Pad each AtomicU64 to CACHE_LINE with #[repr(align(CACHE_LINE))].

3.5 VirtualHAL must simulate adversarial conditions

A null sink that emits periodic callbacks tests the happy path. Real hardware jitters, changes callback size, and disconnects. Extend VirtualHAL to support:

· Callback size variance (256/512/1024 in random sequence, matching CoreAudio's behavior).
· Timing jitter (±10% of period) to exercise the clock DLL.
· Injected underruns (skip a callback) and overruns (double callback) at configurable rates.
· Device disconnect/reconnect events.
· Rate change events.

Without these, VirtualHAL won't catch the clock-DLL and underrun-handling bugs that are the whole point of the exercise.

3.6 DSP SIMD strategy — biquads are serial, so what is SIMD'd?

"SIMD Physics" is vague. Be specific per block:

Block SIMD strategy
Biquad EQ (per channel) Not SIMD-able per sample (IIR). Vectorize across channels only (stereo → 2-wide, 5.1 → 8-wide with padding). Use std::simd or wide.
Limiter (look-ahead) Vectorizable per block.
K-weighted LUFS Vectorizable per block (sliding window sum).
State buffers Aligned to SIMD width (#[repr(align(32))] for AVX2, 16 for NEON).

Add to the contract: dsp_simd_width: u32 in EngineSnapshot so CI can verify SIMD is actually active on each target.

3.7 Jam CRDT — name the crate and define the compaction strategy

"Jam CRDT consensus" — which one? automerge (Rust port), yrs (Yjs in Rust), or custom? This choice determines:

· Document size (Automerge grows unbounded without compaction).
· Merge semantics (operation-based vs. state-based).
· Offline behavior.

Decision: Use automerge (well-supported, has Rust bindings). Add:

· Compaction: snapshot every 10k ops or 10MB, whichever first. Store as a fresh Automerge doc.
· Jam security: peers authenticate via a signed join token (issued by the jam host, verified by Ed25519). Rate-limit CRDT ops per peer (e.g., 100 ops/sec). Reject docs >10MB.
· Peer discovery: out of scope for this plan (assumes a signaling service exists). Document the assumption.

3.8 Desktop CMP binary size is a product decision, not a footnote

The plan targets "sub-100MB" for Android but says nothing about desktop. A CMP Desktop app with JVM + Skia is typically 150–300MB packaged (DMG/NSIS/AppImage). This is fine for a desktop app but will surprise stakeholders who saw the "sub-100MB" number.

Decision: Accept the size, but set an explicit budget (e.g., <250MB) and measure it in CI. If size becomes a product constraint later, alternatives are Kotlin/Native+CMP (less mature), Tauri (different tech stack), or egui/Slint (separate UI codebase) — all of which are major re-architectures, so decide now.

3.9 Android migration is completely unaddressed

The plan says Android is "A+ hardened" and then proposes a completely new architecture. Is the existing Android code rewritten? Feature-frozen? Coexisting?

Decision: Add to Phase 5:

· New Rust audio pipeline behind a feature flag (BuildConfig.USE_RUST_AUDIO), tested in parallel with legacy.
· Feature freeze on audio-related Android code from Phase 3 start until the flag flips.
· Cutover after 2-week dogfood + parity checklist (seek, EQ, gapless, background, media session, focus handling).
· Legacy code deleted 30 days after stable release.

3.10 Observability is missing entirely

No tracing, no metrics, no crash reporting, no profiling hooks. For a 5-platform client this is unacceptable — you will not be able to debug field issues on a user's Windows machine or a user's iOS device without structured logs.

Decision: Add to Phase 3:

· Logging: tracing with per-platform subscribers (android_logger / os_log / ETW / journald). Log levels controlled by a runtime setting.
· Metrics: metrics crate with per-platform exporters. Key metrics: playback start latency, underrun count, error rates by EngineError variant, FFI call latency p50/p99.
· Crash reporting: sentry (works on all 5 platforms; self-host or SaaS).
· Diagnostics snapshot: Engine::diagnostics() returning a redacted JSON blob for support tickets (no PII, no keys).

---

4. Capacity & Timeline — the plan is unrealistic as stated

Rough effort estimate for the current scope, 2 senior engineers, full-time, no unknowns:

Phase Alpha (Rust/Audio) Beta (UI/Capability)
Phase 3 (contract, ring buffer, build matrix) 5–6 wk 2 wk (contract review, build matrix support)
Phase 4 (VirtualHAL, 4 HALs, golden vectors) 10–14 wk 2 wk (VirtualHAL test harness)
Phase 5 (CMP shell, SwiftUI shell, 5 adapters) 3 wk (support) 12–16 wk
Total (parallel) ~18–23 wk ~16–20 wk

That's 4.5–6 months of full-time work for 2 engineers in the optimistic case, with the following caveats:

· One engineer cannot do 4 HALs alone. CoreAudio (iOS + macOS) is 4–6 weeks. WASAPI is 3–4 weeks. PipeWire is 2–3 weeks. AAudio is 1–2 weeks. That's 10–15 weeks of audio-driver work, which is the Alpha engineer's entire Phase 4 budget, leaving no time for the ring buffer, golden vectors, or build matrix. Phase 4 alone requires 1.5 FTE.
· SwiftUI from scratch is 6–8 weeks for a real audiophile client (library, search, EQ, settings, AirPlay, CarPlay). Beta engineer's 12–16 weeks is tight for the CMP shell + SwiftUI shell + 5 adapters.
· Add 30–50% for integration and debugging (they always exist), and you're looking at 7–9 months.

Decisions:

1. Add a third engineer (audio specialist) for Phase 4. If that's not possible, split Phase 4: Phase 4a = Android (AAudio) + iOS (CoreAudio) + VirtualHAL + golden vectors (ships Android+iOS parity first); Phase 4b = WASAPI + PipeWire (desktop later). This is the right call for a mobile-first product.
2. Define explicit milestones:
   · M1: Contract frozen, ring buffer + VirtualHAL pass CI on all 5 platforms.
   · M2: Android + iOS ship with the new Rust audio core (behind feature flag on Android).
   · M3: Desktop (CMP) Capacity & Timeline — the plan is unrealistic as stated

Rough effort estimate for the current scope, 2 senior engineers, full-time, no unknowns:

Phase Alpha (Rust/Audio) Beta (UI/Capability)
Phase 3 (contract, ring buffer, build matrix) 5–6 wk 2 wk (contract review, build matrix support)
Phase 4 (VirtualHAL, 4 HALs, golden vectors) 10–14 wk 2 wk (VirtualHAL test harness)
Phase 5 (CMP shell, SwiftUI shell, 5 adapters) 3 wk (support) 12–16 wk
Total (parallel) ~18–23 wk ~16–20 wk

That's 4.5–6 months of full-time work for 2 engineers in the optimistic case, with the following caveats:

· One engineer cannot do 4 HALs alone. CoreAudio (iOS + macOS) is 4–6 weeks. WASAPI is 3–4 weeks. PipeWire is 2–3 weeks. AAudio is 1–2 weeks. That's 10–15 weeks of audio-driver work, which is the Alpha engineer's entire Phase 4 budget, leaving no time for the ring buffer, golden vectors, or build matrix. Phase 4 alone requires 1.5 FTE.
· SwiftUI from scratch is 6–8 weeks for a real audiophile client (library, search, EQ, settings, AirPlay, CarPlay). Beta engineer's 12–16 weeks is tight for the CMP shell + SwiftUI shell + 5 adapters.
· Add 30–50% for integration and debugging (they always exist), and you're looking at 7–9 months.

Decisions:

1. Add a third engineer (audio specialist) for Phase 4. If that's not possible, split Phase 4: Phase 4a = Android (AAudio) + iOS (CoreAudio) + VirtualHAL + golden vectors (ships Android+iOS parity first); Phase 4b = WASAPI + PipeWire (desktop later). This is the right call for a mobile-first product.
2. Define explicit milestones:
   · M1: Contract frozen, ring buffer + VirtualHAL pass CI on all 5 platforms.
   · M2: Android + iOS ship with the new Rust audio core (behind feature flag on Android).
   · M3: Desktop (CMP) ships on macOS/Windows/Linux.
   · M4: All media-capability adapters (Media3, MPRemoteCommandCenter, SMTC, MPRIS) complete.
3. Do not commit to a single end date until M1 is complete and the HAL estimates have real data behind them.

---

5. Revised Phase Plan (delta from v27.10)

Phase 3 (revised)

· Contract expansion including MediaCommand input enum, DacPresentationClock, full EngineError taxonomy, contract_version, dsp_simd_width.
· JNI bindings for Android, JNA for desktop — build matrix split.
· Panic safety: catch_unwind + panic = "abort" + fuzz harness.
· SRC strategy documented and implemented on producer side (48kHz internal).
· Flush protocol documented + loom tests.
· Observability: tracing + metrics + Sentry integration.
· Contract versioning + compat matrix in CI.
· Full CI matrix (13–15 build targets, 5 test targets, VirtualHAL on 3 desktop runners).

Phase 4a (new, ships Android+iOS first)

· VirtualHAL with jitter/rate-change/disconnect simulation.
· AAudio (Android) + CoreAudio (iOS+macOS) HALs.
· Golden vectors at 48kHz, tolerance-based for cross-rate.
· Timestamp latency estimation acceptance criteria per HAL.

Phase 4b (desktop)

· WASAPI + PipeWire.
· Desktop-specific VirtualHAL tests.
· Desktop size budget measured in CI.

Phase 5 (revised)

· CMP shell + SwiftUI shell (reframed rationale: binary size + build time + tooling, not GC).
· iOS AVAudioSession adapter with full state diagram (new deliverable).
· MediaCommandSource for OS → core command flow (new deliverable).
· Platform capability adapters (Media3, MPRemoteCommandCenter, SMTC, MPRIS).
· Android migration plan with feature flag + cutover checklist.

Phase 6 (new, post-v1)

· Audio process isolation on desktop (shared-memory ring buffer + control-plane socket).
· HTTP/3 feature flag evaluation.
· Jam security hardening (signed join tokens, rate limiting, compaction).

---

6. Summary of Decisions to Lock In

# Decision Impact
1 Android uses JNI, Desktop uses JNA Correctness + performance
2 panic = "abort" + catch_unwind at every FFI boundary + fuzz harness Prevents UB, produces diagnosable crashes
3 Internal 48kHz, single SRC path at producer, rate change = stream teardown Enables "bit-exact" golden vectors; fixes the biggest audio quality risk
4 Device-local encrypted SQLite; CRDT (not DB) syncs over network Eliminates key-sharing problem
5 MediaCommandSource + debounce for OS → core media commands Closes a missing half of the media contract
6 Full iOS AVAudioSession adapter (category, interruptions, route changes, rate limits) ~1 week of work currently missing
7 Structured EngineError enum, #[non_exhaustive], no string catch-alls Telemetry + exhaustive Swift/Kotlin switches
8 Contract versioning with fail-fast check + CI compat matrix Prevents silent ABI corruption
9 Full CI matrix + VirtualHAL on all 3 desktop runners Makes headless audio CI possible
10 tracing + metrics + Sentry from Phase 3 Only way to debug field issues on 5 platforms
11 Split Phase 4 into 4a (mobile) and 4b (desktop); add 1 audio engineer or reduce scope The 2-engineer plan cannot deliver 4 HALs on schedule
12 HTTP/2 + TLS 1.3 only for v1; HTTP/3 behind a flag Removes quinn complexity
13 Desktop size budget measured in CI Avoids stakeholder surprise vs. the "sub-100MB" story
14 Android feature-flag migration with explicit cutover checklist De-risks the biggest existing codebase
15 Reframe "No Double-Bridge": binary size + build time + tooling, not "GC contention" Accurate rationale survives technical review

The architecture's core invariants — sovereign Rust core, hardware-clock-as-master, push state, f32 SPSC, capability ports — are sound. The plan's risk is in the details it omits, not the direction it sets. The items above are the ones I'd want resolved in writing before a single line of Phase 3 code is merged.
