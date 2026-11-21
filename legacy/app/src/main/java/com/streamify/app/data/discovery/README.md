# `data/discovery/` — Identity, Matching & Ranking

The "is this the same song?" brain trust. Every resolution, queue dedup
and library hydration decision funnels through these gates, so a
regression here silently corrupts what users hear.

## Files

| File | What it is |
|---|---|
| `FuzzyTitleMatcher.kt` | Root-hash + fuzzy title/artist similarity. Backed by `FuzzyTitleMatcherTest` (the JVM CI shard). |
| `ReRanker.kt` | Scores candidate lists by acoustic/harmonic affinity. |
| `AntiDriftScoringEngine.kt` | Junk filtering (compilations, 10-hour mixes), artist saturation caps, dedup, ranking. Rust-vector accelerated with pure-Kotlin fallback. |

## Consumers

- `data/network/YouTubeStreamResolver` + `CanonicalSeedResolver`
  (canonical identity gate before accepting a CDN stream)
- `data/repository/TrackRepository` (search + library hydration dedup)
- `radio/OnlineRadioEngine` (candidate filtering for radio queues)

When tuning matching behavior, prefer editing `FuzzyTitleMatcher` and
extending its test rather than adding parallel heuristics at call sites.
