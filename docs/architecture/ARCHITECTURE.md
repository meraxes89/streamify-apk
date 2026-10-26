# Streamify — Architecture

> **Start here.** This document is the map of the whole system: what
> lives where, what depends on what, and which rules keep it
> maintainable. For "where do I put my change", jump to
> [ADD_A_FEATURE.md](ADD_A_FEATURE.md). For file-by-file detail, see
> [CODEBASE_GUIDE.md](CODEBASE_GUIDE.md).

---

## 1. What Streamify is

An Android music-streaming engine with a **triple-native core**:

| Layer | Technology | Role |
|---|---|---|
| UI | Kotlin + Jetpack Compose | 120 FPS interface, zero-alloc draw paths |
| Audio engine | C++20 (NEON) | Real-time DSP: LUFS, limiter, key/BPM, physics |
| I/O & auth | Rust (Tokio) | Stream resolution, zero-copy JSON, PTP, tagging |
| Backend | Supabase (PostgREST + Realtime) | Accounts, sync, jam sessions, telemetry |

The repo also carries **three auxiliary systems**: a Supabase schema
(`supabase/`), a fleet of probe/tooling scripts (`scripts/`, `tools/`),
and a frozen archive of the pre-Supabase backend (`legacy/`).

## 2. Repository layout

```
streamify-apk/
├── app/                  the Android app (single Gradle module)
│   └── src/main/java/com/streamify/app/
│       ├── data/         data layer — 13 domain subpackages
│       ├── media/        audio engine — playback/audio/cache/sync/ingestion/lyrics
│       ├── jam/          distributed jam sessions
│       ├── radio/        radio queue construction
│       ├── di/           AppGraph (hand-rolled DI) + dispatchers
│       ├── navigation/   Compose nav graph
│       ├── ui/           screens, components (+yt/ kit), theme
│       ├── viewmodel/    presentation state
│       ├── util/         cross-cutting utils (+ newpipe/ PO-token pipeline)
│       └── worker/       foreground workers
├── native/               C++20 DSP & physics core (libstreamify_native_core.so)
├── rust/                 Rust I/O engine (libstreamify_core_rs.so)
├── supabase/             backend schema + migrations
├── scripts/ tools/       verification probes & fleet ops
├── docs/                 engine deep-dives + this architecture series
└── legacy/               archived code — NOT built, bit-frozen
```

Every directory above has a `README.md` with its own file index.

## 3. The Kotlin layer cake

```
┌────────────────────────────────────────────────────────────────┐
│  ui/            screens & components (Compose)                 │
├────────────────────────────────────────────────────────────────┤
│  navigation/    routes                                          │
├────────────────────────────────────────────────────────────────┤
│  viewmodel/     presentation state, coroutine owners           │
├───────────────────────┬────────────────────────────────────────┤
│  data/                │  media/                                 │
│  repositories         │  playback service + queue              │
│  persistence          │  DSP chain, spatial, equalizer          │
│  discovery/matching   │  cache & pre-buffer policy             │
│  network (resolve)    │  PTP sync, PLL, scheduling             │
│  supabase/spotify/    │  background workers                    │
│  youtube providers    │  lyric playback controllers            │
├───────────────────────┴───────────┬────────────────────────────┤
│  jam/  radio/  (domain engines)    │                            │
├────────────────────────────────────┴────────────────────────────┤
│  di/  util/  (foundation: AppGraph, SLog, newpipe, haptics)     │
└────────────────────────────────────────────────────────────────┘
```

**Dependency rules (convention, kept enforceable for a future Gradle
module split):**

1. Arrows only point **downward**: `ui → viewmodel → {data, media} → {di, util}`.
2. `data/` and `media/` never import `ui/` types.
3. `viewmodel/` orchestrates cross-domain flows (e.g. Spotify ingest →
   repository → Supabase sync); providers don't call each other.
4. `data/models/` and `ui/models/` are **ProGuard-pinned** — their
   package paths are serialization contracts.
5. `data/NativeBridge.kt` is **JNI-pinned** — its package is an ABI
   contract with `rust/src/jni_bridge.rs` and `native/jni/jni_bridge.cc`.

## 4. How a song plays (the 30-second tour)

```
user taps a Track
   │
   ▼
PlayerViewModel (viewmodel/) ── loads via PlayerTrackLoader
   │
   ▼
media/playback: PlaybackService (Media3) + QueueEngine + DynamicQueueManager
   │                (sliding 2-track JIT lookahead)
   ▼
data/network: YouTubeStreamResolver → ResilientMediaRouter
   │   (tier 0 native cache → tier 1 HTTP/2 race → tier 2 PO-token)
   │   gated by CanonicalSeedResolver + data/discovery (CAD-ID)
   ▼
media/cache: AudioCacheManager + PredictivePreBufferManager
   │                (gapless hand-off)
   ▼
media/audio: StreamifyAudioProcessor (native LUFS/limiter chain)
   │
   ▼
device speaker ← Media3 AudioTrack
```

Failures short-circuit through `StreamifyCircuitBreaker` and
`NegativeResultCache` so a dead CDN never stalls the queue.

## 5. The native boundary

```
Kotlin                          Native
──────                          ──────
data/NativeBridge.kt ──JNI──▶  rust/src/jni_bridge.rs    (I/O, auth, PTP, tag)
   ▲  140+ external funs ───▶  native/jni/jni_bridge.cc  (DSP, physics, storage)
   │
   └── app/src/main/assets/po_token.html + util/newpipe/ (BotGuard WebView)
```

- JNI is **name-mangled**, not `RegisterNatives` — the class's package
  is load-bearing.
- Rust wraps every FFI crossing in `catch_unwind`; panics must never
  cross the boundary.
- Adding native functionality = add `external fun` in `NativeBridge` +
  matching `Java_com_streamify_app_data_NativeBridge_<name>` symbol.

## 6. CI — the Extreme Test Matrix

`.github/workflows/extreme-test-matrix.yml` runs on every PR to main:

| Job | Gate? | What it does |
|---|---|---|
| `build-and-compile` | ✅ required | C++20 + Rust + `assembleDebug` |
| `test-matrix` (5 shards) | ✅ required | native-dsp / simd-physics / telemetry-storage (ASan+UBSan), jvm-unit-suite, rust-core-engine |
| `native-deep-fuzz` | ✅ required | libFuzzer crash hunt |
| `codeql-analysis` | ✅ required | security + quality |
| `gatekeeper-api-probe` | advisory | live search→CDN verification |
| `emulator-matrix` | nightly advisory | macrobenchmarks |
| `aggregate-and-publish-logs` | ✅ final verdict | scans shard logs, commits to `testing-log` branch |

**Releases**: `build-and-compile` publishes GitHub releases **only**
for `main` / `streamify-yt-spt` branch runs — PR runs never publish.
`release.yml` fires only on `v*` tags. Details:
[CI-TESTING-GUIDE.md](CI-TESTING-GUIDE.md).

## 7. Design decisions worth knowing

- **Hand-rolled DI (`AppGraph`)** over a framework: compile-time
  visibility of the whole graph, zero reflection, trivially testable.
- **Package-level modularization now, Gradle modules later.** The
  v1.2.0 tree is shaped so `data/`, `media/`, `ui/` can each become a
  Gradle module by (1) adding `build.gradle.kts`, (2) adding to
  `settings.gradle.kts`, (3) flipping same-project references to
  cross-module imports. The dependency rules in §3 are written to
  survive that migration unchanged.
- **Immutable models + explicit decode sizes + lazy-list keys** are
  perf contracts from the v1.1.0 series — don't regress them.
- **`SLog` everywhere** (never `printStackTrace`), and coroutine
  lifecycles are owned by ViewModels.

## 8. Glossary

| Term | Meaning |
|---|---|
| **CAD-ID** | Duration-aware canonical identity hash — dedups songs across Spotify/YTM without collapsing remixes. |
| **SLYR** | Binary compiled lyric timeline (Wiener-Khinchin aligned). |
| **PO token** | YouTube BotGuard proof-of-origin token (WebView-generated). |
| **Edge Mesh** | LAN peer sessions (discovery + Supabase registry). |
| **Jam** | Multi-device lockstep listening (single-writer + PTP PLL). |
| **Wrapped** | Stats analytics screens backed by `YtStatsTelemetryEngine`. |
