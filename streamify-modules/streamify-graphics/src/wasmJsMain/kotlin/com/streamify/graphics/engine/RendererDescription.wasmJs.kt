package com.streamify.graphics.engine

/**
 * wasmJs actual of the live renderer-description seam (S24 Track A).
 *
 * The browser sandbox hides the GPU adapter behind WebGL/WebGPU — a
 * `WEBGL_debug_renderer_info` probe would leak a renderer string that
 * browsers increasingly mask ("ANGLE (Google, Vulkan ... (SwiftShader))"),
 * and querying it requires a live GL context. The conservative empty
 * default keeps classification on the compile-probe axis; the runtime
 * governor's P95 demotion covers slow software backends.
 */
internal actual fun liveRendererDescription(): String = ""
