# P1 decision: facade gaps (filed, not worked around)

Status: FILED (handed to Engineer A / the engine track)
Owner: Engineer B (filing), Engineer A (engine/facade disposition)

House rule: engine-level gaps land in the engine, the controller only
exposes. This file is the list of what P1 reads needed or will soon need
that the current wasm facade (`front-end/crates/streamify-*` in the
streamify monorepo) does not expose to JS. None of these were worked
around in the fork; each read either uses real data or degrades honestly.

## 1. No library-query surface on the wasm facade

The facade's JS-reachable surface today: the playback control plane
(snapshots, commands, queue), the jam session ops lane (op pack/unpack,
fractional index, cad ids, room state), and the proof-console pair
(`search_music`, `resolve_stream`). The local TrackRepository library
(scanned files, folders, downloads) is not queryable from JS - the same
gap `front-end/web/src/lib/libraryStore.ts` documents.

Disposition for the fork: reads run against the cloud library snapshot
(queries the web app already issues). When a facade library-query surface
lands, its rows merge into the same snapshot (the bridge owns one seam)
and local content lights up with zero controller changes.

Priority note: not needed for the P1 exit (browse is cloud-backed);
becomes needed when the fork should show local/jam content.

## 2. Jam session queue is not JS-readable

The jam CRDT session document (the shared queue) has no read projection
on the facade (`get_jam_room_state` returns participants, not tracks).
Nothing in P1 browse needs it. P2/P3 (jam surfaces in feishin) will: the
read projection (queue rows with fracs and authors) is the facade ask.

## 3. OPFS-backed persistent cover cache cannot slot into feishin's image lane

Feishin's `ImageRequest` contract is synchronous (`{ url, cacheKey }`,
fetched by `useNativeImage`), so an async OPFS read cannot participate
without either:

- a service worker that intercepts the cover URL lane (upstream's web
  build owns its PWA config in `web.vite.config.ts` - off the fork's
  pre-approved touch list), or
- upstream component changes (off-limits by fork policy).

P1 ships the URL lane (external cover URLs, stable cacheKeys, feishin's
session cache + browser HTTP cache). The persistent OPFS cache should
ride the same SW lane the monorepo's W2.0 media bridge established
(`front-end/web/src/lib/mediaBridge.ts` + `public/sw.js`) when that
pattern is ported to the fork - an A-track decision, not a B workaround.

## 4. Genre data does not exist in the cloud schema

Not a facade gap strictly (it is a `tracks` schema question): no genre
column anywhere in the library data. The genre read set is implemented
and honestly empty. If genres matter for the feishin surface, the schema
addition + backfill is the prerequisite; `normalizeGenre` is ready.

## 5. No co-play / similarity signal beyond own play counts

`getSimilarSongs` uses same-artist + own-play-count ranking. A richer mix
needs either the radio scorer's candidate lane (the facade's
`score_and_rank_radio_candidates` exists on the proof-console module -
wiring it into the fork is a P2+ playback-adjacent decision) or a
co-play matrix in the cloud stats. Recorded so the derivation in
`derive.ts` has a named upgrade path.
