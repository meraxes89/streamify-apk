cmake_minimum_required(VERSION 3.22.1)
project(streamify_native_core LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Optimization and Vectorization Flags
set(EXTREME_LTO_FLAGS "-flto")
if(CI_SMOKE_LANE)
    set(EXTREME_LTO_FLAGS "")
endif()

# --- Phase 3 (multiplatform): host sanitizer/CTest lane ---------------------
# ENABLE_ASAN mirrors the Android CI shard's sanitizer discipline on a plain
# host toolchain (gcc or clang, no NDK required): it turns on the standalone
# test suites, injects ASan+UBSan into every compile AND link (CMAKE_*_FLAGS
# reach the link rule), and drops global LTO so the sanitizers see the real
# stack frames. The test targets' own -O2 -fno-fast-math -fno-lto overrides
# keep NaN/overflow guards intact (fast-math would elide them). The Android
# packaging library (streamify_native_core: jni.h, -landroid, -llog) is
# excluded in this mode — it cannot link on a host and the lane exists to
# run `ctest` over the host-built suites (Phase 3 quality gate).
option(ENABLE_ASAN "Build host test suites under AddressSanitizer + UBSan (implies STREAMIFY_BUILD_TESTS, skips the Android-only .so)" OFF)
if(ENABLE_ASAN)
    set(STREAMIFY_BUILD_TESTS ON)
    set(EXTREME_LTO_FLAGS "")
    # Same discipline as the CI native shard (extreme-test-matrix.yml): ASan+
    # UBSan at -O2 with frame pointers — instrumented but still vectorizable,
    # so the 10M-frame SPSC and pacer stress loops stay inside the CI budget.
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fsanitize=address,undefined -fno-omit-frame-pointer -O2 -g")
    set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -fsanitize=address,undefined -fno-omit-frame-pointer -O2 -g")
endif()

set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -ffast-math ${EXTREME_LTO_FLAGS} -fvisibility=hidden -Wall -Wextra")
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O3 -ffast-math ${EXTREME_LTO_FLAGS} -fvisibility=hidden")

if(ENABLE_ASAN)
    # The global -O3/-ffast-math appended above would fight the sanitizer
    # lane's frame-keeping -O2; re-assert the sanitizer tail so the LAST
    # spelling of each switch wins in every compile (fast-math elides the
    # NaN/overflow guards the suites assert on).
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fno-fast-math -O2")
    set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -fno-fast-math -O2")
endif()

if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -march=armv8-a+simd+crypto")
endif()

include_directories(
    agsl
    audio
    dsp
    dsp/kissfft
    engine
    include
    ingest
    jni
    math
    palette
    src
    util
    video
    third_party/sqlite3
)

# The Android packaging library needs the NDK (jni.h, -landroid, -llog);
# the host test lanes (ENABLE_ASAN, and the Phase 4a ENABLE_TESTS alias)
# build ONLY the test suites, so the .so target is skipped entirely there
# (it cannot link on a host). Plain NDK cross-compiles — which pass no
# test flags — keep building the packaging library.
if (NOT ENABLE_ASAN AND NOT ENABLE_TESTS)
add_library(streamify_native_core SHARED
    # Phase-5: SIMD palette clustering + WCAG contrast engine.
    src/palette_extractor_simd.cpp
    # Phase-5: lock-free 120Hz frame pacing & jank monitor.
    src/frame_pacer_monitor.cpp
    # Phase-5: frame pacer wire serialization + process-default instance.
    src/streamify_frame_palette.cpp
    # Phase-4: lock-free SPSC audio sink + SIMD sample converter.
    src/audio_sink_ringbuffer.cpp
    # Phase-4: companion WearOS frame packetizer & rate-limiting compressor.
    src/wear_audio_encoder.cpp
    engine/TaskOrchestrator.cc
    math/CandidateHasher.cc
    math/HarmonicTransitionEngine.cc
    math/CircadianCurves.cc
    video/CanvasLoopMath.cc
    video/CanvasLoopRenderer.cc
    agsl/AmbientGlowShader.cc
    audio/OpusPacketParser.cc
    audio/AdtsFrameParser.cc
    audio/AudioFrameRemuxer.cc
    palette/BitmapPalette.cc
    dsp/LufsNormalizer.cc
    dsp/SoftKneeLimiter.cc
    dsp/AcousticPhaseResampler.cc
    dsp/ChannelOps.cc
    dsp/MasterChain.cc
    dsp/kissfft/kiss_fft.c
    dsp/kissfft/kiss_fftr.c
    engine/VectorStore.cc
    jni/jni_bridge.cc
    jni/jni_bridge_dsp.cc
    engine/StreamifyDB.cc
    engine/RecommendEngine.cc
    engine/EventTracker.cc
    engine/TelemetryEngine.cc
    engine/ChronosProfiler.cc
    engine/PtpEngine.cc
    engine/HardwareLatencyProfiler.cc
    engine/AirDropPhysicsEngine.cc
    ingest/AudioPipeline.cc
    dsp/LyricAligner.cc
    third_party/sqlite3/sqlite3.c
)

target_link_libraries(streamify_native_core
    android
    log
)
endif()  # NOT ENABLE_ASAN (Android packaging library)

# --- STANDALONE TEST TARGETS (Host C++20 with ASan / UBSan) ---
option(STREAMIFY_BUILD_TESTS "Build native test suites" OFF)
# Phase 4a (MP-HAL01): -DENABLE_TESTS=ON is the directive workflow's
# spelling of the test lane ("cmake -B build -S . -DENABLE_TESTS=ON");
# it implies STREAMIFY_BUILD_TESTS so both spellings drive one gate.
option(ENABLE_TESTS "Alias: build the native test suites (implies STREAMIFY_BUILD_TESTS)" OFF)
if(ENABLE_TESTS)
    set(STREAMIFY_BUILD_TESTS ON)
endif()
if (STREAMIFY_BUILD_TESTS)
    # Phase 3 (multiplatform): register the host suites with CTest so the
    # quality gate is a single `ctest --test-dir native/build` invocation
    # (`-DENABLE_ASAN=ON` implies this block and skips the Android .so).
    enable_testing()
    add_executable(dsp_test_suite
        tests/test_dsp.cc
        tests/test_dsp_phase1.cc
        tests/test_harmonic_math.cc
        tests/test_canvas_loop_agsl.cc
        tests/test_audio_remuxer.cc
        tests/test_media_palette.cc
        tests/test_audio_sink_ringbuffer.cpp
        # Phase-5: SIMD palette + WCAG contrast, lock-free frame pacer.
        tests/test_palette_simd.cpp
        tests/test_frame_pacer.cpp
        math/CandidateHasher.cc
        math/HarmonicTransitionEngine.cc
        math/CircadianCurves.cc
        video/CanvasLoopMath.cc
        video/CanvasLoopRenderer.cc
        agsl/AmbientGlowShader.cc
        audio/OpusPacketParser.cc
        audio/AdtsFrameParser.cc
        audio/AudioFrameRemuxer.cc
        palette/BitmapPalette.cc
        # Phase-4: SPSC audio sink + WearOS packetizer under test.
        src/audio_sink_ringbuffer.cpp
        src/wear_audio_encoder.cpp
        # Phase-5: palette clustering + frame pacer under test.
        src/palette_extractor_simd.cpp
        src/frame_pacer_monitor.cpp
        src/streamify_frame_palette.cpp
        dsp/SoftKneeLimiter.cc
        dsp/LufsNormalizer.cc
        dsp/ChannelOps.cc
        dsp/MasterChain.cc
        dsp/AcousticPhaseResampler.cc
        engine/PtpEngine.cc
        dsp/LyricAligner.cc
        dsp/kissfft/kiss_fft.c
        dsp/kissfft/kiss_fftr.c
    )
    target_include_directories(dsp_test_suite PRIVATE dsp dsp/kissfft mix engine math util video agsl audio palette include src .)
    target_link_libraries(dsp_test_suite PRIVATE m pthread)

    add_executable(simd_physics_test_suite
        tests/test_simd_physics.cc
        engine/VectorStore.cc
        engine/AirDropPhysicsEngine.cc
    )
    target_include_directories(simd_physics_test_suite PRIVATE engine .)
    target_link_libraries(simd_physics_test_suite PRIVATE m pthread)

    add_executable(storage_telemetry_test_suite
        tests/test_storage_telemetry.cc
        engine/StreamifyDB.cc
        engine/TelemetryEngine.cc
        engine/ChronosProfiler.cc
        engine/EventTracker.cc
        third_party/sqlite3/sqlite3.c
    )
    target_include_directories(storage_telemetry_test_suite PRIVATE engine third_party/sqlite3 .)
    target_link_libraries(storage_telemetry_test_suite PRIVATE pthread dl)

    # Sub-millisecond acoustic phase-lock core: PTP clock kernel, fractional
    # sinc resampler, hardware/Bluetooth latency profiler (mission brief 3A/B/C).
    # Host C++20, no Android SDK needed; the suite carries its own
    # operator-new allocation guard, so it MUST NOT see -ffast-math/-flto
    # (fast-math elides the NaN/overflow guards it asserts on).
    add_executable(dsp_phase_lock_test_suite
        tests/test_dsp_phase_lock.cc
        engine/PtpEngine.cc
        engine/HardwareLatencyProfiler.cc
        dsp/AcousticPhaseResampler.cc
    )
    target_include_directories(dsp_phase_lock_test_suite PRIVATE engine dsp .)
    target_link_libraries(dsp_phase_lock_test_suite PRIVATE m pthread)

    # Phase 4a (MP-HAL01/MP-HAL02): the VirtualHAL adversarial test double —
    # deterministic simulated DAC clock + jitter/underrun/overrun/rate-change
    # stress against the REAL hardened AudioSink ring.
    add_executable(virtual_hal_test_suite
        tests/test_virtual_hal.cpp
        src/virtual_hal.cpp
        src/audio_sink_ringbuffer.cpp
        # Phase 4a audit fix: the zero-alloc proofs were structurally
        # vacuous until this counting TU was linked in.
        tests/alloc_guard_override.cc
    )
    target_include_directories(virtual_hal_test_suite PRIVATE include src util .)
    target_link_libraries(virtual_hal_test_suite PRIVATE m pthread)

    # Phase 4a (MP-HAL04): the CoreAudio adapter's RT render core — the
    # platform-independent pull/clock-publication path the Apple
    # AURenderCallback runs, audited on every host lane.
    add_executable(coreaudio_render_core_test_suite
        tests/test_coreaudio_render_core.cpp
        src/audio_sink_ringbuffer.cpp
        # Phase 4a audit fix: real (non-vacuous) zero-alloc proofs.
        tests/alloc_guard_override.cc
    )
    target_include_directories(coreaudio_render_core_test_suite PRIVATE include src util .)
    target_link_libraries(coreaudio_render_core_test_suite PRIVATE m pthread)

    # Phase 4a (MP-HAL01): the directive workflow's dedicated SPSC lane —
    # the shared suite body with a standalone main, so
    # `cmake --build build --target test_audio_sink_ringbuffer` works.
    add_executable(test_audio_sink_ringbuffer
        tests/main_audio_sink_standalone.cpp
        tests/test_audio_sink_ringbuffer.cpp
        src/audio_sink_ringbuffer.cpp
        src/wear_audio_encoder.cpp
        # Phase 4a audit fix: real (non-vacuous) zero-alloc proofs.
        tests/alloc_guard_override.cc
    )
    target_include_directories(test_audio_sink_ringbuffer PRIVATE include src util .)
    target_link_libraries(test_audio_sink_ringbuffer PRIVATE m pthread)

    # Phase 4b (MP-HAL08 / MP-HAL09): the desktop HAL render cores —
    # WASAPI, PipeWire and the ALSA fallback — platform-independent
    # RT pull/clock paths audited on every host lane, driven callback-
    # for-callback against the Phase 4a VirtualHAL adversarial double
    # (deliverable 3: buffer pull contracts without Windows/Linux
    # audio hardware).
    add_executable(desktop_hal_render_core_test_suite
        tests/test_desktop_hal_render_cores.cpp
        src/audio_sink_ringbuffer.cpp
        src/virtual_hal.cpp
        # Phase 4a audit fix: real (non-vacuous) zero-alloc proofs.
        tests/alloc_guard_override.cc
    )
    target_include_directories(desktop_hal_render_core_test_suite PRIVATE include src util .)
    target_link_libraries(desktop_hal_render_core_test_suite PRIVATE m pthread)

    # Phase 4b (MP-HAL09): the Linux HAL driver TU — dlopen PipeWire +
    # ALSA fallback. White-box suite: unity-includes the driver, so it
    # needs no PipeWire/ALSA dev packages and runs against the ALSA
    # "null" PCM (the deviceless hardware double). Linux hosts only.
    if(CMAKE_SYSTEM_NAME STREQUAL "Linux")
        add_executable(linux_hal_driver_test_suite
            tests/test_linux_hal_driver.cpp
            src/audio_sink_ringbuffer.cpp
            tests/alloc_guard_override.cc
        )
        target_include_directories(linux_hal_driver_test_suite PRIVATE include src util .)
        target_link_libraries(linux_hal_driver_test_suite PRIVATE m pthread ${CMAKE_DL_LIBS})
        # Best-effort REAL-header cross-check lane: when the spa/pod
        # headers are reachable, the suite re-derives the canonical
        # EnumFormat pod with spa_pod_builder_add_object and byte-
        # compares it against the embedded frozen ABI. Gracefully
        # skipped where the dev packages are absent.
        find_path(STREAMIFY_SPA_INCLUDE_DIR spa/pod/builder.h
                  PATH_SUFFIXES spa-0.2)
        find_path(STREAMIFY_PW_INCLUDE_DIR pipewire/stream.h
                  PATH_SUFFIXES pipewire-0.3)
        if(STREAMIFY_SPA_INCLUDE_DIR AND STREAMIFY_PW_INCLUDE_DIR)
            message(STATUS "Phase 4b: real pipewire headers found — enabling the frozen-ABI cross-check lane")
            target_compile_definitions(linux_hal_driver_test_suite PRIVATE
                STREAMIFY_HAVE_REAL_PW_HEADERS)
            target_include_directories(linux_hal_driver_test_suite PRIVATE
                ${STREAMIFY_SPA_INCLUDE_DIR} ${STREAMIFY_PW_INCLUDE_DIR})
        else()
            message(STATUS "Phase 4b: no pipewire dev headers — frozen-ABI cross-check lane skipped (embedded reference still checked)")
        endif()
    endif()

    # Phase 5 (MP-CAP03): the Apple remote-command C ABI parity lane.
    # The header (native/include/apple_remote_command.h) is PURE C — no
    # Apple frameworks — so this suite compiles and pins the frozen
    # SF_APPLE_CMD_* codes on EVERY host; the Rust suite cross-checks
    # the same values from the other side, and the real .mm glue builds
    # only in the Apple lanes below.
    add_executable(capability_abi_parity_test_suite
        tests/test_capability_abi_parity.cc
        # The inert C stub keeps the ABI linkable on every host (the
        # real ObjC++ bridge only compiles on Apple lanes, below).
        os/apple_remote_command_stub.c
    )
    target_include_directories(capability_abi_parity_test_suite PRIVATE include .)
    target_link_libraries(capability_abi_parity_test_suite PRIVATE pthread)

    # Variant B: Hardware-tuned hot loops for all 4 test targets.
    # Prevents ASan/UBSan instrumentation from defeating vectorization and register
    # allocation in the 10M-frame SPSC, 1M-tick pacer, and SHA-256 stress loops.
    # -fno-lto drops LTO overhead on test binaries. No -march=native: it caused
    # cross-runner SIGILL and was removed at 06bbd60; host test lanes stay
    # portable, while the arm64-v8a NDK lane carries the deterministic
    # -march=armv8-a+simd+crypto baseline set at the top of this file.
    if (CMAKE_CXX_COMPILER_ID MATCHES "Clang" OR CMAKE_CXX_COMPILER_ID MATCHES "GNU")
        set(PHASE4B_TEST_TARGETS dsp_test_suite simd_physics_test_suite storage_telemetry_test_suite dsp_phase_lock_test_suite virtual_hal_test_suite coreaudio_render_core_test_suite test_audio_sink_ringbuffer desktop_hal_render_core_test_suite capability_abi_parity_test_suite)
        if(CMAKE_SYSTEM_NAME STREQUAL "Linux")
            list(APPEND PHASE4B_TEST_TARGETS linux_hal_driver_test_suite)
        endif()
        foreach(tname ${PHASE4B_TEST_TARGETS})
            target_compile_options(${tname} PRIVATE
                -fno-fast-math
                -O2
                -fno-lto
                -fno-omit-frame-pointer)
            target_link_options(${tname} PRIVATE
                -fno-fast-math
                -fno-lto)
        endforeach()
    endif()

    # Phase 3 (multiplatform): CTest registration — the quality gate is
    # `cmake --build native/build && ctest --test-dir native/build`.
    # The suites are self-contained mains (pass/fail counters, exit code
    # non-zero on any failure), so a plain COMMAND registration is exact.
    add_test(NAME dsp_test_suite COMMAND dsp_test_suite)
    add_test(NAME simd_physics_test_suite COMMAND simd_physics_test_suite)
    add_test(NAME storage_telemetry_test_suite COMMAND storage_telemetry_test_suite)
    add_test(NAME dsp_phase_lock_test_suite COMMAND dsp_phase_lock_test_suite)
    # Phase 4a lanes: VirtualHAL, the CoreAudio render core and the
    # directive's standalone SPSC target.
    add_test(NAME virtual_hal_test_suite COMMAND virtual_hal_test_suite)
    add_test(NAME coreaudio_render_core_test_suite COMMAND coreaudio_render_core_test_suite)
    add_test(NAME test_audio_sink_ringbuffer COMMAND test_audio_sink_ringbuffer)
    # Phase 4b lanes: the desktop HAL render cores (every host) and the
    # Linux dlopen driver (Linux hosts).
    add_test(NAME desktop_hal_render_core_test_suite COMMAND desktop_hal_render_core_test_suite)
    # Phase 5 lane: the capability C-ABI parity suite (every host).
    add_test(NAME capability_abi_parity_test_suite COMMAND capability_abi_parity_test_suite)
    if(CMAKE_SYSTEM_NAME STREQUAL "Linux")
        add_test(NAME linux_hal_driver_test_suite COMMAND linux_hal_driver_test_suite)
    endif()
endif()

# --- Phase 4a (MP-HAL04): Apple CoreAudio pull adapter (Apple hosts only) ----
# The AURenderCallback lifecycle wrapper (AUHAL on macOS / RemoteIO on iOS).
# Its entire RT-critical path is the host-testable CoreAudioRenderCore
# (see coreaudio_render_core_test_suite above); this library adds only the
# AudioUnit scaffolding and the C facade for the Swift shell (Phase 5).
if(APPLE)
    add_library(streamify_coreaudio_adapter STATIC
        audio/coreaudio_pull_adapter.cpp
    )
    target_include_directories(streamify_coreaudio_adapter PRIVATE include src .)
    target_link_libraries(streamify_coreaudio_adapter PRIVATE
        "-framework CoreAudio"
        "-framework AudioUnit"
        "-framework CoreFoundation"
    )
endif()

# --- Phase 4b (MP-HAL08): Windows WASAPI pull adapter (Windows hosts only) ---
# The event-driven IAudioClient3 / shared / exclusive render thread with
# Pro Audio MMCSS priority and IAudioClock ingestion. Its entire
# RT-critical path is the host-testable WasapiRenderCore (see
# desktop_hal_render_core_test_suite above); this library adds only the
# COM/MMDevice scaffolding and the C facade for the desktop shells.
if(WIN32)
    add_library(streamify_wasapi_adapter STATIC
        audio/wasapi_pull_adapter.cpp
    )
    target_include_directories(streamify_wasapi_adapter PRIVATE include src .)
    # avrt: AvSetMmThreadCharacteristicsW; ole32: COM runtime.
    target_link_libraries(streamify_wasapi_adapter PRIVATE avrt ole32)
endif()

# --- Phase 4b (MP-HAL09): Linux PipeWire driver + ALSA fallback (Linux only) -
# The pw_stream process-callback driver with the dlopen'd ALSA fallback.
# ZERO link dependencies: both backends are resolved at runtime through
# dlopen, so this target compiles on any Linux toolchain without
# libpipewire-dev / libasound-dev. Its RT-critical path is the
# host-testable PipeWireRenderCore / AlsaRenderCore pair (see
# desktop_hal_render_core_test_suite) and its frozen embedded ABI is
# audited by linux_hal_driver_test_suite.
if(CMAKE_SYSTEM_NAME STREQUAL "Linux")
    add_library(streamify_linuxhal_driver STATIC
        audio/pipewire_driver.cpp
    )
    target_include_directories(streamify_linuxhal_driver PRIVATE include src .)
    target_link_libraries(streamify_linuxhal_driver PRIVATE ${CMAKE_DL_LIBS})
endif()

# --- Phase 5 (MP-CAP03): Apple RemoteCommandCenter bridge (Apple only) -------
# The MPRemoteCommandCenter / MPNowPlayingInfoCenter glue behind the
# pure-C ABI of native/include/apple_remote_command.h. The Rust core
# resolves these symbols at RUNTIME (dlsym, see
# rust/src/platform/apple/remote_command.rs) — the same zero-link-dep
# discipline as the Phase 4b PipeWire dlopen driver — so nothing here
# is a build-time dependency of the Rust crate: this library exists so
# macOS hosts can link it into the app process (and the XCFramework
# script compiles the same TU for the iOS slices).
# The header itself stays host-compilable and is audited on EVERY lane
# by capability_abi_parity_test_suite above.
if(APPLE)
    enable_language(OBJCXX)
    add_library(streamify_apple_remote_command STATIC
        os/apple_remote_command.mm
    )
    target_include_directories(streamify_apple_remote_command PRIVATE include .)
    target_link_libraries(streamify_apple_remote_command PRIVATE
        "-framework MediaPlayer"
        "-framework Foundation"
    )
endif()

# --- LIBFUZZER HARNESS (host clang; requires clang with compiler-rt) ---
option(STREAMIFY_BUILD_FUZZERS "Build LibFuzzer harnesses (host builds only)" OFF)
if (STREAMIFY_BUILD_FUZZERS)
    if (NOT CMAKE_CXX_COMPILER_ID MATCHES "Clang")
        message(FATAL_ERROR "STREAMIFY_BUILD_FUZZERS requires clang (libFuzzer).")
    endif()
    add_executable(dsp_fuzzer_harness
        fuzz/dsp_fuzzer_harness.cc
        # Phase-5 palette/frame-pacer fuzz cases, composed into the shared
        # entry (no LLVMFuzzerTestOneInput of its own in this mode).
        tests/fuzz_palette.cpp
        src/palette_extractor_simd.cpp
        src/frame_pacer_monitor.cpp
        src/streamify_frame_palette.cpp
        dsp/SoftKneeLimiter.cc
        dsp/LufsNormalizer.cc
        dsp/ChannelOps.cc
        dsp/MasterChain.cc
        dsp/AcousticPhaseResampler.cc
        math/CandidateHasher.cc
        math/HarmonicTransitionEngine.cc
        math/CircadianCurves.cc
        video/CanvasLoopMath.cc
        video/CanvasLoopRenderer.cc
        agsl/AmbientGlowShader.cc
        audio/OpusPacketParser.cc
        audio/AdtsFrameParser.cc
        audio/AudioFrameRemuxer.cc
        palette/BitmapPalette.cc
    )
    target_include_directories(dsp_fuzzer_harness PRIVATE dsp mix math util video agsl audio palette .)
    # Sanitizers replace global -O3/-flto/-ffast-math for this target: fast-math
    # elides the NaN guards the harness exists to exercise.
    target_compile_options(dsp_fuzzer_harness PRIVATE
        -fsanitize=fuzzer,address,undefined -fno-omit-frame-pointer -fno-fast-math -O1 -g
    )
    # fuzz_palette.cpp joins WITHOUT its own libFuzzer entry point.
    set_source_files_properties(tests/fuzz_palette.cpp PROPERTIES
        COMPILE_DEFINITIONS "STREAMIFY_FUZZ_COMPOSED")
    target_link_options(dsp_fuzzer_harness PRIVATE
        -fsanitize=fuzzer,address,undefined -fno-fast-math
    )

    # Phase-4: malformed network audio streams & packet corruption (the
    # same TU carries a -DSTREAMIFY_FUZZ_STANDALONE mutator driver for
    # sanitizer-only environments without compiler-rt fuzzer runtime).
    add_executable(audio_sink_fuzz_harness
        tests/fuzz_audio_sink.cpp
        src/audio_sink_ringbuffer.cpp
        src/wear_audio_encoder.cpp
    )
    target_include_directories(audio_sink_fuzz_harness PRIVATE include src util .)
    target_compile_options(audio_sink_fuzz_harness PRIVATE
        -fsanitize=fuzzer,address,undefined -fno-omit-frame-pointer -fno-fast-math -O1 -g
    )
    target_link_options(audio_sink_fuzz_harness PRIVATE
        -fsanitize=fuzzer,address,undefined -fno-fast-math
    )

    # Phase-5: arbitrary image bitstreams (corrupt dimensions/strides/buffers,
    # extreme RGB) through the SIMD palette extractor + WCAG contract, and
    # corrupted frame histograms through the lock-free pacer (-DSTREAMIFY_
    # FUZZ_STANDALONE mutator driver for sanitizer-only environments).
    add_executable(frame_palette_fuzz_harness
        tests/fuzz_palette.cpp
        src/palette_extractor_simd.cpp
        src/frame_pacer_monitor.cpp
        src/streamify_frame_palette.cpp
    )
    target_include_directories(frame_palette_fuzz_harness PRIVATE include src util .)
    target_compile_options(frame_palette_fuzz_harness PRIVATE
        -fsanitize=fuzzer,address,undefined -fno-omit-frame-pointer -fno-fast-math -O1 -g
    )
    target_link_options(frame_palette_fuzz_harness PRIVATE
        -fsanitize=fuzzer,address,undefined -fno-fast-math
    )
    endif()
