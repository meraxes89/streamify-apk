# Changelog

All notable changes to Streamify are documented in this file.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased] — WS-B Apple Integration: from showroom to first note (B1–B7, issue #154)

### Added — the Apple mounting bracket (B4, A1 RFC §7 "Apple" row)
- **Rust consumer lease** (`rust/crates/streamify-playback/src/consumer_lease.rs`): the process-wide lease of the
  attached plane's consumer ring, published by `attach_sink` / withdrawn by `detach_sink`, plus the frozen C edge
  (`streamify_ring_read_f32` / `_request_flush` / `_flush_generations` / `_stats_wire` / `_is_attached`) the native
  bracket pulls through. NOT a UDL surface by design (A1 §6.3/§7); unit tests cover the lease lifecycle and the
  C edge over a real ring.
- **`native/audio/apple_ring_mount.cpp`**: the bracket's native half — a control-plane pump bridging the Rust ring
  onto the C++ `AudioSink` in the frozen `SinkRegistry` (canonical 48 kHz stereo, a deliberately small 4096-frame
  bridge ring ≈ 85 ms), with engine-side skip propagation via the generation handshake and the shell's
  seek/flush intents applied at pass boundaries (the frozen protocol's no-in-flight-WriteFloat rule). The audio
  thread stays pure C++ exactly as the RT audit pins it.
- **`Platform/Audio/AppleRingMount.swift`**: the dlsym late binding over the `streamify_apple_mount_*` facade
  (NativeCoreAudioDriver's discipline; clean degrade until the XCFramework links it).
- **`scripts/build_ios_xcframework.sh`** now compiles and links `coreaudio_pull_adapter.cpp` (the
  `streamify_coreaudio_*` facade — previously not in the XCFramework at all) and `apple_ring_mount.cpp` into
  every slice of the framework.

### Added — the feature lane (B1/B3)
- **`UniFFIFeatureLibrary`** (`FeatureLibrary.swift`, behind `#if canImport(streamifyFFI)`): the 14-view-model
  surface over the live engine gateway — `searchTracks` (typed search lane), `applyEqualizerProfile`
  (ten-band `set_equalizer_band`), read-after-action `queueEvents` pushes, `removeQueueItems(all) → clear_queue`,
  and `play(track:)`: resolve (bounded 128-entry cache) → stage (reusing an existing row for the id) →
  `play_track`. Binds the resolver's persistent memo DB under Application Support. Everything without a UDL
  surface keeps the protocol defaults — honest empty states, documented in the PR.
- **`AppServices` lane selection (B1's hard-truth rule)**: `AppServices.engine` is the ONE shared gateway;
  `AppServices.library` follows the ENGINE lane (never splits the seams on a real-lane init failure).
  `PreviewFeatureLibrary` stays strictly the preview/test double. `play(track:)` rides the `FeatureLibrary`
  protocol with a `false` default; the preview double pushes a scripted queue snapshot.
- **`EngineGateway` v2 data-plane surface** (lane-neutral): `attachSink`/`detachSink`/`playTrack`/
  `bufferProgress`/`recordDacClock`/`resolveStreamFormats` + the `EngineStreamFormat` /
  `EngineBufferProgress` mirrors; `UniFFIEngineGateway` maps the generated UDL v2 records (resolve hops off the
  cooperative pool — searchTracks' discipline); the preview gateway keeps the protocol defaults.

### Changed — the shell goes live (B2)
- **`RootShellView`**: owns the 9 formerly-orphaned ViewModels (Home, Search, Queue, Community, StatsWrapped,
  UpdatesHub, UserProfile, Ingestion, Settings) + the new `DownloadsViewModel`; lowers their observable state
  onto the screens' props; queue truth is the ENGINE's staged queue mirrored by `QueueViewModel` (the
  `queueEvents` push lane — read-after-action, never a poll). The play tap routes through
  `model.present(track:)` (optimistic lockscreen/CarPlay metadata — the UDL snapshot carries no track row) +
  `queueModel.play(track:)` (the B3 transaction). Search: the view's 300 ms input settle feeds
  `SearchViewModel.submit`, whose own 300 ms debounce lowers onto `searchTracks` over the live FFI.
- **PreviewCatalog sweep**: every production default-argument/internal read flipped to empty/neutral values
  with fixtures moved into `#Preview` blocks; **`scripts/apple_preview_catalog_lint.py`** enforces
  zero production references (previews/tests/harness exempt) — green in the Apple CI lane.
- **`PlaybackViewModel` (B4/B6)**: the audio plane arms around the transport lifecycle
  (`attachSink → mount → render core → DacClockPublisher`, teardown in exact reverse, paused stays mounted
  for gapless resume); `present(track:)` optimistic admission; `ingest` keeps a presented track when the
  pushed snapshot carries none; the Live Activity rides the admitted push; seek forwards the flush intent to
  the bridge sink.
- **CarPay (B6)**: `sharedRouter`/`sharedContentProvider` wiring (the delegate is system-instantiated from the scene manifest — the shell installs its shared surfaces at app start and `connect` binds them in) + the live `QueueCarPlayContentProvider` (now-playing + up-next rows from the live queue; the Playlists tab stays the honest placeholder until a playlist surface crosses the FFI contract).