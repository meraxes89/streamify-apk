# P3-B — Feishin favorites, curation & the playback persistence/scrobble bridge

**Status: COMPLETE** — 2026-11-16. Branch `ws-w/p3b-favorites-curation`. Local
gates green (vitest, tsc renderer clean; the full vite build runs in Feishin CI
on GitHub Actions per the 4 GB-box rule).

## Scope (as kicked off)

Close the P2-B playback persistence/scrobble bridge inside `front-end/feishin`
(so #187's PlaySessionTracker, ScrobbleOutbox, and URL-free queue persistence
survive P5's retirement of `front-end/web`), and deliver P3-B: favorites, play
counters (playTally), home-screen curation verification, library filters, and
i18n keys for the Streamify UI surfaces.

## The architecture ruling: port the domain, not the state machine

#186 wired audio playback into feishin, where `player.store` and
`web-player-engine.tsx` already own queue order, shuffle/repeat, and the
dual-slot crossfade. Porting #187's `queueMachine.ts`/`queueStore.ts` would
have created TWO owners of canonical queue state — a split-brain the web spine
was built to prevent. The ruling, per the kickoff: **port the pure domain
modules, never the machine**:

| #187 module (front-end/web) | P3-B home (front-end/feishin) | What changed |
|---|---|---|
| `lib/playback/scrobbler.ts` | `api/streamify/playback/scrobbler.ts` | verbatim port (PlaySessionTracker, ScrobbleOutbox, threshold rule, tick cap, poison/flap policy) |
| `lib/playback/persistence.ts` | `api/streamify/playback/queue-persistence.ts` | re-shaped for feishin's payload: `songIds[]` replaces `rows[]/playOrder[]` (feishin's queue items reduce to canonical ids in order); format v3; per-server lanes; IndexedDB keyed by serverId |
| `lib/playback/queueStore.ts` (runtime) | `api/streamify/playback/scrobble-bridge.ts` | the wiring re-built for feishin's event stream (below) — no machine, no engine mirror |
| — | `api/streamify/playback/play-tally.ts` | `lib/playTally.ts` ported + the snapshot-hydration/apply layer |

## The scrobble bridge: sparse events → actual played time

Feishin's stock `use-scrobble.ts` (untouched upstream code) reports through
`StreamifyController.scrobble`. For a Streamify server the Jellyfin-gated
progress/seek lanes never fire (`REPORT_PLAYBACK` is not in our feature set),
so the observable stream is: `start` (pos 0), `stop` (abandon playhead),
`pause`/`unpause` (playhead), and `submission: true` (position = track
duration, sent when feishin's OWN listen-time accounting crosses — 75% or 240 s
by default, which dominates our min(50%, 240 s) for every track length).

The bridge reconstructs ACTUAL PLAYED TIME from that stream: every
playing-context sample accrues `dt = min(positionDelta, wallClockDelta)` — the
playhead's real advance, bounded by real time (a forward seek accrues only the
seconds it really took; pauses accrue nothing; backwards jumps clamp to zero).
Deltas are fed to the tracker in `MAX_TICK_MS` chunks so the #187 tick cap
keeps its meaning. Event semantics:

- `start` — always a FRESH session (replay re-arm, the #187 ruling).
- `stop` — accrue, then abandon: a skip that never crossed is not a scrobble.
- `pause`/`unpause` — accrue / re-baseline (a seek while paused never accrues).
- `submission` on a fired session — feishin re-armed its own flag (repeat /
  restart-from-zero, the only re-submit paths): a deliberate replay, re-arm ours.

The fire path mirrors the web queueStore: `recordTally` (local counter —
STRICTLY on threshold crossing, never on skips) → `applyPlayToSnapshot` (live
playCount/lastPlayedAt bump so Home shelves and FREQUENT/RECENT sorts refresh
without a snapshot reload) → outbox push → drain. Fired events carry the
resolved-stream rung (`lastRungForVideo`, read from the resolver's bounded log
ring in `stream-source.ts`).

## Play-queue persistence: ids, never URLs

`savePlayQueue` receives feishin's queue (ids in playback order, cursor,
playhead offset), deduplicates (the serializer demands unique ids), clamps the
cursor, and persists through the tiered lanes (localStorage ≤750 k chars →
IndexedDB, single-source invariant, quota → honest "not saved", corruption →
discard). The in-flight scrobble session rides along via
`sessionForQueue(currentSongId)`. `getPlayQueue` resolves the ids against the
CURRENT library (dropped tracks vanish, never a dead row), re-derives the
cursor, continues the scrobble session across the restore (a fired session
never re-fires), and opportunistically drains yesterday's offline outbox.
**The serializer's explicit field list is the URL guarantee** — enforced by
test (a smuggled `googlevideo.com` URL never survives the round-trip).

## Favorites: a favorite IS a cloud like

`createFavorite`/`deleteFavorite` (song / playlistSong / queueSong types —
feishin's own mutation grouping) run `mutateFavorite`: live snapshot flip
ALWAYS (rows + likedIds most-recent-first — the memoized snapshot would
otherwise serve stale flags until the next sign-in), then the cloud write
mirror-of-the-web-app (`tracks` upsert merge-duplicates → `user_likes` insert
ignore-duplicates; delete removes the edge, the shared catalog row stays).
Offline failures journal: a bounded favorites journal (200 cap, 5-attempt
poison drop, any-success flap reset, like/undo coalescing) that drains on the
next mutation, the next `getFavoriteSongs`, opportunistically. A dead session
(401 after refresh) rethrows — the auth seam's clean-sign-out bounce owns it.
Album / album-artist / artist favorites have no cloud model (P1's ruling) —
honest no-op with a debug log. `getFavoriteSongs` reads
`likedIds ⋈ songById` with the artist filter and like-recency order, plus an
opportunistic journal drain.

### The query.ts FAVORITED polarity fix

`sortSongs(FAVORITED)` had its comparator inverted: under the dropdown's
default DESC, favorites SANK below unliked rows. Feishin's own list sort
(`shared/api/utils.ts`) orders `userFavorite` with the requested direction —
DESC = favorites first. The comparator now matches (`ASC = unliked first,
boolean false-first, SQL parity`), locked by test.

### The favorite-optimistic-updates fixes (logged in PATCHES.md #22)

- An unfavorite now REMOVES the song from the `favoriteSongs` cache instantly
  (items + totalRecordCount), instead of waiting for the invalidation refetch;
  the immediate path's `homeQueries.concat(homeQueries)` double-registration
  bug is fixed; the deferred path gains `favorite-songs` and `home-songs`
  lanes (parity with the immediate path).

## Home curation & library filters: the mapping, verified

Feishin's Home shelves (`features/home/routes/home-route.tsx` carousels) drive
`getAlbumList`/`getSongList` with exactly: `MOST_PLAYED → PLAY_COUNT DESC`,
`RECENTLY_ADDED → RECENTLY_ADDED DESC` (NEWEST), `RECENTLY_PLAYED →
RECENTLY_PLAYED DESC` (RECENT), `RANDOM → RANDOM` (the seeded shuffle — stable
within a snapshot load, re-rolls on refresh), plus `PLAYLISTS` and the
feature-carousels (`RANDOM DESC`). Library filter bars add `favorite`
(STARRED), `genreIds`, year ranges, artist/album ids, and search terms. All of
these were ALREADY implemented in P1's `api/streamify/query.ts` over the
snapshot; P3-B's additions: the playTally hydration (`hydrateSnapshotWithTally`
on load; `applyPlayToSnapshot` on every counted play) makes FREQUENT/RECENT
live, the FAVORITED polarity fix makes STARRED honest, and the sort/filter
predicates are now locked by tests (NEWEST, FREQUENT, RECENT with nulls always
last, RANDOM seeded, STARRED filter + polarity, genre honest-empty, search
term, artist/album id resolution). Album `userFavorite` stays `false`
honestly (no cloud model) — the shelves render live, populated, zero broken
cards.

## i18n

`en.json` gains `streamifyAuth.*` (10 keys — the /streamify-auth screen's
strings, previously hardcoded) and `error.streamifyNotConfigured` /
`error.streamifyNoVideo` / `error.streamifySabr` (the honest failure notices
for the unconfigured deployment, the no-video mapping, and the SABR-only
resolve). The route now renders through `t()`. No key-parity gate exists in CI
(the 39-locale catalog falls back to `en` via i18next), and the
`StreamifySabrError`/`StreamifyNoVideoError` toasts themselves surface where
`features/player` renders playback errors — that surface is upstream-owned and
off-limits per the hygiene rules; the keys are in place for it.

## Files (all inside the isolated directories + the one logged upstream touch)

- New: `api/streamify/playback/scrobbler.ts`, `queue-persistence.ts`,
  `play-tally.ts`, `scrobble-bridge.ts`; `api/streamify/favorites.ts`;
  `api/streamify/__tests__/{queue-persistence,scrobbler,scrobble-bridge,favorites,curation,play-queue-controller}.test.ts`
- Edited (ours): `api/streamify/{cloud,query,streamify-controller}.ts`,
  `api/streamify/playback/stream-source.ts` (the `lastRungForVideo` export),
  `features/streamify-auth/routes/streamify-auth-route.tsx`, `src/i18n/locales/en.json`
- Edited (upstream, PATCHES.md #22): `features/shared/mutations/favorite-optimistic-updates.ts`

## Tests

57 new vitest cases (network-free): queue round-trip + URL-free guarantee +
corruption discard + lane routing; the threshold rule (incl. the 4-min cap,
unknown duration), fire-once, replay re-arm, skip abandonment, tick cap;
outbox offline hold, drain, flap reset, poison drop, cap, mid-drain push;
bridge actual-played-time reconstruction (pause, seek-while-paused, forward
seek wall-bound, submission completion, repeat re-arm, rung attachment,
restore continuation); favorites live-flip, journal coalescing/drain/poison/
flap/auth-rethrow, signed-out journaling, unknown-row edge-only ops;
curation predicates (NEWEST/FREQUENT/RECENT/RANDOM/STARRED/genre/search);
controller round-trip incl. dropped-track restore, cursor clamping, empty
clear, scrobble through the controller with fake time, and the favorites
endpoints end-to-end.
