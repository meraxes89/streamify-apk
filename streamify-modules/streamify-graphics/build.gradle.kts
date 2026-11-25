@file:OptIn(ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

// =============================================================================
// :streamify-modules:streamify-graphics — Sovereign GPU Engine
// (docs/gpu/ARCHITECTURE.md, docs/gpu/PHASED_PLAN.md Phase 1 / Phase 2)
//
// Upstream-isolated shader / effect / capability-governor abstraction for
// Compose Multiplatform.
// Phase 1 scope: 3-layer design tokens, frozen public facade (Modifier.streamifyGlass),
// hexagonal engine port (SovereignGraphicsEngine), Koin wiring (Track B),
// canonical LiquidGlassCore.sksl shader, and dialect transpilation test (Track A).
// Phase 2-A scope: platform ShaderProvider actuals — androidMain (AGSL /
// RenderEffect blur / acrylic scrim lanes) plus the shared skikoCommonMain
// source set wired below (Desktop JVM + wasmJs -> Skia RuntimeShaderBuilder).
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

    listOf(
        iosArm64(),
        iosSimulatorArm64()
    )

    sourceSets {
        commonMain {
            dependencies {
                implementation(libs.kotlin.stdlib)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.koin.core)

                // Compose Multiplatform — pure draw-phase Modifier
                implementation(libs.runtime)
                implementation(libs.compose.ui)

                // Kyant optical parity (S24, Track C): the fluid press
                // interaction needs foundation's observe-only gesture
                // primitives (awaitEachGesture / awaitFirstDown) and
                // animation's Animatable + spring for the 0.5/300 press
                // swell. Same composeMultiplatform version as ui/runtime —
                // still a pure draw-phase, zero-recomposition facade.
                implementation(libs.foundation)
                implementation(libs.compose.animation)
            }
        }

        // -------------------------------------------------------------------------
        // Phase 2-A Track A: skikoCommonMain — the shared Skia adapter source
        // set (Desktop JVM + wasmJs) of docs/gpu/ARCHITECTURE.md section 1:
        // [Adapters] skikoCommonMain -> Skia RuntimeShaderBuilder (SkSL).
        //
        // No extra dependency is declared here on purpose: skiko
        // (RuntimeEffect / RuntimeShaderBuilder / Shader) is api-exposed by
        // compose.ui on BOTH consuming targets — verified against the compose
        // 1.12.2 Gradle module metadata (ui-desktop desktopApiElements and
        // ui-wasm-js wasmJsApiElements both depend on
        // org.jetbrains.skiko:skiko:0.150.2).
        // -------------------------------------------------------------------------
        val skikoCommonMain by creating {
            dependsOn(commonMain.get())
        }
        val desktopMain by getting {
            dependsOn(skikoCommonMain)
        }
        val wasmJsMain by getting {
            dependsOn(skikoCommonMain)
        }

        // iOS skiko wiring — per-target source sets, NOT the `iosMain`
        // intermediate. `iosMain` is synthesized by KGP's default hierarchy
        // template only at afterEvaluate, so an eager `val iosMain by getting`
        // here throws UnknownDomainObjectException at configuration time
        // (the "KotlinSourceSet with name 'iosMain' not found" that broke the
        // Graphics and Wasm CI lanes); per-target sets like iosArm64Main are
        // created eagerly together with the target declaration, exactly like
        // desktopMain above. Wiring both iOS targets directly keeps the same
        // dependency intent (skiko adapter code shared into the iOS lane)
        // without racing the template.
        val iosArm64Main by getting {
            dependsOn(skikoCommonMain)
        }
        val iosSimulatorArm64Main by getting {
            dependsOn(skikoCommonMain)
        }

        val desktopTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                // Skia RuntimeEffect / RuntimeShaderBuilder for the dialect
                // compilation gate (compose 1.12.x -> skiko).
                implementation(compose.desktop.currentOs)
            }
        }

        // S24 Track E (docs/systems/S24-sovereign-graphics.md): the Apple
        // thermal-constant weld + governor smoke suite executes on the iOS
        // Simulator lane (`iosSimulatorArm64Test` on the macOS CI runner —
        // the test binary runs natively on Apple Silicon hosts). Per-target
        // sets (iosArm64Test, iosSimulatorArm64Test) avoid the eager
        // intermediate resolution race on `iosTest` (same pattern as iosArm64Main).
        val iosArm64Test by getting {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
        val iosSimulatorArm64Test by getting {
            dependencies {
                implementation(libs.kotlin.test)
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

// CI_TESTING_SPEC.md section 2, Job 4 — the software-raster fallback gate.
// A dedicated Test JVM fork running ONLY SoftwareRasterFallbackGateTest with
// LIBGL_ALWAYS_SOFTWARE=1 (Mesa onto a CPU rasterizer). The fork isolation
// matters: ShaderProvider.probe()'s process-lazy classification must resolve
// *under* the forced environment, which a shared fork with the healthy-host
// assertions of SkikoShaderProviderTest would race. Framework (JUnit 4, the
// Gradle default — matching desktopTest, which declares no platform) and
// classpath mirror the desktopTest task.
val desktopTestTask = tasks.named<Test>("desktopTest")
tasks.register<Test>("testSoftwareRasterFallback") {
    group = "verification"
    description = "Runs the software-raster fallback gate (CI_TESTING_SPEC.md Job 4) with LIBGL_ALWAYS_SOFTWARE=1."
    testClassesDirs = desktopTestTask.get().testClassesDirs
    classpath = desktopTestTask.get().classpath
    useJUnit()
    include("com/streamify/graphics/engine/SoftwareRasterFallbackGateTest.class")
    environment("LIBGL_ALWAYS_SOFTWARE", "1")
    shouldRunAfter("desktopTest")
}
