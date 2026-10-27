// ============================================================================
//  main_audio_sink_standalone.cpp — standalone entry point for the SPSC
//  ring-buffer suite (native/tests/main_audio_sink_standalone.cpp)
// ============================================================================
//
//  The suite body (run_audio_sink_tests) is shared with dsp_test_suite
//  (whose main lives in test_dsp.cc). This TU gives the directive
//  workflow's dedicated lane its own binary:
//
//      cmake -B build -S . -DENABLE_TESTS=ON
//      cmake --build build --target test_audio_sink_ringbuffer
//      ctest --test-dir build --output-on-failure
// ============================================================================

extern int run_audio_sink_tests();

int main() { return run_audio_sink_tests(); }
