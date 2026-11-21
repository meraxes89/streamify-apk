# Streamify Desktop (Phase 5 — MP-UI04)

Desktop JVM target of the Streamify multiplatform roadmap
(`docs/architecture/MULTIPLATFORM_PLAN_27_10.MD`, Phase 4b + Phase 5 / Beta
scope): the **responsive Compose Multiplatform shell** over the JNA-mode
engine lane, plus the retained Phase 4b responsibilities — CI packaging on
the Windows/Linux matrix and the **< 250 MiB distribution size budget**
enforcement.

## What lives here (and what does not)

| Concern | Owner | Where |
|---|---|---|
| Desktop CI matrix (ubuntu + windows) | this module | `.github/workflows/desktop-ci.yml` |
| Responsive CMP shell (rail, frame, tray, hotkeys) | this module | `src/main/.../shell/` |
| Shared UI components / theme / state holder | `:shared` (Phase 5, MP-UI03) | `shared/src/commonMain` |
| Desktop engine gateway (`DesktopEnginePort`) | `:shared` | `shared/src/desktopEngine` |
| UniFFI JNA binding generation + 0.28.3 shim | `:shared` (relocated in Phase 5) | `shared/build.gradle.kts` |
| JNA engine binding smoke test | this module | `src/test/.../DesktopJnaEngineSmokeTest.kt` |
| Headless shell UI smoke tests | this module | `src/test/.../DesktopShellUiSmokeTest.kt` |
| Size budget gate (`verifyDesktopSizeBudget`) | this module | `desktop/build.gradle.kts` |
| Asset hygiene gate (`verifyDesktopNativeHygiene`) | this module | `desktop/build.gradle.kts` |
| WASAPI / PipeWire HAL drivers | Alpha (Phase 4b, MP-HAL08/09) | `rust/**`, `native/**` |
| Windows engine compilability (`task_orchestrator.rs` cfg gating) | Alpha (follow-up, see below) | `rust/src/task_orchestrator.rs` |

**Windows legs are probe-gated**: `rust/src/task_orchestrator.rs` imports
Linux-only libc APIs (`cpu_set_t`, `sched_setaffinity`, `CPU_SET`, `CPU_ZERO`,
`setpriority`, `PRIO_PROCESS`) with no cfg gate, and `rust/src/lib.rs` compiles
the module on every target — so the engine cdylib (and even the bindgen, which
compiles the package lib) cannot build on Windows today. Each Windows CI leg
runs a cheap `cargo check --lib` probe first: while the blocker stands the leg
reports GREEN with a loud blocker notice; the moment the rust tree compiles on
Windows, the full smoke/packaging suite activates automatically. The durable
fix (`#[cfg(target_os = "linux")]` on the module + a no-op stub elsewhere —
its only caller is the Android-gated `jni_bridge.rs`) is Alpha's to land.

## The Phase 5 shell (MP-UI04)

```
Main.kt                      ← pins the UniFFI libraryOverride, hands off
shell/DesktopShellApp.kt     ← composition root: window + tray + engine lane
shell/DesktopShell.kt        ← adaptive rail + content panes + mini player dock
shell/WindowFrame.kt         ← custom undecorated title bar (drag / min / max / close)
shell/SystemTraySupport.kt   ← tray menu: Play/Pause, Next, Previous, Mute, Show/Hide, Quit
shell/KeyboardShortcuts.kt   ← Space toggle · ←/→ seek ±10s · ↑/↓ volume ±5% ·
                               Ctrl+←/→ skip · Ctrl+F search (pure map + TransportSteps)
shell/ShellNavModel.kt       ← adaptive rail breakpoints (pure) — 5 destinations
                               (Home · Search · Library · Jam Sessions · Settings)
shell/AppIcon.kt             ← runtime-drawn brand icon (zero binary assets)
engine/EnginePortLoader.kt   ← reflective engine gateway load (hermetic-safe)
```

* **Adaptive layout**: `BoxWithConstraints` resolves the rail per width band
  (≥ 1000 dp expanded, below icon-only); the hamburger toggles manually with
  band-crossing reset semantics (see `ShellNavModel` + smoke tests).
* **Engine state**: every rendered value — status, volume, DAC-clock position,
  buffer health — arrives via the push flow (`PlaybackController.state`);
  playback position derives strictly from the hardware DAC presentation clock
  (MP-DEC-03); zero polling (MP-DEC-04). The shell's MAIN source set carries
  zero `uniffi.*` imports: the gateway loads reflectively
  (`EnginePortLoader`), so hermetic hosts still compile and run the module.
* **Keyboard shortcuts** dispatch from a bubble-phase `onKeyEvent`, so focused
  text entry (the search field) keeps its own keys — typing a space inserts a
  character; Ctrl+Left moves the caret. The full directive map: Space =
  play/pause, bare ←/→ = seek ±10 s, bare ↑/↓ = volume ±5%, Ctrl+→/← =
  next/previous, Ctrl+F = focus search. Seek/volume nudges base off the
  ENGINE-AUTHORITATIVE pushed position/volume (the smoothed playhead is
  render-only) and clamp to track bounds (`TransportSteps`, pinned by the
  smoke suite).
* **Now-playing surface**: the Home pane renders the shared `PlaybackSeekbar`
  (drag-to-scrub, commit-on-release) over the DAC-derived position with the
  shared playhead guards (`rememberDacSmoothedPosition`), plus the shared
  `QueueList` (honest empty state until the catalog/storage contract ships
  the queue surface — the Android host lowers its Media3 queue onto the SAME
  component). The Settings pane surfaces the engine lane status, the
  shortcut reference and the shell behavior cards.
* **System tray** actions route through the same `PlaybackController` command
  plane as the in-app transport and the shortcuts — tray and window can never
  disagree. Mute is a round trip: command out, pushed snapshot back.

## Binding mode (MP-DEC-02)

The desktop JVM reaches `streamify_core_rs` through UniFFI's **JNA backend**:

```
CMP shell (:desktop)
  └── shared presentation layer (:shared)
        └── uniffi.streamify.*  (generated Kotlin proxies — :shared compiles them,
                                  package pinned by rust/uniffi.toml)
              └── com.sun.jna → Native.load("streamify_core_rs")
                    └── streamify_core_rs.dll / libstreamify_core_rs.so  (uniffi_* C ABI)
```

The Android direct-JNI lane does not exist here: `jni` is cfg-gated to
`target_os = "android"` in `rust/Cargo.toml` (MP-C13), so the desktop cdylib
exports no `Java_*` symbols at all.

### Library name pin (important)

UDL-mode uniffi codegen derives the library name from the namespace fallback
(`uniffi_streamify`) because the bindgen CLI is not given the cdylib path. The
shipped library is `streamify_core_rs`. The generated loader reads
`uniffi.component.streamify.libraryOverride` before every `Native.load`, and
the desktop lane pins it in two places:

* `Main.kt` — first statement of `main()`, before any FFI touch;
* the test JVM (`tasks.test { systemProperty(...) }`).

The durable fix — adding `cdylib_name = "streamify_core_rs"` under
`[bindings.kotlin]` in `rust/uniffi.toml` — belongs to the `rust/**` subtree
(Alpha) and is flagged for follow-up. The same latent mismatch exists for the
Android module's generated proxies.

### uniffi-rs 0.28.3 codegen shim

The Kotlin backend renames error types (`EngineError` → `EngineException`)
and, for non-flat error variants, emits **both** a constructor property and an
`override val message` formatted-getter for every field literally named
`message`. The UDL's `EngineError.Internal(message, backtrace)` hits exactly
that, and the generated `streamify.kt` does not compile as emitted
(conflicting `message` declarations + a self-recursive getter). The Phase 4b
desktop lane was the first lane anywhere to actually compile the generated
bindings, so the defect surfaced there first.

Since `rust/**` is Alpha's subtree, the lane lowers the RAW codegen through a
one-token rename before compilation: every backticked `` `message` `` field
reference becomes `` `errorMessage` `` (only the `Internal` variant carries a
`message` field, audited against the whole UDL). In Phase 5 the shim moved to
`shared/build.gradle.kts` (`patchSharedUniffiBindings`) together with the
binding generation (`generateSharedUniffiBindings`) — the proxies now compile
exactly once per desktop lane, inside `:shared`. The durable fix — renaming
the UDL field, or pinning a codegen release that stops shadowing
`kotlin.Throwable.message` — is flagged in the PR description. The shim task
fails loudly if the generated error surface ever drifts.

## Gradle tasks

| Task | Purpose |
|---|---|
| `:shared:generateSharedUniffiBindings` | `cargo run --bin uniffi-bindgen -- generate rust/src/streamify.udl --language kotlin` into `shared/build/generated/uniffi/kotlin-raw` |
| `:shared:patchSharedUniffiBindings` | uniffi 0.28.3 shim: renames the Throwable-shadowing `` Internal(`message`) `` field to `` `errorMessage` `` in the raw codegen, lowering it into `shared/build/generated/uniffi/kotlin` (the compiled srcDir) |
| `:shared:desktopTest` | shared presentation-layer logic suite (pure JVM) |
| `buildDesktopEngineLibrary` | `cargo build --release --lib` (host target; release profile is `strip = true`) |
| `prepareDesktopEngineResources` | stages the single host-arch cdylib at the resource root (JNA classpath loading; single-arch by construction) |
| `verifyDesktopNativeHygiene` | gate: exactly one host-arch engine lib, no debug-symbol files, no test fixtures, no Android-ABI artifacts |
| `verifyDesktopSizeBudget` | MP-HAL10 gate: every installer + the jpackage app image must be **strictly < 250 MiB** (262,144,000 bytes); prints a per-artifact report and a top-20 offender breakdown on violation |
| `packageDistributionForCurrentOS` | jpackage pass (Deb + Msi) — finalized by the size-budget gate |

## Toolchain requirements

* JDK 17 (CI uses temurin 17) — compilation targets JVM 17.
* Rust stable with the **host** target (Linux: `x86_64-unknown-linux-gnu`,
  Windows: `x86_64-pc-windows-msvc`) for the engine library and the UniFFI
  bindings. Without cargo, the binding/native tasks skip (`:shared` detaches
  its `src/desktopEngine` too) and the smoke test degrades to a JUnit
  `assumeTrue` skip; `:desktop:compileKotlin` (engine-free shell) still works.
* Windows MSI packaging additionally needs WiX Toolset 3.x (the CI workflow
  installs it via chocolatey and exports `WIX`).

## Local quickstart

`--configure-on-demand` matters on hosts without an Android SDK: the desktop
lane only configures `:desktop` + `:shared` (both AGP-free), never the
`:app`/`:shared-android` projects.

```bash
# Lightweight (works without cargo and without an Android SDK):
./gradlew :desktop:compileKotlin --no-daemon --configure-on-demand

# Full JNA smoke + headless shell UI smoke + shared logic suite (needs cargo):
./gradlew :desktop:test :shared:desktopTest --no-daemon --configure-on-demand

# Package + size audit (needs cargo; WiX on Windows):
./gradlew :desktop:packageDistributionForCurrentOS --no-daemon --configure-on-demand

# Hermetic zero-toolchain pass (bindings/native tasks skip; the uniffi smoke
# suite lives in src/testEngine and is registered ONLY when the engine lane
# is live — the shell + shared suites stay pure and KEEP RUNNING):
./gradlew :desktop:test :shared:desktopTest --no-daemon --configure-on-demand \
    -Pstreamify.skipRust=true
```

## Distribution budget

Measured on the Phase 4b matrix: Linux `.deb` installer ≈ 51 MiB (~20% of the
250 MiB ceiling), stripped native core `libstreamify_core_rs.so` ≈ 8 MB. The
Phase 5 shell adds only shared Compose classes and the runtime-drawn icon —
no binary assets, no additional native slices — keeping the distribution
well inside the MP-HAL10 budget (`verifyDesktopSizeBudget` enforces it on
every packaging pass, and `verifyDesktopNativeHygiene` guards the
single-arch/no-symbol/no-fixture invariants).
