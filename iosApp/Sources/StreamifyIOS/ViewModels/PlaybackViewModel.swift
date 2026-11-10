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

    // ── WS-B B4: the CoreAudio data plane (ENG-2's mounting bracket) ─────
    //
    //   gateway.attachSink (the Rust ring + producer, UDL v2)
    //     -> AppleRingMount (the pump: Rust ring -> C++ bridge sink)
    //       -> CoreAudioRenderCore (the AURenderCallback pulling the
    //          bridge sink through the audited native render core)
    //     and DacClockPublisher drains the render core's published clock
    //     back into the engine (`record_dac_clock`), so every pushed
    //     snapshot carries the hardware-anchored position.
    //
    //   Ordering on arm:   attach -> mount -> render core -> publisher.
    //   Ordering on teardown is the exact reverse, each step idempotent.
    //   The ZERO-TOLERANCE rule (AHEAD B4): no interim AVPlayer path —
    //   the engine dictates real-time alignment via the physical
    //   DacPresentationClock frames. CoreAudio is non-negotiable.

    /// The render-core facade (owns ONE driver; injectable for the
    /// XCTest lane — the simulated driver keeps the lifecycle
    /// deterministic without hardware).
    let renderCore: CoreAudioRenderCore

    /// The Rust-ring -> bridge-sink pump (dlsym late binding; a clean
    /// no-op degrade until the XCFramework links the native object).
    private let ringMount: AppleRingMount

    /// The 10 Hz hardware clock drain: republishes DAC samples to the UI
    /// and mirrors each one into the engine (`record_dac_clock`).
    private var dacPublisher: DacClockPublisher?

    /// The sink capacity handed to `attachSink` (A1 RFC §4 buffering
    /// budget: the bulk buffering lives in the Rust ring; the native
    /// bridge ring adds a bounded ≈85 ms decoupling layer on top).
    var sinkCapacityFrames: Int = 8192

    /// Diagnostics: why the audio plane last armed or refused.
    private(set) var lastAudioPlaneStatus: String = "idle"

    private var audioPlaneArmed = false

    // ── WS-B B6: the Live Activity controller (iOS 16.1+) ────────────────
    #if canImport(ActivityKit) && canImport(UIKit)
    private let liveActivity = PlaybackLiveActivityController()
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
        // WS-B B4: the audio-plane members (same nonisolated-default
        // rule — the fallbacks construct here, on the main actor).
        renderCore: CoreAudioRenderCore? = nil,
        ringMount: AppleRingMount? = nil
    ) {
        self.gateway = gateway
        self.nowPlayingInfo = nowPlayingInfoPublisher ?? NowPlayingInfoPublisher()
        self.widgetRefresher = widgetRefresher ?? WidgetPlaybackRefresher()
        // Two-phase initialization: every remaining stored property is a
        // var-optional (implicitly nil-initialized) or already assigned, so
        // `self` is FULLY initialized by the time the adapters below capture
        // it (a `let` optional such as widgetRefresher has no implicit nil —
        // it must be assigned BEFORE the first `self` escape).
        self.renderCore = renderCore ?? CoreAudioRenderCore()
        self.ringMount = ringMount ?? AppleRingMount()
        self.audioSession = audioSession ?? IOSAudioSessionAdapter(responder: self)
        self.remoteCommands = RemoteCommandCenterAdapter(
            router: self,
            windowNs: remoteCommandWindowNs
        )
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
        // WS-B B6: kill every stale Live Activity from a previous process
        // lifetime BEFORE the first push can start a fresh one.
        #if canImport(ActivityKit) && canImport(UIKit)
        Task { await PlaybackLiveActivityController.endAllStale() }
        #endif
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
        // WS-B B4: the audio plane tears down in the exact reverse of the
        // arm order — render core (retract the RT pointer, stop the
        // unit), pump (join deterministically, drop the bridge sink),
        /// producer (detach the engine plane) — each step idempotent.
        teardownAudioPlane()
        #if canImport(ActivityKit) && canImport(UIKit)
        Task { await liveActivity.end() }
        #endif
        nowPlayingInfo.clear()
        audioSession.deactivate()
        gateway.shutdown()
        progress.reset()
        status = .idle
        volume = 0
        buffer = nil
        currentTrack = nil
        jamSessionName = nil
        lastDacSample = nil
        lastRev = 0
    }

    /// One pushed snapshot applied atomically on the main actor — the single
    /// mutation point, which is what makes the transition race-free: bursts
    /// are serialized here in rev order, and anything out of order dies.
    private func ingest(_ snapshot: PlaybackSnapshot) {
        // Monotonic revision guard: a stale (or equal, re-delivered) push
        // can never repaint the UI — MP-DEC-04's `rev: u64` discipline.
        guard snapshot.rev > lastRev || lastRev == 0 else { return }
        lastRev = snapshot.rev

        status = snapshot.status
        volume = snapshot.volume
        buffer = snapshot.buffer
        jamSessionName = snapshot.jamSessionName
        // WS-B B3: a pushed track identity updates the surface; a snapshot
        // WITHOUT a track never wipes the shell's presented track (the
        // engine's UDL snapshot carries no track row — the play flow's
        // optimistic `present(track:)` stays authoritative until the next
        // explicit presentation).
        if let snapshotTrack = snapshot.currentTrack,
           snapshotTrack.id != currentTrack?.id {
            currentTrack = snapshotTrack
            progress.reset()
        }

        // The hardware clock anchor swap + optimistic-seek unlatch.
        lastDacSample = snapshot.clock
        progress.ingest(
            snapshot.clock,
            isPlaying: snapshot.status == .playing,
            durationMs: currentTrack?.durationMs,
            hostUptimeNs: Self.hostUptimeNs
        )

        // WS-B B4: arm/tear the audio plane around the lifecycle.
        updateAudioPlane(for: snapshot.status)

        // WS-B B6: the Live Activity rides the same admitted push (its own
        // 1 Hz rendered-value admission gate absorbs bursts).
        updateLiveActivity()

        nowPlayingInfo.publish(state: state)
        // SW-ENG3-06: mirror the admitted push into the widget plane
        // (fingerprint-gated store refresh + intent-mailbox drain — the
        // same push cadence the mailbox was designed for).
        mirrorWidgetPlane()
    }

    // MARK: - WS-B B4: the audio plane lifecycle

    /// Arms or tears the CoreAudio data plane around the transport
    /// lifecycle. Arming happens on the FIRST non-idle push (buffering
    /// counts: the producer must already be refilling when the DAC wakes);
    /// teardown happens on idle. Paused keeps the plane mounted (a resume
    /// must be gapless — the engine's own pause semantics).
    private func updateAudioPlane(for status: PlaybackStatus) {
        switch status {
        case .buffering, .playing:
            if !audioPlaneArmed {
                armAudioPlane()
            }
        case .idle, .error:
            if audioPlaneArmed {
                teardownAudioPlane()
            }
        case .paused:
            break  // mounted, silent — gapless resume by design
        }
    }

    private func armAudioPlane() {
        audioPlaneArmed = true
        lastAudioPlaneStatus = "arming"
        Task { [weak self] in
            guard let self else { return }
            await self.armAudioPlaneNow()
        }
    }

    private func armAudioPlaneNow() async {
        // 1. The engine plane: the Rust ring + the producer thread
        //    (replace semantics engine-side; failures degrade silently —
        //    the next `bufferProgress()` read reflects the truth).
        await gateway.attachSink(capacityFrames: sinkCapacityFrames)

        // 2. The mount: the pump bridging the Rust ring onto the C++
        //    bridge sink. A clean degrade on every refusal (the shell
        //    keeps rendering engine state; the diagnostics surface says
        //    why the plane is silent).
        let mountStatus = ringMount.start()
        switch mountStatus {
        case .mounted:
            lastAudioPlaneStatus = "mounted"
        case .nativeFacadeUnavailable:
            lastAudioPlaneStatus = "unavailable: native mount not linked"
        case .planeNotAttached:
            lastAudioPlaneStatus = "unavailable: engine plane not attached"
        case .mountRefused:
            lastAudioPlaneStatus = "refused: bridge sink creation failed"
        }

        // 3. The render core: the AURenderCallback pulling the bridge
        //    sink. Only a mounted handle can arm it.
        if case .mounted(let handle) = mountStatus {
            let renderStatus = renderCore.start(sinkHandle: handle)
            switch renderStatus {
            case .started:
                lastAudioPlaneStatus = "live (CoreAudio)"
            case .nativeFacadeUnavailable:
                lastAudioPlaneStatus = "unavailable: render facade not linked"
            case .invalidSinkHandle:
                lastAudioPlaneStatus = "unavailable: invalid bridge handle"
            case .deviceOpenFailed:
                lastAudioPlaneStatus = "unavailable: output unit open failed"
            }
            // 4. The clock publisher: 10 Hz drain -> engine mirror, so
            //    every pushed snapshot carries the hardware-anchored
            //    position (the <5 ms discipline, MP-DEC-03).
            if renderCore.isRunning {
                startDacPublisherIfNeeded()
            }
        }
    }

    private func teardownAudioPlane() {
        guard audioPlaneArmed else { return }
        audioPlaneArmed = false
        lastAudioPlaneStatus = "idle"
        dacPublisher?.stop()
        dacPublisher = nil
        renderCore.stop()
        ringMount.stop()
        Task { [weak self] in
            await self?.gateway.detachSink()
        }
    }

    private func startDacPublisherIfNeeded() {
        guard dacPublisher == nil else { return }
        let gateway = self.gateway
        let publisher = DacClockPublisher(
            driver: renderCore.driver,
            onSample: { sample in
                // The UDL `record_dac_clock` mirror: republished inside
                // every pushed snapshot from here on (never from the
                // audio thread — the publisher drains on the main actor).
                gateway.recordDacClock(sample)
            }
        )
        dacPublisher = publisher
        publisher.start()
    }

    // MARK: - WS-B B6: the Live Activity

    private func updateLiveActivity() {
        #if canImport(ActivityKit) && canImport(UIKit)
        guard let track = currentTrack else { return }
        let isPlaying = status == .playing
        // Hardware-anchored position (the DAC sample when one exists —
        // MP-DEC-03; the interpolated projection is the fallback while
        // the plane arms).
        let positionMs = PlaybackFormatting.positionMs(from: lastDacSample)
            ?? (state.positionMs ?? 0)
        let positionSeconds = Double(positionMs) / 1000.0
        let durationSeconds = Double(track.durationMs ?? 0) / 1000.0
        let artworkUrl = track.artworkUrl
        if liveActivity.isActive {
            Task {
                await liveActivity.update(
                    trackId: track.id,
                    title: track.title,
                    artist: track.artist,
                    isPlaying: isPlaying,
                    positionSeconds: positionSeconds,
                    durationSeconds: durationSeconds,
                    artworkUrl: artworkUrl
                )
            }
        } else if isPlaying {
            Task {
                await liveActivity.start(
                    trackId: track.id,
                    title: track.title,
                    artist: track.artist,
                    isPlaying: isPlaying,
                    positionSeconds: positionSeconds,
                    durationSeconds: durationSeconds,
                    artworkUrl: artworkUrl
                )
            }
        }
        #endif
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

    // MARK: - WS-B B3: optimistic play-flow metadata admission

    /// Presents the track the play flow just tapped: the engine's UDL
    /// snapshot carries no track row (the frozen push shape), so the
    /// shell admits the identity optimistically — exactly the discipline
    /// `registerSeek` applies to positions. The next push repaints
    /// transport state around it; the now-playing/lockscreen/CarPlay
    /// surfaces get their metadata immediately.
    func present(track: PresentationTrack) {
        guard track.id != currentTrack?.id else { return }
        currentTrack = track
        progress.reset()
        // The state-transition escape hatch: the rare identity change
        /// repaints the lockscreen NOW (the 1 Hz budget governs per-frame
        // churn, not transitions — NowPlayingInfoPublisher's contract).
        nowPlayingInfo.publishNow(info: NowPlayingInfoPublisher.infoDict(for: state))
        mirrorWidgetPlane()
        updateLiveActivity()
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

    func seek(to positionMs: Int) {
        // Optimistic seek admission (Android parity): the scrub bar jumps
        // immediately; the engine's pushed hardware position unlatches it.
        progress.registerSeek(positionMs: positionMs, hostUptimeNs: Self.hostUptimeNs)
        // WS-B B4: the bridge sink must drop its stale tail before the
        // re-anchored audio arrives (the cooperative flush — applied by
        // the pump on its next pass, <= 4 ms). The engine re-anchors the
        // Rust ring on its own side of the seam.
        ringMount.flush()
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
