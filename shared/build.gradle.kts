import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// ─────────────────────────────────────────────────────────────────────────────
// Phase 5 (MP-UI03 / Beta): Compose Multiplatform shared UI module.
//
// Scope per docs/architecture/MULTIPLATFORM_PLAN_27_10.MD §4 Phase 5 and the
// MULTIPLATFORM_CHECKBOX ledger: `shared/src/commonMain` carries the
// platform-agnostic presentation layer — Compose theme tokens (colors,
// typography, spacing), reusable playback control components (MiniPlayerBar,
// TrackListItem, VolumeSlider), core presentation models and the
// platform-independent state holder (PlaybackController) behind the
// EnginePort seam. Both client shells consume this module; no UI code is
// duplicated between platforms.
//
// MODULE SHAPE (why a jvm-only KMP module):
//   * `:shared` exposes a single JVM target named `desktop`. The DESKTOP
//     shell (`:desktop`) depends on it directly, and `:desktop`'s JNA-mode
//     UniFFI engine bindings are generated HERE (house task pair below) so
//     the engine-backed gateway (src/desktopEngine) and the generated
//     proxies compile exactly once for the desktop lane — `:desktop` keeps
//     its Phase 4b role (engine cdylib build, resource staging, packaging,
//     size budget) and gains the shell.
//   * The ANDROID seam is `:shared-android` (sibling AGP library at
//     shared/android) compiling the SAME commonMain + androidMain sources
//     against androidx Compose. See settings.gradle.kts for the
//     desktop-lane-hermeticity rationale (no AGP configuration on the
//     Android-SDK-less desktop CI runners).
//   * commonMain is deliberately expect/actual-free: it is compiled TWICE
//     (KGP jvm for desktop, AGP+KGP for android) and stays pure
//     platform-agnostic Compose. Platform-specific behavior enters through
//     the EnginePort interface, implemented per platform.
//
// HERMETIC DEGRADATION (same contract as :desktop): with
// -Pstreamify.skipRust=true (or -Pstreamify.skipUniffiBindgen=true) on a
// toolchain-less host, the generated `uniffi.streamify` proxies cannot
// exist, so `src/desktopEngine` — the only uniffi-referencing source set —
// is simply not attached to desktopMain and codegen tasks are skipped.
// commonMain compiles everywhere; `:desktop:test` degrades to zero tests
// via its own Phase 4b exclusion; nothing turns red on a hermetic host.
// ─────────────────────────────────────────────────────────────────────────────

plugins {
    kotlin("multiplatform")
    id("org.jetbrains.compose")
}

kotlin {
    // Single JVM target ("desktop") — bytecode 17, matching :desktop and
    // the app module. No jvmToolchain: the build must run on any JDK >= 17
    // without auto-provisioning.
    jvm("desktop")

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)
            implementation(compose.material3)
            // Shared icon set (Pause / SkipNext / SkipPrevious / VolumeUp /
            // VolumeOff live in the extended artifact). Pinned to the CMP
            // 1.6.1 line = the same androidx Compose 1.6.x artifacts the app
            // resolves through its BOM on the Android seam.
            implementation("org.jetbrains.compose.material:material-icons-extended:1.6.1")

            // Platform-independent state holders: coroutines-core is
            // multiplatform on the 1.7.3 line the app already ships.
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
        }

        // NOTE: single-target KMP modules do NOT get generated per-target
        // source-set accessors (only the common ones) — resolve the
        // desktop main set by name instead.
        val desktopMain by getting {
            dependencies {
                // Skiko-backed desktop runtime (window/animation primitives).
                implementation(compose.desktop.currentOs)

                // MP-DEC-02 desktop JNA binding mode: the generated
                // `uniffi.streamify` proxies dispatch through com.sun.jna. Plain
                // JVM artifact, version-locked to the same 5.14.0 line as
                // app/build.gradle.kts and desktop/build.gradle.kts.
                implementation("net.java.dev.jna:jna:5.14.0")
            }
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
        }
    }

    // JVM bytecode level for the desktop target (house-wide: 17).
    tasks.withType<KotlinCompile>().configureEach {
        kotlinOptions.jvmTarget = "17"
    }
}

// ── UniFFI Kotlin binding generation (desktop JNA lane) ──────────────────────
//
// Mirrors the Phase 4b task pair from desktop/build.gradle.kts (same
// crate-local bindgen entry, same UDL, same package pin via
// rust/uniffi.toml: `uniffi.streamify`) — relocated here in Phase 5 so the
// generated proxies compile ONCE for the whole desktop lane: :shared owns
// them, :desktop consumes them transitively (its JNA smoke suite keeps
// compiling against the identical classes, now from this module's jar).
//
// House flags (same contract as :desktop): hermetic environments (no Rust
// toolchain, -Pstreamify.skipRust=true) degrade the native lane. Gradle
// CONFIGURATION stays toolchain-free — only task execution touches cargo,
// so `./gradlew help`/`tasks`/`--dry-run` work on any host.

val rustCrateDir: File = layout.projectDirectory.dir("../rust").asFile

val uniffiUdlFile: File = File(rustCrateDir, "src/streamify.udl")
val uniffiConfigFile: File = File(rustCrateDir, "uniffi.toml")
val uniffiBindgenEntryFile: File = File(rustCrateDir, "src/bin/uniffi-bindgen.rs")
val uniffiGeneratedSourcesDir: File =
    layout.buildDirectory.dir("generated/uniffi/kotlin").get().asFile

// Raw codegen output (pre-shim); patchSharedUniffiBindings lowers it into
// uniffiGeneratedSourcesDir (see the codegen-defect shim below).
val uniffiGeneratedRawSourcesDir: File =
    layout.buildDirectory.dir("generated/uniffi/kotlin-raw").get().asFile

val skipSharedNative: Boolean = providers.gradleProperty("streamify.skipRust")
    .map { it.toBoolean() }
    .orElse(providers.gradleProperty("streamify.skipUniffiBindgen").map { it.toBoolean() })
    .getOrElse(false)

val generateSharedUniffiBindings by tasks.registering(Exec::class) {
    group = "native"
    description = "Generates the desktop JNA-mode UniFFI Kotlin bindings from rust/src/streamify.udl (Phase 5, relocated from :desktop)."

    val crateDir = rustCrateDir
    val udlFile = uniffiUdlFile
    val configFile = uniffiConfigFile
    val bindgenFile = uniffiBindgenEntryFile
    val outDir = uniffiGeneratedRawSourcesDir
    val skip = skipSharedNative

    onlyIf { !skip && udlFile.isFile && bindgenFile.isFile }

    inputs.file(udlFile)
    if (configFile.isFile) {
        inputs.file(configFile)
    }
    outputs.dir(outDir)

    workingDir = crateDir

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

// ── uniffi-rs 0.28.3 Kotlin codegen defect shim ────────────────────────────
//
// Identical to the Phase 4b desktop shim (proven in PR #116 CI): the Kotlin
// backend emits BOTH a constructor property and an `override val message`
// getter for every error-variant field literally named `message` — the UDL's
// EngineError.Internal(message, backtrace) hits exactly that — and the two
// declarations collide (`message` hides member of supertype `EngineException`
// / recursive getter). The shim lowers the RAW codegen through a one-token
// rename (`message` -> `errorMessage`) before compilation; the gate fails
// loudly if the generated surface ever drifts. The durable fix (UDL field
// rename or a fixed codegen release) stays in Alpha's rust/ subtree.
val patchSharedUniffiBindings by tasks.registering {
    group = "native"
    description = "uniffi 0.28.3 shim: renames the Throwable-shadowing Internal(`message`) field of the raw codegen to `errorMessage` so the generated Kotlin compiles."

    dependsOn(generateSharedUniffiBindings)

    val rawDir = uniffiGeneratedRawSourcesDir
    val finalDir = uniffiGeneratedSourcesDir
    val skip = skipSharedNative
    val rawKtFile = File(rawDir, "uniffi/streamify/streamify.kt")

    onlyIf { !skip && rawKtFile.isFile }

    inputs.dir(rawDir)
    outputs.dir(finalDir)

    doLast {
        val text = rawKtFile.readText()
        val internalPresent = text.contains("class InternalException(")
        val engineExceptionPresent = text.contains("class EngineException")
        val backticked = text.split("`message`").size - 1
        when {
            !internalPresent && !engineExceptionPresent ->
                throw GradleException(
                    "generated streamify.kt no longer declares the EngineError/" +
                        "EngineException surface — the UDL error contract changed; " +
                        "re-audit patchSharedUniffiBindings"
                )
            internalPresent && backticked == 0 ->
                logger.warn(
                    "uniffi codegen no longer emits a backticked `message` field — " +
                        "upstream appears fixed; patchSharedUniffiBindings is a no-op"
                )
            else ->
                logger.lifecycle(
                    "uniffi 0.28.3 codegen shim: renaming {} backticked `message` " +
                        "field reference(s) to `errorMessage`",
                    backticked
                )
        }

        rawDir.walkTopDown().filter { it.isFile }.forEach { f ->
            val dest = File(finalDir, f.relativeTo(rawDir).path)
            dest.parentFile.mkdirs()
            if (f.name.endsWith(".kt")) {
                dest.writeText(f.readText().replace("`message`", "`errorMessage`"))
            } else {
                f.copyTo(dest, overwrite = true)
            }
        }
    }
}

// The generated proxies register through the kotlin source dir of
// desktopMain (KGP compiles .kt sources registered there; same house
// pattern as the :app/:desktop generated-source wiring). The directory is
// attached unconditionally: on hermetic hosts it simply does not exist and
// costs nothing.
kotlin.sourceSets.getByName("desktopMain") {
    kotlin.srcDir(uniffiGeneratedSourcesDir)

    // The engine gateway is the ONLY uniffi-referencing shared code: attach
    // it only when the binding lane is active so hermetic hosts
    // (-Pstreamify.skipRust=true) still compile commonMain + desktopMain.
    if (!skipSharedNative) {
        kotlin.srcDir(file("src/desktopEngine/kotlin"))
    }
}

// Every desktop-target Kotlin compilation runs after the PATCHED bindings
// exist (house pattern — covers compileKotlinDesktop and the desktop test
// compilation; the shim task transitively depends on generation).
tasks.matching { task ->
    task.name.startsWith("compile") && task.name.endsWith("Kotlin")
}.configureEach {
    dependsOn(patchSharedUniffiBindings)
}

tasks.withType<Test>().configureEach {
    useJUnit()
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    // The shared suite is pure-JVM (state holders, formatters, controllers
    // against fakes) — it never touches the native engine, so no
    // jna.library.path wiring is needed and a modest heap keeps the 2-core
    // CI runners comfortable.
    maxHeapSize = "768m"
}
