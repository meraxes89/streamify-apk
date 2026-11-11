import Foundation
import Observation

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG4-01 / SW-ENG4-02 (Engineer 4): the playback transport state holder —
// the `@Observable @MainActor` successor of the Phase 5 `ObservableObject`
// scaffold (SW-DEC-02: no Combine, no @Published, no AnyCancellable bags).
//
// PUSH EVENT-DRIVEN STATE (MP-DEC-04): the holder consumes the gateway's
// snapshot stream — every snapshot carries a strictly monotonic `rev`, and
// stale revisions are DROPPED at this boundary, so out-of-order pushes (a
// burst coalesced across the FFI) can never rewind the UI. User intent goes
// out as serialized fire-and-forget commands; the engine's next push repaints
// whatever the command changed.
//
// 120 Hz PROGRESS (SW-ENG4-02): the fine-grained playhead lives in the
// SEPARATE `PlaybackProgressClock` observable (hardware DAC interpolation),
// so display-synced per-frame reads invalidate only scrub-bar leaves. This
// holder itself changes only when a pushed snapshot actually changes state.
//
// SW-ENG3 INTEGRATION (Engineer 3, merged on top): the transport controls
// are the MP-DEC-07 hardware-debounced `RemoteCommandCenterAdapter`
// (50-150 ms envelope — the ENG4 draft referenced an undefined
// `RemoteCommandAdapter`; this merge binds the real adapter), the widget /
// intent mailbox plane (`WidgetPlaybackRefresher`, SW-ENG3-06) mirrors
// every admitted push, and the like/dislike feedback intent surfaces as
// `lastFeedback` (SW-ENG3-03 — shell-level concern, not engine transport).
//
// WS-B B3/B4/B6 (Engineer 2): the CoreAudio data plane mounts HERE — the
// render core pulls PCM straight from the engine's exported Rust SPSC ring
// (`playback_ring()`'s consumer edge — NO interim AVPlayer, the engine
// dictates real-time alignment through the physical DAC frames), the 10 Hz
// `DacClockPublisher` drain mirrors hardware presentation timestamps into
// `record_dac_clock`, and the lock-screen Live Activity rides the same
// admitted pushes (1 Hz fingerprint policy inside the controller).
// ─────────────────────────────────────────────────────────────────────────────

@Observable
@MainActor
final class PlaybackViewModel: AudioSessionResponding, RemoteCommandRouting {

    // ── Push-fed transport state (snapshots only — never a getter poll) ────

    private(set) var status: PlaybackStatus = .idle
    private(set) var volume: Double = 0
    private(set) var buffer: BufferHealth?
    private(set) var currentTrack: PresentationTrack?
    private(set) var jamSessionName: String?
    private(set) var lastResult: PlayerCommandResult?

    /// SW-ENG3-03: the last system-surface feedback intent (like/dislike),
    /// surfaced for the shell to render (heart animation, library curation)
    /// — feedback is a shell-level concern, not engine transport.
    private(set) var lastFeedback: RemoteFeedback?

    /// The last raw hardware DAC sample pushed by the engine (diagnostics).
    private(set) var lastDacSample: DacClockSample?

    /// The highest engine revision ingested so far (stale drops happen below).
    private(set) var lastRev = 0

    /// The hardware-anchored interpolator feeding the scrub bar (SW-ENG4-02).
    let progress = PlaybackProgressClock()

    // ── Wiring (identical shape to the Phase 5 scaffold) ────────────────────

    let gateway: EngineGateway
    private(set) var audioSession: IOSAudioSessionAdapter!

    /// MP-UI01: the lockscreen / now-playing surface, rate-limited to 1 Hz
    /// (leading-edge admission + trailing-edge flush).
    private let nowPlayingInfo: NowPlayingInfoPublisher

    /// SW-ENG3-03 / MP-DEC-07: the lockscreen / Control Center / headset /
    /// CarPlay transport controls, hardware-debounced into the approved
    /// 50-150 ms envelope and routed onto the same command plane as the
    /// in-app UI.
    private(set) var remoteCommands: RemoteCommandCenterAdapter!

    /// SW-ENG3-06: the widget plane mirror — the snapshot store refresh +
    /// the intent mailbox drain, both riding the engine push cadence (the
    /// 1 Hz collect loop: exactly the cadence the mailbox was designed for).
    private let widgetRefresher: WidgetPlaybackRefresher?

    // ── WS-B B4: the CoreAudio data plane (the Apple sink mount) ─────────

    /// The pull-adapter facade: native when the bracket is linked,
    /// simulated for previews/tests. Mounted lazily on the first play.
    let renderCore: CoreAudioRenderCore

    /// The 10 Hz hardware-clock drain (started with the render core; each
    /// drained sample mirrors into `record_dac_clock` — hardware → engine,
    /// never the reverse, MP-DEC-03).
    private var dacClockPublisher: DacClockPublisher?

    /// Diagnostics: the render core's latest cumulative RT counters.
    private(set) var lastCoreAudioTelemetry: CoreAudioTelemetry?

    /// Diagnostics: the attached playback plane's latest progress read
    /// (`buffer_progress` — ring occupancy, underruns, rendered position).
    private(set) var lastBufferProgress: EngineBufferProgress?

    // ── WS-B B6: the lock-screen Live Activity ───────────────────────────

    #if canImport(ActivityKit) && canImport(UIKit)
    /// Driven from the same admitted pushes as the now-playing surface
    /// (the controller's own 1 Hz fingerprint admission does the gating).
    private var liveActivityController: PlaybackLiveActivityController?
    #endif

    private var collectTask: Task<Void, Never>?
    private var dispatchChain: Task<Void, Never>?

    // MARK: - Init (two-phase: properties before self references)

    init(
        gateway: EngineGateway,
        audioSession: IOSAudioSessionAdapter? = nil,
        // Optional-with-nil-default instead of a `NowPlayingInfoPublisher()`
        // default expression: default arguments are evaluated in a
        // NONISOLATED context, so a @MainActor-isolated initializer call
        // there is a compile error. The fallback constructs on the main
        // actor, inside this isolated init body.
        nowPlayingInfoPublisher: NowPlayingInfoPublisher? = nil,
        // SW-ENG3-03: the MP-DEC-07 debounce window for the system
        // transport controls (clamped into the approved envelope by the
        // adapter itself).
        remoteCommandWindowNs: UInt64 = MediaCommandDebouncePolicy.defaultWindowNs,
        // SW-ENG3-06: the widget plane mirror (nil disables the cross-
        // process plane — single-process / test mode).
        widgetRefresher: WidgetPlaybackRefresher? = nil,
        // WS-B B4: the CoreAudio driver seam (tests inject the simulated
        // VirtualHAL twin; the app gets the native pull adapter).
        coreAudioDriver: CoreAudioDriver? = nil
    ) {
        self.gateway = gateway
        self.nowPlayingInfo = nowPlayingInfoPublisher ?? NowPlayingInfoPublisher()
        self.widgetRefresher = widgetRefresher ?? WidgetPlaybackRefresher()
        self.renderCore = CoreAudioRenderCore(driver: coreAudioDriver ?? NativeCoreAudioDriver())
        // Two-phase initialization: every remaining stored property is a
        // var-optional (implicitly nil-initialized) or already assigned, so
        // `self` is FULLY initialized by the time the adapters below capture
        // it (a `let` optional such as widgetRefresher has no implicit nil —
        // it must be assigned BEFORE the first `self` escape).
        self.audioSession = audioSession ?? IOSAudioSessionAdapter(responder: self)
        self.remoteCommands = RemoteCommandCenterAdapter(
            router: self,
            windowNs: remoteCommandWindowNs
        )
        #if canImport(ActivityKit) && canImport(UIKit)
        liveActivityController = PlaybackLiveActivityController()
        #endif
    }

    // MARK: - Snapshot ingestion (rev-guarded push consumer)

    /// Cold-start hydration + push-flow collection. Idempotent.
    func start() {
        guard collectTask == nil else { return }
        remoteCommands.activate()
        do {
            try audioSession.activate()
        } catch {
            // Session activation failure is survivable: playback continues
            // (AVAudioSession re-arms on the next route change); the shell
            // keeps rendering engine state either way.
        }
        collectTask = Task { [weak self] in
            guard let self else { return }
            if let seeded = await self.gateway.currentSnapshot() {
                self.ingest(seeded)
            }
            // The VM is app-lifetime (root environment); holding self for
            // the stream's duration is intended — the loop ends when the
            // gateway's snapshot stream finishes.
            for await snapshot in self.gateway.snapshots {
                self.ingest(snapshot)
            }
        }
    }

    /// Clean teardown (stops collection, tears the lockscreen surfaces down,
    /// deactivates the audio session, releases the gateway bridge).
    func stop() {
        collectTask?.cancel()
        collectTask = nil
        dispatchChain?.cancel()
        dispatchChain = nil
        remoteCommands.deactivate()
        nowPlayingInfo.clear()
        #if canImport(ActivityKit) && canImport(UIKit)
        let activityToEnd = liveActivityController
        if let activityToEnd {
            Task { await activityToEnd.end() }
        }
        #endif
        // WS-B B4: ordered teardown — stop the clock drain and retract the
        // render core BEFORE the plane detaches (a late in-flight render
        // degrades to digital silence, never a use-after-free), then tear
        // the playback plane down prompt (the A5 skip-under-load posture).
        dacClockPublisher?.stop()
        dacClockPublisher = nil
        renderCore.stop()
        lastCoreAudioTelemetry = nil
        let gateway = self.gateway
        Task { await gateway.detachSink() }
        audioSession.deactivate()
        gateway.shutdown()
        progress.reset()
        status = .idle
        volume = 0
        buffer = nil
        currentTrack = nil
        jamSessionName = nil
        lastDacSample = nil
        lastBufferProgress = nil
        lastRev = 0
    }

    /// One pushed snapshot applied atomically on the main actor — the single
    /// mutation point, which is what makes the transition race-free: bursts
    /// are serialized here in rev order, and anything out of order dies.
    private func ingest(_ snapshot: PlaybackSnapshot) {
        // Monotonic revision guard: a stale (or equal, re-delivered) push
        // can never repaint the UI — MP-DEC-04's `rev: u64` discipline.
        guard snapshot.rev > lastRev || lastRev == 0 else { return }
        let trackChanged = snapshot.currentTrack?.id != currentTrack?.id
        lastRev = snapshot.rev

        status = snapshot.status
        volume = snapshot.volume
        buffer = snapshot.buffer
        jamSessionName = snapshot.jamSessionName
        if snapshot.currentTrack != nil || trackChanged {
            currentTrack = snapshot.currentTrack
        }
        if trackChanged {
            progress.reset()
        }

        // The hardware clock anchor swap + optimistic-seek unlatch.
        lastDacSample = snapshot.clock
        progress.ingest(
            snapshot.clock,
            isPlaying: snapshot.status == .playing,
            durationMs: snapshot.currentTrack?.durationMs,
            hostUptimeNs: Self.hostUptimeNs
        )

        nowPlayingInfo.publish(state: state)
        updateLiveActivity()
        // SW-ENG3-06: mirror the admitted push into the widget plane
        // (fingerprint-gated store refresh + intent-mailbox drain — the
        // same push cadence the mailbox was designed for).
        mirrorWidgetPlane()
    }

    /// SW-ENG3-06: the widget plane mirror — refreshes the cross-process
    /// snapshot store (zero I/O on unchanged rendered values) and drains
    /// the widget / intent / menu-bar command mailbox onto the shared
    /// command plane. Fire-and-forget on the main actor: a push is already
    /// an async boundary, and the store's fingerprint gate absorbs bursts.
    private func mirrorWidgetPlane() {
        guard let refresher = widgetRefresher else { return }
        let uiState = state
        Task { @MainActor [weak self] in
            await refresher.refresh(state: uiState)
            refresher.drainCommands { [weak self] command in
                self?.remoteCommandReceived(command)
            }
        }
    }

    /// Host monotonic uptime in ns (the DAC interpolation time base; matches
    /// the engine's CLOCK_MONOTONIC epoch on Apple — ns since boot).
    static var hostUptimeNs: Int {
        Int(DispatchTime.now().uptimeNanoseconds)
    }

    // MARK: - Legacy projection (compat with the Phase 5 views/publishers)

    /// The flat `PlaybackUiState` projection (kept for the now-playing
    /// publisher and the scaffold's views; new views read the individual
    /// observable properties for fine-grained dependency tracking).
    var state: PlaybackUiState {
        PlaybackUiState(
            PlaybackSnapshot(
                rev: lastRev,
                status: status,
                volume: volume,
                buffer: buffer,
                clock: lastDacSample,
                currentTrack: currentTrack,
                jamSessionName: jamSessionName
            )
        )
    }

    // MARK: - Intent (fire-and-forget; the next push repaints)

    func togglePlayPause() { dispatch(.togglePlayPause) }
    func next() { dispatch(.next) }
    func previous() { dispatch(.previous) }

    // MARK: - WS-B B3: the full play path (Home tap → audible track)

    /// Plays one track end-to-end: mounts the CoreAudio plane (the render
    /// core pulls straight from the engine's exported Rust ring — no
    /// AVPlayer), waits bounded for the first hardware DAC clock sample
    /// (the A5 ordering: position truth anchors at the first presented
    /// frame, BEFORE the play intent), then lowers
    /// resolve → stage → attach → play intent onto the gateway. The next
    /// engine push repaints metadata, transport and progress.
    ///
    /// `resolvedStreamUrl` short-circuits the resolve step for callers that
    /// already hold a playable source (WS-B B7's offline lane passes a
    /// `file://` URL the engine's fetcher reads straight off disk).
    func play(track: PresentationTrack, resolvedStreamUrl: String? = nil) {
        // Optimistic now-playing metadata (the enriched push confirms;
        // reverted below when the lane refuses the row and nothing plays).
        currentTrack = track
        let engineTrack = EngineTrack(
            id: track.id,
            title: track.title,
            artist: track.artist,
            album: "",
            durationMs: track.durationMs ?? 0,
            thumbnailUrl: track.artworkUrl,
            resolvedStreamUrl: resolvedStreamUrl
        )
        Task { [weak self] in
            guard let self else { return }
            _ = self.mountCoreAudioPlane()
            await self.awaitDacClockAnchor()
            let staged = await self.gateway.play(track: engineTrack)
            if staged == nil, self.status != .playing {
                // The lane refused the row (resolve failure surfaces as a
                // silent nil — B3's degrade discipline) and nothing else is
                // playing: retract the optimistic metadata.
                self.currentTrack = nil
            }
            self.refreshPlaneDiagnostics()
        }
    }

    /// Plays a row already staged in the engine's queue (a queue-list tap:
    /// no re-resolve, no duplicate row — the engine selects the row and
    /// emits the intent itself).
    func playStaged(trackId: String) {
        Task { [weak self] in
            guard let self else { return }
            _ = self.mountCoreAudioPlane()
            await self.awaitDacClockAnchor()
            _ = await self.gateway.playStagedTrack(id: trackId)
            self.refreshPlaneDiagnostics()
        }
    }

    /// Mounts the Apple sink onto the engine's playback plane (WS-B B4).
    /// Idempotent; a refused mount (no native facade, no audio route, ring
    /// not yet exported) degrades to transport-only playback — never a
    /// crash, never a block. The 10 Hz `DacClockPublisher` drain starts
    /// with the core and mirrors every hardware presentation timestamp
    /// into `record_dac_clock` (hardware → engine, never the reverse).
    @discardableResult
    private func mountCoreAudioPlane() -> Bool {
        guard !renderCore.isRunning else { return true }
        let status = renderCore.startRustRing()
        guard status == .started else { return false }
        let gateway = self.gateway
        let publisher = DacClockPublisher(
            driver: renderCore.driver,
            onSample: { sample in
                Task { await gateway.recordDacClock(sample) }
            }
        )
        publisher.start()
        dacClockPublisher = publisher
        return true
    }

    /// Bounded wait for the first hardware DAC clock sample: the engine
    /// anchors rendered position at intent-emit time ONLY when a clock
    /// sample already exists, so the HAL must be alive before the first
    /// track (the A5 self-test's exact ordering, minus the synthetic
    /// prime — on hardware the anchor must be REAL). Skipped entirely
    /// when the render core is not running (preview lane / refused mount).
    private func awaitDacClockAnchor() async {
        guard renderCore.isRunning else { return }
        let deadlineNs = DispatchTime.now().uptimeNanoseconds + 750_000_000
        while DispatchTime.now().uptimeNanoseconds < deadlineNs {
            if let sample = renderCore.latestClock() {
                await gateway.recordDacClock(sample)
                return
            }
            try? await Task.sleep(nanoseconds: 25_000_000)
        }
        // Timeout: the intent still goes out (audible playback proceeds);
        // rendered position simply reads 0 until the next intent. Degrade,
        // never block.
    }

    /// Drains the plane's diagnostics onto the observable surface (called
    /// after play intents — the push cadence repaints transport state).
    private func refreshPlaneDiagnostics() {
        lastCoreAudioTelemetry = renderCore.telemetry()
        let gateway = self.gateway
        Task {
            lastBufferProgress = await gateway.bufferProgress()
        }
    }

    // MARK: - WS-B B6: the lock-screen Live Activity

    #if canImport(ActivityKit) && canImport(UIKit)
    /// Drives the Live Activity from the same admitted pushes the in-app
    /// now-playing surface consumes (the controller's 1 Hz fingerprint
    /// admission does the rate limiting). Ends the activity when the
    /// transport stops without a track.
    private func updateLiveActivity() {
        guard let controller = liveActivityController else { return }
        guard let track = currentTrack else {
            if controller.isActive, status == .idle || status == .error {
                let ender = controller
                Task { await ender.end() }
            }
            return
        }
        let isPlaying = status == .playing
        let positionSeconds = Double(
            progress.positionMs(atUptimeNs: Self.hostUptimeNs) ?? 0
        ) / 1000.0
        let durationSeconds = Double(track.durationMs ?? 0) / 1000.0
        let updater = controller
        let artworkUrl = track.artworkUrl
        let trackId = track.id
        let title = track.title
        let artist = track.artist
        Task { @MainActor in
            if updater.isActive {
                await updater.update(
                    trackId: trackId,
                    title: title,
                    artist: artist,
                    isPlaying: isPlaying,
                    positionSeconds: positionSeconds,
                    durationSeconds: durationSeconds,
                    artworkUrl: artworkUrl
                )
            } else if isPlaying {
                await updater.start(
                    trackId: trackId,
                    title: title,
                    artist: artist,
                    isPlaying: isPlaying,
                    positionSeconds: positionSeconds,
                    durationSeconds: durationSeconds,
                    artworkUrl: artworkUrl
                )
            }
        }
    }
    #else
    private func updateLiveActivity() {}
    #endif

    func seek(to positionMs: Int) {
        // Optimistic seek admission (Android parity): the scrub bar jumps
        // immediately; the engine's pushed hardware position unlatches it.
        progress.registerSeek(positionMs: positionMs, hostUptimeNs: Self.hostUptimeNs)
        dispatch(.seek(positionMs: positionMs))
    }

    func setVolume(_ volume: Double) {
        dispatch(.setVolume(min(max(volume, 0), 1)))
    }

    /// Creates the engine's local Jam session mirror; the name (or nil)
    /// surfaces through the calling view.
    func createJamSession(name: String) async -> String? {
        await gateway.createJamSession(name: name)
    }

    /// BLAKE3 state hash of the active Jam session, or nil.
    func jamStateHash() async -> String? {
        await gateway.jamStateHash()
    }

    private func dispatch(_ command: PlayerCommand) {
        let previousTask = dispatchChain
        dispatchChain = Task { [weak self] in
            _ = await previousTask?.value
            guard let self else { return }
            self.lastResult = await self.gateway.send(command)
        }
    }

    /// Flushes all pending asynchronous dispatches into the engine gateway (strict FIFO).
    func flushDispatches() async {
        _ = await dispatchChain?.value
    }

    // MARK: - AudioSessionResponding (MP-UI02 reactions)

    func audioSessionRequestsPause(reason: String) {
        dispatch(.pause)
    }

    func audioSessionRequestsResume(reason: String) {
        dispatch(.play)
    }

    // MARK: - RemoteCommandRouting (MP-UI01 lockscreen transport)

    func remoteCommandReceived(_ command: RemoteCommand) {
        switch command {
        case .play: dispatch(.play)
        case .pause: dispatch(.pause)
        case .togglePlayPause: dispatch(.togglePlayPause)
        case .next: dispatch(.next)
        case .previous: dispatch(.previous)
        case .seek(let positionMs): dispatch(.seek(positionMs: positionMs))
        }
    }

    /// SW-ENG3-03: like/dislike from the lockscreen / Control Center /
    /// CarPlay — surfaced for the shell; engine transport is uninvolved.
    func remoteFeedbackReceived(_ feedback: RemoteFeedback) {
        lastFeedback = feedback
    }
}
