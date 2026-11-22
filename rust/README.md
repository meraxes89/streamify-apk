# rust/ — Sovereign Rust core

Ten crates, one artifact: `libstreamify_core_rs` (cdylib, rlib, staticlib)
that every platform binds to. This README states observed facts from the
source tree; interpretation is confined to the last section.

## Crates

| Crate | ~LOC | Role |
|---|---|---|
| streamify-core | 1,990 | Shared primitives |
| streamify-audio | 4,792 | RT boundary: rubato resampler, LUFS, bass/spatializer DSP; no tokio/rusqlite/reqwest deps |
| streamify-playback | 2,421 | Producer thread, symphonia decode, `SpcsAudioRing` sink + 4-symbol C ABI export |
| streamify-storage | 1,949 | SQLite + ChaCha20-Poly1305 AEAD vault |
| streamify-jam | 19,427 | PTP math, Kalman PLL, consensus, Ed25519 governance, gossip |
| streamify-jam-crdt | 2,272 | Voting CRDT (promotion quorum floor(n/2)+1), wasm32-safe |
| streamify-radio-scorer | 929 | Radio/daylist scoring |
| streamify-resolver | 6,695 | Innertube tiers, rustypipe engine, tagger, lyrics, aligner |
| streamify-contract | 9,866 | UniFFI UDL (956 lines) + engine implementation; `contract_version()` = 2 |
| streamify-ffi | 3,617 | JNI bridge, C ABI (`ffi.rs`), uniffi-bindgen CLI |

Workspace: edition 2021, MIT. Key deps: tokio 1.38 (rt-multi-thread), reqwest
0.12 (rustls-only), rubato 0.15, lofty 0.25, symphonia 0.6 (aac, isomp4, ogg,
pcm, wav — no Opus decoder, so the resolver ranking prefers m4a), uniffi
0.28, rusqlite (bundled), ed25519-dalek, blake3, chacha20poly1305, rustypipe
0.11 + optional botguard. Release profile: `panic = "abort"`, LTO,
`codegen-units = 1`, `opt-level = 3`, `strip = true`.

## Runtime model

One shared multi-thread Tokio runtime in a `OnceLock`:
`worker_threads = available_parallelism().clamp(4, 8)` (rationale comment:
fixed 2-worker pool saturated; ceiling caps tokio's 2 MB stacks at 16 MB).
Sync FFI entry points `block_on`; the downloader runs `block_on` from a plain
thread, never inside a worker. Documented exception: the P2P mesh lane
builds a dedicated 2-worker runtime on `nativeP2pStart` and drops it on stop.
HTTP: 30 s total / 10 s connect timeouts on the shared client, 8 s/15 s on
the downloader; bounded fetch retries (3); 429/503 map to a 5,000 ms
`retry_after` hint.

## Resolver

Modules in `crates/streamify-resolver/src/`: `resolver.rs`, `rustypipe_engine.rs`,
`downloader.rs`, `search.rs`, `playlist_parser.rs`, `spotify_ingest.rs`,
`tagger.rs`, `lyrics.rs`, `aligner.rs`.

- Client ladder: anonymous `ANDROID_VR` 1.65.10 primary (client-name 28),
  authenticated `WEB_REMIX`, then a legacy cascade (`ANDROID_MUSIC` 6.42.52,
  `ANDROID` 21.26.364, `ANDROID_VR` 1.60.19). RustyPipe tier adds its own
  Desktop -> iOS -> TV ladder.
- Session warm-up harvests cookies, `visitorData`, and `signatureTimestamp`
  (fallback 20683) from the watch page.
- Signature and n-parameter transforms execute in embedded QuickJS
  (rustypipe via `rquickjs-sys`; bindgen enabled for Android/iOS triples).
  PO tokens come from an optional botguard companion binary
  (`STREAMIFY_BOTGUARD_BIN`); without it the resolver degrades to the
  PO-free iOS/TV ladder.
- Candidate dedup by videoId (11 chars `[A-Za-z0-9_-]`); track resolution
  tiers: SQLite cache by CAD-ID -> ISRC query -> fuzzy match (Jaro-Winkler
  >= 0.72 with artist containment). CDN format TTL: `valid_until` minus
  15 min margin, clamped [60 s, 6 h]; deobf transforms persist in the
  `rustypipe_deobf` SQLite cache.
- The public web API key is the standard public Innertube key, overridable
  via `STREAMIFY_INNERTUBE_KEY` (SEC-07); unit tests pin the key contract.

## Audio path

Producer thread pipeline: fetch (64 KiB segmented ranges, 3 retries, 64 MiB
source cap) -> symphonia decode with incremental-feed checkpointing -> rubato
`SincFixedOut` (256-tap sinc, f_cutoff 0.95, squared Blackman-Harris window,
1024-frame output chunks; 48 kHz input is bit-exact passthrough) -> LUFS
(BS.1770-4 at 48 kHz only: 400 ms blocks, 75 % overlap, -70/-10 LU gates,
target -16 LUFS, ±20 dB clamp in this lane) -> `SpcsAudioRing` SPSC write
(64-byte aligned cursors, 96-byte LE stats wire, magic `0x51AB0001`),
exported to C consumers through the frozen 4-symbol ABI in
`src/ring_export.rs`.

## Lyrics (SLYR)

`lyrics.rs` compiles LRC into the SLYR binary format: magic `0x534C5952`
("SLYR"), version 1, `#[repr(C, align(16))]` structs (header, line headers,
syllable spans) followed by a NUL-separated UTF-8 text pool padded to a
16-byte multiple. Millisecond integer timing; O(log N) binary search for the
active line and syllable spans; `vocal_offset_ms` carries the
auto-calibrated drift offset. Exposed via `rust_compile_to_slyr`,
`rust_find_active_slyr_positions`, and JNI `nativeFindActiveSlyrLine`.
Energy-based forced alignment lives in `aligner.rs` (prosodic short-word
model) behind `rust_align_and_compile_lyrics`.

## Jam and clock sync

- PTP (`ptp.rs`): `theta = ((t1-t0)+(t2-t3))/2`, `delta = ((t3-t0)-(t2-t1))/2`,
  adaptive EMA (alpha clamped [0.01, 1.0]; C-ABI singleton uses 0.25).
- Bootstrap clock (`jam_clock.rs`): Cristian handshake, best-lowest-delta
  selection, `MAX_TRUSTED_RTT_MS = 2,000`.
- Kalman PLL (`kalman_pll.rs`): Q_POS 4.0, Q_VEL 0.05, R_MEAS 36.0; errors
  <= 150 ms close by speed scalar (±2 %), larger errors hard-seek with state
  reset; velocity clamp ±5 %.
- Consensus: 2-peer threshold with distinct-node anti-collusion,
  |delta LUFS| <= 0.3, exact Camelot key match, cosine similarity >= 0.94 on
  128-dim embeddings; HMAC-SHA256 proofs; Ed25519 governance with epoch
  fencing and 5 intents/sec token bucket. Scale evidence: 32-node loopback
  UDP chaos harness (0/15/25 % loss, 10-80 ms jitter), median convergence
  <= 300 ms, excluded from the PR `ci` profile and run serialized nightly.

## UniFFI contract

`crates/streamify-contract/src/streamify.udl` (956 lines), scaffolding
generated by `build.rs`; `uniffi.toml` maps Kotlin package
`uniffi.streamify` to cdylib `streamify_core_rs`. Surfaces: `StreamifyEngine`
(initialize, playback queue ops, 10-band equalizer, jam session create and
48-byte delta apply, `record_dac_clock`, media commands with 50-150 ms
debounce), `TasteEngine` (radio, daylist, acoustic embeddings),
`OsMediaIntegration` (SMTC/MPRIS/Apple remote), `EngineEventListener`
snapshot callbacks (strictly monotonic `rev`, dedicated dispatcher thread),
`SecureStoreDelegate`. Every method `[Throws=EngineError]` and
panic-shielded. Bindings CLI: `cargo run --bin uniffi-bindgen -- generate ...`.

## Build and test

```bash
cargo test --all-targets
cargo clippy --all-targets -- -D warnings
cargo run --bin uniffi-bindgen -- generate crates/streamify-contract/src/streamify.udl --language kotlin
```

Android cross-compile is CI's cargo-ndk lane (arm64-v8a, x86_64). 35
integration test files include golden-vector suites (jam op wire vs
`fixtures/jam_golden_vectors.json`; DSP vectors shared with C++), the
counting-allocator RT gate, offline resolver tests over serde-envelope
fixtures, and the 32-peer mesh chaos suite (nightly, serialized via
`.config/nextest.toml`).

## Interpretation

Note for readers of older docs: there is no WASM runtime in this workspace.
Signature solving is QuickJS-in-process; the "content-addressed WASM
delivery" in `server/` ships the Kotlin Compose wasmJs browser bundle, not a
Rust-compiled solver. The workspace `default-members` list duplicates
`streamify-playback` (harmless) and predates three crates — the table above
is authoritative. The two-runtime P2P exception is accepted debt, not the
pattern for new code.

---
Verified against commit 8b858b1c. Staleness protocol: docs/adr/README.md.
<!-- doc-verified-against: 8b858b1c89b91f643cd7159180706fbd931f925e -->
