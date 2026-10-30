import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2-10 — Streamify motion: named spring curves & timing tokens.
//
// The Swift counterpart of ui/animations/{PlayerTransition,CardPressEffect,
// HeartBurstEffect}.kt + ui/theme/Dimens.kt StreamifyAnimations — every
// Android spec maps onto a native SwiftUI spring (Compose stiffness →
// response/dampingFraction). No Compose porting baggage: no Animatable
// plumbing, no LaunchedEffect — SwiftUI's animation system owns the
// interpolation; these tokens just NAME the feel, once, for the whole app.
//
// 120 Hz discipline (SW-ENG2-10): every curve here is spring/CA-driven
// (display-link synced); interactive gestures drive progress 1:1 with the
// finger and hand off to `Motion.settle`/`Motion.sheet` on release.
// ─────────────────────────────────────────────────────────────────────────────

public enum Motion {

    // ─ Duration tokens (Android StreamifyAnimations, ms → s) ─────────────
    public static let fastDuration: Double = 0.15
    public static let normalDuration: Double = 0.30
    public static let slowDuration: Double = 0.50
    public static let superSlowDuration: Double = 0.80

    /// Staggered shelf reveal: base delay + per-index increment (Android
    /// StaggerBase 50 ms / StaggerIncrement 30 ms, capped to stay snappy).
    public static func staggerDelay(_ index: Int) -> Double {
        Double(min(max(index, 0), 10)) * 0.03
    }

    // ─ Named springs ─────────────────────────────────────────────────────

    /// THE signature Streamify spring (the directive's
    /// `.spring(response: 0.35, dampingRatio: 0.85)` — Apple's param name
    /// is dampingFraction): the default for sheets, hero crossfades, chips.
    public static let fluid = Animation.spring(response: 0.35, dampingFraction: 0.85)

    /// Tight taps, toggles, segmented switches.
    public static let snappy = Animation.spring(response: 0.30, dampingFraction: 0.86)

    /// Sheet morphs & large-surface presentations.
    public static let sheet = Animation.spring(response: 0.48, dampingFraction: 0.86)

    /// Playful overshoot (Android StreamifyDamping 0.75): splash, daylist
    /// hero swap, blend confetti.
    public static let bouncy = Animation.spring(response: 0.42, dampingFraction: 0.75)

    /// Player surface transitions (Android PlayerTransition: NoBouncy /
    /// StiffnessMediumLow) — one continuous, overshoot-free handoff.
    public static let playerTransition = Animation.spring(response: 0.45, dampingFraction: 1.0)

    /// Card press release (Android DampingRatioLowBouncy / StiffnessLow):
    /// scale 0.95 with a gentle rebound.
    public static let cardPress = Animation.spring(response: 0.40, dampingFraction: 0.62)

    /// Pressed alpha decay (Android StiffnessHigh — near-instant).
    public static let pressAlpha = Animation.spring(response: 0.18, dampingFraction: 1.0)

    /// Heart-burst pop (Android HeartBurstEffect: HighBouncy / 1.2× scale).
    public static let heartBurst = Animation.spring(response: 0.34, dampingFraction: 0.55)

    /// Non-bouncy settle for gesture release returns (GestureSpringSolver's
    /// critically-damped fling counterpart).
    public static let settle = Animation.spring(response: 0.32, dampingFraction: 1.0)

    /// Bottom tab-bar fluid indicator (YtBottomNavBar's YtFluidNavItem).
    public static let tabBar = Animation.spring(response: 0.35, dampingFraction: 0.80)

    /// Rail/grid reveal with the shelf stagger baked in.
    public static func reveal(_ index: Int) -> Animation {
        Animation.spring(response: 0.42, dampingFraction: 0.85)
            .delay(staggerDelay(index))
    }

    // ─ Gesture math helpers ──────────────────────────────────────────────

    /// Apple's classic rubber-band: 1:1 inside the bounds, asymptotic
    /// resistance beyond (constant 0.55 = HIG scroll-feel softness). Powers
    /// the full-player sheet's dismiss drag and overscroll tails.
    public static func rubberBand(
        _ offset: Double,
        dimension: Double,
        constant: Double = 0.55
    ) -> Double {
        guard dimension > 0, abs(offset) > dimension else { return offset }
        let alpha = constant * (abs(offset) - dimension) / dimension
        let resisted = dimension * (1.0 - 1.0 / (alpha + 1.0)) + dimension
        return resisted * (offset < 0 ? -1 : 1)
    }
}
