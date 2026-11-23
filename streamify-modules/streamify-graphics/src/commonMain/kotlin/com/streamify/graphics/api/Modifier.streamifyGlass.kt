package com.streamify.graphics.api

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.layer.GraphicsLayer
import com.streamify.graphics.engine.LegacyRedirectGlassEngine
import org.koin.core.context.GlobalContext

/**
 * Frozen public facade of the Sovereign GPU Engine — the single entry point
 * UI composables use for Liquid Glass surfaces (docs/gpu/ARCHITECTURE.md,
 * section 5: "All composables consume `Modifier.streamifyGlass(backdrop,
 * shape, tint)`").
 *
 * Contract (frozen as of Phase 1 — additive changes only):
 *  * **Draw-phase only.** Implemented as `drawBehind`; applying the modifier
 *    never triggers recomposition, and the engine is resolved through Koin at
 *    most once per process (then cached).
 *  * **Never breaks a composable.** Until the Phase 2 platform adapters are
 *    registered (and whenever Koin has no engine bound), rendering silently
 *    redirects to the Tier-3-style [LegacyRedirectGlassEngine] fallback —
 *    clipped backdrop, optional tint, 1px specular rim.
 *  * The caller is responsible for capturing [backdrop] (e.g. with
 *    `rememberGraphicsLayer()` on the content behind the surface) and for
 *    clipping its own content if the design requires it.
 *
 * @param backdrop Pre-captured layer with the content behind the surface.
 * @param shape Clip geometry of the glass surface (see
 * `com.streamify.graphics.tokens.ComponentTokens` for standard radii).
 * @param tint Optional overlay fill; `Color.Unspecified` (default) skips the
 * tint layer. Standard fills live in
 * `com.streamify.graphics.tokens.SemanticTokens`.
 */
fun Modifier.streamifyGlass(
    backdrop: GraphicsLayer,
    shape: Shape,
    tint: Color = Color.Unspecified,
): Modifier {
    // Built once per composition, reused across every draw pass — no per-frame
    // allocation in the draw phase.
    val request = GlassRenderRequest(backdrop = backdrop, shape = shape, tint = tint)
    return drawBehind {
        SovereignGraphicsEngineLocator.engine().applyGlass(this, request)
    }
}

/**
 * Draw-phase resolver for the [SovereignGraphicsEngine] port.
 *
 * Resolution order:
 *  1. The engine bound in Koin (`streamifyGraphicsModule`, overridable by the
 *     Phase 2 tier-governed adapter module), cached after first success.
 *  2. [LegacyRedirectGlassEngine] while Koin is not yet started, has no
 *     binding, or resolution fails for any reason — a draw must never crash.
 *
 * The not-found path deliberately stays uncached so a `loadKoinModules(...)`
 * that happens after the first frame is still picked up.
 */
internal object SovereignGraphicsEngineLocator {

    @Volatile
    private var cached: SovereignGraphicsEngine? = null

    fun engine(): SovereignGraphicsEngine {
        cached?.let { return it }
        val resolved = runCatching {
            GlobalContext.get().get(SovereignGraphicsEngine::class)
        }.getOrNull()
        if (resolved != null) {
            cached = resolved
            return resolved
        }
        return LegacyRedirectGlassEngine
    }

    /** Drops the cached binding so the next draw re-resolves through Koin. */
    fun reset() {
        cached = null
    }
}
