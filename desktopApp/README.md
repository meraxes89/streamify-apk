# desktopApp/ — Compose Desktop client and packaging

The desktop entry point plus packaging configuration. The window, tray, Koin
startup, and MiniPlayer live in `composeApp/src/jvmMain/.../DesktopApp.kt`;
this module contributes `Main.kt`, the build, and Conveyor/jpackage config.

## Entry point

`Main.kt` forces the Linux WM_CLASS to "SimpMusic" (reflective
`sun.awt.X11.XToolkit.awtAppClassName` override) and calls
`runDesktopApp(args)`. `runDesktopApp` initializes Sentry, enforces
single-instance, starts Koin (upstream modules, then
`streamifyAllModules`), and opens a 1500x860 window with a system tray
(now-playing label, open/quit, MiniPlayer toggle). The custom undecorated
title bar is macOS-only; Linux and Windows use native decorations.

## Audio and natives

Desktop playback goes through bundled libmpv slices (per-arch natives staged
by `:composeApp:mpvSetupAll` and packaged by Conveyor). The prebuilt playback
stack handles audio rendering across Windows, Linux, and macOS. The legacy JNA
engine smoke test (`legacy/desktop/.../DesktopJnaEngineSmokeTest.kt`) verifies
the Rust cdylib and engine lifecycle on the desktop classpath; legacy
modules are not part of the shipping build.

## Memory tuning

Observed and configured (details in `MEMORY_TUNING.md`; note the G1 heap-trim
trio documented there was removed from conveyor.conf on 2026-08-28 to fit
Conveyor's 16384-byte launcher option buffer):

- `-Xmx512m` heap ceiling: without it the JVM defaults to 1/4 of physical
  RAM and resident memory sat around 1 GB.
- Linux AppImage `AppRun`: `export MALLOC_ARENA_MAX=2` — measured 161
  arenas / 1.45 GB down to ~5 arenas; RSS 1.90 GB -> ~0.93 GB.
- macOS: `MallocNanoZone = "0"` in the app's `LSEnvironment`.
- Background `MemoryTrimmer` (core submodule) calls `malloc_trim` /
  `HeapSetInformation` on PAUSED/IDLE, throttled to 60 s.

## Packaging (conveyor.conf)

- Identity: `app.fsname = simpmusic`, display name SimpMusic, GPL-3.0
  upstream lineage, update site resolves to GitHub Releases.
- Architectures: `windows.amd64`, `mac.amd64`, `mac.aarch64`,
  `linux.amd64.glibc`. Dropped: Linux ARM64 and Windows ARM64 (no win-arm64
  SQLite JNI driver; x64 runs under Prism emulation).
- Artifacts: Windows `.msix` (WiX 3.14 via jpackage path), hand-wrapped
  Linux AppImage (appimagetool), macOS `.zip` wrapping the .app (unsigned
  when cross-built). No Conveyor-native update channel (`updates = none`).
- JVM: all modules on the module path, per-OS `--add-opens` including
  `java.desktop/sun.awt.X11` for the WM_CLASS reflection.

## Build and CI

```bash
./gradlew :composeApp:jvmTest                          # unit tests
./gradlew :desktopApp:packageDistributionForCurrentOS  # deb/msi/dmg installers
```

The `_ci-desktop.yml` lane (ubuntu + windows matrix) applies `core.patch`,
installs JDK 21 + Rust, downloads the prebuilt engine cdylib from the engine
lane, builds the desktop HAL with CMake, runs `:composeApp:jvmTest`, and
packages distributions.

---
Verified against commit 8b858b1c. Staleness protocol: docs/adr/README.md.
<!-- doc-verified-against: 8b858b1c89b91f643cd7159180706fbd931f925e -->
