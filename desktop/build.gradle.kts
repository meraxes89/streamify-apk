import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// ─────────────────────────────────────────────────────────────────────────────
// Phase 4b (MP-HAL10 / Beta): Desktop JVM scaffold — JNA engine lane, CI
// packaging and the <250MB distribution size budget.
//
// Scope per docs/architecture/MULTIPLATFORM_PLAN_27_10.MD §4 Phase 4b and the
// MULTIPLATFORM_CHECKBOX ledger: this module exists so the Desktop target can
// be BUILT, PACKAGED and SIZE-AUDITED on the Windows/Linux CI matrix, and so
// the JNA-mode engine binding (MP-DEC-02: `streamify-jvm` lane) can be
// verified headlessly. The full Compose Multiplatform shell (responsive
// navigation rail, system tray, SMTC/MPRIS adapters) is Phase 5 scope
// (MP-UI04) — Main.kt stays a deliberately minimal, engine-free window so it
// compiles even on hosts without a Rust toolchain.
//
// Binding mode (MP-DEC-02 / MP-C12): the Desktop JVM reaches the sovereign
// Rust core through UniFFI's JNA backend — the generated `uniffi.streamify`
// proxies dispatch via com.sun.jna, and the runtime dependency is the plain
// JNA jar (NOT the Android @aar). The Android direct-JNI lane is structurally
// absent: `jni` is cfg-gated to `target_os = "android"` in rust/Cargo.toml
// (MP-C13), so the desktop cdylib exports the uniffi_* C ABI and no Java_*
// symbols at all.
//
// Toolchain notes (verified against uniffi-rs v0.28.3 sources):
//   * UDL-mode codegen (`cargo run --bin uniffi-bindgen -- generate …`) sets
//     `cdylib_name` to the "uniffi_<namespace>" fallback because no cdylib
//     file is passed on the CLI — the generated Kotlin therefore wants to
//     `Native.load("uniffi_streamify")`. The shipped library is
//     `streamify_core_rs` (crate [lib] name). The sanctioned escape hatch is
//     the runtime property `uniffi.component.<namespace>.libraryOverride`
//     (NamespaceLibraryTemplate.kt reads it before every load), which this
//     module pins to the real cdylib name in the test JVM and in Main.kt
//     ahead of any FFI touch. The durable fix — a `cdylib_name` key in
//     rust/uniffi.toml — lives in Alpha's subtree and is flagged in the PR
//     description.
//   * `rust/Cargo.toml` [profile.release] already sets `strip = true`, so the
//     release cdylib ships without symbol/debug sections; the hygiene gate
//     below additionally forbids symbol FILES, fixture payloads and foreign
//     architecture binaries in the packaged resources.
// ─────────────────────────────────────────────────────────────────────────────

plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<KotlinCompile>().configureEach {
    kotlinOptions.jvmTarget = "17"
}

dependencies {
    implementation(compose.desktop.currentOs)

    // MP-DEC-02: desktop JNA binding mode. Plain JVM artifact (the Android
    // module uses the @aar flavor for its bundled libjnidispatch ABI set).
    // Version-locked to the same 5.14.0 line as app/build.gradle.kts.
    implementation("net.java.dev.jna:jna:5.14.0")

    testImplementation("junit:junit:4.13.2")
}

compose.desktop {
    application {
        mainClass = "com.streamify.desktop.MainKt"

        nativeDistributions {
            // One canonical installer per CI matrix leg: jpackage deb needs
            // only dpkg-deb (present on ubuntu runners), msi needs WiX 3.x
            // (installed by .github/workflows/desktop-ci.yml on windows
            // runners). rpm/dmg/pkg stay out of the Phase 4b matrix.
            targetFormats(TargetFormat.Deb, TargetFormat.Msi)

            // Lowercase on purpose: dpkg package names must match
            // [a-z0-9][a-z0-9+.-]* — "Streamify" would be rejected.
            packageName = "streamify"
            packageVersion = "1.1.0"
            description = "Streamify audiophile desktop client (Phase 4b JNA scaffold)"
            vendor = "Streamify"
            linux {
                debMaintainer = "Streamify CI <ci@streamify.local>"
            }
            // Fixed so successive MSIs upgrade each other instead of
            // side-by-side installing (jpackage --win-upgrade-uuid).
            windows {
                upgradeUuid = "6b0d69b1-1f6e-4f29-9f6a-9f1f0000004b"
                menu = true
                shortcut = true
            }
        }
    }
}

// ── Rust core: paths, platform-mapped cdylib name, skip flags ────────────────

val rustCrateDir: File = layout.projectDirectory.dir("../rust").asFile
val rustTargetReleaseDir: File = File(rustCrateDir, "target/release")

val uniffiUdlFile: File = File(rustCrateDir, "src/streamify.udl")
val uniffiConfigFile: File = File(rustCrateDir, "uniffi.toml")
val uniffiBindgenEntryFile: File = File(rustCrateDir, "src/bin/uniffi-bindgen.rs")
val uniffiGeneratedSourcesDir: File =
    layout.buildDirectory.dir("generated/uniffi/kotlin").get().asFile

// House flags (same contract as app/build.gradle.kts): hermetic environments
// (no Rust toolchain, -Pstreamify.skipRust=true) degrade the native lane.
// Gradle CONFIGURATION stays toolchain-free — only task execution touches
// cargo, so `./gradlew help`/`tasks`/`--dry-run` work on any host.
val skipDesktopNative: Boolean = providers.gradleProperty("streamify.skipRust")
    .map { it.toBoolean() }
    .orElse(providers.gradleProperty("streamify.skipUniffiBindgen").map { it.toBoolean() })
    .getOrElse(false)

// JNA Native.load(name) platform mapping for the engine cdylib:
//   windows → streamify_core_rs.dll, linux → libstreamify_core_rs.so,
//   macos → libstreamify_core_rs.dylib.
val osName: String = System.getProperty("os.name").lowercase()
val engineLibraryName: String = when {
    osName.contains("windows") -> "streamify_core_rs.dll"
    osName.contains("mac") || osName.contains("darwin") -> "libstreamify_core_rs.dylib"
    else -> "libstreamify_core_rs.so"
}
val engineLibraryFile: File = File(rustTargetReleaseDir, engineLibraryName)

// Prepared resources root: the SINGLE host-platform engine library sits at the
// classpath root under its platform-mapped name, which is exactly where JNA's
// classpath fallback looks for it — this is what makes the library loadable
// identically from :desktop:test, :desktop:run and the jpackaged image.
val engineResourceDir: File = layout.buildDirectory.dir("engine-native").get().asFile

// ── UniFFI Kotlin binding generation (mirrors generateUniffiKotlinBindings
//    in app/build.gradle.kts — same crate-local bindgen entry, same UDL,
//    same package pin via rust/uniffi.toml: `uniffi.streamify`) ──────────────

val generateDesktopUniffiBindings by tasks.registering(Exec::class) {
    group = "native"
    description = "Generates the desktop JNA-mode UniFFI Kotlin bindings from rust/src/streamify.udl (Phase 4b)."

    val crateDir = rustCrateDir
    val udlFile = uniffiUdlFile
    val configFile = uniffiConfigFile
    val bindgenFile = uniffiBindgenEntryFile
    val outDir = uniffiGeneratedSourcesDir
    val skip = skipDesktopNative

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

// House pattern from app/build.gradle.kts (UFFI-02): the generated
// `uniffi/streamify/streamify.kt` registers through the java source dir —
// KGP compiles .kt files from java srcDirs, proven by the build-ref CI lane
// on the Android module. The engine resource root joins as resources.
sourceSets.getByName("main") {
    java.srcDir(uniffiGeneratedSourcesDir)
    resources.srcDir(engineResourceDir)
}

// Hermetic degradation (same contract as the app module's JVM unit shard):
// with -Pstreamify.skipRust=true on a toolchain-less host, the JNA smoke
// suite cannot compile against the generated bindings, so its sources are
// excluded from the test compilation entirely and the run reports zero
// tests instead of failing. CI desktop jobs (cargo present) always compile
// and execute the suite for real.
sourceSets.getByName("test") {
    if (skipDesktopNative) {
        java.setSrcDirs(emptyList<String>())
        resources.setSrcDirs(emptyList<String>())
    }
}

// Every Kotlin compilation runs after the bindings exist (house pattern from
// the app module — covers compileKotlin and the test compilation).
tasks.matching { task ->
    task.name.startsWith("compile") && task.name.endsWith("Kotlin")
}.configureEach {
    dependsOn(generateDesktopUniffiBindings)
}

// ── Host-platform engine build + single-arch resource preparation ───────────

val buildDesktopEngineLibrary by tasks.registering(Exec::class) {
    group = "native"
    description = "Builds the streamify_core_rs cdylib for the HOST platform (JNA mode, release, stripped)."

    val crateDir = rustCrateDir
    val outLib = engineLibraryFile
    val skip = skipDesktopNative

    onlyIf { !skip }

    inputs.dir(File(crateDir, "src"))
    inputs.file(File(crateDir, "Cargo.toml"))
    inputs.file(File(crateDir, "Cargo.lock"))
    inputs.file(File(crateDir, "build.rs"))
    outputs.file(outLib)

    workingDir = crateDir
    commandLine("cargo", "build", "--release", "--lib")
}

val prepareDesktopEngineResources by tasks.registering(Sync::class) {
    group = "native"
    description = "Stages the host engine cdylib at the resource root for JNA classpath loading (single-arch by construction)."

    dependsOn(buildDesktopEngineLibrary)
    from(engineLibraryFile)
    into(engineResourceDir)
}

tasks.named("processResources") {
    dependsOn(prepareDesktopEngineResources)
}

// ── Phase 4b deliverable 3: packaging & asset hygiene gate ───────────────────
//
// Runs after every resource processing pass (test, run, package). Enforces
// the distribution hygiene invariants the master plan attaches to the 250MB
// budget: exactly ONE native engine library, matching the CURRENT host
// platform (no multi-architecture duplicates), zero debug-symbol files,
// zero test fixtures, zero Android-ABI artifacts.
val verifyDesktopNativeHygiene by tasks.registering {
    group = "verification"
    description = "Asset hygiene gate: single host-arch engine lib, no debug symbols, no fixtures, no foreign-ABI binaries."

    val packagedResourcesDir = layout.buildDirectory.dir("resources/main")
    val expectedLibName = engineLibraryName
    val skip = skipDesktopNative
    // Hermetic degradation: without a Rust toolchain no engine library is
    // staged (by design) and the packaged resources stay empty — the gate
    // has nothing to audit and must not turn a hermetic run red.
    onlyIf { !skip }
    outputs.upToDateWhen { false }

    doLast {
        val root = packagedResourcesDir.get().asFile
        if (!root.isDirectory) {
            throw GradleException("packaged resources not found at ${root} — processResources has not run")
        }
        val files = root.walkTopDown().filter { it.isFile }.toList()
        if (files.isEmpty()) {
            throw GradleException("packaged resources are empty — the engine library staging did not run")
        }

        val nativeLibs = files.filter { f ->
            f.extension.lowercase() in setOf("so", "dll", "dylib")
        }
        val expected = files.firstOrNull { it.name == expectedLibName }
            ?: throw GradleException(
                "host engine library $expectedLibName missing from packaged resources; found ${files.map { it.name }}"
            )

        // (a) multi-architecture duplicate guard: the distribution carries
        //     exactly one engine binary — the current OS/arch slice.
        if (nativeLibs.size != 1 || nativeLibs[0] != expected) {
            throw GradleException(
                "multi-architecture native binaries are forbidden in the desktop distribution " +
                    "(expected only [$expectedLibName], found ${nativeLibs.map { it.name }})"
            )
        }

        // (b) debug-symbol files: jpackage images must never carry .pdb/.dbg/
        //     .dwarf/.o sidecars (the cdylib itself is stripped by
        //     rust/Cargo.toml [profile.release] strip = true).
        val symbolOffenders = files.filter { f ->
            val n = f.name.lowercase()
            n.endsWith(".pdb") || n.endsWith(".dbg") || n.endsWith(".dwarf") ||
                n.endsWith(".dsym") || n.endsWith(".o") || n.endsWith(".obj")
        }
        if (symbolOffenders.isNotEmpty()) {
            throw GradleException(
                "debug symbol files leaked into packaged resources: ${symbolOffenders.map { it.name }}"
            )
        }

        // (c) test fixtures and golden payloads (rust/tests/fixtures/**)
        //     must never reach the runtime classpath.
        val fixtureOffenders = files.filter { f ->
            val n = f.name.lowercase()
            n.endsWith(".json") || n.endsWith(".mp3") || n.endsWith(".wav") || n.endsWith(".flac")
        }
        if (fixtureOffenders.isNotEmpty()) {
            throw GradleException(
                "test fixture payloads leaked into packaged resources: ${fixtureOffenders.map { it.name }}"
            )
        }

        // (d) Android-ABI artifacts (jniLibs layouts, ABI-tagged .so names).
        val androidOffenders = files.filter { f ->
            val p = f.relativeTo(root).path.lowercase()
            p.contains("jnilibs") || p.contains("arm64-v8a") || p.contains("armeabi") ||
                f.name.lowercase().contains("android")
        }
        if (androidOffenders.isNotEmpty()) {
            throw GradleException(
                "Android-ABI artifacts leaked into desktop resources: ${androidOffenders.map { it.name }}"
            )
        }

        // Advisory only: a release+stripped engine this size almost certainly
        // means an unstripped or debug-flavored build sneaked in.
        if (expected.length() > 48L * 1024 * 1024) {
            logger.warn(
                "engine library $expectedLibName is ${expected.length() / (1024 * 1024)} MiB — " +
                    "verify the cargo release profile (strip = true) is active"
            )
        }

        logger.lifecycle(
            "desktop asset hygiene: OK (1 native library [{}], {} file(s), {} bytes total)",
            expectedLibName, files.size, files.sumOf { it.length() }
        )
    }
}

tasks.named("processResources") {
    finalizedBy(verifyDesktopNativeHygiene)
}

// ── Phase 4b deliverable 1: MP-HAL10 size-budget enforcement (< 250 MiB) ────
//
// Measures every distributable artifact under build/compose/binaries — each
// produced installer (.deb/.msi/…) AND the jpackage app image — against the
// master plan threshold: strictly < 250 MiB (262,144,000 bytes = 262.14
// decimal MB). Wired as a finalizer of packageDistributionForCurrentOS so no
// packaging pass (local or CI) can skip the audit; re-runnable standalone for
// the CI report step.
val verifyDesktopSizeBudget by tasks.registering {
    group = "verification"
    description = "MP-HAL10: enforces the packaged desktop distribution size budget (strictly < 250 MiB) with a budget breakdown."

    val binariesRoot = layout.buildDirectory.dir("compose/binaries")
    val budgetBytes = 250L * 1024L * 1024L
    outputs.upToDateWhen { false }

    doLast {
        val root = binariesRoot.get().asFile
        if (!root.isDirectory) {
            throw GradleException(
                "no packaged desktop distribution under ${root} — run :desktop:packageDistributionForCurrentOS first"
            )
        }

        data class Measured(val label: String, val bytes: Long, val kind: String)

        val installerExtensions = setOf("msi", "exe", "deb", "rpm", "dmg", "pkg")
        val installers = root.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in installerExtensions }
            .map { Measured(it.name, it.length(), "installer") }
            .toList()

        // App images live at compose/binaries/<app>/app/<packageName>/ — a
        // directory directly under an "app" directory.
        val appImages = root.walkTopDown()
            .filter { it.isDirectory && it.parentFile?.name == "app" }
            .map { dir ->
                val size = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                Measured("app-image[${dir.relativeTo(root).path}]", size, "app-image")
            }
            .toList()

        val artifacts = installers + appImages
        if (artifacts.isEmpty()) {
            throw GradleException(
                "no distribution artifacts (installers or app image) found under ${root}"
            )
        }

        val mib = 1024.0 * 1024.0
        logger.lifecycle("MP-HAL10 desktop size budget report — ${root}")
        logger.lifecycle(
            "budget: strictly < 250 MiB ({} bytes; {} decimal MB)",
            budgetBytes, String.format("%.2f", budgetBytes / mib)
        )

        var violations = 0
        artifacts.sortedByDescending { it.bytes }.forEach { a ->
            val over = a.bytes >= budgetBytes
            if (over) violations++
            logger.lifecycle(
                "  [{}] {} — {} MiB ({} MB){}",
                a.kind, a.label,
                String.format("%.2f", a.bytes / mib),
                String.format("%.2f", a.bytes / 1_000_000.0),
                if (over) "  << OVER BUDGET" else ""
            )
        }

        if (violations > 0) {
            logger.lifecycle("budget breakdown — top offenders:")
            appImages.forEach { image ->
                val imageDir = root.resolve(image.label.removePrefix("app-image[").removeSuffix("]"))
                imageDir.walkTopDown()
                    .filter { it.isFile }
                    .sortedByDescending { it.length() }
                    .take(20)
                    .forEach { f ->
                        logger.lifecycle(
                            "    {} MiB  {}",
                            String.format("%10.2f", f.length() / mib),
                            f.relativeTo(root).path
                        )
                    }
            }
            throw GradleException(
                "MP-HAL10 size budget violation: $violations desktop artifact(s) >= 250 MiB — " +
                    "strip debug symbols, drop duplicate multi-architecture binaries, or trim the " +
                    "bundled runtime before landing"
            )
        }
        logger.lifecycle("MP-HAL10 size budget: PASS ({} artifact(s) audited)", artifacts.size)
    }
}

// The audit can never be forgotten: every packaging pass is finalized by it.
tasks.matching { it.name == "packageDistributionForCurrentOS" }.configureEach {
    finalizedBy(verifyDesktopSizeBudget)
}

// ── Test JVM: JNA-mode wiring for the smoke suite ───────────────────────────

tasks.test {
    useJUnit()

    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }

    // UDL-mode codegen derives the library name "uniffi_streamify" (namespace
    // fallback); pin it to the real cdylib so JNA resolves the staged
    // resource. See the module header notes for the durable rust/uniffi.toml
    // fix (Alpha's subtree).
    systemProperty("uniffi.component.streamify.libraryOverride", "streamify_core_rs")

    // Belt and braces: direct load from the cargo output dir wins over the
    // classpath extraction, and the classpath copy keeps IDE runs working.
    systemProperty("jna.library.path", rustTargetReleaseDir.absolutePath)
    systemProperty("java.library.path", rustTargetReleaseDir.absolutePath)

    // Headless by construction: the suite exercises the engine FFI, never a
    // Compose window. Bounded heap keeps the 2-core CI runners comfortable.
    maxHeapSize = "1g"
}
