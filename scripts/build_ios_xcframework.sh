#!/usr/bin/env bash
# ============================================================================
#  build_ios_xcframework.sh — Phase 5 (MP-CAP03 / Multiplatform invariant 2):
#  assemble `StreamifyCore.xcframework`, the single drop-in artifact an iOS
#  shell links to embed the Streamify Rust engine + the Apple media-center
#  bridge.
#
#  WHAT LANDS IN THE FRAMEWORK
#  ---------------------------
#    * libstreamify_core_rs.a  — the Rust `staticlib` crate-type
#      (rust/Cargo.toml), built for every iOS slice below;
#    * libstreamify_apple_remote_command.a — the Objective-C++
#      MPRemoteCommandCenter / MPNowPlayingInfoCenter glue
#      (native/os/apple_remote_command.mm) behind the frozen C ABI of
#      native/include/apple_remote_command.h;
#    * the MP-UI02 AVAudioSession adapter
#      (native/audio/ios_audio_session.mm) behind the frozen C ABI of
#      native/include/ios_audio_session.h: AVAudioSessionCategoryPlayback
#      + mix/duck options, interruption pause/resume, pause on route
#      loss, clean AirPlay teardown.
#      The Rust core resolves both symbol families at RUNTIME via
#      dlsym (zero link-time dependency — the Phase 4b dlopen
#      discipline), and the XCFramework guarantees they ARE present
#      in the final app link.
#
#  SLICES
#  ------
#    aarch64-apple-ios       — iPhone / iPad devices
#    aarch64-apple-ios-sim   — Apple Silicon simulators
#    x86_64-apple-ios        — Intel simulators
#    The two simulator slices are lipo'd into one fat simulator library,
#    so the xcframework carries 3 library headers: device / sim / macabi-free.
#
#  SWIFT BINDINGS
#  --------------
#    The UniFFI Swift module is generated from the same UDL contract as the
#    Kotlin one (`rust/src/streamify.udl`, uniffi 0.28) via the crate-local
#    bindgen binary — version lockstep is structural (same Cargo.toml
#    entry), the same discipline as the Gradle `generateUniffiKotlinBindings`
#    task. Output: swift/StreamifyCore.swift + modulemaps, to be dropped
#    into the Xcode project next to the xcframework.
#
#  USAGE (macOS host with Xcode + rustup; roughly 8-12 min cold)
#  ------
#    ./scripts/build_ios_xcframework.sh [--output dist/ios] [--skip-bindings]
#
#  CI NOTE: this script is NOT wired into .github/workflows (the hosted
#  macOS lanes stay on the Android/JNI contract); it is the release-engineer
#  entry point for the future iOS shell (Multiplatform Phase 6) and the
#  audit trail for how the artifact is assembled.
# ============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
RUST_DIR="${REPO_ROOT}/rust"
NATIVE_DIR="${REPO_ROOT}/native"

OUTPUT_DIR="${REPO_ROOT}/dist/ios"
SKIP_BINDINGS=0
while [[ $# -gt 0 ]]; do
    case "$1" in
        --output)       OUTPUT_DIR="$2"; shift 2 ;;
        --skip-bindings) SKIP_BINDINGS=1; shift ;;
        -h|--help)      grep '^#' "$0" | sed 's/^# \{0,2\}//'; exit 0 ;;
        *) echo "unknown flag: $1 (see --help)" >&2; exit 64 ;;
    esac
done

# --- host & toolchain gates (fail fast, name the fix) -----------------------
if [[ "$(uname -s)" != "Darwin" ]]; then
    echo "FATAL: iOS slices need Xcode SDKs — run me on a macOS host" >&2
    exit 69  # EX_UNAVAILABLE
fi
for tool in xcrun xcodebuild cargo; do
    if ! command -v "${tool}" >/dev/null 2>&1; then
        echo "FATAL: \`${tool}\` not on PATH — install Xcode CLT / rustup first" >&2
        exit 69
    fi
done
if ! xcodebuild -version >/dev/null 2>&1; then
    echo "FATAL: xcodebuild present but no Xcode licensed/installed" >&2
    exit 69
fi

DEVICE_TARGET="aarch64-apple-ios"
SIM_ARM_TARGET="aarch64-apple-ios-sim"
SIM_X86_TARGET="x86_64-apple-ios"
for target in "${DEVICE_TARGET}" "${SIM_ARM_TARGET}" "${SIM_X86_TARGET}"; do
    if ! rustup target list --installed 2>/dev/null | grep -q "^${target}"; then
        echo "FATAL: rust target \`${target}\` missing — fix: rustup target add ${target}" >&2
        exit 69
    fi
done

WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/streamify-xcframework.XXXXXX")"
trap 'rm -rf "${WORK_DIR}"' EXIT
echo "── workspace: ${WORK_DIR}"

IPHONEOS_SDK_PATH="$(xcrun --sdk iphoneos --show-sdk-path)"
IPHONESIM_SDK_PATH="$(xcrun --sdk iphonesimulator --show-sdk-path)"
echo "── iphoneos SDK:        ${IPHONEOS_SDK_PATH}"
echo "── iphonesimulator SDK: ${IPHONESIM_SDK_PATH}"

# --- 1. Rust staticlib per slice --------------------------------------------
#  Debug is deliberate: the engine's own CI runs the test suites; this
#  artifact exists so a shell can LINK and SMOKE the integration early.
#  Flip PROFILE=release when the Phase 6 shell lands.
PROFILE="${STREAMIFY_IOS_PROFILE:-debug}"
LIB_SUFFIX="a"
for target in "${DEVICE_TARGET}" "${SIM_ARM_TARGET}" "${SIM_X86_TARGET}"; do
    echo "── [1/4] cargo build --target ${target} (${PROFILE})"
    ( cd "${RUST_DIR}" && cargo build --target "${target}" --profile "${PROFILE}" --lib )
done
# `--profile debug` emits to target/<t>/debug, `--profile release` to release:
OUT_SUBDIR="debug"; [[ "${PROFILE}" == "release" ]] && OUT_SUBDIR="release"

# --- 2. the ObjC++ glue per slice ---------------------------------------------
#  Two TUs, three compiles each: remote-command center + iOS audio
#  session. -fobjc-arc, arm64 slice needs no bitcode marker (Xcode 14+
#  dropped the requirement entirely).
compile_glue() {  # $1 = clang triple, $2 = sdk path, $3 = src .mm, $4 = out .o
    xcrun -sdk "$(basename "$(dirname "$2")")" clang \
        -target "$1" \
        -isysroot "$2" \
        -fobjc-arc -fmodules -O1 \
        -Wall -Werror \
        -I"${NATIVE_DIR}/include" \
        -c "$3" \
        -o "$4"
}
echo "── [2/4] compiling apple glue per slice (remote command + iOS audio session)"
compile_glue "arm64-apple-ios"        "${IPHONEOS_SDK_PATH}"  "${NATIVE_DIR}/os/apple_remote_command.mm"  "${WORK_DIR}/rc_device.o"
compile_glue "arm64-apple-ios"        "${IPHONEOS_SDK_PATH}"  "${NATIVE_DIR}/audio/ios_audio_session.mm"   "${WORK_DIR}/ias_device.o"
compile_glue "arm64-apple-simulator"  "${IPHONESIM_SDK_PATH}" "${NATIVE_DIR}/os/apple_remote_command.mm"  "${WORK_DIR}/rc_sim_arm64.o"
compile_glue "arm64-apple-simulator"  "${IPHONESIM_SDK_PATH}" "${NATIVE_DIR}/audio/ios_audio_session.mm"  "${WORK_DIR}/ias_sim_arm64.o"
compile_glue "x86_64-apple-simulator" "${IPHONESIM_SDK_PATH}" "${NATIVE_DIR}/os/apple_remote_command.mm"  "${WORK_DIR}/rc_sim_x86_64.o"
compile_glue "x86_64-apple-simulator" "${IPHONESIM_SDK_PATH}" "${NATIVE_DIR}/audio/ios_audio_session.mm"  "${WORK_DIR}/ias_sim_x86_64.o"

#  Each slice = Rust staticlib + glue object, merged with libtool (Apple's
#  ar): the app links ONE library per platform, dlsym finds the glue.
SLICE_LIB() { echo "${WORK_DIR}/StreamifyCore_$1.a"; }

echo "── [3/4] lipo + libtool"
xcrun libtool -static -o "$(SLICE_LIB device)" \
    "${RUST_DIR}/target/${DEVICE_TARGET}/${OUT_SUBDIR}/libstreamify_core_rs.${LIB_SUFFIX}" \
    "${WORK_DIR}/rc_device.o" \
    "${WORK_DIR}/ias_device.o"

xcrun libtool -static -o "${WORK_DIR}/StreamifyCore_simulator.a" \
    "${WORK_DIR}/rc_sim_arm64.o" \
    "${WORK_DIR}/ias_sim_arm64.o" \
    "${WORK_DIR}/rc_sim_x86_64.o" \
    "${WORK_DIR}/ias_sim_x86_64.o" \
    "${RUST_DIR}/target/${SIM_ARM_TARGET}/${OUT_SUBDIR}/libstreamify_core_rs.${LIB_SUFFIX}" \
    "${RUST_DIR}/target/${SIM_X86_TARGET}/${OUT_SUBDIR}/libstreamify_core_rs.${LIB_SUFFIX}"

# --- 4. xcframework + Swift bindings -----------------------------------------
rm -rf "${OUTPUT_DIR}"
mkdir -p "${OUTPUT_DIR}"
echo "── [4/4] xcodebuild -create-xcframework"
rm -rf "${WORK_DIR}/headers"
mkdir -p "${WORK_DIR}/headers"
#  The xcframework wants per-slice header dirs; the two C ABI headers
#  are the only public surface (the Rust side is reached through UniFFI).
cp "${NATIVE_DIR}/include/apple_remote_command.h" "${WORK_DIR}/headers/"
cp "${NATIVE_DIR}/include/ios_audio_session.h" "${WORK_DIR}/headers/"
xcodebuild -create-xcframework \
    -library "$(SLICE_LIB device)"    -headers "${WORK_DIR}/headers" \
    -library "${WORK_DIR}/StreamifyCore_simulator.a" \
    -output "${OUTPUT_DIR}/StreamifyCore.xcframework" \
    >/dev/null

#  Slice sanity: BOTH glue symbol families MUST be resolvable in every
#  slice (the dlsym contracts the Rust bridges depend on — fail the
#  build, not the app).
for lib in "$(SLICE_LIB device)" "${WORK_DIR}/StreamifyCore_simulator.a"; do
    for symbol in sf_apple_remote_command_start sf_ios_audio_session_start; do
        if ! nm -gU "${lib}" 2>/dev/null | grep -q "${symbol}"; then
            echo "FATAL: ${symbol} missing in ${lib} — dlsym contract broken" >&2
            exit 70
        fi
    done
done

if [[ "${SKIP_BINDINGS}" -ne 1 ]]; then
    echo "── generating UniFFI Swift bindings (crate-local bindgen, 0.28 lockstep)"
    BINDINGS_DIR="${OUTPUT_DIR}/swift"
    mkdir -p "${BINDINGS_DIR}"
    #  Same invocation shape as the Gradle `generateUniffiKotlinBindings`
    #  task (app/build.gradle.kts): UDL in, language bindings out. The
    #  crate ships uniffi.toml, but its pinned knobs are Kotlin-lane
    #  only ([bindings.kotlin] package_name/cdylib_name); the Swift lane
    #  deliberately keeps generator defaults (module `streamify` from
    #  the namespace), so no --config is passed here.
    ( cd "${RUST_DIR}" && cargo run --bin uniffi-bindgen -- generate \
        src/streamify.udl \
        --language swift \
        --out-dir "${BINDINGS_DIR}" )
    echo "── Swift module: ${BINDINGS_DIR}"
fi

echo "── DONE: ${OUTPUT_DIR}/StreamifyCore.xcframework"
du -sh "${OUTPUT_DIR}/StreamifyCore.xcframework" || true
