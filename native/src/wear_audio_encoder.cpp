// ============================================================================
//  wear_audio_encoder.cpp — Phase-4 companion WearOS audio frame packetizer
//  & rate-limiting compressor (native/src/wear_audio_encoder.cpp)
// ============================================================================
//
//  Implementation notes
//  --------------------
//  * The CRC32 table is generated at compile time (constexpr) — no .rodata
//    cost beyond the 1 KiB table and no runtime init order concerns.
//  * PackFrames performs zero heap allocation per packet: the compression
//    scratch is posix_memalign'ed once at construction (NeonCompat mandate).
//  * Every parse path is hostile-input safe: bounds are checked before any
//    field is trusted, and the RLE decoder can never write past `cap`.
// ============================================================================

#include "../include/wear_audio_encoder.h"

#include <array>
#include <chrono>
#include <cstring>

#include "../util/NeonCompat.h"

namespace streamify {
namespace wear {

// ---- little-endian helpers ---------------------------------------------------

namespace {

inline void PutLE16(uint8_t* p, uint16_t v) {
    p[0] = static_cast<uint8_t>(v & 0xFFu);
    p[1] = static_cast<uint8_t>(v >> 8);
}

inline uint16_t GetLE16(const uint8_t* p) {
    return static_cast<uint16_t>(p[0] | (p[1] << 8));
}

inline void PutLE32(uint8_t* p, uint32_t v) {
    p[0] = static_cast<uint8_t>(v & 0xFFu);
    p[1] = static_cast<uint8_t>((v >> 8) & 0xFFu);
    p[2] = static_cast<uint8_t>((v >> 16) & 0xFFu);
    p[3] = static_cast<uint8_t>((v >> 24) & 0xFFu);
}

inline uint32_t GetLE32(const uint8_t* p) {
    return static_cast<uint32_t>(p[0]) | (static_cast<uint32_t>(p[1]) << 8) |
           (static_cast<uint32_t>(p[2]) << 16) |
           (static_cast<uint32_t>(p[3]) << 24);
}

constexpr std::array<uint32_t, 256> MakeCrcTable() {
    std::array<uint32_t, 256> t{};
    for (uint32_t i = 0; i < 256; ++i) {
        uint32_t c = i;
        for (int k = 0; k < 8; ++k) {
            c = (c & 1u) ? (0xEDB88320u ^ (c >> 1)) : (c >> 1);
        }
        t[i] = c;
    }
    return t;
}

constexpr std::array<uint32_t, 256> kCrcTable = MakeCrcTable();

}  // namespace

uint32_t Crc32(const uint8_t* data, size_t length) {
    if (data == nullptr) length = 0;
    uint32_t c = 0xFFFFFFFFu;
    for (size_t i = 0; i < length; ++i) {
        c = kCrcTable[(c ^ data[i]) & 0xFFu] ^ (c >> 8);
    }
    return c ^ 0xFFFFFFFFu;
}

// ---- rate codes ---------------------------------------------------------------

uint8_t SampleRateToCode(uint32_t hz) {
    switch (hz) {
        case 8000:  return 0;
        case 16000: return 1;
        case 24000: return 2;
        case 44100: return 3;
        case 48000: return 4;
        default:    return 0xFF;
    }
}

uint32_t SampleRateFromCode(uint8_t code) {
    switch (code) {
        case 0: return 8000;
        case 1: return 16000;
        case 2: return 24000;
        case 3: return 44100;
        case 4: return 48000;
        default: return 0;
    }
}

// ---- zero-run RLE codec ---------------------------------------------------------

// Token grammar (byte-oriented):
//   [0x01..0xFE] literal count L (1..254) followed by L literal bytes
//   [0xFF, R]    run of R (1..255) zero bytes
// Literal counts stop at 254 so a literal control byte can never collide
// with the 0xFF run marker.
size_t WearCompressMaxOutput(size_t raw_bytes) {
    return raw_bytes * 2 + 8;
}

size_t WearCompressPayload(const uint8_t* in, size_t n, uint8_t* out,
                           size_t cap) {
    if (in == nullptr || out == nullptr || n == 0) return kWearCodecBadSize;
    size_t o = 0;
    size_t i = 0;
    while (i < n) {
        if (in[i] == 0) {
            size_t run = 1;
            while (i + run < n && in[i + run] == 0 && run < 255) ++run;
            if (o + 2 > cap) return kWearCodecBadSize;
            out[o++] = 0xFF;
            out[o++] = static_cast<uint8_t>(run);
            i += run;
        } else {
            const size_t start = i;
            while (i < n && (i - start) < 254 && in[i] != 0) ++i;
            const size_t lit = i - start;
            if (o + 1 + lit > cap) return kWearCodecBadSize;
            out[o++] = static_cast<uint8_t>(lit);
            std::memcpy(out + o, in + start, lit);
            o += lit;
        }
    }
    return o;
}

size_t WearDecompressPayload(const uint8_t* in, size_t n, uint8_t* out,
                             size_t cap) {
    if (in == nullptr || out == nullptr || n == 0) return kWearCodecBadSize;
    size_t i = 0;
    size_t o = 0;
    while (i < n) {
        const uint8_t c = in[i++];
        if (c == 0xFF) {
            if (i >= n) return kWearCodecBadSize;  // truncated run token
            const size_t run = in[i++];
            if (run == 0) return kWearCodecBadSize;
            if (o + run > cap) return kWearCodecBadSize;
            std::memset(out + o, 0, run);
            o += run;
        } else {
            const size_t lit = c;  // 1..255
            if (i + lit > n) return kWearCodecBadSize;   // truncated literal
            if (o + lit > cap) return kWearCodecBadSize;
            std::memcpy(out + o, in + i, lit);
            o += lit;
            i += lit;
        }
    }
    return o;
}

// ---- RateLimiter ----------------------------------------------------------------

RateLimiter::RateLimiter(uint32_t bytes_per_second, uint32_t burst_bytes) {
    configure(bytes_per_second, burst_bytes);
}

void RateLimiter::configure(uint32_t bytes_per_second, uint32_t burst_bytes) {
    rate_ = bytes_per_second;
    burst_ = burst_bytes;
    if (rate_ > 0 && burst_ == 0) burst_ = rate_;  // >= 1s of budget
    reset();
}

void RateLimiter::reset() {
    tokens_ = 0.0;
    last_ms_ = 0.0;
    started_ = false;
}

void RateLimiter::refill(double now_ms) {
    if (rate_ == 0) return;
    if (!started_) {
        tokens_ = static_cast<double>(burst_);
        last_ms_ = now_ms;
        started_ = true;
        return;
    }
    if (now_ms <= last_ms_) {
        // Monotonic-clock contract: a regress neither mints tokens nor
        // rewinds the clock (a rewind would double-count the next span).
        return;
    }
    const double dt = now_ms - last_ms_;
    last_ms_ = now_ms;
    tokens_ += dt * static_cast<double>(rate_) / 1000.0;
    if (tokens_ > static_cast<double>(burst_)) {
        tokens_ = static_cast<double>(burst_);
    }
}

bool RateLimiter::tryAcquireAt(size_t bytes, double now_ms) {
    if (rate_ == 0 || bytes == 0) return true;  // unlimited
    refill(now_ms);
    if (static_cast<double>(bytes) > tokens_) return false;
    tokens_ -= static_cast<double>(bytes);
    return true;
}

bool RateLimiter::tryAcquire(size_t bytes) {
    if (rate_ == 0 || bytes == 0) return true;
    const auto now = std::chrono::steady_clock::now();
    const double now_ms =
        std::chrono::duration<double, std::milli>(now.time_since_epoch())
            .count();
    return tryAcquireAt(bytes, now_ms);
}

// ---- FramePacketizer ---------------------------------------------------------------

bool FramePacketizer::ValidateConfig(const Config& c) {
    if (SampleRateToCode(c.sample_rate) == 0xFF) return false;
    const size_t frame_bytes = (c.stereo ? 2u : 1u) * 2u;
    if (c.mtu_bytes < kWearHeaderSize + kWearCrcSize + frame_bytes) {
        return false;  // MTU must carry at least one whole frame
    }
    if (c.mtu_bytes > kWearMaxWireSize) return false;
    return true;
}

FramePacketizer::FramePacketizer(const Config& cfg) {
    if (!ValidateConfig(cfg)) return;  // stays invalid; Parse still usable
    cfg_ = cfg;
    rate_code_ = SampleRateToCode(cfg.sample_rate);
    size_t max_payload = cfg.mtu_bytes - kWearHeaderSize - kWearCrcSize;
    if (max_payload > kWearMaxPayload) max_payload = kWearMaxPayload;
    max_payload_ = max_payload;

    uint32_t burst = cfg.burst_bytes != 0 ? cfg.burst_bytes : 2u * cfg.mtu_bytes;
    if (burst < cfg.mtu_bytes) burst = cfg.mtu_bytes;  // a full packet always fits
    limiter_.configure(cfg.rate_budget_bytes_per_sec, burst);

    scratch_ = static_cast<uint8_t*>(
        streamify::alignedAlloc(64, WearCompressMaxOutput(kWearMaxPayload)));
}

FramePacketizer::~FramePacketizer() {
    if (scratch_ != nullptr) {
        streamify::alignedFree(scratch_);
        scratch_ = nullptr;
    }
}

void FramePacketizer::reset() {
    seq_ = 0;
    limiter_.reset();
    emitted_.store(0, std::memory_order_relaxed);
    throttled_.store(0, std::memory_order_relaxed);
    frames_packed_.store(0, std::memory_order_relaxed);
    payload_bytes_.store(0, std::memory_order_relaxed);
    wire_bytes_.store(0, std::memory_order_relaxed);
    crc_failures_.store(0, std::memory_order_relaxed);
    parse_errors_.store(0, std::memory_order_relaxed);
}

size_t FramePacketizer::PackFrames(const int16_t* pcm, size_t frames,
                                   uint8_t* out, size_t out_capacity,
                                   size_t* packets_out,
                                   size_t* frames_packed) {
    if (packets_out != nullptr) *packets_out = 0;
    if (frames_packed != nullptr) *frames_packed = 0;
    if (scratch_ == nullptr || pcm == nullptr || out == nullptr ||
        frames == 0 || out_capacity < kWearMinWireSize) {
        return 0;
    }
    const size_t frame_bytes = (cfg_.stereo ? 2u : 1u) * 2u;
    const size_t max_frames = max_payload_ / frame_bytes;  // >= 1 (validated)

    size_t used = 0;
    size_t packets = 0;
    size_t done = 0;
    while (done < frames) {
        size_t chunk = frames - done;
        if (chunk > max_frames) chunk = max_frames;
        const size_t raw_bytes = chunk * frame_bytes;
        const uint8_t* raw =
            reinterpret_cast<const uint8_t*>(pcm) + done * frame_bytes;

        const uint8_t* payload = raw;
        size_t payload_bytes = raw_bytes;
        bool compressed = false;
        if (cfg_.enable_compression) {
            const size_t c = WearCompressPayload(
                raw, raw_bytes, scratch_, WearCompressMaxOutput(kWearMaxPayload));
            // Keep compression only when it saves real bytes (still whole
            // frames — RLE is byte-oriented but frame-aligned by chunking).
            if (c != kWearCodecBadSize && c + 16 < raw_bytes) {
                payload = scratch_;
                payload_bytes = c;
                compressed = true;
            }
        }

        const size_t wire = kWearHeaderSize + payload_bytes + kWearCrcSize;
        if (used + wire > out_capacity) break;  // caller retries the rest
        if (!limiter_.tryAcquire(wire)) {
            // Pacing backpressure: Bluetooth controller queues on the watch
            // would overflow — defer the remainder to the next window.
            throttled_.fetch_add(1, std::memory_order_relaxed);
            break;
        }

        uint8_t* p = out + used;
        p[0] = static_cast<uint8_t>(kWearPacketMagic & 0xFFu);
        p[1] = static_cast<uint8_t>(kWearPacketMagic >> 8);
        p[2] = static_cast<uint8_t>(seq_ & 0xFFu);
        p[3] = static_cast<uint8_t>(seq_ >> 8);
        seq_ = static_cast<uint16_t>(seq_ + 1);  // wraps mod 65536 (defined)
        p[4] = rate_code_;
        uint8_t flags = kWearFlagCrc;
        if (cfg_.stereo) flags |= kWearFlagStereo;
        if (compressed) flags |= kWearFlagCompressed;
        if (done + chunk < frames) flags |= kWearFlagContinuation;
        p[5] = flags;
        PutLE16(p + 6, static_cast<uint16_t>(payload_bytes));
        std::memcpy(p + kWearHeaderSize, payload, payload_bytes);
        PutLE32(p + kWearHeaderSize + payload_bytes, Crc32(payload, payload_bytes));

        used += wire;
        ++packets;
        done += chunk;
        payload_bytes_.fetch_add(raw_bytes, std::memory_order_relaxed);
    }
    emitted_.fetch_add(packets, std::memory_order_relaxed);
    wire_bytes_.fetch_add(used, std::memory_order_relaxed);
    frames_packed_.fetch_add(done, std::memory_order_relaxed);
    if (packets_out != nullptr) *packets_out = packets;
    if (frames_packed != nullptr) *frames_packed = done;
    return used;
}

FramePacketizer::ParseResult FramePacketizer::Parse(
    const uint8_t* packet, size_t size, ParsedPacket* info, uint8_t* decoded,
    size_t decoded_capacity) {
    if (packet == nullptr) {
        parse_errors_.fetch_add(1, std::memory_order_relaxed);
        return ParseResult::kNullArgs;
    }
    if (size < kWearHeaderSize + kWearCrcSize) {
        parse_errors_.fetch_add(1, std::memory_order_relaxed);
        return ParseResult::kTooShort;
    }
    if (packet[0] != static_cast<uint8_t>(kWearPacketMagic & 0xFFu) ||
        packet[1] != static_cast<uint8_t>(kWearPacketMagic >> 8)) {
        parse_errors_.fetch_add(1, std::memory_order_relaxed);
        return ParseResult::kBadMagic;
    }
    const uint16_t payload_size = GetLE16(packet + 6);
    if (payload_size > kWearMaxPayload) {
        parse_errors_.fetch_add(1, std::memory_order_relaxed);
        return ParseResult::kBadSize;
    }
    const size_t wire = kWearHeaderSize + payload_size + kWearCrcSize;
    if (size < wire) {
        parse_errors_.fetch_add(1, std::memory_order_relaxed);
        return ParseResult::kBadSize;
    }
    const uint8_t flags = packet[5];
    if ((flags & ~kWearKnownFlagMask) != 0 ||
        (flags & kWearFlagCrc) == 0) {
        parse_errors_.fetch_add(1, std::memory_order_relaxed);
        return ParseResult::kBadFlags;
    }
    const uint32_t rate = SampleRateFromCode(packet[4]);
    if (rate == 0) {
        parse_errors_.fetch_add(1, std::memory_order_relaxed);
        return ParseResult::kBadRateCode;
    }
    const uint8_t* payload = packet + kWearHeaderSize;
    if (GetLE32(packet + kWearHeaderSize + payload_size) !=
        Crc32(payload, payload_size)) {
        crc_failures_.fetch_add(1, std::memory_order_relaxed);
        return ParseResult::kCrcMismatch;
    }

    const bool compressed = (flags & kWearFlagCompressed) != 0;
    size_t decoded_bytes = payload_size;
    if (compressed) {
        if (decoded == nullptr) {
            parse_errors_.fetch_add(1, std::memory_order_relaxed);
            return ParseResult::kNullArgs;
        }
        decoded_bytes = WearDecompressPayload(payload, payload_size, decoded,
                                              decoded_capacity);
        if (decoded_bytes == kWearCodecBadSize) {
            parse_errors_.fetch_add(1, std::memory_order_relaxed);
            return ParseResult::kDecodeOverflow;
        }
    } else if (decoded != nullptr) {
        if (decoded_capacity < payload_size) {
            parse_errors_.fetch_add(1, std::memory_order_relaxed);
            return ParseResult::kDecodeOverflow;
        }
        if (payload_size > 0) {
            std::memcpy(decoded, payload, payload_size);
        }
    }

    if (info != nullptr) {
        info->sequence = GetLE16(packet + 2);
        info->sample_rate = rate;
        info->stereo = (flags & kWearFlagStereo) != 0;
        info->compressed = compressed;
        info->continuation = (flags & kWearFlagContinuation) != 0;
        info->payload_bytes = decoded_bytes;
        info->wire_bytes = wire;
    }
    return ParseResult::kOk;
}

void FramePacketizer::SnapshotTelemetry(WearTelemetry* out) const {
    if (out == nullptr) return;
    out->packets_emitted = emitted_.load(std::memory_order_relaxed);
    out->packets_throttled = throttled_.load(std::memory_order_relaxed);
    out->frames_packed = frames_packed_.load(std::memory_order_relaxed);
    out->bytes_payload = payload_bytes_.load(std::memory_order_relaxed);
    out->bytes_wire = wire_bytes_.load(std::memory_order_relaxed);
    out->crc_failures = crc_failures_.load(std::memory_order_relaxed);
    out->parse_errors = parse_errors_.load(std::memory_order_relaxed);
}

}  // namespace wear
}  // namespace streamify
