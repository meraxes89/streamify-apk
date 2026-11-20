#ifndef STREAMIFY_AUDIO_FRAME_REMUXER_H
#define STREAMIFY_AUDIO_FRAME_REMUXER_H
// ============================================================================
//  AudioFrameRemuxer.h — validated packet-stream remuxer (Phase 3,
//  BEHIND.md #38/#44: downloaded audio -> local lossless/remuxed container)
// ============================================================================
//
//  Input : framed packets from the download layer (itag 251 Opus chunks or
//          itag 140 ADTS frames) + a monotonic sequence number per packet.
//  Output: through a caller sink —
//            Opus   -> standard Ogg Opus (.opus): BOS page with OpusHead
//                      (RFC 7845), OpusTags page, audio pages with lacing
//                      tables and 48 kHz granule positions, EOS page.
//            AAC    -> normalized clean ADTS: rebuilt 7-byte headers with
//                      corrected lengths and VBR-legal fullness; payload
//                      bytes passed through untouched. CRC-protected source
//                      frames are passed through verbatim (the decoder owns
//                      checksum enforcement).
//
//  Robustness contract (directive §2.2): truncated, corrupted, and
//  out-of-order packets are DROPPED AND COUNTED — never a crash, never UB,
//  never a stall. Sequence regressions/duplicates are out-of-order drops.
//  All buffers are fixed-size members: ZERO heap allocation on the
//  per-packet path (the JNI session layer owns the growable output vector).
//
//  Ogg CRC-32: poly 0x04C11DB7, init 0, MSB-first, no reflection, no xorout
//  (RFC 3533 §5.2) — computed from a constexpr-generated table, cross-checked
//  against a bitwise reference in the test suite.
//
//  Lifecycle: begin() -> remuxPacket()* -> finish(); begin() again restarts.
//  One instance per stream; not internally synchronized.
// ============================================================================

#include "AudioStreamTypes.h"

#include <cstddef>
#include <cstdint>

namespace streamify::audio {

// C-style output sink (ABI-stable across sanitizers; no std::function).
struct RemuxSink {
    void* ctx = nullptr;
    // Return false to abort the current operation (maps to kIoError).
    bool (*write)(void* ctx, const uint8_t* data, size_t len) = nullptr;
};

struct RemuxerConfig {
    Codec codec = Codec::kOpus;
    int32_t nominalSampleRate = 48000; // Opus always 48k; AAC 44100/48000
    int32_t channels = 2;              // 0 = learn from the first packet
    bool strictRates = true;           // reject rates outside 44.1/48 kHz
    uint32_t oggSerial = 0x53545246u;  // "STRF"
    // OpusHead pre-skip written into the Ogg header (libopus default @48k;
    // raw packet streams carry no encoder metadata to recover the true
    // value from — documented trade-off for packet-level remux).
    int32_t opusPreSkip = 312;
};

struct RemuxStats {
    uint64_t packetsIn = 0;
    uint64_t packetsAccepted = 0;
    uint64_t droppedCorrupt = 0;     // bad sync/header/frame-length/rate
    uint64_t droppedTruncated = 0;
    uint64_t droppedOutOfOrder = 0;
    uint64_t bytesIn = 0;
    uint64_t bytesOut = 0;
    uint64_t pagesEmitted = 0;       // Ogg pages (Opus path)
    uint64_t framesEmitted = 0;      // ADTS frames (AAC path)
};

class AudioFrameRemuxer {
public:
    AudioFrameRemuxer() = default;

    // Arm the remuxer for a new stream (also usable as a reset).
    void begin(const RemuxerConfig& cfg, const RemuxSink& sink);

    // Validate + remux one packet. `sequence` must be strictly increasing
    // per stream; equal or regressing values are out-of-order drops.
    ParseStatus remuxPacket(const uint8_t* data, size_t len,
                            uint64_t sequence);

    // Flush the tail (final Ogg page with EOS, ADTS needs nothing).
    ParseStatus finish();

    const RemuxStats& stats() const { return stats_; }

    // ---- Ogg CRC (exposed for tests) ----
    static uint32_t oggCrc32(const uint8_t* data, size_t len);
    static uint32_t oggCrc32Bitwise(const uint8_t* data, size_t len);

    static constexpr size_t kPageStagingBytes = 65536 + 512;  // > max legal
                                                             // Opus packet
                                                             // (48*1275) +
                                                             // page header

private:
    enum class State { kIdle, kLive, kFinished };

    // ---- Ogg page staging (Opus path) ----
    ParseStatus stageOpusPacket(const uint8_t* data, size_t len);
    ParseStatus flushOggPage(bool lastPage, bool bosPage);
    ParseStatus beginOggStream();

    // ---- ADTS normalization (AAC path) ----
    ParseStatus remuxAdtsFrame(const uint8_t* data, size_t len,
                               const AdtsFrameInfo& info);

    bool sinkWrite(const uint8_t* data, size_t len);

    State state_ = State::kIdle;
    RemuxerConfig cfg_{};
    RemuxSink sink_{};
    RemuxStats stats_{};
    bool oggHeadersWritten_ = false;
    bool oggEosWritten_ = false;
    uint64_t lastSequence_ = 0;
    bool hasLastSequence_ = false;
    int32_t effectiveChannels_ = 0;

    // Staged Ogg page: bytes + lacing table.
    uint8_t page_[kPageStagingBytes];
    size_t pageFill_ = 0;
    uint8_t lacing_[255];
    int32_t lacingCount_ = 0;
    int32_t lacingSum_ = 0;        // bytes covered by the staged segments
    uint64_t granule_ = 0;         // cumulative 48 kHz samples
    uint64_t pendingGranule_ = 0;  // granule AFTER the staged packets
    uint32_t pageSequence_ = 0;
};

}  // namespace streamify::audio

#endif  // STREAMIFY_AUDIO_FRAME_REMUXER_H
