# W1-A — Cloudflare Edge KV metadata cache, the `metadata/provider.ts` seam & the 1-Click YTM importer

**Status:** COMPLETE. Branch `ws-w/w1a-edge-kv-metadata-importer`, PR against
`main` (based on post-P5 `main` — #195/#196 already landed, so the branch
inherits the consolidated PATCHES.md, the canonical resolver tree and the
check-i18n.mjs gate). CI: Feishin CI, Web Build (wasm/wasm-tests/**relay**),
Unified CI preflight, CI gate (blocking).

## The decision this record locks

Three related decisions, one architecture:

1. **A Cloudflare Worker Edge KV cache replaces the Supabase `ytm_cache`
   idea entirely.** The discussion rejected a client-writable Postgres
   metadata table (`with check (true)` JSONB poisoning, storage-exhaustion
   vectors). The rejection extends to ANY client-writable cache: only the
   WORKER writes the edge cache, and only after re-fetching the data from
   YouTube itself and validating the response server-side. A browser can
   at most *probe* the cache with different keys; it can never *plant*
   content in it.
2. **`StreamifyController` stays strictly snapshot-backed and unaware of
   InnerTube AST internals.** A new
   `api/streamify/metadata/provider.ts` seam owns the
   `Edge KV → InnerTube (youtubei.js) → Normalize → Snapshot Upsert`
   pipeline for all *public* YTM browse/metadata traffic. Engineer B (W1-B)
   wires the controller's catalog/lyrics/search endpoints against this
   seam; the controller never parses an engagement panel.
3. **The importer is a pure consumer of the seam** — URL parsing, batched
   continuation ingestion with live progress and AbortSignal cancellation,
   materialized into #190's Jam CRDT playlist store (the same
   `createStreamifyPlaylist`/`addTracksToStreamifyPlaylist` surface the
   rest of the app uses).

---

## A. Where each pipeline stage lives

The four-stage pipeline spans two runtimes. The division of labor:

| stage | runtime | module |
| --- | --- | --- |
| Edge KV lookup + SWR + single-flight | worker | `front-end/relay/src/ytm-meta.ts` |
| InnerTube upstream fetch | **worker** (server-side; raw `fetch`) | `front-end/relay/src/ytm-meta.ts` |
| Normalize | **worker** (server-side, authoritative) + client-side only for the fallback path | worker `ytm-meta.ts` / feishin `metadata/normalize.ts` |
| Snapshot Upsert | client (per-session) | feishin `metadata/snapshot.ts` |

### Why the worker performs the InnerTube fetch

Three candidate designs were considered:

- **(a) client-fetches, client-POSTs-to-cache** — rejected: the write
  would be client-writable. Schema validation catches malformed shapes,
  not semantically-poisoned valid ones, and there is no per-user auth on
  the relay yet (the P4/W4.1 TODO). This is the same poisoning vector the
  discussion rejected, one tier lower.
- **(b) worker-side youtubei.js dependency** — rejected: the worker is
  deliberately runtime-pure (`Pure Workers runtime — no external
  dependencies`, its file header). youtubei.js's JIT evaluator needs
  `eval`/`new Function` behind compatibility flags, drags a player
  retrieve for the signature timestamp, and adds a 500 kB+ bundle to a
  worker whose whole security posture is being small and auditable.
- **(c) worker-side raw innertube POSTs** (CHOSEN) — the shapes are
  stable public wire formats (browse/search/next/player/
  get_search_suggestions on the music client). The extraction logic
  (shelf walkers, engagement-panel readers) is ported from youtubei.js
  18.3.0's own parsers — the same version feishin pins — with provenance
  comments at each ported walker. The worker already does raw-fetch
  pragmatism (`/bg-session` homepage regex extraction); this is the same
  discipline, one tier up.

The client-side youtubei.js fallback exists ONLY for degraded modes: the
relay is unreachable, the deployed worker predates `/ytm/meta`
(capability check on `/`), or the worker's upstream fetch failed. It
rides the existing `getYt()` session (relay lanes, visitor seed) and
normalizes through the mirrored client-side normalizer. The fallback
never writes the edge cache.

### The session identity (worker-side)

The innertube calls need a visitor identity + client version. The worker
reuses the `/bg-session` homepage cache: the ytcfg JSON it already
extracts carries `INNERTUBE_API_KEY`, `INNERTUBE_CLIENT_VERSION` and
`VISITOR_DATA`-equivalent (`visitorData`). A second homepage-shaped fetch
of `music.youtube.com` (same cache shape, keyed by UA) supplies the music
client version for WEB_REMIX. Both are cached per-UA exactly like
`bgSessionCache`; a failed extraction degrades the innertube call to a
client-version-less body (still functional; the version field is
advisory, not gated).

## B. The cache contract (worker)

### Cache key

Every key encodes the full regional/locale identity — the invariant that
keeps regional charts and localized titles from bleeding across
countries/languages:

```
ytm/v1/<endpoint>/<gl>/<hl>/<urlencoded(id-or-query)>[/<fnv1a64(continuation)>]
```

- `endpoint` ∈ {lyrics, credits, chapters, artist, album, explore, home,
  up-next, related, search, search-suggest, playlist}
- `gl`/`hl` default to `US`/`en` when absent — the defaults are part of
  the key, never implicit.
- continuation tokens are long opaque strings; they enter the key as an
  FNV-1a-64 hex digest (bounded keys, same digest discipline as the CRDT
  cad hasher).

### TTL matrix (enforced)

| endpoint class | TTL | stale-serve policy |
| --- | --- | --- |
| getLyrics / getCredits / getChapters | 7 days | serve stale; background revalidate **only if payload empty or (lyrics) `synced: false`** |
| getArtist / getAlbum | 24 h | SWR: serve stale + background revalidate |
| getExplore / getHomeFeed | 6 h | SWR |
| getUpNext / getRelated | 12 h | SWR |
| search | 15 min | SWR |
| getSearchSuggestions *(unlisted — documented choice)* | 15 min | SWR (same class as search) |
| getPlaylist *(unlisted — documented choice)* | 12 h | SWR (playlist contents are editable; 12 h aligns with the up-next mutability class) |

The lyrics rule is the subtle one: a fully-synced lyrics payload is
treated as terminal — background revalidation would not improve it —
while an empty or plain-text payload is flagged for refresh so a later
fetch (after LRCLIB lands in later waves) can upgrade it.

### Envelope + storage layers

```
{ v: 1, endpoint, key, cachedAt, expiresAt, payload }   // payload:null + missing:true for empty-normalized results
```

- **In-memory Map** (per-isolate, always present, capped ~512 with
  insertion-order eviction — isolates recycle anyway).
- **KV binding `YTM_CACHE`** — read on memory miss; written on fill.
  KV `expirationTtl = ttl + STALE_WINDOW` (STALE_WINDOW = 3 × TTL), so an
  entry is *stale-serveable* for three TTLs past expiry and then vanishes
  — bounded residency, no unbounded growth on the free tier.
- **Binding absent** (local `wrangler dev`, CI tests) → in-memory only;
  the absence is detected per-request, never thrown.

### Single-flight + SWR

One in-flight promise per cache key (module Map). A miss with a pending
fill for the same key awaits that promise — concurrent identical requests
coalesce into one upstream fetch. Background revalidation (SWR) routes
through the same single-flight map, so a revalidate piggybacks on an
in-flight fill instead of doubling it. `ctx.waitUntil` keeps the
revalidation alive past the response.

### Server-side validation (before caching)

- JSON.parse failure → 502 (schema parse error — never cached, never
  served).
- Parsed-but-oversized (> 2 MiB) → served to the caller, **not cached**
  (the negative-cache `too-large` precedent; a >2 MiB metadata payload is
  anomalous, not representative).
- Normalized payload shape check per endpoint (the payload must carry the
  fields its schema promises — `synced: boolean` on lyrics, etc.) →
  invalid shape is served but not cached.

## C. The provider seam (client)

`api/streamify/metadata/provider.ts` exports the `MetadataProvider`
interface (the typed contract Engineer B wires the controller against)
and the default singleton:

```
getLyrics(videoId, hl?)            getUpNext(seedVideoId, continuation?)
getArtist(browseId, gl?, hl?)      getRelated(seedVideoId)
getAlbum(browseId, gl?, hl?)       search(query, filter?, continuation?, gl?, hl?)
getExplore(gl?, hl?)               getSearchSuggestions(input, gl?, hl?)
getHomeFeed(gl?, hl?)              getPlaylist(browseId, continuation?, signal?)
```

Failure separation (three distinct typed outcomes, no cross-contamination):

- **not-found** (`kind: 'not-found'`): edge answered `missing: true`, or
  the fallback normalized to an empty shelf. Zero retries, zero backoff —
  a missing entity is not an error condition; it is cached in the
  snapshot (15 min) so repeat calls in one session never re-hit.
- **rate-limited** (`kind: 'rate-limited'`): HTTP 429 anywhere in the
  chain. Exponential backoff on the edge call (3 attempts, 250 ms base),
  then the **circuit guard** opens for 60 s during which every edge call
  fails fast with the typed rate-limited result (the caller may still get
  the fallback path — the circuit guards the EDGE, not the provider).
- **parse-error** (`kind: 'parse-error'`): payload failed client-side
  validation. Logged diagnostic, then a clean fallback to the client-side
  InnerTube lane; if the fallback also fails, the typed parse-error result
  surfaces (Engineer B's UI shows an honest error state).

The per-call pipeline: snapshot hit → edge (relay base from
`relayBaseCandidate()`) → on edge miss/unavailable: client-side youtubei.js
fallback (`getYt()` session) → normalize → snapshot upsert. The snapshot
is in-memory, per-session-keyed, and stores the last-known payload per
cache key (same key scheme as the worker — one key discipline across the
seam).

## D. The importer (`features/streamify/import/`)

Nested namespace rule honored: the importer lives at
`src/renderer/features/streamify/import/` — the first nested entry under
`features/streamify/` (flat `streamify-*` siblings stay untouched). All
UI components, hooks and the URL parser live inside it; nothing leaks to
a flat top-level directory.

- **URL parser** (`url-parser.ts`, pure): accepts raw IDs (`PL…`,
  `OLAK5uy_…`, `RDCLAK5uy_…`, `VL…`) and public YouTube / YouTube Music
  URLs (`list=` params, `music.youtube.com` / `www.youtube.com` hosts).
  Output: `{ kind: 'playlist' | 'album', listId, browseId }` normalized
  forms (PL-ids get the `VL` browse-namespace prefix youtubei.js uses;
  OLAK5uy_ list-ids pass through as album browse targets).
- **Ingestion engine** (`ingest.ts`): streams `provider.getPlaylist`
  continuation pages in batches. Each batch: (1) upserts the catalog
  `tracks` rows (merge-duplicates — the FK the sync pass's
  `playlist_tracks` inserts depend on), (2) mints the ADD ops via
  `addTracksToStreamifyPlaylist` (tail fracs, jam anchors — unchanged),
  (3) reports progress `(imported / total?)`. The playlist shell is
  created once up-front (`createStreamifyPlaylist`) with the YTM title +
  description. Cancellation via AbortSignal: checked between pages;
  already-materialized batches STAY (CRDT ops are real and converge —
  rolling back tombstones the whole batch and is strictly worse).
- **Trigger**: one minimal button in feishin's playlist list header
  (logged in PATCHES.md as the only upstream-file seam) opening the
  importer modal. The modal, progress state and abort wiring are
  fork-owned components under `features/streamify/import/components/`.

**i18n policy**: every new user-facing string lands in
`src/i18n/locales/en.json` ONLY (`streamifyImport.*` group). The 38
Weblate-managed catalogs are untouched — `check-i18n.mjs` stays green
by construction. Note: the check's `t()`-reference scan walks
`features/streamify-*` entries; the nested `features/streamify/import/`
directory is outside that walk (name filter is `startsWith('streamify-')`),
so its `t()` literals are verified by this wave's own vitest suite
(importer render assertions) instead — checked with B, who owns the
script; the gap is recorded here, not papered over.

## E. Formal amendment of the P1 cloud.ts boundary rule

P1's rule was: *"reads are local-first, no NEW Supabase round-trips"* —
cloud.ts mirrors the web app's library-load lanes call-for-call, nothing
more. This wave AMENDS the rule, explicitly and narrowly:

> **The Cloudflare Edge KV cache (`/ytm/meta` on the relay worker) is the
> global metadata cache for public YTM browse/metadata traffic.** No
> Supabase table is added for YTM metadata (the `ytm_cache` idea is
> dead). No client-writable cache exists at any tier. The provider seam
> is the ONLY module that may issue YTM browse/metadata network calls;
> everything else (controller, features) consumes the snapshot it upserts.

Everything else in the P1 rule stands: the library snapshot lanes in
cloud.ts are unchanged, the controller's local-first reads are unchanged,
and the favorites/likes lanes are unchanged.

## E2. The deploy record (live verification, 2026-11-16)

**Version `08ee1805-21b1-49f5-a9f3-d9e1888678ab`** on
`https://streamify-relay.zephyr4289.workers.dev` — the KV namespace
`YTM_CACHE` (`275cb7d17fe34ea59626c1ea4ba71dc9`) created and bound. The
`/` capability list now carries `ytm-meta`. Every wire shape below was
probed END-TO-END against the deployed worker (not just the sandbox):

| probe | result |
| --- | --- |
| `lyrics lYBUbBu4W08` (the 2-leg MPLY flow) | fill → (KV propagation) fresh; 58 lines, source Musixmatch |
| `lyrics <bad id>` | `missing: true` — the not-found form |
| `playlist PL9ADD…` (VL-normalized) | title "90s r&b and hip hop hits"; 101 + 47 tracks across 2 pages, last page `continuation: null` |
| `playlist OLAK5uy_l4Z8…` (the album-import form) | 17 tracks, each with title/artist/album from the three-column layout |
| `album MPREb_LQwx7Jq6u9H` | "Abbey Road (2019 Mix)", 17 tracks |
| `artist UC2XdaAVU…` | "The Beatles" + "54.7M monthly audience"; `gl=DE&hl=de` isolates into its own key with German-localized text — the regional invariant, live |
| `explore` / `home` | 2 / 1 carousel shelves |
| `search filter=songs` | "Never Gonna Give You Up" first result |
| `search-suggest` | live suggestions (the exact wire shape the youtubei.js fallback cannot serve) |
| `up-next` / `related` | 1 track (the direct panel — the automix follow is youtubei.js's job, documented) / 3 shelves |

The KV eventual-consistency window measured ~20 s (fill → fresh across
isolates) — the in-memory layer absorbs per-isolate repeats meanwhile.

## F. Verification matrix

- **Network-free vitest** (feishin): provider normalization + snapshot
  upsert + 404/429 separation (mocked fetch), URL parser table
  (PL/OLAK5uy_/RDCLAK5uy_/VL + URL forms + rejects), continuation
  batching + abort + CRDT materialization against the real sync engine
  (the cloud-simulator pattern from the P3-A suite).
- **Network-free vitest** (relay, new lane): key encoding (gl/hl
  isolation), TTL selection, `synced` flag preservation, single-flight
  coalescing, KV-absent fallback, payload validation — all against
  exported pure functions + fixture innertube JSON.
- **Live verification** (deploy, not CI): the worker is redeployed with
  the route; `/ytm/meta` is probed with real video/browse ids before the
  PR is opened. CI never grows a network lane — the daily SABR corpus
  stays playback-scoped.

## G. Risks & honest limits

- **Wire-shape risk** (worker-side raw innertube): mitigated by porting
  from youtubei.js 18.3.0's own parsers (same version feishin pins) +
  live deploy verification. A shape drift upstream of YouTube's is a
  production monitoring concern (the `source` field on every response
  distinguishes `edge-fill` from `edge-fresh`/`edge-stale`, so a
  broken-fill incident is visible as 502s on fill states).
- **Free-tier KV write budget** (1 k/day): a first-fill miss is one
  write. The in-memory layer absorbs repeat traffic per-isolate; the KV
  layer is the cross-colo amplification damper, not the primary. If the
  budget binds, the documented lever is STALE_WINDOW (longer windows =
  fewer re-fills).
- **The fallback duplicates normalization logic** (worker-side and
  client-side normalizers share a schema, not code). Accepted: the two
  runtimes import different input types (raw JSON vs youtubei.js typed
  objects); forcing a shared module across the workspace boundary would
  couple the worker to the feishin tree. The SCHEMA is the contract; both
  sides' tests assert the same shapes.
