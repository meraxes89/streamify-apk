# `data/supabase/` — Supabase Backend Clients

All Supabase (PostgREST + Realtime) access. The god-object was split in
v1.1.0 into domain clients behind a thin `SupabaseClient` facade.

## Files

| File | What it is |
|---|---|
| `SupabaseClient.kt` | Thin facade: session, auth state, cross-domain broadcasts. |
| `SupabaseAdminClient.kt` | Admin surface (service-role operations). |
| `SupabaseTracksClient.kt` | Track catalog upserts/fetches. |
| `SupabaseStatsClient.kt` | Telemetry merge endpoints. |
| `SupabasePlaylistSyncClient.kt` | Playlist LWW sync. |
| `SupabaseCommunityClient.kt` | Community hub (comments, friend activity). |
| `SupabaseEdgeMeshClient.kt` | Edge-Mesh peer registry. |
| `SupabaseRealtimeClient.kt` | Realtime channel plumbing. |
| `SupabaseModels.kt` | DTOs (`TrackComment`, `FriendActivity`, `CommunityPlaylist`, ...). |
| `AuthManager.kt` | App auth state machine (`AuthState`), Google sign-in, profile. |

## Rules

- Schema lives in `supabase/schema.sql` + `supabase/migrations/`.
  Client changes must be mirrored there.
- Never call the admin client from UI code.
- A new domain? Add a focused client + a facade method, don't grow
  `SupabaseClient` back into a god object.
