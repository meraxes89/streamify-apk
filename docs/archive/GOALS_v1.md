# Streamify: Universal Taste Ingestion & Dual-Native Systems Architecture

---

### 🎯 Primary Objective
The goal of this architecture is to eliminate onboarding friction for new users by providing **Universal In-App Taste Sync**. Instead of forcing users ttreamify ingests their Liked Songs, custom playlists, and personalized algorithmic mixes (Daily Mixes, Discover Weekly, Supermix, Listen Again) and serves them through Streamify’s zero-ad, 120 FPS hardware-accelerated playback pipeline, utilizing native C++20 DSP and zero-copy Rust I/O.
### 🏛️ Executive System Topology
```
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                              STREAMIFY CLIENT ARCHIT────────────────────────────────────────────────────────────────────────────────────────┤
│   🎨 JETPACK COMPOSE UI LAYER (120 FPS Hardware VSYNC)                                 │
│   ├─ Universal Home Feed: Spotify Daily Mixes + YouTube Music Supermix Shelves         │
│   ├─  └─ Zero-Alloc Draw Scope: CompositingStrategy.Offscreen + SLYR Karaoke Shader        │
│                                      │                                                 │
│                        @Immutable Value Models / JNI                                   │
│                                      ▼                                                 │
│   🎵 ANDROID SERVICE & PLAYBACK CONTROLLER                                             │
│   ├─ Sliding 2-Track          │
│   ├─ In-Stream Zero-Copy Live PCM Tap (MeshPcmAudioProcessor)                          │
│   └─ Hardware AudioTrack Position Tracker + Bluetooth A2DP Delay Compensation          │
│                                      │                                                 │
│                 ┌────────────────────┴────────────────────┐                            │
│                 ▼                                🧠 NATIVE C++20 ENGINE (DSP & PHYSICS)    🦀 RUST ENGINE (I/O, AUTH & STORAGE)       │
│   ├─ 2048-pt KissFFT HPCP Camelot Keys      ├─ Native SAPISIDHASH Generator (SHA-1)    │
│   ├─ ITU-R BS.1770-4 LUFS Normalizer        ├─ Async Tokio JIT Resolver (reqwest)      │
│   ├─ 100 Hz Vocal Cross-Correlation (Δτ*)   ├─ Duration-Aware CAD-ID SQLite Graph      │
│   └─ 6-DOF RK4 Fluid Dynamic Tokens                                                                                         │
└────────────────────────────────────────────────────────────────────────────────────────┘
```
---

#### 1. Native Authentication & Gated Session Security
* **Spotify PKCE Engine**: Standard OAuth 2.0 PKCE executed in a Custom Chrome Tab. Long-li--
#### 2. Duration-Aware Canonical Identity Graph (Rust SQLite)
* **Zero-Collision Identity (CAD-ID)**: Deduplicates songs across Spotify and YouTube Music without collapsing distinct audio versions (such as remixes, acoustic edits, or live performances) into the same entity:

---
#### 3. Sliding 2-Track JIT Stream Resolver (Rust Tokio)
1. *Direct Video ID Hit*: Immediate playback if the track originated on YouTube Music ($<10\text{ms}$).
2. *ISRC Query Match*: Exact master recording search via `isrc:<CODE>` for Spotify tracks.
3. *Token* **Panic-Free Async Bridge**: Rust's Tokio runtime executes network requests asynchronously while isolating panics behind `std::panic::catch_unwind`, ensuring network dropouts return clean error codes instead of crashing the Android process.

---
#### 4. Zero-GC Virtual Shelf & 120 FPS UI Pipeline
* **Immutable Snapshot Mapping**: Network and database payloads are deserialized once into `@Immutable` Kotlin data models on arrival, eliminating string parsing and allocation during UI rendering.
* **Deterministic Key Stability**: Every shelf item is indexed by its canonical ID (`key = { it.cadId }`), allowing Compose to reuse UI nodes during rapid scrolling.
* **Recomposition Skip0 FPS Draw-Phase Lyrics**: Karaoke rendering reads directly from 16-byte aligned binary memory (`.slyr`) via direct memory offsets, computing text sweeps inside an isolated GPU layer (`CompositingStrategy.Offscreen`) with exactly **0 bytes allocated per frame**.


---
### 📊 Performance & Runtime Invariants
* **Cold Startup & Hydration**: Under **$200\text{ms}$** to hydrate all cached virtual shelves f **Scroll Frame Budget**: **$8.33\text{ms}$ per frame** (Locked 120 FPS) with 0 ART Garbage Collection pauses during rapid library flings.
* **Stream Resolution Latency**: Under **$80\text{ms}$** for initial track launch; **$0\text{ms}$ gapless** playback transitions via lookahead pre-buffering.
* **APK Binary Target**: Maintained under **$20\text{ MB}$** by eliminating heavy embedded runtimes in favor of stripped Rust and C++20 static librar












