#ifndef STREAMIFY_AUDIO_STREAM_TYPES_H
#define STREAMIFY_AUDIO_STREAM_TYPES_H
// ============================================================================
//  AudioStreamTypes.h — shared types for the native audio bitstream layer
//  (Phase 3, BEHIND.md #38 quality ladder / #44 background downloads:
//  "local remux of YT Opus/AAC" — today LosslessRemuxer is a byte copy;
//  this module turns it into validated, container-level remuxing).
// ============================================================================
//
//  Source codecs (YouTube itag ladder):
//    * itag 251 — Opus (WebM audio), decodes at 48 kHz / s16le
//    * itag 140 — AAC-LC in ADTS (M4A audio), 44.1 kHz nominal
//
//  Hard rules for every parser/muxer in this module:
//    * No read ever crosses the caller-provided buffer bounds.
//    * Malformed/truncated/corrupted input yields a ParseStatus — never a
//      crash, never UB, never an out-of-bounds read.
//    * Zero heap allocation on the per-packet hot path.
// ============================================================================

#include <cstddef>
#include <cstdint>

namespace streamify::audio {

// Remux target codec.
enum class Codec : int32_t {
    kOpus = 0,   // itag 251 -> Ogg Opus
    kAacAdts = 1 // itag 140 -> normalized ADTS
};

// Unified status ladder. Values are stable (JNI transports them verbatim).
enum class ParseStatus : int32_t {
    kOk = 0,
    kTooSmall = 1,         // fewer bytes than the minimum header requires
    kBadSync = 2,          // ADTS syncword absent
    kBadHeader = 3,        // structurally invalid header fields
    kUnsupportedRate = 4,  // sample rate outside the strict 44.1/48k ladder
    kTruncated = 5,        // declared length exceeds the available bytes
    kBadFrameLength = 6,   // internal inconsistency (VBR sums, padding, 1275)
    kOutOfOrder = 7,       // sequence regression (remuxer-level)
    kWrongState = 8,       // remuxer API misuse (no begin(), use after finish)
    kInvalidArgument = 9,  // null/buffer-size mismatch from the caller
    kIoError = 10,         // output sink refused a write
};

// Parsed Opus packet layout (RFC 6716 §3, libopus-compatible accounting).
struct OpusFrameInfo {
    int32_t config = 0;           // TOC config 0..31 (mode + bandwidth + size)
    int32_t frameCount = 0;       // frames carried in this packet (1..48)
    float frameSizeMs = 0.0f;     // per-frame duration (2.5 .. 60 ms)
    float totalDurationMs = 0.0f; // frameCount * frameSizeMs
    int32_t channels = 1;         // 1 or 2 (TOC stereo bit, mapping family 0)
    int32_t sampleRateHz = 48000; // Opus ALWAYS decodes at 48 kHz
    int32_t pcmBitDepth = 16;     // s16 decode target (codec is lossy)
    int32_t samplesAt48k = 0;     // totalDurationMs * 48 (granule delta)
    bool vbr = false;             // code 1, or code 3 + V bit
    bool hasPadding = false;      // code 3 + P bit
    int32_t paddingBytes = 0;     // decoded padding length
    int32_t largestFrameBytes = 0; // largest single frame in the packet
};

// Parsed ADTS frame layout (ISO/IEC 14496-3 §1.7.3).
struct AdtsFrameInfo {
    int32_t profile = 1;           // 2-bit: 0 Main, 1 LC (itag 140), 2 SSR, 3 LTP
    int32_t sampleRateIndex = 4;   // 4-bit index (4 == 44100, 3 == 48000)
    int32_t sampleRateHz = 0;      // resolved from the index
    int32_t channels = 0;          // 3-bit config (0 = in-band PCE)
    int32_t frameLength = 0;       // declared total, header included
    int32_t headerBytes = 7;       // 7, or 9 when the CRC is present
    int32_t payloadBytes = 0;      // frameLength - headerBytes
    int32_t rawBlocksInFrame = 1;  // number_of_raw_data_blocks_in_frame
    int32_t bufferFullness = 0;    // 11-bit field (informational)
    int32_t pcmBitDepth = 16;      // AAC-LC s16 decode target
    bool hasCrc = false;           // !protection_absent
};

// True iff `status` is one of the "packet is structurally fine" codes.
inline bool parseOk(ParseStatus s) { return s == ParseStatus::kOk; }

// Human-readable status name (host logs / test output).
inline const char* parseStatusName(ParseStatus s) {
    switch (s) {
    case ParseStatus::kOk: return "ok";
    case ParseStatus::kTooSmall: return "too-small";
    case ParseStatus::kBadSync: return "bad-sync";
    case ParseStatus::kBadHeader: return "bad-header";
    case ParseStatus::kUnsupportedRate: return "unsupported-rate";
    case ParseStatus::kTruncated: return "truncated";
    case ParseStatus::kBadFrameLength: return "bad-frame-length";
    case ParseStatus::kOutOfOrder: return "out-of-order";
    case ParseStatus::kWrongState: return "wrong-state";
    case ParseStatus::kInvalidArgument: return "invalid-argument";
    case ParseStatus::kIoError: return "io-error";
    }
    return "unknown";
}

}  // namespace streamify::audio

#endif  // STREAMIFY_AUDIO_STREAM_TYPES_H
