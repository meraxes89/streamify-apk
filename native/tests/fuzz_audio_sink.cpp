// ============================================================================
//  fuzz_audio_sink.cpp — Phase-4 LibFuzzer harness: malformed network audio
//  streams and packet corruption (native/tests/fuzz_audio_sink.cpp)
// ============================================================================
//
//  Phase-4 directive deliverable 5. Feeds fully hostile byte streams into:
//    1. FramePacketizer::Parse — truncated headers, corrupted magic/flags/
//       rate codes, lying payload sizes, CRC-randomized payloads and
//       malformed compressed payloads (every rejection path must stay
//       in-bounds; any out-of-range access trips ASan).
//    2. PackFrames -> Parse roundtrip invariant — anything the packetizer
//       emits MUST parse back cleanly (wire format self-consistency).
//    3. The zero-run RLE codec — compress/decompress roundtrip exactness
//       on arbitrary bytes, plus raw hostile compressed streams into small
//       decoder buffers (decode-overflow must be rejected, never OOB).
//    4. AudioSink — arbitrary float bit patterns (incl. NaN/Inf) through
//       the SIMD converter (finite inputs within 1 LSB of the scalar
//       reference; non-finite map to silence or a saturated rail), the
//       downmix + decimation pipeline, ring reads/writes and telemetry
//       invariants (occupancy/watermarks within capacity).
//
//  Invariant violations call __builtin_trap() which libFuzzer reports as a
//  crash. Build modes:
//    * clang + -fsanitize=fuzzer  -> classic LibFuzzer target (CI).
//    * -DSTREAMIFY_FUZZ_STANDALONE -> self-contained deterministic mutator
//      driver (runs under plain ASan/UBSan with any compiler; used for
//      local smoke runs and sanitizer-only environments).
// ============================================================================

#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstring>

#include "../include/audio_sink_ringbuffer.h"
#include "../include/wear_audio_encoder.h"

using streamify::sink::AudioSink;
using streamify::sink::ConvertF32ToI16;
using streamify::sink::ConvertSampleF32ToI16;
using streamify::sink::Decimation;
using streamify::sink::SinkStats;
using streamify::wear::FramePacketizer;
using streamify::wear::WearCompressMaxOutput;
using streamify::wear::WearCompressPayload;
using streamify::wear::WearDecompressPayload;
using streamify::wear::kWearCodecBadSize;

// LibFuzzer abort()s are reported as crashes; use this for invariants.
static inline void fuzz_check(bool ok) {
    if (!ok) __builtin_trap();
}

static FramePacketizer::Config PacketizerConfigFromByte(uint8_t b) {
    static const uint32_t kRates[] = {8000, 16000, 24000, 44100, 48000};
    FramePacketizer::Config c;
    c.sample_rate = kRates[b % 5];
    c.stereo = (b & 0x40) != 0;
    c.mtu_bytes = 16u + static_cast<uint32_t>(b % 200u) * 20u;  // 16..4016
    c.enable_compression = (b & 0x80) != 0;
    c.rate_budget_bytes_per_sec = (b & 0x20) ? 65536u : 0u;
    if (!FramePacketizer::ValidateConfig(c)) {
        // Stereotype configs must always be valid; fall back defensively.
        c = FramePacketizer::Config{};
        c.sample_rate = 24000;
        c.mtu_bytes = 512;
        fuzz_check(FramePacketizer::ValidateConfig(c));
    }
    return c;
}

extern "C" int LLVMFuzzerTestOneInput(const uint8_t* data, size_t size) {
    if (size < 8) return 0;

    // ---- 1. Parse torture on raw fuzz bytes -------------------------------
    {
        FramePacketizer pkt(PacketizerConfigFromByte(data[0]));
        static uint8_t decoded[8192];
        FramePacketizer::ParsedPacket info;
        const auto r =
            pkt.Parse(data, size, &info, decoded, sizeof(decoded));
        fuzz_check(r >= FramePacketizer::ParseResult::kOk &&
                   r <= FramePacketizer::ParseResult::kDecodeOverflow);
        if (r == FramePacketizer::ParseResult::kOk) {
            fuzz_check(info.wire_bytes >= streamify::wear::kWearMinWireSize);
            fuzz_check(info.wire_bytes <= size);
            fuzz_check(info.payload_bytes <= sizeof(decoded));
            fuzz_check(info.sample_rate != 0);
        }
    }

    // ---- 2/3. Pack roundtrip: emitted wire MUST parse back cleanly --------
    {
        FramePacketizer pk(PacketizerConfigFromByte(data[1]));
        if (pk.valid()) {
            static int16_t pcm[2048];
            const size_t pcm_samples =
                size >= 2 ? ((size - 2) / 2 < 2048 ? (size - 2) / 2 : 2048)
                          : 0;
            // memcpy staging: reinterpret_cast on arbitrary-offset fuzz
            // bytes is alignment-UB under UBSan.
            if (pcm_samples > 0) {
                std::memcpy(pcm, data + 2, pcm_samples * sizeof(int16_t));
            }
            const size_t ch = pk.config().stereo ? 2u : 1u;
            const size_t frames = pcm_samples / ch;
            static uint8_t wire[16384];
            size_t packets = 0;
            size_t packed = 0;
            const size_t used =
                pk.PackFrames(pcm, frames, wire, sizeof(wire), &packets,
                              &packed);
            fuzz_check(used <= sizeof(wire));
            fuzz_check(packed <= frames);
            size_t off = 0;
            while (off < used) {
                static uint8_t dec[8192];
                FramePacketizer::ParsedPacket info;
                const auto rr =
                    pk.Parse(wire + off, used - off, &info, dec, sizeof(dec));
                fuzz_check(rr == FramePacketizer::ParseResult::kOk);
                fuzz_check(info.wire_bytes >= streamify::wear::kWearMinWireSize);
                fuzz_check(off + info.wire_bytes <= used);
                off += info.wire_bytes;
            }
            // Pacing may defer frames, but never emit more than requested.
            if (packets == 0) fuzz_check(used == 0);
        }
    }

    // ---- 4. RLE codec roundtrip + hostile compressed streams ---------------
    {
        const size_t n = size < 4096 ? size : 4096;
        static uint8_t comp[8200];  // WearCompressMaxOutput(4096) = 4096*2+8
        const size_t c = WearCompressPayload(data, n, comp, sizeof(comp));
        if (c != kWearCodecBadSize) {
            static uint8_t back[4352];
            const size_t d =
                WearDecompressPayload(comp, c, back, sizeof(back));
            fuzz_check(d == n);
            fuzz_check(d == n && std::memcmp(back, data, n) == 0);
        }
        // Raw hostile "compressed" stream into a small buffer: bounded
        // rejection only (any OOB write trips ASan).
        static uint8_t small[512];
        (void)WearDecompressPayload(data, size < 2048 ? size : 2048, small,
                                    sizeof(small));
    }

    // ---- 5. Sink torture with arbitrary float bit patterns -----------------
    {
        AudioSink::Config cfg;
        cfg.sample_rate = 48000;
        cfg.channels = 2;
        cfg.capacity_frames = 64;  // minimum: maximum wrap pressure
        cfg.downmix_to_mono = (data[2] & 0x01) != 0;
        cfg.decimation = (data[2] & 0x02) ? Decimation::kThird
                        : (data[2] & 0x04) ? Decimation::kHalf
                                           : Decimation::kNone;
        AudioSink sink(cfg);
        if (sink.valid()) {
            static float f[1024];
            const size_t fn =
                size >= 3 ? ((size - 3) / 4 < 1024 ? (size - 3) / 4 : 1024)
                          : 0;
            if (fn > 0) {
                std::memcpy(f, data + 3, fn * sizeof(float));
            }
            const size_t consumed = sink.WriteFloat(f, fn);
            fuzz_check(consumed <= fn);

            // Converter invariants on arbitrary bit patterns (NaN/Inf/Inf-
            // ranges included): finite within 1 LSB of the scalar reference,
            // non-finite confined to silence or the saturated rails.
            static int16_t cv[1024];
            ConvertF32ToI16(f, cv, fn);
            for (size_t i = 0; i < fn; ++i) {
                if (std::isfinite(f[i])) {
                    const int16_t ref = ConvertSampleF32ToI16(f[i]);
                    const int delta =
                        static_cast<int>(cv[i]) - static_cast<int>(ref);
                    fuzz_check(delta >= -1 && delta <= 1);
                } else {
                    fuzz_check(cv[i] == 0 || cv[i] == 32767 ||
                               cv[i] == -32767);
                }
            }

            static int16_t out[2048];
            const size_t got = sink.ReadFrames(out, 2048);
            fuzz_check(got <= sink.capacity_frames());
            static uint8_t bytes[4096];
            const size_t rb = sink.ReadBytes(bytes, sizeof(bytes));
            fuzz_check(rb % sink.frame_bytes_out() == 0);

            // Telemetry invariants must hold under any write pattern.
            SinkStats st;
            sink.GetStats(&st);
            fuzz_check(st.current_fullness_frames <= st.capacity_frames);
            fuzz_check(st.high_watermark_frames <= st.capacity_frames);
            fuzz_check(st.low_watermark_frames <= st.capacity_frames);
            fuzz_check(st.frames_read <= st.frames_written);
        }
    }
    return 0;
}

// ============================================================================
//  Standalone deterministic mutator driver (no libFuzzer runtime needed)
// ============================================================================

#ifdef STREAMIFY_FUZZ_STANDALONE

#include <cstdio>
#include <cstdlib>
#include <vector>

namespace {

struct StandaloneRng {
    uint64_t s;
    explicit StandaloneRng(uint64_t seed) : s(seed ? seed : 0x853C49E6748FEA9Bull) {}
    uint64_t next() {
        s ^= s << 13;
        s ^= s >> 7;
        s ^= s << 17;
        return s;
    }
    uint32_t below(uint32_t n) { return static_cast<uint32_t>(next() % n); }
};

std::vector<uint8_t> MakeSeedPackets() {
    // Valid wire traffic for the mutator to chew on: a packed stereo burst
    // (compressed and raw) at a small MTU.
    FramePacketizer::Config pc;
    pc.sample_rate = 24000;
    pc.stereo = true;
    pc.mtu_bytes = 64;
    pc.enable_compression = false;
    FramePacketizer raw_pk(pc);
    std::vector<int16_t> pcm(600, 0);
    for (size_t i = 0; i < pcm.size(); ++i) {
        pcm[i] = static_cast<int16_t>((i * 7919u) % 4001u);
    }
    std::vector<uint8_t> out(16384);
    const size_t used =
        raw_pk.PackFrames(pcm.data(), pcm.size() / 2, out.data(), out.size());
    out.resize(used);

    pc.enable_compression = true;
    FramePacketizer::Config pc2 = pc;
    FramePacketizer comp_pk(pc2);
    std::vector<uint8_t> comp(16384);
    const size_t used2 =
        comp_pk.PackFrames(pcm.data(), pcm.size() / 2, comp.data(), comp.size());
    comp.resize(used2);

    out.insert(out.end(), comp.begin(), comp.end());
    return out;
}

}  // namespace

int main(int argc, char** argv) {
    unsigned long iters = 300000;
    if (argc > 1) {
        const unsigned long n = std::strtoul(argv[1], nullptr, 10);
        if (n > 0) iters = n;
    }
    StandaloneRng rng(0x5EED5A11C0DE5ull);
    const std::vector<uint8_t> seeds = MakeSeedPackets();
    fuzz_check(!seeds.empty());

    std::vector<uint8_t> buf;
    unsigned long crashes = 0;  // trap() aborts the process, so reaching the
                                 // summary means zero invariant violations.
    for (unsigned long it = 0; it < iters; ++it) {
        buf = seeds;
        // Mutate: 1-8 edits, then a random structural change.
        const uint32_t edits = 1 + rng.below(8);
        for (uint32_t e = 0; e < edits; ++e) {
            if (buf.empty()) break;
            const size_t pos = rng.below(static_cast<uint32_t>(buf.size()));
            switch (rng.below(3)) {
                case 0: buf[pos] ^= static_cast<uint8_t>(1u << rng.below(8)); break;
                case 1: buf[pos] = static_cast<uint8_t>(rng.next()); break;
                default: buf[pos] = static_cast<uint8_t>(rng.below(2) ? 0x00 : 0xFF); break;
            }
        }
        switch (rng.below(4)) {
            case 0:  // truncate
                if (!buf.empty()) buf.resize(rng.below(static_cast<uint32_t>(buf.size())) + 1);
                break;
            case 1:  // splice out a middle chunk
                if (buf.size() > 4) {
                    const size_t a = rng.below(static_cast<uint32_t>(buf.size() - 2));
                    const size_t b = a + 1 + rng.below(static_cast<uint32_t>(buf.size() - a - 1));
                    buf.erase(buf.begin() + static_cast<ptrdiff_t>(a),
                              buf.begin() + static_cast<ptrdiff_t>(b));
                }
                break;
            case 2:  // extend with random bytes
                for (int i = 0; i < 16; ++i) {
                    buf.push_back(static_cast<uint8_t>(rng.next()));
                }
                break;
            default:  // pure random buffer of seed length
                buf.resize(1 + rng.below(512));
                for (auto& b : buf) b = static_cast<uint8_t>(rng.next());
                break;
        }
        LLVMFuzzerTestOneInput(buf.data(), buf.size());
        ++crashes;  // every completed iteration is a survivor
    }
    std::printf("[fuzz-audio-sink] standalone: %lu iterations survived, "
                "0 invariant violations\n",
                crashes);
    return 0;
}

#endif  // STREAMIFY_FUZZ_STANDALONE
