pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven { setUrl("https://jitpack.io") }
        maven("https://jogamp.org/deployment/maven")
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven { url = uri("https://jitpack.io") }
        maven("https://jogamp.org/deployment/maven")
        maven(url = "https://raw.githubusercontent.com/bravepipeproject/maven-repo/master/repository")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// Core modules live in the `core` git submodule INSIDE this repo.
val coreDir = File(rootDir, "core")
val serviceDir = File(rootDir, "core/service")
val mediaDir = File(rootDir, "core/media")

rootProject.name = "Streamify"
include(
    ":androidApp",
    ":vivoAndroidApp",
    ":composeApp",
    ":desktopApp",
    ":common",
    ":data",
    ":domain",
    ":ktorExt",
    ":kotlinYtmusicScraper",
    ":spotify",
    ":aiService",
    ":autoEqService",
    ":lyricsService",
    ":media-jvm",
    ":media-jvm-ui",
    ":media3",
    ":media3-ui",
    ":crashlytics",
    ":crashlytics-empty",
    ":cast",
    ":cast-empty",
    ":lastfm",
    ":lastfm-empty",
    ":kizzy",
    ":discord",
    ":listenTogether",
    ":loginSync",
)

// core modules
project(":common").projectDir = File(coreDir, "common")
project(":data").projectDir = File(coreDir, "data")
project(":domain").projectDir = File(coreDir, "domain")

// service modules
project(":ktorExt").projectDir = File(serviceDir, "ktorExt")
project(":aiService").projectDir = File(serviceDir, "aiService")
project(":autoEqService").projectDir = File(serviceDir, "autoEqService")
project(":lyricsService").projectDir = File(serviceDir, "lyricsService")
project(":kotlinYtmusicScraper").projectDir = File(serviceDir, "kotlinYtmusicScraper")
project(":spotify").projectDir = File(serviceDir, "spotify")
project(":kizzy").projectDir = File(serviceDir, "kizzy")
project(":discord").projectDir = File(serviceDir, "discord")
project(":listenTogether").projectDir = File(serviceDir, "listenTogether")
project(":loginSync").projectDir = File(serviceDir, "loginSync")

// media modules
project(":media-jvm").projectDir = File(mediaDir, "media-jvm")
project(":media-jvm-ui").projectDir = File(mediaDir, "media-jvm-ui")
project(":media3").projectDir = File(mediaDir, "media3")
project(":media3-ui").projectDir = File(mediaDir, "media3-ui")

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

// =========================================================================
// STREAMIFY EXTENSION SUITE (Non-invasive downstream modules)
// =========================================================================
val streamifyDir = File(rootDir, "streamify-modules")
if (streamifyDir.exists()) {
    include(
        ":streamify-core-bridge",
        ":streamify-media-hal",
        ":streamify-lyrics-slyr",
        ":streamify-supabase",
        ":streamify-jam"
    )
    project(":streamify-core-bridge").projectDir = File(streamifyDir, "core-bridge")
    project(":streamify-media-hal").projectDir = File(streamifyDir, "media-hal")
    project(":streamify-lyrics-slyr").projectDir = File(streamifyDir, "lyrics-slyr")
    project(":streamify-supabase").projectDir = File(streamifyDir, "supabase")
    project(":streamify-jam").projectDir = File(streamifyDir, "jam")
}

// Legacy modules preservation
val legacyAppDir = File(rootDir, "legacy/app")
if (legacyAppDir.exists()) {
    include(":legacy-app")
    project(":legacy-app").projectDir = legacyAppDir
}
