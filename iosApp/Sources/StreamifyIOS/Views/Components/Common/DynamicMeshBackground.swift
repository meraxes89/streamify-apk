import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2-C04.12 — DynamicMeshBackground: the player's ambient mesh backdrop.
//
// Port of ui/components/DynamicMeshBackground.kt (the artwork-derived bloom
// field washed over the full-screen player). Two renderers, one contract:
//
//   (a) AmbientGradientLayers — ALWAYS available: layered radial/linear
//       gradients over the palette stops, slowly drifting; positions are PURE
//       functions of a TimelineView phase Date (sin/cos — zero state mutation,
//       zero Animatable plumbing), so the draw loop is allocation-free.
//   (b) TrueMeshGradient — the REAL MeshGradient on iOS 18 / macOS 15, gated
//       behind #if compiler(>=6.0) + @available so the SAME file compiles on
//       Xcode 15 (Swift 5.10) and Xcode 16+, per the multi-SDK rule.
//
// Palette crossfades run on Motion.slowDuration-based easeInOut whenever the
// palette identity changes (SW-DEC-06).
// ─────────────────────────────────────────────────────────────────────────────

public struct DynamicMeshBackground: View {

    public var palette: AmbientPalette
    public var intensity: Double

    public init(palette: AmbientPalette, intensity: Double = 1) {
        self.palette = palette
        self.intensity = intensity
    }

    public var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 60.0)) { timeline in
            meshRoot(phase: timeline.date)
                .animation(
                    .easeInOut(duration: Motion.slowDuration),
                    value: palette
                )
        }
        .blur(radius: 28)
        .ignoresSafeArea()
        .accessibilityHidden(true)
    }

    /// Runtime/SDK dispatch: the true mesh where both allow it, the layered
    /// fallback everywhere else. The #if gates the MeshGradient SYMBOL only.
    @ViewBuilder
    private func meshRoot(phase: Date) -> some View {
        if #available(iOS 18.0, macOS 15.0, *) {
            #if compiler(>=6.0)
            TrueMeshGradient(palette: palette, intensity: intensity, phase: phase)
            #else
            AmbientGradientLayers(palette: palette, intensity: intensity, phase: phase)
            #endif
        } else {
            AmbientGradientLayers(palette: palette, intensity: intensity, phase: phase)
        }
    }
}

// MARK: - Pure drift math (positions are functions of time only)

private enum MeshDrift {
    static func sinDrift(
        _ t: Double, speed: Double, amplitude: Double, phaseOffset: Double = 0
    ) -> Double {
        sin(t * speed + phaseOffset) * amplitude
    }

    static func cosDrift(
        _ t: Double, speed: Double, amplitude: Double, phaseOffset: Double = 0
    ) -> Double {
        cos(t * speed + phaseOffset) * amplitude
    }
}

// MARK: - (a) Always-available layered fallback

/// The concrete small fallback view reused by both branches: three layered
/// gradients (dominant bloom, accent harmonic, muted vignette) over the 4-stop
/// palette (dominant · accent · darkMuted · playerBase).
private struct AmbientGradientLayers: View {

    let palette: AmbientPalette
    let intensity: Double
    let phase: Date

    private var stops: [Color] {
        [palette.dominant, palette.accent, palette.darkMuted, StreamifyColors.playerDynamicBase]
    }

    var body: some View {
        GeometryReader { geo in
            let t = phase.timeIntervalSinceReferenceDate
            let bloomRadius = max(geo.size.width, 1)
            ZStack {
                Rectangle().fill(stops[3])

                // Dominant bloom — top-right, slow drift.
                Rectangle().fill(
                    RadialGradient(
                        colors: [
                            stops[0].opacity(0.42 * intensity),
                            stops[0].opacity(0.16 * intensity),
                            .clear,
                        ],
                        center: UnitPoint(
                            x: 0.85 + MeshDrift.sinDrift(t, speed: 0.11, amplitude: 0.10),
                            y: 0.20 + MeshDrift.cosDrift(t, speed: 0.07, amplitude: 0.08)
                        ),
                        startRadius: 0,
                        endRadius: bloomRadius * 0.95
                    )
                )

                // Accent harmonic — bottom-left, counter-phase drift.
                Rectangle().fill(
                    RadialGradient(
                        colors: [
                            stops[1].opacity(0.30 * intensity),
                            stops[1].opacity(0.10 * intensity),
                            .clear,
                        ],
                        center: UnitPoint(
                            x: 0.15 + MeshDrift.cosDrift(t, speed: 0.09, amplitude: 0.12),
                            y: 0.75 + MeshDrift.sinDrift(t, speed: 0.13, amplitude: 0.10, phaseOffset: 0.8)
                        ),
                        startRadius: 0,
                        endRadius: bloomRadius * 0.85
                    )
                )

                // Muted underglow + base vignette — keeps the mesh behind the
                // transport readable (the Android 0.65/0.92 BgBase veil).
                Rectangle().fill(
                    LinearGradient(
                        stops: [
                            .init(color: .clear, location: 0),
                            .init(color: stops[2].opacity(0.35 * intensity), location: 0.55),
                            .init(color: stops[3].opacity(0.92), location: 1),
                        ],
                        startPoint: UnitPoint(
                            x: 0.5 + MeshDrift.sinDrift(t, speed: 0.05, amplitude: 0.18),
                            y: 0
                        ),
                        endPoint: UnitPoint(x: 0.5, y: 1)
                    )
                )
            }
        }
    }
}

// MARK: - (b) True iOS 18 mesh (compiler-gated symbol)

#if compiler(>=6.0)
@available(iOS 18.0, macOS 15.0, *)
private struct TrueMeshGradient: View {

    let palette: AmbientPalette
    let intensity: Double
    let phase: Date

    var body: some View {
        let t = phase.timeIntervalSinceReferenceDate
        ZStack {
            Rectangle().fill(StreamifyColors.playerDynamicBase)
            MeshGradient(width: 3, height: 3, points: meshPoints(t), colors: meshColors)
        }
    }

    /// 3×3 grid: corners pinned, edge midpoints drift ALONG their edge, the
    /// center drifts in both axes — always inside the unit square.
    private func meshPoints(_ t: Double) -> [SIMD2<Float>] {
        [
            SIMD2<Float>(0, 0),
            SIMD2<Float>(
                Float(0.5 + MeshDrift.sinDrift(t, speed: 0.21, amplitude: 0.12)),
                Float(MeshDrift.cosDrift(t, speed: 0.16, amplitude: 0.04))
            ),
            SIMD2<Float>(1, 0),
            SIMD2<Float>(
                Float(MeshDrift.cosDrift(t, speed: 0.15, amplitude: 0.04)),
                Float(0.5 + MeshDrift.sinDrift(t, speed: 0.18, amplitude: 0.12))
            ),
            SIMD2<Float>(
                Float(0.5 + MeshDrift.sinDrift(t, speed: 0.19, amplitude: 0.10, phaseOffset: 0.6)),
                Float(0.5 + MeshDrift.cosDrift(t, speed: 0.23, amplitude: 0.10))
            ),
            SIMD2<Float>(
                Float(1 + MeshDrift.cosDrift(t, speed: 0.13, amplitude: 0.04)),
                Float(0.5 + MeshDrift.sinDrift(t, speed: 0.17, amplitude: 0.10, phaseOffset: 1.4))
            ),
            SIMD2<Float>(0, 1),
            SIMD2<Float>(
                Float(0.5 + MeshDrift.cosDrift(t, speed: 0.20, amplitude: 0.12, phaseOffset: 0.3)),
                Float(1 + MeshDrift.sinDrift(t, speed: 0.12, amplitude: 0.04))
            ),
            SIMD2<Float>(1, 1),
        ]
    }

    private var meshColors: [Color] {
        [
            palette.darkMuted.opacity(0.85 * intensity),
            palette.dominant.opacity(0.80 * intensity),
            palette.dominant.opacity(0.55 * intensity),
            palette.darkMuted.opacity(0.75 * intensity),
            palette.accent.opacity(0.75 * intensity),
            palette.accent.opacity(0.45 * intensity),
            StreamifyColors.playerDynamicBase,
            palette.dominant.opacity(0.50 * intensity),
            StreamifyColors.playerDynamicBase,
        ]
    }
}
#endif

#Preview("Dynamic mesh background") {
    ZStack {
        DynamicMeshBackground(palette: PreviewCatalog.palette(forTitle: "Obsidian Sunrise"))
        VStack(spacing: 12) {
            Text("Obsidian Sunrise")
                .font(StreamifyType.playerTitle)
                .foregroundStyle(StreamifyColors.textMain)
            Text("Sireen · 4:11")
                .font(StreamifyType.playerArtist)
                .foregroundStyle(StreamifyColors.textSecondary)
        }
    }
}
