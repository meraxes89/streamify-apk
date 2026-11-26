# streamify-modules/ — downstream extension suite

Kotlin Multiplatform modules that attach Streamify-specific features to the
upstream app without invasive edits. Attachment is inversion of control
through three mechanisms (decision record:
[ADR-004](../docs/adr/ADR-004-ioc-streamify-modules.md)):

1. **Koin addition.** Upstream `loadAllModules(...)` runs first; then the
   shell calls `loadKoinModules(streamifyAllModules)` (aggregated in
   `jam/src/commonMain/kotlin/com/streamify/jam/di/JamModule.kt`; observed
   in `SimpMusicApplication.kt` and `DesktopApp.kt`). The suite registers
   only new `com.streamify.*` types — it does not redefine upstream beans.
   Caveat documented in-code: modules added via `loadKoinModules` do not
   fire `createdAtStart`, so singletons must be explicitly injected.
2. **Gradle dependency substitution.** The ffmpeg compat shim replaces the
   24.4 MB `io.github.maxrave-dev:ffmpeg-kit-audio` binary at build time
   (root `build.gradle.kts`).
3. **Guarded includes.** `settings.gradle.kts` includes the suite only when
   this directory exists, so an upstream-pure build stays constructible.

## Modules

| Module | Gradle path | Provides |
|---|---|---|
| media-hal | `:streamify-media-hal` | Android: `DolbySpatialManager` & `StreamifyMedia3Module.init(context)` capability detection. JVM/iOS/Wasm: `DolbySpatialManager` + empty `mediaHalModule` Koin bindings |
| lyrics-slyr | `:streamify-lyrics-slyr` | SLYR lyric engine (see below) |
| supabase | `:streamify-supabase` | Ktor Supabase client: auth, library sync, friends, admin client, hybrid home repository; Koin `SupabaseModule` |
| jam | `:streamify-jam` | `StreamifyJamCoordinator` — a pure-Kotlin `StateFlow` room state machine (create/join via 6-char code, transport/track broadcast keyed to `DacPresentationClock` timestamps) — plus the `BlendGenerator` (Jaccard blend score `jaccard*60+40`, clamped 45-98). Note: this module does not wrap the Rust jam engine; the two coexist (see ADR-004) |
| ffmpeg-compat-shim | `:streamify-ffmpeg-compat-shim` | `com.arthenica.ffmpegkit` API implemented with zero-re-encode `MediaExtractor`/`MediaMuxer` remux; Android-only |
| graphics | `:streamify-modules:streamify-graphics` | Sovereign GPU Engine: `Modifier.streamifyGlass` liquid-glass facade over `LiquidGlassCore.sksl` (SkSL/AGSL, Kyant optical parity — directional 45° rim, 1.5× vibrancy, adaptive "đục đen" scrim, fluid press interaction), 3-layer design tokens, TierGovernor 4-tier capability ladder, headless Skia parity golden tests (docs/gpu/) |

## lyrics-slyr (PR #253)

- `SlyrClock.kt`: unified presentation-time clock,
  `HeardTime = PlayerPresentationPosition - L_output - UserOffset`;
  interpolates the raw position at ~60 Hz; monotonic non-decreasing during
  playback; seeks force a latency-model snap.
- `LatencyModel.kt`: decomposes `L_output = L_hal_buffer + L_link`; slews
  the target delay over 250 ms in 16 ms steps to avoid visual jumps on
  route changes; `snapToTarget()` for immediate reset. Codec profiles:
  LE Audio 40 ms, A2DP AAC 140 ms, A2DP SBC 180 ms, aptX 90 ms, LDAC 130 ms,
  SCO 80 ms, wired 0 ms.
- `BluetoothLatencyTracker.android.kt`: `AudioDeviceCallback` route
  classification (BLE -> 40 ms, A2DP AAC -> 140 ms, SCO -> 80 ms, wired ->
  0 ms measured) with a pre-M fallback.
- `SlyrBytecodeCompiler.kt`: LRC compiler with `[mm:ss.xx]` lines,
  multi-timestamp lines, `<mm:ss.xx>` syllable tags, `[offset:±ms]`;
  enforces a monotonic timeline, bounds the last syllable to its line,
  minimum line duration 500 ms.
- `LyricsPlaybackEngine.kt`: amortized O(1) incremental cursor with
  O(log N) binary-search fallback on seeks; syllable progress strictly
  linear in time.

## Scope of "upstream immunity"

The suite avoids invasive edits, but immunity is qualified, not absolute:
`core.patch` modifies the `core` submodule, and `composeApp` contains
streamify code (`StreamifySyncEngine`, `StreamifyDefaultsInitializer`,
`StreamifyUpdateRepositoryImpl`). Treat the boundary as a review convention
enforced by `scripts/check_feishin_resolver.py` (vendored-file integrity),
not a hard guarantee.

Corrections to earlier revisions of this README: the suite contains no
UniFFI/JNA bindings (those are JNI; UniFFI bindings are generated
elsewhere), no Oboe usage, no Wiener-Khinchin phase correlation in
lyrics-slyr (alignment math lives in `rust/` and `native/`), no WebSocket
client in the jam module, and no Realtime channel code in the supabase
module (plain Ktor REST; Realtime is configured server-side).

---
Verified against commit 8b858b1c. Staleness protocol: docs/adr/README.md.
<!-- doc-verified-against: 5286b8486a114eb58d1ce8355baefb6b98602555 -->
