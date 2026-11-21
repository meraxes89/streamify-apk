#ifndef STREAMIFY_ADTS_FRAME_PARSER_H
#define STREAMIFY_ADTS_FRAME_PARSER_H
// ============================================================================
//  AdtsFrameParser.h — ISO/IEC 14496-3 ADTS header validator (Phase 3,
//  itag 140 "AAC 140 -> local lossless/remuxed container")
// ============================================================================
//
//  Layout (7 bytes, or 9 with CRC — CRC protection is detected but the
//  checksum itself is NOT verified: a remuxer passes protected frames
//  through byte-exact and the decoder owns checksum enforcement):
//
//    b0      : syncword hi (0xFF)
//    b1 bits : syncword lo (0xF0) | ID (0x08, MPEG-2 vs MPEG-4) | layer
//              (0x06, MUST be 0) | protection_absent (0x01)
//    b2 bits : profile (0xC0, == object type - 1) | sr index (0x3C) |
//              private (0x02) | chanConfig hi (0x01)
//    b3 bits : chanConfig lo (0xC0) | original/copy | home |
//              copyright id start | frameLength hi2 (0x03)
//    b4, b5  : frameLength mid8 + lo3 (13 bits total, incl. header)
//    b5/b6   : buffer fullness (11 bits) | raw data blocks - 1 (2 bits)
//
//  Validation ladder: syncword -> layer 0 -> non-reserved sample-rate
//  index -> self-consistent frameLength (>= headerBytes) -> length vs
//  available bytes (kTruncated when more data is needed). The optional
//  strict ladder restricts rates to the 44.1/48 kHz YouTube sources.
//
//  All functions are pure: no state, no allocation, no globals.
// ============================================================================

#include "AudioStreamTypes.h"

#include <cstddef>
#include <cstdint>

namespace streamify::audio {

class AdtsFrameParser {
public:
    // Minimum header sizes.
    static constexpr int32_t kHeaderBytesNoCrc = 7;
    static constexpr int32_t kHeaderBytesWithCrc = 9;

    // Full structural validation. `strictLadderRates` additionally rejects
    // sample rates outside {44100, 48000} with kUnsupportedRate (the
    // Phase-3 download pipeline only ever feeds itag-140 AAC).
    static ParseStatus parse(const uint8_t* data, size_t len,
                             bool strictLadderRates, AdtsFrameInfo* outInfo);

    // Sample rate for a 4-bit index (0 = reserved -> 0 Hz).
    static int32_t sampleRateForIndex(int32_t index);

    // Scan `data[from..len)` for the next plausible ADTS sync: 0xFF 0xFx
    // with layer 0, a non-reserved rate index, and a frameLength that
    // fits the remaining bytes. Returns the offset, or -1.
    static int64_t resync(const uint8_t* data, size_t len, size_t from);

    // Rebuild a clean 7-byte ADTS header (protection absent) with a
    // corrected frame length and VBR-legal buffer fullness (0x7FF).
    // Used by the remuxer to normalize sloppy source headers; payload
    // bytes are always passed through untouched.
    static void writeNormalizedHeader(uint8_t out7[7], int32_t profile,
                                      int32_t sampleRateIndex,
                                      int32_t channels, int32_t frameLength);
};

}  // namespace streamify::audio

#endif  // STREAMIFY_ADTS_FRAME_PARSER_H
