import Darwin
import Foundation

// ─────────────────────────────────────────────────────────────────────────────
// WS-B B4 (ENG-2): the Apple ring mount's Swift half — the dlsym late
// binding over `native/audio/apple_ring_mount.cpp`'s frozen C facade
// (`streamify_apple_mount_*`).
//
// ARCHITECTURE (the completed bracket, end to end):
//
//   Rust producer thread (ENG-1, WS-A A3/A4)
//     └─ SpscAudioRing (the Rust ring — `attach_sink` builds it)
//          └─ streamify_ring_* C edge (consumer_lease.rs — the frozen ABI)
//               └─ AppleRingMount pump thread (apple_ring_mount.cpp)
//                    └─ C++ AudioSink in the SinkRegistry (the bridge ring)
//                         └─ CoreAudio AURenderCallback (coreaudio_pull_adapter.cpp)
//                              └─ the DAC (native device speakers)
//
// SEAM DISCIPLINE (mirrors NativeCoreAudioDriver exactly): the symbols
// resolve via dlsym(RTLD_DEFAULT) at `start` — until the XCFramework is
// linked, `start` degrades to a clean `.unavailable`-style nil, never a
// crash, and the whole package keeps compiling standalone (the probe-gate
// philosophy; ZERO Swift runtime state is reachable from the pump thread
// — this file only ever touches the control plane).
// ─────────────────────────────────────────────────────────────────────────────

/// The mount lifecycle status surfaced to the shell (diagnostics surface).
enum AppleRingMountStatus: Equatable, Sendable {
    case mounted(handle: Int64)
    /// The `streamify_apple_mount_*` symbols are not linked into this
    /// process (pre-XCFramework scaffold period, or a non-Apple host).
    case nativeFacadeUnavailable
    /// The engine's playback data plane is not attached yet (the shell's
    /// ordering: `attach_sink` first, then mount, then render core).
    case planeNotAttached
    /// The mount refused (registry/geometry failure — see the native log).
    case mountRefused
}

/// Late-bound bridge over the `streamify_apple_mount_*` C facade. The
/// returned handle feeds `CoreAudioRenderCore.start(sinkHandle:)` — the
/// render core then opens the output unit and pulls from the bridge sink
/// this mount keeps refilled.
final class AppleRingMount {

    private var handle: Int64 = 0
    private var startFn: StartFn?
    private var stopFn: StopFn?
    private var flushFn: FlushFn?

    private typealias StartFn = @convention(c) () -> Int64
    private typealias StopFn = @convention(c) () -> Void
    private typealias FlushFn = @convention(c) () -> Void

    var isMounted: Bool { handle != 0 }

    init() {}

    /// Arms the pump. The engine's data plane must already be attached
    /// (`gateway.attachSink`) — the native mount refuses otherwise, which
    /// maps to `.planeNotAttached` here.
    @discardableResult
    func start() -> AppleRingMountStatus {
        stop()
        guard resolveFacade(), let startFn else { return .nativeFacadeUnavailable }
        let mounted = startFn()
        if mounted == -1 {
            return .planeNotAttached
        }
        guard mounted != 0 else {
            return .mountRefused
        }
        handle = mounted
        return .mounted(handle: mounted)
    }

    /// Ordered teardown (pump joined deterministically, registry entry
    /// destroyed). Idempotent.
    func stop() {
        guard handle != 0 else { return }
        stopFn?()
        handle = 0
    }

    /// Forwards a seek/skip intent onto the bridge sink (applied by the
    /// pump on its next pass — the frozen cooperative-flush protocol).
    func flush() {
        guard handle != 0 else { return }
        flushFn?()
    }

    deinit { stop() }

    /// Resolves the three facade symbols from the process' global scope
    /// once per start attempt (NativeCoreAudioDriver's discipline).
    private func resolveFacade() -> Bool {
        if startFn != nil, stopFn != nil, flushFn != nil {
            return true
        }
        let rtldDefault = UnsafeMutableRawPointer(bitPattern: -2)
        guard
            let startSym = dlsym(rtldDefault, "streamify_apple_mount_start"),
            let stopSym = dlsym(rtldDefault, "streamify_apple_mount_stop"),
            let flushSym = dlsym(rtldDefault, "streamify_apple_mount_flush")
        else {
            return false
        }
        startFn = unsafeBitCast(startSym, to: StartFn.self)
        stopFn = unsafeBitCast(stopSym, to: StopFn.self)
        flushFn = unsafeBitCast(flushSym, to: FlushFn.self)
        return true
    }
}
