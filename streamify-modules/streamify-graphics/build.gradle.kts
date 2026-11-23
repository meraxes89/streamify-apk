@file:OptIn(ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

// =============================================================================
// :streamify-modules:streamify-graphics — Sovereign GPU Engine
// (docs/gpu/ARCHITECTURE.md, docs/gpu/PHASED_PLAN.md Phase 1-B)
//
// Upstream-isolated shader / effect / capability-governor abstraction for
// Compose Multiplatform. Phase 1 scope: 3-layer design tokens, the frozen
// public facade (`Modifier.streamifyGlass`) and the hexagonal engine port
// (`SovereignGraphicsEngine`) plus Koin wiring. Platform shader adapters
// (AGSL / RenderEffect / Skia RuntimeShaderBuilder) land in Phase 2.
// =============================================================================

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    android {
        namespace = "com.streamify.graphics"
        compileSdk = 37
        // Tier floor per docs/gpu/: acrylic-scrim fallback must cover API 26+
        // (Vivo 1712 ships Android 8.1 / API 27 and pins Tier 3).
        minSdk = 26
    }

    // Desktop is the JVM target named `desktop` so its source set is literally
    // `desktopMain` and the CI task selectors documented in
    // docs/gpu/CI_TESTING_SPEC.md (`testDesktopUnitTest`) resolve as specified.
    jvm("desktop")

    wasmJs {
        browser()
    }

    sourceSets {
        commonMain {
            dependencies {
                implementation(libs.kotlin.stdlib)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.koin.core)

                // Compose Multiplatform — the facade is a pure draw-phase
                // Modifier (ui graphics/layer/unit only; no foundation deps).
                implementation(libs.runtime)
                implementation(libs.compose.ui)
            }
        }
    }
}
