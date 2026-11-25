# ADR-006: Unified hybrid sync architecture v2 & schema alignment

* Status: Accepted
* Date: 2026-11-24
* Supersedes: Partial sync specifications in ADR-003
* Verified against: 8b858b1c (staleness protocol in docs/adr/README.md)

## Context

Streamify bridges offline-first Room SQLite with cloud-durable Supabase PostgreSQL.
Production investigation of the live database (`dbgtvzgwixerhnwvhtil`) and KMP sync engine
revealed multiple failure modes:
1. **Legacy ID Poisoning:** 2,192 tracks and 160 user likes in Supabase carried `trk_*` hashes from
   the pre-multiplatform legacy app (`legacy/app`). When hydrated into Room SQLite, these phantom IDs
   prevented ExoPlayer and Innertube stream resolution from playing tracks.
2. **Playlist Deadlock:** PostgREST typed `playlists.id` as `UUID NOT NULL DEFAULT uuid_generate_v4()`.
   The KMP client transmitted `"local_${localPl.id}"`, crashing PostgreSQL with `22P02: invalid input syntax for type uuid`.
   Outbound playlist sync hardcoded `trackIds = emptyList()`, and inbound playlist hydration was entirely unimplemented.
3. **Missing Tables:** `user_item_favorites` (required for artist and album synchronization) was missing from the live
   database, resulting in HTTP 404 relation errors.
4. **Auth & Token Lifecycle:** The client lacked `refresh_token` persistence and rotation, causing all sync calls to
   fail with `401 Unauthorized` after 60 minutes. Anonymous sessions (`auth.uid() = NULL`) triggered RLS rejections.
5. **Schema Bloat & Deprecated Jam/Social Tables:** 12 zombie tables from abandoned RFCs (distributed edge compute, Markov chain state graphs, audio
   chronotypes) plus stale `listening_sessions`, `jam_ops`, `track_comments`, `admin_broadcasts`, and `friend_connections` added dead schema weight:
   - Real-time collaborative playback in KMP is handled via MetroServer WebSockets (`ListenTogetherClient.kt`), rendering Supabase `listening_sessions` and `jam_ops` obsolete.
   - Song comments are streamed directly from YouTube Music / Innertube (`CommentsViewModel` / `CommentsBottomSheet`), rendering `track_comments` completely redundant (0 active rows in production).
   - App announcements and startup popups are delivered via cryptographically signed Ed25519 manifests (`RemoteAnnouncementSource` / `AnnouncementEngine`), rendering `admin_broadcasts` obsolete.
   - `friend_connections` was an unimplemented social placeholder with 0 consumers and 0 rows; friend activity is rendered dynamically from `public.profiles`.

## Decision

1. **Purge & Unify Supabase Schema (SQL Layer):**
   - Execute `cleanup_legacy_tables.sql` to purge all `trk_*` poisoned records from `tracks` and `user_likes`, drop the 12 dead RFC tables, and decommission `listening_sessions`, `jam_ops`, `track_comments`, `admin_broadcasts`, and `friend_connections` along with their RPCs.
   - Apply `2026_11_sync_v2_unification.sql` establishing canonical `user_item_favorites` (`item_type IN ('artist', 'album')`) and `user_search_history` with strict owner-scoped RLS policies.
2. **KMP Playlist Sync Engine Overhaul:**
   - Link `LocalPlaylistEntity.syncId` (UUID v4) directly to `public.playlists.id`. If a legacy local playlist lacks a `syncId`, generate a stable UUID derived from its local ID.
   - Extract actual track IDs from `localPlaylistRepository.getFullPlaylistTracks(localPl.id)` and push to `public.playlist_tracks`.
   - Implement complete inbound playlist hydration in `StreamifySyncEngine`: fetch remote playlists via `fetchCloudPlaylists()`, batch-insert into `local_playlist`, and fetch remote ordered tracks via `fetchCloudPlaylistTracks()`.
3. **Artist & Album Favorites:**
   - Wire `syncArtistsInternal()` and `syncAlbumsInternal()` to `public.user_item_favorites` using deterministic `(user_id, item_type, item_id)` primary keys.
4. **Token Refresh & Concurrency Guard:**
   - Persist OAuth `refresh_token` in `StreamifyAuthManager` and rotate access tokens automatically via Supabase Auth grant `refresh_token`.
   - Gate sync triggers: abort cleanly if unauthenticated or holding bare `ANON_KEY`.
   - Isolate Room SQLite ingestion from Main-thread auto-download thrashing.
5. **User Settings Profile Sync:**
   - Establish `public.user_settings` with a `settings JSONB` payload per user and owner-scoped RLS.
   - Wire `syncSettingsInternal()` in `StreamifySyncEngine` to allow seamless cloud backup and multi-device restoration of themes, audio quality, SponsorBlock, crossfade, and lyrics preferences.
6. **Analytics & Listening History Ledger Sync:**
   - Drop brittle catalog FK constraint on `public.user_listening_history` to prevent 409 rejections on unliked stream plays and protect history from catalog cleans.
   - Wire `syncListeningHistoryInternal()` in `StreamifySyncEngine` and real-time playback hooks in `SupabaseFriendSync.reportPlayback()` to synchronize `PlaybackEventEntity` $\longleftrightarrow$ `user_listening_history`, making Wrapped stats and Analytics charts portable across devices.
7. **Resilient Bulk Operations & Zombie Prevention:**
   - Enforce `UNIQUE (playlist_id, track_id)` on `public.playlist_tracks` and replace serial N+1 track POSTs with single bulk JSON array operations.
   - Implement unlike reconciliation: if a cloud-liked track already exists locally with `liked = false`, issue cloud delete instead of resurrecting it locally.
   - Embed `tracks(*)` metadata in playlist track fetching to eliminate blank `"Synced Track"` dummy rows on new devices.
   - Add limit+offset pagination to cloud like fetches to transcend PostgREST 1,000-row ceilings.

## Consequences

**Positive.**
- All cloud tracks now hold genuine 11-character YouTube video IDs, ensuring seamless streaming in ExoPlayer.
- User playlists synchronize bidirectionally with track ordering and UUID integrity.
- Followed artists and bookmarked albums synchronize across Android, Desktop, and Web.
- Live database size is reduced, RLS policies are strictly enforced, and dead tables are eliminated.

**Negative.**
- Any legacy device still running the pre-multiplatform `legacy/app` will lose access to deleted `trk_*` entries and must upgrade to CMP.

## Compliance

- Schema migrations tracked in `supabase/migrations/2026_11_sync_v2_unification.sql`.
- RLS verified by `supabase/sql/tests/rls_test.sql` and live query validation.
- Architectural design documented in `supabase/HYBRID_SYNC_ARCHITECTURE.md`.

---
<!-- doc-verified-against: 8b858b1c89b91f643cd7159180706fbd931f925e -->
