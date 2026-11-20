# `data/update/` — Self-Update

## Files

| File | What it is |
|---|---|
| `StreamifyUpdateManager.kt` | Checks the GitHub releases endpoint for newer builds than `BuildConfig`. |

Installation is handled by `util/ApkInstaller` (download via
`worker/DownloadWorker`). The update card UI is
`ui/components/UpdateAvailableCard`.
