#include <algorithm>
#include <cstdint>
#include <cstddef>
#include <cstring>
#include <vector>

#include "../dsp/SoftKneeLimiter.h"
#include "../dsp/LufsNormalizer.h"
// Phase-1 additions (BEHIND.md #11/#13/#39/#41).
#include "../dsp/AcousticPhaseResampler.h"
#include "../dsp/ChannelOps.h"
#include "../dsp/MasterChain.h"
#include "../mix/AudioRingBuffer.h"
#include "../mix/PeerMixPool.h"
// Phase-2 additions (BEHIND.md #15/#20/#25/#26/#39).
#include "../math/CandidateHasher.h"
#include "../math/CircadianCurves.h"
#include "../math/HarmonicTransitionEngine.h"
// Phase-5 additions: SIMD palette clustering + WCAG contrast engine,
// lock-free frame pacer (composed from tests/fuzz_palette.cpp).
namespace streamify {
int StreamifyFuzzPaletteFrame(const uint8_t* data, size_t size);
}
// Phase-3 additions (BEHIND.md #45/#38/#44/#57).
#include "../agsl/AmbientGlowShader.h"
#include "../audio/AdtsFrameParser.h"
#include "../audio/AudioFrameRemuxer.h"
#include "../audio/OpusPacketParser.h"
#include "../palette/BitmapPalette.h"
#include "../video/CanvasLoopMath.h"

// LibFuzzer abort()s are reported as crashes; use this for invariants.
static inline void fuzz_check(bool ok) {
    if (!ok) __builtin_trap();
}

extern "C" int LLVMFuzzerTestOneInput(const uint8_t *data, size_t size) {
    if (size < 16) return 0;

    // 1. Fuzz float PCM limiter + normalizer.
    // memcpy into an aligned buffer: reinterpret_cast on arbitrary-offset
    // fuzz bytes is alignment-UB under UBSan.
    size_t numFloats = size / sizeof(float);
    if (numFloats > 0 && numFloats <= 4096) {
        std::vector<float> floatBuffer(numFloats);
        std::memcpy(floatBuffer.data(), data, numFloats * sizeof(float));
        streamify::dsp::SoftKneeLimiter limiter(0.85f, 0.15f);
        limiter.processFloats(floatBuffer.data(), static_cast<int>(floatBuffer.size()));
        for (float v : floatBuffer) fuzz_check(__builtin_isfinite(v));

        // Local instance: shared-singleton state across cases kills reproducibility.
        LufsNormalizer normalizer;
        normalizer.processFloats(floatBuffer.data(), static_cast<int>(floatBuffer.size()), -14.0f);
        for (float v : floatBuffer) fuzz_check(__builtin_isfinite(v));
    }

    // 2. Fuzz short PCM limiter
    size_t numShorts = size / sizeof(int16_t);
    if (numShorts > 0 && numShorts <= 4096) {
        std::vector<int16_t> shortBuffer(numShorts);
        std::memcpy(shortBuffer.data(), data, numShorts * sizeof(int16_t));
        streamify::dsp::SoftKneeLimiter limiter(0.85f, 0.15f);
        limiter.processShorts(shortBuffer.data(), static_cast<int>(shortBuffer.size()));
    }

    // 3. Phase-1: MasterChain (LUFS glide + balance/mono + true-peak ceiling).
    if (numFloats >= 4 && numFloats <= 4096) {
        const uint8_t* b = data;
        float balance;
        std::memcpy(&balance, b, 4);
        float ceilingDb;
        std::memcpy(&ceilingDb, b + 4, 4);
        const bool mono = (b[8] & 1) != 0;
        const bool bypass = (b[8] & 2) != 0;

        std::vector<float> pcm(numFloats);
        std::memcpy(pcm.data(), data, numFloats * sizeof(float));

        streamify::dsp::MasterChain chain;
        chain.configure(48000, 2);
        chain.setBalance(balance);
        chain.setLimiterCeiling(ceilingDb);
        chain.setMonoDownmix(mono);
        chain.setSilentBypass(bypass);
        const int frames = static_cast<int>(numFloats / 2);
        if (bypass) {
            // Silent bypass: the PROCESSED region (frames * 2 floats) must
            // come back bit-identical; the odd tail float is not touched by
            // contract. Fuzz bytes may legitimately BE NaN/Inf patterns.
            std::vector<float> before = pcm;
            chain.process(pcm.data(), frames);
            fuzz_check(std::memcmp(pcm.data(), before.data(),
                                   static_cast<size_t>(frames) * 2 * sizeof(float)) == 0);
        } else {
            // Plant hostile float patterns deterministically.
            if (numFloats > 512) {
                pcm[7] = __builtin_nanf("");
                pcm[11] = __builtin_inff();
                pcm[13] = -__builtin_inff();
            }
            chain.process(pcm.data(), frames);
            for (int i = 0; i < frames * 2; ++i) {   // processed region only;
                fuzz_check(__builtin_isfinite(pcm[static_cast<size_t>(i)]));  // the odd tail
            }                                              // float is untouched
        }                                                   // by contract
    }

    // 4. Phase-1: ChannelOps — linear stage: finite input stays finite.
    if (numFloats >= 8) {
        float balance;
        std::memcpy(&balance, data, 4);
        streamify::dsp::ChannelOps ops;
        ops.setBalance(balance);
        ops.setMonoDownmix((data[4] & 1) != 0);
        std::vector<float> pcm(numFloats);
        std::memcpy(pcm.data(), data, numFloats * sizeof(float));
        if (numFloats > 64) {
            pcm[31] = __builtin_nanf("");
            pcm[33] = __builtin_inff();
        }
        std::vector<float> fin(numFloats);
        std::memcpy(fin.data(), data, numFloats * sizeof(float));
        // Fuzz floats can be Inf/NaN/near-FLT_MAX (x*sqrt2 would overflow).
        // The invariant under test is the stage's LOGIC: build a finite,
        // non-overflowing variant and assert it stays finite after the gain.
        for (auto& v : fin) {
            if (!__builtin_isfinite(v)) v = 0.0f;
            else if (v > 1e36f) v = 1e36f;
            else if (v < -1e36f) v = -1e36f;
        }
        ops.processInterleaved(fin.data(), numFloats / 2);
        for (float v : fin) fuzz_check(__builtin_isfinite(v));
    }

    // 5. Phase-1: AudioRingBuffer write/read/peek/accumulate.
    if (numFloats >= 16) {
        const int chan = 1 + (data[0] % 8);
        const size_t cap = 16 + (data[1] % 512);
        streamify::mix::AudioRingBuffer rb;
        if (rb.init(chan, cap)) {
            std::vector<float> src(numFloats);
            std::memcpy(src.data(), data, numFloats * sizeof(float));
            const size_t frames = numFloats / chan;
            (void)rb.write(src.data(), frames);
            std::vector<float> dst(numFloats, 0.0f);
            const size_t got = rb.read(dst.data(), frames);
            fuzz_check(got <= frames);
            float g;
            std::memcpy(&g, data + 8, 4);
            (void)rb.accumulate(dst.data(), got, g);
            std::vector<float> peek(numFloats, 0.0f);
            (void)rb.peek(peek.data(), got);
            rb.reset();
        }
    }

    // 6. Phase-1: PeerMixPool (up to 8 peers, saturation-bounded output).
    if (numFloats >= 32) {
        streamify::mix::PeerMixPool pool;
        if (pool.init(48000, 2, 512)) {
            const int peers = 1 + (data[2] % 8);
            std::vector<float> src(256 * 2);
            std::memcpy(src.data(), data, std::min(numFloats, size_t(512)) * sizeof(float));
            for (int i = 0; i < peers; ++i) {
                const int slot = pool.attachPeer();
                if (slot < 0) break;
                float g;
                std::memcpy(&g, data + 12 + i * 4, 4);
                pool.setPeerGain(slot, g);
                const size_t n = 64 + (data[3 + i] % 192);
                fuzz_check(pool.peerWrite(slot, src.data(), n));
            }
            std::vector<float> out(256 * 2, 0.0f);
            streamify::mix::PeerMixPool::MixStats st;
            pool.mix(out.data(), 256, &st);
            for (float v : out) {
                fuzz_check(__builtin_isfinite(v));
                fuzz_check(v <= 1.0000002f && v >= -1.0000002f);
            }
        }
    }

    // 7. Phase-1: resampler silent-bypass transitions + active kernel.
    if (numFloats >= 64 && numFloats <= 4096) {
        streamify::dsp::AcousticPhaseResampler res;
        int64_t drift;
        std::memcpy(&drift, data, 8);
        res.setTargetDriftNanosPerSecond(drift);
        const bool bypass = (data[8] & 1) != 0;
        res.SetSilentBypass(bypass);
        const int frames = static_cast<int>(numFloats / 2);

        // (a) ACTIVE kernel: finite, non-overflowing input stays finite.
        {
            std::vector<float> inA(numFloats);
            std::memcpy(inA.data(), data, numFloats * sizeof(float));
            for (auto& v : inA) {
                if (!__builtin_isfinite(v)) v = 0.0f;
                else if (v > 1e36f) v = 1e36f;
                else if (v < -1e36f) v = -1e36f;
            }
            std::vector<float> outA(numFloats, 0.0f);
            const int r = res.process(inA.data(), frames, outA.data(), frames + 8);
            fuzz_check(r >= 0);
            for (int i = 0; i < r * 2; ++i) {          // only the produced region
                fuzz_check(__builtin_isfinite(outA[static_cast<size_t>(i)]));
            }
        }
        // (b) BYPASS: bit-exact passthrough of the RAW bytes (NaN/Inf by
        // design — the resampler must not touch a single sample).
        {
            res.SetSilentBypass(true);
            std::vector<float> inB(numFloats);
            std::memcpy(inB.data(), data, numFloats * sizeof(float));
            std::vector<float> outB(numFloats, 0.0f);
            const int r = res.process(inB.data(), frames, outB.data(), frames + 8);
            fuzz_check(r == frames);
            fuzz_check(std::memcmp(outB.data(), inB.data(),
                                   static_cast<size_t>(frames) * 2 * sizeof(float)) == 0);
            res.SetSilentBypass(false);
        }
    }

    // 8. Phase-2: candidate dedup kernels — deep structural invariants over
    //    two fuzz-derived u64 streams (heavy-collision domain half the time
    //    so both the table fast path and dup-heavy paths stay exercised).
    {
        const size_t numU64 = size / sizeof(uint64_t);
        if (numU64 >= 8) {
            size_t half = numU64 / 2;
            if (half > 96) half = 96;              // bound the O(n^2) checks
            std::vector<uint64_t> a(half);
            std::vector<uint64_t> b(std::min(numU64 - half, static_cast<size_t>(96)));
            std::memcpy(a.data(), data, a.size() * sizeof(uint64_t));
            std::memcpy(b.data(), data + a.size() * sizeof(uint64_t),
                        b.size() * sizeof(uint64_t));
            if ((data[0] & 1) != 0) {
                for (auto& v : a) v %= 7;
                for (auto& v : b) v %= 7;
            }
            const auto nA = static_cast<int32_t>(a.size());
            const auto nB = static_cast<int32_t>(b.size());
            auto memberOf = [](const std::vector<uint64_t>& v, uint64_t h) {
                for (uint64_t x : v)
                    if (x == h) return true;
                return false;
            };

            std::vector<uint8_t> ma(a.size()), mb(b.size());
            const int32_t common = streamify::math::computeIntersectionMasks(
                a.data(), nA, b.data(), nB, ma.data(), mb.data());
            fuzz_check(common >= 0 && common <= nA);
            for (int32_t i = 0; i < nA; ++i) {
                fuzz_check(ma[static_cast<size_t>(i)] ==
                           (memberOf(b, a[static_cast<size_t>(i)]) ? 1 : 0));
            }
            for (int32_t j = 0; j < nB; ++j) {
                fuzz_check(mb[static_cast<size_t>(j)] ==
                           (memberOf(a, b[static_cast<size_t>(j)]) ? 1 : 0));
            }

            // Full-capacity union + invariants.
            std::vector<uint64_t> u(static_cast<size_t>(nA) + nB, 0xAB);
            std::vector<uint8_t> ov(u.size(), 7);
            const int32_t n = streamify::math::computeUniqueUnion(
                a.data(), nA, b.data(), nB, u.data(), ov.data(),
                static_cast<int32_t>(u.size()));
            fuzz_check(n >= 0 && static_cast<size_t>(n) <= u.size());
            // (i) no duplicates in the union.
            for (int32_t i = 0; i < n; ++i) {
                for (int32_t j = i + 1; j < n; ++j) {
                    fuzz_check(u[static_cast<size_t>(i)] != u[static_cast<size_t>(j)]);
                }
            }
            // (ii) every union element belongs to A or B; overlap flag
            //      set iff it belongs to BOTH.
            for (int32_t i = 0; i < n; ++i) {
                const uint64_t h = u[static_cast<size_t>(i)];
                const bool inA = memberOf(a, h);
                const bool inB = memberOf(b, h);
                fuzz_check(inA || inB);
                fuzz_check(ov[static_cast<size_t>(i)] == ((inA && inB) ? 1 : 0));
            }
            // (iii) every unique A value and every B-only value is present.
            for (int32_t i = 0; i < nA; ++i) {
                bool first = true;
                for (int32_t k = 0; k < i; ++k) {
                    if (a[static_cast<size_t>(k)] == a[static_cast<size_t>(i)]) {
                        first = false;
                        break;
                    }
                }
                if (first) fuzz_check(memberOf(u, a[static_cast<size_t>(i)]));
            }
            for (int32_t j = 0; j < nB; ++j) {
                const uint64_t h = b[static_cast<size_t>(j)];
                if (!memberOf(a, h)) fuzz_check(memberOf(u, h));
            }
            // (iv) size probe (NULL output) returns the SAME required size.
            fuzz_check(streamify::math::computeUniqueUnion(
                           a.data(), nA, b.data(), nB, nullptr, nullptr, 0) == n);
            // (v) truncation: capacity n-1 still returns n and matches the
            //     first n-1 entries of the full write.
            if (n > 0) {
                std::vector<uint64_t> ut(u.size(), 0xAB);
                const int32_t nt = streamify::math::computeUniqueUnion(
                    a.data(), nA, b.data(), nB, ut.data(), nullptr, n - 1);
                fuzz_check(nt == n);
                fuzz_check(std::memcmp(ut.data(), u.data(),
                                       static_cast<size_t>(n - 1) * sizeof(uint64_t)) == 0);
            }
        }
    }

    // 9. Phase-2: transition scoring — hostile keys and RAW float BPMs
    //    (fuzz bytes are frequently NaN/Inf/huge bit patterns; the engine
    //    must stay finite and in [0,1] for ALL of them).
    if (size >= 28) {
        int32_t keyA;
        int32_t keyB;
        std::memcpy(&keyA, data, 4);
        std::memcpy(&keyB, data + 4, 4);
        float bpmA;
        float bpmB;
        std::memcpy(&bpmA, data + 8, 4);
        std::memcpy(&bpmB, data + 12, 4);
        const bool minorA = (data[16] & 1) != 0;
        const bool minorB = (data[17] & 1) != 0;

        const float s = streamify::math::transitionCompatibility(
            keyA, minorA, bpmA, keyB, minorB, bpmB);
        fuzz_check(__builtin_isfinite(s) && s >= 0.0f && s <= 1.0f);
        const float k = streamify::math::keyTransitionScore(keyA, minorA, keyB,
                                                            minorB);
        fuzz_check(__builtin_isfinite(k) && k >= 0.0f && k <= 1.0f);
        const float bpm = streamify::math::bpmTransitionScore(bpmA, bpmB);
        fuzz_check(__builtin_isfinite(bpm) && bpm >= 0.0f && bpm <= 1.0f);
    }

    // 10. Phase-2: circadian curves — hostile local hours (NaN/Inf/huge),
    //     plus a finite-domain periodicity spot check (t == t + 24).
    if (size >= 36) {
        float hour;
        std::memcpy(&hour, data + 20, 4);
        const auto w = streamify::math::circadianWeights(hour);
        fuzz_check(__builtin_isfinite(w.energy) && w.energy >= 0.0f &&
                   w.energy <= 1.0f);
        fuzz_check(__builtin_isfinite(w.acousticness) && w.acousticness >= 0.0f &&
                   w.acousticness <= 1.0f);
        fuzz_check(__builtin_isfinite(w.valence) && w.valence >= 0.0f &&
                   w.valence <= 1.0f);
        // Periodicity identity t == t + 24, guarded by an ERROR-BUDGET
        // proof rather than a naive magnitude bound. fl(hour+24) can differ
        // from hour+24 by up to 1/2 ULP; a shift error of eps hours moves
        // each weight by <= slope_max*eps (slope_max ~ 0.167/h) so the
        // squared distance stays < 3*(0.167*eps)^2 and must stay under the
        // 1e-6 tolerance. |hour| < 4096 bounds eps by 1/2 ULP(4120) =
        // 2^-12 h -> squared distance <= ~5e-9 (200x margin). Larger hours
        // legitimately break the identity: CI crash-b1b1bcde hit
        // hour = 33554430 (0x4BFFFFFF < the old 1e8 guard) whose +24 shift
        // crosses the 2^25 ULP boundary (2 -> 4) and rounds to hour+22,
        // a float-precision reality, not an engine defect.
        if (__builtin_isfinite(hour) && hour > -4096.0f && hour < 4096.0f) {
            const auto w2 = streamify::math::circadianWeights(hour + 24.0f);
            const float de = w2.energy - w.energy;
            const float da = w2.acousticness - w.acousticness;
            const float dv = w2.valence - w.valence;
            fuzz_check(de * de + da * da + dv * dv < 1e-6f);
        }
    }

    // 11. Phase-2: candidate hashing — determinism, production == reference,
    //     bulk == single, across arbitrary byte spans (any length 0..64).
    //     (Length byte = data[23], the LAST byte: data[24] on a minimum-size
    //     24-byte input is a one-past-end read — caught by CI LibFuzzer,
    //     crash-5ebf58bb, after the local fixed-buffer soak missed it.)
    if (size >= 24) {
        const uint32_t spanLen = data[23] % 65;
        if (static_cast<size_t>(1 + spanLen) <= size) {
            const char* span = reinterpret_cast<const char*>(data) + 1;
            const uint64_t h1 = streamify::math::hashCandidateId(span, spanLen);
            const uint64_t h2 = streamify::math::hashCandidateId(span, spanLen);
            fuzz_check(h1 == h2);
            fuzz_check(h1 == streamify::math::hashCandidateIdReference(span,
                                                                       spanLen));
            streamify::math::CandidateIdSpan spans[2] = {{span, spanLen},
                                                          {span, spanLen}};
            uint64_t out[2] = {1, 2};
            streamify::math::hashCandidateIdsBulk(spans, 2, out);
            fuzz_check(out[0] == h1 && out[1] == h1);
        }
    }

    // 12. Phase-3: audio remuxer — corrupted Opus/ADTS bitstreams against
    //     the remuxer and both packet validators. data[0] picks the codec;
    //     the stream is carved into deterministic sub-spans, seeded with
    //     structurally valid heads half the time so the ACCEPT path stays
    //     exercised (a harness that only feeds garbage proves nothing about
    //     the muxer, only the reject ladder). Sequence numbers regress
    //     occasionally to exercise the out-of-order gate.
    if (size >= 16) {
        const bool aac = (data[0] & 1) != 0;
        std::vector<uint8_t> out;
        out.reserve(8192);
        const streamify::audio::RemuxSink sink{
            &out, [](void* ctx, const uint8_t* d, size_t n) {
                static_cast<std::vector<uint8_t>*>(ctx)->insert(
                    static_cast<std::vector<uint8_t>*>(ctx)->end(), d, d + n);
                return true;
            }};
        streamify::audio::AudioFrameRemuxer remux;
        streamify::audio::RemuxerConfig cfg;
        cfg.codec = aac ? streamify::audio::Codec::kAacAdts
                        : streamify::audio::Codec::kOpus;
        remux.begin(cfg, sink);

        size_t pos = 1;
        uint64_t seq = 0;
        while (pos < size) {
            size_t len = 1 + (data[pos] % 97);
            if (len > size - pos) len = size - pos;
            std::vector<uint8_t> pkt(data + pos, data + pos + len);
            // Seed a plausible header on every 2nd packet (bounded by the
            // minimum header sizes so the seed itself cannot overread).
            if ((seq & 1) == 0) {
                if (aac && len >= 7) {
                    pkt[0] = 0xFF;
                    pkt[1] = 0xF1;
                    pkt[2] = static_cast<uint8_t>(
                        (1 << 6) | ((data[pos] % 13) << 2));
                    pkt[3] = static_cast<uint8_t>(2 << 6);
                    pkt[4] = static_cast<uint8_t>((len >> 3) & 0xFF);
                    pkt[5] = static_cast<uint8_t>(((len & 7) << 5) | 0x1F);
                    pkt[6] = 0xFC;
                } else if (!aac && len >= 2) {
                    pkt[0] = static_cast<uint8_t>(
                        (31u << 3) | ((data[pos] & 4) ? (1u << 2) : 0u) |
                        (data[pos] % 4));
                }
            }
            // Sequence: strictly increasing except every 8th packet, which
            // regresses into the already-seen range.
            const uint64_t s =
                (seq % 8 == 7) ? (seq / 2) : seq;
            (void)remux.remuxPacket(pkt.data(), pkt.size(), s);
            ++seq;
            pos += len;
        }
        (void)remux.finish();
        (void)remux.remuxPacket(data, 4, seq);  // post-finish: wrong state

        const auto& st = remux.stats();
        fuzz_check(st.packetsIn == st.packetsAccepted + st.droppedCorrupt +
                                      st.droppedTruncated +
                                      st.droppedOutOfOrder);
        if (!out.empty()) {
            if (!aac) {
                // Opus output is always a valid Ogg prefix.
                fuzz_check(std::memcmp(out.data(), "OggS", 4) == 0);
            } else {
                // ADTS output frames always carry a syncword head.
                fuzz_check(out.size() >= 7);
                fuzz_check(out[0] == 0xFF && (out[1] & 0xF0) == 0xF0);
            }
        }

        // Standalone validators on raw sub-spans (probe API, no session):
        // any bytes, any length — never a crash (the checks are the calls
        // themselves under ASan/UBSan; statuses are informational).
        {
            streamify::audio::OpusFrameInfo oi;
            (void)streamify::audio::OpusPacketParser::parse(data + 8,
                                                           size - 8, &oi);
            streamify::audio::AdtsFrameInfo ai;
            (void)streamify::audio::AdtsFrameParser::parse(data + 8,
                                                          size - 8, false,
                                                          &ai);
            // Truncated variants: exact-boundary discipline.
            if (size >= 10) {
                (void)streamify::audio::OpusPacketParser::parse(data, size - 1,
                                                                &oi);
                (void)streamify::audio::AdtsFrameParser::parse(data, size - 1,
                                                               false, &ai);
            }
        }
    }

    // 13. Phase-3: palette extractor — malformed/truncated image bytes.
    //     data[1..3] derive dimensions/formats for both a large case (usually
    //     past the buffer: graceful kBadBufferSize) and a tiny case (usually
    //     accepted: full pipeline under fuzz bytes). Every successful
    //     extraction must produce valid ARGB colors and a real WCAG pick.
    if (size >= 16) {
        streamify::palette::BitmapPaletteExtractor extractor;
        streamify::palette::PaletteResult r;
        const int32_t bigW = 1 + (data[1] % 512);
        const int32_t bigH = 1 + (data[2] % 512);
        const int32_t fmtIdx = data[3] % 3;
        const auto fmt = static_cast<streamify::palette::PixelFormat>(fmtIdx);
        // Large case: offset 4, all remaining bytes.
        (void)extractor.extract(data + 4, size - 4, bigW, bigH, fmt, &r);
        // Tiny case: offset 8, dimensions that usually fit.
        const int32_t w = 1 + (data[5] % 16);
        const int32_t h = 1 + (data[6] % 16);
        const auto st2 = extractor.extract(data + 8, size - 8, w, h, fmt, &r);
        // Snapshot for the determinism check: `r` is overwritten in place
        // by the RGB565 twin and the truncation probe below.
        const streamify::palette::PaletteResult first = r;
        if (st2 == streamify::palette::PaletteStatus::kOk) {
            fuzz_check((r.primaryArgb >> 24) == 0xFF);
            fuzz_check((r.secondaryArgb >> 24) == 0xFF);
            fuzz_check((r.textForegroundArgb >> 24) == 0xFF);
            fuzz_check((r.ambientGlowArgb >> 24) == 0xFF);
            fuzz_check(r.textForegroundArgb == 0xFFFFFFFF ||
                       r.textForegroundArgb == 0xFF000000);
            // Theoretical floor of max(white,black) contrast is ~4.58.
            fuzz_check(r.foregroundContrastRatio >= 4.0f);
            fuzz_check(r.swatchCount >= 1 && r.swatchCount <= 24);
            // RGB565 twin: same bytes as 565 must also be graceful.
            (void)extractor.extract(data + 8, size - 8, w, h,
                                    streamify::palette::PixelFormat::kRgb565,
                                    &r);
        }
        // Exact-boundary truncation: one byte short of any declared size is
        // ALWAYS kBadBufferSize, never a crash (never reads past `size`).
        const int32_t w2 = 1 + (data[9] % 48);
        const int32_t h2 = 1 + (data[10] % 48);
        const size_t need =
            static_cast<size_t>(w2) * static_cast<size_t>(h2) *
            static_cast<size_t>(
                streamify::palette::BitmapPaletteExtractor::bytesPerPixel(
                    fmt));
        if (need > 0 && size - 8 >= need) {
            fuzz_check(extractor.extract(data + 8, need - 1, w2, h2, fmt,
                                         &r) ==
                       streamify::palette::PaletteStatus::kBadBufferSize);
        }
        // Determinism under fuzz bytes: identical input -> identical bits.
        // (Compares against the snapshot taken BEFORE the RGB565 twin and
        // truncation probe overwrote `r`.)
        if (st2 == streamify::palette::PaletteStatus::kOk) {
            streamify::palette::BitmapPaletteExtractor twin;
            streamify::palette::PaletteResult r2;
            fuzz_check(twin.extract(data + 8, size - 8, w, h, fmt, &r2) ==
                       streamify::palette::PaletteStatus::kOk);
            fuzz_check(std::memcmp(&first, &r2, sizeof(first)) == 0);
        }
    }

    // 14. Phase-3: canvas loop + AGSL param generator — hostile time and
    //     config floats (fuzz bytes are frequently NaN/Inf/huge patterns;
    //     every output must stay finite and in range), plus a periodicity
    //     error-budget spot check in a PROVEN safe domain (see the Phase-2
    //     circadian guard for the methodology): |t| < 1e6 ms and period in
    //     [0.5, 120] s bound the float shift error by < 1 ULP of 1e6 ms
    //     (0.0625 ms) so the phase drifts at most ~1e-8 and every derived
    //     param by <= 2*pi*1e-8*scale << 1e-4.
    if (size >= 40) {
        float tMs, periodSec;
        std::memcpy(&tMs, data + 24, 4);
        std::memcpy(&periodSec, data + 28, 4);
        streamify::video::CanvasLoopConfig cfg;
        cfg.periodSec = periodSec;
        cfg.blendSec = tMs;  // hostile blend, sanitized internally
        streamify::video::CanvasLoopFrame f;
        streamify::video::CanvasLoopMath::computeFrame(
            tMs * 0.001f, cfg, 0.012f, 0.035f, 0.015f, 1.0f, &f);
        fuzz_check(std::isfinite(f.phase) && f.phase >= 0.0f && f.phase < 1.0f);
        fuzz_check(std::isfinite(f.crossfadeWeight) &&
                   f.crossfadeWeight >= 0.0f && f.crossfadeWeight <= 1.0f);
        fuzz_check(std::isfinite(f.scale) && std::isfinite(f.rotationRad) &&
                   std::isfinite(f.translateX) && std::isfinite(f.translateY));
        fuzz_check(std::isfinite(f.glowPulse) && f.glowPulse >= 0.0f &&
                   f.glowPulse <= 1.0f);
        fuzz_check(std::isfinite(f.hueDriftRad));

        // AGSL param generator with an entirely hostile config. NOTE: the
        // float->int64 cast is domain-guarded FIRST — casting NaN/Inf/huge
        // floats to int64 is UB (float-cast-overflow) and the CI fuzzer
        // would rightly trap it (the JNI layer receives jlong natively and
        // never performs this cast).
        streamify::agsl::AmbientGlowConfig gcfg;
        float hostile[16];
        std::memcpy(hostile, data + 24, 16);  // may be NaN/Inf patterns
        streamify::agsl::AmbientGlowRuntime::floatsToConfig(hostile, &gcfg);
        const int64_t ms =
            (std::isfinite(tMs) && std::fabs(tMs) < 1.0e15f)
                ? static_cast<int64_t>(tMs)
                : 0;
        streamify::agsl::GlowShaderParams params;
        streamify::agsl::AmbientGlowRuntime::computeFrameParams(
            ms, 1080, 2400, gcfg, &params);
        const float* pf = reinterpret_cast<const float*>(&params);
        for (int i = 0; i < streamify::agsl::GlowShaderParams::kFloatCount;
             ++i) {
            fuzz_check(std::isfinite(pf[static_cast<size_t>(i)]));
        }
        // loopPhase bound: [0,1] INCLUSIVE — hostile config floats can select
        // the ping-pong strategy, whose triangle phase legitimately peaks at
        // exactly 1.0f at the turnaround (t/period == 0.5). The stricter
        // wraparound bound (< 1.0) is a dedicated unit-test invariant.
        fuzz_check(params.loopPhase >= 0.0f && params.loopPhase <= 1.0f);
        fuzz_check(params.crossfade >= 0.0f && params.crossfade <= 1.0f);

        // Periodicity spot check (safe domain, WRAP-AWARE methodology).
        //
        // Error budget (second revision — the first trapped the local soak
        // by ignoring the ms quantization of the sample offset):
        //   * the B sample is placed at (int64)(period*1000) ms, and that
        //     cast TRUNCATES fractional milliseconds: the sample can sit
        //     up to ~1 ms off the true seam. At the smallest guarded period
        //     (0.5 s) that alone is a phase error of 1e-3/0.5 = 2.0e-3.
        //     This is a HARNESS measurement artifact — the engine's own
        //     seam periodicity is bit-exact at exact-ms periods and is
        //     unit-tested as such at t = 0 / period.
        //   * float demotion of tSec rounds: |tSec| < 1000 s -> two sides
        //     of 0.5*ULP(1024 s) = 6.1e-5 s -> 2.44e-4 phase at 0.5 s.
        //   * the quotient t/period rounds independently per side:
        //     quotient <= 2000 -> 2 * 0.5*ULP(2000) = 1.2e-4.
        //   * total phase error <= 2.0e-3 + 2.44e-4 + 1.2e-4 = 2.4e-3 ->
        //     loopPhase/lagPhase tolerance 5e-3 (2.1x margin); hueDriftRad
        //     = 2*pi*hueTurns*phase, hueTurns = 1 (clean defaults) ->
        //     1.5e-2 -> tolerance 5e-2 (3.3x margin).
        //   * RAW phases wrap at the seam and crossfade jumps 1 -> 0 there
        //     BY DESIGN — the continuity lives in the shader composite
        //     mix(g(p), g(p-1), w), unit-tested bit-exact at the seam. The
        //     linear phase comparison is only sound when BOTH samples rest
        //     outside the crossfade window (weight exactly 0): the 0-weight
        //     region ends at 1 - blendFrac <= 1 - 1/120, an 8.3e-3 guard
        //     band that still dwarfs the 2.4e-3 phase error, so no wrap
        //     can occur between the two samples. In-window samples skip.
        //   * every config-derived field (resolution, intensity, breath,
        //     colors, noise*, grain, vignette) is time-invariant: must be
        //     BIT-IDENTICAL between the two frames.
        if (std::isfinite(tMs) && std::fabs(tMs) < 1.0e6f &&
            std::isfinite(periodSec) && periodSec >= 0.5f &&
            periodSec <= 120.0f) {
            streamify::agsl::GlowShaderParams pA, pB;
            streamify::agsl::AmbientGlowConfig clean;
            clean.periodSec = periodSec;
            streamify::agsl::AmbientGlowRuntime::computeFrameParams(
                static_cast<int64_t>(tMs), 500, 500, clean, &pA);
            streamify::agsl::AmbientGlowRuntime::computeFrameParams(
                static_cast<int64_t>(tMs) +
                    static_cast<int64_t>(periodSec * 1000.0f),
                500, 500, clean, &pB);
            const float* a = reinterpret_cast<const float*>(&pA);
            const float* b = reinterpret_cast<const float*>(&pB);
            // Field map (frozen by the uniform table static_asserts):
            // 0-1 resolution, 2 loopPhase, 3 crossfade, 4 lagPhase,
            // 5 intensity, 6 breath, 7 hueDriftRad, 8-16 colors,
            // 17-20 noise/grain/vignette.
            for (int i = 0; i < streamify::agsl::GlowShaderParams::kFloatCount;
                 ++i) {
                if (i == 2 || i == 3 || i == 4 || i == 7) continue;
                fuzz_check(a[static_cast<size_t>(i)] ==
                           b[static_cast<size_t>(i)]);
            }
            if (pA.crossfade == 0.0f && pB.crossfade == 0.0f) {
                fuzz_check(std::fabs(a[2] - b[2]) < 5e-3f);   // loopPhase
                fuzz_check(std::fabs(a[4] - b[4]) < 5e-3f);   // lagPhase
                fuzz_check(std::fabs(a[7] - b[7]) < 5e-2f);   // hueDriftRad
            }
        }
    }

    // Phase-5 additions: arbitrary image bitstreams through the SIMD palette
    // extractor (corrupt dimensions/strides/buffers, extreme RGB, the WCAG
    // >= 4.5:1 text-surface contract, determinism) and corrupted frame
    // histograms through the lock-free pacer (exact counters, percentile
    // monotonicity, wire-layout capacity guards). Lives in
    // tests/fuzz_palette.cpp (STREAMIFY_FUZZ_COMPOSED build).
    streamify::StreamifyFuzzPaletteFrame(data, size);

    return 0;
}
