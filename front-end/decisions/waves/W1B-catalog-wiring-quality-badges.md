# W1-B — Deep InnerTube Catalog Wiring, Two-Tier Lyrics & Honest Quality Badges

**Status:** Proposed (Wave 1-B)
**Branch:** `ws-w/w1b-catalog-lyrics-badges`
**Date:** 2026-12 (post P5-A/P5-B cutover)
**Owner:** Engineer B (Wave 1-B)

## Context

After the P5 cutover (Feishin is the canonical web client; the legacy
`front-end/web` tree is archived), the 3-wave expansion begins. Wave 1
splits cleanly: Engineer A builds the Edge KV metadata spine (W1-A — the
Cloudflare Worker `/ytm/meta` route and `api/streamify/metadata/
provider.ts`'s live implementation), while W1-B (this ADR) wires the
Feishin catalog/lyrics/search/favorites surface against that spine and
ships the honest quality badges.

The locked discussion-page rulings W1-B must honor:

1. **Honest telemetry guardrail** — player-bar quality badges read
   directly from the existing `StreamUrlResolution` (#185/#186) and the
   P4-A OPFS manifests (#192); NO extra `getInfo` RPCs, NO fake or
   guessed badges, and a `Spatial / 5.1` badge is gated strictly on
   `audioChannels > 2` (never inferred from track titles).
2. **Canonical scrobble invariant** — any playTally-driven repeat
   filtering relies strictly on the canonical
   `min(half-duration, 4 min)` actual-played-time rule recorded in
   `front-end/decisions/p2/P2B-playback-spine.md` (the Automix exclusion
   set below consumes only ledger entries that rule already produced —
   W1-B never counts a skip as a play).

## The MetadataProvider seam (merge-in-either-order)

`front-end/feishin/src/renderer/api/streamify/metadata/provider.ts`
freezes the agreed typed contract:

- `getLyrics(videoId, hl?)`, `getArtist(browseId, gl?, hl?)`,
  `getAlbum(browseId, gl?, hl?)`, `getExplore(gl?, hl?)`,
  `getHomeFeed(gl?, hl?)`, `getUpNext(seedVideoId, continuation?)`,
  `getRelated(seedVideoId)`, `search(query, filter?, continuation?,
  gl?, hl?)`, `getSearchSuggestions(input, gl?, hl?)`.

Rules: every method resolves `null` on miss (never throws for
not-found, never fabricates); DTOs are the normalized `Ytm*` shapes
declared in the same file (InnerTube AST internals stay inside W1-A's
implementation — the StreamifyController remains unaware of them);
`gl`/`hl` are caller hints, W1-A owns the defaults.

W1-B ships a **stub provider** (`stubMetadataProvider`, installed by
default) that resolves an honest miss for every method, plus
`installMetadataProvider()` — W1-A's registration entry point. Consequence:
**W1-B merges before or after W1-A with zero structural rewrite.** While
the stub is installed, every catalog branch below falls back to the exact
P1–P4 snapshot behavior; the moment W1-A registers the Edge KV-backed
provider, the same call sites go live. The LRCLIB tier-1, the
album/artist favorites ledger, and the quality badges are live
independently of W1-A (no provider involvement).

Zero-overlap boundary honored: W1-B does not touch `front-end/relay/`,
`metadata/provider.ts`'s Edge KV/InnerTube internals (only the frozen
contract + stub + installer it created), or `features/streamify-import/`.

## Two-tier synced & fallback lyrics

`api/streamify/lyrics/` (new fork-owned tree; upstream
`features/lyrics/` untouched):

- **Tier 1 — LRCLIB** (`lrclib-web.ts`): the web build's first tier.
  Upstream's remote-lyrics lane is Electron-only (`window.api.lyrics` is
  null on web); lrclib.net is CORS-open, so the renderer queries it
  directly, call-for-call with the Electron client (`getLyricsBySongId`
  naming parity). Best-match ranking: synced > plain, title fidelity,
  artist fidelity, ±3 s duration proximity.
- **Tier 2 — YTM InnerTube** (`getLyrics` through the provider; Edge KV
  7d TTL on W1-A's side): fills the LRCLIB misses common on regional,
  indie and day-one releases.
- **The synced rule** (`lyrics-pipeline.ts`): `synced: true` beats plain
  text ACROSS tiers — the precedence is
  LRCLIB-synced > YTM-synced > LRCLIB-plain > YTM-plain. A plain LRCLIB
  hit never shadows a timestamped YTM result.
- **Surface**: `StreamifyController.getStructuredLyrics` returns
  `StructuredLyric[]` and `getLyrics` returns the single-lyrics shape;
  `getServerInfo` now advertises `LYRICS_MULTIPLE_STRUCTURED: [1]` so the
  stock `fetchLocalLyrics` lane calls the structured endpoint. The stock
  precedence machinery (override > preferLocal > remote) is untouched.

## The ID namespaces (deep wiring contract)

- `ytm:<videoId>` — a YTM song outside the snapshot.
  `getStreamUrl` decodes the prefix straight to the resolver: playback
  never requires snapshot membership. Scrobble/tally facts degrade
  honestly for such rows (empty facts, the 4-min threshold cap — the
  same posture as a stale queue restore).
- `ytm-album:<browseId>` — `getAlbumDetail` resolves it via
  `provider.getAlbum` (tracks mapped to songs).
- `ytm-artist:<browseId>` — `getAlbumArtistDetail` /
  `getAlbumArtistInfo` resolve it via `provider.getArtist`.
- Snapshot rows keep their cloud track ids. Cross-tier dedup key is the
  `(title, artist)` signature: a YTM result matching a snapshot row reuses
  the snapshot row (likes/play-stats joins stay intact).

## The 11 wired endpoints (DTO & pagination contracts)

All list shapes honor `BasePaginatedResponse` (`items, startIndex,
totalRecordCount` — `totalRecordCount` is the FULL merged length before
slicing, exactly like `searchSnapshot`'s section semantics). Every
provider miss keeps the P1 honest fallback.

| Endpoint | W1-B wiring | Fallback (pre-W1-A / miss) |
| --- | --- | --- |
| `getLyricsBySongId` | LRCLIB web client (tier 1) | null miss |
| `getStructuredLyrics` | two-tier pipeline → `StructuredLyric[]` | `[]` |
| `getSimilarSongs` | `getUpNext` continuation pages (Automix), playTally-filtered, deduped, seed-excluded | P1 play-stats derivation |
| `getRandomSongList` | genre/mood tile → mood radio (scoped-search seed → Up Next chain); unfiltered → Automix seeded from the tally's strongest track | seeded library shuffle; genre/year filters stay the honest empty |
| `getGenreList` | `getExplore` moods + genres as Genre tiles (`ytm-mood:`/`ytm-genre:` ids) + the New Releases pseudo-tile (`ytm-new-releases`, present only when the home feed answered) | honest empty |
| `getTopSongs` | scoped artist search → `getArtist` top songs, merged ahead of the user's play-ranked rows, deduped by signature | P1 `topSongs` |
| `getAlbumList` | `artistIds: ['ytm-artist:…']` → discography grid (albums + singles + live); `genreIds: ['ytm-new-releases']` → home-feed new releases | P1 filter/sort/paginate |
| `getAlbumArtistDetail` | `ytm-artist:` → structured artist page DTO | snapshot row |
| `getArtistList` | favorite filter now backed by the item-favorites ledger (`likedArtistIds`) | snapshot derivation |
| `search` | scoped merge: snapshot matches first, then provider results, deduped; playlists ride the fork DTO and are dropped at the controller (feishin's `SearchResponse` has no slot) | `searchSnapshot` |
| `getFavoriteAlbums` / `getFavoriteArtists` | fork-side paginated reads over the item-favorites ledger (`catalog/favorite-items.ts`) | honest empty |

Documented honest limits: mood/genre tiles carry no album/song listing
(no `getPlaylist` in the W1-A contract — mood tiles drive the radio
instead); mood/genre Genre tiles report `songCount/albumCount = 0`; YTM
album DTOs carry `createdAt/updatedAt = ''` (remote-only releases have
no replica arrival time).

### The Automix exclusion set (playTally ledger)

`catalog/automix.ts recentExclusions` unions:

1. the playTally `topTracks` keys (the local ledger of counted plays —
   produced exclusively by the canonical
   `min(half-duration, 4 min)` threshold crossing; see
   `front-end/decisions/p2/P2B-playback-spine.md`), mapped to videoIds
   and (title, artist) signatures through the snapshot; and
2. the snapshot's cloud play events inside a 3 h recency window (the
   tally carries no timestamps; the events do).

Candidates are excluded by videoId OR signature, so covers/alternate
versions of a just-played track rotate out too. `AUTOMIX_MAX_PAGES = 5`
bounds the continuation walk (the "endless" contract is the wiring, not
an unbounded loop).

### Artist discography & related graph

`catalog/discography.ts`: `getArtist` splits into Albums, Singles & EPs,
Live Performances, Featured On (`appearsOn`) and Fans Also Like
(`relatedArtists`, surfaced through `getAlbumArtistInfo.similarArtists`);
`getRelated(seedVideoId)` supplies the song-level alternate
versions/covers. The album grid pages through
`getAlbumList({artistIds: ['ytm-artist:…']})`.

### Predictive typeahead

`catalog/search-suggest.ts createSearchSuggester`: a 280 ms debounce
(250–300 ms band) with AbortController cancellation — a new input
retires the previous round's timer AND aborts its in-flight provider
call. Local snapshot suggestions merge first; the provider tier is
skipped entirely once the local tier is saturated. Feishin's stock
search bar is upstream and untouched; this is the fork-side suggester
surface (query lane + pure debouncer, network-free testable).

## Album & artist favorites (cross-device persistence)

The #191 ledger extended: `user_likes` is track-shaped, so album/artist
favorites live on a new `user_item_favorites` table
(`(user_id, item_type, item_id)` PK, RLS fenced exactly like
`user_likes` — migration `supabase/migrations/2026_12_w1b_item_favorites.sql`
+ `schema.sql` §4b).

- Snapshot load gains `likedAlbumIds` / `likedArtistIds` sets (the
  `fetchItemFavorites` lane, loaded in the existing parallel batch).
- `createFavorite` / `deleteFavorite` route album/artist types through
  `mutateItemFavorite`: live snapshot flip ALWAYS, cloud write
  best-effort, the same bounded offline journal as track likes (the
  `FavoriteOp` gains an optional `itemType`; coalescing keys on
  `(trackId, itemType)`; old journaled track ops stay valid).
- `filterAlbums`/`filterArtists` favorite filters and
  `normalizeAlbum`/`normalizeAlbumArtist` `userFavorite` flags read the
  sets — the stock favorites pages and heart toggles work unchanged.

## Honest quality badges (player bar)

`features/streamify-badges/` (new fork feature) + one minimal upstream
seam (right-controls.tsx mounts `<StreamifyQualityBadges />`; PATCHES.md
A14).

- **Zero extra network RPCs, by construction**: the component reads the
  resolver's cache (`peekStreamUrl(videoId)` — the resolve playback
  already performed) and nothing else. The offline state derives from
  the negotiated resolution itself: P4-A's zero-egress serve hands the
  element a `blob:` URL, so `resolution.url.startsWith('blob:')` IS the
  OPFS manifest read-out.
- **Truthful labels**: `Opus · 160 kbps`, `AAC · 128 kbps`, `HLS`,
  `OPFS Offline` — codec from `resolution.codecs`, bitrate from the live
  rung (rounded kbps; unknown bitrate renders the codec alone; unknown
  everything renders nothing).
- **The strict Spatial rule**: the badge renders ONLY when
  `resolution.audioChannels > 2` (5.1 at ≥ 6 channels). `null` — the
  wire did not say — always suppresses. Never inferred from titles,
  codecs or itags.
- **The `audioChannels` plumb**: `StreamUrlRung` and
  `StreamUrlResolution` gain `audioChannels: number | null`, populated
  from the wire's `audioChannels` (raw PlayerResponse rungs) or
  `audio_channels`/`audioChannels` (youtubei.js rungs, defensively —
  absent maps to null). OPFS offline resolutions honestly carry `null`.
  A light 1 s tick re-reads the cache (a Map read, no IO) so the badges
  track the async resolve/expiry without any store mutation.

i18n: the only new user-visible strings (`streamify.badges.title`,
`streamify.badges.offline`) live in `en.json` only; the 38 Weblate
catalogs fall back via `fallbackLng: 'en'` and the P5-B
`scripts/check-i18n.mjs` gate enforces the policy.

## Upstream hygiene

- Upstream files touched: `right-controls.tsx` (one import + one element,
  PATCHES.md A14) and `en.json` (append-only, A8). Nothing else.
- New fork trees: `api/streamify/metadata/`, `api/streamify/lyrics/`,
  `api/streamify/catalog/`, `features/streamify-badges/` (inventoried in
  PATCHES.md section B).
- Tests: network-free Vitest suites
  (`w1b-lyrics-fallback`, `w1b-catalog-wiring`, `w1b-search-suggest`,
  `w1b-quality-badges`) — every provider interaction is a fake; the
  LRCLIB fetcher is injectable; the debouncer's scheduler is injectable.

## Consequences

- W1-B is shippable independently of W1-A; the catalog wiring lights up
  when the Edge KV provider registers. LRCLIB synced lyrics on web, the
  favorites ledger, and the honest badges are live immediately.
- `getStreamUrl` accepting `ytm:` ids widens playback beyond the
  snapshot; scrobble facts for such rows degrade honestly (documented
  above) and queue persistence drops unknown ids on restore (unchanged
  P3-B semantics).
- The `user_item_favorites` table is additive; deployments that do not
  run the migration simply journal-and-drop (bounded, 5-attempt poison
  drop — the same offline posture as track likes).
