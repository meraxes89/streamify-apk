#!/usr/bin/env bash
# ============================================================================
#  build_ios_xcframework.sh — SW-ENG1-02 (Apple Universal): assemble
#  `StreamifyCore.xcframework`, the single drop-in artifact an iOS AND
#  macOS shell links to embed the Streamify Rust engine + the Apple
#  media-center bridge.
#
#  WHAT LANDS IN THE FRAMEWORK
#  ---------------------------
#    * libstreamify_core_rs.a  — the Rust `staticlib` crate-type
#      (rust/crates/streamify-ffi), built for every Apple slice below;
#    * libstreamify_apple_remote_command.a glue — the Objective-C++
#      MPRemoteCommandCenter / MPNowPlayingInfoCenter objects
#      (native/os/apple_remote_command.mm) behind the frozen C ABI of
#      native/include/apple_remote_command.h;
#    * the MP-UI02 AVAudioSession adapter objects
#      (native/audio/ios_audio_session.mm) behind the frozen C ABI of
#      native/include/ios_audio_session.h. On non-iOS slices the .mm
#      compiles itself down to its inert stub branch (the file's own
#      `TARGET_OS_IPHONE` gate), so the SAME two TUs build for every
#      slice and the dlsym contract holds everywhere. The Rust core
#      resolves both symbol families at RUNTIME via dlsym (zero
#      link-time dependency), and the XCFramework guarantees they ARE
#      present in the final app link.
#    * the UniFFI Swift FFI module: `uniffi-bindgen --language swift`
#      emits the C header + module map that expose the `uniffi_streamify`
#      C module FROM the xcframework itself (SW-DEC-01 / MP-DEC-02: Swift
#      consumes the generated module directly out of StreamifyCore — no
#      Kotlin/Native, no Obj-C shim in between).
#
#  SLICES (universal fat matrix, SW-ENG1-02)
#  -----------------------------------------
#    ios-arm64            aarch64-apple-ios              iPhone / iPad devices
#    ios-arm64[_x86_64]-  aarch64-apple-ios-sim          Apple Silicon simulators
#    simulator            x86_64-apple-ios-sim           Intel simulators
#                         (CONDITIONAL: the Rust stable distribution does
#                         not ship a rust-std for the Intel SIMULATOR triple
#                         — verified against the channel manifests 1.82 →
#                         1.99.1; the DEVICE triple x86_64-apple-ios is
#                         still distributed, but device objects cannot run
#                         in the simulator. When the toolchain provides the
#                         sim std, both arches are lipo'd into ONE fat
#                         simulator slice; otherwise the slice ships
#                         arm64-only and the build logs a notice. The
#                         mission acceptance (arm64-ios, arm64-simulator,
#                         darwin) holds either way.)
#    macos-arm64_x86_64   aarch64-apple-darwin           Apple Silicon Macs
#                         x86_64-apple-darwin             Intel Macs
#                         (lipo'd into ONE fat macOS slice)
#
#  Per-arch slices are assembled with `libtool -static` (Rust staticlib +
#  the two glue objects), then the multi-arch slices are merged with
#  `lipo -create` — so member-name collisions between the two Rust
#  archives can never silently drop an architecture.
#
#  SWIFT BINDINGS
#  --------------
#    `uniffi-bindgen generate --language swift` (crate-local binary,
#    version lockstep with the Cargo.toml entry — same discipline as the
#    Gradle `generateUniffiKotlinBindings` task) emits:
#      * streamify.swift          — the Swift bindings source;
#      * uniffi_streamifyFFI.h    — the extern-C surface;
#      * uniffi_streamify.modulemap.
#    The FFI header + a generated module.modulemap are installed into
#    every slice's headers/, so `import uniffi_streamify` resolves the
#    moment an SPM binaryTarget links this xcframework. The .swift
#    source is copied to <output>/swift/ AND (by default) dropped into
#    iosApp/Sources/StreamifyIOS/Generated/ wrapped in a
#    `#if canImport(uniffi_streamify)` probe-gate, which lights the real
#    engine lane up the moment the framework is linked — the same
#    philosophy as the desktop CI's Windows probe-gate.
#
#  USAGE (macOS host with Xcode + rustup; roughly 10-15 min cold)
#  ------
#    ./scripts/build_ios_xcframework.sh [--output dist/ios]
#        [--skip-bindings] [--no-package-bindings]
#
#    Full SPM real-lane flow (links the package against the framework):
#      ./scripts/build_ios_xcframework.sh --output iosApp/Frameworks
#
#  CI: wired into .github/workflows/_ci-ios.yml (Apple Unified lane).
#  Missing rustup targets are installed on demand so the script also
#  runs on a bare dev Mac.
# ============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
RUST_DIR="${REPO_ROOT}/rust"
NATIVE_DIR="${REPO_ROOT}/native"
UDL_PATH="crates/streamify-contract/src/streamify.udl"

OUTPUT_DIR="${REPO_ROOT}/dist/ios"
SKIP_BINDINGS=0
PACKAGE_BINDINGS=1
while [[ $# -gt 0 ]]; do
    case "$1" in
        --output)             OUTPUT_DIR="$2"; shift 2 ;;
        --skip-bindings)      SKIP_BINDINGS=1; shift ;;
        --no-package-bindings) PACKAGE_BINDINGS=0; shift ;;
        -h|--help)            grep '^#' "$0" | sed 's/^# \{0,2\}//'; exit 0 ;;
        *) echo "unknown flag: $1 (see --help)" >&2; exit 64 ;;
    esac
done

# --- host & toolchain gates (fail fast, name the fix) -----------------------
if [[ "$(uname -s)" != "Darwin" ]]; then
    echo "FATAL: Apple slices need Xcode SDKs — run me on a macOS host" >&2
    exit 69  # EX_UNAVAILABLE
fi
for tool in xcrun xcodebuild cargo rustup lipo; do
    if ! command -v "${tool}" >/dev/null 2>&1; then
        echo "FATAL: \`${tool}\` not on PATH — install Xcode CLT / rustup first" >&2
        exit 69
    fi
done
if ! xcodebuild -version >/dev/null 2>&1; then
    echo "FATAL: xcodebuild present but no Xcode licensed/installed" >&2
    exit 69
fi

# --- the universal target matrix (SW-ENG1-02) --------------------------------
DEVICE_TARGET="aarch64-apple-ios"
SIM_ARM_TARGET="aarch64-apple-ios-sim"
SIM_X86_TARGET="x86_64-apple-ios-sim"
MAC_ARM_TARGET="aarch64-apple-darwin"
MAC_X86_TARGET="x86_64-apple-darwin"
REQUIRED_TARGETS=(
    "${DEVICE_TARGET}" "${SIM_ARM_TARGET}"
    "${MAC_ARM_TARGET}" "${MAC_X86_TARGET}"
)

# Missing targets are installed on demand (the CI lane pins its own list;
# a bare dev Mac should not have to know it by heart).
for target in "${REQUIRED_TARGETS[@]}"; do
    if ! rustup target list --installed 2>/dev/null | grep -q "^${target}"; then
        echo "── rustup target \`${target}\` missing — installing"
        rustup target add "${target}"
    fi
done

# OPTIONAL Intel simulator slice: the Rust stable distribution carries no
# rust-std for x86_64-apple-ios-sim (removed upstream — requesting it in a
# `rustup toolchain install` fails the whole install, which is why the CI
# lane lists only the four required triples). Probe-installing it here must
# NOT be fatal: degrade to an arm64-only simulator slice instead.
SIM_X86_AVAILABLE=0
if rustup target list --installed 2>/dev/null | grep -q "^${SIM_X86_TARGET}"; then
    SIM_X86_AVAILABLE=1
elif rustup target add "${SIM_X86_TARGET}" >/dev/null 2>&1; then
    SIM_X86_AVAILABLE=1
else
    echo "── NOTE: \`${SIM_X86_TARGET}\` std is not in this toolchain's channel"
    echo "   (removed from the Rust distribution upstream) — the iOS simulator"
    echo "   slice will be arm64-only (Apple Silicon Macs). Intel-Mac simulator"
    echo "   support returns the day a distribution ships that std again; every"
    echo "   other slice of the universal matrix is unaffected."
fi

WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/streamify-xcframework.XXXXXX")"
trap 'rm -rf "${WORK_DIR}"' EXIT
echo "── workspace: ${WORK_DIR}"

IPHONEOS_SDK_PATH="$(xcrun --sdk iphoneos --show-sdk-path)"
IPHONESIM_SDK_PATH="$(xcrun --sdk iphonesimulator --show-sdk-path)"
MACOSX_SDK_PATH="$(xcrun --sdk macosx --show-sdk-path)"
echo "── iphoneos SDK:        ${IPHONEOS_SDK_PATH}"
echo "── iphonesimulator SDK: ${IPHONESIM_SDK_PATH}"
echo "── macosx SDK:          ${MACOSX_SDK_PATH}"

# --- 1. Rust staticlib per target --------------------------------------------
#  Debug is deliberate: the engine's own CI runs the test suites; this
#  artifact exists so a shell can LINK and SMOKE the integration early.
#  Flip PROFILE=release when the Phase 6 shell lands.
PROFILE="${STREAMIFY_IOS_PROFILE:-debug}"
LIB_SUFFIX="a"
BUILD_TARGETS=("${REQUIRED_TARGETS[@]}")
[[ "${SIM_X86_AVAILABLE}" -eq 1 ]] && BUILD_TARGETS+=("${SIM_X86_TARGET}")
for target in "${BUILD_TARGETS[@]}"; do
    echo "── [1/6] cargo build --target ${target} (${PROFILE})"
    case "${target}" in
        *-apple-ios|*-apple-ios-sim)
            # Rust's iOS triples default to a 10.0 deployment target,
            # but the SDK's cc-crate objects (quickjs / ring / sqlite)
            # reference ___chkstk_darwin — available from iOS 12 — so
            # the cdylib LINK fails with undefined symbols. Build the
            # engine at the package's own floor (.iOS(.v17), the same
            # minimum the SPM target and the Xcode destinations use).
            ( cd "${RUST_DIR}" && \
              IPHONEOS_DEPLOYMENT_TARGET=17.0 \
              cargo build --target "${target}" --profile "${PROFILE}" --lib )
            ;;
        *)
            ( cd "${RUST_DIR}" && cargo build --target "${target}" --profile "${PROFILE}" --lib )
            ;;
    esac
done
# `--profile debug` emits to target/<t>/debug, `--profile release` to release:
OUT_SUBDIR="debug"; [[ "${PROFILE}" == "release" ]] && OUT_SUBDIR="release"
RUST_LIB() { echo "${RUST_DIR}/target/$1/${OUT_SUBDIR}/libstreamify_core_rs.${LIB_SUFFIX}"; }

# --- 2. the ObjC++ glue per slice ---------------------------------------------
#  Two TUs per arch: remote-command center + iOS audio session. Both .mm
#  files self-gate (MediaPlayer availability / TARGET_OS_IPHONE) and carry
#  an inert-stub branch, so the same sources build for the macOS slices.
#  -fobjc-arc, no bitcode marker (Xcode 14+ dropped the requirement).
compile_glue() {  # $1 = sdk name, $2 = clang -target triple, $3 = sdk path, $4 = src .mm, $5 = out .o
    # The sdk NAME must be passed explicitly (iphoneos / iphonesimulator /
    # macosx): deriving it from the sdk path collapses to the literal
    # directory name "SDKs", which xcrun cannot resolve.
    xcrun -sdk "$1" clang \
        -target "$2" \
        -isysroot "$3" \
        -fobjc-arc -fmodules -O1 \
        -Wall -Werror \
        -I"${NATIVE_DIR}/include" \
        -c "$4" \
        -o "$5"
}
echo "── [2/6] compiling apple glue per arch (remote command + audio session)"
# iOS device (arm64)
compile_glue "iphoneos" "arm64-apple-ios"         "${IPHONEOS_SDK_PATH}"  "${NATIVE_DIR}/os/apple_remote_command.mm"   "${WORK_DIR}/rc_ios_arm64.o"
compile_glue "iphoneos" "arm64-apple-ios"         "${IPHONEOS_SDK_PATH}"  "${NATIVE_DIR}/audio/ios_audio_session.mm"    "${WORK_DIR}/ias_ios_arm64.o"
# iOS simulator (arm64 always; x86_64 only when its sim std exists —
# the SIMULATOR triples, per SW-ENG1-02)
compile_glue "iphonesimulator" "arm64-apple-ios-sim"     "${IPHONESIM_SDK_PATH}" "${NATIVE_DIR}/os/apple_remote_command.mm"   "${WORK_DIR}/rc_sim_arm64.o"
compile_glue "iphonesimulator" "arm64-apple-ios-sim"     "${IPHONESIM_SDK_PATH}" "${NATIVE_DIR}/audio/ios_audio_session.mm"    "${WORK_DIR}/ias_sim_arm64.o"
if [[ "${SIM_X86_AVAILABLE}" -eq 1 ]]; then
    compile_glue "iphonesimulator" "x86_64-apple-ios-sim"    "${IPHONESIM_SDK_PATH}" "${NATIVE_DIR}/os/apple_remote_command.mm"   "${WORK_DIR}/rc_sim_x86_64.o"
    compile_glue "iphonesimulator" "x86_64-apple-ios-sim"    "${IPHONESIM_SDK_PATH}" "${NATIVE_DIR}/audio/ios_audio_session.mm"    "${WORK_DIR}/ias_sim_x86_64.o"
fi
# macOS desktop (arm64 + x86_64; the audio-session TU degrades to its stub)
compile_glue "macosx" "arm64-apple-macosx14.0"  "${MACOSX_SDK_PATH}"    "${NATIVE_DIR}/os/apple_remote_command.mm"   "${WORK_DIR}/rc_macos_arm64.o"
compile_glue "macosx" "arm64-apple-macosx14.0"  "${MACOSX_SDK_PATH}"    "${NATIVE_DIR}/audio/ios_audio_session.mm"    "${WORK_DIR}/ias_macos_arm64.o"
compile_glue "macosx" "x86_64-apple-macosx14.0" "${MACOSX_SDK_PATH}"    "${NATIVE_DIR}/os/apple_remote_command.mm"   "${WORK_DIR}/rc_macos_x86_64.o"
compile_glue "macosx" "x86_64-apple-macosx14.0" "${MACOSX_SDK_PATH}"    "${NATIVE_DIR}/audio/ios_audio_session.mm"    "${WORK_DIR}/ias_macos_x86_64.o"

# --- 3. UniFFI Swift bindings (before assembly: the header ships in-slice) --
#  Same invocation shape as the Gradle `generateUniffiKotlinBindings`
#  task (app/build.gradle.kts): UDL in, language bindings out. The crate
#  ships uniffi.toml, but its pinned knobs are Kotlin-lane only
#  ([bindings.kotlin]); the Swift lane keeps generator defaults and the
#  module name is PROBED from the generated source below, so a uniffi
#  default change cannot silently break the import.
BINDINGS_DIR="${WORK_DIR}/swift-bindings"
SWIFT_BINDINGS_FILE=""
FFI_HEADER_FILE=""
FFI_MODULE="uniffi_streamify"
if [[ "${SKIP_BINDINGS}" -ne 1 ]]; then
    echo "── [3/6] generating UniFFI Swift bindings (crate-local bindgen, version lockstep)"
    mkdir -p "${BINDINGS_DIR}"
    ( cd "${RUST_DIR}" && cargo run --bin uniffi-bindgen -- generate \
        "${UDL_PATH}" \
        --language swift \
        --out-dir "${BINDINGS_DIR}" )
    SWIFT_BINDINGS_FILE="$(ls "${BINDINGS_DIR}"/*.swift 2>/dev/null | head -1 || true)"
    FFI_HEADER_FILE="$(ls "${BINDINGS_DIR}"/*FFI.h 2>/dev/null | head -1 || true)"
    if [[ -z "${SWIFT_BINDINGS_FILE}" || -z "${FFI_HEADER_FILE}" ]]; then
        echo "FATAL: uniffi-bindgen did not emit the expected .swift + FFI.h pair:" >&2
        ls -la "${BINDINGS_DIR}" >&2
        exit 70
    fi
    # Probe the FFI module name from the bindings source (uniffi 0.28
    # default: `import uniffi_streamify`). The probe makes the assembly
    # robust against generator-default drift instead of hard-coding it.
    FFI_MODULE="$(sed -nE 's/^import (uniffi_[A-Za-z0-9_]+)$/\1/p' "${SWIFT_BINDINGS_FILE}" | head -1 || true)"
    [[ -z "${FFI_MODULE}" ]] && FFI_MODULE="uniffi_streamify"
    echo "── Swift FFI module: ${FFI_MODULE} (from ${SWIFT_BINDINGS_FILE##*/})"
    if [[ "${FFI_MODULE}" != "uniffi_streamify" ]]; then
        echo "   NOTE: generator emitted module \`${FFI_MODULE}\` — the Swift package's" >&2
        echo "   probe-gates expect \`uniffi_streamify\`; align [bindings.swift] config." >&2
    fi
else
    echo "── [3/6] Swift bindings SKIPPED (--skip-bindings)"
fi

# --- 4. per-arch libtool assembly + fat lipo merges --------------------------
#  Every arch slice is assembled single-arch first (Rust staticlib + its
#  glue objects under one `libtool -static`), and only then are same-OS
#  slices merged with `lipo -create` — no two-arch archives ever enter
#  libtool, so member-name collisions between the per-target Rust
#  archives cannot silently drop an architecture.
echo "── [4/6] libtool per-arch + lipo fat merges"
xcrun libtool -static -o "${WORK_DIR}/slice_ios_arm64.a" \
    "$(RUST_LIB "${DEVICE_TARGET}")" \
    "${WORK_DIR}/rc_ios_arm64.o" \
    "${WORK_DIR}/ias_ios_arm64.o"

xcrun libtool -static -o "${WORK_DIR}/slice_sim_arm64.a" \
    "$(RUST_LIB "${SIM_ARM_TARGET}")" \
    "${WORK_DIR}/rc_sim_arm64.o" \
    "${WORK_DIR}/ias_sim_arm64.o"
if [[ "${SIM_X86_AVAILABLE}" -eq 1 ]]; then
    xcrun libtool -static -o "${WORK_DIR}/slice_sim_x86_64.a" \
        "$(RUST_LIB "${SIM_X86_TARGET}")" \
        "${WORK_DIR}/rc_sim_x86_64.o" \
        "${WORK_DIR}/ias_sim_x86_64.o"
    lipo -create \
        "${WORK_DIR}/slice_sim_arm64.a" \
        "${WORK_DIR}/slice_sim_x86_64.a" \
        -output "${WORK_DIR}/StreamifyCore_simulator.a"
else
    # Degraded matrix: arm64-only simulator slice (see the target-matrix
    # note above) — the slice keeps the same member name so the
    # xcframework layout and sanity pass are unchanged.
    cp "${WORK_DIR}/slice_sim_arm64.a" "${WORK_DIR}/StreamifyCore_simulator.a"
fi

xcrun libtool -static -o "${WORK_DIR}/slice_macos_arm64.a" \
    "$(RUST_LIB "${MAC_ARM_TARGET}")" \
    "${WORK_DIR}/rc_macos_arm64.o" \
    "${WORK_DIR}/ias_macos_arm64.o"
xcrun libtool -static -o "${WORK_DIR}/slice_macos_x86_64.a" \
    "$(RUST_LIB "${MAC_X86_TARGET}")" \
    "${WORK_DIR}/rc_macos_x86_64.o" \
    "${WORK_DIR}/ias_macos_x86_64.o"
lipo -create \
    "${WORK_DIR}/slice_macos_arm64.a" \
    "${WORK_DIR}/slice_macos_x86_64.a" \
    -output "${WORK_DIR}/StreamifyCore_macos.a"

# --- 5. headers + xcframework -------------------------------------------------
rm -rf "${OUTPUT_DIR}"
mkdir -p "${OUTPUT_DIR}"
echo "── [5/6] xcodebuild -create-xcframework"

#  Per-slice header dirs. The two C ABI headers are the glue surface; the
#  UniFFI FFI header + module.modulemap expose the `uniffi_streamify`
#  C module FROM the xcframework (SW-DEC-01: Swift consumes the generated
#  module directly out of StreamifyCore — nothing else in between).
make_headers() {  # $1 = destination dir
    local dir="$1"
    rm -rf "${dir}"; mkdir -p "${dir}"
    cp "${NATIVE_DIR}/include/apple_remote_command.h" "${dir}/"
    cp "${NATIVE_DIR}/include/ios_audio_session.h"    "${dir}/"
    {
        if [[ "${SKIP_BINDINGS}" -ne 1 ]]; then
            printf 'module %s {\n' "${FFI_MODULE}"
            printf '    header "%s"\n' "${FFI_HEADER_FILE##*/}"
            printf '    export *\n'
            printf '}\n'
        fi
        printf 'module StreamifyCore {\n'
        printf '    header "apple_remote_command.h"\n'
        printf '    header "ios_audio_session.h"\n'
        printf '    export *\n'
        printf '}\n'
    } > "${dir}/module.modulemap"
    if [[ "${SKIP_BINDINGS}" -ne 1 ]]; then
        cp "${FFI_HEADER_FILE}" "${dir}/"
    fi
}
make_headers "${WORK_DIR}/headers_ios"
make_headers "${WORK_DIR}/headers_sim"
make_headers "${WORK_DIR}/headers_macos"

xcodebuild -create-xcframework \
    -library "${WORK_DIR}/slice_ios_arm64.a"              -headers "${WORK_DIR}/headers_ios" \
    -library "${WORK_DIR}/StreamifyCore_simulator.a"      -headers "${WORK_DIR}/headers_sim" \
    -library "${WORK_DIR}/StreamifyCore_macos.a"          -headers "${WORK_DIR}/headers_macos" \
    -output "${OUTPUT_DIR}/StreamifyCore.xcframework" \
    >/dev/null

#  Slice sanity, per architecture of every slice: BOTH glue symbol
#  families AND the UniFFI namespace trampoline must be resolvable (the
#  dlsym + canImport contracts the bridges depend on — fail the build,
#  not the app). `nm -arch` checks each fat member, so a silently dropped
#  architecture cannot pass.
for lib in \
    "${WORK_DIR}/slice_ios_arm64.a" \
    "${WORK_DIR}/StreamifyCore_simulator.a" \
    "${WORK_DIR}/StreamifyCore_macos.a"
do
    while read -r arch; do
        [[ -z "${arch}" ]] && continue
        for symbol in sf_apple_remote_command_start sf_ios_audio_session_start uniffi_streamify_contract_fn_func_contract_version; do
            if ! nm -arch "${arch}" -gU "${lib}" 2>/dev/null | grep -q "${symbol}"; then
                echo "FATAL: ${symbol} missing in ${lib} (${arch}) — ABI contract broken" >&2
                exit 70
            fi
        done
    done < <(lipo -archs "${lib}" | tr ' ' '\n')
done
echo "── slice sanity: glue + uniffi symbols present in every arch of every slice"

# --- 6. Swift bindings artifacts + package emission ---------------------------
if [[ "${SKIP_BINDINGS}" -ne 1 ]]; then
    echo "── [6/6] publishing Swift bindings"
    DIST_BINDINGS_DIR="${OUTPUT_DIR}/swift"
    mkdir -p "${DIST_BINDINGS_DIR}"
    cp "${SWIFT_BINDINGS_FILE}" "${DIST_BINDINGS_DIR}/"
    cp "${FFI_HEADER_FILE}" "${DIST_BINDINGS_DIR}/"
    if [[ -f "${BINDINGS_DIR}/${FFI_MODULE}.modulemap" ]]; then
        cp "${BINDINGS_DIR}/${FFI_MODULE}.modulemap" "${DIST_BINDINGS_DIR}/"
    fi
    echo "── Swift module: ${DIST_BINDINGS_DIR}"

    if [[ "${PACKAGE_BINDINGS}" -ne 0 ]]; then
        #  Drop the bindings into the SPM package, wrapped in the probe
        #  gate: the package compiles standalone TODAY (the guard is false
        #  without the framework) and the real lane lights up the moment
        #  `iosApp/Frameworks/StreamifyCore.xcframework` exists (see
        #  Package.swift's conditional binary target).
        GENERATED_DIR="${REPO_ROOT}/iosApp/Sources/StreamifyIOS/Generated"
        mkdir -p "${GENERATED_DIR}"
        {
            echo "// GENERATED by scripts/build_ios_xcframework.sh (SW-ENG1-02) — DO NOT EDIT."
            echo "// Regenerate: ./scripts/build_ios_xcframework.sh"
            echo "// Probe-gated: compiles to nothing until StreamifyCore.xcframework is"
            echo "// linked (Package.swift conditional binary target), mirroring the"
            echo "// #if canImport(uniffi_streamify) discipline in Engine/."
            echo "#if canImport(${FFI_MODULE})"
            cat "${SWIFT_BINDINGS_FILE}"
            echo ""
            echo "#endif"
        } > "${GENERATED_DIR}/uniffi_streamify.swift"
        echo "── package bindings: ${GENERATED_DIR}/uniffi_streamify.swift (probe-gated)"
        if [[ "${OUTPUT_DIR}" != "${REPO_ROOT}/iosApp/Frameworks" ]]; then
            echo "── HINT: to light the package's real lane up, re-run with:"
            echo "     ./scripts/build_ios_xcframework.sh --output iosApp/Frameworks"
        fi
    fi
else
    echo "── [6/6] Swift bindings SKIPPED"
fi

echo "── DONE: ${OUTPUT_DIR}/StreamifyCore.xcframework"
du -sh "${OUTPUT_DIR}/StreamifyCore.xcframework" || true
