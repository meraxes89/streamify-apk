// ============================================================================
//  AudioFrameRemuxer.cc — see AudioFrameRemuxer.h for the contracts.
// ============================================================================

#include "AudioFrameRemuxer.h"

#include <array>
#include <cstring>

#include "AdtsFrameParser.h"
#include "OpusPacketParser.h"

namespace streamify::audio {

namespace {
// ---- Ogg CRC-32 (RFC 3533 §5.2): poly 0x04C11DB7, init 0, MSB-first,
// no reflection, no final xor. Table generated at compile time.
constexpr std::array<uint32_t, 256> makeOggCrcTable() {
    std::array<uint32_t, 256> t{};
    for (uint32_t i = 0; i < 256; ++i) {
        uint32_t r = i << 24;
        for (int k = 0; k < 8; ++k) {
            r = (r & 0x80000000u) != 0u ? ((r << 1) ^ 0x04C11DB7u)
                                        : (r << 1);
        }
        t[i] = r;
    }
    return t;
}
constexpr std::array<uint32_t, 256> kOggCrcTable = makeOggCrcTable();

inline uint32_t crcStep(uint32_t crc, uint8_t b) {
    return (crc << 8) ^
           kOggCrcTable[((crc >> 24) & 0xFFu) ^ static_cast<uint32_t>(b)];
}

uint32_t crc32Update(uint32_t crc, const uint8_t* d, size_t n) {
    for (size_t i = 0; i < n; ++i) {
        crc = crcStep(crc, d[i]);
    }
    return crc;
}

// Little-endian writers (Ogg fields are all LE).
inline void putLe16(uint8_t* p, uint16_t v) {
    p[0] = static_cast<uint8_t>(v & 0xFF);
    p[1] = static_cast<uint8_t>((v >> 8) & 0xFF);
}
inline void putLe32(uint8_t* p, uint32_t v) {
    p[0] = static_cast<uint8_t>(v & 0xFF);
    p[1] = static_cast<uint8_t>((v >> 8) & 0xFF);
    p[2] = static_cast<uint8_t>((v >> 16) & 0xFF);
    p[3] = static_cast<uint8_t>((v >> 24) & 0xFF);
}
inline void putLe64(uint8_t* p, uint64_t v) {
    putLe32(p, static_cast<uint32_t>(v & 0xFFFFFFFFu));
    putLe32(p + 4, static_cast<uint32_t>(v >> 32));
}

constexpr size_t kPageBodyOffset = 27 + 255;  // max header footprint
constexpr size_t kPageBodyCapacity = AudioFrameRemuxer::kPageStagingBytes -
                                     kPageBodyOffset;

// OpusHead (RFC 7845 §5.1): 19 bytes, mapping family 0.
constexpr size_t kOpusHeadSize = 19;
// OpusTags (RFC 7845 §5.2): magic + vendor length + vendor + 0 comments.
constexpr char kVendorString[] = "StreamifyNativeRemux/1.0";
}  // namespace

uint32_t AudioFrameRemuxer::oggCrc32(const uint8_t* data, size_t len) {
    return crc32Update(0, data, len);
}

uint32_t AudioFrameRemuxer::oggCrc32Bitwise(const uint8_t* data, size_t len) {
    uint32_t crc = 0;
    for (size_t i = 0; i < len; ++i) {
        crc ^= static_cast<uint32_t>(data[i]) << 24;
        for (int k = 0; k < 8; ++k) {
            crc = (crc & 0x80000000u) != 0u ? ((crc << 1) ^ 0x04C11DB7u)
                                            : (crc << 1);
        }
    }
    return crc;
}

bool AudioFrameRemuxer::sinkWrite(const uint8_t* data, size_t len) {
    if (sink_.write == nullptr || data == nullptr) {
        return false;
    }
    if (len == 0) {
        return true;
    }
    if (!sink_.write(sink_.ctx, data, len)) {
        return false;
    }
    stats_.bytesOut += len;
    return true;
}

void AudioFrameRemuxer::begin(const RemuxerConfig& cfg, const RemuxSink& sink) {
    cfg_ = cfg;
    if (cfg_.channels != 1 && cfg_.channels != 2) {
        cfg_.channels = 0;  // learn from the first packet
    }
    if (cfg_.codec != Codec::kOpus && cfg_.codec != Codec::kAacAdts) {
        cfg_.codec = Codec::kOpus;
    }
    if (cfg_.opusPreSkip < 0 || cfg_.opusPreSkip > 65535) {
        cfg_.opusPreSkip = 312;
    }
    sink_ = sink;
    stats_ = RemuxStats{};
    oggHeadersWritten_ = false;
    oggEosWritten_ = false;
    hasLastSequence_ = false;
    lastSequence_ = 0;
    effectiveChannels_ = 0;
    pageFill_ = 0;
    lacingCount_ = 0;
    lacingSum_ = 0;
    granule_ = 0;
    pendingGranule_ = 0;
    pageSequence_ = 0;
    state_ = State::kLive;
}

ParseStatus AudioFrameRemuxer::flushOggPage(bool lastPage, bool bosPage) {
    // Nothing staged: only an EOS (or BOS) page may legitimately be empty.
    if (lacingCount_ == 0 && !lastPage && !bosPage) {
        return ParseStatus::kOk;
    }
    if (oggEosWritten_) {
        return ParseStatus::kOk;  // never double-EOS
    }

    uint8_t hdr[kPageBodyOffset];
    std::memset(hdr, 0, sizeof(hdr));
    hdr[0] = 'O';
    hdr[1] = 'g';
    hdr[2] = 'g';
    hdr[3] = 'S';
    hdr[4] = 0;  // version
    uint8_t headerType = 0;
    if (bosPage) headerType |= 0x02;
    if (lastPage) headerType |= 0x04;
    hdr[5] = headerType;
    // Granule of the last packet that COMPLETES on this page.
    putLe64(hdr + 6, lacingCount_ == 0 ? granule_ : pendingGranule_);
    putLe32(hdr + 14, cfg_.oggSerial);
    putLe32(hdr + 18, pageSequence_);
    putLe32(hdr + 22, 0);  // CRC placeholder (zeroed during computation)
    hdr[26] = static_cast<uint8_t>(lacingCount_);
    const size_t hdrLen = 27 + static_cast<size_t>(lacingCount_);
    if (lacingCount_ > 0) {
        std::memcpy(hdr + 27, lacing_, static_cast<size_t>(lacingCount_));
    }

    // CRC over header (with zeroed field) then body.
    uint32_t crc = crc32Update(0, hdr, hdrLen);
    crc = crc32Update(crc, page_ + kPageBodyOffset, pageFill_);
    putLe32(hdr + 22, crc);

    if (!sinkWrite(hdr, hdrLen)) {
        return ParseStatus::kIoError;
    }
    if (pageFill_ > 0 &&
        !sinkWrite(page_ + kPageBodyOffset, pageFill_)) {
        return ParseStatus::kIoError;
    }

    granule_ = pendingGranule_;
    lacingCount_ = 0;
    lacingSum_ = 0;
    pageFill_ = 0;
    ++pageSequence_;
    ++stats_.pagesEmitted;
    if (lastPage) {
        oggEosWritten_ = true;
    }
    return ParseStatus::kOk;
}

ParseStatus AudioFrameRemuxer::beginOggStream() {
    if (oggHeadersWritten_) {
        return ParseStatus::kOk;
    }
    if (effectiveChannels_ != 1 && effectiveChannels_ != 2) {
        effectiveChannels_ = 2;  // final fallback (itag 251 is stereo)
    }

    // Page 0 (BOS): OpusHead.
    uint8_t head[kOpusHeadSize];
    std::memcpy(head, "OpusHead", 8);
    head[8] = 1;  // version
    head[9] = static_cast<uint8_t>(effectiveChannels_);
    putLe16(head + 10, static_cast<uint16_t>(cfg_.opusPreSkip));
    putLe32(head + 12, 48000);  // input sample rate (informational; Opus
                                // ALWAYS decodes at 48 kHz)
    putLe16(head + 16, 0);      // output gain
    head[18] = 0;               // mapping family 0
    std::memcpy(page_ + kPageBodyOffset, head, kOpusHeadSize);
    pageFill_ = kOpusHeadSize;
    lacing_[0] = static_cast<uint8_t>(kOpusHeadSize);
    lacingCount_ = 1;
    lacingSum_ = kOpusHeadSize;
    if (const ParseStatus s = flushOggPage(false, true); s != ParseStatus::kOk) {
        return s;  // sink failure: never leave a half-written stream
    }

    // Page 1: OpusTags (vendor string, zero user comments).
    const size_t vendorLen = sizeof(kVendorString) - 1;
    const size_t tagsSize = 8 + 4 + vendorLen + 4;
    uint8_t tags[8 + 4 + sizeof(kVendorString) + 4];
    std::memcpy(tags, "OpusTags", 8);
    putLe32(tags + 8, static_cast<uint32_t>(vendorLen));
    std::memcpy(tags + 12, kVendorString, vendorLen);
    putLe32(tags + 12 + vendorLen, 0);
    std::memcpy(page_ + kPageBodyOffset, tags, tagsSize);
    pageFill_ = tagsSize;
    lacing_[0] = static_cast<uint8_t>(tagsSize);
    lacingCount_ = 1;
    lacingSum_ = tagsSize;
    if (const ParseStatus s = flushOggPage(false, false); s != ParseStatus::kOk) {
        return s;
    }

    oggHeadersWritten_ = true;
    return ParseStatus::kOk;
}

ParseStatus AudioFrameRemuxer::stageOpusPacket(const uint8_t* data,
                                               size_t len) {
    // Lacing values: 255-runs + terminator.
    int32_t needed = 0;
    for (size_t n = len;; n -= 255) {
        ++needed;
        if (n < 255) break;
    }
    // Flush first if this packet would overflow the current page.
    if ((lacingCount_ + needed > 255) ||
        (pageFill_ + len > kPageBodyCapacity)) {
        if (const ParseStatus s = flushOggPage(false, false); s != ParseStatus::kOk) {
            return s;
        }
        // A legal packet (<= 48 * 1275 bytes + overhead) always fits in a
        // fresh page — the parser rejected anything larger already.
        if (lacingCount_ != 0 || needed > 255 ||
            len > kPageBodyCapacity) {
            return ParseStatus::kBadFrameLength;
        }
    }
    // Stage body bytes then lacing values.
    std::memcpy(page_ + kPageBodyOffset + pageFill_, data, len);
    pageFill_ += len;
    size_t n = len;
    for (;;) {
        const uint8_t seg = n >= 255 ? 255 : static_cast<uint8_t>(n);
        lacing_[static_cast<size_t>(lacingCount_++)] = seg;
        if (n < 255) break;
        n -= 255;
    }
    lacingSum_ += static_cast<int32_t>(len);
    return ParseStatus::kOk;
}

ParseStatus AudioFrameRemuxer::remuxAdtsFrame(const uint8_t* data, size_t len,
                                              const AdtsFrameInfo& info) {
    if (info.hasCrc) {
        // Protected frame: pass through byte-exact (header + CRC + payload).
        if (!sinkWrite(data, len)) {
            return ParseStatus::kIoError;
        }
    } else {
        uint8_t hdr[7];
        AdtsFrameParser::writeNormalizedHeader(
            hdr, info.profile, info.sampleRateIndex, info.channels,
            static_cast<int32_t>(len));
        if (!sinkWrite(hdr, 7)) {
            return ParseStatus::kIoError;
        }
        if (!sinkWrite(data + 7, len - 7)) {  // payload untouched
            return ParseStatus::kIoError;
        }
    }
    ++stats_.framesEmitted;
    return ParseStatus::kOk;
}

ParseStatus AudioFrameRemuxer::remuxPacket(const uint8_t* data, size_t len,
                                           uint64_t sequence) {
    if (state_ != State::kLive) {
        return ParseStatus::kWrongState;
    }
    ++stats_.packetsIn;
    stats_.bytesIn += len;

    // Sequence gate: strictly increasing; duplicates and regressions drop.
    if (hasLastSequence_ && sequence <= lastSequence_) {
        ++stats_.droppedOutOfOrder;
        return ParseStatus::kOutOfOrder;
    }

    if (data == nullptr) {
        if (len == 0) {
            ++stats_.droppedTruncated;
            return ParseStatus::kTooSmall;
        }
        ++stats_.droppedCorrupt;
        return ParseStatus::kInvalidArgument;
    }

    ParseStatus status;
    if (cfg_.codec == Codec::kOpus) {
        OpusFrameInfo info;
        status = OpusPacketParser::parse(data, len, &info);
        if (status == ParseStatus::kOk) {
            if (!oggHeadersWritten_) {
                // Learn channels from the first packet when unspecified.
                effectiveChannels_ =
                    (cfg_.channels == 1 || cfg_.channels == 2)
                        ? cfg_.channels
                        : info.channels;
                const ParseStatus hs = beginOggStream();
                if (hs != ParseStatus::kOk) {
                    // Header emission failed (sink): surface it, keep
                    // stats conservation.
                    ++stats_.droppedCorrupt;
                    return hs;
                }
            }
            status = stageOpusPacket(data, len);
            if (status == ParseStatus::kOk) {
                pendingGranule_ += static_cast<uint64_t>(info.samplesAt48k);
                ++stats_.packetsAccepted;
                lastSequence_ = sequence;
                hasLastSequence_ = true;
            } else {
                // Sink/IO failure on an otherwise-valid packet. Counted in
                // the corrupt bucket so packetsIn == accepted + drops holds
                // (only kIoError can reach here: parse already bounds every
                // legal packet below the page capacity).
                ++stats_.droppedCorrupt;
            }
            return status;
        }
    } else {
        AdtsFrameInfo info;
        status = AdtsFrameParser::parse(data, len, cfg_.strictRates, &info);
        if (status == ParseStatus::kOk) {
            status = remuxAdtsFrame(data, len, info);
            if (status == ParseStatus::kOk) {
                ++stats_.packetsAccepted;
                lastSequence_ = sequence;
                hasLastSequence_ = true;
            } else {
                ++stats_.droppedCorrupt;  // sink failure; see note above
            }
            return status;
        }
    }

    // Classify the drop for the stats ladder.
    if (status == ParseStatus::kTooSmall || status == ParseStatus::kTruncated) {
        ++stats_.droppedTruncated;
    } else {
        ++stats_.droppedCorrupt;
    }
    return status;
}

ParseStatus AudioFrameRemuxer::finish() {
    if (state_ != State::kLive) {
        return ParseStatus::kWrongState;
    }
    if (cfg_.codec == Codec::kOpus) {
        if (!oggHeadersWritten_) {
            // Degenerate stream (no valid packet ever arrived): still emit
            // a structurally valid Ogg Opus file (headers + EOS).
            if (const ParseStatus s = beginOggStream(); s != ParseStatus::kOk) {
                return s;
            }
        }
        if (const ParseStatus s = flushOggPage(true, false);
            s != ParseStatus::kOk) {
            return s;
        }
    }
    state_ = State::kFinished;
    return ParseStatus::kOk;
}

}  // namespace streamify::audio
