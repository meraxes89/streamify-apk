cmake_minimum_required(VERSION 3.22.1)
project(streamify_native_core LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Optimization and Vectorization Flags
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -ffast-math -flto -fvisibility=hidden -Wall -Wextra")
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O3 -ffast-math -flto -fvisibility=hidden")

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

add_library(streamify_native_core SHARED
    # Phase-5: SIMD palette clustering + WCAG contrast engine.
    src/palette_extractor_simd.cpp
    # Phase-5: lock-free 120Hz frame pacing & jank monitor.
    src/frame_pacer_monitor.cpp
    # Phase-5: frame pacer wire serialization + process-default instance.
    src/streamify_frame_palette.cpp
    # Phase-5: palette extraction & frame pacing JNI bridges.
    src/jni_bridge_frame_palette.cpp
    # Phase-4: lock-free SPSC audio sink + SIMD sample converter.
    src/audio_sink_ringbuffer.cpp
    # Phase-4: companion WearOS frame packetizer & rate-limiting compressor.
    src/wear_audio_encoder.cpp
    # Phase-4: audio sink JNI bridges (DirectByteBuffer transfer + telemetry).
    src/jni_bridge_audio_sinks.cpp
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
    jni/jni_bridge_native_dsp_engine.cc
    jni/jni_bridge_harmonic_engine.cc
    jni/jni_bridge_media_palette.cc
    jni/jni_bridge_audio_remuxer.cc
    jni/jni_bridge_agsl_glow.cc
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

# --- STANDALONE TEST TARGETS (Host C++20 with ASan / UBSan) ---
option(STREAMIFY_BUILD_TESTS "Build native test suites" OFF)
if (STREAMIFY_BUILD_TESTS)
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

    # The suites assert on NaN/Inf/overflow guards and bounded outputs; the
    # global -ffast-math would fold those guards away (the limiter test
    # asserts |sample| <= 1.1). Same rationale as the fuzzer harness below.
    # The flag must ALSO reach the LINK step: with -flto, link-time
    # optimization re-optimizes the merged IR using the link-line flags, so
    # a compile-only -fno-fast-math still leaks fast-math semantics back
    # into the sanitized suites (observed on clang-18 LTO: folded isfinite
    # guards and reassociated token-bucket arithmetic).
    if (CMAKE_CXX_COMPILER_ID MATCHES "Clang" OR CMAKE_CXX_COMPILER_ID MATCHES "GNU")
        foreach(tname dsp_test_suite simd_physics_test_suite storage_telemetry_test_suite dsp_phase_lock_test_suite)
            target_compile_options(${tname} PRIVATE -fno-fast-math)
            target_link_options(${tname} PRIVATE -fno-fast-math)
        endforeach()
    endif()
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
