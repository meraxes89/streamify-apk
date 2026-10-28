import Foundation

// ─────────────────────────────────────────────────────────────────────────────
// Phase 5 (MP-UI01 / Beta): the engine-lane selector.
//
// The REAL direct-Swift UniFFI gateway compiles only when Alpha's
// StreamifyCore.xcframework is linked (`#if canImport(uniffi_streamify)` in
// UniFFIEngineGateway.swift); until then the deterministic preview gateway
// keeps the shell fully navigable. Selection happens here, in ONE place —
// the same probe-gate philosophy as the desktop CI's Windows engine check.
// ─────────────────────────────────────────────────────────────────────────────

enum EngineGatewayLoader {

    /// The best available engine lane for this build.
    static func load() -> EngineGateway {
        #if canImport(uniffi_streamify)
        if let real = try? UniFFIEngineGateway() {
            return real
        }
        #endif
        return PreviewEngineGateway()
    }

    /// True when the real UniFFI lane is compiled into this build.
    static var isRealLaneCompiled: Bool {
        #if canImport(uniffi_streamify)
        return true
        #else
        return false
        #endif
    }
}
