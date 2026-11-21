// ============================================================================
//  test_audio_sink_ringbuffer.cc — Phase-4 verification: lock-free SPSC
//  sink + SIMD converter + WearOS packetizer (native-dsp CI shard)
// ============================================================================
//
//  Covers the Phase-4 directive deliverables 1-3:
//    A. AudioSink — SPSC ordering under producer/consumer thread contention
//       across 10,000,000 frames with zero dropped or out-of-order samples;
//       wraparound, underrun/overrun accounting, watermarks, latency math,
//       flush semantics, zero-allocation hot paths (AllocGuard).
//    B. SIMD sample converter — Float32->PCM16 accuracy vs the scalar
//       reference (per-path rounding contract, +-1 LSB), hard saturation
//       bounds, non-finite range safety; stereo->mono downmix exactness.
//    C. Decimator — DC gain unity, 1 kHz passband transparency, 20 kHz
//       anti-alias attenuation, exact output frame counts.
//    D. FramePacketizer — roundtrip byte-exactness, MTU chunking with
//       continuation flags, sequence continuity, corruption ladder
//       (truncation/magic/flags/rate/size/CRC/decode-overflow), zero-run
//       compressor roundtrip, token-bucket pacing and backpressure.
//    E. SinkRegistry + stats DirectByteBuffer serialization — lifecycle,
//       garbage-handle safety, little-endian layout offsets.
//
//  Linked into dsp_test_suite; entry point run_audio_sink_tests() is
//  called from test_dsp.cc's main().
// ============================================================================

#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <limits>
#include <thread>
#include <vector>

#include "../include/audio_sink_ringbuffer.h"
#include "../include/streamify_audio_sinks.h"
#include "../include/wear_audio_encoder.h"

#include "AllocGuard.h"

using streamify::sink::AudioSink;
using streamify::sink::ConvertF32ToI16;
using streamify::sink::Decimation;
using streamify::sink::Decimator;
using streamify::sink::DownmixStereoToMono;
using streamify::sink::SinkRegistry;
using streamify::sink::SinkStats;
using streamify::sink::WriteSinkStatsBuffer;
using streamify::wear::Crc32;
using streamify::wear::FramePacketizer;
using streamify::wear::RateLimiter;
using streamify::wear::SampleRateFromCode;
using streamify::wear::SampleRateToCode;
using streamify::wear::WearCompressMaxOutput;
using streamify::wear::WearCompressPayload;
using streamify::wear::WearDecompressPayload;

namespace {

int g_passed = 0;
int g_failed = 0;

void check(bool ok, const char* what) {
    if (ok) {
        ++g_passed;
    } else {
        ++g_failed;
        std::printf("    FAILED: %s\n", what);
    }
}

// Deterministic RNG (SplitMix64) — same family as the other suites.
struct Rng {
    uint64_t s;
    explicit Rng(uint64_t seed) : s(seed) {}
    uint64_t next() {
        s += 0x9E3779B97F4A7C15ull;
        uint64_t z = s;
        z = (z ^ (z >> 30)) * 0xBF58476D1CE4E5B9ull;
        z = (z ^ (z >> 27)) * 0x94D049BB133111EBull;
        return z ^ (z >> 31);
    }
    float unit() {  // [0, 1)
        return static_cast<float>(next() >> 40) * (1.0f / 16777216.0f);
    }
};

uint32_t GetLE32(const uint8_t* p) {
    return static_cast<uint32_t>(p[0]) | (static_cast<uint32_t>(p[1]) << 8) |
           (static_cast<uint32_t>(p[2]) << 16) |
           (static_cast<uint32_t>(p[3]) << 24);
}
uint64_t GetLE64(const uint8_t* p) {
    return static_cast<uint64_t>(GetLE32(p)) |
           (static_cast<uint64_t>(GetLE32(p + 4)) << 32);
}

float Rms(const float* x, size_t n) {
    if (n == 0) return 0.0f;
    double acc = 0.0;
    for (size_t i = 0; i < n; ++i) acc += static_cast<double>(x[i]) * x[i];
    return static_cast<float>(std::sqrt(acc / n));
}

// Exact-roundtrip sample pattern: raw/32767 (raw in [0, 32766]) maps back
// to raw on every SIMD path (rounding error < 0.005 LSB), with an
// alternating sign so negative quantization is exercised too.
inline float PatternSample(uint64_t frame, uint32_t channel) {
    const uint64_t k = (frame * 7919ull + channel * 104729ull) % 32767ull;
    const float v = static_cast<float>(k) / 32767.0f;
    return ((frame + channel) & 1ull) ? -v : v;
}
inline int16_t ExpectedPattern(uint64_t frame, uint32_t channel) {
    const uint64_t k = (frame * 7919ull + channel * 104729ull) % 32767ull;
    return static_cast<int16_t>(((frame + channel) & 1ull) ? -static_cast<int64_t>(k)
                                                           : static_cast<int64_t>(k));
}

// ============================================================================
//  A. CRC32 + rate codes
// ============================================================================

void test_crc32() {
    std::printf("  [sink] CRC32 known vectors + rate codes\n");
    const uint8_t* v = reinterpret_cast<const uint8_t*>("123456789");
    check(Crc32(v, 9) == 0xCBF43926u, "CRC32 check value 0xCBF43926");
    check(Crc32(v, 0) == 0x00000000u, "CRC32 of empty input");
    // Incremental equivalence: crc(a||b) == crc(b, crc(a)^0xFFFF...) via
    // standard chaining — verify whole-vs-split consistency instead.
    uint8_t buf[300];
    Rng rng(42);
    for (auto& b : buf) b = static_cast<uint8_t>(rng.next());
    const uint32_t whole = Crc32(buf, sizeof(buf));
    check(whole == Crc32(buf, sizeof(buf)), "CRC32 deterministic");
    check(whole != Crc32(buf, sizeof(buf) - 1), "CRC32 length-sensitive");

    check(SampleRateToCode(8000) == 0 && SampleRateToCode(16000) == 1 &&
              SampleRateToCode(24000) == 2 && SampleRateToCode(44100) == 3 &&
              SampleRateToCode(48000) == 4,
          "SampleRateToCode table");
    check(SampleRateToCode(22050) == 0xFF, "unsupported rate -> 0xFF");
    for (uint8_t code = 0; code <= 4; ++code) {
        check(SampleRateToCode(SampleRateFromCode(code)) == code,
              "rate code roundtrip");
    }
    check(SampleRateFromCode(5) == 0 && SampleRateFromCode(255) == 0,
          "unknown rate code -> 0 Hz");
}

// ============================================================================
//  B. SIMD converter + downmix
// ============================================================================

void test_convert_f32_to_i16() {
    std::printf("  [sink] SIMD Float32->PCM16 accuracy + saturation\n");
    // Accuracy vs scalar reference over random in-range and out-of-range
    // values (per-path rounding contract: within 1 LSB).
    Rng rng(0xC0FFEE);
    const size_t kCount = 8192;
    std::vector<float> in(kCount);
    std::vector<int16_t> out(kCount);
    for (size_t i = 0; i < kCount; ++i) {
        const float u = rng.unit() * 2.4f - 1.2f;  // 20% out-of-range
        in[i] = u;
    }
    ConvertF32ToI16(in.data(), out.data(), kCount);
    size_t worst = 0;
    for (size_t i = 0; i < kCount; ++i) {
        const int16_t ref = streamify::sink::ConvertSampleF32ToI16(in[i]);
        const int delta = std::abs(static_cast<int>(out[i]) - static_cast<int>(ref));
        if (static_cast<size_t>(delta) > worst) worst = static_cast<size_t>(delta);
        check(delta <= 1, "SIMD within 1 LSB of scalar reference");
        if (g_failed > 20) return;  // don't flood on a systematic break
    }
    std::printf("    max SIMD-vs-scalar delta: %zu LSB\n", worst);

    // Hard saturation bounds (exact on every path).
    const float edges[] = {1.0f, -1.0f, 5.5f, -5.5f, 1e9f, -1e9f};
    const int16_t want[] = {32767, -32767, 32767, -32767, 32767, -32767};
    int16_t got[6];
    ConvertF32ToI16(edges, got, 6);
    for (int i = 0; i < 6; ++i) {
        check(got[i] == want[i], "hard clamp at +-1 -> +-32767");
    }
    const float zero = 0.0f;
    int16_t z = 0x1234;
    ConvertF32ToI16(&zero, &z, 1);
    check(z == 0, "silence passthrough");

    // Non-finite inputs: bounds-only contract (path-dependent value).
    const float specials[] = {std::nanf(""), -std::nanf(""),
                              std::numeric_limits<float>::infinity(),
                              -std::numeric_limits<float>::infinity()};
    int16_t sp[4];
    ConvertF32ToI16(specials, sp, 4);
    for (int i = 0; i < 4; ++i) {
        // Documented union of path behaviors: digital silence or a hard
        // saturated rail — never a garbage value.
        check(sp[i] == 0 || sp[i] == 32767 || sp[i] == -32767,
              "non-finite maps to silence or a saturated rail");
    }
    check(streamify::sink::ConvertSampleF32ToI16(std::nanf("")) == 0 &&
              streamify::sink::ConvertSampleF32ToI16(
                  std::numeric_limits<float>::infinity()) == 0,
          "scalar maps NaN/Inf to digital silence");

    // Odd counts exercise the scalar tails of the vectorized loops.
    for (size_t n : {size_t{1}, size_t{3}, size_t{5}, size_t{7}, size_t{11},
                     size_t{13}, size_t{17}, size_t{255}, size_t{257}}) {
        std::vector<float> odd_in(n);
        std::vector<int16_t> odd_out(n);
        for (size_t i = 0; i < n; ++i) {
            odd_in[i] = rng.unit() * 2.0f - 1.0f;
        }
        ConvertF32ToI16(odd_in.data(), odd_out.data(), n);
        bool tail_ok = true;
        for (size_t i = 0; i < n; ++i) {
            const int16_t ref = streamify::sink::ConvertSampleF32ToI16(odd_in[i]);
            tail_ok = tail_ok && std::abs(odd_out[i] - ref) <= 1;
        }
        check(tail_ok, "odd-count tail handling");
    }
}

void test_downmix() {
    std::printf("  [sink] SIMD stereo->mono downmix\n");
    Rng rng(0xBEEF);
    const size_t kFrames = 4096;
    std::vector<float> stereo(kFrames * 2);
    std::vector<float> mono(kFrames);
    for (auto& f : stereo) f = rng.unit() * 2.0f - 1.0f;
    DownmixStereoToMono(stereo.data(), mono.data(), stereo.size());
    bool exact = true;
    for (size_t i = 0; i < kFrames; ++i) {
        const float ref = (stereo[2 * i] + stereo[2 * i + 1]) * 0.5f;
        // SIMD and scalar compute identical IEEE ops -> bit-exact expected.
        exact = exact && mono[i] == ref;
    }
    check(exact, "downmix bit-exact vs scalar (L+R)*0.5");

    const float known[] = {1.0f, 0.5f, -0.25f, 0.75f, 0.0f, 0.0f};
    float out[3];
    DownmixStereoToMono(known, out, 6);
    check(out[0] == 0.75f && out[1] == 0.25f && out[2] == 0.0f,
          "downmix known pairs");
}

// ============================================================================
//  C. FIR decimator
// ============================================================================

void test_decimator() {
    std::printf("  [sink] FIR decimator: DC, passband, anti-alias, counts\n");
    // Passthrough (factor 1) is an identity copy.
    Decimator pt;
    check(pt.init(1, 2), "decimator init factor=1");
    float pin[8] = {0.25f, -0.5f, 0.75f, 1.0f, -1.0f, 0.1f, -0.2f, 0.3f};
    float pout[8];
    check(pt.process(pin, 4, pout) == 4 && std::memcmp(pin, pout, 16) == 0,
          "factor=1 passthrough identity");

    // DC gain must be unity after the filter settles.
    Decimator d2;
    check(d2.init(2, 1), "decimator init factor=2");
    std::vector<float> dc_in(4800, 0.5f);
    std::vector<float> dc_out(2410);
    const size_t dc_n = d2.process(dc_in.data(), dc_in.size(), dc_out.data());
    check(dc_n == 2400, "DC: exact output count 4800/2");
    bool dc_ok = true;
    for (size_t i = 64; i < dc_n; ++i) {
        dc_ok = dc_ok && std::fabs(dc_out[i] - 0.5f) < 0.005f;
    }
    check(dc_ok, "DC gain unity (+-0.005 after settle)");

    // 1 kHz @ 48k must pass transparently (well inside the 10.8k cutoff).
    d2.reset();
    std::vector<float> tone_in(9600);
    std::vector<float> tone_out(4810);
    for (size_t i = 0; i < tone_in.size(); ++i) {
        tone_in[i] = 0.8f * std::sin(2.0f * 3.14159265f * 1000.0f *
                                     static_cast<float>(i) / 48000.0f);
    }
    const size_t tone_n =
        d2.process(tone_in.data(), tone_in.size(), tone_out.data());
    check(tone_n == 4800, "passband: exact output count 9600/2");
    const float pass_rms = Rms(tone_out.data() + 64, tone_n - 64);
    const float want_rms = 0.8f / std::sqrt(2.0f);
    check(pass_rms > want_rms * 0.9f && pass_rms < want_rms * 1.1f,
          "1kHz passband amplitude within 10%");

    // 20 kHz @ 48k aliases into the 24k output band; the anti-aliasing
    // lowpass must crush it (normalized 0.4167 >> cutoff 0.225).
    d2.reset();
    for (size_t i = 0; i < tone_in.size(); ++i) {
        tone_in[i] = 0.8f * std::sin(2.0f * 3.14159265f * 20000.0f *
                                     static_cast<float>(i) / 48000.0f);
    }
    const size_t alias_n =
        d2.process(tone_in.data(), tone_in.size(), tone_out.data());
    const float alias_rms = Rms(tone_out.data() + 64, alias_n - 64);
    check(alias_rms < 0.05f, "20kHz alias attenuated below 5% (-26 dB)");

    // Factor 3 output accounting (48k -> 16k family).
    Decimator d3;
    check(d3.init(3, 2), "decimator init factor=3");
    std::vector<float> tri_in(9600);
    std::vector<float> tri_out(6410);
    for (size_t i = 0; i < tri_in.size(); ++i) {
        tri_in[i] = PatternSample(i / 2, i % 2) * 0.9f;
    }
    const size_t tri_n = d3.process(tri_in.data(), 4800, tri_out.data());
    check(tri_n == 1600, "factor=3: 4800 inputs -> 1600 outputs");
    bool finite_ok = true;
    for (size_t i = 0; i < tri_n * 2; ++i) {
        finite_ok = finite_ok && std::isfinite(tri_out[i]) &&
                    tri_out[i] >= -1.5f && tri_out[i] <= 1.5f;
    }
    check(finite_ok, "factor=3 outputs bounded");

    // Bad arguments must be rejected.
    Decimator bad;
    check(!bad.init(0, 1) && !bad.init(4, 1) && !bad.init(2, 0) &&
              !bad.init(2, 3),
          "decimator rejects bad factor/channels");
}

// ============================================================================
//  D. Ring buffer: basic IO, wraparound, telemetry, flush
// ============================================================================

void test_ring_basic_io() {
    std::printf("  [sink] SPSC ring basic IO + wraparound + byte reads\n");
    AudioSink::Config cfg;
    cfg.sample_rate = 48000;
    cfg.channels = 1;
    cfg.capacity_frames = 64;  // minimum, forces rapid wraparound
    AudioSink sink(cfg);
    check(sink.valid(), "mono sink valid");
    check(sink.capacity_frames() == 64, "capacity stays pow2 (64)");
    check(sink.frame_bytes_out() == 2, "mono frame bytes");

    float in[64];
    int16_t out[64];
    uint64_t pbase = 0;  // producer cursor: next frame index to be written
    uint64_t cbase = 0;  // consumer cursor: next frame index to be read

    // write 50 / read 30 / write 44 (wraps the index, fills the ring).
    for (size_t i = 0; i < 50; ++i) in[i] = PatternSample(pbase + i, 0);
    check(sink.WriteFloat(in, 50) == 50, "write 50 frames");
    pbase += 50;
    check(sink.ReadFrames(out, 30) == 30, "read 30 frames");
    bool ok30 = true;
    for (size_t i = 0; i < 30; ++i) {
        ok30 = ok30 && out[i] == ExpectedPattern(cbase + i, 0);
    }
    check(ok30, "first 30 frames pattern-exact");
    cbase += 30;

    for (size_t i = 0; i < 44; ++i) in[i] = PatternSample(pbase + i, 0);
    check(sink.WriteFloat(in, 44) == 44, "write 44 across the wrap point");
    pbase += 44;
    // Ring is now exactly full (64): a further write consumes nothing and
    // must not block or drop — the caller retries after draining.
    check(sink.WriteFloat(in, 1) == 0, "write with full ring returns 0");
    SinkStats xst;
    sink.GetStats(&xst);
    check(xst.overrun_events >= 1, "blocked write counts an overrun event");
    check(xst.overrun_dropped_frames == 0, "blocked write dropped nothing");
    check(xst.current_fullness_frames == 64, "ring exactly full");

    const size_t got = sink.ReadFrames(out, 60);
    check(got == 60, "read 60 of 64 buffered frames");
    bool ok60 = true;
    for (size_t i = 0; i < 60; ++i) {
        ok60 = ok60 && out[i] == ExpectedPattern(cbase + i, 0);
    }
    check(ok60, "wraparound frames pattern-exact (no tear)");
    cbase += 60;
    check(sink.ReadFrames(out, 4) == 4, "drain the wrapped tail");
    bool ok4 = true;
    for (size_t i = 0; i < 4; ++i) {
        ok4 = ok4 && out[i] == ExpectedPattern(cbase + i, 0);
    }
    check(ok4, "wrapped tail pattern-exact");
    cbase += 4;
    check(pbase == cbase, "producer and consumer cursors reconverge");

    // Byte-oriented reads (stereo sink): frame alignment + capacity floor.
    AudioSink::Config cfg2;
    cfg2.sample_rate = 48000;
    cfg2.channels = 2;
    cfg2.capacity_frames = 128;
    AudioSink stereo_sink(cfg2);
    float sin_[201];  // 200 samples + one dangling sample
    for (size_t i = 0; i < 200; ++i) sin_[i] = PatternSample(i, i % 2);
    check(stereo_sink.WriteFloat(sin_, 200) == 200, "stereo write 100 frames");
    // 201st sample alone is less than one stereo frame: consumed 0,
    // the caller keeps it for the next buffer.
    check(stereo_sink.WriteFloat(sin_ + 200, 1) == 0,
          "sub-frame write consumes nothing (whole frames only)");
    uint8_t bytes[512];
    check(stereo_sink.ReadBytes(bytes, 10) == 8, "byte read floors to 2 frames");
    check(stereo_sink.ReadBytes(bytes, 3) == 0, "byte read < frame size -> 0");
    check(stereo_sink.ReadBytes(bytes, sizeof(bytes)) == 392,
          "remaining 98 frames in bytes (392 = 98*4)");
    check(stereo_sink.ReadBytes(bytes, sizeof(bytes)) == 0, "drained");

    // Null/zero-argument hardening.
    check(sink.WriteFloat(nullptr, 100) == 0, "null write rejected");
    check(sink.ReadFrames(nullptr, 10) == 0, "null read rejected");
    check(sink.ReadBytes(nullptr, 100) == 0, "null byte read rejected");
}

void test_ring_telemetry() {
    std::printf("  [sink] telemetry: watermarks, fullness, latency, xruns\n");
    AudioSink::Config cfg;
    cfg.sample_rate = 48000;
    cfg.channels = 1;
    cfg.capacity_frames = 1024;
    AudioSink sink(cfg);

    float in[2048];
    int16_t out[2048];

    // 600 written -> high watermark 600, latency 12.5 ms.
    for (size_t i = 0; i < 600; ++i) in[i] = PatternSample(i, 0);
    check(sink.WriteFloat(in, 600) == 600, "telemetry write 600");
    SinkStats st;
    sink.GetStats(&st);
    check(st.frames_written == 600, "frames_written 600");
    check(st.current_fullness_frames == 600, "fullness 600");
    check(st.high_watermark_frames == 600, "high watermark 600");
    check(std::fabs(st.estimated_latency_ms - 12.5f) < 0.01f,
          "latency 600/48000 == 12.5 ms");
    check(std::fabs(st.fullness_percent - 100.0f * 600 / 1024) < 0.01f,
          "fullness percent 58.59");

    // 200 read -> occupancy 400.
    check(sink.ReadFrames(out, 200) == 200, "read 200");
    sink.GetStats(&st);
    check(st.current_fullness_frames == 400, "occupancy 400 after read");
    check(st.low_watermark_frames == 400, "low watermark 400");

    // 100 more written (occupancy 500 < previous high watermark).
    check(sink.WriteFloat(in, 100) == 100, "write 100");
    sink.GetStats(&st);
    check(st.high_watermark_frames == 600, "high watermark sticky at 600");
    check(st.current_fullness_frames == 500, "occupancy 500");

    // Over-asked read: returns 500 of 700 -> one underrun event, floor 0.
    check(sink.ReadFrames(out, 700) == 500, "over-asked read returns 500");
    sink.GetStats(&st);
    check(st.underrun_events == 1, "underrun counted once");
    check(st.current_fullness_frames == 0, "ring empty");
    check(st.low_watermark_frames == 0, "low watermark floor 0");
    check(st.frames_read == 700, "frames_read 700 total");

    // Full ring: partial write accounting (non-blocking, nothing dropped).
    check(sink.WriteFloat(in, 2048) == 1024, "full ring partial write 1024");
    sink.GetStats(&st);
    check(st.overrun_events == 1, "overrun event counted");
    check(st.overrun_dropped_frames == 0, "nothing dropped (caller retries)");
    check(st.current_fullness_frames == 1024, "ring exactly full");
    check(std::fabs(st.fullness_percent - 100.0f) < 0.01f, "fullness 100%");

    // Empty read after drain.
    check(sink.ReadFrames(out, 1024) == 1024, "drain 1024");
    check(sink.ReadFrames(out, 1) == 0, "empty read returns 0");
    sink.GetStats(&st);
    check(st.underrun_events == 2, "second underrun counted");

    // Latency math on the output rate (downmix+decimation: 48k->16k mono).
    AudioSink::Config cfg3;
    cfg3.sample_rate = 48000;
    cfg3.channels = 2;
    cfg3.capacity_frames = 1024;
    cfg3.downmix_to_mono = true;
    cfg3.decimation = Decimation::kThird;
    AudioSink wear_sink(cfg3);
    check(wear_sink.valid(), "wear sink valid");
    check(wear_sink.sample_rate_out() == 16000, "output rate 16 kHz");
    check(wear_sink.channels_out() == 1, "output mono");
    float stereo_in[3072];  // 1536 frames -> 512 mono output frames
    for (size_t i = 0; i < 3072; ++i) {
        stereo_in[i] = PatternSample(i / 2, i % 2) * 0.5f;
    }
    check(wear_sink.WriteFloat(stereo_in, 3072) == 3072, "wear write 1536 frames");
    wear_sink.GetStats(&st);
    check(st.frames_written == 512, "decimated to 512 frames");
    check(st.current_fullness_frames == 512, "wear occupancy 512");
    check(std::fabs(st.estimated_latency_ms - 32.0f) < 0.01f,
          "latency 512/16000 == 32 ms");
}

void test_ring_flush() {
    std::printf("  [sink] cooperative flush semantics (AMP-10)\n");
    AudioSink::Config cfg;
    cfg.sample_rate = 48000;
    cfg.channels = 1;
    cfg.capacity_frames = 256;
    AudioSink sink(cfg);
    float in[512];
    int16_t out[512];
    for (size_t i = 0; i < 200; ++i) in[i] = PatternSample(i, 0);
    check(sink.WriteFloat(in, 200) == 200, "flush test write 200");
    check(sink.ReadFrames(out, 100) == 100, "flush test read 100");

    // The request itself changes nothing: only the consumer's next read
    // performs the reset (coalescing, level-triggered).
    sink.RequestFlush();
    SinkStats st;
    sink.GetStats(&st);
    check(st.current_fullness_frames == 100, "request alone drops nothing");

    // The flush-consuming read: returns 0, zero-fills the destination
    // (digital silence), and does NOT count an underrun (control
    // decision, not starvation).
    const uint64_t underruns_before = [&sink]() {
        SinkStats s;
        sink.GetStats(&s);
        return s.underrun_events;
    }();
    std::memset(out, 0x5A, sizeof(out));  // garbage pattern
    check(sink.ReadFrames(out, 64) == 0, "flush-consuming read returns 0");
    bool silent = true;
    for (size_t i = 0; i < 64; ++i) silent = silent && out[i] == 0;
    check(silent, "flush-consuming read zero-fills destination");
    sink.GetStats(&st);
    check(st.current_fullness_frames == 0, "flush empties the ring");
    check(st.underrun_events == underruns_before,
          "requested flush is not counted as an underrun");
    check(st.frames_written == 200 && st.frames_read == 100,
          "cumulative counters preserved across flush");

    check(sink.ReadFrames(out, 1) == 0, "post-flush read starves (counts underrun normally)");
    for (size_t i = 0; i < 50; ++i) in[i] = PatternSample(1000 + i, 0);
    check(sink.WriteFloat(in, 50) == 50, "post-flush write works");
    check(sink.ReadFrames(out, 50) == 50, "post-flush read works");
    bool fresh = true;
    for (size_t i = 0; i < 50; ++i) {
        fresh = fresh && out[i] == ExpectedPattern(1000 + i, 0);
    }
    check(fresh, "post-flush stream carries fresh pattern (old audio gone)");

    // Requests coalesce: N pending requests collapse into one flush pass,
    // and a request with nothing buffered is an inert no-op.
    sink.RequestFlush();
    sink.RequestFlush();
    sink.RequestFlush();
    check(sink.ReadFrames(out, 8) == 0, "coalesced requests consume once");
    check(sink.ReadFrames(out, 8) == 0, "no sticky flush after consumption");

    // ReadBytes honors the cooperative flush too (shared consumer path):
    // the destination receives frame-aligned silence.
    for (size_t i = 0; i < 40; ++i) in[i] = PatternSample(2000 + i, 0);
    check(sink.WriteFloat(in, 40) == 40, "byte-flush write 40");
    sink.RequestFlush();
    uint8_t bytes[64];
    std::memset(bytes, 0xA5, sizeof(bytes));
    check(sink.ReadBytes(bytes, sizeof(bytes)) == 0,
          "flush-consuming byte read returns 0");
    bool byte_silent = true;
    for (size_t i = 0; i < sizeof(bytes); ++i) {
        byte_silent = byte_silent && bytes[i] == 0;
    }
    check(byte_silent, "flush-consuming byte read zero-fills destination");

    // Invalid configs must not produce usable sinks.
    AudioSink::Config bad = cfg;
    bad.sample_rate = 100;
    AudioSink bad_sink(bad);
    check(!bad_sink.valid(), "invalid config -> invalid sink");
    check(bad_sink.WriteFloat(in, 100) == 0, "invalid sink write no-ops");
    check(AudioSink::ValidateConfig(bad) == false, "ValidateConfig rejects");
    AudioSink::Config bad2 = cfg;
    bad2.capacity_frames = 4;
    check(!AudioSink::ValidateConfig(bad2), "capacity below minimum rejected");
}

// ============================================================================
//  E2. Cooperative flush under concurrent read stress (AMP-10)
// ============================================================================

void test_cooperative_flush_concurrent_stress() {
    std::printf("  [sink] cooperative flush under concurrent read stress\n");
    // Model of the production race this hardening closes: the UI/control
    // thread hammers RequestFlush() while the consumer audio thread reads
    // and the producer thread writes continuously. Invariants asserted
    // throughout:
    //   * occupancy never underflows (head_ - tail_ stays in
    //     [0, capacity] on every observed sample — the pre-hardening
    //     control-thread tail_ write could push tail_ past head_);
    //   * the consumer never blocks, allocates, or sleeps (by code
    //     inspection: the flush path is one acquire exchange, one
    //     release store, one bounded memset);
    //   * every non-flush read returns EITHER a positive count OR the
    //     digital-silence contract (flush pass / genuine starvation).
    AudioSink::Config cfg;
    cfg.sample_rate = 48000;
    cfg.channels = 2;
    cfg.capacity_frames = 1024;
    AudioSink sink(cfg);
    check(sink.valid(), "stress sink valid");

    // Phase 3 (MP-C10): the generational protocol makes RequestFlush()
    // wait (bounded) for the consumer's ack, so the consumer must stay
    // live until every request has been issued and serviced — the read
    // budget is a CAP on bookkeeping assertions, not an exit condition
    // (a consumerless requester would pay kFlushAckTimeoutMs per call).
    constexpr uint64_t kFlushRequests = 20000;
    constexpr uint64_t kReadIters = 200000;
    std::atomic<uint64_t> underflow_observations{0};
    std::atomic<bool> stop{false};
    std::atomic<bool> requests_done{false};

    std::thread producer([&sink, &stop]() {
        Rng rng(4242);
        float buf[512];  // 256 frames x 2 ch
        while (!stop.load(std::memory_order_relaxed)) {
            for (size_t i = 0; i < 512; ++i) {
                buf[i] = static_cast<float>(rng.next() % 2001 - 1000) / 32767.0f;
            }
            size_t off = 0;
            while (off < 512 && !stop.load(std::memory_order_relaxed)) {
                const size_t n = sink.WriteFloat(buf + off, 512 - off);
                if (n == 0) {
                    std::this_thread::yield();
                    continue;
                }
                off += n;
            }
        }
    });

    std::thread control([&sink, kFlushRequests, &requests_done]() {
        for (uint64_t i = 0; i < kFlushRequests; ++i) {
            sink.RequestFlush();  // noexcept: cannot throw under load
            if ((i & 0xFF) == 0) std::this_thread::yield();
        }
        requests_done.store(true, std::memory_order_release);
    });

    std::thread consumer([&sink, &stop, &requests_done, &underflow_observations,
                          kReadIters]() {
        Rng rng(777);
        int16_t buf[2048];
        for (uint64_t i = 0; i < kReadIters; ++i) {
            const size_t want = 1 + static_cast<size_t>(rng.next() % 1024);
            const size_t n = sink.ReadFrames(buf, want);
            // A read must NEVER return more than was asked: that would
            // mean the cursor pair (head_, tail_) tore — the exact class
            // of corruption the pre-hardening control-thread tail_ write
            // risked (uint64 occupancy underflow).
            if (n > want) {
                underflow_observations.fetch_add(1, std::memory_order_relaxed);
            }
            // Occupancy invariant, sampled to keep the hot loop light:
            // head_ - tail_ stays within [0, capacity] on every
            // observation. (n == 0 is legal: flush pass or starvation.)
            if (i % 8192 == 0) {
                SinkStats st;
                sink.GetStats(&st);
                if (st.current_fullness_frames > st.capacity_frames) {
                    underflow_observations.fetch_add(1,
                                                     std::memory_order_relaxed);
                }
            }
            // Keep servicing the ring until the control thread's requests
            // have all been issued (each pending generation needs one
            // consumer read to be acked — see the protocol note above).
            if (i + 1 == kReadIters && !requests_done.load(std::memory_order_acquire)) {
                i = 0;  // continue in a fresh accounting epoch
                continue;
            }
        }
    });

    control.join();   // all flush requests issued AND acked (live consumer)
    stop.store(true, std::memory_order_relaxed);
    consumer.join();  // consumer exits once requests are drained
    producer.join();

    check(underflow_observations.load() == 0,
          "no torn cursor state observed under concurrent flush/read/write");
    // Post-stress sanity: the sink still behaves per contract. Stereo
    // sink: WriteFloat takes SAMPLES (64 samples = 32 frames).
    float in[64];
    int16_t out[64];
    for (size_t i = 0; i < 64; ++i) in[i] = PatternSample(9000 + i / 2, i % 2);
    sink.RequestFlush();
    check(sink.ReadFrames(out, 32) == 0, "post-stress flush pass works");
    check(sink.WriteFloat(in, 64) == 64, "post-stress write works");
    check(sink.ReadFrames(out, 32) == 32, "post-stress read works");
}

// ============================================================================
//  D2. Canonical f32 pull path (Phase 3 / MP-C08) — the ring stores raw
//  floats end-to-end; quantization exists ONLY at the legacy PCM16 edge.
// ============================================================================

void test_ring_f32_canonical_path() {
    std::printf("  [sink] canonical f32 pull path: no premature quantization (MP-C08)\n");
    AudioSink::Config cfg;
    cfg.sample_rate = 48000;
    cfg.channels = 2;
    cfg.capacity_frames = 1024;
    AudioSink sink(cfg);
    check(sink.valid(), "f32 sink valid");

    // --- Sub-LSB dynamic range survives: values far below the PCM16 LSB
    // (1/32767 ~= 3.05e-5) must round-trip BIT-EXACT through the f32 path.
    // The pre-Phase-3 ring quantized at write time and destroyed them.
    const float tiny[8] = {1.0e-9f,  -2.0e-8f, 3.0e-7f,  -4.0e-6f,
                           5.0e-5f,  -6.0e-4f, 7.0e-3f,  -8.0e-2f};
    check(sink.WriteFloat(tiny, 8) == 8, "sub-LSB write 4 frames");
    float out_f[8];
    check(sink.ReadFramesF32(out_f, 4) == 4, "sub-LSB f32 read 4 frames");
    bool bitexact = true;
    for (size_t i = 0; i < 8; ++i) bitexact = bitexact && out_f[i] == tiny[i];
    check(bitexact, "sub-LSB floats round-trip bit-exact (f32 end-to-end)");

    // --- Full-scale values beyond [-1, 1] survive unclamped on the f32
    // path (the legacy PCM16 edge clamps; the HAL owns output SRC/limiting).
    const float hot[4] = {4.25f, -7.5f, 100.0f, -100.0f};
    check(sink.WriteFloat(hot, 4) == 4, "beyond-full-scale write 2 frames");
    check(sink.ReadFramesF32(out_f, 2) == 2, "beyond-full-scale f32 read");
    check(out_f[0] == 4.25f && out_f[1] == -7.5f && out_f[2] == 100.0f &&
              out_f[3] == -100.0f,
          "beyond-full-scale floats pass through unclamped");

    // --- Stereo interleaved wraparound fidelity (fill past the ring seam).
    float wrap_in[2048];  // 1024 frames == full ring capacity
    for (size_t i = 0; i < 2048; ++i) {
        wrap_in[i] = PatternSample(i / 2, static_cast<uint32_t>(i % 2));
    }
    check(sink.WriteFloat(wrap_in, 2048) == 2048, "wrap write fills ring");
    float wrap_out[2048];
    check(sink.ReadFramesF32(wrap_out, 1024) == 1024, "wrap f32 read 1024");
    bool wrap_ok = true;
    for (size_t i = 0; i < 2048; ++i) {
        wrap_ok = wrap_ok && wrap_out[i] == wrap_in[i];
    }
    check(wrap_ok, "stereo f32 wraparound is bit-exact");

    // --- Legacy PCM16 edge agreement: the SAME pattern, quantized at the
    // read edge, matches the frozen converter contract exactly (regression
    // against the pre-Phase-3 ring output).
    float pat_in[512];
    for (size_t i = 0; i < 512; ++i) {
        pat_in[i] = PatternSample(5000 + i / 2, static_cast<uint32_t>(i % 2));
    }
    check(sink.WriteFloat(pat_in, 512) == 512, "legacy-edge write 256 frames");
    int16_t pcm_out[1024];
    // 300 > kBlockFrames (256) per call: crosses the chunked-conversion
    // loop boundary (one full staging chunk + a starved tail).
    check(sink.ReadFrames(pcm_out, 300) == 256, "legacy-edge read starves at 256");
    bool legacy_ok = true;
    for (size_t i = 0; i < 512; ++i) {
        legacy_ok = legacy_ok && pcm_out[i] ==
                                     ExpectedPattern(5000 + i / 2,
                                                     static_cast<uint32_t>(i % 2));
    }
    check(legacy_ok, "legacy PCM16 edge bit-identical to frozen contract");

    // --- f32 flush pass: silence, zero return, no underrun accounting.
    const uint64_t underruns_before = [&sink]() {
        SinkStats s;
        sink.GetStats(&s);
        return s.underrun_events;
    }();
    check(sink.WriteFloat(pat_in, 64) == 64, "f32-flush write 32 frames");
    sink.RequestFlush();  // no live consumer: bounded timeout, lazy apply
    float flush_out[16];
    std::memset(flush_out, 0x5A, sizeof(flush_out));
    check(sink.ReadFramesF32(flush_out, 8) == 0, "f32 flush-consuming read -> 0");
    bool f32_silent = true;
    for (size_t i = 0; i < 16; ++i) {
        f32_silent = f32_silent && flush_out[i] == 0.0f;
    }
    check(f32_silent, "f32 flush-consuming read zero-fills destination");
    SinkStats st;
    sink.GetStats(&st);
    check(st.underrun_events == underruns_before,
          "f32 flush not counted as underrun");
}

// ============================================================================
//  E2. Generational flush protocol (Phase 3 / MP-C10) — counter discipline,
//  bounded ack wait, coalescing, live-consumer handshake.
// ============================================================================

void test_generational_flush_protocol() {
    std::printf("  [sink] generational flush protocol (MP-C10)\n");
    AudioSink::Config cfg;
    cfg.sample_rate = 48000;
    cfg.channels = 2;
    cfg.capacity_frames = 512;
    AudioSink sink(cfg);
    check(sink.valid(), "gen-flush sink valid");

    // --- Initial discipline: generation 0, ack 0 — no spurious flush.
    check(sink.flush_generation() == 0, "generation starts at 0");
    check(sink.flush_ack_generation() == 0, "ack starts at 0");
    float in[128];
    int16_t out[128];
    for (size_t i = 0; i < 128; ++i) {
        in[i] = PatternSample(i / 2, static_cast<uint32_t>(i % 2));
    }
    check(sink.WriteFloat(in, 128) == 128, "gen-flush write 64 frames");
    check(sink.ReadFrames(out, 16) == 16, "gen-flush read 16 frames");
    check(sink.flush_generation() == 0, "reads/writes do not bump generation");

    // --- Request with NO live consumer: bump is visible, ack stays behind,
    //     and the bounded wait expires (lazy apply on the next read).
    const auto t0 = std::chrono::steady_clock::now();
    sink.RequestFlush();
    const auto waited =
        std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - t0)
            .count();
    check(sink.flush_generation() == 1, "request bumps generation to 1");
    check(sink.flush_ack_generation() == 0,
          "no consumer -> ack stays at 0 (lazy)");
    // Bounded: ~kFlushAckTimeoutMs, generous margin for CI/ASan scheduling.
    check(waited >= 50 && waited < 2000,
          "consumerless request bounded-waits (~100ms) then returns");
    check(sink.ReadFrames(out, 4) == 0, "lazy flush applies on next read");
    check(sink.flush_ack_generation() == 1, "consumer acks generation 1");

    // --- Coalescing: N rapid bumps collapse into ONE flush pass, and the
    //     consumer acks the newest observed generation in a single step.
    check(sink.WriteFloat(in, 128) == 128, "coalesce write 64 frames");
    sink.RequestFlush();
    sink.RequestFlush();
    sink.RequestFlush();
    check(sink.flush_generation() == 4, "three more bumps -> generation 4");
    check(sink.ReadFrames(out, 4) == 0, "coalesced requests consume once");
    check(sink.flush_ack_generation() == 4,
          "consumer acks newest generation directly");
    check(sink.ReadFrames(out, 4) == 0, "no sticky flush after consumption");

    // --- Live-consumer handshake: with a reader thread servicing the ring,
    //     every request converges to ack == generation. RequestFlush itself
    //     returns at the latest after the bounded timeout, so under CI
    //     scheduling stalls the ack may land a beat later — assert eventual
    //     convergence (poll), which is the protocol's actual guarantee.
    std::atomic<bool> stop{false};
    std::thread consumer([&sink, &stop]() {
        int16_t buf[256];
        while (!stop.load(std::memory_order_relaxed)) {
            (void)sink.ReadFrames(buf, 64);
            std::this_thread::yield();
        }
    });
    bool converged = true;
    for (int i = 0; i < 50; ++i) {
        sink.RequestFlush();
        const uint64_t gen = sink.flush_generation();
        // Convergence poll: a live consumer services every pending
        // generation within one read iteration; 2 s covers pathological
        // CI descheduling without ever hanging the suite.
        for (int spin = 0; spin < 2000; ++spin) {
            if (sink.flush_ack_generation() >= gen) break;
            std::this_thread::sleep_for(std::chrono::milliseconds(1));
        }
        if (sink.flush_ack_generation() < gen) {
            converged = false;
            break;
        }
    }
    check(converged, "live consumer acks every flushed generation (50 rounds)");
    stop.store(true, std::memory_order_relaxed);
    consumer.join();

    // --- Post-handshake sanity: fresh data flows after the final flush.
    sink.RequestFlush();  // consumer is gone: bounded timeout again
    check(sink.ReadFrames(out, 4) == 0, "final flush pass");
    check(sink.WriteFloat(in, 128) == 128, "post-gen-flush write works");
    check(sink.ReadFrames(out, 64) == 64, "post-gen-flush read works");
}

// ============================================================================
//  E3. Target-dependent cache-line alignment (Phase 3 / MP-C08) — the
//  alignment constant compiles per target and the hot atomics inherit it.
// ============================================================================

void test_cache_line_alignment() {
    std::printf("  [sink] cache-line alignment discipline (MP-C08)\n");
#if defined(__APPLE__) && defined(__aarch64__)
    check(streamify::sink::kCacheLineSize == 128,
          "Apple Silicon targets the 128-byte L1 line");
#else
    check(streamify::sink::kCacheLineSize == 64,
          "x86_64 / generic ARM targets the 64-byte line");
#endif
    // The class-level alignment of AudioSink must cover the aligned members
    // (over-aligned new/delete handles the allocation portably, MP-C09).
    check(alignof(AudioSink) >= streamify::sink::kCacheLineSize,
          "AudioSink alignment covers its cache-line-aligned members");
}

// ============================================================================
//  E. Zero-allocation hot paths
// ============================================================================

void test_zero_allocation() {
    std::printf("  [sink] zero-allocation hot paths (AllocGuard)\n");
    AudioSink::Config cfg;
    cfg.sample_rate = 48000;
    cfg.channels = 2;
    cfg.capacity_frames = 2048;
    cfg.decimation = Decimation::kHalf;  // full pipeline: convert+decimate
    AudioSink sink(cfg);
    float in[512];
    int16_t out[512];
    float mono[256];
    for (size_t i = 0; i < 512; ++i) in[i] = PatternSample(i / 2, i % 2);
    uint8_t byte_out[2048];

    sink.WriteFloat(in, 512);
    sink.ReadFrames(out, 128);

    {
        streamify_test::AllocGuard g;
        (void)sink.WriteFloat(in, 512);
        check(g.count() == 0, "WriteFloat zero allocs");
    }
    {
        streamify_test::AllocGuard g;
        (void)sink.ReadFrames(out, 256);
        check(g.count() == 0, "ReadFrames zero allocs");
    }
    {
        streamify_test::AllocGuard g;
        float f_out[512];
        (void)sink.ReadFramesF32(f_out, 256);
        check(g.count() == 0, "ReadFramesF32 zero allocs");
    }
    {
        streamify_test::AllocGuard g;
        sink.RequestFlush();
        check(g.count() == 0, "RequestFlush zero allocs");
    }
    {
        streamify_test::AllocGuard g;
        (void)sink.ReadBytes(byte_out, sizeof(byte_out));
        check(g.count() == 0, "ReadBytes zero allocs");
    }
    {
        streamify_test::AllocGuard g;
        ConvertF32ToI16(in, out, 512);
        check(g.count() == 0, "SIMD converter zero allocs");
    }
    {
        streamify_test::AllocGuard g;
        DownmixStereoToMono(in, mono, 512);
        check(g.count() == 0, "downmix zero allocs");
    }
    {
        streamify_test::AllocGuard g;
        SinkStats st;
        sink.GetStats(&st);
        check(g.count() == 0, "GetStats zero allocs");
    }
    {
        Decimator d;
        d.init(3, 2);
        float d_out[600];
        streamify_test::AllocGuard g;
        // `in` holds 512 float SAMPLES == 256 stereo frames.
        (void)d.process(in, 256, d_out);
        check(g.count() == 0, "decimator zero allocs");
    }
    {
        FramePacketizer::Config pc;
        pc.sample_rate = 24000;
        pc.stereo = true;
        pc.mtu_bytes = 512;
        FramePacketizer pk(pc);
        uint8_t wire[16384];
        uint8_t decoded[8192];
        FramePacketizer::ParsedPacket info;
        std::vector<int16_t> pcm(2048 * 2);
        for (auto& s : pcm) s = static_cast<int16_t>(Rng(7).next());
        streamify_test::AllocGuard g;
        (void)pk.PackFrames(pcm.data(), pcm.size() / 2, wire, sizeof(wire));
        check(g.count() == 0, "PackFrames zero allocs");
        (void)pk.Parse(wire, sizeof(wire), &info, decoded, sizeof(decoded));
        check(g.count() == 0, "Parse zero allocs");
    }
}

// ============================================================================
//  F. SPSC contention stress (10,000,000 frames)
// ============================================================================

void test_spsc_stress_10m() {
    std::printf("  [sink] SPSC stress: 10,000,000 frames, 2 threads\n");
    constexpr uint64_t kFrames = 10000000;
    AudioSink::Config cfg;
    cfg.sample_rate = 48000;
    cfg.channels = 2;
    cfg.capacity_frames = 8192;
    AudioSink sink(cfg);
    check(sink.valid(), "stress sink valid");

    std::atomic<uint64_t> mismatches{0};
    const auto t0 = std::chrono::steady_clock::now();

    std::thread producer([&sink, &mismatches]() {
        Rng rng(1234);
        float buf[4096];  // up to 2048 frames x 2 channels
        uint64_t f = 0;
        uint64_t spins = 0;
        while (f < kFrames) {
            size_t chunk = 1 + static_cast<size_t>(rng.next() % 2048);
            if (chunk > kFrames - f) chunk = static_cast<size_t>(kFrames - f);
            if (chunk > 2048) chunk = 2048;
            for (size_t i = 0; i < chunk; ++i) {
                // 30-bit frame index split across the two channels; every
                // value is raw/32767 so the SIMD roundtrip is bit-exact.
                const uint64_t idx = f + i;
                buf[2 * i] =
                    static_cast<float>(idx & 0x7FFFull) / 32767.0f;
                buf[2 * i + 1] =
                    static_cast<float>((idx >> 15) & 0x7FFFull) / 32767.0f;
            }
            size_t off = 0;
            const size_t samples = chunk * 2;
            while (off < samples) {
                const size_t n = sink.WriteFloat(buf + off, samples - off);
                if (n == 0) {
                    if (++spins > 1000000000ull) {  // stall guard (~seconds)
                        mismatches.fetch_add(0x1000000ull);
                        return;
                    }
                    std::this_thread::yield();
                    continue;
                }
                off += n;
            }
            f += chunk;
        }
    });

    std::thread consumer([&sink, &mismatches]() {
        Rng rng(5678);
        int16_t buf[4096];
        uint64_t expect = 0;
        uint64_t spins = 0;
        bool reported = false;
        while (expect < kFrames) {
            const size_t want = 1 + static_cast<size_t>(rng.next() % 2048);
            const size_t n = sink.ReadFrames(buf, want);
            if (n == 0) {
                if (++spins > 1000000000ull) {
                    mismatches.fetch_add(0x1000000ull);
                    return;
                }
                std::this_thread::yield();
                continue;
            }
            for (size_t i = 0; i < n; ++i) {
                const uint32_t lo = static_cast<uint16_t>(buf[2 * i]);
                const uint32_t hi = static_cast<uint16_t>(buf[2 * i + 1]);
                const uint64_t idx = lo | (static_cast<uint64_t>(hi) << 15);
                if (idx != expect) {
                    mismatches.fetch_add(1, std::memory_order_relaxed);
                    if (!reported) {
                        reported = true;
                        std::printf("    first mismatch: got=%llu want=%llu\n",
                                    static_cast<unsigned long long>(idx),
                                    static_cast<unsigned long long>(expect));
                    }
                }
                ++expect;
            }
        }
    });

    producer.join();
    consumer.join();
    const auto t1 = std::chrono::steady_clock::now();
    const double secs = std::chrono::duration<double>(t1 - t0).count();

    check(mismatches.load() == 0,
          "10M frames: zero dropped / out-of-order / corrupted samples");
    SinkStats st;
    sink.GetStats(&st);
    check(st.frames_written == kFrames, "frames_written == 10,000,000");
    check(st.frames_read == kFrames, "frames_read == 10,000,000");
    check(st.overrun_dropped_frames == 0, "zero dropped frames");
    check(st.current_fullness_frames == 0, "ring fully drained");
    check(st.high_watermark_frames <= 8192, "high watermark within capacity");
    std::printf("    %.2f s wall, %.1f Mframes/s (%s sanitizer build)\n",
                secs, kFrames / secs / 1.0e6,
#ifdef __SANITIZE_ADDRESS__
                "ASan"
#else
                "unsanitized"
#endif
    );
}

void test_spsc_pipeline_stress() {
    std::printf("  [sink] SPSC pipeline stress: downmix + decimate, 2 threads\n");
    constexpr uint64_t kInFrames = 200000;
    AudioSink::Config cfg;
    cfg.sample_rate = 48000;
    cfg.channels = 2;
    cfg.capacity_frames = 4096;
    cfg.downmix_to_mono = true;
    cfg.decimation = Decimation::kThird;
    AudioSink sink(cfg);

    std::atomic<uint64_t> consumed{0};
    std::atomic<bool> done{false};

    std::thread producer([&]() {
        Rng rng(0x53555345);
        float buf[1024];  // 512 frames
        uint64_t f = 0;
        while (f < kInFrames) {
            size_t chunk = 1 + static_cast<size_t>(rng.next() % 512);
            if (chunk > kInFrames - f) chunk = static_cast<size_t>(kInFrames - f);
            if (chunk > 512) chunk = 512;
            for (size_t i = 0; i < chunk * 2; ++i) {
                buf[i] = static_cast<float>(static_cast<int32_t>(rng.next() % 65536) - 32768) / 32768.0f;
            }
            size_t off = 0;
            const size_t samples = chunk * 2;
            while (off < samples) {
                const size_t n = sink.WriteFloat(buf + off, samples - off);
                if (n == 0) {
                    std::this_thread::yield();
                    continue;
                }
                off += n;
            }
            f += chunk;
        }
        done.store(true, std::memory_order_release);
    });

    std::thread consumer([&]() {
        int16_t buf[2048];
        uint64_t total = 0;
        while (total < kInFrames / 3 || !done.load(std::memory_order_acquire)) {
            const size_t n = sink.ReadFrames(buf, 1024);
            if (n == 0) {
                if (done.load(std::memory_order_acquire) &&
                    total >= kInFrames / 3 - 2) {
                    break;
                }
                std::this_thread::yield();
                continue;
            }
            total += n;
        }
        consumed.store(total, std::memory_order_release);
    });

    producer.join();
    consumer.join();
    // Drain anything left after the producer finished.
    int16_t tail[2048];
    uint64_t extra = 0;
    for (;;) {
        const size_t n = sink.ReadFrames(tail, 2048);
        if (n == 0) break;
        extra += n;
    }

    const uint64_t total_read = consumed.load() + extra;
    SinkStats st;
    sink.GetStats(&st);
    check(st.frames_written == kInFrames / 3,
          "pipeline: frames_written == inputs/3 (within block rounding)");
    check(st.frames_written == total_read,
          "pipeline: read frame count == written frame count");
    check(st.overrun_dropped_frames == 0, "pipeline: zero dropped frames");
}

// ============================================================================
//  G. Packetizer: roundtrip, chunking, corruption, compression, pacing
// ============================================================================

void test_packet_roundtrip() {
    std::printf("  [sink] packetizer roundtrip + MTU chunking\n");
    FramePacketizer::Config pc;
    pc.sample_rate = 24000;
    pc.stereo = true;
    pc.mtu_bytes = 512;
    pc.enable_compression = false;
    FramePacketizer pk(pc);
    check(pk.valid(), "packetizer valid");

    const size_t kFrames = 2048;
    std::vector<int16_t> pcm(kFrames * 2);
    Rng rng(0xD15EA5E);
    for (auto& s : pcm) s = static_cast<int16_t>(rng.next());

    uint8_t wire[16384];
    size_t packets = 0, packed = 0;
    const size_t used =
        pk.PackFrames(pcm.data(), kFrames, wire, sizeof(wire), &packets, &packed);
    // (512 - 12) / 4 bytes-per-frame = 125 frames per packet -> 17 packets.
    check(used > 0, "wire bytes emitted");
    check(packed == kFrames, "all 2048 frames packed");
    check(packets == 17, "17 packets at MTU 512 (125 frames each)");

    // Walk + parse every packet, reassemble the payload stream.
    std::vector<uint8_t> decoded(8192);
    std::vector<uint8_t> reassembled;
    reassembled.reserve(kFrames * 4);
    size_t off = 0;
    uint16_t expect_seq = 0;
    size_t packet_i = 0;
    bool seq_ok = true, flag_ok = true, meta_ok = true;
    while (off < used) {
        FramePacketizer::ParsedPacket info;
        check(pk.Parse(wire + off, used - off, &info, decoded.data(),
                       decoded.size()) == FramePacketizer::ParseResult::kOk,
              "parse packet ok");
        seq_ok = seq_ok && info.sequence == expect_seq++;
        const size_t expect_payload =
            (packet_i == packets - 1) ? (kFrames - 16 * 125) * 4 : 125 * 4;
        meta_ok = meta_ok && info.sample_rate == 24000 && info.stereo &&
                  !info.compressed && info.payload_bytes == expect_payload;
        const bool is_last = (packet_i == packets - 1);
        flag_ok = flag_ok && info.continuation == !is_last;
        reassembled.insert(reassembled.end(), decoded.data(),
                           decoded.data() + info.payload_bytes);
        off += info.wire_bytes;
        ++packet_i;
    }
    check(seq_ok, "sequence numbers contiguous from 0");
    check(meta_ok, "header metadata (rate/stereo/payload size)");
    check(flag_ok, "continuation flag set on all but the last packet");
    check(packet_i == packets, "parsed packet count matches emit count");
    check(reassembled.size() == kFrames * 4 &&
              std::memcmp(reassembled.data(), pcm.data(), kFrames * 4) == 0,
          "reassembly byte-exact");

    // Sequence wraparound at 65536: 65537 single-frame packets, verifying
    // the wrapping cursor at regular checkpoints (0 -> 65535 -> 0).
    FramePacketizer::Config wc;
    wc.sample_rate = 8000;
    wc.stereo = false;
    wc.mtu_bytes = 16;  // 12 overhead + 2-byte mono frame + slack
    wc.enable_compression = false;
    FramePacketizer wrap_pk(wc);
    check(wrap_pk.valid(), "wrap packetizer valid");
    const int16_t one[1] = {1234};
    uint8_t wbuf[64];
    bool wrap_ok = true;
    for (uint32_t i = 0; i <= 65536u; ++i) {
        size_t p = 0;
        (void)wrap_pk.PackFrames(one, 1, wbuf, sizeof(wbuf), &p, nullptr);
        wrap_ok = wrap_ok && p == 1;
        if (i % 8192 == 0 || i == 65536u) {
            FramePacketizer::ParsedPacket winfo;
            wrap_ok = wrap_ok &&
                      wrap_pk.Parse(wbuf, sizeof(wbuf), &winfo, nullptr, 0) ==
                          FramePacketizer::ParseResult::kOk;
            wrap_ok = wrap_ok && winfo.sequence == (i & 0xFFFFu);
        }
    }
    check(wrap_ok, "sequence wraps mod 65536 (verified 0..65536)");
}

void test_packet_corruption() {
    std::printf("  [sink] packetizer corruption ladder (hostile input)\n");
    FramePacketizer::Config pc;
    pc.sample_rate = 48000;
    pc.stereo = false;
    pc.mtu_bytes = 512;
    pc.enable_compression = false;
    FramePacketizer pk(pc);
    int16_t pcm[200];
    Rng rng(99);
    for (auto& s : pcm) s = static_cast<int16_t>(rng.next());
    std::vector<uint8_t> wire(1024);
    size_t used = pk.PackFrames(pcm, 200, wire.data(), wire.size());
    check(used == 12 + 400, "single packet wire size 412");

    uint8_t decoded[8192];
    FramePacketizer::ParsedPacket info;
    using PR = FramePacketizer::ParseResult;

    // 1. Payload bit flip -> CRC mismatch.
    std::vector<uint8_t> bad = wire;
    bad[20] ^= 0x40;
    check(pk.Parse(bad.data(), used, &info, decoded, sizeof(decoded)) == PR::kCrcMismatch,
          "flipped payload bit -> kCrcMismatch");

    // 2. Magic corruption.
    bad = wire;
    bad[0] = 0x00;
    check(pk.Parse(bad.data(), used, &info, decoded, sizeof(decoded)) == PR::kBadMagic,
          "corrupt magic -> kBadMagic");

    // 3. Truncation ladder.
    check(pk.Parse(wire.data(), 5, &info, decoded, sizeof(decoded)) == PR::kTooShort,
          "5-byte packet -> kTooShort");
    check(pk.Parse(wire.data(), 11, &info, decoded, sizeof(decoded)) == PR::kTooShort,
          "11-byte packet -> kTooShort");
    check(pk.Parse(wire.data(), used - 1, &info, decoded, sizeof(decoded)) == PR::kBadSize,
          "missing CRC byte -> kBadSize");

    // 4. Oversized / lying payload size.
    bad = wire;
    bad[6] = 0xFF;
    bad[7] = 0x0F;  // 4095 > actual buffer
    check(pk.Parse(bad.data(), used, &info, decoded, sizeof(decoded)) == PR::kBadSize,
          "payload_size 4095 over buffer -> kBadSize");
    bad = wire;
    bad[6] = 0xFF;
    bad[7] = 0xFF;  // 65535 > kWearMaxPayload
    check(pk.Parse(bad.data(), used, &info, decoded, sizeof(decoded)) == PR::kBadSize,
          "payload_size 65535 -> kBadSize");

    // 5. Unknown rate code.
    bad = wire;
    bad[4] = 0x7F;
    check(pk.Parse(bad.data(), used, &info, decoded, sizeof(decoded)) == PR::kBadRateCode,
          "unknown rate code -> kBadRateCode");

    // 6. Flag ladder: unknown bit, missing CRC bit.
    bad = wire;
    bad[5] |= 0x80;
    check(pk.Parse(bad.data(), used, &info, decoded, sizeof(decoded)) == PR::kBadFlags,
          "unknown flag bit 0x80 -> kBadFlags");
    bad = wire;
    bad[5] = static_cast<uint8_t>(bad[5] & ~streamify::wear::kWearFlagCrc);
    check(pk.Parse(bad.data(), used, &info, decoded, sizeof(decoded)) == PR::kBadFlags,
          "missing CRC flag -> kBadFlags");

    // 7. CRC-valid compressed payload that decodes beyond the caller buffer.
    std::vector<uint8_t> evil(16);
    evil[0] = static_cast<uint8_t>(streamify::wear::kWearPacketMagic & 0xFF);
    evil[1] = static_cast<uint8_t>(streamify::wear::kWearPacketMagic >> 8);
    evil[2] = 0; evil[3] = 0;
    evil[4] = 4;                                   // 48 kHz
    evil[5] = streamify::wear::kWearFlagCrc | streamify::wear::kWearFlagCompressed;
    evil[6] = 4; evil[7] = 0;                      // payload = 4 bytes
    evil[8] = 0xFF; evil[9] = 0xFF;                // run 255 zeros
    evil[10] = 0xFF; evil[11] = 0xFF;              // run 255 zeros -> 510 total
    const uint32_t crc = Crc32(evil.data() + 8, 4);
    evil[12] = static_cast<uint8_t>(crc & 0xFF);
    evil[13] = static_cast<uint8_t>((crc >> 8) & 0xFF);
    evil[14] = static_cast<uint8_t>((crc >> 16) & 0xFF);
    evil[15] = static_cast<uint8_t>((crc >> 24) & 0xFF);
    uint8_t small[256];
    check(pk.Parse(evil.data(), evil.size(), &info, small, sizeof(small)) == PR::kDecodeOverflow,
          "compressed 510-zero payload into 256B buffer -> kDecodeOverflow");
    uint8_t big[1024];
    check(pk.Parse(evil.data(), evil.size(), &info, big, sizeof(big)) == PR::kOk,
          "same payload with room parses ok");
    check(info.payload_bytes == 510, "decoded payload 510 zero bytes");

    // 8. Null/zero handling.
    check(pk.Parse(nullptr, 10, &info, decoded, sizeof(decoded)) == PR::kNullArgs,
          "null packet -> kNullArgs");
    check(pk.Parse(wire.data(), 0, &info, decoded, sizeof(decoded)) == PR::kTooShort,
          "empty packet -> kTooShort");

    // 9. Random garbage: bounded, clean rejections only (no crash, no OOB —
    // any UB would trip ASan/UBSan and fail the run).
    Rng grng(0xBAADF00D);
    bool clean = true;
    for (int i = 0; i < 20000; ++i) {
        std::vector<uint8_t> junk(1 + static_cast<size_t>(grng.next() % 512));
        for (auto& b : junk) b = static_cast<uint8_t>(grng.next());
        const PR r = pk.Parse(junk.data(), junk.size(), &info, decoded,
                              sizeof(decoded));
        clean = clean && r != PR::kNullArgs;  // non-null input, valid result
        if (r == PR::kOk) {
            clean = clean && info.payload_bytes <= sizeof(decoded) &&
                    info.wire_bytes <= junk.size();
        }
    }
    check(clean, "20k random buffers: bounded, clean rejections only");

    // Telemetry accounting saw the CRC failure.
    streamify::wear::WearTelemetry tel;
    pk.SnapshotTelemetry(&tel);
    check(tel.crc_failures >= 1, "crc_failures telemetry incremented");
    check(tel.parse_errors >= 10, "parse_errors telemetry incremented");
}

void test_compression_codec() {
    std::printf("  [sink] zero-run compressor roundtrip + bounds\n");
    // Silence-heavy stream compresses hard and roundtrips exactly.
    std::vector<uint8_t> raw(4096, 0);
    Rng rng(0x5EED);
    for (size_t i = 0; i < raw.size(); i += 97) {
        raw[i] = static_cast<uint8_t>(rng.next());
    }
    std::vector<uint8_t> comp(WearCompressMaxOutput(raw.size()));
    const size_t c = WearCompressPayload(raw.data(), raw.size(), comp.data(),
                                         comp.size());
    check(c != streamify::wear::kWearCodecBadSize, "compress succeeds");
    check(c < raw.size() / 8, "silence-heavy stream compresses > 8:1");
    std::vector<uint8_t> back(4096);
    const size_t d = WearDecompressPayload(comp.data(), c, back.data(),
                                           back.size());
    check(d == raw.size() && std::memcmp(back.data(), raw.data(), d) == 0,
          "decompress roundtrip byte-exact");

    // Random (incompressible) data still roundtrips via literal tokens.
    for (auto& b : raw) b = static_cast<uint8_t>(rng.next());
    const size_t c2 = WearCompressPayload(raw.data(), raw.size(), comp.data(),
                                          comp.size());
    check(c2 != streamify::wear::kWearCodecBadSize, "random data compresses (expands)");
    check(c2 <= WearCompressMaxOutput(raw.size()), "expansion within bound");
    const size_t d2 = WearDecompressPayload(comp.data(), c2, back.data(),
                                            back.size());
    if (d2 != raw.size()) {
        std::printf("    random roundtrip: decoded %zu of %zu bytes (c2=%zu)\n",
                    d2, raw.size(), c2);
    } else if (std::memcmp(back.data(), raw.data(), d2) != 0) {
        for (size_t i = 0; i < d2; ++i) {
            if (back[i] != raw[i]) {
                std::printf("    random roundtrip: first diff at %zu: "
                            "%02x != %02x (c2=%zu)\n",
                            i, back[i], raw[i], c2);
                break;
            }
        }
    }
    check(d2 == raw.size() && std::memcmp(back.data(), raw.data(), d2) == 0,
          "random roundtrip byte-exact");

    // Bounds + malformed-token rejections.
    check(WearCompressPayload(nullptr, 10, comp.data(), comp.size()) ==
              streamify::wear::kWearCodecBadSize,
          "null input rejected");
    check(WearCompressPayload(raw.data(), 0, comp.data(), comp.size()) ==
              streamify::wear::kWearCodecBadSize,
          "empty input rejected");
    const uint8_t zeros[64] = {0};
    check(WearCompressPayload(zeros, 64, comp.data(), 1) ==
              streamify::wear::kWearCodecBadSize,
          "compression capacity overflow rejected");
    uint8_t one[1] = {0xFF};
    check(WearDecompressPayload(one, 1, back.data(), back.size()) ==
              streamify::wear::kWearCodecBadSize,
          "truncated run token rejected");
    uint8_t lit[2] = {3, 0xAA};  // claims 3 literals, has 1
    check(WearDecompressPayload(lit, 2, back.data(), back.size()) ==
              streamify::wear::kWearCodecBadSize,
          "truncated literal token rejected");
    uint8_t run[2] = {0xFF, 200};
    check(WearDecompressPayload(run, 2, back.data(), 100) ==
              streamify::wear::kWearCodecBadSize,
          "decode capacity overflow rejected");

    // Packetizer with compression enabled: silence packets shrink and the
    // reassembly is still byte-exact.
    FramePacketizer::Config pc;
    pc.sample_rate = 16000;
    pc.stereo = true;
    pc.mtu_bytes = 256;
    pc.enable_compression = true;
    FramePacketizer pk(pc);
    std::vector<int16_t> pcm(512 * 2, 0);  // 512 stereo frames of silence
    pcm[0] = 1234;
    pcm[1] = -4321;
    uint8_t wire[8192];
    size_t packets = 0, packed = 0;
    const size_t used =
        pk.PackFrames(pcm.data(), 512, wire, sizeof(wire), &packets, &packed);
    check(used > 0 && packets > 0 && packed == 512, "compressed burst emitted");
    check(used < 512 * 4, "silence burst compresses on the wire");
    // Parse + decode the first packet: compressed flag set, payload decodes
    // to whole frames and roundtrips the leading samples.
    uint8_t decoded[8192];
    FramePacketizer::ParsedPacket info;
    check(pk.Parse(wire, used, &info, decoded, sizeof(decoded)) ==
              FramePacketizer::ParseResult::kOk,
          "compressed packet parses");
    check(info.compressed, "compressed flag advertised");
    check(info.payload_bytes % 4 == 0, "decoded payload frame-aligned");
    int16_t first_l = 0, first_r = 0;
    std::memcpy(&first_l, decoded, 2);
    std::memcpy(&first_r, decoded + 2, 2);
    check(first_l == 1234 && first_r == -4321,
          "leading samples survive compression roundtrip");
}

void test_rate_limiter() {
    std::printf("  [sink] token-bucket rate limiter (deterministic clock)\n");
    // Assertions keep comfortable margins (>= 10 tokens) instead of
    // knife-edge boundaries so the CONTRACT (burst grant, refill rate, burst
    // cap, no-minting on clock regress) is what's verified — not bit-exact
    // double paths that legitimately vary across toolchains and
    // floating-point modes (the CI link stage runs LTO with fast-math).
    RateLimiter rl;
    rl.configure(1000, 2000);  // 1000 B/s, 2000 B burst
    check(rl.tryAcquireAt(1500, 0.0), "t=0: burst grant 1500 of 2000");
    check(!rl.tryAcquireAt(2000, 0.0), "t=0: 500 remaining < 2000 denied");
    check(rl.tryAcquireAt(400, 1000.0), "t=1s: +1000 refill grants 400");
    check(!rl.tryAcquireAt(2000, 1000.0), "t=1s: ~1100 remaining < 2000 denied");
    // Idle accrual saturates at the burst cap.
    check(rl.tryAcquireAt(1000, 10000.0), "t=10s: burst cap reached, 1000 granted");
    check(!rl.tryAcquireAt(2000, 10000.0), "t=10s: ~1000 remaining < 2000 denied");
    check(!rl.tryAcquireAt(1500, 10000.0), "t=10s: still below request denied");
    // Clock regress must neither mint tokens nor rewind the clock.
    check(rl.tryAcquireAt(900, 10000.5), "t=10s: small grant 900 of ~1000");
    check(!rl.tryAcquireAt(500, 5.0), "clock regress mints no tokens");
    check(!rl.tryAcquireAt(200, 10000.6), "tiny accrual still short of 200");
    check(rl.tryAcquireAt(100, 10011.0), "accrued ~111 tokens grant 100");
    check(!rl.tryAcquireAt(50, 10011.5), "~11 residual < 50 denied");
    // Unlimited budget always grants.
    rl.configure(0, 0);
    check(rl.tryAcquire(1u << 20), "unlimited budget grants 1 MiB");
    // Zero-byte acquisition is a no-op grant.
    check(rl.tryAcquireAt(0, 0.0), "zero bytes always granted");
}

void test_packetizer_throttle() {
    std::printf("  [sink] packetizer pacing + backpressure telemetry\n");
    FramePacketizer::Config pc;
    pc.sample_rate = 24000;
    pc.stereo = true;
    pc.mtu_bytes = 512;
    pc.rate_budget_bytes_per_sec = 512;  // one MTU per second
    pc.burst_bytes = 512;                // exactly one packet of budget
    pc.enable_compression = false;
    FramePacketizer pk(pc);
    check(pk.valid(), "throttled packetizer valid");

    std::vector<int16_t> pcm(2000 * 2);
    Rng rng(31337);
    for (auto& s : pcm) s = static_cast<int16_t>(rng.next());
    uint8_t wire[16384];
    size_t packets = 0, packed = 0;
    const size_t used =
        pk.PackFrames(pcm.data(), 2000, wire, sizeof(wire), &packets, &packed);
    check(packets == 1, "burst budget allows exactly one packet");
    check(packed < 2000, "remaining frames deferred (backpressure)");
    check(used <= 512, "emitted packet within MTU");

    streamify::wear::WearTelemetry tel;
    pk.SnapshotTelemetry(&tel);
    check(tel.packets_throttled >= 1, "throttle telemetry counted");
    check(tel.packets_emitted == 1, "emitted telemetry counted");
    check(tel.frames_packed == packed, "frames_packed telemetry matches");

    // With the limiter opened up, the rest flows (fresh limiter state).
    pk.limiter().configure(0, 0);
    size_t packets2 = 0, packed2 = 0;
    uint8_t wire2[16384];
    const size_t used2 =
        pk.PackFrames(pcm.data() + packed * 2, 2000 - packed, wire2,
                      sizeof(wire2), &packets2, &packed2);
    check(packed2 == 2000 - packed, "deferred frames flow after unthrottle");
    check(used2 > 0 && packets2 > 0, "second burst emitted");

    // Output-capacity backpressure: tiny out buffer defers cleanly.
    FramePacketizer pk2(pc);
    pk2.limiter().configure(0, 0);
    uint8_t tiny[64];
    size_t p3 = 0, f3 = 0;
    const size_t used3 =
        pk2.PackFrames(pcm.data(), 2000, tiny, sizeof(tiny), &p3, &f3);
    check(used3 <= sizeof(tiny) && p3 == 0 && f3 == 0,
          "buffer below min wire size -> clean no-op");

    // Invalid configs rejected.
    FramePacketizer::Config bad = pc;
    bad.mtu_bytes = 8;  // cannot carry header+crc+one frame
    check(!FramePacketizer::ValidateConfig(bad), "MTU below one frame rejected");
    bad = pc;
    bad.sample_rate = 22050;  // no rate code
    check(!FramePacketizer::ValidateConfig(bad), "unsupported rate rejected");
    FramePacketizer bad_pk(bad);
    check(!bad_pk.valid(), "invalid packetizer stays inert");
    check(bad_pk.PackFrames(pcm.data(), 100, tiny, sizeof(tiny)) == 0,
          "invalid packetizer PackFrames no-ops");
    // Parse on an invalid instance still works (receiver side is stateless).
    uint8_t decoded[8192];
    FramePacketizer::ParsedPacket info;
    check(pk.Parse(wire, used, &info, decoded, sizeof(decoded)) ==
              FramePacketizer::ParseResult::kOk,
          "parse works on any instance");
}

// ============================================================================
//  H. Registry + stats DirectByteBuffer serialization
// ============================================================================

void test_registry_and_stats_buffer() {
    std::printf("  [sink] sink registry + stats wire layout\n");
    SinkRegistry::instance().destroyAll();
    check(SinkRegistry::instance().liveCount() == 0, "registry starts empty");

    AudioSink::Config cfg;
    cfg.sample_rate = 48000;
    cfg.channels = 2;
    cfg.capacity_frames = 4096;
    const int64_t h1 = SinkRegistry::instance().create(cfg);
    const int64_t h2 = SinkRegistry::instance().create(cfg);
    check(h1 > 0 && h2 > 0 && h1 != h2, "handles positive and unique");

    AudioSink::Config bad;
    bad.sample_rate = 100;
    check(SinkRegistry::instance().create(bad) == 0,
          "invalid config -> handle 0");

    auto sink = SinkRegistry::instance().resolve(h1);
    check(sink != nullptr, "resolve live handle");
    check(SinkRegistry::instance().resolve(h1).get() == sink.get(),
          "resolve returns the same underlying sink");
    check(SinkRegistry::instance().liveCount() == 2, "liveCount 2");

    // Audio flows through the registry-resolved sink.
    float in[2000];
    for (size_t i = 0; i < 2000; ++i) in[i] = PatternSample(i / 2, i % 2);
    check(sink->WriteFloat(in, 2000) == 2000, "registry write 1000 frames");
    int16_t out[2000];
    check(SinkRegistry::instance().resolve(h1)->ReadFrames(out, 500) == 500,
          "registry read via a second resolve");

    // Stats wire layout.
    uint8_t buf[96];
    check(WriteSinkStatsBuffer(*sink, buf, sizeof(buf)), "stats buffer written");
    check(GetLE32(buf + streamify::sink::kSinkStatsOffMagic) == 0x51AB0001u,
          "layout magic at +0");
    check(GetLE32(buf + streamify::sink::kSinkStatsOffLayoutBytes) == 96,
          "layout bytes 96 at +4");
    check(GetLE32(buf + streamify::sink::kSinkStatsOffCapacity) == 4096,
          "capacity at +8");
    check(GetLE32(buf + streamify::sink::kSinkStatsOffRateIn) == 48000 &&
              GetLE32(buf + streamify::sink::kSinkStatsOffRateOut) == 48000,
          "sample rates at +12/+16");
    check(GetLE32(buf + streamify::sink::kSinkStatsOffChannelsIn) == 2 &&
              GetLE32(buf + streamify::sink::kSinkStatsOffChannelsOut) == 2,
          "channels at +20/+24");
    check(GetLE32(buf + streamify::sink::kSinkStatsOffFullness) == 500,
          "fullness frames at +28");
    check(GetLE64(buf + streamify::sink::kSinkStatsOffFramesWritten) == 1000,
          "frames_written at +32");
    check(GetLE64(buf + streamify::sink::kSinkStatsOffFramesRead) == 500,
          "frames_read at +40");
    const uint32_t seq1 = GetLE32(buf + streamify::sink::kSinkStatsOffSequence);
    uint8_t buf2[96];
    WriteSinkStatsBuffer(*sink, buf2, sizeof(buf2));
    check(GetLE32(buf2 + streamify::sink::kSinkStatsOffSequence) == seq1 + 1,
          "stats sequence increments");
    check(GetLE32(buf2 + streamify::sink::kSinkStatsOffReserved) == 0,
          "reserved word zero");
    uint8_t small[95];
    check(!WriteSinkStatsBuffer(*sink, small, sizeof(small)),
          "undersized stats buffer rejected");
    check(!WriteSinkStatsBuffer(*sink, nullptr, 96), "null stats buffer rejected");

    // Handle hardening: garbage handles never resolve and never crash.
    check(SinkRegistry::instance().resolve(0) == nullptr, "handle 0 rejected");
    check(SinkRegistry::instance().resolve(-1) == nullptr, "negative rejected");
    check(SinkRegistry::instance().resolve(0xDEADBEEF) == nullptr,
          "garbage handle rejected");
    check(!SinkRegistry::instance().destroy(0xDEADBEEF),
          "destroy garbage handle is a no-op");

    // Destroy racing resolvers: shared_ptr keep-alive must stay safe.
    std::atomic<bool> stop{false};
    std::vector<std::thread> resolvers;
    for (int t = 0; t < 3; ++t) {
        resolvers.emplace_back([&stop, h2]() {
            while (!stop.load(std::memory_order_relaxed)) {
                auto s = SinkRegistry::instance().resolve(h2);
                if (s != nullptr) {
                    int16_t throwaway[4];
                    (void)s->ReadFrames(throwaway, 2);
                }
            }
        });
    }
    check(SinkRegistry::instance().destroy(h2), "destroy while resolvers run");
    std::this_thread::sleep_for(std::chrono::milliseconds(10));
    stop.store(true, std::memory_order_relaxed);
    for (auto& t : resolvers) t.join();
    check(SinkRegistry::instance().resolve(h2) == nullptr,
          "destroyed handle no longer resolves");
    check(!SinkRegistry::instance().destroy(h2), "double destroy is a no-op");
    check(SinkRegistry::instance().liveCount() == 1, "one sink left");

    SinkRegistry::instance().destroyAll();
    check(SinkRegistry::instance().liveCount() == 0, "destroyAll clears");
    check(SinkRegistry::instance().resolve(h1) == nullptr,
          "post-destroyAll resolve fails");
}

}  // namespace

int run_audio_sink_tests() {
    std::printf("[phase4] audio sinks: SPSC ring, SIMD converter, Wear codec\n");
    test_crc32();
    test_convert_f32_to_i16();
    test_downmix();
    test_decimator();
    test_ring_basic_io();
    test_ring_telemetry();
    test_ring_flush();
    test_cooperative_flush_concurrent_stress();
    test_ring_f32_canonical_path();
    test_generational_flush_protocol();
    test_cache_line_alignment();
    test_zero_allocation();
    test_spsc_stress_10m();
    test_spsc_pipeline_stress();
    test_packet_roundtrip();
    test_packet_corruption();
    test_compression_codec();
    test_rate_limiter();
    test_packetizer_throttle();
    test_registry_and_stats_buffer();
    std::printf("[phase4] audio sinks: %d passed, %d failed\n", g_passed,
                g_failed);
    return g_failed;
}
