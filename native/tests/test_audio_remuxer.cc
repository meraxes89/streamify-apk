// ============================================================================
//  test_audio_remuxer.cc — Phase 3 verification: Opus/ADTS bitstream
//  validators + packet-stream remuxer (BEHIND.md #38/#44)
// ============================================================================
//
//  Covers the Phase-3 directive deliverable 2:
//    A. OpusPacketParser — all four TOC codes, RFC 6716 accounting (VBR size
//       runs with the 254/255 extension, padding runs), rejection ladder.
//    B. AdtsFrameParser — full header ladder (sync, layer, rate index,
//       lengths), strict 44.1/48 kHz mode, normalized header writer.
//    C. AudioFrameRemuxer — Ogg Opus structural audit (page walk, CRC,
//       granules, BOS/EOS), ADTS normalization, out-of-order/truncated/
//       corrupt drops, stats conservation, zero-alloc per-packet path.
//
//  Linked into dsp_test_suite (native-dsp CI shard); entry point
//  run_audio_remuxer_tests() is called from test_dsp.cc's main().
// ============================================================================

#include <cmath>
#include <cstdio>
#include <cstring>
#include <vector>

#include "../audio/AdtsFrameParser.h"
#include "../audio/AudioFrameRemuxer.h"
#include "../audio/OpusPacketParser.h"

#include "AllocGuard.h"

using streamify::audio::AdtsFrameParser;
using streamify::audio::AdtsFrameInfo;
using streamify::audio::AudioFrameRemuxer;
using streamify::audio::Codec;
using streamify::audio::OpusFrameInfo;
using streamify::audio::OpusPacketParser;
using streamify::audio::ParseStatus;
using streamify::audio::RemuxerConfig;
using streamify::audio::RemuxSink;
using PS = streamify::audio::ParseStatus;

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
    uint8_t byte() { return static_cast<uint8_t>(next() >> 32); }
};

uint64_t rdLe(const uint8_t* p, int n) {
    uint64_t v = 0;
    for (int i = n - 1; i >= 0; --i) v = (v << 8) | p[i];
    return v;
}

// Build an ADTS frame (LC profile): 7-byte header + `payload` bytes; the
// declared length is `declaredLen` when >= 0 (for truncation tests).
std::vector<uint8_t> makeAdts(int srIdx, int ch, int payload,
                              int declaredLen = -1, bool withCrc = false) {
    const int hdr = withCrc ? 9 : 7;
    int total = hdr + payload;
    if (declaredLen >= 0) total = declaredLen;
    std::vector<uint8_t> f(hdr + payload, 0xA5);
    f[0] = 0xFF;
    f[1] = static_cast<uint8_t>(withCrc ? 0xF0 : 0xF1);  // MPEG-4, layer 0
    f[2] = static_cast<uint8_t>((1 << 6) | (srIdx << 2) | ((ch >> 2) & 1));
    f[3] = static_cast<uint8_t>(((ch & 3) << 6) | ((total >> 11) & 3));
    f[4] = static_cast<uint8_t>((total >> 3) & 0xFF);
    f[5] = static_cast<uint8_t>(((total & 7) << 5) | (0x7FF >> 6));
    f[6] = static_cast<uint8_t>(((0x7FF & 0x3F) << 2) | 0);
    return f;
}

// Vector sink for output capture.
struct VecSink {
    std::vector<uint8_t> out;
};

bool vecWrite(void* ctx, const uint8_t* data, size_t len) {
    static_cast<VecSink*>(ctx)->out.insert(
        static_cast<VecSink*>(ctx)->out.end(), data, data + len);
    return true;
}

// Fixed-capacity sink for zero-alloc audits.
struct FixedSink {
    uint8_t buf[1 << 17];  // 128 KB
    size_t fill = 0;
    bool failNext = false;
};

bool fixedWrite(void* ctx, const uint8_t* data, size_t len) {
    auto* s = static_cast<FixedSink*>(ctx);
    if (s->failNext) return false;
    if (s->fill + len > sizeof(s->buf)) return false;
    std::memcpy(s->buf + s->fill, data, len);
    s->fill += len;
    return true;
}

// One code-0 Opus packet (config 31 = CELT FB 20 ms).
std::vector<uint8_t> makeOpus(int payload, bool stereo = true,
                              uint8_t config = 31) {
    std::vector<uint8_t> p(static_cast<size_t>(payload) + 1, 0x33);
    p[0] = static_cast<uint8_t>((config << 3) | (stereo ? 1u << 2 : 0u) | 0u);
    return p;
}

}  // namespace

int run_audio_remuxer_tests() {
    std::printf("[phase3] audio remuxer & bitstream validators\n");

    // ------------------------------------------------------------------
    // A. OpusPacketParser
    // ------------------------------------------------------------------
    std::printf("  [opus] TOC codes 0-3 + RFC 6716 accounting\n");
    {
        OpusFrameInfo fi;
        // Code 0: single frame.
        auto p = makeOpus(100, false);
        check(OpusPacketParser::parse(p.data(), p.size(), &fi) == PS::kOk,
              "code 0 parses");
        check(fi.frameCount == 1 && fi.frameSizeMs == 20.0f,
              "code 0 frame count/size");
        check(fi.samplesAt48k == 960 && fi.channels == 1 && !fi.vbr,
              "code 0 samples/channels");
        check(fi.largestFrameBytes == 100, "code 0 frame size");
        check(fi.sampleRateHz == 48000 && fi.pcmBitDepth == 16,
              "opus decode attributes");
        // Frame over the RFC 1275 limit.
        auto big = makeOpus(1276);
        check(OpusPacketParser::parse(big.data(), big.size(), &fi) ==
                  PS::kBadFrameLength,
              "code 0 frame > 1275 rejected");
        // Empty packet.
        check(OpusPacketParser::parse(p.data(), 0, &fi) == PS::kTooSmall,
              "empty packet -> too small");
        check(OpusPacketParser::parse(nullptr, 10, &fi) ==
                  PS::kInvalidArgument,
              "null data -> invalid argument");
        check(OpusPacketParser::parse(p.data(), 10, nullptr) ==
                  PS::kInvalidArgument,
              "null info -> invalid argument");

        // Code 1: two VBR frames.
        std::vector<uint8_t> c1(2 + 10 + 20, 0);
        c1[0] = (31u << 3) | (1u << 2) | 1u;
        c1[1] = 10;
        check(OpusPacketParser::parse(c1.data(), c1.size(), &fi) == PS::kOk,
              "code 1 parses");
        check(fi.frameCount == 2 && fi.vbr && fi.channels == 2,
              "code 1 accounting");
        check(fi.samplesAt48k == 1920, "code 1 samples (2x20ms)");
        c1[1] = 200;  // size byte > payload
        check(OpusPacketParser::parse(c1.data(), c1.size(), &fi) ==
                  PS::kBadFrameLength,
              "code 1 oversize split rejected");

        // Code 2: two CBR frames; odd payload rejected.
        std::vector<uint8_t> c2(1 + 21, 0);
        c2[0] = (31u << 3) | 2u;
        check(OpusPacketParser::parse(c2.data(), c2.size(), &fi) ==
                  PS::kBadFrameLength,
              "code 2 odd payload rejected");
        c2.push_back(0);
        check(OpusPacketParser::parse(c2.data(), c2.size(), &fi) == PS::kOk,
              "code 2 even payload parses");
        check(fi.frameCount == 2 && fi.largestFrameBytes == 11,
              "code 2 halves");

        // Code 3 CBR: 3 frames of 10 bytes.
        std::vector<uint8_t> c3;
        c3.push_back((31u << 3) | 3u);
        c3.push_back(3);  // N=3, no V/P
        c3.insert(c3.end(), 30, 0x77);
        check(OpusPacketParser::parse(c3.data(), c3.size(), &fi) == PS::kOk,
              "code 3 CBR parses");
        check(fi.frameCount == 3 && !fi.vbr && fi.largestFrameBytes == 10,
              "code 3 CBR accounting");

        // Code 3 padding overrun.
        std::vector<uint8_t> pad;
        pad.push_back((31u << 3) | 3u);
        pad.push_back(3 | 0x40);  // P=1
        pad.push_back(100);       // padding claims 100 bytes
        pad.insert(pad.end(), 10, 0);
        check(OpusPacketParser::parse(pad.data(), pad.size(), &fi) ==
                  PS::kBadFrameLength,
              "padding overrun rejected");

        // Code 3 VBR with a 255-extension size run: [255,1] == 255.
        std::vector<uint8_t> vbr;
        vbr.push_back((31u << 3) | 3u);
        vbr.push_back(3 | 0x80);  // V=1
        vbr.push_back(255);
        vbr.push_back(1);  // size0 = 254 + 1 = 255
        vbr.push_back(2);  // size1 = 2
        vbr.insert(vbr.end(), 255 + 2 + 7, 0);  // + last frame 7
        check(OpusPacketParser::parse(vbr.data(), vbr.size(), &fi) == PS::kOk,
              "code 3 VBR with 255-run parses");
        check(fi.vbr && fi.frameCount == 3 && fi.largestFrameBytes == 255,
              "255-run size accounting");

        // Bad frame counts (0, 49).
        std::vector<uint8_t> z(10, 0);
        z[0] = (31u << 3) | 3u;
        z[1] = 0 | 0x80;
        check(OpusPacketParser::parse(z.data(), z.size(), &fi) ==
                  PS::kBadHeader,
              "code 3 count 0 rejected");
        z[1] = 49;
        check(OpusPacketParser::parse(z.data(), z.size(), &fi) ==
                  PS::kBadHeader,
              "code 3 count 49 rejected");

        // Config table sanity across all 32 configs.
        bool tableOk = true;
        for (int cfg = 0; cfg < 32; ++cfg) {
            const float ms = OpusPacketParser::configFrameSizeMs(cfg);
            const int32_t s = OpusPacketParser::configSamplesAt48k(cfg);
            if (ms <= 0.0f || s <= 0) tableOk = false;
            if (std::fabs(ms * 48.0f - static_cast<float>(s)) > 0.001f) {
                tableOk = false;
            }
        }
        check(tableOk, "config duration table consistent (0..31)");
        check(OpusPacketParser::configFrameSizeMs(32) == 0.0f &&
                  OpusPacketParser::configSamplesAt48k(-1) == 0,
              "out-of-range configs -> 0");
    }

    // ------------------------------------------------------------------
    // B. AdtsFrameParser
    // ------------------------------------------------------------------
    std::printf("  [adts] header ladder + strict rates\n");
    {
        AdtsFrameInfo fi;
        auto f = makeAdts(4, 2, 200);  // 44.1 kHz stereo LC
        check(AdtsFrameParser::parse(f.data(), f.size(), true, &fi) ==
                  PS::kOk,
              "valid 44.1 kHz frame parses");
        check(fi.sampleRateHz == 44100 && fi.channels == 2 && fi.profile == 1,
              "44.1 kHz attributes");
        check(fi.frameLength == 207 && fi.payloadBytes == 200 && !fi.hasCrc,
              "frame length accounting");
        check(fi.sampleRateIndex == 4 && fi.pcmBitDepth == 16,
              "rate index + bit depth");
        // 48 kHz.
        auto f48 = makeAdts(3, 2, 150);
        check(AdtsFrameParser::parse(f48.data(), f48.size(), true, &fi) ==
                  PS::kOk,
              "valid 48 kHz frame parses (strict)");
        check(fi.sampleRateHz == 48000, "48 kHz resolved");
        // Rejection ladder.
        auto bad = f;
        bad[0] = 0xFE;
        check(AdtsFrameParser::parse(bad.data(), bad.size(), true, &fi) ==
                  PS::kBadSync,
              "broken syncword rejected");
        bad = f;
        bad[1] = 0xF3;  // layer != 0
        check(AdtsFrameParser::parse(bad.data(), bad.size(), true, &fi) ==
                  PS::kBadHeader,
              "layer != 0 rejected");
        bad = f;
        bad[2] = static_cast<uint8_t>((1 << 6) | (13 << 2));  // reserved rate
        check(AdtsFrameParser::parse(bad.data(), bad.size(), true, &fi) ==
                  PS::kBadHeader,
              "reserved rate index rejected");
        bad = f;
        bad[2] = static_cast<uint8_t>((1 << 6) | (7 << 2));  // 22.05 kHz
        check(AdtsFrameParser::parse(bad.data(), bad.size(), true, &fi) ==
                  PS::kUnsupportedRate,
              "off-ladder rate rejected (strict)");
        check(AdtsFrameParser::parse(bad.data(), bad.size(), false, &fi) ==
                  PS::kOk,
              "off-ladder rate accepted (lenient)");
        // Truncation ladder.
        auto t = makeAdts(4, 2, 200, 500);  // declares 500 bytes
        check(AdtsFrameParser::parse(t.data(), t.size(), true, &fi) ==
                  PS::kTruncated,
              "declared > available -> truncated");
        check(AdtsFrameParser::parse(t.data(), 6, true, &fi) == PS::kTooSmall,
              "short header -> too small");
        // Self-inconsistent length (< header).
        auto tiny = makeAdts(4, 2, 10, 5);
        check(AdtsFrameParser::parse(tiny.data(), tiny.size(), true, &fi) ==
                  PS::kBadHeader,
              "length < header rejected");
        // CRC-protected frames parse (hasCrc, 9-byte header).
        auto crc = makeAdts(4, 2, 100, -1, true);
        check(AdtsFrameParser::parse(crc.data(), crc.size(), true, &fi) ==
                  PS::kOk,
              "CRC frame parses");
        check(fi.hasCrc && fi.headerBytes == 9, "CRC accounting");
        // Normalized header writer: header + payload round-trips through
        // the parser (a bare 7-byte header would be kTruncated — the
        // declared length covers the payload too).
        uint8_t frame[207];
        AdtsFrameParser::writeNormalizedHeader(frame, 1, 4, 2, 207);
        std::memset(frame + 7, 0x5A, 200);
        AdtsFrameInfo norm;
        check(AdtsFrameParser::parse(frame, sizeof(frame), true, &norm) ==
                  PS::kOk,
              "normalized header re-parses");
        check(norm.frameLength == 207 && norm.channels == 2 &&
                  norm.profile == 1,
              "normalized header fields");
        check(norm.bufferFullness == 0x7FF, "normalized fullness is VBR-legal");
        // Resync.
        std::vector<uint8_t> stream(64, 0);
        stream.insert(stream.end(), f.begin(), f.end());
        check(AdtsFrameParser::resync(stream.data(), stream.size(), 0) == 64,
              "resync skips garbage to the frame");
        check(AdtsFrameParser::resync(stream.data(), 64, 0) == -1,
              "resync finds nothing in garbage");
    }

    // ------------------------------------------------------------------
    // C. AudioFrameRemuxer — Ogg Opus
    // ------------------------------------------------------------------
    std::printf("  [remux] Ogg Opus structural audit\n");
    {
        VecSink vs;
        AudioFrameRemuxer r;
        RemuxerConfig cfg;  // Opus defaults, channels learned from TOC
        r.begin(cfg, RemuxSink{&vs, vecWrite});
        // 10 stereo 20 ms packets + 1 mono 10 ms packet.
        for (int i = 0; i < 10; ++i) {
            auto p = makeOpus(50);
            check(r.remuxPacket(p.data(), p.size(), static_cast<uint64_t>(i)) ==
                      PS::kOk,
                  "opus packet accepted");
        }
        auto mono = makeOpus(2, false, 0);  // config 0: SILK NB 10 ms mono
        check(r.remuxPacket(mono.data(), mono.size(), 10) == PS::kOk,
              "mono packet accepted");
        // Out-of-order + duplicate + corrupt drops.
        auto p = makeOpus(50);
        check(r.remuxPacket(p.data(), p.size(), 9) == PS::kOutOfOrder,
              "duplicate sequence dropped");
        check(r.remuxPacket(p.data(), p.size(), 4) == PS::kOutOfOrder,
              "regressed sequence dropped");
        auto over = makeOpus(1300);
        check(r.remuxPacket(over.data(), over.size(), 11) ==
                  PS::kBadFrameLength,
              "oversize frame dropped");
        check(r.remuxPacket(nullptr, 16, 12) == PS::kInvalidArgument,
              "null packet dropped");
        check(r.finish() == PS::kOk, "finish");
        check(r.remuxPacket(mono.data(), mono.size(), 13) == PS::kWrongState,
              "post-finish packet rejected");

        const auto& st = r.stats();
        check(st.packetsIn == 15 && st.packetsAccepted == 11,
              "stats: packets in/accepted");
        check(st.droppedOutOfOrder == 2 && st.droppedCorrupt == 2,
              "stats: drops classified (2 ooo + oversize + null)");
        check(st.packetsIn == st.packetsAccepted + st.droppedCorrupt +
                                  st.droppedTruncated +
                                  st.droppedOutOfOrder,
              "stats conservation");

        // Page-by-page Ogg audit.
        const std::vector<uint8_t>& out = vs.out;
        size_t pos = 0;
        int pages = 0;
        uint64_t granule = 0;
        bool sawBos = false, sawEos = false;
        uint32_t seqExpect = 0;
        while (pos < out.size()) {
            check(out.size() - pos >= 27, "page header fits");
            check(std::memcmp(&out[pos], "OggS", 4) == 0, "page magic");
            check(out[pos + 4] == 0, "version 0");
            const uint8_t ht = out[pos + 5];
            const uint64_t gr = rdLe(&out[pos + 6], 8);
            const uint32_t serial = static_cast<uint32_t>(rdLe(&out[pos + 14], 4));
            const uint32_t pseq = static_cast<uint32_t>(rdLe(&out[pos + 18], 4));
            const uint32_t crcField =
                static_cast<uint32_t>(rdLe(&out[pos + 22], 4));
            const int nseg = out[pos + 26];
            check(serial == cfg.oggSerial, "serial");
            check(pseq == seqExpect++, "page sequence contiguous");
            if (ht & 0x02) sawBos = true;
            if (ht & 0x04) sawEos = true;
            check((ht & 0x01) == 0, "no continued pages");
            size_t bodyLen = 0;
            for (int i = 0; i < nseg; ++i) {
                bodyLen += out[pos + 27 + i];
            }
            const size_t pageLen = 27 + static_cast<size_t>(nseg) + bodyLen;
            check(pos + pageLen <= out.size(), "page fits in output");
            std::vector<uint8_t> tmp(out.begin() + static_cast<long>(pos),
                                     out.begin() + static_cast<long>(pos + pageLen));
            std::memset(&tmp[22], 0, 4);
            check(AudioFrameRemuxer::oggCrc32(tmp.data(), tmp.size()) ==
                      crcField,
                  "page CRC valid");
            check(gr >= granule, "granule monotonic");
            granule = gr;
            pos += pageLen;
            ++pages;
        }
        check(sawBos && sawEos, "BOS + EOS flags present");
        check(pages == static_cast<int>(st.pagesEmitted), "page count matches");
        check(granule == 10 * 960 + 480, "granule = 48 kHz sample total");
        // OpusHead + OpusTags placement.
        check(std::memcmp(&out[27 + 1], "OpusHead", 8) == 0, "OpusHead magic");
        check(out[27 + 1 + 8] == 1 && out[27 + 1 + 9] == 2,
              "OpusHead v1 + learned stereo");
        check(rdLe(&out[27 + 1 + 12], 4) == 48000, "OpusHead input rate");
        const size_t page1Body = 47 + 27 + 1;  // page0 = 27+1+19
        check(std::memcmp(&out[page1Body], "OpusTags", 8) == 0,
              "OpusTags magic");
        // CRC table == bitwise reference on pseudo-random data.
        std::vector<uint8_t> blob(513);
        Rng rng(0xA5A5);
        for (auto& b : blob) b = rng.byte();
        check(AudioFrameRemuxer::oggCrc32(blob.data(), blob.size()) ==
                  AudioFrameRemuxer::oggCrc32Bitwise(blob.data(), blob.size()),
              "CRC table == bitwise reference");
    }

    std::printf("  [remux] ADTS normalization + lifecycle\n");
    {
        VecSink vs;
        AudioFrameRemuxer r;
        RemuxerConfig cfg;
        cfg.codec = Codec::kAacAdts;
        r.begin(cfg, RemuxSink{&vs, vecWrite});
        auto f1 = makeAdts(4, 2, 100);
        auto f2 = makeAdts(3, 2, 120);  // 48 kHz also on-ladder
        auto badRate = makeAdts(7, 2, 50);  // 22.05 kHz
        auto truncated = makeAdts(4, 2, 200, 9999);
        check(r.remuxPacket(f1.data(), f1.size(), 0) == PS::kOk, "adts 1");
        check(r.remuxPacket(badRate.data(), badRate.size(), 1) ==
                  PS::kUnsupportedRate,
              "off-ladder adts dropped");
        check(r.remuxPacket(truncated.data(), truncated.size(), 2) ==
                  PS::kTruncated,
              "truncated adts dropped");
        check(r.remuxPacket(f2.data(), f2.size(), 3) == PS::kOk, "adts 2");
        check(r.remuxPacket(f1.data(), f1.size(), 2) == PS::kOutOfOrder,
              "out-of-order adts dropped");
        // CRC frames pass through byte-exact.
        auto crcF = makeAdts(4, 2, 60, -1, true);
        check(r.remuxPacket(crcF.data(), crcF.size(), 4) == PS::kOk,
              "CRC adts accepted");
        check(r.finish() == PS::kOk, "adts finish");

        const auto& st = r.stats();
        check(st.framesEmitted == 3 && st.packetsAccepted == 3,
              "adts stats accepted");
        check(st.droppedCorrupt == 1 && st.droppedTruncated == 1 &&
                  st.droppedOutOfOrder == 1,
              "adts drop classification");
        // Layout: [hdr 100][hdr 120][crc-frame verbatim].
        const std::vector<uint8_t>& out = vs.out;
        check(out.size() == 7 + 100 + 7 + 120 + crcF.size(),
              "adts output byte layout");
        int len0 = ((out[3] & 3) << 11) | (out[4] << 3) | (out[5] >> 5);
        check(len0 == 107, "normalized length corrected");
        int full0 = ((out[5] & 0x1F) << 6) | (out[6] >> 2);
        check(full0 == 0x7FF, "normalized fullness");
        check(std::memcmp(&out[7], &f1[7], 100) == 0, "payload byte-exact");
        const size_t crcAt = 7 + 100 + 7 + 120;
        check(std::memcmp(&out[crcAt], crcF.data(), crcF.size()) == 0,
              "CRC frame passed through verbatim");
    }

    std::printf("  [remux] degenerate streams + io failure\n");
    {
        // Zero valid packets: still a structurally valid Ogg file.
        VecSink vs;
        AudioFrameRemuxer r;
        r.begin(RemuxerConfig{}, RemuxSink{&vs, vecWrite});
        auto garbage = makeOpus(1300);
        for (int i = 0; i < 3; ++i) {
            check(r.remuxPacket(garbage.data(), garbage.size(),
                                static_cast<uint64_t>(i)) ==
                      PS::kBadFrameLength,
                  "garbage packets dropped");
        }
        check(r.finish() == PS::kOk, "degenerate finish");
        check(vs.out.size() >= 27 * 3, "headers + EOS still emitted");
        check(std::memcmp(&vs.out[0], "OggS", 4) == 0, "degenerate BOS magic");
        check(r.stats().packetsAccepted == 0, "nothing accepted");

        // Sink failure -> kIoError, counted, never a crash.
        FixedSink fs;
        fs.failNext = true;
        AudioFrameRemuxer r2;
        r2.begin(RemuxerConfig{}, RemuxSink{&fs, fixedWrite});
        auto p = makeOpus(50);
        check(r2.remuxPacket(p.data(), p.size(), 0) == PS::kIoError,
              "sink failure surfaces as kIoError");
        check(r2.stats().packetsIn == 1 && r2.stats().packetsAccepted == 0,
              "io failure counted");
        check(r2.stats().packetsIn == r2.stats().packetsAccepted +
                                          r2.stats().droppedCorrupt +
                                          r2.stats().droppedTruncated +
                                          r2.stats().droppedOutOfOrder,
              "stats conservation under io failure");
        // Wrong-state misuse.
        AudioFrameRemuxer r3;
        check(r3.remuxPacket(p.data(), p.size(), 0) == PS::kWrongState,
              "remux before begin rejected");
        check(r3.finish() == PS::kWrongState, "finish before begin rejected");
    }

    std::printf("  [remux] randomized corrupt-stream soak (500 packets)\n");
    {
        // Deterministic fuzz-lite: random packet bodies, random lengths,
        // random sequence regressions — no crash, stats always conserve.
        Rng rng(0xDEADBEEF);
        for (int round = 0; round < 20; ++round) {
            VecSink vs;
            AudioFrameRemuxer r;
            RemuxerConfig cfg;
            cfg.codec = (round & 1) ? Codec::kAacAdts : Codec::kOpus;
            r.begin(cfg, RemuxSink{&vs, vecWrite});
            for (int i = 0; i < 25; ++i) {
                std::vector<uint8_t> pkt(1 + (rng.next() % 300));
                for (auto& b : pkt) b = rng.byte();
                if ((rng.next() & 7) == 0 && i > 0) {
                    (void)r.remuxPacket(pkt.data(), pkt.size(),
                                        rng.next() % static_cast<uint64_t>(i));
                } else {
                    (void)r.remuxPacket(pkt.data(), pkt.size(),
                                        static_cast<uint64_t>(i));
                }
            }
            (void)r.finish();
            const auto& st = r.stats();
            check(st.packetsIn == st.packetsAccepted + st.droppedCorrupt +
                                      st.droppedTruncated +
                                      st.droppedOutOfOrder,
                  "soak: stats conservation");
            // Output is always valid Ogg/ADTS prefix-wise.
            if (!vs.out.empty() && cfg.codec == Codec::kOpus) {
                check(std::memcmp(&vs.out[0], "OggS", 4) == 0,
                      "soak: opus output starts with OggS");
            }
        }
    }

    std::printf("  [remux] zero-allocation per-packet path\n");
    {
        FixedSink fs;
        AudioFrameRemuxer r;
        r.begin(RemuxerConfig{}, RemuxSink{&fs, fixedWrite});
        auto p = makeOpus(50);
        r.remuxPacket(p.data(), p.size(), 0);  // warm up (headers emitted)
        {
            streamify_test::AllocGuard guard;
            for (int i = 1; i < 50; ++i) {
                (void)r.remuxPacket(p.data(), p.size(),
                                    static_cast<uint64_t>(i));
            }
            check(guard.count() == 0, "50 packets, zero allocations");
        }
        (void)r.finish();
    }

    std::printf("[phase3] audio remuxer: %d passed, %d failed\n", g_passed,
                g_failed);
    return g_failed == 0 ? 0 : 1;
}
