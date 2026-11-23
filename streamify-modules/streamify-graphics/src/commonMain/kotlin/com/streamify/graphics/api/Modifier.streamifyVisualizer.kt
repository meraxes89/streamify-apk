package com.streamify.graphics.api

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * Frozen public facade of the Sovereign GPU Engine audio visualizer
 * (docs/gpu/ARCHITECTURE.md, section 4 "Audio Visualizer Zero-Allocation
 * Draw Pipeline"; docs/gpu/PHASED_PLAN.md, Phase 3-B, Merge Gate 3).
 *
 * Contract (frozen as of Phase 3 — additive changes only):
 *  * **Draw-phase confinement (hard invariant).** Implemented exclusively
 *    as `Modifier.drawBehind`: [fftSource] is invoked *inside* the draw
 *    lambda, so every snapshot-state read it performs is observed by the
 *    Draw phase alone. Frequency data invalidates — at most — the draw
 *    pass: **the recomposition count stays strictly 0 during active
 *    playback** (pinned by `GraphicsTelemetryTest`). The facade reads no
 *    Compose state while composing.
 *  * **Zero per-draw allocation.** The render request (resolved colors +
 *    captured producer) is built once per composition and reused across
 *    every draw pass; the drawing loop allocates nothing — bars are
 *    rounded rects with inline value-class geometry and colors are mixed
 *    arithmetically (no `Brush`, no `Path`).
 *  * **Never breaks a draw.** Degenerate producer output — empty
 *    spectrum, NaN / infinite / negative magnitudes — is clamped or
 *    skipped, and a throwing producer degrades to a skipped frame rather
 *    than a crashed draw.
 *  * Callers must *capture* — not read — the FFT state when building
 *    [fftSource] (e.g. `streamifyVisualizer(fftSource = { fft.value })`).
 *    Reading the value while *composing* re-introduces a recomposition
 *    dependency and voids this guarantee.
 *
 * Phase 3-A (Track A) seam: once `visualizer/FftDataTexture.kt` and
 * `shaders/VisualizerShader.sksl` land, the GPU lane samples the uploaded
 * 1024x1 data texture in the fragment shader *behind this exact facade* —
 * the public signature stays frozen and only the internal draw lane is
 * swapped. Until then the draw phase runs the CPU spectrum lane below,
 * which shares the invalidation semantics and the zero-recomposition
 * proof.
 *
 * @param fftSource Zero-argument producer returning the current magnitude
 * spectrum; magnitudes are clamped to `[0..1]` (typical normalized FFT
 * output). Invoked exactly once per draw pass, on the draw thread.
 * @param primaryColor Low-energy end of the spectrum ramp;
 * `Color.Unspecified` (default) falls back to [SpectrumDefaults.primaryColor].
 * @param accentColor High-energy end of the spectrum ramp;
 * `Color.Unspecified` (default) falls back to [SpectrumDefaults.accentColor].
 */
fun Modifier.streamifyVisualizer(
    fftSource: () -> FloatArray,
    primaryColor: Color = Color.Unspecified,
    accentColor: Color = Color.Unspecified,
): Modifier {
    // Built once per composition, reused across every draw pass — no
    // per-frame allocation ever enters the draw phase.
    val request = VisualizerRenderRequest(
        fftSource = fftSource,
        primaryColor = if (primaryColor == Color.Unspecified) SpectrumDefaults.primaryColor else primaryColor,
        accentColor = if (accentColor == Color.Unspecified) SpectrumDefaults.accentColor else accentColor,
    )
    return drawBehind { drawSpectrum(request) }
}

/**
 * Immutable draw request captured by the visualizer facade: resolved
 * colors + the producer lambda. Hoisting the resolution into one
 * composition-scoped object keeps the draw lambda's capture set stable
 * and the draw phase free of parameter plumbing.
 */
private class VisualizerRenderRequest(
    val fftSource: () -> FloatArray,
    val primaryColor: Color,
    val accentColor: Color,
)

/**
 * Default spectrum ramp: teal lows into amber highs — high-contrast over
 * both the light and dark glass surfaces of the token system while
 * staying clear of the cool-white surface palette it overlays.
 */
private object SpectrumDefaults {
    val primaryColor: Color = Color(red = 0.13f, green = 0.83f, blue = 0.75f)
    val accentColor: Color = Color(red = 0.96f, green = 0.45f, blue = 0.11f)
}

/**
 * CPU spectrum lane of the visualizer (the lane that runs until the Phase
 * 3-A shader path lands). Peak-holds at most [MAX_BARS] groups of the raw
 * spectrum into bottom-anchored stadium bars colored across the
 * primary-to-accent ramp. Every geometry argument is a value class or a
 * primitive — the pass allocates nothing.
 */
private fun DrawScope.drawSpectrum(request: VisualizerRenderRequest) {
    // A throwing producer skips the frame: a draw must never crash on
    // producer misbehavior.
    val spectrum = runCatching { request.fftSource() }.getOrNull() ?: return
    if (spectrum.isEmpty()) return

    val width = size.width
    val height = size.height
    if (width <= 0f || height <= 0f) return

    // Bin grouping: a 1024-bin (or any larger) FFT collapses onto at most
    // MAX_BARS peak-held bars, preserving energy spikes that a naive
    // stride sample could drop between bars.
    val barCount = if (spectrum.size <= MAX_BARS) spectrum.size else MAX_BARS
    val groupSize = (spectrum.size + barCount - 1) / barCount
    val slot = width / barCount
    val barWidth = (slot * BAR_WIDTH_FILL).coerceAtLeast(MIN_BAR_WIDTH_PX)
    val colorStep = if (barCount > 1) 1f / (barCount - 1) else 0f

    var bar = 0
    var binStart = 0
    while (binStart < spectrum.size && bar < barCount) {
        val binEnd = minOf(binStart + groupSize, spectrum.size)
        var peak = 0f
        for (bin in binStart until binEnd) {
            val magnitude = spectrum[bin]
            if (magnitude.isFinite() && magnitude > peak) peak = magnitude
        }
        val amplitude = peak.coerceIn(0f, 1f)
        if (amplitude > 0f) {
            val barHeight = height * amplitude
            val top = height - barHeight
            val radius = minOf(barWidth, barHeight) / 2f
            drawRoundRect(
                color = mix(request.primaryColor, request.accentColor, bar * colorStep),
                topLeft = Offset(
                    x = bar * slot + (slot - barWidth) / 2f,
                    y = top,
                ),
                size = Size(width = barWidth, height = barHeight),
                cornerRadius = CornerRadius(radius, radius),
            )
        }
        binStart = binEnd
        bar++
    }

    // Optical baseline anchoring the spectrum, kept inside the last pixel
    // row so a 1 px stroke never bleeds outside the bounds.
    drawLine(
        color = request.primaryColor.copy(alpha = BASELINE_ALPHA),
        start = Offset(0f, height - 0.5f),
        end = Offset(width, height - 0.5f),
        strokeWidth = 1f,
    )
}

/** Arithmetic RGBA mix: [fraction] of the way from [from] to [to]. */
private fun mix(from: Color, to: Color, fraction: Float): Color = Color(
    red = from.red + (to.red - from.red) * fraction,
    green = from.green + (to.green - from.green) * fraction,
    blue = from.blue + (to.blue - from.blue) * fraction,
    alpha = from.alpha + (to.alpha - from.alpha) * fraction,
)

/** Bars drawn per pass at most — bounds the rect count on 1024-bin FFTs. */
private const val MAX_BARS = 128

/** Fraction of a slot a bar fills; the rest is visual breathing room. */
private const val BAR_WIDTH_FILL = 0.62f

/** Anti-alias floor so hairline slots stay visible. */
private const val MIN_BAR_WIDTH_PX = 1f

/** Alpha of the optical baseline under the bars. */
private const val BASELINE_ALPHA = 0.28f
