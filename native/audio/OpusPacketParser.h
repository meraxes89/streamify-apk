#ifndef STREAMIFY_OPUS_PACKET_PARSER_H
#define STREAMIFY_OPUS_PACKET_PARSER_H
// ============================================================================
//  OpusPacketParser.h — RFC 6716 Opus TOC/frame-length validator (Phase 3,
//  itag 251 "Opus 251 -> local remuxed container")
// ============================================================================
//
//  Validates the INTERNAL structure of one Opus packet and reports the
//  frame accounting the Ogg muxer needs (frame count, per-frame duration,
//  48 kHz sample delta, padding, VBR). The implementation mirrors
//  libopus's opus_packet_parse_impl() header accounting exactly:
//
//    TOC byte:  config(5) | stereo(1) | code(2)
//      code 0: 1 frame,  size = packet - 1
//      code 1: 2 frames VBR: 1 size byte, frame2 = rest (must be >= 0)
//      code 2: 2 frames CBR: (packet - 1) must halve evenly
//      code 3: count byte: N = bits 0..5 (1..48), P = 0x40, V = 0x80
//              P: padding length run: b < 255 adds b and stops,
//                 b == 255 adds 254 and continues
//              V: (N-1) size runs with the SAME 255/254 extension rule,
//                 last frame = remainder - padding
//              CBR: (remainder - padding) must divide evenly by N
//    Every frame must be <= 1275 bytes (RFC 6716 §2.1.2 hard limit).
//
//  resyncToc() scans a raw byte stream for the next offset where a
//  plausible Opus packet starts (used by the Kotlin layer after a corrupt
//  region; bounded scans, no reads past the buffer).
//
//  All functions are pure: no state, no allocation, no globals.
// ============================================================================

#include "AudioStreamTypes.h"

#include <cstddef>
#include <cstdint>

namespace streamify::audio {

class OpusPacketParser {
public:
    // Full structural validation + frame accounting. `len` == 0 is
    // kTooSmall; every read is bounds-checked first.
    static ParseStatus parse(const uint8_t* data, size_t len,
                             OpusFrameInfo* outInfo);

    // Per-frame duration in milliseconds for a TOC config (0..31);
    // 0 for out-of-range configs.
    static float configFrameSizeMs(int32_t config);

    // Per-frame sample count at 48 kHz (120/240/480/960/1920/2880);
    // 0 for out-of-range configs.
    static int32_t configSamplesAt48k(int32_t config);

    // Channels advertised by the TOC stereo bit (1 or 2).
    static int32_t tocChannels(const uint8_t* data, size_t len);

    // Scan `data[from..len)` for the next plausible packet start:
    // a TOC byte whose implied packet parses structurally within the
    // remaining bytes. Returns the offset, or -1 when none exists.
    // Purely heuristic for codes 0/2 (self-consistency only) — code 1/3
    // packets carry explicit lengths and are verified exactly.
    static int64_t resyncToc(const uint8_t* data, size_t len, size_t from);

    // The maximum legal Opus frame size (RFC 6716).
    static constexpr int32_t kMaxOpusFrameBytes = 1275;
    static constexpr int32_t kMaxFramesPerPacket = 48;
};

}  // namespace streamify::audio

#endif  // STREAMIFY_OPUS_PACKET_PARSER_H
