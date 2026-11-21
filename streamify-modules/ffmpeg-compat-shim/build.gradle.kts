plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

kotlin {
    android {
        namespace = "com.arthenica.ffmpegkit"
        compileSdk = 37
        minSdk = 26
    }

    sourceSets {
        androidMain {
            dependencies {
                implementation(libs.kotlin.stdlib)
            }
        }
    }
}
