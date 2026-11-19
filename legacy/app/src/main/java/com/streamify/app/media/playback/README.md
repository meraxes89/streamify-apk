# `media/playback/` — Playback Authority

The foreground MediaSessionService and the queue engines that decide
what plays next.

## Files

| File | What it is |
|---|---|
| `PlaybackService.kt` | Media3 `MediaSessionService`. Owns the player, notification, static state (`isBuffering`, `onSeekNext/PrevListener`, `lastRenewalMediaId`) that ViewModels observe. Declared in `AndroidManifest.xml` as `.media.playback.PlaybackService`. |
| `QueueEngine.kt` | Core queue ops (shuffle, repeat, next/prev semantics). |
| `DynamicQueueManager.kt` | Sliding 2-track JIT queue extension (lookahead fill). |
| `OnlineTrackProcessor.kt` | Turns a queued `Track` into a resolved, buffered source (router + cache + fallbacks). |

The playback control brain lives in `viewmodel/PlayerViewModel.kt`
(+ its `Player*` companion modules); this package is the *service
surface* the viewmodel drives.
