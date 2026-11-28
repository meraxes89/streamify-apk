package com.streamify.graphics.engine

/**
 * wasmJsMain actuals of the skiko platform signal seam — S24 Tracks C
 * and D (docs/systems/S24-sovereign-graphics.md).
 *
 * Conservative defaults until the browser lane wires its own reporters:
 * the renderer description stays unknown (""), and the density scale 1.0
 * keeps the Tier 2 backdrop capture at full resolution (the WebGL
 * Ganesh backend has no Retina tile-buffer pressure profile comparable
 * to Apple Silicon TBDR).
 */
actual fun skikoRendererDescription(): String = ""

actual fun skikoDisplayDensityScale(): Float = 1f
