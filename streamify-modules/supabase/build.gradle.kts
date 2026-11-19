plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    android {
        namespace = "com.streamify.supabase"
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
                implementation(libs.koin.core)
                implementation(project(":streamify-core-bridge"))
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.cio)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.kotlinx.json)
            }
        }
        androidMain {
            dependencies {
                implementation("androidx.credentials:credentials:1.2.2")
                implementation("androidx.credentials:credentials-play-services-auth:1.2.2")
                implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
            }
        }
    }
}
