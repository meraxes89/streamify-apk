package com.streamify.app.ui.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.streamify.app.ui.components.TrackCoverArt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Single funnel for the MiniPlayer <-> FullPlayer liquid morph.
 *
 * Holds the morph progress as an [Animatable] (0f = collapsed dock,
 * 1f = expanded sheet). EVERY consumer — sheet container transform, mini
 * dock response, shared cover layer — reads `progress.value` inside
 * graphicsLayer / draw lambdas, so a 120Hz morph stream never recomposes
 * anything: the entire choreography executes in the layout/draw phases.
 *
 * Gesture producers (mini vertical drag, sheet collapse zone, and later the
 * Android 14+ predictive back gesture) all route their deltas through
 * [dragBy] so the finger owns the geometry 1:1; release decisions go
 * through the [GestureSpringSolver].
 */
@Stable
class LiquidMorphController(
    private val scope: CoroutineScope,
    /** Notifies the host whenever the *composition* target flips. */
    private val onExpansionTarget: (expanded: Boolean) -> Unit
) {
    /** Morph progress. Read ONLY inside deferred (lambda) scopes. */
    val progress = Animatable(0f)

    /**
     * Root-coordinate rect of the mini dock's 48dp cover, reported by the
     * real node via onGloballyPositioned. Layout coordinates only — the
     * dock's own graphicsLayer response to the morph is excluded, which is
     * exactly what the shared layer needs (endpoint geometry).
     */
    val miniCoverRect: MutableState<Rect> = mutableStateOf(Rect.Zero)

    /**
     * Root-coordinate rect of the sheet's hero art, reported by the real
     * node. Empty while the sheet has never been laid out; the shared layer
     * substitutes [LiquidMorphGeometry.fallbackHeroRect] for that first frame.
     */
    val heroCoverRect: MutableState<Rect> = mutableStateOf(Rect.Zero)

    /** Screen metrics refresh; used for travel calibration + fallback hero. */
    var screenWpx: Float = 1080f
    var screenHpx: Float = 2160f
    var heroMaxPx: Float = 960f   // 320dp at a default 3x density until calibrate()

    /** Vertical drag travel (px) mapped 1:1 onto the full morph range. */
    var travelPx: Float = 1200f
        private set

    fun calibrate(screenW: Float, screenH: Float, density: Float) {
        screenWpx = screenW
        screenHpx = screenH
        heroMaxPx = LiquidMorphGeometry.HERO_ART_MAX_DP * density
        travelPx = (screenH * 0.55f).coerceAtLeast(240f)
        if (heroCoverRect.value.isEmpty) {
            heroCoverRect.value =
                LiquidMorphGeometry.fallbackHeroRect(screenW, screenH, heroMaxPx)
        }
    }

    // ── Composition-intent plumbing ───────────────────────────────────────

    /** Programmatic expand (dock tap, deep link, remote command). */
    fun expand(velocityPerSec: Float = 0f) {
        onExpansionTarget(true)
        scope.launch {
            val v = if (velocityPerSec == 0f) 1.6f else velocityPerSec
            progress.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = GestureSpringSolver.SETTLE_DAMPING,
                    stiffness = GestureSpringSolver.SETTLE_STIFFNESS
                ),
                initialVelocity = v
            )
        }
    }

    /** Programmatic collapse (chevron button, root back policy). */
    fun collapse(velocityPerSec: Float = 0f) {
        scope.launch {
            progress.animateTo(
                targetValue = 0f,
                animationSpec = spring(
                    dampingRatio = GestureSpringSolver.FLING_DAMPING,
                    stiffness = GestureSpringSolver.SETTLE_STIFFNESS
                ),
                initialVelocity = velocityPerSec
            )
            onExpansionTarget(false)
        }
    }

    // ── Gesture funnel ────────────────────────────────────────────────────

    /**
     * A finger gesture begins. [fromExpanded] tells the controller which
     * stable endpoint the gesture started at: an upward drag launched from
     * the collapsed dock must compose the sheet immediately (so the hero
     * reports its geometry) before progress can rise.
     */
    fun beginGesture(fromExpanded: Boolean) {
        if (!fromExpanded) {
            onExpansionTarget(true)
            // Sheet composes at progress 0 (invisible); seed the hero rect
            // with the spec fallback so the shared layer is correct on frame one.
            if (heroCoverRect.value.isEmpty) {
                heroCoverRect.value =
                    LiquidMorphGeometry.fallbackHeroRect(screenWpx, screenHpx, heroMaxPx)
            }
        }
        // The first dragBy's snapTo cancels any in-flight animateTo; the
        // cancelled settle coroutine's expansion callback is correctly skipped.
    }

    /**
     * 1:1 finger tracking: positive deltas expand, negative collapse.
     * Runs as a mutation on the Animatable so it also cancels in-flight
     * programmatic animations the moment the finger takes over.
     */
    fun dragBy(deltaPx: Float) {
        val delta = deltaPx / travelPx
        if (delta == 0f) return
        scope.launch {
            progress.snapTo((progress.value + delta).coerceIn(0f, 1f))
        }
    }

    /** Set progress directly (scrub-style callers). */
    fun snapProgressTo(value: Float) {
        scope.launch {
            progress.snapTo(value.coerceIn(0f, 1f))
        }
    }

    /**
     * Finger lifted: solve the landing with the spring physics. Positive px
     * velocity = expanding. The settle's completion flips the composition
     * target, so the sheet disposes only after it has fully re-docked.
     */
    fun endGesture(velocityPxPerSec: Float) {
        val v = (velocityPxPerSec / travelPx).coerceIn(-6f, 6f)
        scope.launch {
            val plan = GestureSpringSolver.solveSnap(progress.value, v)
            progress.animateTo(
                targetValue = plan.target,
                animationSpec = spring(
                    dampingRatio = plan.dampingRatio,
                    stiffness = plan.stiffness
                ),
                initialVelocity = v
            )
            onExpansionTarget(plan.target > 0.5f)
        }
    }

    /** Gesture cancelled (system stole the stream): settle to nearest end. */
    fun cancelGesture() {
        endGesture(velocityPxPerSec = 0f)
    }

    /** True when the morph is visibly in flight (cheap, non-snapshot poll). */
    fun isInFlight(): Boolean = progress.value > 0.001f && progress.value < 0.999f

    /** Absolute gesture velocity clamp helper for callers. */
    fun velocityToProgressPerSec(velocityPxPerSec: Float): Float =
        (velocityPxPerSec / travelPx).coerceIn(-6f, 6f)

    companion object {
        /** |velocity| beyond which a release commits direction outright. */
        const val FLING_COMMIT_VELOCITY_PX_PER_SEC = 2200f
    }
}

/**
 * Shared-element cover morph layer.
 *
 * Renders the current artwork ONCE at hero resolution and lets the GPU scale
 * it between the 48dp mini endpoint and the 320dp hero endpoint (downscaling
 * stays crisp; upscaling from 48dp would not). All geometry — interpolated
 * center, scale, corner radius 8dp -> 24dp, alpha — is computed inside the
 * graphicsLayer lambda from [LiquidMorphController.progress] State reads:
 * ZERO recompositions for the whole morph, at any frame rate.
 *
 * Compose it above the full-player overlay (higher zIndex) whenever the
 * sheet is composed; it self-hides at both stable endpoints.
 */
@Composable
fun SharedCoverMorphLayer(
    controller: LiquidMorphController,
    coverArtPath: String?,
    title: String,
    artist: String,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    // Natural render size = hero endpoint width (fallback: 320dp spec).
    val heroPx = controller.heroCoverRect.value.width.takeIf { it > 1f }
        ?: (LiquidMorphGeometry.HERO_ART_MAX_DP * density.density)
    val natural = with(density) { heroPx.toDp() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .zIndex(20f)
    ) {
        Box(
            modifier = Modifier
                .size(natural)
                .graphicsLayer {
                    val p = controller.progress.value
                    val mini = controller.miniCoverRect.value
                    var hero = controller.heroCoverRect.value
                    if (hero.isEmpty || hero.width <= 1f) {
                        hero = LiquidMorphGeometry.fallbackHeroRect(
                            controller.screenWpx, controller.screenHpx, controller.heroMaxPx
                        )
                    }
                    if (mini.isEmpty || mini.width <= 1f) {
                        alpha = 0f
                        return@graphicsLayer
                    }
                    alpha = LiquidMorphGeometry.sharedLayerAlpha(p)
                    if (alpha <= 0.01f) return@graphicsLayer

                    // Strictly linear interpolation: the finger owns the
                    // geometry 1:1 — no easing on the shared element itself.
                    val cx = LiquidMorphGeometry.lerp(mini.center.x, hero.center.x, p)
                    val cy = LiquidMorphGeometry.lerp(mini.center.y, hero.center.y, p)
                    val targetSize = LiquidMorphGeometry.lerp(mini.width, hero.width, p)
                    val scale = targetSize / heroPx

                    // Layer is laid out at host top-start: translate so the
                    // scaled box's center lands on the interpolated center.
                    translationX = cx - (heroPx * scale) / 2f
                    translationY = cy - (heroPx * scale) / 2f
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin.Center

                    // On-screen corner = lerp(8dp, 24dp, p). The layer is
                    // GPU-scaled, so the shape radius is normalized by the
                    // scale to stay perceptually 1:1 with the finger.
                    // (GraphicsLayerScope is a Density: .dp resolves here.)
                    val cornerDp = LiquidMorphGeometry.coverCornerRadiusDp(p) / scale
                    shape = RoundedCornerShape(cornerDp.dp)
                    clip = true
                }
        ) {
            TrackCoverArt(
                coverArtPath = coverArtPath,
                title = title,
                artist = artist,
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(LiquidMorphGeometry.HERO_CORNER_DP.dp)
            )
        }
    }
}

/**
 * Convenience modifier: reports a node's root-coordinate bounds into a
 * controller-held rect. Attach to the mini cover and the sheet hero art.
 */
fun Modifier.reportBoundsTo(
    rect: MutableState<Rect>
): Modifier = this.onGloballyPositioned { coords ->
    rect.value = coords.boundsInRoot()
}

/** Throttle-style helper: true when |v| commits a direction outright. */
fun commitsByVelocity(velocityPxPerSec: Float): Boolean =
    abs(velocityPxPerSec) >= LiquidMorphController.FLING_COMMIT_VELOCITY_PX_PER_SEC
