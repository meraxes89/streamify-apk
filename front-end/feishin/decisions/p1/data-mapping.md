# P1 decision: data mapping, ID scheme, fallbacks

Status: ACCEPTED (this PR)
Owner: Engineer B

The full ledger of how jam/library data maps onto feishin's domain shapes,
which values are derived, which are honestly absent, and what every
fallback does. Companion code: `src/renderer/api/streamify/` (ids.ts,
library.ts, normalize.ts, query.ts, derive.ts).

## The data source (what "jam/library data" concretely is in P1)

The library universe a Streamify user can browse is the cloud library the
shipping web app already loads at sign-in, plus the play-event stats lane:

- `user_likes` joined against `tracks` (like order = recency)
- `playlist_tracks` membership + `tracks` by id
- `playlists` (the user's own)
- `user_play_events` (own play events: play counts, recency)

Every query is call-for-call identical to
`front-end/web/src/lib/supabase.ts` in the streamify monorepo - zero new
Supabase round-trips (the P1 rule). The ONE addition: the play-events
select includes `created_at` (the web app's select omits it) so
`lastPlayedAt` is a real timestamp instead of a rank. Same table, same
filters, one more column; noted here as the only delta.

Reads are local-first: the snapshot loads once per (server, credential)
via the bridge (`engine.ts`), and every list/count/detail/search read
resolves in memory. The wasm engine facade exposes no library-query
surface today (see facade-gaps.md), so there is no wasm call in the P1
read path - nothing is faked to pretend otherwise.

## The ID scheme (ids.ts)

All deterministic Java `String.hashCode` derivations, stable across
sessions and byte-compatible with the Android/web apps where they overlap:

| Entity | ID | Derivation |
|---|---|---|
| Song | `trk_<|hash|>` | the tracks table id itself (Android's upsert derivation) |
| Album | `alb_<|hash|>` | hash of `<albumKey>__<artistKey>` |
| Album artist | `art_<|hash|>` | hash of `<artistKey>` |
| Genre | `gen_<|hash|>` | hash of the lowercase genre name |

- Album identity is (album name, album artist). The hand-rolled web UI
  groups albums by name only; feishin's album model carries an album
  artist, so the pairing is the correct mapping here. Accepted delta.
- Play events join onto songs by plain id equality: the recorded
  `track_sig` IS the `trk_...` track id (the web AppShell passes
  `cloudTrackId(title, artist)` as the signature).
- Unknown buckets: "Unknown Album" (per artist), "Unknown Artist".

## Derivations and fallbacks, per read

| Endpoint | Derivation | Fallback when data is absent |
|---|---|---|
| getAlbumList/Count | group songs by (album, artist); songCount, duration, playCount summed; cover = first song cover | - |
| getAlbumArtistList/Count, getArtistList/Count | group songs by artist string; albumCount, songCount, duration, playCount | - |
| getSongList/Count | the snapshot songs; album/artist filters via id -> key resolution | - |
| getGenreList | the tracks table has no genre column | honest empty list (the genre page renders its empty state) |
| search | local case-insensitive substring over songs/albums/artists | - |
| getTopSongs | play-count rank within the artist, from own play events | artist's songs in library order (no invented popularity) |
| getSimilarSongs | same-artist songs (play-count ranked, seed excluded), then most-played library fill | no co-play matrix exists yet (facade-gaps.md) |
| getArtistRadio / getAlbumRadio | artist/album pool shuffled, most-played fill | - |
| getRandomSongList | seeded shuffle with the played filter from real stats | genre/year filters have no data: honest empty result |
| getFavoriteSongs | the likes list, filtered by artist | type='rating' has no data: honest empty |
| getPlaylistList/Count/Detail/SongList/Ids | cloud playlists + resolvable membership | unresolvable track ids are dropped (count reflects reality) |
| getMusicFolderList | the library is one flat collection | honest empty list |
| getAlbumArtistInfo / getAlbumInfo | - | biography/similar/notes do not exist: honest empties |
| getScanStatus | snapshot load = the scan | scanning: false, real song count |
| getInternetRadioStations | no radio data | honest empty list |
| getRoles | one artist string per track, no role model | empty list |
| getServerInfo | browse-only capability surface | features: {}, version 0.1.0 |
| getUserInfo | from the resolved session | no session: throws unauthorized so the app routes to A's flow |

Sort coverage: every feishin sort enum member resolves to a comparator.
Members with no cloud data (rating, bpm, codec, year, genre, community
rating, ...) fall back to the entity's natural sort (name/title). A list
sorted by a missing field is still stable and correctly paginated; the
fallback is explicit in `query.ts` switch defaults, not silent.

RANDOM sort and the random endpoints use a seeded shuffle (mulberry32,
seeded from the snapshot session key): pages are stable within one
snapshot load and re-roll on the next load.

## Timestamps

The cloud rows carry no per-track created/updated timestamps. Semantics
chosen:

- `createdAt` / `updatedAt` on songs and albums = the snapshot's load
  time: the replica's local arrival time, which is exactly what it is.
- "recently added" sorts use the like/membership arrival order the load
  lanes preserve (likes arrive most-recent-first).
- `lastPlayedAt` = the newest play event's `created_at` (real).
- Playlist `createdAt`/`updatedAt` = the cloud rows (real).

## Image lane

Entities carry `imageUrl` (the external cover URL) AND `imageId` (the
entity's own id); `getImageUrl` / `getImageRequest` resolve the same URL
from the loaded snapshot with a stable cacheKey. feishin's in-session
image cache and the browser HTTP cache do the byte caching. See
facade-gaps.md for why the OPFS persistent cache is not wired in P1.

## Known accepted limitations (surfaced, not hidden)

- Favorite/rating mutations are stubs (P3): hearts render from real like
  state, clicking one errors with the not-implemented toast.
- Album-level favorites do not exist in the cloud model; album
  `userFavorite` is false and a favorite filter on albums matches nothing.
- Genre and year filters on songs match nothing (no data to match).
