package com.streamify.desktop

import androidx.compose.ui.window.application
import com.streamify.desktop.shell.StreamifyDesktopApp

/**
 * Phase 5 desktop entry point (Beta / MP-UI04).
 *
 * Thin by design: pins the UniFFI JNA library name to the real cdylib
 * before any FFI touch (UDL-mode codegen derives the fallback name
 * `uniffi_streamify`; the shipped library is `streamify_core_rs` — the
 * `uniffi.component.<namespace>.libraryOverride` system property is the
 * sanctioned correction hook read by the generated loader on every
 * `Native.load`, set identically for the test JVM by
 * desktop/build.gradle.kts), then hands off to the responsive shell.
 *
 * This file stays free of `uniffi.*` references (as it has since Phase 4b)
 * so the module compiles on hosts without a Rust toolchain; the engine
 * gateway is instantiated reflectively via
 * [com.streamify.desktop.engine.EnginePortLoader].
 */
fun main() = application {
    System.setProperty("uniffi.component.streamify.libraryOverride", "streamify_core_rs")
    StreamifyDesktopApp()
}
