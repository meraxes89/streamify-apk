plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "com.arthenica.ffmpegkit"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
    }
}

dependencies {
    implementation(libs.kotlin.stdlib)
}
