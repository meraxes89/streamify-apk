package com.streamify.app.ui.motion

import kotlin.math.abs

/**
 * Pure geometry & easing math for the MiniPlayer <-> FullPlayer liquid sheet
 * morph. Everything here is a pure function of the morph progress
 * `Y in [0f, 1f]` (0 = collapsed dock, 1 = expanded sheet) so it is fully
 * unit-testable on the JVM and never touches Compose state.
 *
 * The MiniPlayer endpoint follows the design spec: 64dp bar height, 48dp
 * cover thumbnail, 8dp corner radius. The FullPlayer endpoint: 320dp hero
 * art, 24dp corner radius.
 */
object LiquidMorphGeometry {

    // ── Design-spec endpoints (dp) ────────────────────────────────────────
    const val MINI_BAR_HEIGHT_DP = 64f
    const val MINI_COVER_SIZE_DP = 48f
    const val MINI_COVER_CORNER_DP = 8f
    const val HERO_ART_MAX_DP = 320f
    const val HERO_CORNER_DP = 24f

    // ── Interpolation primitives ──────────────────────────────────────────

    fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    /** Decelerating ease used for the sheet's own entrance transform. */
    fun easeOutCubic(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        val inv = 1f - x
        return 1f - inv * inv * inv
    }

    /** Hermite smoothstep — S-curve used for alpha crossfades. */
    fun smoothstep(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    // ── Mini dock response ────────────────────────────────────────────────

    /**
     * Mini dock alpha: fully visible while collapsed, fully gone by 70% of
     * the morph — the shared cover layer takes over the artwork well before
     * the dock itself finishes fading.
     */
    fun miniBarAlpha(progress: Float): Float =
        (1f - (progress.coerceIn(0f, 1f) / 0.7f)).coerceIn(0f, 1f)

    /**
     * Mini dock upward lift fraction (of bar height): the dock slides up and
     * out of the way as the sheet grows over it, bottom-anchored.
     */
    fun miniBarLiftFraction(progress: Float): Float =
        easeOutCubic(progress) * 0.35f

    // ── Sheet container response ──────────────────────────────────────────

    /** Sheet opacity: reaches full opacity at ~55% progress. */
    fun sheetAlpha(progress: Float): Float =
        smoothstep(progress.coerceIn(0f, 1f) / 0.55f)

    /** Sheet scale: grows from 92% to 100%, bottom-anchored. */
    fun sheetScale(progress: Float): Float =
        0.92f + 0.08f * easeOutCubic(progress)

    /**
     * Sheet vertical offset as a fraction of screen height: starts 22% below
     * its resting place and rises into position. Linear in progress so the
     * finger owns the motion 1:1 during a drag (no double-easing).
     */
    fun sheetTranslationFraction(progress: Float): Float =
        (1f - progress.coerceIn(0f, 1f)) * 0.22f

    // ── Shared cover element ──────────────────────────────────────────────

    /**
     * Interpolated shared-cover corner radius in dp between the 8dp mini
     * endpoint and the 24dp hero endpoint. Linear: radius is a small detail
     * and stays perceptually 1:1 with the finger.
     */
    fun coverCornerRadiusDp(progress: Float): Float =
        lerp(MINI_COVER_CORNER_DP, HERO_CORNER_DP, progress.coerceIn(0f, 1f))

    /**
     * Shared-layer alpha: the morphing cover layer owns the artwork strictly
     * inside the open interval — at the endpoints the real nodes (mini cover
     * / sheet hero) are pixel-exact owners, so a hard cut is invisible.
     */
    fun sharedLayerAlpha(progress: Float): Float {
        val p = progress.coerceIn(0f, 1f)
        return if (p <= 0.001f || p >= 0.999f) 0f else 1f
    }

    /**
     * 1:1 finger mapping: raw drag travel (px) -> morph progress. Downward
     * finger travel from the sheet and upward travel from the dock both map
     * monotonically onto [0, 1] over [travelPx].
     */
    fun dragToProgress(dragPx: Float, travelPx: Float): Float {
        if (travelPx <= 0f) return 0f
        return (dragPx / travelPx).coerceIn(0f, 1f)
    }

    // ── Horizontal swipe-to-skip resistance ───────────────────────────────

    /** 80dp trigger threshold for the mini-player swipe-to-skip gesture. */
    const val SKIP_TRIGGER_DP = 80f

    /**
     * Spring-resisted visual offset for the mini-player horizontal swipe:
     * exactly 1:1 with the finger inside the trigger threshold, then a
     * smooth saturating tanh tail beyond it so the card feels increasingly
     * reluctant to leave — subtle resistance without dead motion.
     */
    fun swipeResistanceOffset(rawDragPx: Float, thresholdPx: Float): Float {
        if (thresholdPx <= 0f) return 0f
        val raw = rawDragPx
        val sign = if (raw < 0f) -1f else 1f
        val mag = abs(raw)
        val offset = if (mag <= thresholdPx) {
            mag
        } else {
            // C1-continuous tail: value & slope match the linear segment at
            // the seam (threshold, threshold) -> tanh blended beyond it.
            thresholdPx + thresholdPx * 0.75f *
                    kotlin.math.tanh((mag - thresholdPx) / (thresholdPx * 0.75f))
        }
        return sign * offset
    }

    /**
     * Peek-preview reveal fraction (0..1) for the upcoming/previous track
     * title: dead zone until 55% of the trigger threshold, then a smoothstep
     * ramp that saturates at ~85% of it — the preview is fully legible
     * exactly when the arming haptic fires.
     */
    fun swipePeekFraction(rawDragPx: Float, thresholdPx: Float): Float {
        if (thresholdPx <= 0f) return 0f
        val mag = abs(rawDragPx)
        val start = thresholdPx * 0.55f
        if (mag <= start) return 0f
        val t = ((mag - start) / (thresholdPx * 0.30f)).coerceIn(0f, 1f)
        return smoothstep(t)
    }

    /**
     * Fallback hero rect (root-coordinate px) used for the first frame of an
     * upward dock drag, before the freshly-composed sheet reports its real
     * hero geometry: centered horizontally, sized to the 88%-width / 320dp
     * cap spec, sitting at the upper-third of the viewport.
     */
    fun fallbackHeroRect(
        screenW: Float,
        screenH: Float,
        heroMaxPx: Float
    ): androidx.compose.ui.geometry.Rect {
        val size = (screenW * 0.88f).coerceAtMost(heroMaxPx)
        val cx = screenW / 2f
        val cy = screenH * 0.36f
        return androidx.compose.ui.geometry.Rect(
            left = cx - size / 2f,
            top = cy - size / 2f,
            right = cx + size / 2f,
            bottom = cy + size / 2f
        )
    }
}
