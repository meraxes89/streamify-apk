# P3A — Jam CRDT playlist backing (Feishin StreamifyController)

**Status:** COMPLETE. Branch `ws-w/p3a-jam-playlists`, PR against `main`.
CI: Feishin Web CI (lint + typecheck + tests + build:web), Unified CI
preflight, CI gate (blocking).

## What landed

The complete playlist endpoint surface on `StreamifyController`, backed by
a TypeScript port of the Jam CRDT engine (the same 48-byte op row, fractional
indexing, and hybrid-clock discipline the Rust/wasm/Kotlin engines froze)
and a Supabase sync layer with deterministic multi-device convergence:

| Endpoint | Implementation |
|---|---|
| `getPlaylistList` / `getPlaylistListCount` | CRDT ordered view through the P1B query engine (filter/sort/paginate reused) |
| `getPlaylistDetail` | CRDT doc -> `normalizePlaylist` |
| `getPlaylistSongList` | CRDT order + snapshot metadata join; `playlistItemId` = the element's add-op id (decimal) |
| `getPlaylistSongIds` | ordered track ids |
| `createPlaylist` | doc mint + metadata LWW register; row insert deferred to the sync pass |
| `updatePlaylist` | metadata LWW register write (full snapshot per write) |
| `deletePlaylist` | absorbing tombstone; durable row delete re-issued by the sync pass |
| `addToPlaylist` | Add ops at tail fracs (`fracAfter`, the jam anchors) |
| `removeFromPlaylist` | songIds as feishin song ids (all elements of the track) OR playlistItemIds (per-item, the Navidrome lane) |
| `replacePlaylist` / `setPlaylistSongs` | stable element matching, surplus Removes, missing Adds, one Reorder per moved element onto a fresh `0.5 + i` grid |
| `movePlaylistItem` | one Reorder at the ending-index midpoint (`fracIndexForInsertAt`, the jam insert helper) |
| `uploadPlaylistImage` / `deletePlaylistImage` | still stubs (optional endpoints; no storage bucket exists in the cloud schema - covers are track-derived URLs) |

## The CRDT data model

Ported 1:1 from `rust/crates/streamify-jam-crdt` (see `api/streamify/jam/`):

- **The 48-byte op row** (`jam-op.ts`): identical little-endian layout, the
  FNV-1a-32 checksum over bytes [0..40), zero-pad rejection, and the
  op-id mint (`unix_ms << 16 | 16-bit counter`, ratcheted over clock
  step-back, per-device counter seed). u64s cross every boundary as
  decimal strings (the `front-end/web/src/lib/jamEngine.ts` bridge
  discipline); the op wire crosses as bytes (base64 on the broadcast
  lane), never JSON numbers.
- **Fractional indexing** (`fractional-index.ts`): the frozen Kotlin/Rust
  anchors (`FRAC_HEAD = 0.0625`, `FRAC_FIRST_INDEX = 0.5`, tail `+1.0`,
  prepend `/2`, midpoint), the ULP collision probe, and the
  relative-ULP rebalance latch with the host-run respace (compaction)
  pass.
- **CAD ids** (`cad.ts`): the FNV-1a-64 hasher, byte-exact
  `generate_cad_id_u64` (golden vectors minted from an independent
  transcription of `cad.rs`); playlist elements mint their cad from the
  durable track id's UTF-8 bytes (elements reference catalog rows, not
  raw content), with the track id riding out-of-band beside the op - the
  JamTrackCodec/TRACK_META discipline.

The playlist adaptation (`playlist-crdt.ts`):

- **Element identity is its Add op's `op_id`** (jam B2 lineage). Every
  element carries an ORDER REGISTER `(frac, order_op_id)`.
- **Remove ops tombstone** the target element; tombstones are absorbing -
  a late Add replay or a late Reorder of a tombstoned element is a no-op
  on every replica, in any delivery order.
- **The ordered view** sorts live elements by the composite
  `(frac_bits, add_op_id)` key - the jam R1 rule: concurrent inserts into
  the same gap tie-break deterministically by op id.
- **Metadata is one LWW register** (full-snapshot per write); **delete is
  an absorbing tombstone** - dead docs absorb every later op, so stale
  edits never resurrect a deleted playlist.

## Conflict-resolution rules (and why they are what they are)

The register/meta comparison key is deliberately **counter-free**:
`(hybrid-clock ms, frac bits)` for order registers, `(ms, deterministic
value order)` for metadata. The durable table carries only the ms half of
a register clock (`added_at` / `updated_at`), so any rule that depended on
the op id's 16 counter bits would diverge between the origin device and a
replica that adopted the register from a row. The counter-free key is
computable identically from an op and from a row, which makes the live
lane (broadcasts) and the anti-entropy lane (table sync) agree by
construction. This is the one deliberate semantic refinement over the jam
engine, whose Reorder assumes causal delivery - honest for a live room
queue, not for playlists edited offline on two devices. Same-ms concurrent
reorders resolve by frac bits; same-ms renames by the lexicographic
full-value order; both deterministic everywhere.

## The durable mapping (zero schema changes)

The CRDT state rides the existing `playlists` / `playlist_tracks` tables:

- `playlist_tracks.id` (UUID) = **big-endian `add_op_id` (64 bits) ||
  big-endian `frac_bits` (64 bits)**. The first half is the element
  identity - every row of one element lives in the half-open UUID block
  `[pack(op, 0), pack(op+1, 0))`, so a remove materializes as ONE range
  DELETE that kills the element at any frac (concurrent reorders
  included), and the packing is a deterministic function of the op, so
  INSERTs are idempotent by primary key across devices. Big-endian hex
  means Postgres' bytewise uuid comparison equals the u64 numeric order,
  so PostgREST `gte`/`lt` implement the block bounds exactly.
- `track_id` = the element's value; `added_by` = the acting user.
- `added_at` = the order register's clock (ms) - the LWW timestamp, also
  what a reader compares without extra columns.
- `position` (INT) = a dense-rank cache the sync pass maintains for the
  legacy web app's `order=position.asc` reads (feishin reads CRDT order).
- `playlists.updated_at` = the metadata register's clock (ms).
- A missing playlists row IS the delete tombstone: PATCH on a missing row
  is a 0-row no-op and `createPlaylist` mints fresh UUIDs, so stale
  writes cannot resurrect.
- **Legacy v4 rows** (the shipping web app's `uuid_generate_v4()` lane)
  are separated by the variant nibble (a v4 UUID always carries `4` at
  hex 12 and `10xx` at hex 16; a packed row's second half is a positive
  finite f64's bits whose top nibble is `0x3/0x4` - never `10xx` - so the
  separation has zero false positives either way). Legacy rows are
  ingested deterministically from `(position, added_at)`; CRDT mutations
  compose on top, and reorders convert the row to packed form via the
  id-rewrite PATCH.

## Supabase sync triggers

- **Local mutations** (controller endpoints): apply to the CRDT doc
  first (local-first; the UI updates immediately), enqueue the op-log
  (outbox), persist, invalidate the TanStack Query keys, then a 300 ms
  debounced anti-entropy pass materializes the engine state into the
  tables and broadcasts the ops.
- **The anti-entropy pass** (`playlist-sync/sync.ts`) - load rows, merge
  table->docs (adopt newer registers, adopt unseen elements/playlists,
  tombstone confirmed-then-vanished ones), respace latched docs, repair
  docs->table (delete stale/loser rows, insert missing elements and
  never-durable playlists, rewrite reordered row ids + positions,
  re-issue tombstones, patch metadata, re-densify the position cache),
  clear the outbox, persist, invalidate. Every statement is idempotent
  (PK-conflict-ignoring inserts, range deletes, PK-rewrite patches), so
  a failed pass simply retries on the next trigger; the `remoteConfirmed`
  element flag distinguishes "not yet flushed" (insert) from "deleted
  remotely" (tombstone).
- **Realtime**: Supabase Realtime broadcast channel `playlist-ops:<userId>`
  (zero migrations, zero tables) carries ops/meta/delete deltas as the
  sealed 48-byte rows; receivers validate the checksum, merge through the
  same CRDT lane, persist, and invalidate - open playlist screens update
  live. Broadcast is a latency optimization; the tables + the pass are
  the correctness mechanism (a device that misses a broadcast converges
  on its next sync).
- **Triggers**: first read of a session (P1B parity: load once, then
  local-first), post-mutation debounce, 90 s idle interval, window
  `online` events, and sign-in (the session binder hydrates the
  persisted replica per user).
- **Offline resilience**: CRDT state + pending op-log persist to
  localStorage per user (`streamify.p3a.playlists.<userId>`); offline
  mutations serve from the local replica and flush on reconnect.

## The session integration fix (P1A/P1B gap)

P1A's synthetic server entry stores the raw access token as the
credential, but P1B's session codec expects a `StreamifyCredentialPayload`
JSON - signed-in users silently fell to the anonymous dev session and
every authenticated read came back empty. `resolveStreamifySessionAsync`
(P3A, additive - the sync resolver is untouched) resolves: the payload
codec, then the LIVE supabase-js session, then the dev fallback. The
snapshot memo key is now refresh-stable (user identity, not the token),
so hourly silent token refreshes no longer force full library reloads.

## Endpoint mapping (feishin contract -> CRDT ops)

| Feishin call | Ops minted |
|---|---|
| `createPlaylist(body)` | doc + meta register; `ownerId`/`queryBuilderRules`/`sync` have no cloud-schema meaning (no smart-playlist engine, no sync target) and are ignored |
| `updatePlaylist(query.id, body)` | meta register write (name from body; comment/public default to current) |
| `deletePlaylist(query.id)` | delete tombstone + pending-op cleanup |
| `addToPlaylist(query.id, body.songId[])` | one Add per song at `fracAfter(last)` |
| `removeFromPlaylist(query.id, query.songId[])` | one Remove per matched element |
| `replacePlaylist` / `setPlaylistSongs` | Removes (surplus) + Adds (missing) + one Reorder per moved element at `0.5 + i` |
| `movePlaylistItem(query)` | one Reorder at the ending-index midpoint |
| `getPlaylistSongList` | ordered view joined against the library snapshot; `playlistItemId` = add-op id (decimal) - what `removeFromPlaylist` accepts per-item |

Cache reactivity: local mutations AND incoming remote deltas invalidate
`[serverId, 'playlists']` (list/count/detail/songList/songListIds) plus
the playlist infinite-loader key; playlist writes that add tracks also
drop the library-snapshot memo so the song join reloads lazily.

## Scope boundaries (honest)

- Collaborative playlists: RLS already permits collaborator writes; their
  ops converge through the durable tables + sync pass, but the live
  broadcast channel is per-owner-user (collaborators get eventual, not
  immediate, deltas). A per-playlist channel is a follow-up if
  collaboration becomes a first-class surface.
- Smart playlists (`queryBuilderRules`) and `sync` flags: no engine
  exists; ignored (recorded above).
- `position` densify is capped at 200 patches per pass (the cache is
  best-effort; the legacy web UI catches up over successive passes).
- The op-id counter's 16-bit space admits the same cross-device
  same-millisecond collision probability the jam engine accepts
  (~1/65536 per same-ms op pair, further reduced by the per-device
  counter seed); a collision drops one op, exactly like the jam journal's
  `(session_code, op_id)` dedupe would.

## Verification

65 tests, 9 files, network-free by construction (the cloud is an
in-memory PostgREST simulator at the module boundary; realtime/logger/
query-client are module-mocked; the CRDT + sync engine runs for real):

- `jam-op.test.ts` (8): byte layout at every offset, FNV seal + tamper
  rejection, pad rejection, DTO u64 discipline, op-id ratchet.
- `fractional-index.test.ts` (5): the Kotlin-anchored behaviors.
- `cad.test.ts` (5): independently minted golden vectors + bucket edges.
- `element-id.test.ts` (5): packing round-trips, block ranges, bytewise
  order, v4 separation.
- `playlist-crdt.test.ts` (13): the two-replica convergence suite - both
  delivery orders for concurrent inserts at the same index, reorder vs
  remove, same-ms reorders, rename vs rename, delete vs edit, late-add
  suppression, add-replay inertness, duplicate idempotency, corrupt-op
  rejection, serialization round-trip, rebalance/respace.
- `outbox-persistence.test.ts` (5): queue dedupe/ordering, hydration,
  restart round-trip, device id stability.
- `playlist-sync-convergence.test.ts` (6): two replicas through the real
  sync engine against one shared cloud - concurrent adds, reorder vs
  remove, rename vs rename, delete vs edit (no resurrection), offline
  flush + duplicate-flush idempotency, remote-removal propagation.
- `playlist-controller.test.ts` (10): full CRUD + add/remove/replace/move
  round-trips through the controller, offline survival, legacy v4
  ingestion, query invalidation.
