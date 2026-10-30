import AVFoundation
import XCTest
@testable import StreamifyIOS

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG3-03 acceptance lane: the MP-DEC-07 hardware debounce — leading-edge
// admission with duplicate suppression (the 50-150 ms envelope), the
// trailing-edge seek coalescing, and the feedback plane.
// ─────────────────────────────────────────────────────────────────────────────

final class RemoteCommandCenterAdapterTests: XCTestCase {

    // ── The pure admission gate ───────────────────────────────────────────

    func testFirstArrivalAlwaysAdmits() {
        let gate = MediaCommandGate<RemoteCommand>()
        XCTAssertTrue(gate.admits(.next, nowNs: 0))
        XCTAssertTrue(gate.admits(.pause, nowNs: 5_000_000_000))
    }

    func testIdenticalRefireInsideTheWindowIsSwallowed() {
        let windowNs: UInt64 = 100_000_000
        var gate = MediaCommandGate<RemoteCommand>(windowNs: windowNs)
        gate = gate.admitting(.next, nowNs: 1_000_000_000)
        // 40 ms later: inside the 100 ms window — swallowed.
        XCTAssertFalse(gate.admits(.next, nowNs: 1_040_000_000))
    }

    func testWindowBoundaryReopens() {
        let windowNs: UInt64 = 100_000_000
        var gate = MediaCommandGate<RemoteCommand>(windowNs: windowNs)
        gate = gate.admitting(.next, nowNs: 1_000_000_000)
        // Exactly at the boundary: >= semantics re-admit.
        XCTAssertTrue(gate.admits(.next, nowNs: 1_100_000_000))
    }

    func testDifferentIntentNeverWaitsBehindTheWindow() {
        let windowNs: UInt64 = 100_000_000
        var gate = MediaCommandGate<RemoteCommand>(windowNs: windowNs)
        gate = gate.admitting(.next, nowNs: 1_000_000_000)
        // A genuine pause 40 ms after a next: a real user intent — admits.
        XCTAssertTrue(gate.admits(.pause, nowNs: 1_040_000_000))
        // And the seek value differs from next: admits too.
        XCTAssertTrue(gate.admits(.seek(positionMs: 5), nowNs: 1_040_000_000))
    }

    func testAdmissionStateAdvancesToTheNewestCommand() {
        var gate = MediaCommandGate<RemoteCommand>(windowNs: 100_000_000)
        gate = gate.admitting(.next, nowNs: 1_000_000_000)
        gate = gate.admitting(.pause, nowNs: 2_000_000_000)
        // The pause is now the reference: an immediate pause re-fire is
        // swallowed, but the (older) next admits again.
        XCTAssertFalse(gate.admits(.pause, nowNs: 2_040_000_000))
        XCTAssertTrue(gate.admits(.next, nowNs: 2_040_000_000))
    }

    // ── The envelope clamp ────────────────────────────────────────────────

    func testWindowClampsIntoTheApprovedEnvelope() {
        XCTAssertEqual(MediaCommandDebouncePolicy.clampedWindowNs(1_000), 50_000_000)
        XCTAssertEqual(MediaCommandDebouncePolicy.clampedWindowNs(50_000_000), 50_000_000)
        XCTAssertEqual(MediaCommandDebouncePolicy.clampedWindowNs(100_000_000), 100_000_000)
        XCTAssertEqual(MediaCommandDebouncePolicy.clampedWindowNs(150_000_000), 150_000_000)
        XCTAssertEqual(MediaCommandDebouncePolicy.clampedWindowNs(999_000_000), 150_000_000)
    }

    // ── The adapter: discrete commands + feedback ─────────────────────────

    @MainActor
    private final class RecordingRouter: RemoteCommandRouting {
        var commands: [RemoteCommand] = []
        var feedback: [RemoteFeedback] = []

        func remoteCommandReceived(_ command: RemoteCommand) {
            commands.append(command)
        }

        func remoteFeedbackReceived(_ feedback: RemoteFeedback) {
            self.feedback.append(feedback)
        }
    }

    /// The directive's core scenario: a headset toggle double-fire (the
    /// same command twice inside the window) routes ONCE; a later real
    /// press routes again (the window re-opened).
    @MainActor
    func testAdapterSwallowsHeadsetDoubleFire() {
        let router = RecordingRouter()
        var virtualNow: UInt64 = 1_000_000_000
        let adapter = RemoteCommandCenterAdapter(
            router: router,
            windowNs: 100_000_000,
            clock: { virtualNow }
        )

        // The real press at t0: admitted + routed.
        XCTAssertTrue(adapter.receive(.next))
        // The Bluetooth repeat 40 ms later: swallowed.
        virtualNow += 40_000_000
        XCTAssertFalse(adapter.receive(.next))
        // Still inside the window at +80 ms: swallowed.
        virtualNow += 40_000_000
        XCTAssertFalse(adapter.receive(.next))
        // Past the window at +120 ms: a real press routes again.
        virtualNow += 40_000_000
        XCTAssertTrue(adapter.receive(.next))

        XCTAssertEqual(router.commands, [.next, .next])
    }

    /// A DIFFERENT intent inside the window is never blocked (windows are
    /// per-command, not global).
    @MainActor
    func testDifferentIntentInsideWindowRoutesImmediately() {
        let router = RecordingRouter()
        var virtualNow: UInt64 = 1_000_000_000
        let adapter = RemoteCommandCenterAdapter(
            router: router,
            windowNs: 100_000_000,
            clock: { virtualNow }
        )

        XCTAssertTrue(adapter.receive(.next))
        virtualNow += 40_000_000
        XCTAssertTrue(adapter.receive(.pause))
        virtualNow += 40_000_000
        XCTAssertTrue(adapter.receive(.togglePlayPause))

        XCTAssertEqual(router.commands, [.next, .pause, .togglePlayPause])
    }

    @MainActor
    func testFeedbackRoutesToTheShellPlane() {
        let gateway = PreviewEngineGateway()
        let model = PlaybackViewModel(gateway: gateway)
        model.remoteFeedbackReceived(.like)
        model.remoteFeedbackReceived(.dislike)
        XCTAssertEqual(model.lastFeedback, .dislike)
        model.stop()
    }

    // ── The trailing-edge seek debounce ───────────────────────────────────

    @MainActor
    func testDispatchSeekDefersToTheTrailingEdge() async {
        let router = RecordingRouter()
        let adapter = RemoteCommandCenterAdapter(
            router: router,
            windowNs: 100_000_000
        )

        adapter.dispatchSeek(positionMs: 42_000)
        // Not routed immediately — the trailing edge owns it.
        XCTAssertTrue(router.commands.isEmpty)

        // The window (100 ms) passes: the seek lands exactly once.
        try? await Task.sleep(nanoseconds: 400_000_000)
        XCTAssertEqual(router.commands, [.seek(positionMs: 42_000)])
    }

    @MainActor
    func testRapidScrubbingCoalescesToTheLastPosition() async {
        let router = RecordingRouter()
        var virtualNow: UInt64 = 1_000_000_000
        let adapter = RemoteCommandCenterAdapter(
            router: router,
            windowNs: 100_000_000,
            clock: { virtualNow }
        )

        // A scrub storm: five distinct positions inside one window.
        adapter.dispatchSeek(positionMs: 10_000)
        virtualNow += 20_000_000
        adapter.dispatchSeek(positionMs: 20_000)
        virtualNow += 20_000_000
        adapter.dispatchSeek(positionMs: 30_000)
        virtualNow += 20_000_000
        adapter.dispatchSeek(positionMs: 40_000)
        virtualNow += 20_000_000
        adapter.dispatchSeek(positionMs: 50_000)

        try? await Task.sleep(nanoseconds: 400_000_000)
        // ONLY the last position reached the engine.
        XCTAssertEqual(router.commands, [.seek(positionMs: 50_000)])
    }

    @MainActor
    func testIdenticalSeekRefireInsideWindowIsSwallowed() async {
        let router = RecordingRouter()
        var virtualNow: UInt64 = 1_000_000_000
        let adapter = RemoteCommandCenterAdapter(
            router: router,
            windowNs: 100_000_000,
            clock: { virtualNow }
        )

        adapter.dispatchSeek(positionMs: 42_000)
        virtualNow += 40_000_000
        // The same position re-firing (Bluetooth repeat): swallowed.
        adapter.dispatchSeek(positionMs: 42_000)

        try? await Task.sleep(nanoseconds: 400_000_000)
        XCTAssertEqual(router.commands, [.seek(positionMs: 42_000)])
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG3-02 acceptance lane: the hardened audio-session policy —
// pre-interruption memory, resume safety, and the new-device buffer policy.
// ─────────────────────────────────────────────────────────────────────────────

final class IOSAudioSessionHardeningTests: XCTestCase {

    // ── Pre-interruption memory (the resume-safety core) ──────────────────

    func testInterruptionBeganAlwaysPausesRegardlessOfMemory() {
        XCTAssertEqual(
            IOSAudioSessionAdapter.decision(
                forInterruption: .began,
                options: [],
                preInterruptionPlaying: true
            ),
            .pauseEngine
        )
        XCTAssertEqual(
            IOSAudioSessionAdapter.decision(
                forInterruption: .began,
                options: [],
                preInterruptionPlaying: false
            ),
            .pauseEngine
        )
    }

    func testEndedWithShouldResumeResumesOnlyWhenPlayingBefore() {
        // The phone call finished and the OS permits resuming — but the
        // engine was PAUSED when the call stole the route: stay paused.
        XCTAssertEqual(
            IOSAudioSessionAdapter.decision(
                forInterruption: .ended,
                options: [.shouldResume],
                preInterruptionPlaying: false
            ),
            .none
        )
        // Playing before the interruption: resume.
        XCTAssertEqual(
            IOSAudioSessionAdapter.decision(
                forInterruption: .ended,
                options: [.shouldResume],
                preInterruptionPlaying: true
            ),
            .resumeEngine
        )
    }

    func testEndedWithoutTheFlagNeverResumes() {
        XCTAssertEqual(
            IOSAudioSessionAdapter.decision(
                forInterruption: .ended,
                options: [],
                preInterruptionPlaying: true
            ),
            .none
        )
    }

    func testFlagOnlyDecisionKeepsItsLegacyContract() {
        // The 2-parameter view (pre-hardening call sites / the original
        // test suite) == the 3-parameter view with NO memory.
        XCTAssertEqual(
            IOSAudioSessionAdapter.decision(forInterruption: .ended, options: [.shouldResume]),
            IOSAudioSessionAdapter.decision(
                forInterruption: .ended,
                options: [.shouldResume],
                preInterruptionPlaying: nil
            )
        )
        XCTAssertEqual(
            IOSAudioSessionAdapter.decision(forInterruption: .began, options: []),
            .pauseEngine
        )
    }

    // ── Route-change reactions (the full reaction) ────────────────────────

    func testNewDeviceAvailableAdaptsTheBufferWithoutTouchingTheEngine() {
        let reaction = IOSAudioSessionAdapter.reaction(forRouteChange: .newDeviceAvailable)
        XCTAssertEqual(reaction.engineDecision, .none)
        XCTAssertTrue(reaction.adaptsBufferDuration)
    }

    func testOldDeviceUnavailablePausesWithoutBufferAdaptation() {
        let reaction = IOSAudioSessionAdapter.reaction(forRouteChange: .oldDeviceUnavailable)
        XCTAssertEqual(reaction.engineDecision, .pauseEngine)
        XCTAssertFalse(reaction.adaptsBufferDuration)
    }

    func testInformationalReasonsDoNothing() {
        XCTAssertEqual(
            IOSAudioSessionAdapter.reaction(forRouteChange: .categoryChange),
            RouteChangeReaction(engineDecision: .none, adaptsBufferDuration: false)
        )
        XCTAssertEqual(
            IOSAudioSessionAdapter.reaction(forRouteChange: .routeConfigurationChange),
            RouteChangeReaction(engineDecision: .none, adaptsBufferDuration: false)
        )
    }

    // ── The IO-buffer policy math ─────────────────────────────────────────

    func testBufferPolicyIsLatencyProportionalWithHeadroom() {
        // 10 ms device latency + 5 ms headroom, inside the bounds.
        XCTAssertEqual(
            OutputBufferPolicy.preferredIOBufferDuration(
                outputLatency: 0.010,
                minimum: 0.005,
                maximum: 0.093
            ),
            0.015,
            accuracy: 1e-9
        )
    }

    func testBufferPolicyClampsToTheReportedWindow() {
        // Huge latency clamps to the maximum.
        XCTAssertEqual(
            OutputBufferPolicy.preferredIOBufferDuration(
                outputLatency: 0.5,
                minimum: 0.005,
                maximum: 0.093
            ),
            0.093,
            accuracy: 1e-9
        )
        // Zero latency still takes the minimum (headroom below the floor).
        XCTAssertEqual(
            OutputBufferPolicy.preferredIOBufferDuration(
                outputLatency: 0,
                minimum: 0.023,
                maximum: 0.093
            ),
            0.023,
            accuracy: 1e-9
        )
    }

    func testBufferPolicyFallsBackWhenBoundsAreDegenerate() {
        // Unreported bounds (0) or inverted bounds fall back to the
        // documented defaults for BOTH ends — never a crash, never a
        // fabricated window: 10 ms latency + 5 ms headroom = 15 ms.
        XCTAssertEqual(
            OutputBufferPolicy.preferredIOBufferDuration(
                outputLatency: 0.010,
                minimum: 0,
                maximum: 0
            ),
            0.015,
            accuracy: 1e-9
        )
        XCTAssertEqual(
            OutputBufferPolicy.preferredIOBufferDuration(
                outputLatency: 0.010,
                minimum: 0.200,
                maximum: 0.100
            ),
            0.015,
            accuracy: 1e-9
        )
    }

    func testBufferPolicyClampsNegativeLatency() {
        let target = OutputBufferPolicy.preferredIOBufferDuration(
            outputLatency: -0.5,
            minimum: 0.005,
            maximum: 0.093
        )
        XCTAssertEqual(target, 0.005, accuracy: 1e-9)
    }

    // ── End-to-end interruption memory through notifications ─────────────

    @MainActor
    private final class StatefulResponder: AudioSessionResponding {
        var pauseCount = 0
        var resumeCount = 0
        /// What `audioSessionPlaybackIsActive` answers (the shell's truth).
        var playbackIsActive = false

        var audioSessionPlaybackIsActive: Bool { playbackIsActive }

        func audioSessionRequestsPause(reason: String) { pauseCount += 1 }
        func audioSessionRequestsResume(reason: String) { resumeCount += 1 }
    }

    /// The acceptance checklist's "Interruption Resume Safety": a call
    /// begins while playing → pause + memory; ends with shouldResume →
    /// deterministic resume.
    @MainActor
    func testPlayingInterruptionCycleResumesDeterministically() async throws {
        let responder = StatefulResponder()
        responder.playbackIsActive = true
        let adapter = IOSAudioSessionAdapter(responder: responder)
        try adapter.activate()

        NotificationCenter.default.post(
            name: AVAudioSession.interruptionNotification,
            object: nil,
            userInfo: IOSAudioSessionAdapter.interruptionPayload(type: .began)
        )
        try await Task.sleep(nanoseconds: 200_000_000)
        XCTAssertEqual(responder.pauseCount, 1)
        // The memory recorded the pre-interruption truth.
        XCTAssertEqual(adapter.interruptionMemory, InterruptionMemory(wasPlaying: true))

        NotificationCenter.default.post(
            name: AVAudioSession.interruptionNotification,
            object: nil,
            userInfo: IOSAudioSessionAdapter.interruptionPayload(
                type: .ended,
                options: [.shouldResume]
            )
        )
        try await Task.sleep(nanoseconds: 200_000_000)
        XCTAssertEqual(responder.resumeCount, 1)
        // The memory is consumed by the cycle.
        XCTAssertNil(adapter.interruptionMemory)

        adapter.deactivate()
    }

    /// The mirror case: the user had already paused when the call arrived —
    /// shouldResume must NOT unpause them.
    @MainActor
    func testPausedBeforeInterruptionStaysPausedAfterShouldResume() async throws {
        let responder = StatefulResponder()
        responder.playbackIsActive = false
        let adapter = IOSAudioSessionAdapter(responder: responder)
        try adapter.activate()

        NotificationCenter.default.post(
            name: AVAudioSession.interruptionNotification,
            object: nil,
            userInfo: IOSAudioSessionAdapter.interruptionPayload(type: .began)
        )
        try await Task.sleep(nanoseconds: 200_000_000)
        XCTAssertEqual(responder.pauseCount, 1)

        NotificationCenter.default.post(
            name: AVAudioSession.interruptionNotification,
            object: nil,
            userInfo: IOSAudioSessionAdapter.interruptionPayload(
                type: .ended,
                options: [.shouldResume]
            )
        )
        try await Task.sleep(nanoseconds: 200_000_000)
        XCTAssertEqual(responder.resumeCount, 0)

        adapter.deactivate()
    }
}
