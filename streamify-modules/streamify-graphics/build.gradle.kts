@file:OptIn(ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

// =============================================================================
// :streamify-modules:streamify-graphics — Sovereign GPU Engine
// (docs/gpu/ARCHITECTURE.md, docs/gpu/PHASED_PLAN.md Phase 1)
//
// Upstream-isolated shader / effect / capability-governor abstraction for
// Compose Multiplatform.
// Phase 1 scope: 3-layer design tokens, frozen public facade (Modifier.streamifyGlass),
// hexagonal engine port (SovereignGraphicsEngine), Koin wiring (Track B),
// canonical LiquidGlassCore.sksl shader, and dialect transpilation test (Track A).
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
    // `desktopMain` / `desktopTest` and the CI task selectors documented in
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

                // Compose Multiplatform — pure draw-phase Modifier
                implementation(libs.runtime)
                implementation(libs.compose.ui)
            }
        }

        val desktopTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                // Skia RuntimeEffect / RuntimeShaderBuilder for the dialect
                // compilation gate (compose 1.12.x -> skiko).
                implementation(compose.desktop.currentOs)
            }
        }
    }
}

// docs/gpu/CI_TESTING_SPEC.md pins the desktop verification entry point as
// `./gradlew :streamify-modules:streamify-graphics:testDesktopUnitTest`, so
// expose that name as a verification alias over desktopTest.
tasks.register("testDesktopUnitTest") {
    group = "verification"
    description = "Runs desktopTest - the CI-documented desktop unit-test entry point."
    dependsOn("desktopTest")
}
