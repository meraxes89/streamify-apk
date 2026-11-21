@file:OptIn(ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

kotlin {
    android {
        namespace = "com.streamify.sovereign.player"
        compileSdk = 37
        minSdk = 26
    }
    jvm()
    wasmJs {
        browser()
    }

    sourceSets {
        commonMain {
            dependencies {
                implementation(libs.kotlin.stdlib)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.koin.core)
                // The Layer-2 seam: the ONLY upstream type the Sovereign HAL
                // consumes is the MediaPlayerHandler INTERFACE (core/domain —
                // the small, slow-moving, observable contract every screen
                // already speaks). Behavior lives here; upstream's handler
                // implementations stay the default binding underneath us.
                implementation(project(":domain"))
                implementation(project(":streamify-core-bridge"))
            }
        }
        commonTest {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.core)
                // The portable suspend test runner (runTest) — a KMP
                // artifact (JVM/Android/JS/wasmJs), unlike runBlocking
                // which is JVM-only. Pinned by the workspace's coroutines
                // BOM conventions: the version matches the core artifact.
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
            }
        }
        jvmMain {
            dependencies {
                // Sovereign HAL Phase 1 (D4): the desktop JVM bridge rides
                // JNA against the Rust engine's exported C ABI (the same
                // frozen ring/stats edge the CI brackets use — 96-byte
                // stats wire, magic 0x51AB0001).
                implementation(libs.jna)
            }
        }
        // Sovereign HAL Phase 2 (Step 1): the Android Oboe lane. The
        // seam resolves against libstreamify_native_core.so (the NDK
        // CMake umbrella — src/oboe_output_bracket.cpp) through plain
        // System.loadLibrary; no extra Gradle dependency carries it.
        androidMain {
            dependencies {
                // kotlinx-coroutines is already commonMain; the bracket
                // seam needs nothing platform-side beyond the JDK's
                // android.util.Log (framework).
            }
        }
    }
}
