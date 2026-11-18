plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    android {
        namespace = "com.streamify.jam"
        compileSdk = 37
        minSdk = 26
    }
    jvm()

    sourceSets {
        commonMain {
            dependencies {
                implementation(libs.kotlin.stdlib)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(project(":streamify-core-bridge"))
                implementation(project(":streamify-supabase"))
                implementation(project(":streamify-media-hal"))
            }
        }
    }
}
