package com.streamify.app.ui.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * LIQUID MORPH GEOMETRY TESTS (pure JVM)
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Pins the sheet-morph interpolation contract: endpoint exactness at
 * progress 0/1, monotonicity across the sweep, the 8dp -> 24dp corner
 * radius lerp, the alpha crossfade curves, the swipe-to-skip resistance
 * function (C1 continuity at the 80dp seam + saturating tail), and the
 * peek-preview reveal fraction.
 */
class LiquidMorphGeometryTest {

    private val eps = 1e-4f

    // ── Endpoints ──────────────────────────────────────────────────────────

    @Test
    fun `design spec endpoints are pinned`() {
        assertEquals(64f, LiquidMorphGeometry.MINI_BAR_HEIGHT_DP, 0f)
        assertEquals(48f, LiquidMorphGeometry.MINI_COVER_SIZE_DP, 0f)
        assertEquals(8f, LiquidMorphGeometry.MINI_COVER_CORNER_DP, 0f)
        assertEquals(320f, LiquidMorphGeometry.HERO_ART_MAX_DP, 0f)
        assertEquals(24f, LiquidMorphGeometry.HERO_CORNER_DP, 0f)
        assertEquals(80f, LiquidMorphGeometry.SKIP_TRIGGER_DP, 0f)
    }

    // ── Easing primitives ───────────────────────────────────────────────────

    @Test
    fun `lerp interpolates linearly`() {
        assertEquals(0f, LiquidMorphGeometry.lerp(0f, 100f, 0f), eps)
        assertEquals(100f, LiquidMorphGeometry.lerp(0f, 100f, 1f), eps)
        assertEquals(50f, LiquidMorphGeometry.lerp(0f, 100f, 0.5f), eps)
    }

    @Test
    fun `easing endpoints are exact and clamped`() {
        assertEquals(0f, LiquidMorphGeometry.easeOutCubic(0f), eps)
        assertEquals(1f, LiquidMorphGeometry.easeOutCubic(1f), eps)
        assertEquals(1f, LiquidMorphGeometry.easeOutCubic(2.5f), eps)   // clamped
        assertEquals(0f, LiquidMorphGeometry.smoothstep(0f), eps)
        assertEquals(1f, LiquidMorphGeometry.smoothstep(1f), eps)
        assertEquals(0.5f, LiquidMorphGeometry.smoothstep(0.5f), eps)   // hermite midpoint
    }

    // ── Mini dock response ─────────────────────────────────────────────────

    @Test
    fun `mini bar alpha is full collapsed and gone by 70 percent`() {
        assertEquals(1f, LiquidMorphGeometry.miniBarAlpha(0f), eps)
        assertEquals(0.5f, LiquidMorphGeometry.miniBarAlpha(0.35f), eps)
        assertEquals(0f, LiquidMorphGeometry.miniBarAlpha(0.7f), eps)
        assertEquals(0f, LiquidMorphGeometry.miniBarAlpha(1f), eps)
        assertEquals(0f, LiquidMorphGeometry.miniBarAlpha(1.4f), eps)   // clamped
    }

    @Test
    fun `mini bar lift is monotonic and bounded`() {
        var prev = -1f
        for (i in 0..10) {
            val p = i / 10f
            val lift = LiquidMorphGeometry.miniBarLiftFraction(p)
            assertTrue(lift >= prev - eps)
            assertTrue(lift <= 0.35f + eps)
            prev = lift
        }
        assertEquals(0f, LiquidMorphGeometry.miniBarLiftFraction(0f), eps)
        assertEquals(0.35f, LiquidMorphGeometry.miniBarLiftFraction(1f), eps)
    }

    // ── Sheet container response ────────────────────────────────────────────

    @Test
    fun `sheet alpha is monotonic and full before the end`() {
        var prev = -1f
        for (i in 0..20) {
            val a = LiquidMorphGeometry.sheetAlpha(i / 20f)
            assertTrue(a >= prev - eps)
            prev = a
        }
        assertEquals(0f, LiquidMorphGeometry.sheetAlpha(0f), eps)
        assertEquals(1f, LiquidMorphGeometry.sheetAlpha(0.55f), eps)    // full at 55%
        assertEquals(1f, LiquidMorphGeometry.sheetAlpha(1f), eps)
    }

    @Test
    fun `sheet scale grows from 92 to 100 percent`() {
        assertEquals(0.92f, LiquidMorphGeometry.sheetScale(0f), eps)
        assertEquals(1f, LiquidMorphGeometry.sheetScale(1f), eps)
        assertTrue(LiquidMorphGeometry.sheetScale(0.5f) > 0.92f)
        assertTrue(LiquidMorphGeometry.sheetScale(0.5f) < 1f)
    }

    @Test
    fun `sheet translation is linear for 1 to 1 finger ownership`() {
        assertEquals(0.22f, LiquidMorphGeometry.sheetTranslationFraction(0f), eps)
        assertEquals(0.11f, LiquidMorphGeometry.sheetTranslationFraction(0.5f), eps)  // linear midpoint
        assertEquals(0f, LiquidMorphGeometry.sheetTranslationFraction(1f), eps)
    }

    // ── Shared cover element ────────────────────────────────────────────────

    @Test
    fun `cover corner radius lerps from 8dp to 24dp`() {
        assertEquals(8f, LiquidMorphGeometry.coverCornerRadiusDp(0f), eps)
        assertEquals(16f, LiquidMorphGeometry.coverCornerRadiusDp(0.5f), eps)
        assertEquals(24f, LiquidMorphGeometry.coverCornerRadiusDp(1f), eps)
        assertEquals(8f, LiquidMorphGeometry.coverCornerRadiusDp(-0.5f), eps)   // clamped
        assertEquals(24f, LiquidMorphGeometry.coverCornerRadiusDp(1.5f), eps)
    }

    @Test
    fun `shared layer owns only the open interval`() {
        assertEquals(0f, LiquidMorphGeometry.sharedLayerAlpha(0f), eps)
        assertEquals(0f, LiquidMorphGeometry.sharedLayerAlpha(1f), eps)
        assertEquals(1f, LiquidMorphGeometry.sharedLayerAlpha(0.0011f), eps)
        assertEquals(1f, LiquidMorphGeometry.sharedLayerAlpha(0.5f), eps)
        assertEquals(1f, LiquidMorphGeometry.sharedLayerAlpha(0.9989f), eps)
    }

    @Test
    fun `drag to progress is clamped and monotonic`() {
        assertEquals(0f, LiquidMorphGeometry.dragToProgress(-50f, 600f), eps)
        assertEquals(0.5f, LiquidMorphGeometry.dragToProgress(300f, 600f), eps)
        assertEquals(1f, LiquidMorphGeometry.dragToProgress(900f, 600f), eps)
        assertEquals(0f, LiquidMorphGeometry.dragToProgress(300f, 0f), eps)  // degenerate travel
    }

    // ── Swipe-to-skip resistance ────────────────────────────────────────────

    @Test
    fun `swipe resistance is 1 to 1 inside the threshold`() {
        val t = 200f
        assertEquals(50f, LiquidMorphGeometry.swipeResistanceOffset(50f, t), eps)
        assertEquals(200f, LiquidMorphGeometry.swipeResistanceOffset(200f, t), eps)
        assertEquals(-120f, LiquidMorphGeometry.swipeResistanceOffset(-120f, t), eps)
    }

    @Test
    fun `swipe resistance is continuous at the threshold seam`() {
        val t = 200f
        val justBelow = LiquidMorphGeometry.swipeResistanceOffset(t - 0.01f, t)
        val justAbove = LiquidMorphGeometry.swipeResistanceOffset(t + 0.01f, t)
        assertEquals(justBelow, justAbove, 0.5f)   // C0/C1 continuity
    }

    @Test
    fun `swipe resistance saturates beyond the threshold`() {
        val t = 200f
        val at1x = LiquidMorphGeometry.swipeResistanceOffset(t, t)
        val at2x = LiquidMorphGeometry.swipeResistanceOffset(2f * t, t)
        val at4x = LiquidMorphGeometry.swipeResistanceOffset(4f * t, t)
        // Growth strictly decelerates: (2x-1x) > (4x-2x)
        assertTrue(at2x - at1x > at4x - at2x)
        // And the tail is bounded: extra beyond the seam never exceeds 0.75*threshold.
        assertTrue(at4x < t + 0.75f * t + eps)
    }

    @Test
    fun `swipe resistance preserves direction and zero`() {
        assertEquals(0f, LiquidMorphGeometry.swipeResistanceOffset(0f, 100f), 0f)
        assertTrue(LiquidMorphGeometry.swipeResistanceOffset(-500f, 100f) < 0f)
        assertTrue(LiquidMorphGeometry.swipeResistanceOffset(500f, 100f) > 0f)
    }

    @Test
    fun `peek fraction reveals only past half the threshold and saturates`() {
        val t = 200f
        assertEquals(0f, LiquidMorphGeometry.swipePeekFraction(0f, t), eps)
        assertEquals(0f, LiquidMorphGeometry.swipePeekFraction(50f, t), eps)      // dead zone (< 55%)
        assertEquals(0f, LiquidMorphGeometry.swipePeekFraction(110f, t), eps)     // exactly at the dead-zone edge
        assertTrue(LiquidMorphGeometry.swipePeekFraction(120f, t) > 0f)           // past the edge: revealing
        assertEquals(1f, LiquidMorphGeometry.swipePeekFraction(t, t), eps)        // saturated by threshold
        assertEquals(1f, LiquidMorphGeometry.swipePeekFraction(3f * t, t), eps)
    }

    // ── Fallback hero rect ──────────────────────────────────────────────────

    @Test
    fun `fallback hero rect is centered and capped`() {
        val r = LiquidMorphGeometry.fallbackHeroRect(
            screenW = 1000f, screenH = 2000f, heroMaxPx = 700f
        )
        assertEquals(700f, r.width, eps)               // 88% of 1000 capped at 700
        assertEquals(700f, r.height, eps)
        assertEquals(500f, r.center.x, eps)            // horizontally centered
        assertEquals(2000f * 0.36f, r.center.y, eps)
    }

    @Test
    fun `fallback hero rect uses width fraction when under the cap`() {
        val r = LiquidMorphGeometry.fallbackHeroRect(
            screenW = 500f, screenH = 1600f, heroMaxPx = 700f
        )
        assertEquals(500f * 0.88f, r.width, eps)
    }
}
