# W3-B — Streamify Wrapped Analytics, Jam Room Live Voting & Reactions, and Custom 10-Band EQ Presets

**Status:** Proposed (Wave 3-B)
**Branch:** `ws-w/w3b-wrapped-jam-eq-presets`
**Date:** 2026-12 (post W2-A/W2-B, parallel to W3-A)
**Owner:** Engineer B (Wave 3-B)

## Context

Wave 3-B is the final user-experience, social, and audio-customization wave
for Streamify Web. Locked rulings this ADR extends:

1. **The playTally invariant** (`decisions/p2/P2B-playback-spine.md`): a
   play counts when actual played time crosses
   `min(duration / 2, 4 min)` — #191's `PlaySessionTracker` fires, starts
   never count, skips never count. P4-A materialized those crossings into
   the time-windowed listening ledger
   (`api/streamify/wrapped/listening-ledger.ts`,
   `P4A-downloads-wrapped-debug.md`).
2. **The Jam lane** (`decisions/p4/P4B-jam-eq-theming-settings.md`): the
   48-byte op journal is byte-frozen and Rust-mirrored
   (`crates/streamify-jam-crdt`, golden vectors in `fixtures/`); the
   broadcast lane (jam_sync / jam_meta / jam_control) is JSON with the
   `v: 1` strict-decoder discipline.
3. **The DSP chain** (P4-B): `gains → preamp → 10× peaking biquads →
   limiter → destination`, with band/preamp clamps applied BEFORE
   persistence and a watchdog that bypasses to the direct path on silent
   output.
4. **Isolation:** zero modifications to `playback/resolver/**`,
   `relayBase.ts`, `metaBase.ts`, `front-end/relay/**`, or Engineer A's
   W3-A surface (`features/streamify/connect/`, `media-session/`, the
   diagnostics card, `right-controls.tsx`).

## A. Streamify Wrapped & Listening Analytics (`features/streamify/wrapped/`)

### The data lane (exclusively the ledger)

Every W3-B metric is a fold over the SAME `LedgerEvent` rows P4-A
aggregates — no parallel play counter exists anywhere:

- **`wrapped-engine.ts`** (pure, `features/streamify/wrapped/`):
  - *Listening rhythm* — `computeWrappedRhythm(events, window, now)`:
    24-slot hour histogram + a 7-slot weekday histogram (local time, the
    same `Date#getHours`/`getDay` semantics P4-A's `byHour` uses), with
    peak hour / peak weekday as argmax and **earliest-index tie-breaking**
    (deterministic).
  - *Streaks* — `computeListeningStreak(events, now)`: local calendar
    days with ≥ 1 qualifying play; `longestDays` is the longest
    consecutive run, `currentDays` only counts a run **ending today or
    yesterday** (anything older broke).
  - *Share-card contract* — `formatWrappedShareCardData(...)`: a pure
    formatter (top 5 tracks, top 3 artists, total verified minutes,
    streak label, window label) so the canvas layer stays dumb and the
    numbers are exactly the ledger's.
- **Route wiring**: the P4-A route (`features/streamify-wrapped/routes/`)
  keeps its window selector and gains the rhythm card, the discovery
  card, and the share-card button via `getWrappedExtended(window)` —
  which seeds from the live snapshot exactly once per query (the
  `getWrappedStats` rule).

### The discovery-origin annotation (the one ledger change)

"Top Discovery" needs to know HOW a track was first introduced. The
ruling: **the recommendation surface that first queues a track claims it**,

- `api/streamify/wrapped/origin-hints.ts` — a bounded (512-entry,
  first-writer-wins, FIFO-evicted, best-effort-persisted) registry
  `id → origin`, keyed by videoId (what catalog surfaces hold).
- `automixRadio()` (W1-B) claims `ORIGIN_AUTOMIX` for every track it
  surfaces — mood radio rides the same claim. W2-B's Discover Mix claims
  `ORIGIN_DISCOVER_MIX` at its enqueue point (the post-rebase hook).
- `recordCountedPlay` gains an optional `origin` on its fire-path payload,
  resolved by the controller's `onCountedPlay` wiring via
  `discoveryOriginOf(trackId, snapshot)` (trackId → direct hint, else
  song row → videoId hint). **The scrobble spine is untouched** — no
  `ScrobbleEvent`/outbox/diagnostic field changed, so the
  observability-guard pinned sets hold. Cloud backfill rows and
  pre-W3-B rows parse to an honest `origin: null` (organic).

### The graduation stat

`computeDiscoveryGraduation(events, window, now)`: a track **graduates**
when its first-ever ledger row carries a recommendation origin
(`automix` | `discover-mix`), it has ≥ 2 total plays, and the first play
falls inside the window (the P4-A first-ever walk, applied to origins).
Sorted by count DESC then title ASC — deterministic; honest empty when
nothing has graduated (the registry starts recording now, and the card
says so).

### The share card

`components/wrapped-share-card.tsx` renders a 1200×630 card on an
offscreen canvas (devicePixelRatio-scaled for crispness, system-ui fonts,
the live theme accent with a fixed fallback), then
`canvas.toBlob('image/png') → object URL → anchor click` — **zero network
calls, zero external assets**. The geometry lives in an exported
`drawWrappedShareCard(ctx, data, accent)` so it stays reviewable; the
data is the pure formatter's output (pinned by tests).

### Navigation

The Wrapped sidebar entry + route shipped in P4-A (PATCHES A17/A18,
`AppRoute.STREAMIFY_WRAPPED`) — W3-B adds no new navigation seam; the
extended cards render inside the existing page.

## B. Jam Room Live Queue Voting & Reactions (`features/streamify/jam/`)

### Vote semantics (the frozen-fold contract, honored not extended)

The CRDT's VOTE register already guarantees **+1 per sender**
(`votes: Map<senderNonceHex, opId>`, max-op-id overwrite). W3-B treats
that as the toggle contract:

- `upvoteTrack` gains a client-side idempotency guard
  (`hasMyJamVote` → skip) so re-clicks mint no ops; the fold would dedupe
  anyway. **Vote removal is out of scope by design**: an un-vote needs a
  new op type in the byte-frozen 48-byte row (Rust mirror + golden
  vectors) — not worth breaking the freeze for a social nicety.
- The 48-byte codec, the fold, the checksums, and the Rust mirror are
  untouched.

### The voting presentation order

`jamVotingOrder(elements, { pinnedAddOpId })` (pure, in
`features/streamify/jam/jam-voting.ts`): pending candidates sort by
**(voteCount DESC, addedAt ASC)** — and `addOpId` is
`unix_ms<<16 | counter`, so ascending op id IS ascending added time (the
deterministic tie-break). **The currently playing element is pinned
first** and never reordered by votes.

- The controller's `publishQueue()` now publishes the voting view (with a
  per-item `voted` flag for this device). **The CRDT's fractional storage
  order is untouched** — votes never write reorder ops, respace is
  unchanged, and canonical state stays vote-independent.
- The playing element is derived per device: exactly one live element
  matching the local bridge's current track cad (`generateCadIdU64`).
  Ambiguous (duplicate cad) or absent → no pin. The protocol's reserved
  `currentAddOpId` / `trackAddOpId` fields stay null pending the future
  consumption lane; this derivation is the honest bridge.

### Ephemeral live reactions

- **Wire**: a fourth broadcast event `jam_reaction` on the same
  `jam_<CODE>` channel — `{ emoji, kind: 'reaction', senderNonce, ts, v: 1 }`,
  validated by a strict `decodeJamReactionMessage` (whitelist of exactly
  🔥 ❤️ 🎧 ⚡ 👏; bad ts/nonce/version → null). Broadcasts are
  fire-and-forget like the existing lanes; **never journaled** — a
  reaction is display-only state.
- **Flood control**: `canSendJamReaction(lastSentAtMs, nowMs)` enforces
  one emit per 500 ms per client, applied in the controller's
  `sendReaction` (injected clock, so tests pin the boundary). The local
  echo pushes to the store immediately (the channel is
  `broadcast: { self: false }` — senders never hear their own).
- **Render**: `features/streamify/jam/components/jam-reaction-bar.tsx`
  renders the five triggers plus a floating-badge overlay (CSS-module
  keyframe float, 24-slot capped store feed with 10 s age-out). Buttons
  disable when the room is not live; an offline/local-only playlist (or a
  null Supabase client → null controller) renders nothing and throws
  nothing.
- The store (`streamify-jam.store.ts`) stays transport-free: it only
  gains `reactions` + `pushReaction` and the `voted` flag on
  `JamQueueItemView`.

## C. Custom 10-Band EQ Presets (`features/streamify/eq-presets/`)

### The share codec (a frozen wire contract)

`preset-codec.ts` — payload `{ v: 1, name, preampDb, bands: number[10] }`
(band gains positional over the 10 ISO centers), compact JSON → UTF-8 →
**URL-safe base64** (no `+`, `/`, `=`), riding the node+browser
`bytesToBase64` helpers from playlist-sync/wire. The decoder is liberal
in input form (raw JSON with a leading `{` is accepted) and strict in
shape:

- **Typed errors, never thrown parses**:
  `empty | bad-base64 | bad-json | bad-schema | bad-bands` (the panel
  maps each to a literal i18n key — no dynamic `t()` templates).
- **Exactly 10 finite band gains** and a finite `preampDb` are required;
  names are sanitized (control chars stripped, 40-char cap, honest
  fallback).

### Clamping (the P4-B invariant, restated and enforced twice)

Every gain is clamped to **[-12, +12] dB** and the preamp to
**[-12, +12] dB** on BOTH encode and decode — so a hostile share code
can never materialize an out-of-bounds `EqPreset`, and the only shapes
that reach the store, the settings write-through
(`eqBandsForSettings` → `settings.playback.equalizer`) and the live graph
(`eqBandsForEngine` → BiquadFilterNodes) are pre-clamped. The P4-B
limiter stays in the chain regardless (enabled or transparent), and the
watchdog bypass path is untouched — importing a malformed or extreme
preset can neither clip audio nor break the AudioContext.

### The bank and the store

- `BUILTIN_EQ_PRESETS` gains **Spatial Air**
  (`[-2, -1.5, -1, 0, 0, 0.5, 1, 2, 3.5, 4.5]`, preamp −1 — an airy
  top-octave lift with a gently tamed low end) and display names for the
  whole bank (ids stay stable).
- `importCustomPreset(preset)`: id-deduped against the existing bank
  (`custom:rock` next to a saved `custom:rock` becomes `custom:rock-2`),
  persisted in the `streamify.eq.v1` blob, selected, and applied through
  the same write-through `applyPreset` uses.
- The panel (`streamify-eq-panel.tsx`, the P4-B drawer's EQ tab) gains
  **Copy Share Code** (any selected preset, built-ins included), an
  **Import Share Code** popover (paste → typed error or applied + toast),
  and **Rename** for customs — Save/Delete were already there. The slug
  derivation (`custom:<slug>`) is now shared between the save and import
  lanes (`eqPresetSlugId`).

## Tests (network-free)

- `api/streamify/__tests__/w3b-wrapped.test.ts` — rhythm folds (TZ-
  agnostic fixtures, tie-breaking, window filters, honest nulls), streak
  math (consecutive local days, yesterday survival, broken currents),
  the origin lane (first-writer-wins persistence, ledger annotation with
  the invariant's playedSec intact, the Automix claim through a fake
  provider), graduation (ranking, window demotion, organic exclusion,
  cold start), and the share-card data contract (minutes rounding, top-5
  slice, streak threshold).
- `api/streamify/__tests__/w3b-jam-voting.test.ts` — per-sender vote
  idempotency at the fold (both orders + duplicates converge), the
  (voteCount DESC, addedAt ASC) order with pinning (and an unresolvable
  pin ignored), the reaction decoder (whitelist, ts/nonce/version
  rejection), the 500 ms throttle boundaries, the capped store feed, and
  the controller wiring end-to-end (idempotent upvote ops, voting-order
  publish with the `voted` flag, playing-track pin, gated reaction
  broadcasts + inbound decode) against minimal fakes.
- `api/streamify/__tests__/w3b-eq-presets.test.ts` — base64url round-trips
  (plus the liberal raw-JSON form and the 12→10 band resample), clamps
  in both directions, typed rejection of every malformed shape, name
  sanitization, the Spatial Air built-in, and the store's
  import/dedupe/rename/delete persistence through a memory localStorage.
- Updated pinned suites: `wrapped-stats.test.ts` (fixture carries the new
  `origin` field), `eq.test.ts` (seven built-ins, display names,
  bank length 8), `jam-session-controller.test.ts` (FakeTransport gains
  the `broadcastReaction` lane).

## Upstream hygiene

- **i18n**: new keys ONLY in `src/i18n/locales/en.json` —
  `streamifyWrapped.*` (rhythm/streak/discovery/share-card),
  `streamify.jam.*` (upvoted, votingHint, reactionsTitle),
  `streamify.eq.*` (share/import/rename + the five typed-error strings).
  The 38 Weblate catalogs are untouched (`scripts/check-i18n.mjs`
  enforces; the panel's error mapping uses literal keys only).
- **PATCHES.md**: A8 (en.json) extended; the jam-live B-entry extended
  (reaction lane + voting publish); a new B-inventory block for the
  nested `features/streamify/{wrapped,jam,eq-presets}/` tree; the
  `streamify-jam.store.ts` / `streamify-eq.store.ts` entries extended.
  No new upstream seams — the Wrapped nav entry is P4-A's (A17/A18), and
  the EQ/Jam UI edits are fork-owned files.
- Zero overlap with Engineer A's `ws-w/w3a-connect-mediasession-diagnostics`:
  no `right-controls.tsx`, no `features/streamify/connect/` or
  `media-session/`, no settings diagnostics.

## Consequences

- Wrapped's Top Discovery is honest about its own birthday: origins only
  start recording once W3-B ships, so the card explains the empty state
  rather than fabricating attribution.
- The voting order is presentation-only: the CRDT converges identically
  with or without votes, and move up/down (manual reorder ops) remains
  available — the next vote publish re-sorts, which is the documented
  social contract ("votes sort the pending queue").
- Reaction spam is bounded at the emitter (500 ms) AND the receiver
  (whitelist + strict decode), so a hostile client can at worst push
  whitelisted glyphs at the Supabase broadcast lane's own cadence.
- The EQ share code is a commitment: `v: 1` with 10 positional gains is
  now a cross-install wire format; future band layouts must version up.
