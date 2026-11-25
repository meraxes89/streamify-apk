@file:OptIn(ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

kotlin {
    android {
        namespace = "com.streamify.media.hal"
        compileSdk = 37
        minSdk = 26
    }
    jvm()
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
            }
        }
        androidMain {
            dependencies {
                implementation(libs.media3.common)
                implementation(libs.media3.exoplayer)
            }
        }
    }
}
