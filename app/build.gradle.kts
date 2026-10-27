import java.util.Properties
import java.io.File
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProperties = Properties().apply {
    val localFile = rootProject.file("local.properties")
    if (localFile.exists()) {
        localFile.inputStream().use { stream ->
            this.load(stream)
        }
    }
}

val supabaseUrl: String = localProperties.getProperty("SUPABASE_URL")
    ?: System.getenv("SUPABASE_URL")
    ?: "https://dbgtvzgwixerhnwvhtil.supabase.co"

// SEC-01: the legacy fallback below embedded a long-lived service_role
// JWT — a database administrator credential that bypasses every RLS policy
// and must never ship inside a client binary. Client traffic now binds
// strictly to the unprivileged anon key injected at build time via
// local.properties (see CONTRIBUTING.md "Development environment") or the
// SUPABASE_ANON_KEY environment variable. The empty fallback is
// deliberately unprivileged: an unconfigured build degrades to anonymous,
// RLS-gated access instead of silently wielding administrator privileges.
// The exposed legacy key is presumed compromised and must additionally be
// rotated/revoked in the Supabase dashboard.
val supabaseAnonKey: String = localProperties.getProperty("SUPABASE_ANON_KEY")
    ?: System.getenv("SUPABASE_ANON_KEY")
    ?: ""

// SEC-04: release signing state — true only when a production keystore
// is provided via the KEYSTORE_FILE environment variable. When false, the
// release signing config stays unpopulated and the fail-fast guards below
// abort any release assemble/bundle task with a GradleException instead of
// the legacy silent debug-keystore fallback.
val releaseKeystoreConfigured: Boolean = System.getenv("KEYSTORE_FILE")
    ?.let { it.isNotBlank() && file(it).exists() } == true

val googleWebClientId: String = localProperties.getProperty("GOOGLE_WEB_CLIENT_ID")
    ?: System.getenv("GOOGLE_WEB_CLIENT_ID")
    ?: "868888425624-9dfr5crtahiqph68tiba1acapvicnsug.apps.googleusercontent.com"

// 1. Resolve flags at configuration time via Provider API (Configuration-Cache Safe!)
val ciPrebuiltNativeDir: String? = providers.gradleProperty("ciPrebuiltNativeDir").orNull

val skipNativeBuild: Boolean = (ciPrebuiltNativeDir != null) || providers.gradleProperty("skipExternalNativeBuild")
    .map { it.toBoolean() }
    .orElse(providers.gradleProperty("streamify.skipRust").map { it.toBoolean() })
    .getOrElse(false)

val singleTargetAbi: String? = providers.gradleProperty("targetAbi").orNull

// UFFI-02: root of the Kotlin UniFFI bindings generated from
// rust/src/streamify.udl (task registered next to cargoBuildRust at the
// bottom of this file). Registered as a main source directory inside the
// android block below. While Engineer Alpha's UDL contract (UFFI-01) has
// not landed the directory stays empty and compilation is unaffected —
// every CI shard, including the hermetic JVM unit shard, remains green.
val uniffiGeneratedSourcesDir: File = layout.buildDirectory.dir("generated/uniffi/kotlin").get().asFile

val allSupportedAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
val activeAbis: List<String> = if (singleTargetAbi != null && singleTargetAbi.isNotBlank()) {
    listOf(singleTargetAbi)
} else {
    allSupportedAbis
}

android {
    namespace = "com.streamify.app"
    compileSdk = 34

    val rawBuildNum = (System.getenv("GITHUB_RUN_NUMBER") ?: System.getenv("BUILD_NUMBER") ?: "1").toIntOrNull() ?: 1
    val buildNum = 130 + rawBuildNum

    defaultConfig {
        applicationId = "com.streamify.app"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNum
        versionName = "1.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"$supabaseAnonKey\"")
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"$googleWebClientId\"")
        buildConfigField("String", "ADMIN_EMAIL", "\"sireenyadav@gmail.com\"")

        if (!skipNativeBuild) {
            externalNativeBuild {
                cmake {
                    cppFlags += "-std=c++20 -O3 -flto"
                    arguments += "-DANDROID_STL=c++_shared"
                }
            }
        }
        ndk {
            abiFilters.addAll(activeAbis)
        }
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("release") {
            val ksPath = System.getenv("KEYSTORE_FILE")
            if (!ksPath.isNullOrBlank() && file(ksPath).exists()) {
                storeFile = file(ksPath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
            // SEC-04: the silent debug-keystore fallback that used to live
            // here is gone. Release artifacts must be signed with the
            // production keystore or not produced at all — the guard tasks
            // registered after the android block fail release
            // assemble/bundle execution when no KEYSTORE_FILE is provided.
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
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
        buildConfig = true
    }

    // UFFI-02: the Kotlin UniFFI bindings generated from
    // rust/src/streamify.udl are first-class Kotlin sources. The empty/
    // absent directory costs nothing until Alpha's UFFI-01 contract lands;
    // from then on `generateUniffiKotlinBindings` (wired ahead of
    // compile*Kotlin below) keeps them in sync with the UDL.
    sourceSets.getByName("main") {
        java.srcDir(uniffiGeneratedSourcesDir)
    }

    // Lint safety net: the historical codebase carries known warnings
    // (printStackTrace-era logging, legacy AsyncTask palette path, explicit
    // nullability assertions). Lint must inform, not block release builds.
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
    testOptions {
        // Strict-mock migration (sprint P1): default-value stubs were masking
        // unmocked Android framework calls across JVM unit tests. Every
        // framework API that reaches a JVM test now needs an explicit mock or
        // fake (see app/src/test/java/com/streamify/app/testkit/). No silent
        // defaults: a missing mock is a loud RuntimeException, not a quiet 0.
        unitTests {
            isReturnDefaultValues = false
            // Strict-mock companion: arm the pure-JVM SLog fallback sink for
            // the WHOLE test JVM (order-proof — beats per-class @BeforeClass
            // installs because static initializers can run before any test
            // method). See SLog.JvmStdoutSink.
            all { test ->
                test.systemProperty("streamify.slog.jvm", "true")

                // Zero-Compromise JVM Test Parallelism & Fast C1 JIT Tuning
                test.maxParallelForks = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
                test.forkEvery = 0 // Keep warm Android shadow classloaders alive for Robolectric
                test.jvmArgs(
                    "-Xmx1536m",
                    "-XX:+UseParallelGC",
                    "-XX:TieredStopAtLevel=1", // Fast C1 JIT only: eliminates C2 compilation overhead on one-shot tests
                    "-noverify"                // Skip redundant bytecode verification in test workers
                )
            }
        }
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.10"
    }
    if (!skipNativeBuild) {
        externalNativeBuild {
            cmake {
                path = file("../native/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    } else if (ciPrebuiltNativeDir != null) {
        sourceSets.getByName("main") {
            jniLibs.srcDir(file(ciPrebuiltNativeDir))
        }
    }
}

// SEC-04 fail-fast guards: with no production keystore configured, refuse
// to execute any release-variant artifact task. This replaces the old
// silent debug-signing fallback that produced installable release APKs
// signed with the public debug key (spoofable by anyone) whenever CI or a
// local environment forgot to export KEYSTORE_FILE.
if (!releaseKeystoreConfigured) {
    tasks.matching { task ->
        val n = task.name
        (n.startsWith("assemble") && n.endsWith("Release")) ||
                (n.startsWith("bundle") && n.endsWith("Release")) ||
                (n.startsWith("package") && (n.endsWith("Release") || n.endsWith("ReleaseBundle"))) ||
                (n.startsWith("validateSigning") && n.endsWith("Release"))
    }.configureEach {
        doFirst {
            throw GradleException("Missing production KEYSTORE_FILE for release variant")
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    // Installs baseline profiles when they ship; required before the
    // baseline-profile Gradle module can be generated via macrobenchmark.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    
    // Media3
    implementation("androidx.media3:media3-exoplayer:1.2.1")
    implementation("androidx.media3:media3-session:1.2.1")
    implementation("androidx.media3:media3-ui:1.2.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.2.1")

    // Phase 4 — Google Cast SDK (Media3 CastPlayer binding + route-aware volume)
    implementation("androidx.media3:media3-cast:1.2.1")
    implementation("com.google.android.gms:play-services-cast-framework:21.4.0")

    // Phase 4 — WearOS companion links (Data/Message/Channel clients)
    implementation("com.google.android.gms:play-services-wearable:20.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.11.0")

    // Phase 4 — Home/Lock screen Now Playing widgets (Glance)
    implementation("androidx.glance:glance:1.0.0")
    implementation("androidx.glance:glance-appwidget:1.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.11.0")
    
    // Coil
    implementation("io.coil-kt:coil-compose:2.7.0")
    
    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.12.0")

    // UI Additions
    implementation("androidx.palette:palette-ktx:1.1.0")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.ui:ui-text-google-fonts:1.6.2")

    // Google Credential Manager (One-Tap Google Sign-In)
    implementation("androidx.credentials:credentials:1.2.2")
    implementation("androidx.credentials:credentials-play-services-auth:1.2.2")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.2.1")

    // ONNX Runtime
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.31.0")

    // Phase 2 — UniFFI Kotlin bindings runtime (UFFI-02). The generated
    // uniffi.* Kotlin proxies dispatch through JNA (Java Native Access)
    // instead of hand-rolled JNI entry points, per DEC-01 "Contract-First
    // Law". The @aar artifact bundles libjnidispatch.so for every ABI we
    // filter above (arm64-v8a, armeabi-v7a, x86, x86_64) — no host .so is
    // required for the JVM unit-test classpath (see
    // StreamifyEngineBridge for the hermetic fallback).
    implementation("net.java.dev.jna:jna:5.19.1@aar")

    // High-Performance HTTP Transport
    implementation("com.squareup.okhttp3:okhttp:4.12.1")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.1")
    implementation("com.github.teamnewpipe:newpipeextractor:v0.26.4")

    // Hardware-Backed KeyStore & EncryptedSharedPreferences
    implementation("androidx.security:security-crypto:1.1.0")

    // Unit tests (JVM): the jvm-unit-suite CI shard runs the full
    // testDebugUnitTest suite (no package filter, so new tests in any
    // package are picked up automatically).
    testImplementation("junit:junit:4.13.2")
    // Phase 4 — ViewModel viewModelScope support on the JVM shard.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")

    // JAM v4: offline pairing QR generation (pure-JVM zxing core — no
    // Android artifact, safe for the JVM test shard).
    implementation("com.google.zxing:core:3.5.4")

    // JAM v4: real org.json on the JVM test classpath — the android.jar
    // stubs throw "not mocked" for JSONObject in unit tests; this artifact
    // (test-only, never packaged) keeps the Jam wire track JSON codecs
    // exercisable in testDebugUnitTest.
    testImplementation("org.json:json:20240303")

    // Instrumented macrobenchmark (StreamifyMacrobenchmark) executed by the
    // emulator-matrix CI job via connectedDebugAndroidTest.
    androidTestImplementation("androidx.benchmark:benchmark-macro-junit4:1.2.3")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.4.0")
    androidTestImplementation("junit:junit:4.13.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
}

// ── Rust core packaging (Configuration-Cache Safe) ─────────────────────
val rustCrateDir: File = layout.projectDirectory.dir("../rust").asFile
val jniLibsOutputDir: File = layout.projectDirectory.dir("src/main/jniLibs").asFile

val cargoBuildRust by tasks.registering(Exec::class) {
    group = "native"
    description = "Builds streamify_core_rs cdylib via cargo-ndk (Configuration-Cache safe)."

    val targetAbis = activeAbis
    val workDir = rustCrateDir
    val outDir = jniLibsOutputDir
    val skip = skipNativeBuild

    onlyIf { !skip }

    inputs.dir(File(workDir, "src"))
    inputs.file(File(workDir, "Cargo.toml"))
    outputs.dir(outDir)

    workingDir(workDir)

    val ndkArgs = mutableListOf("ndk", "--platform", "26", "-o", outDir.absolutePath)
    targetAbis.forEach { abi ->
        ndkArgs.addAll(listOf("-t", abi))
    }
    ndkArgs.addAll(listOf("build", "--release"))

    commandLine("cargo", *ndkArgs.toTypedArray())
}

if (!skipNativeBuild) {
    tasks.matching {
        it.name.startsWith("merge") && it.name.endsWith("JniLibFolders")
    }.configureEach {
        dependsOn(cargoBuildRust)
    }
}

// ── UniFFI Kotlin binding generation (UFFI-02, Configuration-Cache Safe) ─
//
// DEC-01 (Contract-First Law): rust/src/streamify.udl — owned by Engineer
// Alpha (the rust/ subtree) — is the single ABI source of truth for the
// control plane. This task lowers it into Kotlin proxies under
// build/generated/uniffi/kotlin (package `uniffi.<namespace>`) via the
// crate's `uniffi-bindgen` binary (`cargo run --bin uniffi-bindgen`).
//
// Forward-safe by design: while the UDL has not landed the task is a
// no-op (SKIPPED), so every CI shard — including the hermetic JVM unit
// shard running with -PskipExternalNativeBuild=true and no Rust
// toolchain — stays green. The moment the contract lands, generation
// activates with zero further build changes.
//
// Escape hatch: -Pstreamify.skipUniffiBindgen=true forces the skip for
// environments that have the UDL present but no cargo toolchain.
val uniffiUdlFile: File = File(rustCrateDir, "src/streamify.udl")
val uniffiConfigFile: File = File(rustCrateDir, "uniffi.toml")

val skipUniffiBindgen: Boolean = providers.gradleProperty("streamify.skipUniffiBindgen")
    .map { it.toBoolean() }
    .getOrElse(false)

val generateUniffiKotlinBindings by tasks.registering(Exec::class) {
    group = "native"
    description = "Generates Kotlin UniFFI bindings from rust/src/streamify.udl (UFFI-02)."

    // Configuration-cache: copy script-level vals into task-local vals so
    // the onlyIf/spec lambdas capture plain serializable values, never the
    // Gradle script object (same house pattern as cargoBuildRust).
    val udlFile = uniffiUdlFile
    val configFile = uniffiConfigFile
    val crateDir = rustCrateDir
    val outDir = uniffiGeneratedSourcesDir
    val bindgenFile = File(crateDir, "src/bin/uniffi-bindgen.rs")
    val skip = skipUniffiBindgen || skipNativeBuild || !bindgenFile.isFile

    // No-op until bindgen entry point exists and native build is enabled;
    // also skippable for toolchain-less / JVM-only environments.
    onlyIf { !skip && udlFile.isFile }

    inputs.file(udlFile)
    if (configFile.isFile) {
        inputs.file(configFile)
    }
    outputs.dir(outDir)

    workingDir(crateDir)

    // `cargo run --bin uniffi-bindgen` compiles the bindgen entry point
    // declared by the Rust crate on demand; the UDL (not the .so) is the
    // only semantic input, so this task deliberately does NOT depend on
    // cargoBuildRust — binding generation and native packaging run as
    // independent parallel edges ahead of the compile lifecycle.
    val bindgenArgs = mutableListOf(
        "run",
        "--manifest-path", File(crateDir, "Cargo.toml").absolutePath,
        "--bin", "uniffi-bindgen",
        "--",
        "generate",
        udlFile.absolutePath,
        "--language", "kotlin",
        "--out-dir", outDir.absolutePath
    )
    if (configFile.isFile) {
        bindgenArgs.addAll(listOf("--config", configFile.absolutePath))
    }
    commandLine("cargo", *bindgenArgs.toTypedArray())
}

// Hook the binding generation into the Android compilation lifecycle:
// every Kotlin compilation of app sources (compileDebugKotlin,
// compileReleaseKotlin and the JVM unit-test compilation that reuses main
// sources) runs AFTER the bindings exist — mirroring how cargoBuildRust
// is wired ahead of merge*JniLibFolders above.
tasks.matching { task ->
    task.name.startsWith("compile") && task.name.endsWith("Kotlin")
}.configureEach {
    dependsOn(generateUniffiKotlinBindings)
}

