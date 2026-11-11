import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// Phase 5 (MP-UI01 / Beta): the app entry surface.
//
// WHY A `Scene` AND NOT `@main`: SPM cannot emit an App Store bundle, so
// the package is a LIBRARY and a thin Xcode app target owns the actual
// entry point (iosApp/README.md "Host app target"). An `@main` inside the
// library would collide with the host's own `@main` at link time; instead
// the library exposes [StreamifyMainScene], which the host's 3-line App
// struct composes directly. Everything real — shell, state holder, engine
// seam, audio-session adapter — lives in this package; the host is a
// husk.
//
// ENGINE LANE SELECTION: [EngineGatewayLoader] picks the real direct-Swift
// UniFFI gateway when Alpha's StreamifyCore.xcframework is linked, and the
// deterministic preview gateway otherwise. ZERO Kotlin/Native
// double-bridge either way (the Apple-platform mandate, MP-DEC-02).
// ─────────────────────────────────────────────────────────────────────────────

public struct StreamifyMainScene: Scene {

    @State private var model: PlaybackViewModel

    // @MainActor: the @Observable model's initializer is main-actor-isolated
    // (SW-DEC-02); Scene bodies already run there, and the host's 3-line App
    // struct calls this init from its own @MainActor body.
    //
    // WS-B B1: the model rides AppServices' SHARED engine gateway — the
    // same `StreamifyEngine` the feature library (UniFFIFeatureLibrary)
    // speaks to. Two gateways would mean two engines, two queues, and a
    // silent desync between what plays and what the screens show.
    @MainActor public init() {
        _model = State(
            initialValue: PlaybackViewModel(gateway: AppServices.engineGateway)
        )
    }

    public var body: some Scene {
        WindowGroup {
            RootShellView()
                .environment(model)
                .preferredColorScheme(StreamifyTheme.shellColorScheme)
                .task {
                    model.start()
                    // WS-B B6: reap any Live Activity a previous launch
                    // left presenting (cold-launch staleness sweep).
                    #if canImport(ActivityKit) && canImport(UIKit)
                    await PlaybackLiveActivityController.endAllStale()
                    #endif
                }
                .onDisappear { model.stop() }
        }
    }
}
