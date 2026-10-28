package com.streamify.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

/**
 * Phase 4b desktop entry point (Beta / MP-HAL10).
 *
 * Deliberately a minimal, engine-free Compose window: the Phase 4b mission is
 * CI packaging, the size budget and the headless JNA binding verification
 * (see `DesktopJnaEngineSmokeTest`); the full responsive shell — navigation
 * rail, system tray, SMTC/MPRIS integration — is Phase 5 scope (MP-UI04).
 * Keeping this file free of `uniffi.*` references also lets the module
 * compile on hosts without a Rust toolchain.
 *
 * The single runtime statement that matters: pin the UniFFI JNA library name
 * to the real cdylib before any FFI touch. UDL-mode codegen derives the
 * fallback name `uniffi_streamify` (namespace-based), while the shipped
 * library is `streamify_core_rs` (crate [lib] name) — the
 * `uniffi.component.<namespace>.libraryOverride` system property is the
 * sanctioned correction hook read by the generated loader on every
 * `Native.load`. It is set identically for the test JVM by
 * `desktop/build.gradle.kts`.
 */
fun main() = application {
    System.setProperty("uniffi.component.streamify.libraryOverride", "streamify_core_rs")

    Window(
        title = "Streamify Desktop",
        onCloseRequest = ::exitApplication,
    ) {
        MaterialTheme {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "Streamify Desktop",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Phase 4b scaffold — JNA engine lane & CI size budget (MP-HAL10)",
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    text = "The responsive shell lands in Phase 5 (MP-UI04).",
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
