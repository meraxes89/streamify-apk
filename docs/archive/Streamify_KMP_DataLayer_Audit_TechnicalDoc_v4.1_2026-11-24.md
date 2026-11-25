# Streamify KMP Data Layer Audit — Cloud Backup Fidelity & Gap Analysis (Schema V4.1)

**Document type:** Technical Audit / Architecture Recommendation
**Auditor:** Lead Backend Systems Architect
**Scope:** Room SQLite v29 (27 entities) ↔ Supabase Sync Contract V3 / Target Schema V4 (commit `342a57454`, core submodule `a9e710d`)
**Method:** Read-only live probe of project `dbgtvzgwixerhnwvhtil` (12 tables, 3 views, 130+ RPCs) + static review of `MusicDatabase.kt`, `SyncDao.kt`, `StreamifySyncEngine.kt`, `SupabaseLibrarySync.kt`, `SupabaseModels.kt`
**Date:** 2026-11-24

---

## 1. Executive Summary

The P0–P4 cloud foundation (HLC changelog, idempotent batch RPCs, presence, view bridge) is solid and correctly enforces the three invariants — no media blobs, no EQ sync (the 22-key `SYNCABLE_SETTINGS_KEYS` list correctly excludes all equalizer keys), and the `public.tracks` bridge view remains intact. However, the **client side does not yet exploit this foundation**. The delta outbox (`syncOutboxPushInternal`) pushes only liked tracks — capped at 50 rows — while artists, albums, playlists, podcasts, search, history, and settings sync exclusively through **cold-bootstrap set-differencing**. Any mutation made between bootstraps is lost on a device wipe, and several code paths actively duplicate or drop data (listening history deduplicates by `videoId`, destroying repeat plays; cloud history rows are re-inserted without idempotency keys). The Room v28 `sync_change` ledger and `SyncDao` — a purpose-built offline outbox — are **fully orphaned**: zero references from `composeApp`, `androidApp`, or `desktopApp`. The fastest path to 100% backup fidelity is not new infrastructure but wiring the existing `SyncDao` outbox into the existing `sync_push` RPC, plus four additive V4.1 schema changes.

**Verdict:** ⚠️ Conditional Pass. Infrastructure A-grade; client fidelity C-grade. Estimated user-visible data loss today: listening history beyond the most recent 100 events (deduplicated by track), all non-liked library adds, all saved YouTube playlists, and any cross-device mutation made between full bootstraps.

---

## 2. Live Schema Baseline (Probe Results)

| Cloud table | Live rows | Key columns relevant to this audit |
|---|---|---|
| `profiles` | 53 | `total_plays`, `listening_seconds`, `top_track` (scalar telemetry only) |
| `track_index` | 35 | Canonical `yt:{id}` dedup via `UNIQUE(source, external_id)` |
| `user_likes` | 35 | `snapshot jsonb`, tombstone `deleted_at`, `modified_hlc` |
| `sync_changelog` | 1 | `entity ∈ {like, favorite, playlist, playlist_track, search, history, settings}` |
| `user_listening_history` | **0** | Has `client_event_id uuid` + `UNIQUE(user_id, client_event_id)` — **unused by client** |
| `user_settings` | 0 | `settings jsonb`, `modified_hlc` |
| `user_item_favorites` | 0 | `CHECK item_type ∈ ('artist','album','podcast')` — **no `'playlist'`** |
| `playlists` / `playlist_tracks` | 0 | `uq_playlist_position (playlist_id, position) WHERE deleted_at IS NULL` |
| `user_presence` / `user_devices` / `user_search_history` | 0 | Presence HLC-guarded; search `UNIQUE(user_id, query)` |

All user-graph tables carry `FK → profiles(id) ON DELETE CASCADE` and tombstone/HLC columns. The server is ready for high-fidelity sync; the client simply does not send most of it.

---

## 3. Sync Matrix — Room v29 → Supabase V4

**Status legend:** ✅ Fully Synced · 🟡 Partially Synced · ❌ Missing · 🔒 Deliberately Local (invariant or cache)

| # | Room table (entity) | Cloud target | Status | Evidence / Deficiency |
|---|---|---|---|---|
| 1 | `song.liked = true` (SongEntity) | `user_likes` + `track_index` | 🟡 | Bootstrap set-diff + outbox push capped `take(50)`; unlike reconciliation only runs during bootstrap |
| 2 | `song.inLibrary` (no like) | — | ❌ | `inLibrary: LocalDateTime` never synced; library-adds without thumbs-up are wiped with the device |
| 3 | `artist.followed` (ArtistEntity) | `user_item_favorites('artist')` | 🟡 | Bootstrap set-diff only; never in delta outbox |
| 4 | `album.liked` (AlbumEntity) | `user_item_favorites('album')` | 🟡 | Bootstrap set-diff only |
| 5 | `playlist` — saved YouTube playlists (PlaylistEntity, `liked`/`inLibrary`) | — | ❌ | `user_item_favorites_item_type_check` rejects `'playlist'`; nothing in client code attempts it |
| 6 | `local_playlist` (LocalPlaylistEntity) | `playlists` | 🟡 | Bootstrap push; `syncId` synthesized as `00000000-0000-4000-8000-{hex(localId)}` — **collides across devices** that both auto-increment to the same local id |
| 7 | `pair_song_local_playlist` (positions) | `playlist_tracks.position` | 🟡 | Destructive full-replace: REST `DELETE ?playlist_id=eq.X` then bulk `POST`; non-atomic, no tombstones; reorder regenerates every row → D+I changelog cascade |
| 8 | `podcast_table.isFavorite` (PodcastsEntity) | `user_item_favorites('podcast')` | 🟡 | Favorites only; subscriptions (`inLibrary`) not synced |
| 9 | `podcast_episode_table` (EpisodeEntity) | — | ❌ | Metadata-only entity — **no playback position / played columns exist locally either**; nothing to sync yet |
| 10 | `playback_event` (PlaybackEventEntity) | `user_listening_history` | 🟡 | Lossy: only first 100 events per bootstrap; dedupe by `videoId` (repeat plays of same track dropped); pushes omit `syncId`/`client_event_id` → duplicate cloud rows accumulate; `uq_history_client_event` never fires |
| 11 | `event_artist` (EventArtistEntity) | `user_listening_history.artist_id` | 🟡 | Only first artist of an event survives the mapping |
| 12 | `search_history` (SearchHistory) | `user_search_history` | 🟡 | Set-diff both ways; local entity has no timestamp column, so recency ordering cannot be restored |
| 13 | DataStore preferences (22 keys) | `user_settings` | 🟡 | Bootstrap merge-only; `applyDeltaOp` has **no `"settings"` branch** — changelog ops emitted by `tg_bump_changelog` are silently dropped on pull |
| 14 | Playback queue (`queue`) | `user_presence.queue_ids` | 🟡 | Transient presence heartbeat (hardcodes `isPlaying=false`); not a durable backup |
| 15 | `sync_change` (SyncChangeEntity) | `sync_changelog` (analogous) | 🔒→❌ | Purpose-built offline outbox (`pending()`, `markSent()`, `recordPending()`) with **zero callers** — orphaned since P2P library-sync work |
| 16 | `song_info` (SongInfoEntity) | — | 🔒 | Metadata cache; rehydrates from likes `snapshot`/`meta` |
| 17 | `new_format` (NewFormatEntity) | — | 🔒 | Stream-format cache (itag/bitrate) — client sovereignty |
| 18 | `lyrics` (LyricsEntity) | — | 🔒 | Invariant: no raw lyrics trees in cloud |
| 19 | `translated_lyrics` | — | 🔒 | Same invariant |
| 20 | `deleted_lyrics` (v29) | — | 🔒 | Local cache GC tombstone |
| 21 | `set_video_id` (SetVideoIdEntity) | — | 🔒 | YouTube API plumbing, device-scoped |
| 22 | `google_account` (GoogleAccountEntity) | — | 🔒 | Credentials/cookies — must never leave device |
| 23 | `notification` (NotificationEntity) | — | 🔒 | Derived from followed artists (recomputable) |
| 24 | `followed_artist_single_and_album` | — | 🔒 | Derived cache |
| 25–27 | `autoeq_entry`, `autoeq_index_meta`, `autoeq_curve` | — | 🔒 | **EQ invariant: device-bound, never synced** ✓ |
| 28 | `artist_motion` (ArtistMotionEntity) | — | 🔒 | Rendering cache |

**Count:** 8 🟡 partially, 5 ❌ missing, 13 🔒 correctly local, 0 ✅ fully.

---

## 4. Gap Analysis (Prioritized)

### P0 — Direct user data loss on device wipe

1. **Listening history is truncated and duplicated.** Bootstrap syncs only `getPlaybackEventsByOffset(0, 100)`, deduplicates against cloud by `videoId` (so a track played 40 times syncs once), and pushes without any idempotency key — every re-bootstrap inserts fresh UUID rows server-side. Cross-device "Stats/Wrapped" is therefore impossible from cloud data alone, violating the 100%-graph-recovery invariant. The fix requires **no server change**: `playback_event.syncId` (v28), `user_listening_history.client_event_id`, and `uq_history_client_event` already exist and are unused.
2. **`inLibrary` without `liked` never syncs** (Q4). A user who saves an album's tracks to library without liking them loses every one on reinstall.
3. **Saved YouTube playlists (`playlist` table) are unsyncable** (Q3) — blocked by the `item_type` CHECK constraint.
4. **Podcast subscriptions and episode state** (Q2): only `isFavorite` syncs; `inLibrary` subscriptions are local-only; episode playback progress is not persisted locally at all.

### P1 — Correctness / conflict hazards

5. **Delta outbox covers only likes** (`take(50)`): favorites, playlists, playlist tracks, search, settings, history have no push path once the cursor is initialized — offline mutations on device A never reach device B, and A's stale set-diff can resurrect cloud-deleted items.
6. **`applyDeltaOp` drops `"settings"` ops** — the server emits them; the client `when` block has no branch, so remote setting changes never apply.
7. **Destructive, non-atomic playlist replace:** `syncPlaylist` issues `DELETE playlist_tracks?playlist_id=eq.X` then a separate bulk `POST`. A failure between the two leaves the playlist empty; every reorder rewrites the full membership and floods `sync_changelog` with D+I pairs for all N tracks (the "conflict cascade" of Q5).
8. **Playlist UUID synthesis collides:** `"00000000-0000-4000-8000-" + hex(localId)` — two devices that each create a first playlist both map to the same cloud UUID, merging unrelated playlists.
9. **Search history loses ordering:** local `SearchHistory` has no timestamp; cloud `searched_at` cannot round-trip.

### P2 — Durability niceties

10. Queue is presence-only (`queue_ids` transient, `is_playing` hardcoded `false` in heartbeat).
11. Per-track `totalPlayTime` is local-only (recomputable from synced events once P0-1 is fixed).
12. `take(50)`/`take(100)` caps everywhere — bootstrap must become paginated drains.

---

## 5. Answers to the Seven Questions

1. **Raw listening events vs rollups:** Sync **raw events** to `user_listening_history` keyed by `client_event_id` (the column, unique index, and BRIN index already exist — this is exactly what they were built for). Row volume is modest (a heavy user generates ~50–150 rows/day; pg_cron GC + BRIN keep it cheap), and per-event timestamps are non-negotiable for Listening Clock / Decades / Fingerprint analytics. Add a `v_user_listening_daily` rollup **view** (Section 6) so Wrapped queries never scan raw rows; do not materialize rollups as the source of truth.
2. **Podcasts:** Favorites do sync (`item_type='podcast'`, bootstrap only). Subscriptions should ride the same table via `metadata jsonb`. Episode progress **cannot sync yet** because Room v29's `EpisodeEntity` stores no position/played fields — add local columns first (Section 7, step 6), then sync to the new `user_podcast_episode_state` table.
3. **Saved YouTube playlists:** Not synced today; the CHECK constraint is the blocker plus no client code path. V4.1 widens `item_type` to `'playlist'` and the client maps `PlaylistEntity(id, liked, inLibrary, favoriteAt)` to favorite ops.
4. **`inLibrary` vs `liked`:** Real gap. Model it explicitly with a new `user_library_tracks` table (Section 6.2) rather than overloading `user_likes` — the two states have different lifecycles (add-to-library vs thumbs-up) and different tombstone semantics.
5. **Playlist reordering:** Current implementation is the worst case (full rewrite, no tombstones, D+I cascade per reorder). Replace with position-diff upserts through `sync_push` (`playlist_track` ops with explicit `position`, tombstone deletes via `deleted_at`); `uq_playlist_position` already tolerates this. Reorder then costs O(moved) ops, not O(N).
6. **Settings:** Keep the `user_settings` JSONB doc (simple, one row, LWW by `modified_hlc` is appropriate for preferences). Fix the two client defects: apply inbound `"settings"` delta ops, and push on change rather than bootstrap-only. EQ/AutoEq stay excluded — already correct.
7. **SyncDao outbox:** **Yes — wire it in.** It is the missing half of the architecture: `recordPending()` at the DAO transaction boundary gives crash-safe capture; `pending(limit)` + `markSent()` map 1:1 onto `sync_push` batches; the `entity` vocabulary (`like`, `lp`, `lp_order`, `lp_track`, `history`) needs only a thin mapper to the changelog vocabulary. Reuse `playback_event.syncId` and `local_playlist.syncId` (both added in v28 for the P2P design) as the cloud `client_event_id` and `playlists.id` respectively — they were designed for exactly this.

---

## 6. Recommended V4.1 Schema Changes (Additive Only)

All statements are additive; none touch the `public.tracks` view bridge or existing data.

### 6.1 Widen `user_item_favorites` to cover playlists

```sql
-- V4.1-01: allow saved YouTube playlists as favorites
ALTER TABLE public.user_item_favorites
  DROP CONSTRAINT user_item_favorites_item_type_check;
ALTER TABLE public.user_item_favorites
  ADD CONSTRAINT user_item_favorites_item_type_check
  CHECK (item_type = ANY (ARRAY['artist','album','podcast','playlist']));
```

### 6.2 New `user_library_tracks` (in-library without like)

```sql
-- V4.1-02: durable "added to library" state, independent of likes
CREATE TABLE public.user_library_tracks (
  user_id      uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
  track_id     text NOT NULL,
  added_at     timestamptz NOT NULL DEFAULT now(),
  deleted_at   timestamptz,
  modified_hlc bigint NOT NULL DEFAULT 0,
  PRIMARY KEY (user_id, track_id)
);
CREATE INDEX idx_library_tracks_chrono
  ON public.user_library_tracks (user_id, added_at DESC)
  WHERE deleted_at IS NULL;
```

### 6.3 New `user_podcast_episode_state` (future episode progress)

```sql
-- V4.1-03: cross-device podcast episode progress/completion
CREATE TABLE public.user_podcast_episode_state (
  user_id       uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
  episode_id    text NOT NULL,
  podcast_id    text NOT NULL,
  position_sec  integer NOT NULL DEFAULT 0,
  completed     boolean NOT NULL DEFAULT false,
  modified_hlc  bigint NOT NULL DEFAULT 0,
  updated_at    timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, episode_id)
);
CREATE INDEX idx_episode_state_podcast
  ON public.user_podcast_episode_state (user_id, podcast_id)
  WHERE completed = false;
```

### 6.4 Extend changelog vocabulary + daily rollup view

```sql
-- V4.1-04: changelog accepts the two new entity types
ALTER TABLE public.sync_changelog DROP CONSTRAINT changelog_entity_chk;
ALTER TABLE public.sync_changelog
  ADD CONSTRAINT changelog_entity_chk
  CHECK (entity = ANY (ARRAY['like','favorite','playlist','playlist_track',
                             'search','history','settings','library_track','episode_state']));

-- V4.1-05: Wrapped-ready daily rollup (view — zero storage)
CREATE OR REPLACE VIEW public.v_user_listening_daily AS
SELECT user_id,
       date_trunc('day', created_at) AS day,
       count(*)                      AS plays,
       sum(listened_duration_sec)    AS seconds,
       count(DISTINCT track_id)      AS distinct_tracks
FROM public.user_listening_history
WHERE deleted_at IS NULL
GROUP BY user_id, date_trunc('day', created_at);
```

### 6.5 RPC enhancement — atomic playlist reorder in `sync_push`

Extend the existing `sync_push(p_ops jsonb, p_device_id text)` handler to accept `playlist_track` ops of the form `{"op":"U","key":"<trackId>","playlist_id":"...","position":N}` and `{"op":"D", ...}` (tombstone `deleted_at = now()` instead of row delete). Inside the RPC, apply ops in HLC order within one transaction, re-pack positions with a gap-tolerant `row_number()` normalization, and let `tg_bump_changelog` emit exactly one op per changed row. This removes the client's DELETE-then-POST pattern entirely. `user_listening_history` needs **no server change** — the client must simply populate `client_event_id` (idempotency then works via `uq_history_client_event`).

---

## 7. KMP Implementation Plan

1. **Wire `SyncDao` as the universal outbox (highest leverage).** In `DatabaseDao`/repository write paths for likes, follows, album likes, playlist likes, podcast favorites, local-playlist mutations, and playback-event inserts, call `SyncDao.recordPending(SyncChangeEntity(...))` inside the same `@Transaction`. `StreamifySyncEngine.syncOutboxPushInternal` drains `syncDao.pending(500)`, maps entities (`like→like`, `artist|album|playlist|podcast→favorite`, `lp→playlist`, `lp_track|lp_order→playlist_track`, `history→history`, settings writes→`settings`) into `sync_push` ops, then `markSent()` with the returned cursor. This one change converts every 🟡 bootstrap-only lane into a durable delta lane.
2. **Fix listening history fidelity.** On event insert, generate a UUID `syncId` (v28 column already exists). Drain `eventsWithoutSyncId()` fully (paginated, no cap), push each with `client_event_id = syncId`, `listened_duration_sec`, `artist_id` list, `album_id`, original `created_at`. Inbound: hydrate by `client_event_id` existence, not `videoId` set-diff. Backfill existing local rows once.
3. **Sync `inLibrary`** to `user_library_tracks` via the same favorite-op pattern; on bootstrap, hydrate library membership before likes so cover-art/library views restore correctly.
4. **Sync saved YouTube playlists** (`PlaylistEntity.liked/inLibrary`) to `user_item_favorites('playlist')` once V4.1-01 lands; carry `{title, thumbnail, trackCount}` in `metadata`.
5. **Replace destructive playlist sync.** Delete the REST DELETE+POST path in `syncPlaylist`; compute a position diff (added / removed / moved) and emit `playlist_track` I/U/D ops through `sync_push`; tombstones reconcile via `deleted_at`. Generate **UUIDv7 at playlist creation time** and store it in `local_playlist.syncId` immediately — remove the hex(localId) synthesis and migrate existing synthesized IDs once (regenerate UUIDs for playlists whose cloud row has no `youtube_playlist_id` match).
6. **Podcast episode state.** Add `position_sec: Int` / `completed: Boolean` / `modifiedAt: Long` columns to `EpisodeEntity` (Room v30 migration), persist progress from the player, then sync to `user_podcast_episode_state` with HLC-LWW (max `modified_hlc` wins; playback position is monotonic-per-device).
7. **Apply settings deltas.** Add the `"settings"` branch to `applyDeltaOp` (merge key-by-key into DataStore unless locally newer) and push `settings` ops on DataStore write for the 22 whitelisted keys. EQ keys remain excluded by the whitelist — invariant preserved.
8. **De-cap the bootstraps.** Replace `take(50)`/`take(100)` with looped drains using each repository's paging; checkpoint per-entity progress in DataStore so an interrupted bootstrap resumes rather than repeats.
9. **Presence truthfulness.** Pass real `isPlaying` and periodic position into `heartbeatPresence` (currently hardcoded `false`) so hand-off resumes near the right offset.

**Sequencing:** Steps 1–2 deliver the bulk of "never lose anything" (history + all delta lanes) and can ship against the current V4 schema unchanged. Steps 3–5 pair with the V4.1-01/02 DDL and the `sync_push` reorder enhancement. Steps 6–9 follow independently.

---

## 8. Defect Register (code-level, found during audit)

| ID | Severity | Location | Defect |
|---|---|---|---|
| D1 | High | `StreamifySyncEngine.syncPlaylistsInternal` | Deterministic UUID from local autoincrement id collides across devices |
| D2 | High | `SupabaseLibrarySync.syncPlaylist` | Non-atomic `DELETE`-all + bulk `POST` for playlist tracks; failure window empties playlist; reorder rewrites all rows |
| D3 | High | `syncListeningHistoryInternal` / `recordPlaybackHistory` | No idempotency key sent → duplicate cloud history rows; inbound dedupe by `videoId` drops repeat plays |
| D4 | Medium | `applyDeltaOp` | No `"settings"` branch — server ops silently dropped |
| D5 | Medium | `syncOutboxPushInternal` | Only likes pushed, `take(50)` cap |
| D6 | Medium | `syncListeningHistoryInternal` | 100-event bootstrap window; unbounded history never synced |
| D7 | Low | `syncPresenceInternal` | `isPlaying` hardcoded `false`; queue only from saved queue |
| D8 | Low | `SearchHistory` | No timestamp column — cloud `searched_at` cannot restore local ordering |

All invariants verified intact: no media/stream URLs persisted cloud-side (only `track_index` metadata), EQ/AutoEq excluded from every sync path, and the `public.tracks` view bridge untouched by any recommendation above.
