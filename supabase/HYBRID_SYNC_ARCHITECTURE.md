# Streamify Hybrid Database Architecture (Room SQLite ↔ Supabase Postgres)

**Document Version:** 1.0.0  
**Author:** Streamify Sovereign Engineering Team  
**Status:** Approved & Implemented  
**Scope:** Multiplatform Android / Desktop / iOS Library Synchronization

---

## 1. Executive Summary & Problem Statement

Streamify bridges two disparate persistence systems:
1. **Local SimpMusic Core**: An offline-first, low-latency SQLite database powered by AndroidX Room (`SongEntity`, `LocalPlaylistEntity`, `ArtistEntity`, `AlbumEntity`).
2. **Streamify Cloud Core**: A globally distributed PostgreSQL backend on Supabase (`tracks`, `user_likes`, `user_item_favorites`, `playlists`, `playlist_tracks`, `user_listening_history`).

### Corruption Risks in Hybrid Dual-Store Architectures
Without strict architectural boundaries, dual-store synchronization suffers from:
- **Partial Write Inconsistencies**: Network interruptions during sync leaving orphaned foreign keys or half-populated entities.
- **Zombie / Resurrected Items**: Deleting an item locally while offline causes a naive cloud fetch to re-insert it ("resurrect" the deleted song).
- **Echo / Ping-Pong Loops**: Local Room mutation triggers an upload to Supabase, which triggers a realtime notification, which writes back to Room, causing infinite looping and battery drain.
- **ID Collisions**: Local databases assigning integer auto-increment IDs (`Long`) colliding with distributed cloud UUIDs (`UUIDv4`).
- **Schema Drift & Serialization Aborts**: A column added to the cloud crashes client-side JSON parsers, locking users out of their libraries.

---

## 2. Core Architectural Pillars

### Pillar I: Local-First Single Source of Truth
- The client UI **never blocks on the network**. All UI components (`HomeScreen`, `LibraryScreen`, `NowPlaying`) read from and write directly to local Room SQLite flows.
- Operations succeed instantly offline. Supabase serves as an asynchronous, durable **Cloud Backup and Cross-Device Ledger**.

### Pillar II: Atomic Batch Transactions (`withTransaction`)
- All inbound sync writes from Supabase are applied in bounded, transactional batches (25–50 items).
- If a network error or corruption occurs on record #47 of a batch, Room rolls back that atomic transaction completely—leaving the existing database intact without partial or corrupted state.

### Pillar III: Deterministic Conflict Resolution (Timestamp LWW)
- Every entity incorporates a timestamp (`favoriteAt`, `inLibrary`, `created_at`, `updated_at`).
- In case of conflicting states between device and cloud, **Last-Write-Wins (LWW)** is enforced deterministically.
- A newer local un-like action supersedes an older cloud like, deleting the cloud record rather than restoring it locally.

### Pillar IV: Echo Suppression (`isSyncActive`)
- To eliminate ping-pong loops, the `StreamifySyncEngine` asserts an `isSyncActive` re-entrancy gate during remote-to-local insertions. Local repository observers observe this flag and suppress reciprocal network pushes.

### Pillar V: Defensive Serialization
- All Ktor JSON deserializers strictly enable `ignoreUnknownKeys = true` and `coerceInputValues = true`. Future backend migrations never break running client builds.

---

## 3. Entity Mapping Specification

| SimpMusic Local Room Entity | Streamify Cloud Supabase Table | Primary Key Strategy | Conflict Resolution |
| :--- | :--- | :--- | :--- |
| `SongEntity` (`videoId`, `liked=true`) | `public.user_likes` $\bowtie$ `public.tracks` | `videoId` (YouTube / Stream ID) | Idempotent upsert via `resolution=merge-duplicates` |
| `ArtistEntity` (`channelId`, `followedAt`) | `public.user_item_favorites` (`item_type='artist'`) | `channelId` (Channel / Artist ID) | Union merge on `item_id` |
| `AlbumEntity` (`albumId`, `bookmarkedAt`) | `public.user_item_favorites` (`item_type='album'`) | `albumId` (Browse / Album ID) | Union merge on `item_id` |
| `LocalPlaylistEntity` (`id: Long`) | `public.playlists` (`id: UUID`) | `UUID` stored in playlist metadata | Local-to-Cloud UUID linkage |
| `PairSongLocalPlaylist` (`position`) | `public.playlist_tracks` (`position`) | `(playlist_id, track_id)` | Position-preserving upsert |
| `SongPlayCount` / Analytics | `public.user_listening_history` + `profiles` | Incremental timestamp ledger | Append-only with deduplication |

---

## 4. The Three-Phase Synchronization Engine

```
┌─────────────────────────────────────────────────────────────┐
│                       PHASE 1                               │
│              LOCAL-FIRST MUTATIONS (OFFLINE)                │
│  User taps Like / Create Playlist / Follow Artist           │
│  ➜ Immediate atomic write to local Room SQLite              │
│  ➜ UI reflects state instantly (< 5ms)                      │
│  ➜ Enqueue change event to background outbox                │
└──────────────────────────────┬──────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────┐
│                       PHASE 2                               │
│             OUTBOX DRAIN & CONFLICT RESOLUTION              │
│  Check network connectivity & authentication state          │
│  ➜ Batch upload pending likes/favorites to Supabase         │
│  ➜ Upsert track catalog metadata in chunks (40 IDs)         │
│  ➜ Enforce Last-Write-Wins via timestamps                   │
└──────────────────────────────┬──────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────┐
│                       PHASE 3                               │
│            INBOUND CLOUD HYDRATION (LOGIN/STARTUP)          │
│  Triggered on AuthState.Authenticated & Cold Start          │
│  ➜ Fetch cloud likes (`user_likes` + `tracks`)              │
│  ➜ Fetch item favorites (artists + albums)                  │
│  ➜ Suppress echo (`isSyncActive = true`)                    │
│  ➜ Atomic batch insert into Room withTransaction            │
│  ➜ Room StateFlows automatically update UI                  │
└─────────────────────────────────────────────────────────────┘
```

---

## 5. Verification & Testing Guardrails

1. **Cold-Start Fresh Install Test**:
   - Install fresh APK with completely empty local SQLite database.
   - Sign in via Google OAuth.
   - Assert: Cloud liked songs, artists, and playlists populate into Room within 3 seconds.
2. **Offline Mutation Test**:
   - Disconnect Wi-Fi / cellular.
   - Like 5 tracks, unlike 2 tracks.
   - Assert: Room database updates immediately; no network timeout dialogs appear.
   - Reconnect network.
   - Assert: Outbox drains successfully to Supabase without resurrecting the 2 unliked tracks.
3. **Loop Resistance Test**:
   - Monitor network inspection logs during sync.
   - Assert: Zero duplicate or circular requests occur.
