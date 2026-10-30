import Foundation

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2-10 — pure geometry & easing math for the MiniPlayer ↔ FullPlayer
// liquid sheet morph, ported 1:1 from ui/motion/LiquidMorphGeometry.kt.
//
// Everything is a pure function of the morph progress `y ∈ [0, 1]`
// (0 = collapsed dock, 1 = expanded sheet): fully unit-testable, never
// touching view state. The MiniPlayer endpoint follows the design spec:
// 64 pt bar height, 48 pt cover, 8 pt corner; the FullPlayer endpoint:
// 320 pt hero art, 24 pt corner.
// ─────────────────────────────────────────────────────────────────────────────

public enum LiquidMorphGeometry {

    // ─ Design-spec endpoints (pt) ────────────────────────────────────────
    public static let miniBarHeight: Double = 64
    public static let miniCoverSize: Double = 48
    public static let miniCoverCorner: Double = 8
    public static let heroArtMax: Double = 320
    public static let heroCorner: Double = 24

    // ─ Interpolation primitives ──────────────────────────────────────────

    public static func lerp(_ a: Double, _ b: Double, _ t: Double) -> Double {
        a + (b - a) * t
    }

    /// Decelerating ease used for the sheet's own entrance transform.
    public static func easeOutCubic(_ t: Double) -> Double {
        let x = min(max(t, 0), 1)
        let inv = 1 - x
        return 1 - inv * inv * inv
    }

    /// Hermite smoothstep — S-curve used for alpha crossfades.
    public static func smoothstep(_ t: Double) -> Double {
        let x = min(max(t, 0), 1)
        return x * x * (3 - 2 * x)
    }

    // ─ Mini dock response ─────────────────────────────────────────────────

    /// Mini dock alpha: fully visible while collapsed, fully gone by 70 % of
    /// the morph — the shared cover layer owns the artwork well before the
    /// dock itself finishes fading.
    public static func miniBarAlpha(_ progress: Double) -> Double {
        min(max(1 - progress / 0.7, 0), 1)
    }

    /// Mini dock upward lift fraction (of bar height): the dock slides up
    /// and out of the way as the sheet grows over it, bottom-anchored.
    public static func miniBarLiftFraction(_ progress: Double) -> Double {
        easeOutCubic(progress) * 0.35
    }

    // ─ Sheet container response ───────────────────────────────────────────

    /// Sheet opacity: reaches full opacity at ~55 % progress.
    public static func sheetAlpha(_ progress: Double) -> Double {
        smoothstep(progress / 0.55)
    }

    /// Sheet scale: grows from 92 % to 100 %, bottom-anchored.
    public static func sheetScale(_ progress: Double) -> Double {
        0.92 + 0.08 * easeOutCubic(progress)
    }

    /// Sheet vertical offset as a fraction of screen height: starts 22 % below
    /// its resting place and rises into position. Linear in progress so the
    /// finger owns the motion 1:1 during a drag (no double-easing).
    public static func sheetTranslationFraction(_ progress: Double) -> Double {
        (1 - min(max(progress, 0), 1)) * 0.22
    }

    // ─ Shared cover element ────────────────────────────────────────────────

    /// Interpolated shared-cover corner radius between the 8 pt mini endpoint
    /// and the 24 pt hero endpoint. Linear: radius is a small detail and
    /// stays perceptually 1:1 with the finger.
    public static func coverCornerRadius(_ progress: Double) -> Double {
        lerp(miniCoverCorner, heroCorner, min(max(progress, 0), 1))
    }

    /// Shared-layer alpha: the morphing cover layer owns the artwork strictly
    /// inside the open interval — at the endpoints the real nodes (mini
    /// cover / sheet hero) are pixel-exact owners, so the hard cut is invisible.
    public static func sharedLayerAlpha(_ progress: Double) -> Double {
        let p = min(max(progress, 0), 1)
        return (p <= 0.001 || p >= 0.999) ? 0 : 1
    }

    /// 1:1 finger mapping: raw drag travel (pt) → morph progress. Downward
    /// finger travel from the sheet and upward travel from the dock both map
    /// monotonically onto [0, 1] over `travelPt`.
    public static func dragToProgress(_ dragPt: Double, _ travelPt: Double) -> Double {
        guard travelPt > 0 else { return 0 }
        return min(max(dragPt / travelPt, 0), 1)
    }

    // ─ Horizontal swipe-to-skip resistance ────────────────────────────────

    /// 80 pt trigger threshold for the mini-player swipe-to-skip gesture.
    public static let skipTrigger: Double = 80

    /// Spring-resisted visual offset for the mini-player horizontal swipe:
    /// exactly 1:1 with the finger inside the trigger threshold, then a
    /// smooth saturating tanh tail beyond it so the card feels increasingly
    /// reluctant to leave — subtle resistance without dead motion.
    public static func swipeResistanceOffset(rawDragPt: Double, thresholdPt: Double) -> Double {
        guard thresholdPt > 0 else { return 0 }
        let sign = rawDragPt < 0 ? -1.0 : 1.0
        let mag = abs(rawDragPt)
        let offset: Double
        if mag <= thresholdPt {
            offset = mag
        } else {
            // C1-continuous tail: value & slope match the linear segment at
            // the seam (threshold, threshold) → tanh blended beyond it.
            offset = thresholdPt + thresholdPt * 0.75
                * tanh((mag - thresholdPt) / (thresholdPt * 0.75))
        }
        return sign * offset
    }

    /// Peek-preview reveal fraction (0…1) for the upcoming/previous track
    /// title: dead zone until 55 % of the trigger threshold, then a
    /// smoothstep ramp that saturates at ~85 % of it — the preview is fully
    /// legible exactly when the arming haptic fires.
    public static func swipePeekFraction(rawDragPt: Double, thresholdPt: Double) -> Double {
        guard thresholdPt > 0 else { return 0 }
        let mag = abs(rawDragPt)
        let start = thresholdPt * 0.55
        guard mag > start else { return 0 }
        let t = min(max((mag - start) / (thresholdPt * 0.30), 0), 1)
        return smoothstep(t)
    }

    // ─ Hero geometry fallback ─────────────────────────────────────────────

    /// Fallback hero rect (view-coordinate pt) used for the first frame of an
    /// upward dock drag, before the freshly-composed sheet reports its real
    /// hero geometry: centered horizontally, sized to the 88 %-width / hero
    /// cap, sitting at the upper-third of the viewport.
    public static func fallbackHeroRect(
        screenW: Double,
        screenH: Double,
        heroMaxPt: Double
    ) -> CGRect {
        let size = min(screenW * 0.88, heroMaxPt)
        let cx = screenW / 2
        let cy = screenH * 0.36
        return CGRect(
            x: cx - size / 2,
            y: cy - size / 2,
            width: size,
            height: size
        )
    }
}
