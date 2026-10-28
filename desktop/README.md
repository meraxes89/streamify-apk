# Streamify Desktop (Phase 4b — MP-HAL10)

Desktop JVM target of the Streamify multiplatform roadmap
(`docs/architecture/MULTIPLATFORM_PLAN_27_10.MD`, Phase 4b / Beta scope):
the JNA-mode engine lane, CI packaging on the Windows/Linux matrix, and the
**< 250 MiB distribution size budget** enforcement.

## What lives here (and what does not)

| Concern | Owner | Where |
|---|---|---|
| Desktop CI matrix (ubuntu + windows) | this module | `.github/workflows/desktop-ci.yml` |
| JNA engine binding smoke test | this module | `src/test/.../DesktopJnaEngineSmokeTest.kt` |
| Size budget gate (`verifyDesktopSizeBudget`) | this module | `desktop/build.gradle.kts` |
| Asset hygiene gate (`verifyDesktopNativeHygiene`) | this module | `desktop/build.gradle.kts` |
| WASAPI / PipeWire HAL drivers | Alpha (Phase 4b, MP-HAL08/09) | `rust/**`, `native/**` |
| Full responsive Compose shell | Phase 5 (MP-UI04) | — |

`Main.kt` is intentionally a minimal, engine-free Compose window. The Phase 4b
mission is packaging, the size budget and headless JNA verification; wiring
the engine into the shell is Phase 5 scope. This also keeps the module
compilable on hosts without a Rust toolchain (`./gradlew :desktop:compileKotlin`
works anywhere; the JNA smoke test needs cargo — see below).

## Binding mode (MP-DEC-02)

The desktop JVM reaches `streamify_core_rs` through UniFFI's **JNA backend**:

```
Compose shell (scaffold)
  └── uniffi.streamify.*  (generated Kotlin proxies, package pinned by rust/uniffi.toml)
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
this module pins it in two places:

* `Main.kt` — first statement of `main()`, before any FFI touch;
* the test JVM (`tasks.test { systemProperty(...) }`).

The durable fix — adding `cdylib_name = "streamify_core_rs"` under
`[bindings.kotlin]` in `rust/uniffi.toml` — belongs to the `rust/**` subtree
(Alpha) and is flagged for follow-up. The same latent mismatch exists for the
Android module's generated proxies.

## Gradle tasks

| Task | Purpose |
|---|---|
| `generateDesktopUniffiBindings` | `cargo run --bin uniffi-bindgen -- generate rust/src/streamify.udl --language kotlin` into `build/generated/uniffi/kotlin` |
| `buildDesktopEngineLibrary` | `cargo build --release --lib` (host target; release profile is `strip = true`) |
| `prepareDesktopEngineResources` | stages the single host-arch cdylib at the resource root (JNA classpath loading; single-arch by construction) |
| `verifyDesktopNativeHygiene` | gate: exactly one host-arch engine lib, no debug-symbol files, no test fixtures, no Android-ABI artifacts |
| `verifyDesktopSizeBudget` | MP-HAL10 gate: every installer + the jpackage app image must be **strictly < 250 MiB** (262,144,000 bytes); prints a per-artifact report and a top-20 offender breakdown on violation |
| `packageDistributionForCurrentOS` | jpackage pass (Deb + Msi) — finalized by the size-budget gate |

## Toolchain requirements

* JDK 17 (CI uses temurin 17) — compilation targets JVM 17.
* Rust stable with the **host** target (Linux: `x86_64-unknown-linux-gnu`,
  Windows: `x86_64-pc-windows-msvc`) for the engine library and the UniFFI
  bindings. Without cargo, the binding/native tasks skip and the smoke test
  degrades to a JUnit `assumeTrue` skip; `compileKotlin` (engine-free
  scaffold) still works.
* Windows MSI packaging additionally needs WiX Toolset 3.x (the CI workflow
  installs it via chocolatey and exports `WIX`).

## Local quickstart

`--configure-on-demand` matters on hosts without an Android SDK: the desktop
lane only configures `:desktop`, never the AGP-driven `:app` project.

```bash
# Lightweight (works without cargo and without an Android SDK):
./gradlew :desktop:compileKotlin --no-daemon --configure-on-demand

# Full JNA smoke (needs cargo on PATH):
./gradlew :desktop:test --no-daemon --configure-on-demand

# Package + size audit (needs cargo; WiX on Windows):
./gradlew :desktop:packageDistributionForCurrentOS --no-daemon --configure-on-demand

# Hermetic zero-toolchain pass (bindings/native tasks skip; smoke suite
# degrades to a JUnit assumption skip):
./gradlew :desktop:test --no-daemon --configure-on-demand -Pstreamify.skipRust=true
```
