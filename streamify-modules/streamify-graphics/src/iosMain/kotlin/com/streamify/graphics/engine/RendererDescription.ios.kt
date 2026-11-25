package com.streamify.graphics.engine

/**
 * iOS actual of the live renderer-description seam (S24 Track A).
 *
 * Metal exposes no renderer-identification string to application code
 * (skiko's Metal adapter querying is internal), and every iOS device
 * ships a hardware GPU — the software-rasterizer lane is unreachable
 * here. Conservative empty default.
 */
internal actual fun liveRendererDescription(): String = ""
