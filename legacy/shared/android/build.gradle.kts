// ─────────────────────────────────────────────────────────────────────────────
// Phase 5 (MP-UI03 / Beta): Android consumption seam for the shared CMP UI.
//
// An AGP LIBRARY that compiles the shared source tree —
//   * shared/src/commonMain  (platform-agnostic theme tokens, components,
//     presentation models, state holders), and
//   * shared/src/androidMain (Android glue consuming them: the MP-CUT01
//     RustAudioDogfoodPanel),
// against androidx Compose, using EXACTLY the dependency + compiler setup
// the app module already proves in CI: compose BOM 2024.02.00, Compose
// compiler 1.5.10 on Kotlin 1.9.22, buildFeatures.compose, jvmTarget 17.
// The app then depends on this module (`implementation(project(":shared-android"))`)
// — that single edge is the whole Android-side wiring of MP-UI03.
//
// WHY NOT a KMP `androidTarget` inside :shared: see settings.gradle.kts —
// the desktop CI lane configures :desktop + :shared with
// --configure-on-demand on runners WITHOUT an Android SDK; an AGP target
// inside :shared would break that hermeticity. The dual compilation of
// commonMain (KGP-jvm for desktop, AGP here for Android) is deliberate and
// safe: commonMain is expect/actual-free pure Compose, and both lanes
// resolve to the same androidx Compose 1.6.x line (CMP 1.6.1 == Compose
// 1.6.1 runtime + material3 1.2.x).
// ─────────────────────────────────────────────────────────────────────────────

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.streamify.shared"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    // Same proven pair as app/build.gradle.kts: Kotlin 1.9.22 + Compose
    // compiler 1.5.10.
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.10"
    }

    sourceSets {
        getByName("main") {
            // The shared source tree is compiled here for the Android seam
            // (kotlin dirs via the java srcDir convention used across this
            // repo's generated-source wiring).
            java.srcDir("src/main/kotlin")
            java.srcDir("../src/commonMain/kotlin")
            java.srcDir("../src/androidMain/kotlin")
        }
    }

    // Lint mirrors the app module's safety net: the shared tree is pure
    // Compose/Kotlin, but lint must inform, never block.
    lint {
        abortOnError = false
    }

    testOptions {
        unitTests.all { test ->
            // Pure-JVM shared tests run with a bounded heap (the app's JVM
            // shard contract).
            test.maxHeapSize = "768m"
        }
    }
}

dependencies {
    // EXACT mirror of the app module's Compose set (BOM-pinned), so the
    // commonMain sources compiled here resolve the SAME androidx artifacts
    // the app runtime uses.
    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Platform-independent state holders (same 1.7.3 line as :app/:shared).
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
}
