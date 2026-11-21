#ifndef STREAMIFY_WEAR_AUDIO_ENCODER_H
#define STREAMIFY_WEAR_AUDIO_ENCODER_H
// ============================================================================
//  wear_audio_encoder.h — Phase-4 companion WearOS audio frame packetizer
//  & rate-limiting compressor (native/include/wear_audio_encoder.h)
// ============================================================================
//
//  Deliverable 2 of the Phase-4 directive: chunk raw or encoded audio into
//  compact, framing-aligned packets sized for low-power Bluetooth Low Energy
//  and Wi-Fi Direct MTUs (512 B – 4 KB frames), with integrity framing and
//  pacing that never overflows a watch's Bluetooth controller queues.
//
//  WIRE FORMAT (all integers little-endian)
//  ----------------------------------------
//      [0..1]  Magic          0x5A 0xF1 ("wear stream" tag)
//      [2..3]  FrameSeq       wrapping uint16 sequence number
//      [4]     SampleRateEnum 0=8k 1=16k 2=24k 3=44.1k 4=48k
//      [5]     Flags          bit0 stereo, bit1 CRC32 present (always set),
//                              bit2 payload zero-run compressed, bit3 more
//                              packets follow for this audio burst
//      [6..7]  PayloadSize    uint16 wire payload bytes (post-compression)
//      [8..N]  Payload        PCM16 bytes (possibly RLE-compressed)
//      [N..N+3] CRC32         IEEE reflected (poly 0xEDB88320) of the payload
//
//  Total packet size = 12 + PayloadSize, capped by the configured MTU.
//
//  PARSING SAFETY
//  --------------
//  Parse() accepts fully hostile input (the fuzz harness feeds it truncated
//  headers, corrupted magic/flags/sizes and CRC-randomized payloads): every
//  field is bounds-checked before use, the decompressor is strictly
//  bounds-checked, and no code path can read or write out of range or
//  recurse. Unknown flag bits are rejected (kBadFlags) so the wire format
//  can evolve safely.
//
//  THREADING: PackFrames drives the sender side, Parse the receiver side;
//  both may run on different threads. The telemetry counters are relaxed
//  atomics for cross-thread observability; the sequence cursor and rate
//  limiter state are sender-thread owned.
// ============================================================================

#include <atomic>
#include <cstddef>
#include <cstdint>

namespace streamify {
namespace wear {

// ---- wire constants ---------------------------------------------------------
inline constexpr uint16_t kWearPacketMagic = 0x5AF1u;
inline constexpr size_t kWearHeaderSize = 8;
inline constexpr size_t kWearCrcSize = 4;
inline constexpr uint16_t kWearMaxPayload = 4096;  // Wi-Fi Direct upper bound
inline constexpr size_t kWearMinWireSize = kWearHeaderSize + 1 + kWearCrcSize;
inline constexpr size_t kWearMaxWireSize =
    kWearHeaderSize + kWearMaxPayload + kWearCrcSize;

// Header flag bits (known-mask = 0x0F).
inline constexpr uint8_t kWearFlagStereo = 0x01;
inline constexpr uint8_t kWearFlagCrc = 0x02;
inline constexpr uint8_t kWearFlagCompressed = 0x04;
inline constexpr uint8_t kWearFlagContinuation = 0x08;
inline constexpr uint8_t kWearKnownFlagMask = 0x0F;

// Rate-code <-> Hz mapping (unknown code -> 0 Hz).
uint8_t SampleRateToCode(uint32_t hz);
uint32_t SampleRateFromCode(uint8_t code);

// CRC32 (IEEE reflected, poly 0xEDB88320, init/xorout 0xFFFFFFFF).
// Check value: Crc32("123456789") == 0xCBF43926 (zlib-compatible).
uint32_t Crc32(const uint8_t* data, size_t length);

// ---- zero-run compressor ("rate-limiting compressor" payload stage) --------
// Byte-oriented RLE over PCM16 payloads: long digital-silence runs (the
// dominant feature of networked companion audio) collapse from N bytes to a
// 2-byte token. Both directions are strictly bounds-checked and return
// kWearCodecBadSize instead of writing out of range.

inline constexpr size_t kWearCodecBadSize = static_cast<size_t>(-1);

// Worst-case expansion of a literal-heavy stream (control byte per literal
// block): 2x + slack.
size_t WearCompressMaxOutput(size_t raw_bytes);

size_t WearCompressPayload(const uint8_t* in, size_t n, uint8_t* out,
                           size_t cap);
size_t WearDecompressPayload(const uint8_t* in, size_t n, uint8_t* out,
                             size_t cap);

// ---- token-bucket rate limiter ----------------------------------------------
// Paces packet emission so BLE / Wi-Fi Direct controller queues on the
// watch never overflow: bytes accrue at `bytes_per_second` up to
// `burst_bytes`, and a packet is only emitted when its full wire size fits
// the bucket. The clock is injectable for deterministic tests.

class RateLimiter {
public:
    RateLimiter() = default;
    RateLimiter(uint32_t bytes_per_second, uint32_t burst_bytes);

    void configure(uint32_t bytes_per_second, uint32_t burst_bytes);
    void reset();

    // Unlimited budgets always grant.
    bool tryAcquire(size_t bytes);
    bool tryAcquireAt(size_t bytes, double now_ms);  // deterministic variant
    uint32_t bytes_per_second() const { return rate_; }
    uint32_t burst_bytes() const { return burst_; }

private:
    void refill(double now_ms);

    uint32_t rate_ = 0;    // 0 == unlimited
    uint32_t burst_ = 0;
    double tokens_ = 0.0;
    double last_ms_ = 0.0;
    bool started_ = false;
};

// ---- packetizer --------------------------------------------------------------

struct WearTelemetry {
    uint64_t packets_emitted;
    uint64_t packets_throttled;   // deferred by the rate limiter
    uint64_t frames_packed;       // PCM frames accepted
    uint64_t bytes_payload;       // pre-compression payload bytes
    uint64_t bytes_wire;          // total on-wire bytes emitted
    uint64_t crc_failures;        // parse-side CRC mismatches
    uint64_t parse_errors;        // parse-side header/size/flag rejects
};

class FramePacketizer {
public:
    struct Config {
        uint32_t sample_rate = 24000;        // must map to a rate code
        bool stereo = false;                 // payload channel count
        uint32_t mtu_bytes = 512;            // max total wire size [13..4108]
        uint32_t rate_budget_bytes_per_sec = 0;  // 0 == unlimited pacing
        uint32_t burst_bytes = 0;                // 0 == auto (2x MTU)
        bool enable_compression = true;      // zero-run RLE when it saves
    };

    enum class ParseResult : int {
        kOk = 0,
        kNullArgs = 1,
        kTooShort = 2,
        kBadMagic = 3,
        kBadRateCode = 4,
        kBadFlags = 5,
        kBadSize = 6,
        kCrcMismatch = 7,
        kDecodeOverflow = 8,
    };

    struct ParsedPacket {
        uint16_t sequence;
        uint32_t sample_rate;
        bool stereo;
        bool compressed;
        bool continuation;
        size_t payload_bytes;  // decoded payload size
        size_t wire_bytes;     // total packet size consumed
    };

    explicit FramePacketizer(const Config& cfg);
    ~FramePacketizer();

    FramePacketizer(const FramePacketizer&) = delete;
    FramePacketizer& operator=(const FramePacketizer&) = delete;

    // False when construction failed (invalid config / scratch allocation
    // failure). Parse() keeps working on an invalid instance; PackFrames
    // becomes a no-op so a bad constructor argument can never take the
    // sender down.
    bool valid() const { return scratch_ != nullptr; }

    static bool ValidateConfig(const Config& cfg);

    // Reset sequence cursor, telemetry and limiter state.
    void reset();

    // Pack `frames` interleaved PCM16 frames into packets written to `out`.
    // Emits packets while input, output capacity and the rate limiter allow;
    // stops cleanly so the caller can retry the remaining frames (back-
    // pressure). Returns wire bytes written; `packets_out`/`frames_packed`
    // are optional out-params. Whole frames only — a partial trailing frame
    // is left to the caller. Zero heap allocation per packet (scratch is
    // preallocated at construction).
    size_t PackFrames(const int16_t* pcm, size_t frames, uint8_t* out,
                      size_t out_capacity, size_t* packets_out = nullptr,
                      size_t* frames_packed = nullptr);

    // Validate + decode one packet. `decoded` receives the (decompressed)
    // payload; it must be non-null when the payload is compressed, and must
    // have >= kWearMaxPayload capacity to guarantee decoding any valid
    // compressed payload. Returns kOk and fills `info` on success.
    ParseResult Parse(const uint8_t* packet, size_t size, ParsedPacket* info,
                      uint8_t* decoded, size_t decoded_capacity);

    void SnapshotTelemetry(WearTelemetry* out) const;
    const Config& config() const { return cfg_; }
    RateLimiter& limiter() { return limiter_; }

private:
    Config cfg_{};
    uint8_t rate_code_ = 0;
    uint16_t seq_ = 0;
    size_t max_payload_ = 0;  // mtu - header - crc
    RateLimiter limiter_;
    uint8_t* scratch_ = nullptr;  // compression scratch (2*kWearMaxPayload+8)

    // Relaxed atomics: sender (Pack) and receiver (Parse) may be threads.
    std::atomic<uint64_t> emitted_{0};
    std::atomic<uint64_t> throttled_{0};
    std::atomic<uint64_t> frames_packed_{0};
    std::atomic<uint64_t> payload_bytes_{0};
    std::atomic<uint64_t> wire_bytes_{0};
    std::atomic<uint64_t> crc_failures_{0};
    std::atomic<uint64_t> parse_errors_{0};
};

}  // namespace wear
}  // namespace streamify

#endif  // STREAMIFY_WEAR_AUDIO_ENCODER_H
