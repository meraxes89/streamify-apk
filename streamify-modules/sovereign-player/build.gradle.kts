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
    }
}
