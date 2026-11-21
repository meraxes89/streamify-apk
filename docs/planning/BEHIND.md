# BEHIND.md — 27 Spotify Features Streamify Must Ship

> **Intent:** scrap YouTube + Spotify + tech giants and provide an ad-free, bloat-free, free premium APK with all extreme features Spotify and YT Music provide — something out of context like yt-dlp. Music-first daily driver. No paywalls, no ads, no bloat.
>
> **Scope:** this file tracks ONLY the 27 kept gaps. Explicitly scrapped (not tracked here): 1-10 (catalog licenses, geo-rights, Premium/Free/ads/billing/audiobook-hours/creator payouts), 21-24 (AI DJ, AI Playlist, Talk, Claude integration), 29-30 (concerts/tickets/Reserved, Charts/Classics), 33 (Spotify Messages DMs), 46-48 (podcast interactivity, audiobook discovery, articles/Netflix crossovers), 50-51 (podcast creator studio, live-events/merch), 59 (cloud library recovery SLA), 60-67 (kids/managed accounts, explicit/parental engine, abuse/AI-labeling, regulatory posture, public API/SDKs, partner embeds, Spotify Codes platform).
>
> **Method:** every "Missing" below was verified with `rg` over `app/src/main/java` plus `rust/`, `native/`, `supabase/` reads (Sept 2026 tree). False-positive hits are called out (Compose `Canvas` vs Spotify Canvas, `basicMarquee` vs Marquee promo, color `blend` vs Blend, `Yt*` widgets vs OS widgets, `skipped→kids` substring, local `LosslessRemuxer` vs lossless tier).

---

## Jam scale + joining

### 11. 32-person rooms at proven scale
- **Spotify behavior in depth:** Jam supports up to 32 simultaneous participants in one private session. The server fans out event-sourced queue/playback events over a BFF layer, sustains 100M+ monthly listening hours, and keeps ordering consistent under churn (join/leave/rejoin). Host can see the roster, remove members, and the room persists while the host is present.
- **Streamify current missing:** Mesh validated at 5 nodes only (`rust/tests/test_mesh_gossip.rs` — 1000 ops @15% loss, median ≤300ms; `rust/tests/test_p2p_swarmer.rs` — 480KiB, 1 CDN fetch). `p2p_mesh.rs:1664` + `gossip.rs:1207` have no 32-node pressure test. Merkle DAG depth math allows ≤11 rounds @1024 ops (`MerkleStateReconciler.kt:580`) but has never been exercised beyond unit tests (`MerkleStateReconcilerTest`). No room-cap policy, no large-roster UI virtualization (Jam queue renders bounded 8 rows collapsed).
- **Engineer directive (regardless of complexity):** Raise PlumTree fanout/redundancy caps in `gossip.rs`, add 32-node loopback chaos test (loss 0/15/25%, join-storm, host-kill mid-sync), Merkle stress to 1024 ops, `JamEngine` roster cap + degraded-mode policy (tick cadence backoff, delta-only sync past N members), virtualized member/queue lists in `JamSessionScreen/Widgets`.

### 12. Zero-friction join fabric
- **Spotify behavior in depth:** Same-WiFi auto-prompt (open app → "Join Jam" appears, host secretly advertises via router), Bluetooth-LE tap ≤1m (phones together), auto-start Jam on smart/Bluetooth speaker connect, `spotify.link` share with attribution, join from Messages/listening-activity. No typing, no PIN dictation.
- **Streamify current missing:** Manual only — 6-char PIN + `streamify://jam/<PIN>?v=4&s=id&k=64hex` QR/NFC/paste (`JamPairing.kt:135`, `MainActivity` deep-links `streamify://jam`). No NSD/mDNS advertisement, no BLE beacon, no speaker-connect hook, no attributed short link.
- **Engineer directive:** Add LAN advertisement (NSD/mDNS + UDP BEACON `0x09` already in `p2p_mesh` registry — wire to UI prompt), BLE advertiser/scanner for tap-join, `AudioDeviceManager` speaker-connect listener that offers host/join, short-link share intent with referrer attribution. Keep offline QR/NFC as fallback.

### 13. Single-speaker party mode
- **Spotify behavior in depth:** In-person Jam = ONE renderer (host phone/speaker). Everyone else is a remote control adding/ordering/voting. This saves battery, avoids multi-room echo, and matches the party reality (one aux).
- **Streamify current missing:** Every device renders phase-locked audio (`SyncAudioProcessor` + resampler + Kalman per device). No single-renderer topology exists in `JamEngine` (invariants `JamEngine.kt:57-72` assume N lockstep players).
- **Engineer directive:** Add `JamEngine.Topology { MULTI_RENDER, SINGLE_RENDER }`. In SINGLE_RENDER: host `PlaybackService` owns `ExoPlayer`, guests suppress local render (keep silent PLL for instant handoff), all transport intents route to host; `tickIntervalMs` stays authoritative. UI toggle in Jam screen + per-guest "listen on host" affordance.

### 14. Host governance
- **Spotify behavior in depth:** Host admits/removes members, reorders/removes anything, sees who-added attribution enforced server-side, and toggles `Let others change what's playing` + `Let guests change volume` independently.
- **Streamify current missing:** Only `ControlPolicy HOST_ONLY/EVERYONE` (`JamEngine.setPolicy` → `JamWire.Msg.POLICY`). No kick/block/co-host, no per-action ACLs. `addedByMap` (`JamEngine.kt:181`) is advisory only.
- **Engineer directive:** Member ACL (`allowControl`, `allowVolume`, `banned`) carried in `PRESENCE`/`POLICY` frames, enforced at intent ingress (drop + epoch-gated). Add kick (`SESSION_END`-targeted) and block list persisted per room. Co-host = secondary nonce allowed to emit leader intents (epoch-fenced, see P11 roadmap).

### 15. Group-taste Blend engine
- **Spotify behavior in depth:** Blend merges 2-10 users' taste vectors into a joint playlist + "others like this" badges on adds, powered by the same CF stack as Daily Mix. It is the social discovery hook inside Jam.
- **Streamify current missing:** Zero group recommendations. Solo engines exist (`continuum_engine.rs`, `neuro_queue.rs`, `markov.rs`, `radio/UniversalCandidateBroker`) but are never fed a multi-user taste union inside Jam.
- **Engineer directive:** Build group-taste union over members' `ChronosProfiler`/history embeddings, score via `radio_scorer.rs` + `queue_optimizer.rs`, surface "X others like this" in queue rows and a Blend shelf in the Jam screen. Reuse `VirtualShelfTrack` + `scoreAndRankRadioCandidates` FFI.

### 16. Per-route volume ACLs
- **Spotify behavior in depth:** Shared volume only where safe (Chromecast/Amazon Cast); locked on Bluetooth/AirPlay where OS owns gain. Host toggles sharing per session.
- **Streamify current missing:** No output-aware volume policy. Volume is local device gain only.
- **Engineer directive:** Classify routes in `AudioDeviceManager` (SPEAKER/BT/A2DP_LE/CAST/WIRED), add `allowGuestVolume` to `ControlPolicy`, enforce per-route (ignore guest volume intents on BT/AirPlay-equivalent paths), reflect lock state in Jam Widgets gauge.

### 17. Remote-vs-in-person switch
- **Spotify behavior in depth:** Join flow asks "listen remotely (own speaker, Premium) or in-person (host speaker)". Remote guests get parallel audio; in-person guests are controllers.
- **Streamify current missing:** Single mesh-sync mode. No per-guest render choice.
- **Engineer directive:** Per-guest `listenMode { SYNCED, REMOTE_SOLO }`. REMOTE_SOLO keeps queue/transport sync but renders locally without PLL discipline (or with relaxed bands); SYNCED keeps full Kalman+resampler lock. Persist per member, show icon in roster.

### 18. In-room moderation
- **Spotify behavior in depth:** Host remove, report abuse, session ends on host leave (crude but present server moderation).
- **Streamify current missing:** Zero report/griefing controls. Hostile frames are rejected at codec (`JamWire` boundary checks) but hostile *users* cannot be reported/removed.
- **Engineer directive:** Report frame (target nonce + reason) → host action (warn/kick/block). Kick = targeted `LEAVE` + block-list entry; block persists for room lifetime. Rate-limit reports per member.

### 19. Listening Activity / Request-to-Jam presence
- **Spotify behavior in depth (2026):** See when friends listen, join live, request-to-jam, message in sync about what's playing/up-next.
- **Streamify current missing:** Presence = mesh radar (`peerRadar`, `MeshPeer` link/rtt/freshness) only. No cross-room activity feed, no join-request flow.
- **Engineer directive:** Promote `peerRadar` + Supabase presence (`participant_last_seen`) to an Activity row (who's in which room, what track/position). Add request-to-join intent (guest → host approve) reusing `STATE_REQ`/`CONTINUATION` handshake.

### 20. Daylist
- **Spotify behavior in depth:** Hyper-personal playlist that mutates through the day (morning focus → night energy), regenerated from taste + time-of-day, highly shared.
- **Streamify current missing:** Zero daypart engine. `ChronosProfiler` has 4-slot circadian vectors but no Daylist surface.
- **Engineer directive:** Daypart scheduler over `ChronosProfiler` + `ContinuumRadioEngine`, Daylist shelf in Home with time-bucketed refresh, share card via `TrackShareCard` pattern.

---

## Discovery / taste (non-AI core)

### 25. Blend (multi-user taste merge)
- **Spotify behavior in depth:** 2+ users merge taste into one playlist, refreshes daily, shareable, drives invites. 45M+ Blends created.
- **Streamify current missing:** `blend` hits are color-blend code. No taste-overlap math exists.
- **Engineer directive:** Pairwise/group overlap over `VectorStore` 128-D + history co-occurrence (`track_cooccurrence_graph`), Blend shelf + invite flow, daily refresh worker (`LibrarySyncWorker` pattern).

### 26. Weekly / Radar / Mixes / Rewind / Capsule / Smart Reorder
- **Spotify behavior in depth:** Discover Weekly (Wed), Release Radar (Fri), Daily Mix 1-6, On Repeat, Repeat Rewind, Time Capsule, Smart Reorder (transition-aware), Prompted/Personal episode lists. Scheduled, cached offline, the core retention loop.
- **Streamify current missing:** Names faked as local pseudo-shelves (`FeedBootstrapManager: spt_mix_1 "Daily Mix 1", spt_discover "Discover Weekly"`) built from ingested tracks. No CF pipeline, no release-graph, no cadence.
- **Engineer directive:** Build schedulers over `TrackRepository` + Supabase telemetry/history (`user_track_plays`, `user_play_events`), novelty/CF scorer in `ReRanker`/`AntiDriftScoringEngine`, pre-resolve + pre-buffer heads (`PredictivePreBufferManager`), notify on new drops.

### 27. Taste Profile controls
- **Spotify behavior in depth:** Exclude playlist/track from taste, hide/unhide songs, toggle social recommendations — user steers the algorithm.
- **Streamify current missing:** Zero. Telemetry ingests everything (`YtStatsTelemetryEngine`).
- **Engineer directive:** `excludedFromTaste` flags on playlists/tracks in `TrackRepositoryApi` + Supabase, filter at ingest/score time, toggles in `SettingsScreen` + playlist context menu.

### 28. Pre-saves
- **Spotify behavior in depth:** Follow unreleased album → push on drop. Key artist-growth loop.
- **Streamify current missing:** Zero presave object.
- **Engineer directive:** Presave watcher (poll `YouTubeMusicSearchApi`/artist feed for release ID), store in `PlaylistRepository`-adjacent table, notify via updater-notification channel pattern, auto-add on release.

---

## Social graph

### 31. Follow graph
- **Spotify behavior in depth:** Follow artists/podcasts/shows, follower counts, profile publishing with image/playlist guidelines.
- **Streamify current missing:** Profiles are local + Supabase `profiles` rows; no follow edges at scale.
- **Engineer directive:** `follows` table (follower→artist/user), follow buttons on `ArtistScreen`/`UserProfileScreen`, follower counts, profile publish flags.

### 32. Friend Activity feed
- **Spotify behavior in depth:** Live desktop/mobile feed of what friends play, click-to-listen/join.
- **Streamify current missing:** One README line + `SupabaseJamClient` comment ("COMMUNITY PLAYLISTS & FRIEND ACTIVITY"). No live feed.
- **Engineer directive:** Aggregator over `user_listening_history` + presence → `CommunityHubScreen`/`HomeViewModel` feed with play/join actions.

### 34. Comments at scale
- **Spotify behavior in depth:** Timestamped track + podcast comments, likes, creator replies, moderation queue, admin delete.
- **Streamify current missing:** Flat `track_comments` + `CommunityHubScreen` basics exist; no threading, no report queue, no ranking.
- **Engineer directive:** Threaded replies, like-rank, report flag → admin queue (`AdminDashboardScreen` already exists — extend), creator-reply badge.

### 35. Collaborative playlists
- **Spotify behavior in depth:** Invite collaborators with add/reorder/remove permissions, pending invites, activity log.
- **Streamify current missing:** `is_collaborative` + `collaborator_ids` fields exist in `playlists` schema; no invite/role flow in app.
- **Engineer directive:** Invite intents (link/PIN like Jam), role enum (viewer/editor/admin) enforced in `PlaylistRepository` + `SupabasePlaylistSyncClient`, activity log.

### 36. Share surfaces
- **Spotify behavior in depth:** Spotify Codes (camera-scan), TikTok/IG/Discord/SharePlay embeds, in-playlist social recs.
- **Streamify current missing:** Local `streamify://` links + `TrackShareCard` only.
- **Engineer directive:** Share-sheet with QR Code card (reuse `zxing` from Jam), system share intents, rich embeds (cover + deep-link), social-recs row in playlists.

### 37. Group voting
- **Spotify behavior in depth (expected, per Spotify "we may add voting"):** Vote tracks up the queue democratically.
- **Streamify current missing:** `OpType.Vote=4` reserved in `jam_crdt.rs`, never merged by `apply_op`.
- **Engineer directive:** Implement `Vote` merge (count per `target_add_op_id`, host threshold promotes), proposed-vs-committed split queue (roadmap P10), vote UI in queue rows.

---

## Audio + offline

### 38. Quality ladder
- **Spotify behavior in depth:** Ogg 96/160/320 + AAC, per-network (WiFi/cellular) quality, Data Saver, download quality picker, storage accounting.
- **Streamify current missing:** `lossless` = local remux of YT Opus/AAC (`LosslessRemuxer`, `DownloadWorker` "Saved Lossless Track" toast) — not a licensed ladder or adaptive policy.
- **Engineer directive:** Source selector (Opus251 vs AAC140 via `extract_best_stream_info` in `json.rs`), per-network default in `NetworkEngine`, quality picker + storage meter in `SettingsScreen`/`StorageManager`.

### 39. Normalize / gapless / Automix toggles
- **Spotify behavior in depth:** Loudness normalization on/off, gapless on/off, crossfade 0-12s, Automix transition EQ.
- **Streamify current missing:** DSP chain exists (`LufsNormalizer -14`, `SoftKneeLimiter`, `CrossfadeAudioProcessor`, `PredictivePreBufferManager` for "gapless transitions" per README) but no calibrated user toggles.
- **Engineer directive:** Expose normalize (target LUFS), gapless, crossfade duration (exists as `crossfade_val` pref — surface properly), Automix reorder (`AntiJarringTransitionEngine` + Smart Reorder hook).

### 40. Shuffle intelligence
- **Spotify behavior in depth:** Smart Shuffle (adds recommendations inline), reshuffle button, bulk queue multiselect.
- **Streamify current missing:** Shuffle/repeat booleans only (`PlayerViewModel.toggleShuffle/Repeat`). Zero smart layer.
- **Engineer directive:** Smart-shuffle injector in `QueueEngine`/`DynamicQueueManager` (interleave `CandidateAggregator` picks, marked), reshuffle button, bulk-select in `QueueScreen`.

### 41. Mono / balance / limiter / explicit / private
- **Spotify behavior in depth:** Mono mix, L/R balance, volume limiter (EU), explicit-content filter, Private Session (no history/taste pollution).
- **Streamify current missing:** `Explicit` is a theme color; `SearchViewModel.isExplicit=false` hardcoded stub. No mono/balance/limiter/private flags.
- **Engineer directive:** Mono downmix + balance in `EqualizerManager`/`StreamifyAudioProcessor` path, limiter ceiling, explicit-filter at resolve/search time, private-session flag that mutes `YtStatsTelemetryEngine` + history writes.

### 42. Hands-free + utilities
- **Spotify behavior in depth:** Voice commands ("play my workout"), Maps/Waze embedded player, lock/home widgets, shortcuts, alarm/wake timer.
- **Streamify current missing:** Zero (19 `widget` hits are `Yt*` UI kit). Sleep timer exists (`PlayerModels.sleepTimerMinutesLeft`, end-of-track); wake/alarm does not.
- **Engineer directive:** Voice intents (media-session actions), Maps/Waze hooks deferred, OS widgets (now-playing + Jam join), keyboard/shortcut layer, alarm timer mirroring sleep-timer FSM.

### 43. Local-files two-way sync
- **Spotify behavior in depth:** Desktop local files → phone over LAN, unified library.
- **Streamify current missing:** `MediaStoreScanner` + JSON import one-way only.
- **Engineer directive:** Sync local-file index via `SupabasePlaylistSyncClient` pattern + LAN transfer (reuse swarm `chunk_swarmer` for bytes), unified `TrackRepository` source flag.

### 44. Background downloads
- **Spotify behavior in depth (2026):** iOS background downloads with progress notifications; ready offline on flight/underground.
- **Streamify current missing:** Foreground `DownloadWorker` only.
- **Engineer directive:** Promote `DownloadWorker`/`IngestionWorker` to background-capable (WorkManager constraints + notification progress), retry + resume via `ParallelStreamDownloader` byte-range support.

---

## Artist surface (kept)

### 45. Music videos + Clips + Canvas
- **Spotify behavior in depth:** Full music videos, 30s artist Clips attached to profiles/tracks/albums (persistent, not stories), Canvas 8s loops on Now Playing, vertical discovery feed (2023 Stream On redesign).
- **Streamify current missing:** `Canvas` hits are Compose draw API (`LyricsCanvas`, `YtSyllableLine`). No video track layer.
- **Engineer directive:** ExoPlayer video track in `FullPlayerSheet`/Artist/Album screens, Clips rail (30s vertical, attached to `Track` IDs), Canvas loop on Now Playing (reuse AGSL/ambient-glow infra), pre-buffer policy extension.

### 49. Artist promo stack (consumer side)
- **Spotify behavior in depth:** Countdown Pages, Clips, Canvas, Campaign Kit, New Release Guide — fans see timers, trailers, presave, merch on profiles.
- **Streamify current missing:** Zero consumer promo surface.
- **Engineer directive:** Promo cards on `ArtistScreen` (countdown timer + trailer + presave button wiring to #28), new-release rail in Home.

---

## Devices (kept all — biggest effort)

### 52. Spotify Connect
- **Spotify behavior in depth:** Cloud-direct WiFi handoff — phone is a remote, speaker streams from cloud, multiroom, no battery drain, works across 2000+ devices since 2013.
- **Streamify current missing:** "Spotify connected!" = OAuth ingest toast (`MainActivity`, `ConnectAccountsSheet`). No cloud queue handoff exists.
- **Engineer directive:** Promote existing `SupabaseClient.remotePlaybackState` to full Connect: cloud queue + transport intents, device registry, sender/receiver roles. This unlocks 53-56.

### 53. Cast / AirPlay / Sonos / TV / consoles
- **Spotify behavior in depth:** One-tap output to Chromecast built-in, AirPlay, Sonos, TVs, consoles, tablets/foldables/ChromeOS.
- **Streamify current missing:** All zero (`Chromecast/AirPlay => 0`). Only local `AudioTrack` + BT routing (`AudioDeviceManager`).
- **Engineer directive:** Per-route `media/sync` sinks + `AudioDeviceManager` route classes; Cast SDK + AirPlay delegation; TV/console modules as separate playback targets.

### 54. Car
- **Spotify behavior in depth:** Android Auto (safety UI + wheel controls + voice), CarPlay, Tesla native, head-unit apps.
- **Streamify current missing:** Zero (`Android Auto/CarPlay => 0`).
- **Engineer directive:** Auto + CarPlay modules (media-browser service already in manifest — extend `PlaybackService` to Auto/CarPlay templates), simplified Jam-join voice flow for car.

### 55. Wearables
- **Spotify behavior in depth:** Watch/WearOS/Garmin/Fitbit/Samsung apps + offline-to-watch for runs.
- **Streamify current missing:** Zero (`Wear OS => 0`).
- **Engineer directive:** Companion watch module (transport + offline cache subset via `ElasticStorageAllocator` policy), Jam companion (vote/add from wrist).

### 56. Voice assistants
- **Spotify behavior in depth:** Alexa/Google/Siri intents, Claude Connect device switching without leaving chat.
- **Streamify current missing:** Zero.
- **Engineer directive:** Media-session voice actions + assistant app-actions (play/search/join-jam), device-switch intent bridging to #52.

---

## Library power

### 57. Folders / pins / covers / bulk
- **Spotify behavior in depth (2026):** Playlist folders on mobile, pin playlists, custom covers, in-playlist bulk actions (multi-track/book/episode edit), queue bulk manage (Premium).
- **Streamify current missing:** All zero (`playlist folder/pin-custom/bulk => 0`). Fractional ordering exists for Jam queue but not library folders.
- **Engineer directive:** Folder entity in `PlaylistRepository` + Supabase (`playlists` parent column), pin flag, cover picker (reuse artwork pipeline `iTunesSearchApi` + Palette), bulk-select in Library/Playlist detail, bulk queue ops in `QueueScreen`.

### 58. Sort / filter / Updates / Recent
- **Spotify behavior in depth:** Library sort/filter, Your Updates hub (new releases from follows), Recent activity, queue reshuffle.
- **Streamify current missing:** Queue screen only; no library-grade sort/filter, no Updates hub.
- **Engineer directive:** Sort/filter bar in `LibraryScreen`, Updates hub fed by follows (#31) + presaves (#28), Recent-activity rail from `user_listening_history`.

---

## Appendix — patterns in what was stripped (why)

- **Stripped 1-10 (licenses + money):** intent is product parity first, funding/compliance later. Do not re-litigate licensing in this file.
- **Stripped 21-24 (AI hype):** Daylist/Blend/Weekly kept as proven retention; generative DJ/Playlist/Talk/Claude cut as demo-grade.
- **Stripped 29,30,33,46-48,50,51 (events/charts/DMs/podcast-audiobook/creator-B2B):** music-only consumer scope; videos/Clips kept because they live on the track surface.
- **Stripped 59,60-67 (backup-SLA/kids/compliance/public-API):** no B2B, no parental, no dev-platform; in-room safety (#18) kept, platform safety cut.
- **Net thesis:** win daily listening (Jam + discovery + library + audio + everywhere-playback) before monetization, AI, non-music, supply-side, or compliance.
