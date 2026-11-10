# Streamify — Architecture

> **Start here.** This document is the map of the whole system: what
> lives where, what depends on what, and which rules keep it
> maintainable. For "where do I put my change", jump to
> [ADD_A_FEATURE.md](../playbooks/ADD_A_FEATURE.md). For file-by-file detail, see
> [CODEBASE_GUIDE.md](../playbooks/CODEBASE_GUIDE.md).

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
│       ├── util/         cross-cutting utils
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
│  di/  util/  (foundation: AppGraph, SLog, haptics)               │
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
   └── pure-Rust RustyPipe engine (QuickJS cipher deciphering in streamify-resolver)
```

- JNI is **name-mangled**, not `RegisterNatives` — the class's package
  is load-bearing.
- Rust wraps every FFI crossing in `catch_unwind`; panics must never
  cross the boundary.
- Adding native functionality = add `external fun` in `NativeBridge` +
  matching `Java_com_streamify_app_data_NativeBridge_<name>` symbol.

## 6. CI — the Hub-and-Spoke Unified Pipeline

`.github/workflows/ci.yml` is the hub of the 3-tier DAG (its retired
monolithic predecessor lives on only in `git log`):

| Tier | Job(s) | Gate? | What it does |
|---|---|---|---|
| 1 | `preflight` ("Tier 1: Fast-Fail & Path Filter") | ✅ | gitleaks (blocking), JNI panic-boundary convention gate, docs-vs-reality lint (`scripts/docs_ledger_check.py`, TRUTH-02), dorny/paths-filter impact analysis |
| 2 | `Core Engine & C++ DSP` (from `_ci-engine.yml`) | ✅ | Rust core nextest matrix (split 2 shards), C++ ASan/UBSan (native-dsp / simd-physics / telemetry-storage), build-once native fan-out: Android NDK artifacts (arm64-v8a, x86_64), Linux + Windows desktop cdylibs |
| 2 | `Rust clippy (workspace, -D warnings)` | ✅ | whole-workspace lint, zero-warning policy |
| 2 | `Android Matrix` (from `_ci-android.yml`) | ✅ | JVM unit suite + assembleDebug APK (consumes the NDK artifacts) |
| 2 | `Desktop Matrix (Linux, Windows)` (from `_ci-desktop.yml`) | ✅ | CMP UI smoke + JNA engine smoke suite, jpackage installers + <250 MiB size budget (packaging-gated) |
| 2 | `Apple Unified` (from `_ci-ios.yml`) | ✅ | builds `StreamifyCore.xcframework`, **links it** (IOS-02), runs the real-lane iOS simulator tests + UniFFI contract tests, macOS SPM compile gate, macOS desktop cdylib + JNA smoke |
| 2 | `Supabase RLS hardening suite (SEC-10)` | ✅ | 11 RLS assertions against a real Postgres+pgvector container |
| 3 | `ci-gate` ("CI Gate (Blocking)") | ✅ | the ONE required status check in branch protection; folds every Tier-2 verdict |
| — | `CodeQL` (`codeql.yml`) | advisory | scheduled + PR security scan (never blocking — cannot deadlock the merge queue) |
| — | `Nightly Deep Matrix` (`nightly-deep-matrix.yml`) | nightly ✅ | deep Rust nextest profile (32-peer mesh chaos), C++ LibFuzzer hunt, KVM Android emulator connected suite (TEST-01), log aggregation to the `testing-log` branch |
| — | `Resolver Client Canary` (`resolver-canary.yml`) | — | daily live-client probe; rotations land as reviewed PRs from `bot/fleet-rotation` (GOV-05), never direct pushes to `main` |

**Releases**: `release.yml` fires only on `v*` tags and publishes from the
protected `release` environment (GOV-05). The historical `build-N` release
river ended at build-691. Details:
[CI_TESTING_GUIDE.md](../playbooks/CI_TESTING_GUIDE.md).

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
