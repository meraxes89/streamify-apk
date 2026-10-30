import SwiftUI

#if os(macOS)
import AppKit
#else
import SwiftUI
#endif

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2-01 — native vibrancy surfaces.
//
// On macOS the app uses NSVisualEffectView (the REAL AppKit material, via a
// SwiftUI representable) — the platform's answer to iOS `.ultraThinMaterial`.
// On iOS/iPadOS this type transparently aliases the SwiftUI `Material`
// ladder, so call sites stay platform-unconditional.
// ─────────────────────────────────────────────────────────────────────────────

#if os(macOS)

/// The NSVisualEffectView bridge — Apple HIG materials on the Mac
/// (SW-ENG2-01 mandate: ".ultraThinMaterial, .thinMaterial, and
/// VisualEffectView for macOS"). `state = .active` keeps vibrancy alive
/// even when the window resigns key (a streaming app must never look dead).
public struct VisualEffectBlur: NSViewRepresentable {
    public var material: NSVisualEffectView.Material
    public var blending: NSVisualEffectView.BlendingMode

    public init(
        material: NSVisualEffectView.Material = .hudWindow,
        blending: NSVisualEffectView.BlendingMode = .behindWindow
    ) {
        self.material = material
        self.blending = blending
    }

    public func makeNSView(context: Context) -> NSVisualEffectView {
        let view = NSVisualEffectView()
        view.material = material
        view.blendingMode = blending
        view.state = .active
        return view
    }

    public func updateNSView(_ nsView: NSVisualEffectView, context: Context) {
        nsView.material = material
        nsView.blendingMode = blending
        nsView.state = .active
    }
}

#else

/// On iPhone/iPad the SwiftUI `Material` ladder already IS the native
/// material system — alias to it so callers stay unconditional.
public struct VisualEffectBlur: View {
    public var material: Material

    public init(
        material: Material = StreamifyMaterials.sheet,
        blending: Int = 0  // macOS-only knob, ignored on iOS
    ) {
        self.material = material
    }

    public var body: some View {
        Rectangle().fill(material)
    }
}

#endif

/// Sidebar chrome per platform: true vibrancy on macOS, ultraThin glass on
/// iPadOS split view.
public struct SidebarChrome: View {
    public init() {}

    public var body: some View {
        #if os(macOS)
        VisualEffectBlur(material: .sidebar, blending: .behindWindow)
            .ignoresSafeArea()
        #else
        Rectangle().fill(.ultraThinMaterial).ignoresSafeArea()
        #endif
    }
}
