# `util/` — Cross-Cutting Utilities

## Files

| File | What it is |
|---|---|
| `SLog.kt` | App-wide logging facade with opt-in diagnostic capture. Never use `printStackTrace()`. |
| `Trace.kt` | Correlation IDs for the admin terminal. |
| `DurationFormatter.kt` | `mm:ss` formatting. |
| `TimeGreeting.kt` | Time-of-day greetings. |
| `PermissionHelper.kt` | Runtime permission ask helpers. |
| `ApkInstaller.kt` | APK install intents (self-update flow). |
| `MediaStoreScanner.kt` | MediaStore rescan after downloads. |
| `TrackShareCard.kt` | Share-card rendering. |
| `StreamifyHapticEngine.kt` | Haptic feedback patterns. |
| `FleetConfig.kt` | Remote-adaptive Innertube client fleet (pairs with `fleet-config.json` at repo root and `tools/write_fleet_config.py`). |

`SLog` is imported essentially everywhere — treat its API as frozen.
