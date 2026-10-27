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
# the Phase 3 host sanitizer lane (ENABLE_ASAN) builds ONLY the test suites,
# so the .so target is skipped entirely there (it cannot link on a host).
if (NOT ENABLE_ASAN)
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

    # Variant B: Hardware-tuned hot loops for all 4 test targets.
    # Prevents ASan/UBSan instrumentation from defeating vectorization and register
    # allocation in the 10M-frame SPSC, 1M-tick pacer, and SHA-256 stress loops.
    # -fno-lto drops LTO overhead on test binaries. No -march=native: it caused
    # cross-runner SIGILL and was removed at 06bbd60; host test lanes stay
    # portable, while the arm64-v8a NDK lane carries the deterministic
    # -march=armv8-a+simd+crypto baseline set at the top of this file.
    if (CMAKE_CXX_COMPILER_ID MATCHES "Clang" OR CMAKE_CXX_COMPILER_ID MATCHES "GNU")
        foreach(tname dsp_test_suite simd_physics_test_suite storage_telemetry_test_suite dsp_phase_lock_test_suite)
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
