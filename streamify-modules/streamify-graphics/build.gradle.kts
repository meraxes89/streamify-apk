// ===========================================================================
// :streamify-modules:streamify-graphics — Sovereign GPU Engine module
// (docs/gpu/ARCHITECTURE.md, docs/gpu/PHASED_PLAN.md).
//
// Phase 1 (Track A) scope: the canonical LiquidGlassCore.sksl shader
// resource plus the Desktop JVM dialect-transpilation gate
// (DialectTranspileTest). Platform ShaderProvider adapters
// (androidMain / skikoCommonMain) land in Phase 2; the 3-layer token
// system, TierGovernor and Koin wiring land with Track B.
//
// Upstream immunity: nothing in core/ is consumed or modified here — this
// module is a self-contained downstream extension under streamify-modules/.
// ===========================================================================
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    // 21 matches :desktopApp / :composeApp desktop lanes; CI runners
    // provision temurin 21.
    jvmToolchain(21)

    // Desktop JVM lane. The target is named `desktop` so its source sets
    // read desktopMain / desktopTest, matching the Phase 2 deliverable
    // layout (ShaderProvider.skiko.kt + the dialect gate below).
    jvm("desktop") {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_21)
                }
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlin.stdlib)
        }
        // The canonical SkSL ships as a commonMain resource so every platform
        // adapter (AGSL androidMain, Skia skikoCommonMain) reads the exact
        // same shader bytes — the dialect deltas live in the adapters, never
        // in the shader source itself.
        val desktopTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                // Skia RuntimeEffect / RuntimeShaderBuilder for the dialect
                // compilation gate (compose 1.12.x -> skiko 0.150.2). The
                // skikoCommonMain api dependency arrives with the Phase 2
                // ShaderProvider adapter.
                implementation(compose.desktop.currentOs)
            }
        }
    }
}

// KGP names a JVM target's test task after the target (`desktopTest`).
// docs/gpu/CI_TESTING_SPEC.md pins the desktop verification entry point as
// `./gradlew :streamify-modules:streamify-graphics:testDesktopUnitTest`, so
// expose that name as a verification alias over desktopTest. The android
// lane (`testDebugUnitTest`) appears when the androidLibrary target lands
// with the Phase 2 ShaderProvider.android.kt.
tasks.register("testDesktopUnitTest") {
    group = "verification"
    description = "Runs desktopTest - the CI-documented desktop unit-test entry point."
    dependsOn("desktopTest")
}
