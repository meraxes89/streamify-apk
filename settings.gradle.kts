pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven { url = uri("https://repo1.maven.org/maven2/") }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://repo1.maven.org/maven2/") }
        maven { url = uri("https://jitpack.io") }
        maven { url = uri("https://chaquo.com/maven") }
    }
}
rootProject.name = "Streamify"
include(":app")

// Phase 4b (MP-HAL10 / Beta): Desktop JVM target — Compose Desktop scaffold
// over the JNA-mode UniFFI engine lane. The module is desktop-only: it never
// participates in the Android variants, so the existing app CI shards are
// untouched (their gradlew invocations address :app tasks directly).
include(":desktop")

// Phase 5 (MP-UI03 / Beta): the Compose Multiplatform shared UI module.
//
// `:shared` is a Kotlin Multiplatform module with a single JVM target
// (`desktop`): commonMain carries the platform-agnostic theme tokens,
// playback control components, presentation models and state holders;
// `src/desktopMain` (plus the conditionally-attached `src/desktopEngine`)
// carries the desktop engine gateway over the JNA-mode UniFFI bindings
// generated in this module's build tree.
//
// `:shared-android` is the Android consumption seam for the SAME source
// tree: an AGP library that compiles `shared/src/commonMain` +
// `shared/src/androidMain` against androidx Compose. It exists as a sibling
// module (projectDir `shared/android`) instead of an `androidTarget` inside
// `:shared` for one hard architectural reason: `:desktop` depends on
// `:shared`, and the desktop CI lane runs `--configure-on-demand` on
// runners WITHOUT an Android SDK — a KMP androidTarget would drag AGP
// configuration (and its SDK requirement) into that lane and break the
// hermeticity guarantee established in Phase 4b. Keeping the Android
// target in a separate module preserves it exactly: desktop runners
// configure only :desktop + :shared (both AGP-free), app runners
// configure everything and have the SDK.
include(":shared")
include(":shared-android")
project(":shared-android").projectDir = file("shared/android")
