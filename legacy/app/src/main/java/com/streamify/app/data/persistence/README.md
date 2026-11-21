# `data/persistence/` — Storage Lifecycle

Everything that owns bytes-on-disk or the database lifecycle: quota
accounting, backup/restore, offline vaulting, and the nuclear reset.

## Files

| File | What it is |
|---|---|
| `DatabaseInitializer.kt` | Idempotent native SQLite bootstrap — must run before any repository call. |
| `StorageManager.kt` | Storage usage breakdown + cache eviction surface (`StorageBreakdown`). |
| `BackupManager.kt` | Export/import of user data bundles (likes, playlists, settings). |
| `SmartOfflineVaultEngine.kt` | Predictive offline pinning of tracks likely to be played. |
| `NuclearResetManager.kt` | Full app reset FSM (`NukeState`) used by the admin terminal. |

## Notes for contributors

- `DatabaseInitializer.ensureInitialized()` is called at the top of
  every repository entry point; do not assume ordering elsewhere.
- Cache/eviction *policy* for played audio lives in `media/cache/`;
  this package owns *storage accounting and user data persistence*.
- `NuclearResetManager` coordinates repositories, `StorageManager` and
  `AuthManager` — extend it rather than writing a new reset path.
