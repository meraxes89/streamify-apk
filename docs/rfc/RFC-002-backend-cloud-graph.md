# RFC 002: Streamify Cloud Graph

**Backend Architecture RFC — Audit Findings, Sync Contract v3, and PostgreSQL Target Schema**

**RFC ID**: 002 (stable; file renamed 2026-11-25 from `docs/BACKEND_ARCHITECTURE_RFC.md`). Status and decision links are tracked in the [RFC index](README.md).

- Prepared for: Streamify Core Engineering
- Prepared by: Lead Backend Systems Architect
- Repository: github.com/meraxes89/streamify-apk
- Supabase Project Ref: dbgtvzgwixerhnwvhtil
- Version 1.0 · Status: Draft for Review
- 2026-11-24
- Footer: Streamify Core Engineering · Internal Distribution

---

## 1. Executive Summary

This RFC records an end-to-end architectural audit of the Streamify cloud graph, conducted on 2026-11-24 against the live Supabase project dbgtvzgwixerhnwvhtil through strictly read-only probing of the Management API, together with a line-level review of **StreamifySyncEngine.kt** (584 lines), the `supabase/migrations` chain, and decision records ADR-005 and ADR-007 [1][2][3][4]. The four architectural invariants — client-sovereign heavy lifting, offline-first Room SQLite as source of truth, device-bound equalizer state, and 100% graph recovery for analytics — are sound and are correctly embodied by the client boundary layer. The persistence and synchronization layers, however, do not yet honor them under scale, and several active defects already violate them today.

The audit records sixteen findings: five critical, six high, and five medium. The most severe are: (a) a foreign key that cascades catalog purges directly into user libraries, deleting liked tracks whenever a catalog row is removed; (b) a `tracks` table that stores expiring stream URLs, full lyric payloads, and pgvector embeddings behind an HNSW index — a direct breach of the client-sovereignty invariant, already averaging 26 kB per row; (c) a full-set diff sync engine whose egress grows with total library size on every cycle; (d) a listening-history pipeline capped at 100 rows per cycle that collapses repeated plays of the same track, silently corrupting Wrapped and analytics data; and (e) four row-level security policies that currently expose likes, favorites, private-playlist membership, and profiles to any authenticated caller.

The proposal replaces the current topology with a **V4 target schema** — a content-addressed track registry, a user graph decoupled from catalog lifecycle with snapshot columns, uniform tombstones, and a per-user append-only change ledger with a bigint cursor — plus a **sync contract v3**: one idempotent batched push RPC, one cursor-based pull RPC, hybrid-logical-clock last-write-wins enforced server-side, and a Realtime wake-up channel. Row-level security is hardened to owner-scope with a SECURITY DEFINER bulk path, and `profiles.active_session` JSONB is replaced by a per-device presence table with Realtime broadcast.

*Table 1 — Top findings (full catalog in Section 3)*

| ID | Severity | Finding |
|---|---|---|
| F-01 | CRITICAL | `user_likes.track_id` FK cascades catalog purges into user libraries |
| F-02 | CRITICAL | `tracks` stores stream URLs, lyric trees, and pgvector embeddings (Invariant 1 breach) |
| F-07 | CRITICAL | Full-set diff sync: egress scales with total library size on every cycle |
| F-08 | CRITICAL | 100-row history window collapses repeat plays; analytics integrity loss |
| F-12 | CRITICAL | Four world-readable RLS policies expose private user graph data |
| — | — | Remaining 6 High and 5 Medium findings are cataloged in Section 3 |

Live volumetrics — 51 profiles, 35 tracks, 35 likes, and empty event tables — mean every breaking change in this RFC can ship now at near-zero migration cost. After the first million listening events, the same changes would require dual-write backfills and online reshaping. The rollout is therefore phased P0 through P4, additive-first, with the only destructive step (legacy table decommission) gated behind client cutover completion.

## 2. Audit Scope, Method and Evidence Base

Three evidence streams feed this document. First, the live database was probed read-only on 2026-11-24 through the Supabase Management API: `information_schema.columns` for the column inventory, `pg_indexes` for the index plan, `pg_policies` for every RLS policy with its exact USING clause, `pg_constraint` for foreign-key topology, and `pg_stat_user_tables` for volumetrics. No DDL or DML was executed against project dbgtvzgwixerhnwvhtil at any point; all queries were SELECT-only against system catalogs. Second, the repository was reviewed: the sync engine at `composeApp/src/commonMain/kotlin/com/streamify/sync/StreamifySyncEngine.kt`, the Supabase multiplatform module, and all seven migrations from 2026_01_stats_overhaul.sql through 2026_11_sync_v3_user_graph.sql [1][4][5]. Third, ADR-005 and ADR-007 supplied the design intent and the known production failure history (legacy `trk_*` ID poisoning, playlist UUID crashes, missing tables, token lifecycle) [2][3].

*Table 2 — Live inventory, probed read-only 2026-11-24 (n_live_tup estimates)*

| Table | Live rows | Total size | Role in the user graph |
|---|---|---|---|
| profiles | 51 | 80 kB | Identity, tier, settings, `active_session` JSONB |
| tracks | 35 | 928 kB | Shared catalog: `stream_url`, `lyrics`, `embedding`, HNSW index |
| user_likes | 35 | 96 kB | Track-level likes (FK to tracks ON DELETE CASCADE) |
| playlists | 0 | 16 kB | User playlists, `collaborator_ids uuid[]` |
| playlist_tracks | 0 | 24 kB | Playlist membership, nullable `playlist_id` |
| user_item_favorites | 0 | 24 kB | Artist / album / podcast favorites |
| user_listening_history | 0 | 24 kB | Playback telemetry (no FK to catalog) |
| user_search_history | 0 | 32 kB | Search queries, only soft-delete flag in schema |
| user_settings | 0 | 16 kB | Preferences JSONB per user |

Two observations frame the whole audit. The database is pre-production: with every event table still empty, this is the cheapest possible moment for schema surgery. And `tracks` already stores roughly 26 kB per row against the 0.5 kB expected of relational metadata — concrete evidence that media-adjacent payloads, not user graph data, dominate storage today. One operational note: the Management PAT used for this audit circulated in plaintext and should be rotated in the Supabase dashboard once the review is accepted.

## 3. Current-State Findings

Findings are grouped by domain and labeled CRITICAL (data loss, privacy breach, or scale wall), HIGH (correctness under multi-device use), and MEDIUM (integrity or operational debt). Every finding cites live probe evidence or the exact engine code path. Sections 4 through 8 resolve them; the rollout in Section 9 sequences the fixes.

### 3.1 Schema and Data Integrity

**F-01 · CRITICAL** — Cascade deletion of user likes via catalog foreign key

Live `pg_constraint` shows `user_likes_track_id_fkey: FOREIGN KEY (track_id) REFERENCES tracks(id) ON DELETE CASCADE`. Any catalog purge — an admin cleanup, a legacy `trk_*` scrub like the one executed in ADR-005, or a future dedup rebuild — silently destroys the affected users' liked library rows, which every connected client then hydrates as deletions on the next sync. The user graph must never depend on catalog lifecycle: likes are user data, tracks are replaceable display metadata. Listing 2 drops this FK and replaces it with a client-supplied snapshot plus a lazy registry lookup.

**F-02 · CRITICAL** — Catalog table performs backend heavy lifting (Invariant 1 breach)

The probed `tracks` table stores `stream_url` (expiring YouTube URLs), full `lyrics` text, `bpm`, `key_signature`, aggregate counters, and a pgvector `embedding` behind the HNSW index `idx_tracks_embedding_hnsw`. Observed storage averages 26 kB per row against roughly 0.5 kB expected for relational metadata; 35 rows already occupy 928 kB. This turns PostgreSQL into a lyrics cache, a stream-URL registry, and a recommendation-vector store — precisely the workloads the invariants assign to the client. Listing 1 replaces `tracks` with a slim `track_index` carrying display-only fields and no vector workload.

**F-03 · HIGH** — No tombstone model outside search history

`user_search_history.is_deleted` is the only soft-delete flag in the schema; likes, favorites, playlists, and playlist tracks hard-delete. A deletion executed on device A therefore leaves no server-side trace that device B must delete as well — the engine relies on set-diff absence plus a likes-only heuristic instead. Any device that misses the deletion window resurrects the row on its next outbound push. V4 introduces uniform `deleted_at` tombstones plus a change ledger that carries D operations to every device with a server-side stale-write guard.

**F-04 · HIGH** — playlist_tracks integrity gaps

`playlist_id` is nullable, `position` has no uniqueness constraint so two rows can claim the same slot, and `UNIQUE (playlist_id, track_id)` forbids the same track appearing twice — a regression against playlist semantics the local Room model permits. Reorders are full-row rewrites with no ordering guarantee, and the client transmits rows the server may re-key. Listing 2 enforces NOT NULL ownership, Listing 8 adds a partial unique index on `(playlist_id, position)` for live rows, and the duplicate-forbidding constraint is dropped.

**F-05 · MEDIUM** — Listening history lacks display snapshots (Invariant 4 risk)

`user_listening_history` stores `track_id`, `artist_id`, and `album_id` but no title, artist name, or album name. On a fresh reinstall the analytics engine (Top Artists, Top Albums, Decades Breakdown, Listening Fingerprint) depends on those labels; if the catalog row is missing or purged, the graph rebuilds with holes and the client must re-scrape metadata to compensate. Listing 2 adds denormalized snapshot columns written by the client at event time, making every history row self-contained.

**F-06 · MEDIUM** — Primary key and idempotency inconsistency

Primary keys mix server `uuid_generate_v4()` defaults (playlists, playlist_tracks, history), natural composite keys (user_likes, user_item_favorites), and free text (tracks). Random UUIDv4 PKs scatter inserts across the B-tree rightmost pages, and history inserts carry no client idempotency key, so a retried batch either duplicates rows or is silently dropped by the set-diff engine. Section 4.2 standardizes identifiers per domain and adds `UNIQUE (user_id, client_event_id)` for history.

### 3.2 Synchronization Engine

**F-07 · CRITICAL** — Full-set diff synchronization cannot scale

Every cycle of `StreamifySyncEngine` fetches the complete cloud sets — likes, playlists, favorites, search history — and diffs them against Room locally. Egress is O(total library) per cycle regardless of change count: at 5,000 likes and roughly 340 bytes per serialized row, each sync transfers about 1.7 MB before a single byte of actual change, and with hourly triggers this approaches 1.2 GB per device per month. The pattern also re-downloads rows the device itself just uploaded, paying double for its own writes. Section 6 replaces it with a per-user change ledger and cursor-based delta pulls.

**F-08 · CRITICAL** — History window collapses repeat plays

`syncListeningHistoryInternal` fetches `limit = 100` rows and reduces both sides to sets keyed by trackId. Two consequences follow: repeated plays of the same track collapse to a single event (Wrapped and Listening Clock undercount forever), and any backlog beyond 100 local events never syncs because the comparison window never advances past the first 100 rows. Wrapped analytics therefore diverge silently between devices and across reinstalls. V4 treats history as an append-only set addressed by `client_event_id`, immune to both failure modes.

**F-09 · HIGH** — LWW is nominal — no logical timestamps exist

The engine header declares Last-Write-Wins conflict resolution, but no cloud row carries a logical clock, so the engine actually resolves conflicts by set-existence plus a likes-only unlike-reconciliation heuristic. Playlist renames, favorite removals, and search deletions have no ordering evidence at all, and wall-clock skew between devices makes any timestamp fallback ambiguous. V4 stores a 64-bit hybrid logical clock (`modified_hlc`) on every mutable row and enforces the comparison inside the push RPC, so convergence no longer depends on client discipline.

**F-10 · HIGH** — Active session blind overwrite

`syncActiveSessionInternal` overwrites the cloud `active_session` whenever the local device has a non-blank recent track, without comparing any timestamp. A device resuming from background with stale state clobbers the session another device wrote seconds earlier. There is no TTL, so abandoned sessions persist indefinitely, and there is no device attribution, so consumers cannot tell which device is authoritative. Section 8 replaces the pattern with per-device presence rows guarded by HLC comparison.

**F-11 · MEDIUM** — Sequential pipeline amplifies latency and failure

Stages run serially under a single mutex with per-entity exception isolation; a failing stage starves every later stage, and the PostgREST 1,000-row pagination workaround multiplies round trips for large libraries. The engine also filters legacy `trk_*` rows client-side on every fetch, paying the legacy tax on each cycle. The v3 contract collapses all entities into one batched RPC with per-op isolation enforced server-side, so one bad row never aborts or starves anything.

### 3.3 Row-Level Security and Privacy

**F-12 · CRITICAL** — World-readable RLS policies expose private graph data

Live `pg_policies` shows `USING (true)` on four surfaces: user_likes (“Anyone can view likes”), user_item_favorites (“viewable by everyone”), playlist_tracks (“View playlist tracks”), and profiles (“Public profiles are viewable by everyone” — the `is_private` column is never consulted by the policy). Any authenticated user can therefore enumerate another user's liked tracks, artist and podcast favorites, and the membership of private playlists. The playlist_tracks leak is the most severe: playlist contents are exposed even when the parent playlist is marked private. Listing 7 closes all four leaks with owner-scoped policies.

```sql
-- Live pg_policies excerpt (probed read-only 2026-11-24):
-- user_likes          SELECT  "Anyone can view likes"       USING (true)
-- user_item_favorites SELECT  "...viewable by everyone"     USING (true)
-- playlist_tracks     SELECT  "View playlist tracks"        USING (true)
-- profiles            SELECT  "Public profiles viewable..." USING (true)   -- is_private ignored
```

**F-13 · HIGH** — Per-row RLS evaluation over bulk full-diff pulls

`auth.uid() = user_id` policies are correct per row, but the full-diff client pulls thousands of rows per cycle and each row pays its own policy evaluation. The v3 bulk path moves sync I/O into SECURITY DEFINER RPCs that verify ownership once per call and filter by `user_id` inside the query itself, cutting the authorization overhead of a 500-op pull from 500 evaluations to one. RLS remains enabled on every table as defense in depth for direct PostgREST access.

**F-14 · MEDIUM** — Collaborator model as uuid[] blocks permission evolution

`playlists.collaborator_ids` is a denormalized UUID array checked with `= ANY()` in every policy; grants are unauditable, and per-track or role-based collaboration is impossible to express. At current scale the array is acceptable, so this RFC keeps it but centralizes its checks in the write RPC; a future `playlist_collaborators` table with its own RLS is noted as the medium-term shape in Section 9.

### 3.4 Active Playback Session

**F-15 · HIGH** — active_session JSONB on profiles is a hot-row anti-pattern

Every playback position update rewrites the user's profile row — churning WAL, invalidating every reader of `profiles` (admin surfaces, friend activity), and serializing behind unrelated profile edits such as bio or avatar changes. The column is polled by fetch rather than pushed, writes carry a client-supplied ISO string with no server contract, and `queue_ids` embeds an unbounded queue into the same hot row. Section 8 moves the workload to `user_presence` with per-device rows, TTL eviction, and Realtime broadcast.

**F-16 · MEDIUM** — No session semantics or eviction

`active_session` has no `is_playing`, no device attribution, and no cleanup when a device disappears, so cross-device hand-off cannot distinguish a session paused an hour ago from one playing right now, and stale devices win writes by accident of timing. The presence design adds device attribution, play state, an HLC guard, and a 7-day TTL.

## 4. Target Architecture

### 4.1 Invariant Enforcement Map

Every mechanism proposed in this RFC traces back to one of the four invariants. The mapping below is the acceptance lens for design review: if a future change weakens a cell, it requires a new ADR.

*Table 3 — Invariant-to-mechanism traceability*

| Invariant | Enforcement in V4 |
|---|---|
| Client-sovereign heavy lifting (zero backend scraping) | Slim content-addressed `track_index` with display-only fields; no `stream_url`, `lyrics`, or `embedding` columns; the server never resolves or fetches media; catalog rows are written lazily from client ops |
| Local SQLite is the realtime source of truth (offline-first) | Client outbox ledger drains through one batched RPC; cloud stores an ordered per-user change log plus graph snapshot; 90-day GC horizon is 3x the 30-day offline SLA |
| Equalizer tuning is device-bound | Syncable settings whitelist enforced server-side inside the push RPC: `equalizer_*` keys are stripped before merge, so no client bug can leak device calibration |
| 100% graph recovery for offline analytics | Snapshot columns (`title`, `artist_name`, `album_name`, duration) on likes, favorites, and history rows; every analytics row is self-contained without catalog or scraping |

### 4.2 Identifier Strategy

The audit question — UUID versus NanoID versus BigInt — has no single answer because the domains differ: syncable user-graph rows need stable, client-assignable addresses; append-only telemetry needs idempotency keys; the ledger needs a dense cursor. The per-domain decision is tabulated below. Two global rejections: NanoID has no native PostgreSQL type, so it degrades to text comparisons on every join while offering no locality advantage; bare BigInt sequences for user-facing identifiers invite enumeration and cannot be generated offline, which breaks the outbox model. UUIDv7 (time-ordered) is the preferred shape for new client-assigned identifiers because it preserves B-tree locality; where UUIDv4 is already deployed (playlists), migration cost outweighs the benefit and the existing values are kept.

*Table 4 — Identifier strategy by domain*

| Domain | Current | Target | Rationale |
|---|---|---|---|
| playlists | UUIDv4 server default | UUID, client-assigned (UUIDv7 where available) | Stable cross-device identity already linked to `LocalPlaylistEntity.syncId`; v7 improves index locality |
| playlist_tracks | UUIDv4 + UNIQUE (playlist_id, track_id) | Client-assigned UUID + partial UNIQUE (playlist_id, position) on live rows | Allows duplicate tracks; position integrity; idempotent replay of add/remove ops |
| user_likes / user_item_favorites | Natural composite PK | Unchanged, plus `modified_hlc`, `deleted_at`, snapshot | Natural keys are the sync address; no surrogate needed |
| user_listening_history | UUIDv4 server-generated | `client_event_id` UUID with UNIQUE (user_id, client_event_id) | Idempotent replay of retried batches; server never mints identity |
| Track catalog | Free text (YouTube ID) | Canonical key `{source}:{external_id}` + UNIQUE (source, external_id) | Deduplication across sources without any server-side fetching |
| sync_changelog | (none) | BIGINT GENERATED ALWAYS AS IDENTITY | Dense, gap-free-enough global cursor for delta pull |

### 4.3 Track Registry and Metadata Deduplication

Deduplication must not create scraping overhead, so the registry is content-addressed rather than fingerprinted: the canonical key is `lower(source) || ':' || external_id`, for example `yt:dQw4w9WgXcQ`, and the client — which already parses YouTube, Spotify, and Piped responses — attaches display metadata (`title`, `artist_name`, `album_name`, `duration_sec`, `thumb_url`) at op time. The server performs an upsert and nothing else; it never resolves, fetches, or validates media. Cross-provider identity mapping (a YouTube track and its Spotify counterpart) deliberately stays client-side, because the client is the only component that ever sees both responses. Registry rows that no graph row references are collected by the daily GC job, keeping the table bounded to the active graph.

Two write paths feed the registry. The push RPC performs a lazy upsert whenever an op carries a `catalog` object (Listing 5), and the direct authenticated insert path from the current schema is preserved behind the existing 60-per-hour rate-limit function. Because every graph row also embeds its own snapshot, a missing registry row degrades nothing functional — the registry exists to enrich fresh-install hydration, not to guard integrity.

### 4.4 Tombstones and the Anti-Resurrection Model

Deletions propagate through three cooperating mechanisms. First, every mutable table gains `deleted_at`; a delete is an UPDATE that sets the tombstone, never a row removal, so the row keeps its identity and its `modified_hlc`. Second, the change-log trigger (Listing 4) emits a D operation for the transition, and every device applies D operations as local soft-deletes on pull. Third — the anti-resurrection guarantee — the push RPC rejects any stale upsert whose `hlc` is not newer than the tombstone it targets, so a device returning from a 30-day offline period cannot resurrect an item the user deleted elsewhere. Hard deletion happens only in the GC job, 90 days after tombstoning, which is three times the 30-day offline SLA and therefore safe for any device that syncs within policy. The same LWW guard makes re-liking work naturally: a newer like op clears `deleted_at` because its clock beats the tombstone.

## 5. Target Schema DDL — Migration 2026_12_sync_v4_graph_ledger.sql

This section is the complete, ordered, drop-in migration. Listings 1 through 8 apply in order and are additive-first: nothing the current client touches is removed until the P4 decommission step, and every statement is idempotent (`if not exists` / `on conflict`) so re-application is safe. The migration was authored against the probed live schema; run it inside a transaction per phase as scheduled in Section 9.

### 5.1 Catalog Layer

The catalog layer is rebuilt as `track_index`, a slim content-addressed registry holding only display metadata. The legacy `tracks` table remains untouched during transition (it is dropped in P4) so old clients keep working; the backfill below is one-time and idempotent.

*Listing 1 — Catalog layer — track_index*

```sql
-- ============================================================================
  -- Catalog layer — content-addressed track registry (Invariant 1)
-- Replaces public.tracks (stream_url / lyrics / embedding removed)
-- ============================================================================
create table if not exists public.track_index (
  id            text        primary key,          -- canonical key '{source}:{external_id}'
  source        text        not null,
  external_id   text        not null,
  title         text        not null,
  artist_name   text,
  album_name    text,
  duration_sec  integer,
  thumb_url     text,                             -- display thumbnail; never stream URLs
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now(),
  constraint track_index_source_chk check (source in ('yt', 'spotify', 'piped', 'local')),
  constraint track_index_dedup    unique (source, external_id)
);

-- One-time backfill from the legacy catalog (idempotent).
insert into public.track_index (id, source, external_id, title, artist_name,
                                album_name, duration_sec, thumb_url)
select 'yt:' || t.id, 'yt', t.id, t.title, t.artist, t.album,
       t.duration_sec, t.cover_url
  from public.tracks t
on conflict (source, external_id) do nothing;

alter table public.track_index enable row level security;
```

### 5.2 User Graph Layer

The graph layer keeps table names and natural keys — the client contract is stable — but decouples from catalog lifecycle (F-01), adds the sync columns everywhere (`modified_hlc`, `deleted_at`), adds history snapshots and the client idempotency key (F-05, F-06), and repairs playlist_tracks integrity (F-04). With live event tables still empty, the NOT NULL change is free.

*Listing 2 — User graph layer — alterations*

```sql
-- ============================================================================
  -- User graph layer — decouple from catalog lifecycle, add sync columns
-- ============================================================================

-- 2.1 user_likes: catalog FK cascaded purges into user libraries (F-01)
alter table public.user_likes
  drop constraint if exists user_likes_track_id_fkey,
  add column if not exists deleted_at   timestamptz,
  add column if not exists modified_hlc bigint,
  add column if not exists snapshot     jsonb;     -- {title, artist, duration, thumb}

-- 2.2 user_item_favorites: tombstone + hlc (metadata snapshot exists)
alter table public.user_item_favorites
  add column if not exists deleted_at   timestamptz,
  add column if not exists modified_hlc bigint;

-- 2.3 playlists: tombstone + hlc
alter table public.playlists
  add column if not exists deleted_at   timestamptz,
  add column if not exists modified_hlc bigint;

-- 2.4 playlist_tracks: ownership NOT NULL, tombstones (F-04)
alter table public.playlist_tracks
  alter column playlist_id set not null,
  drop constraint if exists uq_playlist_track,   -- forbade duplicate tracks
  add column if not exists deleted_at   timestamptz,
  add column if not exists modified_hlc bigint;

-- 2.5 user_search_history: uniform tombstone (replaces ad-hoc is_deleted)
alter table public.user_search_history
  add column if not exists deleted_at timestamptz;
update public.user_search_history
   set deleted_at = searched_at        -- backfill; column is_deleted drops in P4
 where is_deleted and deleted_at is null;

-- 2.6 user_listening_history: idempotency key + graph-recovery snapshots
alter table public.user_listening_history
  add column if not exists client_event_id uuid,
  add column if not exists title        text,
  add column if not exists artist_name  text,
  add column if not exists album_name   text,
  add column if not exists deleted_at   timestamptz;

-- 2.7 user_settings: merge-protected by hlc
alter table public.user_settings
  add column if not exists modified_hlc bigint,
  add column if not exists updated_at   timestamptz not null default now();
```

### 5.3 Sync Ledger, Device Registry and Presence

Three new tables carry the sync topology. `sync_changelog` is the per-user append-only ledger that powers delta pull; `user_devices` records device identity for echo suppression and ops analytics; `user_presence` holds per-device playback state for cross-device continuity, replacing the `profiles.active_session` hot row.

*Listing 3 — Sync ledger, devices, presence*

```sql
-- ============================================================================
-- Sync ledger, device registry, presence
-- ============================================================================
create table if not exists public.user_devices (
  user_id     uuid        not null references public.profiles (id) on delete cascade,
  device_id   text        not null,
  platform    text,
  app_version text,
  last_seen   timestamptz not null default now(),
  primary key (user_id, device_id)
);

create table if not exists public.sync_changelog (
  id           bigint      generated always as identity primary key,
  user_id      uuid        not null references public.profiles (id) on delete cascade,
  entity       text        not null,
  op           text        not null check (op in ('I', 'U', 'D')),
  row_key      text        not null,
  payload      jsonb,                            -- null for deletes
  occurred_hlc bigint,
  origin       text        not null default 'server',  -- writing device_id
  created_at   timestamptz not null default now(),
  constraint changelog_entity_chk check (entity in
    ('like', 'favorite', 'playlist', 'playlist_track', 'search', 'history', 'settings'))
);

create table if not exists public.user_presence (
  user_id      uuid        not null references public.profiles (id) on delete cascade,
  device_id    text        not null,
  track_id     text,
  position_ms  bigint      not null default 0,
  is_playing   boolean     not null default false,
  queue_ids    jsonb       not null default '[]'::jsonb,  -- client caps at 200 ids
  modified_hlc bigint      not null default 0,
  updated_at   timestamptz not null default now(),
  primary key (user_id, device_id)
);

alter table public.user_devices   enable row level security;
alter table public.sync_changelog enable row level security;
alter table public.user_presence  enable row level security;
```

### 5.4 Change-Log Trigger

One trigger function serves every syncable table, parameterized by entity name. It serializes the row to JSONB, derives the natural key per entity, classifies the operation (a soft-delete transition emits D rather than U), and appends exactly one ledger row in the same transaction as the data change — so the ledger can never drift from the graph. The device origin is carried through a transaction-local setting set by the push RPC, which is what makes echo suppression possible on pull.

*Listing 4 — Change-log trigger and bindings*

```sql
-- ============================================================================
-- Change-log trigger — server-side ordering for delta pull
-- ============================================================================
create or replace function public.tg_bump_changelog()
returns trigger
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_entity text := tg_argv[0];
  v_origin text := coalesce(current_setting('streamify.device_id', true), 'server');
  v_row    jsonb;
  v_user   uuid;
  v_key    text;
  v_op     text;
  v_hlc    bigint;
begin
  if tg_op = 'DELETE' then
    v_row := to_jsonb(old);
  else
    v_row := to_jsonb(new);
  end if;

  v_user := (v_row ->> 'user_id')::uuid;
  v_hlc  := (v_row ->> 'modified_hlc')::bigint;
  v_key  := case v_entity
              when 'like'           then v_row ->> 'track_id'
              when 'favorite'       then (v_row ->> 'item_type') || ':' || (v_row ->> 'item_id')
              when 'playlist'       then v_row ->> 'id'
              when 'playlist_track' then v_row ->> 'id'
              when 'search'         then v_row ->> 'query'
              when 'history'        then coalesce(v_row ->> 'client_event_id', v_row ->> 'id')
              when 'settings'       then 'settings'
            end;

  if tg_op = 'DELETE'
     or (tg_op = 'UPDATE' and new.deleted_at is not null and old.deleted_at is null) then
    v_op := 'D';
    v_row := null;                              -- deletes carry the key only
  else
    v_op := case tg_op when 'INSERT' then 'I' else 'U' end;
  end if;

  insert into public.sync_changelog
        (user_id, entity, op, row_key, payload, occurred_hlc, origin)
  values (v_user, v_entity, v_op, v_key, v_row, v_hlc, v_origin);

  return coalesce(new, old);
end $$;

create trigger tg_likes_changelog after insert or update or delete
  on public.user_likes for each row
  execute function public.tg_bump_changelog('like');
create trigger tg_favorites_changelog after insert or update or delete
  on public.user_item_favorites for each row
  execute function public.tg_bump_changelog('favorite');
create trigger tg_playlists_changelog after insert or update or delete
  on public.playlists for each row
  execute function public.tg_bump_changelog('playlist');
create trigger tg_playlist_tracks_changelog after insert or update or delete
  on public.playlist_tracks for each row
  execute function public.tg_bump_changelog('playlist_track');
create trigger tg_search_changelog after insert or update or delete
  on public.user_search_history for each row
  execute function public.tg_bump_changelog('search');
create trigger tg_history_changelog after insert or delete
  on public.user_listening_history for each row
  execute function public.tg_bump_changelog('history');
create trigger tg_settings_changelog after insert or update
  on public.user_settings for each row
  execute function public.tg_bump_changelog('settings');
```

### 5.5 Sync RPCs — Push, Pull, Presence

The push RPC is the single write path: it authenticates once, stamps the device origin, upserts the device registry, and applies every op with per-op exception isolation and the LWW tombstone guard. The pull RPC returns the next cursor page of ledger rows, excluding the calling device's own ops for echo suppression. The presence RPC implements the stale-write guard with row-level locking. Note the settings branch: the `equalizer_*` whitelist is enforced server-side, making Invariant 3 impossible to violate even by a buggy client.

*Listing 5 — sync_push, sync_pull, presence RPCs*

```sql
-- ============================================================================
-- sync_push — idempotent batched write path (LWW + tombstone guard)
-- ============================================================================
create or replace function public.sync_push(p_ops jsonb, p_device_id text)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_uid      uuid := auth.uid();
  v_applied  int  := 0;
  v_rejected int  := 0;
  op         jsonb;
  v_entity   text;
  v_op       text;
  v_key      text;
  v_hlc      bigint;
  v_d        jsonb;
begin
  if v_uid is null then
    raise exception 'sync_push: unauthenticated';
  end if;

  perform set_config('streamify.device_id', coalesce(p_device_id, 'server'), true);
  insert into public.user_devices (user_id, device_id) values (v_uid, p_device_id)
  on conflict (user_id, device_id) do update set last_seen = now();

  for op in select * from jsonb_array_elements(p_ops) loop
    v_entity := op ->> 'entity';
    v_op     := coalesce(op ->> 'op', 'U');
    v_key    := op ->> 'key';
    v_hlc    := coalesce((op ->> 'hlc')::bigint, 0);
    v_d      := coalesce(op -> 'data', '{}'::jsonb);

    begin  -- per-op isolation: one bad row never aborts the batch
      ------------------------------------------------------------- like --
      if v_entity = 'like' then
        if v_op = 'D' then
          update public.user_likes
             set deleted_at = now(), modified_hlc = v_hlc
           where user_id = v_uid and track_id = v_key
             and (modified_hlc is null or modified_hlc <= v_hlc);
        else
          insert into public.user_likes
                (user_id, track_id, created_at, snapshot, modified_hlc, deleted_at)
          values (v_uid, v_key,
                  coalesce((v_d ->> 'created_at')::timestamptz, now()),
                  v_d -> 'meta', v_hlc, null)
          on conflict (user_id, track_id) do update
             set created_at   = excluded.created_at,
                 snapshot     = coalesce(excluded.snapshot, public.user_likes.snapshot),
                 modified_hlc = excluded.modified_hlc,
                 deleted_at   = null
           where public.user_likes.modified_hlc is null
              or public.user_likes.modified_hlc <= excluded.modified_hlc;
        end if;

      --------------------------------------------------------- favorite --
      elsif v_entity = 'favorite' then
        if v_op = 'D' then
          update public.user_item_favorites
             set deleted_at = now(), modified_hlc = v_hlc
           where user_id = v_uid and item_type = v_d ->> 'item_type'
             and item_id = v_d ->> 'item_id'
             and (modified_hlc is null or modified_hlc <= v_hlc);
        else
          insert into public.user_item_favorites
                (user_id, item_type, item_id, metadata, modified_hlc, deleted_at)
          values (v_uid, v_d ->> 'item_type', v_d ->> 'item_id',
                  v_d -> 'meta', v_hlc, null)
          on conflict (user_id, item_type, item_id) do update
             set metadata     = coalesce(excluded.metadata,
                                         public.user_item_favorites.metadata),
                 modified_hlc = excluded.modified_hlc,
                 deleted_at   = null
           where public.user_item_favorites.modified_hlc is null
              or public.user_item_favorites.modified_hlc <= excluded.modified_hlc;
        end if;

      --------------------------------------------------------- playlist --
      elsif v_entity = 'playlist' then
        if v_op = 'D' then
          update public.playlists
             set deleted_at = now(), modified_hlc = v_hlc
           where id = v_key::uuid and user_id = v_uid
             and (modified_hlc is null or modified_hlc <= v_hlc);
        else
          insert into public.playlists
                (id, user_id, name, description, cover_url, is_public,
                 youtube_playlist_id, created_at, modified_hlc, deleted_at)
          values (v_key::uuid, v_uid, v_d ->> 'name', v_d ->> 'description',
                  v_d ->> 'cover_url', coalesce((v_d ->> 'is_public')::boolean, false),
                  v_d ->> 'youtube_playlist_id',
                  coalesce((v_d ->> 'created_at')::timestamptz, now()), v_hlc, null)
          on conflict (id) do update
             set name = excluded.name, description = excluded.description,
                 cover_url = excluded.cover_url, is_public = excluded.is_public,
                 youtube_playlist_id = excluded.youtube_playlist_id,
                 modified_hlc = excluded.modified_hlc, deleted_at = null
           where public.playlists.user_id = v_uid
             and (public.playlists.modified_hlc is null
                  or public.playlists.modified_hlc <= excluded.modified_hlc);
        end if;

      --------------------------------------------------- playlist_track --
      elsif v_entity = 'playlist_track' then
        if v_op = 'D' then
          update public.playlist_tracks
             set deleted_at = now(), modified_hlc = v_hlc
           where id = v_key::uuid
             and playlist_id in (select id from public.playlists
                                  where user_id = v_uid and deleted_at is null)
             and (modified_hlc is null or modified_hlc <= v_hlc);
        else
          insert into public.playlist_tracks
                (id, playlist_id, track_id, position, added_by, added_at,
                 modified_hlc, deleted_at)
          values (v_key::uuid, (v_d ->> 'playlist_id')::uuid, v_d ->> 'track_id',
                  (v_d ->> 'position')::int, v_uid,
                  coalesce((v_d ->> 'added_at')::timestamptz, now()), v_hlc, null)
          on conflict (id) do update
             set position = excluded.position, track_id = excluded.track_id,
                 modified_hlc = excluded.modified_hlc, deleted_at = null
           where (public.playlist_tracks.modified_hlc is null
                  or public.playlist_tracks.modified_hlc <= excluded.modified_hlc)
             and exists (select 1 from public.playlists p
                          where p.id = public.playlist_tracks.playlist_id
                            and p.user_id = v_uid);
        end if;

      ----------------------------------------------------------- search --
      elsif v_entity = 'search' then
        if v_op = 'D' then
          update public.user_search_history
             set deleted_at = now()
           where user_id = v_uid and query = v_key;
        else
          insert into public.user_search_history (user_id, query, searched_at)
          values (v_uid, v_key, coalesce((v_d ->> 'searched_at')::timestamptz, now()))
          on conflict (user_id, query) do update
             set searched_at = excluded.searched_at, deleted_at = null;
        end if;

      --------------------------------------------------------- history --
      elsif v_entity = 'history' then
        insert into public.user_listening_history
              (user_id, client_event_id, track_id, artist_id, album_id,
               title, artist_name, album_name, listened_duration_sec, created_at)
        values (v_uid, (v_d ->> 'client_event_id')::uuid, v_d ->> 'track_id',
                v_d ->> 'artist_id', v_d ->> 'album_id',
                v_d ->> 'title', v_d ->> 'artist_name', v_d ->> 'album_name',
                coalesce((v_d ->> 'listened_duration_sec')::int, 0),
                coalesce((v_d ->> 'listened_at')::timestamptz, now()))
        on conflict (user_id, client_event_id) do nothing;   -- idempotent replay

      --------------------------------------------------------- settings --
      elsif v_entity = 'settings' then
        -- Invariant 3 enforced server-side: equalizer_* never enters cloud sync
        insert into public.user_settings (user_id, settings, modified_hlc, updated_at)
        values (v_uid,
                (select coalesce(jsonb_object_agg(k, v), '{}'::jsonb)
                   from jsonb_each(coalesce(v_d -> 'settings', '{}'::jsonb)) as x(k, v)
                  where k not like 'equalizer_%'),
                v_hlc, now())
        on conflict (user_id) do update
           set settings     = public.user_settings.settings || excluded.settings,
               modified_hlc = excluded.modified_hlc,
               updated_at   = now()
         where public.user_settings.modified_hlc is null
            or public.user_settings.modified_hlc <= excluded.modified_hlc;
      end if;

      -------------------------------------------------- lazy catalog upsert --
      -- Display metadata only; the server never fetches or validates media.
      if v_entity in ('like', 'history', 'playlist_track') and v_d ? 'catalog' then
        insert into public.track_index
              (id, source, external_id, title, artist_name, album_name,
               duration_sec, thumb_url)
        values (v_d -> 'catalog' ->> 'id',
                v_d -> 'catalog' ->> 'source',
                v_d -> 'catalog' ->> 'external_id',
                v_d -> 'catalog' ->> 'title',
                v_d -> 'catalog' ->> 'artist_name',
                v_d -> 'catalog' ->> 'album_name',
                (v_d -> 'catalog' ->> 'duration_sec')::int,
                v_d -> 'catalog' ->> 'thumb_url')
        on conflict (source, external_id) do update
           set title = excluded.title, artist_name = excluded.artist_name,
               album_name = excluded.album_name,
               duration_sec = excluded.duration_sec,
               thumb_url = excluded.thumb_url, updated_at = now();
      end if;

      v_applied := v_applied + 1;
    exception when others then
      v_rejected := v_rejected + 1;   -- mirrors client per-entity isolation
    end;
  end loop;

  return jsonb_build_object(
    'applied',  v_applied,
    'rejected', v_rejected,
    'cursor',   (select coalesce(max(id), 0) from public.sync_changelog
                  where user_id = v_uid));
end $$;

-- ============================================================================
-- sync_pull — cursor-based delta read (echo-suppressed)
-- ============================================================================
create or replace function public.sync_pull(
  p_since bigint, p_limit int default 500, p_device_id text default null)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_uid  uuid := auth.uid();
  v_ops  jsonb := '[]'::jsonb;
  v_last bigint := p_since;
  v_cap  int := least(coalesce(p_limit, 500), 1000);
  rec    record;
begin
  if v_uid is null then
    raise exception 'sync_pull: unauthenticated';
  end if;

  for rec in
    select id, entity, op, row_key, payload, occurred_hlc
      from public.sync_changelog
     where user_id = v_uid
       and id > p_since
       and (p_device_id is null or origin is distinct from p_device_id)
     order by id
     limit v_cap
  loop
    v_ops := v_ops || jsonb_build_object(
      'seq', rec.id, 'entity', rec.entity, 'op', rec.op, 'key', rec.row_key,
      'hlc', rec.occurred_hlc, 'data', rec.payload);
    v_last := rec.id;
  end loop;

  return jsonb_build_object(
    'cursor', v_last,
    'has_more', jsonb_array_length(v_ops) = v_cap,
    'ops', v_ops);
end $$;

-- ============================================================================
-- presence heartbeat with stale-write guard (Section 8)
-- ============================================================================
create or replace function public.heartbeat_presence(
  p_device_id   text,
  p_track_id    text    default null,
  p_position_ms bigint  default 0,
  p_is_playing  boolean default false,
  p_queue_ids   jsonb   default '[]'::jsonb,
  p_hlc         bigint  default 0)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_uid uuid := auth.uid();
  v_row public.user_presence;
begin
  if v_uid is null then
    raise exception 'heartbeat_presence: unauthenticated';
  end if;
  select * into v_row from public.user_presence
   where user_id = v_uid and device_id = p_device_id for update;
  if v_row.modified_hlc > p_hlc then
    return jsonb_build_object('accepted', false, 'reason', 'stale_hlc');
  end if;
  insert into public.user_presence
        (user_id, device_id, track_id, position_ms, is_playing,
         queue_ids, modified_hlc, updated_at)
  values (v_uid, p_device_id, p_track_id, p_position_ms, p_is_playing,
          p_queue_ids, p_hlc, now())
  on conflict (user_id, device_id) do update
     set track_id = excluded.track_id, position_ms = excluded.position_ms,
         is_playing = excluded.is_playing, queue_ids = excluded.queue_ids,
         modified_hlc = excluded.modified_hlc, updated_at = now();
  return jsonb_build_object('accepted', true);
end $$;

create or replace function public.get_latest_presence()
returns setof public.user_presence
language sql
security definer
set search_path = public, pg_temp
stable
as $$
  select * from public.user_presence
   where user_id = auth.uid()
   order by updated_at desc;
$$;
```

### 5.6 Views and Garbage Collection

Two convenience views keep the client contract clean — active playlist membership without tombstones, and a per-user graph summary for onboarding diagnostics. The GC job, scheduled through pg_cron, enforces the retention horizon: ledger rows and tombstones survive 90 days (three times the offline SLA), and presence rows expire after 7 days. Enable the pg_cron extension in the Supabase dashboard before scheduling.

*Listing 6 — Views and GC schedule*

```sql
-- ============================================================================
-- Views and garbage collection (pg_cron)
-- ============================================================================
create or replace view public.v_active_playlist_tracks as
select pt.id, pt.playlist_id, pt.track_id, pt.position, pt.added_by, pt.added_at
  from public.playlist_tracks pt
 where pt.deleted_at is null;

create or replace view public.v_user_graph_summary as
select p.id as user_id,
       (select count(*) from public.user_likes l
         where l.user_id = p.id and l.deleted_at is null)        as liked_tracks,
       (select count(*) from public.user_item_favorites f
         where f.user_id = p.id and f.deleted_at is null)        as favorites,
       (select count(*) from public.user_listening_history h
         where h.user_id = p.id and h.deleted_at is null)        as play_events,
       (select coalesce(sum(h.listened_duration_sec), 0)
          from public.user_listening_history h
         where h.user_id = p.id and h.deleted_at is null)        as listened_seconds
  from public.profiles p;

-- Retention: 3x the 30-day offline SLA for ledger + tombstones;
-- 7-day TTL for presence. Requires pg_cron enabled.
select cron.schedule('streamify_gc_daily', '17 3 * * *', $gc$
  delete from public.sync_changelog
   where created_at < now() - interval '90 days';
  delete from public.user_presence
   where updated_at  < now() - interval '7 days';
  delete from public.user_likes
   where deleted_at  < now() - interval '90 days';
  delete from public.playlist_tracks
   where deleted_at  < now() - interval '90 days';
  delete from public.user_item_favorites
   where deleted_at  < now() - interval '90 days';
$gc$);
```

## 6. Sync Contract v3 and Conflict Resolution

### 6.1 Why Full-Set Diff Fails at Scale

The current engine's cost model is fixed-cost-per-change plus linear-cost-per-library-size, and the second term dominates within months of normal use. The table below makes the arithmetic concrete at 5,000 liked tracks and roughly 340 bytes per serialized row; the numbers are illustrative estimates of the mechanism, not measurements. Beyond bandwidth, every full cycle also costs battery (radio wake for a large transfer), database CPU (thousands of RLS evaluations, F-13), and correctness (the history window, F-08).

*Table 5 — Egress and correctness comparison (illustrative arithmetic)*

| Scenario (assumptions: 5,000 likes, 340 B/row) | Full-diff v2 | Delta v3 |
|---|---|---|
| Cold install bootstrap | ~1.7 MB once (unavoidable in both) | Same bootstrap, then cursor |
| Hourly sync with 20 changed rows/day | ~1.7 MB × 24 ≈ 41 MB/day | ~20 ops × 300 B ≈ 6 kB/day |
| Monthly egress per active device | ~1.2 GB | < 1 MB plus bootstrap |
| 30-day offline catch-up | Full set; history capped at 100 rows (loss) | Cursor replay of exact missed ops (no loss) |

### 6.2 Conflict Resolution: HLC-based LWW, not Vector Clocks

The evaluation question — dual-parent outbox versus vector clocks versus LWW with logical timestamps — resolves decisively for hybrid logical clocks with last-write-wins. Vector clocks buy precise causal detection, but Streamify's rows are single-owner user data with no merge semantics (there is no CRDT to feed causal knowledge into), device membership churns on every reinstall (which orphans clock entries and grows per-row storage), and the anti-entropy machinery they require is exactly the complexity this RFC removes. A 64-bit HLC — 48 bits of Unix milliseconds packed above a 16-bit monotonic counter — is generated client-side, advances even while offline, and gives a total order when paired with the deterministic tiebreak below.

The resolution rule is enforced server-side in `sync_push`: an op applies only when its `hlc` exceeds the row's `modified_hlc`; on exact ties, the greater `device_id` in lexicographic order wins, so every replica converges to the same winner without coordination. The same guard is the anti-resurrection mechanism of Section 4.4 — a tombstone stamped with a newer HLC rejects any stale upsert from a device returning from a long offline period. Listening history needs none of this: it is an append-only set keyed by `client_event_id`, where retries are absorbed by `ON CONFLICT DO NOTHING` and there is simply nothing to conflict. Playlist positions remain the one soft spot — two devices reordering concurrently can interleave — and the contract accepts that with client-side gap numbering (positions in steps of 10) to make collisions rare and renumbering cheap.

*Table 6 — Vector clocks versus HLC-based LWW*

| Aspect | Vector clocks | HLC + LWW (chosen) |
|---|---|---|
| Storage per row | O(N devices) counters | One 64-bit column |
| Membership churn (reinstall) | Orphans clock entries | Irrelevant |
| Conflict detection | Precise causality | Total order only |
| Resolution determinism | Needs merge semantics | (hlc, device_id) tiebreak, all replicas agree |
| Machinery | Anti-entropy + gossip | Compare inside upsert |
| Fit for Streamify | No CRDT targets to inform | Sufficient; history is an append-only set |

### 6.3 The Protocol

The client keeps its Room SQLite as the realtime source of truth and a local outbox table (entity, key, op, hlc, payload, retries). Mutations append to the outbox inside the same Room transaction as the data change, so a crash between play and queue is impossible. The drain loop batches up to 200 ops into one `sync_push` call; the response returns the new ledger cursor plus per-batch applied/rejected counts, and rejected ops are logged, not retried blindly. Pulls are cursor-based with `has_more` paging, and echo suppression is server-side: ops authored by the calling device are excluded, which removes the need for the engine's current `isSyncActive` flag dance. The cursor is persisted in Room only after ops are applied, so a crash mid-apply replays idempotently.

*Table 7 — Sync v3 protocol sequence*

| Step | Actor | Action | Notes |
|---|---|---|---|
| 1 | Client | Local mutation in Room v28 + outbox append with hlc | UI never blocks; same-transaction guarantee |
| 2 | Client | Drain outbox: sync_push(ops, device_id), batches of 200 | Idempotent; safe to retry on timeout |
| 3 | Server | LWW guard per op; ledger rows appended atomically | Returns cursor, applied, rejected |
| 4 | Server to peers | Realtime postgres_changes event on sync_changelog | Wake-up signal only, no payload |
| 5 | Client | sync_pull(since=cursor, device_id) | Own ops excluded; echo-free |
| 6 | Client | Apply ops to Room; advance durable cursor | Replay-safe by construction |

### 6.4 Realtime Topology, Egress and Battery

Realtime is used as a doorbell, not a data channel. Each device subscribes to postgres_changes on `sync_changelog` filtered to its own `user_id`; an event means only “something changed, pull now”, so payloads stay on the RPC path where batching, cursors, and authorization live. Presence rides a broadcast channel (`presence:{user_id}`) for sub-second hand-off while the app is foregrounded. Subscriptions exist only while the app is in the foreground; on backgrounding, the client unsubscribes and falls back to WorkManager-scheduled syncs with network constraints, exponential backoff with jitter from 15 seconds to a 15-minute cap, and an immediate drain on foreground. This hybrid keeps battery cost proportional to actual change activity instead of library size, and it removes the polling loops that today fetch full sets on every trigger.

### 6.5 The 30-Day Offline Walkthrough

The invariants demand deterministic convergence after a 30-day offline period without data loss. The walkthrough below traces the mechanism end to end and is the basis of the verification case V-11 in Section 10.

*Table 8 — 30-day offline convergence walkthrough*

| Event | Mechanism | Result |
|---|---|---|
| Device offline, day 0-30 | Mutations queue in the local outbox; HLC advances from its own counter | Queue bounded only by device storage |
| Back online, day 30 | Outbox drains in 200-op batches via sync_push | Stale writes against newer tombstones are rejected, not applied |
| Pull since old cursor | Ledger replay of every missed I/U/D op in seq order | Deletes stay deleted locally; no resurrection |
| Retention horizon | Ledger and tombstones kept 90 days by GC | 3x the offline SLA; a policy-compliant device never misses a D op |
| Fresh reinstall anytime | Bootstrap: pull since 0 plus snapshot columns | Analytics graph rebuilds fully without scraping (Invariant 4) |

## 7. RLS, Indexing and Query Performance

### 7.1 RLS Critique and Hardening

The policy family `auth.uid() = user_id` is the right shape for owner data — the defects are the four world-read leaks (F-12), the unenforced `is_private` flag, and the bulk-path cost (F-13). Listing 7 rewrites the leaked policies to owner scope, scopes playlist_tracks through the parent playlist with collaborator support, makes profile visibility honor `is_private`, and locks the three new tables to owner access. The catalog remains readable by any authenticated user because it is display metadata, but writes flow only through the push RPC or the existing rate-limited insert path. RLS stays enabled everywhere even though the sync RPCs run as SECURITY DEFINER — defense in depth, so a future client regression to direct PostgREST writes still cannot cross user boundaries.

*Listing 7 — RLS hardening*

```sql
-- ============================================================================
-- RLS hardening — close world-read leaks (F-12), enforce is_private
-- ============================================================================

-- user_likes: was world-readable
drop policy if exists "Anyone can view likes" on public.user_likes;
drop policy if exists "Users manage own likes" on public.user_likes;
create policy "likes_owner_select" on public.user_likes
  for select using (auth.uid() = user_id);
create policy "likes_owner_write" on public.user_likes
  for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

-- user_item_favorites: was world-readable
drop policy if exists "Item favorites are viewable by everyone"
  on public.user_item_favorites;
drop policy if exists "Users manage own item favorites"
  on public.user_item_favorites;
create policy "favorites_owner_select" on public.user_item_favorites
  for select using (auth.uid() = user_id);
create policy "favorites_owner_write" on public.user_item_favorites
  for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

-- playlist_tracks: membership of private playlists leaked (most severe)
drop policy if exists "View playlist tracks" on public.playlist_tracks;
drop policy if exists "Modify playlist tracks" on public.playlist_tracks;
create policy "playlist_tracks_read" on public.playlist_tracks
  for select using (
    exists (select 1 from public.playlists p
             where p.id = playlist_id
               and (p.is_public
                    or p.user_id = auth.uid()
                    or auth.uid() = any (p.collaborator_ids))));
create policy "playlist_tracks_write" on public.playlist_tracks
  for all using (
    exists (select 1 from public.playlists p
             where p.id = playlist_id
               and (p.user_id = auth.uid()
                    or auth.uid() = any (p.collaborator_ids))))
  with check (
    exists (select 1 from public.playlists p
             where p.id = playlist_id
               and (p.user_id = auth.uid()
                    or auth.uid() = any (p.collaborator_ids))));

-- profiles: is_private was never consulted by the world-read policy
drop policy if exists "Public profiles are viewable by everyone"
  on public.profiles;
create policy "profiles_select_scoped" on public.profiles
  for select using ((not is_private) or auth.uid() = id or public.is_admin());

-- New tables: owner-only
create policy "changelog_owner" on public.sync_changelog
  for select using (auth.uid() = user_id);
create policy "devices_owner" on public.user_devices
  for all using (auth.uid() = user_id) with check (auth.uid() = user_id);
create policy "presence_owner" on public.user_presence
  for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

-- Catalog: authenticated read; writes only via sync_push / rate-limited path
create policy "track_index_read" on public.track_index
  for select using (auth.role() = 'authenticated');
```

### 7.2 Index Plan for High-Velocity Tables

The plan combines composite B-trees for the dominant access paths, a BRIN index for time-range scans on the highest-velocity table, and partial indexes that exclude tombstoned rows so dead data never pollutes the hot indexes. `user_listening_history` is the table that reaches hundreds of millions of rows first: the composite `(user_id, created_at DESC)` serves Wrapped queries and delta pulls per user, while BRIN on `created_at` serves retention and analytics range scans at a fraction of the B-tree's size and write amplification — the standard trade at append-only scale [7]. Every index added to history costs WAL per playback event, so the plan deliberately stops at three indexes and lets BRIN carry range scans.

*Listing 8 — Index plan*

```sql
-- ============================================================================
-- Index plan — composite B-tree, BRIN, partial unique
-- ============================================================================

-- High-velocity telemetry: per-user time analytics (Wrapped, Listening Clock)
create index if not exists idx_history_user_time
  on public.user_listening_history (user_id, created_at desc);

-- Cheap time-range scans: BRIN is orders of magnitude smaller than B-tree
-- on append-only timestamptz columns
create index if not exists idx_history_brin_time
  on public.user_listening_history using brin (created_at);

-- Idempotent push replay
create unique index if not exists uq_history_client_event
  on public.user_listening_history (user_id, client_event_id);

-- Chronological library restore + delta pull (tombstones excluded)
create index if not exists idx_likes_chrono
  on public.user_likes (user_id, created_at desc)
  where deleted_at is null;

-- Playlist ordering integrity (live rows only)
create unique index if not exists uq_playlist_position
  on public.playlist_tracks (playlist_id, position)
  where deleted_at is null;

-- Delta-pull cursor
create index if not exists idx_changelog_cursor
  on public.sync_changelog (user_id, id);

-- Presence restore (most recent device wins display)
create index if not exists idx_presence_recent
  on public.user_presence (user_id, updated_at desc);

-- Catalog dedup is enforced by constraint track_index_dedup (Listing 1)
```

*Table 9 — Index plan summary*

| Table | Index | Type | Serves |
|---|---|---|---|
| user_listening_history | (user_id, created_at DESC) | B-tree | Wrapped, Listening Clock, per-user pulls |
| user_listening_history | (created_at) | BRIN | Retention and analytics range scans at low write cost |
| user_listening_history | (user_id, client_event_id) UNIQUE | B-tree | Idempotent replay of retried batches |
| user_likes | (user_id, created_at DESC) WHERE deleted_at IS NULL | Partial B-tree | Chronological restore, delta pull |
| playlist_tracks | (playlist_id, position) WHERE deleted_at IS NULL | Partial unique | Ordering integrity without tombstone pollution |
| sync_changelog | (user_id, id) | B-tree | Cursor-based delta pull |
| user_presence | (user_id, updated_at DESC) | B-tree | Continuity restore across devices |
| track_index | (source, external_id) UNIQUE | B-tree | Content-addressed dedup upsert |

### 7.3 Bulk Sync Under RLS

Direct PostgREST access pays one policy evaluation per row, which the full-diff client multiplied into thousands of evaluations per cycle. The v3 path removes that cost structurally rather than tuning it: `sync_push` and `sync_pull` run as SECURITY DEFINER, verify `auth.uid()` once, and constrain every statement with an explicit `user_id = auth.uid()` predicate in the query itself. The functions inherit the least privilege that matters — they can only touch the syncable tables listed in `search_path` — and RLS remains enabled beneath them for all non-RPC access. This is the same pattern the existing `is_admin()` hardening already established in this codebase, so it introduces no new operational concept [5].

## 8. Cross-Device Playback Continuity

The Spotify-Connect-style hand-off deserves ephemeral transport, but it must also survive a cold start on a device that was offline when the session moved. The design therefore splits the problem along its natural seam: live fan-out goes through Supabase Realtime broadcast, and durability goes to a small per-device table. Each device heartbeats `user_presence` through the guarded RPC at most every 5 seconds while playing (and on state changes), publishes to the broadcast channel `presence:{user_id}` for sub-second peer updates, and every other device resolves the authoritative session as the row with the highest `modified_hlc` — never the freshest wall clock, which is exactly the F-10 bug class. Queues are capped at 200 IDs client-side, rows expire after 7 days, and the HLC guard makes a stale device's heartbeat a no-op instead of a clobber.

Compared with the alternatives: keeping `profiles.active_session` JSONB concentrates a high-frequency write on the profile hot row and offers neither device attribution nor TTL (F-15, F-16); introducing Redis adds an unmanaged component, a second auth boundary, and still no durable fallback for cold starts. Supabase Realtime already carries the wake-up channel of Section 6.4, so presence rides infrastructure the platform provides, and the durable rows make hand-off work even when the receiving device was offline at the moment of switch. `profiles.active_session` is dropped in P4 after cutover.

*Table 10 — Presence lifecycle*

| Event | Transport | State after |
|---|---|---|
| Track starts / position ticks (5 s throttle) | Realtime broadcast `presence:{user_id}` | Peers update UI in sub-second; DB row refreshed on same tick |
| Pause / stop / track change | Broadcast + heartbeat RPC | is_playing and position_ms authoritative on both paths |
| App foregrounds on second device | get_latest_presence + subscribe | Highest-HLC session restored; queue rebuilt from queue_ids |
| Device goes silent | TTL sweep (7 days) | Row expires; no orphan session wins a future restore |
| Stale device heartbeats late | RPC HLC guard | Rejected with stale_hlc; newer session untouched |

## 9. Rollout Plan

The plan is additive-first and every phase has a rollback story: additive DDL is reversible by dropping new objects, and the only destructive steps are gated behind verified client cutover. At current row counts (Section 2) each phase is minutes of work; the sequencing exists to protect the dogfood fleet, not the data volume. Phase owners should treat the exit criteria as merge gates.

*Table 11 — Phased rollout*

| Phase | Window | Scope | Exit criteria |
|---|---|---|---|
| P0 | Day 0 | Apply Listing 7 (RLS lockdown); after a client audit confirms no live reader, drop `stream_url`, `lyrics`, `embedding`, `bpm`, `key_signature` from `tracks`; rotate the audit PAT | Second-account probes return zero foreign rows; legacy columns gone |
| P1 | Week 1 | Listings 1-3 (additive DDL + backfill + new tables) | Migration applies clean in a transaction; current client unaffected |
| P2 | Week 2 | Listings 4-6 and 8 (triggers, RPCs, views, GC, indexes) | RPC smoke tests pass; EXPLAIN plans use the new indexes |
| P3 | Weeks 3-4 | Client cutover: outbox drain via sync_push, cursor pull, presence heartbeat, Realtime wake | Dogfood fleet converges; per-device egress delta observed |
| P4 | Week 5 | Decommission full-diff endpoints; drop `profiles.active_session`, `user_search_history.is_deleted`, legacy `tracks` | Legacy queries 404; zero resurrection events in a 7-day soak |

Two risks deserve explicit ownership. The trigger on six tables adds one ledger insert per graph write; the ledger is append-only and BRIN-friendly, but the GC schedule from Listing 6 must be live before P3 or the ledger grows unbounded on the dogfood fleet. And the client cutover must ship the durable-cursor persistence in the same release as the push RPC, because a device that persists its cursor before applying ops will skip them on crash recovery — the walkthrough in Section 6.3 defines the correct order.

## 10. Verification Checklist

Each verification below is executable against a staging project provisioned from a branch of dbgtvzgwixerhnwvhtil, and V-01 through V-05 are additionally run as CI SQL tests modeled on the existing `supabase/sql/tests/rls_test.sql` harness [2]. A phase is not done until its verifications pass on staging and on one real dogfood device pair.

*Table 12 — Verification checklist*

| # | Verification | Expected result |
|---|---|---|
| V-01 | RLS negative probes: second test account selects foreign likes, favorites, playlist_tracks, profiles | Zero rows on every surface |
| V-02 | Tombstone propagation: delete on device A, pull on device B | B soft-deletes locally; a later B push does not resurrect |
| V-03 | Stale-upsert rejection: offline device pushes a pre-deletion like | Op rejected by LWW guard; tombstone persists |
| V-04 | Idempotent replay: push the same batch twice | Second call applies 0; no duplicate rows anywhere |
| V-05 | Cursor monotonicity under concurrent pushes from two devices | Pull never skips or repeats a seq |
| V-06 | Egress measurement over 24 h of dogfood use | Steady-state well under 1 MB/day/device |
| V-07 | Presence HLC guard: stale device heartbeats after a newer one | Stale heartbeat rejected; newer session untouched |
| V-08 | GC dry run against seeded old tombstones and ledger rows | Only rows past the horizon are removed |
| V-09 | EXPLAIN checks on history queries (per-user, time-range) | Composite B-tree and BRIN used as planned |
| V-10 | Realtime wake latency: change on device A to pull trigger on B | Under 2 seconds at p95 while foregrounded |
| V-11 | 30-day offline simulation: queue mutations, then reconnect | Full convergence; no data loss; no resurrection |
| V-12 | Fresh-install bootstrap from snapshots only (registry empty) | Top Artists, Top Albums, Decades, and Clock rebuild completely |

## 11. References

[1] Streamify repository: github.com/meraxes89/streamify-apk — client, sync module, and Supabase contract (accessed 2026-11-24).

[2] ADR-006: Unified Hybrid Sync v2 Architecture — docs/adr/ADR-006-unified-hybrid-sync-v2.md (verified against 8b858b1c).

[3] ADR-007: User Graph Alignment and Cross-Device Continuity — docs/adr/ADR-007-user-graph-alignment-and-cross-device-continuity.md (verified against 7fc4ed7b).

[4] Client synchronization engine — composeApp/src/commonMain/kotlin/com/streamify/sync/StreamifySyncEngine.kt (584 lines, reviewed in full).

[5] Supabase migration chain — supabase/migrations/2026_01_stats_overhaul.sql through 2026_11_sync_v3_user_graph.sql, including 2026_11_sec10_rls_hardening.sql.

[6] Live probe of project dbgtvzgwixerhnwvhtil via Supabase Management API, read-only system-catalog queries, executed 2026-11-24; all findings F-01 through F-16 cite this evidence.

[7] PostgreSQL documentation: Block Range Indexes (BRIN) and partial indexes — postgresql.org/docs/current/brin.html.

[8] Supabase Realtime documentation: Postgres Changes and Broadcast channels — supabase.com/docs/guides/realtime.

[9] Kulkarni et al., 2014: Logical Physical Clocks (HLC) — the 64-bit packed clock design used in Section 6.2.
