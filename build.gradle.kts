plugins {
    id("com.android.application") version "8.2.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false

    // Phase 4b (MP-HAL10 / Beta): Desktop JVM lane. The Kotlin JVM plugin
    // pins the SAME 1.9.22 toolchain as the Android module — one KGP version
    // per build, no cross-module classpath drift. Compose Multiplatform 1.6.1
    // is the last stable line of the Kotlin 1.9.x era (its bundled Compose
    // compiler mapping resolves 1.5.10.x for Kotlin 1.9.22, the exact pair
    // the app module already ships as kotlinCompilerExtensionVersion).
    id("org.jetbrains.kotlin.jvm") version "1.9.22" apply false
    id("org.jetbrains.compose") version "1.6.1" apply false
}
