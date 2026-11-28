package com.streamify.graphics.tokens

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 3-Layer design token hierarchy for the Sovereign GPU Engine
 * (docs/gpu/ARCHITECTURE.md, section 5 — Design Token Integration).
 *
 * Layering rules — this file is frozen public API (`docs/gpu/PHASED_PLAN.md`,
 * Phase 1-B). Do not rename, retype or repurpose existing tokens; additive
 * changes only.
 *
 *  1. [PrimitiveTokens] — raw, context-free values (color alphas, lens
 *     curvature floats, blur radii, refraction constants). Never consumed
 *     directly by composables; referenced by [SemanticTokens] and by the
 *     Phase 2+ platform shader adapters.
 *  2. [SemanticTokens] — intent-level tokens (what a glass surface *means*,
 *     e.g. active vs muted). Built exclusively from [PrimitiveTokens].
 *  3. [ComponentTokens] — per-component geometry (insets, border widths,
 *     corner radii for cards, nav bars and mood pills). This is the only
 *     layer UI composables may consume directly.
 */

/**
 * Layer 1 — Raw, context-free primitives.
 *
 * These are the only place raw floats / [Dp] magnitudes live. Every value
 * here is consumed either by [SemanticTokens] or by the shader adapters
 * (Phase 2: `LiquidGlassCore.sksl` uniforms) — never by a composable.
 */
object PrimitiveTokens {

    // ------------------------------------------------------------------
    // Surface color alphas
    // ------------------------------------------------------------------

    /** Fill alpha for interactive / focused glass surfaces. */
    const val surfaceAlphaActive: Float = 0.42f

    /** Fill alpha for resting / de-emphasized glass surfaces. */
    const val surfaceAlphaMuted: Float = 0.24f

    /** Alpha of the specular rim highlight stroke drawn along glass edges. */
    const val specularHighlightAlpha: Float = 0.55f

    /**
     * Opacity of the Tier 3 / Tier 4 failover acrylic scrim. The legacy path
     * runs zero blur passes, so the scrim must carry the surface alone.
     */
    const val legacyScrimAlpha: Float = 0.72f

    // ------------------------------------------------------------------
    // Lens curvature (normalized: fraction of the half-min dimension that
    // bends at the surface boundary; 0 = flat, 0.5 = full hemisphere)
    // ------------------------------------------------------------------

    /** Strong edge refraction — Tier 1 hero surfaces (player card, lyrics). */
    const val lensCurvatureStrong: Float = 0.24f

    /** Standard edge refraction — default for Tier 1 / Tier 2 surfaces. */
    const val lensCurvatureStandard: Float = 0.16f

    /** Subtle edge refraction — small chips / pills where a strong lens reads as noise. */
    const val lensCurvatureSubtle: Float = 0.08f

    /** Flat edge — Tier 3 / Tier 4 (no refraction math at all). */
    const val lensCurvatureNone: Float = 0.0f

    // ------------------------------------------------------------------
    // Refraction / dispersion constants (mirror the LiquidGlassCore.sksl
    // uniform contract in docs/gpu/ARCHITECTURE.md section 2)
    // ------------------------------------------------------------------

    /** Index of refraction applied at the lens boundary (canonical value 1.25). */
    const val refractionIndexDefault: Float = 1.25f

    /** Chromatic dispersion delta — Tier 1 only; 0 disables the 3-tap split. */
    const val chromaticSpreadDefault: Float = 0.015f

    /** Schlick Fresnel rim intensity multiplier applied at grazing angles. */
    const val fresnelIntensityDefault: Float = 0.35f

    // ------------------------------------------------------------------
    // Blur radii
    // ------------------------------------------------------------------

    /** Tier 1 (Ultra) backdrop blur radius. */
    val blurRadiusUltra: Dp = 32.dp

    /** Tier 1 / Tier 2 high backdrop blur radius (large hero surfaces). */
    val blurRadiusHigh: Dp = 24.dp

    /** Tier 2 (Balanced) default backdrop blur radius. */
    val blurRadiusMedium: Dp = 18.dp

    /** Tier 2 low backdrop blur radius (small surfaces, pills, chips). */
    val blurRadiusLow: Dp = 12.dp

    /** Tier 3 / Tier 4 — zero blur passes by design. */
    val blurRadiusNone: Dp = 0.dp

    // ------------------------------------------------------------------
    // Kyant optical parity (S24) — the five-pillar constants mirrored from
    // composeApp's io.github.kyant0:backdrop reference
    // (LiquidGlassContainer.kt). Additive only; every pre-existing token
    // above is untouched.
    // ------------------------------------------------------------------

    /**
     * Luminance-preserving saturation multiplier applied to the sampled
     * backdrop before the tint mix (Kyant `colorControls(saturation = 1.5)`).
     * 1.5x deepens chroma without shifting luminance, preventing the
     * milky-white washout over bright artwork.
     */
    const val vibrancySaturation: Float = 1.5f

    /** Darkening ramp floor — the scrim alpha over pitch-black content. */
    const val scrimDarkenMin: Float = 0.12f

    /** Darkening ramp ceiling — the scrim alpha over pure-white content. */
    const val scrimDarkenMax: Float = 0.5f

    /** Backdrop luminance where the "đục đen" ramp starts rising. */
    const val scrimLuminanceFloor: Float = 0.3f

    /** Backdrop luminance span over which the ramp rises from floor to ceiling. */
    const val scrimLuminanceRange: Float = 0.5f

    /**
     * Fixed mid-luminance default for surfaces that do not sample their
     * background (Kyant's non-adapting `liquidGlass` overload pins 0.5).
     */
    const val defaultBackdropLuminance: Float = 0.5f

    /**
     * Directional 45° rim strength bound to the shader's
     * `specularIntensity` uniform. Same magnitude as
     * [fresnelIntensityDefault] — the Schlick envelope is unchanged; only
     * the rim's angular distribution became directional.
     */
    const val directionalSpecularIntensity: Float = 0.35f

    // ------------------------------------------------------------------
    // Fluid press interaction (S24 Track C) — spring & glow constants
    // ------------------------------------------------------------------

    /** Press swell scale target (Kyant press target 1.12x). */
    const val pressScaleSwell: Float = 1.12f

    /** Press spring damping ratio (Kyant press spec 0.5 — underdamped). */
    const val pressSpringDampingRatio: Float = 0.5f

    /** Press spring stiffness (Kyant press spec 300). */
    const val pressSpringStiffness: Float = 300f

    /** Peak alpha of the touch-tracking radial glow. */
    const val pressGlowAlpha: Float = 0.18f

    /** Glow radius as a fraction of the surface's min dimension (1.2x). */
    const val pressGlowRadiusFraction: Float = 1.2f

    // ------------------------------------------------------------------
    // Rim stroke
    // ------------------------------------------------------------------

    /**
     * Width of the optical highlight stroke. Tier 3 pins the "1px optical
     * highlight stroke" contract from docs/gpu/ARCHITECTURE.md section 3.
     */
    val specularStrokeWidth: Dp = 1.dp
}

/**
 * Layer 2 — Intent-level tokens. Built exclusively from [PrimitiveTokens];
 * the single place a "what the surface means" decision is encoded.
 */
object SemanticTokens {

    /** Translucent cool-white fill for interactive / focused glass surfaces. */
    val surfaceGlassActive: Color = Color(
        red = 0.92f,
        green = 0.95f,
        blue = 1.00f,
        alpha = PrimitiveTokens.surfaceAlphaActive,
    )

    /** Softer fill for resting / de-emphasized glass surfaces. */
    val surfaceGlassMuted: Color = Color(
        red = 0.90f,
        green = 0.93f,
        blue = 0.99f,
        alpha = PrimitiveTokens.surfaceAlphaMuted,
    )

    /** Bright rim stroke drawn along the glass boundary (lens edge specular). */
    val specularBorderHighlight: Color = Color(
        red = 1.00f,
        green = 1.00f,
        blue = 1.00f,
        alpha = PrimitiveTokens.specularHighlightAlpha,
    )

    /**
     * Tier 3 / Tier 4 failover scrim: a translucent dark veil that keeps glass
     * surfaces legible when every shader pipeline is bypassed (Vivo 1712 /
     * Android 8.1, software rasterizers, shader-compile failure).
     */
    val legacyAcrylicScrim: Color = Color(
        red = 0.07f,
        green = 0.09f,
        blue = 0.13f,
        alpha = PrimitiveTokens.legacyScrimAlpha,
    )

    /**
     * Touch-tracking press glow (S24 Track C): radial white light drawn with
     * `BlendMode.Plus` at the pointer position while a glass surface is
     * pressed. Peak alpha comes from [PrimitiveTokens.pressGlowAlpha] and is
     * scaled by the spring-animated press progress at draw time.
     */
    val pressGlowHighlight: Color = Color(
        red = 1.00f,
        green = 1.00f,
        blue = 1.00f,
        alpha = PrimitiveTokens.pressGlowAlpha,
    )

    /**
     * Shaded-side stop of the Tier 3 dual-stop directional rim gradient
     * (S24 Track D): the bottom-right boundary of the 45° sweep, sitting at
     * 25% of the lit stop's alpha — the hardware-gradient stand-in for the
     * shader's subtle ambient rim.
     */
    val specularRimShaded: Color = Color(
        red = 1.00f,
        green = 1.00f,
        blue = 1.00f,
        alpha = PrimitiveTokens.specularHighlightAlpha * 0.25f,
    )
}

/**
 * Layer 3 — Component geometry: insets, border widths and corner radii for
 * the glass surfaces of this design system. Composables consume only this
 * layer; everything visual is inherited from the layers below.
 */
object ComponentTokens {

    // ------------------------------------------------------------------
    // Glass cards (home shelves, queue sheets, detail headers)
    // ------------------------------------------------------------------

    /** Corner radius for glass cards. */
    val cardCornerRadius: Dp = 22.dp

    /** Glass card border (specular rim) width. */
    val cardBorderWidth: Dp = 1.dp

    /** Default content inset inside a glass card. */
    val cardContentInset: Dp = 16.dp

    /** Compact content inset for dense cards (compact lists, chips rows). */
    val cardContentInsetCompact: Dp = 12.dp

    // ------------------------------------------------------------------
    // Nav bars (mini player, bottom navigation)
    // ------------------------------------------------------------------

    /** Corner radius for floating nav bars. */
    val navBarCornerRadius: Dp = 26.dp

    /** Nav bar border (specular rim) width. */
    val navBarBorderWidth: Dp = 1.dp

    /** Gutter between a floating nav bar and the screen edge. */
    val navBarScreenInset: Dp = 12.dp

    /** Content inset inside a nav bar. */
    val navBarContentInset: Dp = 12.dp

    // ------------------------------------------------------------------
    // Mood pills (mood / genre selectors)
    // ------------------------------------------------------------------

    /**
     * Mood pill corner radius — stadium silhouette at the standard pill
     * height; lower it only for square-ish chips.
     */
    val moodPillCornerRadius: Dp = 16.dp

    /** Mood pill border (specular rim) width. */
    val moodPillBorderWidth: Dp = 1.dp

    /** Horizontal (leading/trailing) label inset inside a mood pill. */
    val moodPillHorizontalInset: Dp = 12.dp

    /** Vertical (top/bottom) label inset inside a mood pill. */
    val moodPillVerticalInset: Dp = 6.dp
}
