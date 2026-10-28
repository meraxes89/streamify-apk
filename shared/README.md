# Streamify Shared UI Module (`:shared` + `:shared-android`)

**Phase 5 / MP-UI03 (Beta — Client Shells / Bridge / Adapters Engineer)**
Governing plan: `docs/architecture/MULTIPLATFORM_PLAN_27_10.MD` §4 Phase 5 ·
Ledger: `docs/architecture/MULTIPLATFORM_CHECKBOX.MD`

The Compose Multiplatform **shared presentation layer**: theme tokens,
reusable playback control components, presentation models and the
platform-independent state holder that both client shells consume so no UI
code is duplicated between platforms.

## Module shape

```
shared/
├── build.gradle.kts               ← :shared — KMP, single JVM target `desktop`
├── src/
│   ├── commonMain/kotlin/com/streamify/shared/
│   │   ├── theme/     StreamifyTheme.kt      (colors · typography · spacing · shapes)
│   │   ├── components/ MiniPlayerBar · TrackListItem · VolumeSlider
│   │   ├── model/     PlaybackModels · PlaybackFormatting (pure derivations)
│   │   ├── state/     PlaybackController     (the platform-independent "ViewModel")
│   │   └── engine/    EnginePort             (the platform seam)
│   ├── desktopEngine/kotlin/…/desktop/DesktopEnginePort.kt
│   │      ← the ONLY uniffi.* referencing code: JNA-mode engine gateway,
│   │        attached to the desktop target only when the binding lane runs
│   ├── androidMain/kotlin/…/android/dogfood/RustAudioDogfoodPanel.kt
│   │      ← Android consumption of the shared tokens/models (MP-CUT01 panel)
│   └── commonTest/kotlin/         SharedPlaybackLogicTest (+ FakeEnginePort)
└── android/
    └── build.gradle.kts           ← :shared-android — AGP library compiling
                                      commonMain + androidMain against androidx Compose
```

**Why two Gradle modules for one source tree:** `:desktop` depends on
`:shared`, and the desktop CI lane (`desktop-ci.yml`) runs
`--configure-on-demand` on runners **without an Android SDK** — a KMP
`androidTarget` inside `:shared` would drag AGP configuration into that
lane and break the Phase 4b hermeticity guarantee. `:shared-android`
(sibling module, projectDir `shared/android`) keeps the Android seam fully
on the app-side lanes. `commonMain` is deliberately **expect/actual-free**
so the same sources compile on both seams (KGP jvm / CMP for desktop,
AGP + androidx Compose for Android — both resolve to the Compose 1.6.x
line).

## The seam contract (`EnginePort`)

| Platform | Implementation | Binding mode (MP-DEC-02) |
|---|---|---|
| Desktop | `shared/src/desktopEngine/.../DesktopEnginePort` | UniFFI **JNA** proxies (`streamify-jvm` lane), generated into `shared/build/generated/uniffi/kotlin` |
| Android | app's reflective `StreamifyEngineBridge` backend (on-ramp staged by the MP-CUT01 dogfood window) | UniFFI **direct JNI** (`streamify-android.aar`) |
| iOS | `iosApp/…/EngineGateway.swift` | UniFFI **direct Swift** bindings |

Push-only state discipline (MP-DEC-04): the port exposes a `snapshots`
`Flow` with strictly monotonic revisions; UI shells collect it and never
poll. `currentSnapshot()` exists solely for cold-start hydration.

## Build / verify

```bash
# Shared logic tests (pure JVM — no engine, no Compose window):
./gradlew :shared:desktopTest

# Full desktop lane (engine + bindings + shell + packaging):
./gradlew :desktop:test :desktop:packageDistributionForCurrentOS

# Hermetic host (no Rust toolchain): codegen + engine gateway degrade,
# everything else compiles, desktop tests report zero:
./gradlew :shared:desktopTest -Pstreamify.skipRust=true
```

The UniFFI binding generation + the 0.28.3 `message`-shadowing shim live
in `shared/build.gradle.kts` (`generateSharedUniffiBindings` /
`patchSharedUniffiBindings`) — relocated from `desktop/build.gradle.kts`
in Phase 5 so the generated proxies compile exactly once per desktop lane.
