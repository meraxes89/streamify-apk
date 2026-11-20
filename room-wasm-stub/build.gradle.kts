@file:OptIn(ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    wasmJs {
        browser()
    }
    compilerOptions {
        freeCompilerArgs.add("-Xallow-kotlin-package")
    }
    sourceSets {
        commonMain {
            dependencies {
                implementation(libs.kotlin.stdlib)
                implementation(libs.kermit.logging)
                implementation(libs.kotlinx.datetime)
                api("org.jetbrains.kotlinx:kotlinx-io-core:0.6.0")
                implementation(libs.runtime)
                implementation(libs.compose.ui)
                implementation(libs.ktor.client.core)
            }
        }
        wasmJsMain {
            dependencies {
                implementation(libs.ktor.client.js)
            }
        }
    }
}
