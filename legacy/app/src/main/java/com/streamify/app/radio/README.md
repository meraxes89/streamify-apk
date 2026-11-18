# `radio/` — Radio Queue Construction

Endless-radio style queue building from seed tracks.

## Files

| File | What it is |
|---|---|
| `OnlineRadioEngine.kt` | Pure Spotify/YTM radio mixtape construction (autoplay queues). |
| `ContinuumRadioEngine.kt` | Continuation-token radio engine (YTM infinite mixes, `RadioContext`). |
| `UniversalCandidateBroker.kt` | Thin queue-broker facade over OnlineRadioEngine for callers that don't care which radio flavor runs. |
| `Track.videoId` (extension) | Resolves a track's videoId via resolver/coverArt heuristics. |

Candidates come from `data/network/CandidateAggregator`; ranking goes
through `data/discovery/AntiDriftScoringEngine`.
