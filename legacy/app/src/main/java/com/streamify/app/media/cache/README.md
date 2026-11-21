# `media/cache/` — Audio Byte Cache Policy

Owns where streamed bytes live and when they die.

## Files

| File | What it is |
|---|---|
| `AudioCacheManager.kt` | The stream URL/content cache (`NetworkEngine` reads it). |
| `PredictivePreBufferManager.kt` | Lookahead pre-buffering for gapless transitions. |
| `PriorityWeightedEvictor.kt` | Priority-weighted LRU eviction. |
| `ElasticStorageAllocator.kt` | Grows/shrinks cache budget by free space. |
| `LosslessRemuxer.kt` | Remuxes downloaded streams into tagged files. |

Storage *accounting* (what the user sees in Settings) is
`data/persistence/StorageManager`; policy lives here.
