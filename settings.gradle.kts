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
