# `data/network/` — Stream Resolution & Provider Engines

The heart of Streamify: how a `Track` becomes a playable CDN URL, and
how candidates are found in the first place. Deep-dive docs:
`docs/01_STREAM_RESOLUTION_ENGINE.md` and
`docs/MASTER-STREAM-RESOLUTION.md`.

## Files by responsibility

**Resolution & delivery**
| File | What it is |
|---|---|
| `YouTubeStreamResolver.kt` | The resolver: videoId extraction, CDN URL decoding, expiry detection (`isCdnExpired`). |
| `ResilientMediaRouter.kt` | Tiered routing: fast HTTP/2 path first, silent fallbacks after. |
| `ParallelStreamDownloader.kt` | Segmented parallel range-downloader with SHA-256 verification. |
| `NegativeResultCache.kt` | Remembers dead ends so repeat failures are instant. |
| `StreamifyCircuitBreaker.kt` | Bounded (500-entry) breaker that stops retry storms. |
| `NetworkEngine.kt` | Connection warming + shared stream cache. |

**Candidate discovery**
| File | What it is |
|---|---|
| `CandidateAggregator.kt` | Fans out to ~150-200 candidates across providers in <50ms. |
| `CanonicalSeedResolver.kt` | Canonical identity gate for seeds (CAD-ID aware). |
| `HybridGraphFetcher.kt` | Spotify/YT graph similarity (`LastfmSimilarTrack`). |
| `MeshDiscoveryEngine.kt` | LAN peer discovery for Edge-Mesh sessions. |

**Search providers**
| File | What it is |
|---|---|
| `YouTubeMusicSearchApi.kt` | InnerTube search + filters. |
| `iTunesSearchApi.kt` | iTunes catalog search. |
| `SemanticSearchEngine.kt` | Natural-language "vibe" search via embeddings. |

**AI providers**
| File | What it is |
|---|---|
| `ZhipuAiEngine.kt` | GLM-4-Flash chat completions; key pool + endpoint are env/build injected (SEC-08), no embedded credentials — unconfigured builds degrade to AI-off. |
| `SmartAcousticEngine.kt` | AI mastering EQ curves. |
| `PersonaEngine.kt` | Circadian listening-persona analysis. |

**Sequencing**
| File | What it is |
|---|---|
| `AntiJarringTransitionEngine.kt` | Blocks tempo-cliff/energy-shock transitions. |
| `ArtworkResolutionPipeline.kt` | Artwork resolution + explicit decode sizing. |

## Rules

- This package must stay transport-only: no UI types, no persistence.
- New providers go here **only** if they are source-agnostic; provider-
  specific sessions (Spotify OAuth, YTM cookies) live in `data/spotify/`
  and `data/youtube/`.
