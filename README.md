# Streamify

Streamify is a free music streaming client: a Kotlin Compose Multiplatform UI
over a Rust and C++20 native core. It plays YouTube Music catalogs, applies
studio-grade loudness normalization and true-peak limiting on the native audio
path, renders synchronized lyrics, and keeps a local-first library that syncs
to Supabase. The project descends from SimpMusic; the shared data/domain
modules still arrive through the `core` git submodule (`maxrave-dev/core`)
with a local patch (`core.patch`) applied on top.

## UI showcase — Liquid Glass (BETA)

The same Compose Multiplatform UI reflows from a 1440 × 900 desktop canvas
down to a 390 × 844 phone: an adaptive navigation rail, a floating
**Liquid Glass (BETA)** mini-player pill, and the frosted-glass treatment
wherever panels dock over content. Enable it under
`Settings → Appearance → Enable liquid glass (BETA)`
(or set `localStorage.enableLiquidGlass = "true"` on the wasm shell) and the
translucent glass surfaces pick up the artwork and feed behind them.

<table>
  <tr>
    <td width="50%" align="center"><em>Desktop — Home / Discover</em></td>
    <td width="50%" align="center"><em>Desktop — Now Playing</em></td>
  </tr>
  <tr>
    <td width="50%"><a href="asset/screenshot/desktop/desktop-home.png"><img src="asset/screenshot/desktop/desktop-home.png" alt="Streamify on desktop: navigation rail, quick picks and trending community playlists under the Liquid Glass theme" title="Home / Discover"></a></td>
    <td width="50%"><a href="asset/screenshot/desktop/desktop-player.png"><img src="asset/screenshot/desktop/desktop-player.png" alt="Streamify on desktop: Now Playing split view with artwork, lyrics and the frosted glass mini-player pill" title="Now Playing"></a></td>
  </tr>
</table>

<p align="center">
  <a href="asset/screenshot/desktop/desktop-library.png"><img src="asset/screenshot/desktop/desktop-library.png" alt="Streamify on desktop: Library view with favorites, playback stats and synced playlists" title="Library" width="80%"></a><br>
  <em>Desktop — Library</em>
</p>

<table>
  <tr>
    <td width="33%" align="center"><em>Mobile — Home</em></td>
    <td width="33%" align="center"><em>Mobile — Now Playing</em></td>
    <td width="33%" align="center"><em>Mobile — Search</em></td>
  </tr>
  <tr>
    <td width="33%" align="center"><a href="asset/screenshot/mobile/mobile-home.png"><img src="asset/screenshot/mobile/mobile-home.png" alt="Streamify on a 390 × 844 phone: home feed with quick picks and trending playlists" title="Home"></a></td>
    <td width="33%" align="center"><a href="asset/screenshot/mobile/mobile-player.png"><img src="asset/screenshot/mobile/mobile-player.png" alt="Streamify on a phone: full-screen Now Playing view with artwork and synced lyrics" title="Now Playing"></a></td>
    <td width="33%" align="center"><a href="asset/screenshot/mobile/mobile-search.png"><img src="asset/screenshot/mobile/mobile-search.png" alt="Streamify on a phone: search results with the docked mini-player pill" title="Search"></a></td>
  </tr>
</table>

<sub>Captured against the live wasmJs shell with Liquid Glass (BETA) enabled; viewports 1440 × 900 (desktop) and 390 × 844 (mobile). The phone bottom bar in the wasm demo keeps its flat variant — the folding glass bar ships on the Android/iOS/desktop shells.</sub>

```
Platform shells (Kotlin Compose)      androidApp + composeApp (Android)
                                      desktopApp (Windows/macOS/Linux)
                                      composeApp wasmJs (browser)
        │ Koin DI, JNI, UniFFI contract v2
        ▼
Sovereign Rust core (rust/)           resolver (Innertube tiers), playback
                                      producer, jam consensus, crypto vault
        │ f32 stereo, canonical 48 kHz, SPSC write edge
        ▼
Real-time DSP (native/, C++20)        AudioSink SPSC ringbuffer, MasterChain
        ▼
Platform audio HAL                    Android Media3/AudioTrack (PCM float),
                                      WASAPI, PipeWire/ALSA, CoreAudio
Side planes: Room SQLite (WAL) <-> Supabase Postgres (LWW, RLS)
             server/ Fastify relay + content-addressed web bundle
```

## Build in 60 seconds (Android debug APK)

Prerequisites: JDK 21, Android SDK with compileSdk 37, git. The Gradle wrapper
(9.8.1) fetches itself.

```bash
git clone --recursive https://github.com/meraxes89/streamify-apk.git
cd streamify-apk
git -C core apply ../core.patch     # same patch CI applies to the submodule
./gradlew :androidApp:assembleDebug
# androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

The native libraries (`libstreamify_core_rs.so`, `libstreamify_native_core.so`)
are not committed. The APK assembles without them and runs on the Media3 audio
path; the full DSP path requires them, which CI builds with cargo-ndk and
CMake and stages via `-PciPrebuiltNativeDir`. Build them locally with the
commands in [rust/README.md](rust/README.md) and [native/README.md](native/README.md).

Other targets:

```bash
./gradlew :androidApp:testDebugUnitTest -PskipExternalNativeBuild=true   # JVM tests
./gradlew :desktopApp:packageDistributionForCurrentOS                   # desktop installers
cd rust && cargo test --all-targets && cargo clippy --all-targets -- -D warnings
cd native && cmake -B build -S . -DENABLE_TESTS=ON && cmake --build build && ctest --test-dir build --output-on-failure
cd server && npm ci && npm start                                         # relay on :3000
```

## Repository layout

| Directory | Contents |
|---|---|
| [androidApp/](androidApp/README.md) | Android shell: manifest, Media3 services, widgets, release signing, vivo store variant |
| [composeApp/](composeApp/README.md) | Compose Multiplatform UI, ViewModels, navigation, Koin graph, sync engine |
| [rust/](rust/README.md) | Sovereign core: 10 crates, resolver, playback, jam, UniFFI contract |
| [native/](native/README.md) | C++20 real-time DSP: SPSC ringbuffer, LUFS, limiter, desktop HALs |
| [streamify-modules/](streamify-modules/README.md) | Downstream extension suite (Koin IoC, media HAL, lyrics, Supabase, jam, ffmpeg shim) |
| [desktopApp/](desktopApp/README.md) | Compose Desktop entry point, Conveyor packaging, memory tuning |
| [server/](server/README.md) | Fastify relay, stream gateway, content-addressed WASM bundle delivery |
| [supabase/](supabase/README.md) | Postgres schema, RLS policies, realtime publications, LWW upserts |
| [scripts/](scripts/README.md) | CI harnesses, ABI parity gates, golden-vector generators, doc verification |
| [docs/](docs/README.md) | Architecture map, ADRs, playbooks, archive |
| core/ (submodule) | Upstream `maxrave-dev/core`: Room database, repositories, media3 service |
| legacy/ | Archived pre-multiplatform code, not part of the build |

## Documentation index

- [System architecture map](docs/architecture/ARCHITECTURE.md) — the authoritative layer map and measured invariants
- [Architecture Decision Records](docs/adr/README.md) — ADR-001 through ADR-004
- [Subsystem deep-dives](docs/architecture/engines/) — 13 engine documents
- [Developer playbooks](docs/playbooks/) — feature recipes, CI guide, codebase tour
- [Project philosophy](docs/PHILOSOPHY.md)
- [Hybrid sync architecture](supabase/HYBRID_SYNC_ARCHITECTURE.md)
- [Contributing](CONTRIBUTING.md), [Security policy](SECURITY.md), [Code of conduct](CODE_OF_CONDUCT.md)

Releases are published through the unified CI pipeline under
[GitHub Releases](https://github.com/meraxes89/streamify-apk/releases).
Streamify is licensed under the [MIT License](LICENSE).

---
<!-- doc-verified-against: 8b858b1c89b91f643cd7159180706fbd931f925e -->
