# ADR-007: User Graph Alignment and Cross-Device Continuity

* Status: Accepted
* Date: 2026-11-24
* Supersedes: Partial sync specifications in ADR-003 and ADR-006 (ADR-005 v2)
* Verified against: 7fc4ed7b5a0848381b20f98763afea4b921ccdde (staleness protocol in docs/adr/README.md)

## Context

Streamify's local SQLite Room database (schema v28) maintains high-fidelity listener data that enables offline-first operation and full `.backup` restoration. While ADR-006 aligned core tables (likes, artists, albums, playlists, settings, history), a comprehensive audit of the local Room schema against the Supabase cloud schema (`dbgtvzgwixerhnwvhtil`) identified six critical user-graph gaps:

1. **Chronological Like Ordering (`favoriteAt`):** Local Room SQLite preserves the exact epoch/timestamp when a user liked a track (`SongEntity.favoriteAt`). Sync v2 discarded this timestamp during like hydration, defaulting all imported cloud tracks to `now()`, destroying the user's chronological library order.
2. **Search History Disconnection (`search_history`):** The local `search_history` table was entirely isolated from `public.user_search_history`. Search queries did not sync between devices.
3. **YouTube Playlist Linkage (`youtubePlaylistId`):** Local playlists holding synced YouTube IDs (`LocalPlaylistEntity.youtubePlaylistId`) lost this pointer in Supabase `public.playlists`, breaking YouTube-synced playlists when restored on secondary devices.
4. **Listening History Album Attribution (`albumBrowseId`):** `PlaybackEventEntity.albumBrowseId` was discarded when recording history in Supabase `public.user_listening_history`, degrading album-level listening analytics in Wrapped.
5. **Podcast Favorites Limitation:** `public.user_item_favorites` enforced `CHECK (item_type IN ('artist', 'album'))`, rejecting podcast subscriptions (`item_type = 'podcast'`).
6. **Active Session & Playback Continuity:** Moving between phone, tablet, and desktop lacked hand-off continuity (`DataStoreManager.recentMediaId`, `recentPosition`, and queued playback state).

### Client-Sovereignty Principle
The backend is designed strictly as a **User Graph and Relational Identity Ledger** (11-character YouTube video IDs, browse IDs, channel IDs, UUIDs, timestamps, and active session JSONB). All scraping, audio streaming extraction, lyric resolution, and audio processing execute client-side. No heavy media scraping or transient cache blobs are stored in PostgreSQL.

### Equalizer Tuning Constraint
Acoustic profiles and Equalizer frequency curves (`equalizer_*`) are intrinsically coupled to physical transducer hardware and headphone characteristics. They are deliberately kept device-local and excluded from cloud synchronization.

---

## Decision

1. **Supabase DDL Migration (`2026_11_sync_v3_user_graph.sql`):**
   - Added `youtube_playlist_id TEXT` to `public.playlists`.
   - Added `album_id TEXT` to `public.user_listening_history`.
   - Updated constraint on `public.user_item_favorites` to `CHECK (item_type IN ('artist', 'album', 'podcast'))`.
   - Added `active_session JSONB DEFAULT NULL` to `public.profiles`.
   - Live migration verified and executed on Supabase project `dbgtvzgwixerhnwvhtil`.

2. **Chronological Like Timestamp Preservation:**
   - `SupabaseLibrarySync.syncLike` and `uploadLikedTracksBatch` transmit the ISO-8601 representation of `SongEntity.favoriteAt` to `user_likes.created_at`.
   - `fetchCloudLikesWithTime` and `fetchCloudLikedTracks` select `created_at` from `user_likes` and hydrate `SongEntity.favoriteAt` in Room SQLite with the original like timestamp.

3. **Search History Synchronization:**
   - Wired `syncSearchHistoryInternal()` in `StreamifySyncEngine` between `SearchRepository` (`SearchHistory`) and `public.user_search_history`.
   - Implemented `fetchCloudSearchHistory` and `uploadSearchHistory` batching in `SupabaseLibrarySync`.

4. **YouTube Playlist Pointer Alignment:**
   - `LocalPlaylistEntity.youtubePlaylistId` is serialized outbound to `public.playlists.youtube_playlist_id`.
   - Inbound playlist hydration preserves `youtube_playlist_id` when inserting new local playlists.

5. **Listening History Album Association:**
   - `PlaybackEventEntity.albumBrowseId` is serialized outbound to `public.user_listening_history.album_id`.
   - Inbound history hydration populates `albumBrowseId` for analytics and annual Wrapped reports.

6. **Podcast Subscriptions:**
   - Wired `syncPodcastsInternal()` in `StreamifySyncEngine` between `PodcastRepository` and `public.user_item_favorites` using `item_type = 'podcast'`.

7. **Cross-Device Active Playback Session Hand-off:**
   - Implemented `updateActiveSession` and `fetchActiveSession` targeting `profiles.active_session`.
   - Synchronizes `track_id`, `position_ms`, and `queue_ids` across active devices, enabling seamless playback hand-off upon app launch.

8. **Equalizer Boundary:**
   - All `equalizer_*` keys remain strictly local, honoring device-specific audio calibration.

---

## Consequences

### Positive
- **Complete Library Restoration:** Reinstalling the app or signing in on a new device fully restores liked songs in chronological order, search history, podcast subscriptions, and YouTube playlist linkages.
- **Cross-Device Playback Continuity:** Users can switch devices and pick up playing tracks and queues without interruption.
- **Client Sovereignty Maintained:** The cloud backend remains clean, storing relational graph state without scraping overhead or audio file bloat.

### Negative / Trade-offs
- Slight network footprint increase during sync (< 5KB JSON payload) to synchronize search history and session state.

---

<!-- doc-verified-against: dd58e0b3b60e16bed71200d58c9b204431562349 -->
