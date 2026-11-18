# `data/` — Data Layer

Everything that talks to a source of truth that is not the app itself:
databases, cloud backends, remote APIs, caches, and the engines that
transform raw network payloads into playable tracks.

## Package map

| Package | Responsibility |
|---|---|
| `models/` | Immutable value models (`Track`, `Recommendation`, ...) shared by every layer. Pinned by ProGuard keep rules. |
| `repository/` | The app's single source of truth for track & playlist state. |
| `persistence/` | SQLite lifecycle, storage quotas, backup/restore, reset. |
| `discovery/` | Title/artist matching, dedup, candidate ranking. |
| `ingestion/` | Import pipelines: first-feed bootstrap, Exportify, neuro queues. |
| `telemetry/` | Listening-stats engine behind Wrapped-style analytics. |
| `lyrics/` | SLYR/LRC binary lyric cache management. |
| `native/` | Kotlin side of native (Rust/Lofty) metadata tagging. |
| `network/` | The stream-resolution engine + search/AI providers. |
| `supabase/` | Supabase backend clients (auth, jam, stats, sync, realtime). |
| `spotify/` | Spotify OAuth (PKCE) + library extraction. |
| `youtube/` | YouTube Music session, batch resolve, playlist scraping. |
| `update/` | In-app self-update checker. |
| `NativeBridge.kt` | **JNI boundary — do not move.** See below. |

## The `NativeBridge` pinning rule

`NativeBridge.kt` must stay at exactly `com.streamify.app.data.NativeBridge`.
Both native engines register JNI functions via **name-mangled symbols**
(`Java_com_streamify_app_data_NativeBridge_*`) in:

- `rust/src/jni_bridge.rs`
- `native/jni/jni_bridge.cc`

Moving or renaming the class severs 140+ native bindings at runtime.
When you need new native functionality, add `external fun`s to this file
and a matching `Java_com_streamify_app_data_NativeBridge_<name>` symbol
on the native side.

## Layering rules

- `ui/` and `viewmodel/` consume data **only through `repository/`**
  (or the provider clients directly when the repository is not the owner).
- `data/` never imports from `ui/`, `viewmodel/`, or `media/`
  (the one historical exception is documented in `repository/`).
- Cross-provider flows (e.g. Spotify ingest -> Supabase sync) wire up in
  `viewmodel/` or `media/ingestion/`, not inside a provider client.
