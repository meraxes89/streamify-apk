# `data/repository/` — Track & Playlist Repositories

The app's single source of truth for local track and playlist state.
ViewModels and services read from here; nothing else touches SQLite or
the cloud sync tables directly.

## Files

| File | What it is |
|---|---|
| `TrackRepositoryApi.kt` | The abstraction the rest of the app codes against (backs DI/test seams). |
| `TrackRepository.kt` | Implementation: local catalog hydration, likes, stream upserts, cloud-likes LWW sync, search. |
| `PlaylistRepository.kt` | Playlist CRUD + Supabase playlist sync + Exportify import glue. |
| `EdgeMeshRepository.kt` | Local mirror of the Edge-Mesh peer state (LAN session discovery), backed by TitanComputeWorker. |

## How it connects

```
viewmodel/*  ──▶  TrackRepositoryApi  ◀──  media/playback/*
                        │
                        ▼
     SQLite (via data.NativeBridge)  +  data/supabase/*  (cloud sync)
```

`TrackRepository` is where native SQLite (Rust core), the Supabase
clients and the discovery engines meet. If you are adding a new
catalog-level feature (likes sync, smart playlists, history pruning),
start in `TrackRepository` and expose it through `TrackRepositoryApi`.
