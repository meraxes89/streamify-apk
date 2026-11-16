# P4-A — OPFS + wasm chunk-cipher downloads, Wrapped stats, and the /debug gate

**Status:** implemented (Phase 4-A, feishin side)
**Scope:** `front-end/feishin` (features/streamify-{downloads,wrapped,debug}, api/streamify/{downloads,wasm,wrapped}, store/streamify-{downloads,debug}.store.ts) + `front-end/crates/streamify-wasm` (the cipher feature split)
**Base:** P3-A (#190) + P3-B (#191) — the scrobble fire path and playlist-sync state are P4-A integration points.

---

## 1. The wasm feature split (streamify-wasm)

One crate, two disjoint facades (Cargo features):

- **`engine` (default)** — the historical W0.2/W0.4 rustypipe surface, moved *verbatim* to `src/engine.rs`. Bare builds and the `web-build.yml` lanes are unchanged: same export surface, same wire shapes. The default artifact drifted from the committed `web/src/wasm` copy only in layout (the code moved modules); CI stages a fresh pkg for every web build, and nothing checks the committed copy — `front-end/web/**` stays untouched.
- **`cipher`** — `src/cipher.rs`: XChaCha20-Poly1305 per-chunk AEAD, the vault-v2 family contract (`rust/crates/streamify-storage/src/crypto.rs`, SEC-06) in a wasm32 scratch-region ABI:
  - every 64 KiB chunk sealed under a fresh 24-byte XChaCha nonce;
  - the chunk index is AEAD associated data (truncation / reordering / cross-file grafting all fail authentication);
  - **zero JS imports** except wasm-bindgen 0.2.130's 5-line externref table initializer, provided by hand in `cipher-loader.ts` — no wasm-pack glue JS is vendored or linted;
  - randomness stays JS-side by design (`crypto.getRandomValues`): `getrandom` gets a trap backend so the RustCrypto aead stack compiles on wasm32; reaching it is a programming error, not a code path.

**Deterministic staging** (`crates/streamify-wasm/build-feishin-cipher.sh`):
`cargo build --release --target wasm32-unknown-unknown --no-default-features --features cipher` → `wasm-bindgen --target web` → copy to `feishin/src/renderer/api/streamify/wasm/streamify_cipher_bg.wasm` + rewrite `artifact.json` (sha256, rustc, wasm-bindgen, feature flags, byte size). Rebuilds are byte-identical for the same toolchain (verified: two consecutive builds, same sha256). Feishin CI enforces the pair **without any workflow change**: the vitest `wasm-artifact.test.ts` fails if the committed bytes and the committed manifest disagree, and the cipher contract tests run against the actual committed artifact. A local `.gitattributes` (`*.wasm binary`) guards the artifact against the tree-wide `text eol=lf` rule — a CRLF pass corrupts the wasm (caught during staging; the manifest test would also catch it).

## 2. The STREAMIFY_DL_V1 container (OPFS at-rest format)

All integers little-endian; the record framing mirrors vault-v2:

```
"STREAMIFY_DL_V1" (15 B) ‖ version:u32 ‖ chunkSize:u32 ‖ totalPlainLen:u32 ‖ chunkCount:u32
record × chunkCount := ctLen:u32 ‖ nonce:24 B ‖ ct:ctLen B ‖ tag:16 B
```

`ctLen` is derivable (chunkSize for all but the last record) but stored anyway — the vault-v2 robustness choice: the exact-size check catches truncation **before** any authentication attempt. Per-file layout math lives in `format.ts` (`recordOffset`, `containerSize`, `plainLenForChunk`); the header codec validates `chunkCount === ceil(total/chunkSize)`.

**Key placement (integrity-first, documented honestly):** the per-file 32-byte key lives in the sidecar `meta.json` next to the ciphertext in the same OPFS directory (`streamify-downloads/`). A local attacker with OPFS read access can recover audio but **cannot corrupt an entry undetectably** — every Poly1305 tag still rules. Feishin is a local media app caching the user's own tracks; this matches the task's threat model (tamper/truncation/corruption → quarantine). The sidecar is not itself authenticated: a tampered *key* fails auth at serve (quarantine); a tampered *label* mislabels metadata without integrity impact.

**Sidecar fields:** videoId/songId/title/artist/album/durationSec, mimeType, rung + codecs (provenance), chunkSize/chunkCount/totalPlainLen/ciphertextBytes, key (base64), status (`downloading | complete | corrupt`), createdAt/completedAt.

## 3. The download pipeline (zero relay egress at serve time)

**Download:** `queueSongDownload(songId)` → snapshot row → videoId → `getStreamUrl` (progressive rung only; SABR/HLS are honest failures — no fetchable byte stream) → `fetch` the resolved URL → read the body in network-sized segments → buffer to exactly-chunkSize plaintext chunks → `sealChunk` per chunk (fresh nonce, index AAD) → stream header + records into OPFS. Content-Length present → streaming; absent → in-memory `sealContainer` fallback (one write). Per-track progress (receivedBytes/totalBytes), rung/codec metadata, and integrity status publish through `streamify-downloads.store.ts`.

**Failure & quota hygiene:** user cancel / network abort / `QuotaExceededError` / declared≠received size mismatch → the partial container AND its sidecar are removed (no orphans); quota errors surface a sticky per-track note. Crash orphans (sidecars stuck at `downloading` with no live job) are swept at boot (`sweepOrphans`, idempotent).

**Offline serve (the bypass):** `stream-source.ts`'s `resolveStreamifyTrack` checks OPFS **first** — a `complete` entry is size-checked, per-chunk authenticated, decrypted, and served as a seekable `Blob:` URL with honest resolution facts (`protocol: 'progressive'`, `rangeSupport: true`, the download's rung as provenance, `contentLength` = plaintext bytes). On a hit the resolver and the Cloudflare relay are **never touched**; on any failure (size mismatch, auth error, wasm/OPFS unavailable) the entry is quarantined (`status: 'corrupt'`, container deleted, sidecar kept for the UI) and the track falls back to online resolution. Blob URLs are tracked per video and revoked on re-serve. An *element* error on an offline-served source takes the dedicated offline branch in `recoverFromError`: quarantine + forced online re-resolve (`forceRefresh`) — integrity failures never block playback.

The resolver import in the downloads layer goes straight to the vendored mirror (`resolver/audio/get-stream-url`) — no import cycle with stream-source; the mirror owns resolution, stream-source owns engine wiring.

## 4. Wrapped — the listening ledger

**Data backing (the task's ruling):** playTally verified listens — #191's `PlaySessionTracker` threshold crossings (never starts, never skips) — extended with the timestamps the aggregate-only tally cannot carry. The optional `onCountedPlay` observer rides the fire path (after `applyPlayToSnapshot`; a throwing observer never breaks the scrobble/outbox path).

Two sources merge deterministically (`listening-ledger.ts`):
- **local rows** — every counted play with full facts (album, played time, rung), persisted in `localStorage` (`streamify.feishin.wrapped.v1`, 5000-row cap);
- **cloud seed** — `snapshot.playEvents` (`user_play_events`, the durable record the scrobble outbox drains into), re-derived per snapshot load, never persisted here.

**Merge rule (no double-count):** cloud events with `created_at` strictly older than the oldest local row are prepended; anything newer is dropped — by then the outbox has drained it, so it is already a local row. Rare multi-device interleaving can undercount an offline row whose drain landed beyond the boundary; overcounting (the dangerous direction) is impossible. Cloud rows carry no album/duration (the wire has neither) — honest zeros, ADR'd here.

**Aggregation (`aggregates.ts`, pure):** windows 7d/30d/YTD/All-Time; total listening time, verified play count, top tracks/artists (count desc, then seconds; top 10), discovery-vs-repeat, and the hour-of-day histogram. **Discovery:** a play is a discovery iff it is the *first-ever* counted play of its track in the full merged ledger (a track first played before the window and replayed inside it is a repeat). **Zero states:** empty windows yield `count: 0`, `discoveryRatio: null`, zeros everywhere — never NaN, never a broken chart. **Top genres:** the ledger carries the field and the aggregation is live + tested, but the cloud schema has no genre column today (the library.ts honest-empty precedent) — the UI renders an explicit pending state until the schema grows one.

## 5. The /debug gate

Registered at `/debug`, **no sidebar entry**, gated by the pure ruling (`debug-gate-ruling.ts`, directly unit-tested):

1. `import.meta.env.DEV` — vite dev mode always passes;
2. the persisted developer-mode grant (`streamify-debug.store.ts` → `localStorage streamify.feishin.debug.v1`; opt-in from the route's switch, so a previously gated session stays gated);
3. `?debug=1` **only** for an authenticated admin session (`session.isAdmin` — the flag exists on the session seam and was unused until now).

Everyone else gets a clean redirect to `/`. Panels are read-only plus one safe action (Flush Outbox): resolver rungs & cache (`recentResolveLog`), OPFS & cipher wasm health (quota, init status, the deterministic self-test), scrobble outbox (depth, `lastFlushAt`, drain through the live session adapters), playlist CRDT sync (outbox depth, `syncedAt`/`syncing`, `playlistPersistTimestamp`, `playlistRealtimeStatus`), and the wrapped ledger's local/cloud row counts. The two playlist introspection exports are additive read-only accessors in P3-A's `playlist-sync/` — no lifecycle changes.

## 6. Test surface (network-free, Feishin CI)

Six suites, 44 new tests (209 total incl. the P3 base):

- `wasm-artifact.test.ts` — the manifest sha256 gate + the committed artifact's self-test.
- `downloads-cipher.test.ts` — round-trip, fresh-nonce ciphertext divergence, ct/tag tamper rejection, chunk-reorder (AAD) rejection, ABI validation, container layout/truncation/bad-magic/one-bad-record.
- `downloads-lifecycle.test.ts` — progress tracking, cancel cleanup (abort-honoring fake fetch), quota failure, size mismatch, crash-orphan sweep, quarantine-on-tamper, offline serve (blob URL, no resolver hit).
- `offline-bypass.test.ts` — the REAL `stream-source.ts` driven with a fake downloads instance: local serve without resolver traffic, miss → online ladder, element error → quarantine + forced re-resolve.
- `wrapped-stats.test.ts` — window filtering, aggregation + top-N sorting, zero states (null, never NaN), discovery semantics, the cloud merge rule, persistence lane.
- `debug-gate.test.ts` — the pure ruling: default users, anonymous `?debug=1`, non-admin, admin, developer-mode, DEV.

## 7. Coordination & hygiene

- Engineer B (`ws-w/p4b-jam-eq-theming`) owns Jam live-queue UI, EQ DSP, visualizer, brand theming, Streamify settings pages — zero overlap: P4-A touches none of those; route registration is append-at-stable-anchors on both sides.
- Upstream touches are minimal + ledgered (PATCHES.md §24–28): routes/router (the sanctioned route category), settings.store v35 sidebar migration (the fork's own v10 precedent), the three sidebar label maps + icons, en.json sections, four one-line context-menu entries. `features/login|servers|player|components` and themes: untouched except the P2-A seams already carried. `front-end/web/**`, `.github/workflows/**`, `shared/**`: untouched.
