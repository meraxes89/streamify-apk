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

    sourceSets {
        commonMain {
            dependencies {
                implementation(libs.kotlin.stdlib)
                implementation(libs.kotlinx.coroutines.core)
                implementation(project(":streamify-core-bridge"))
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
