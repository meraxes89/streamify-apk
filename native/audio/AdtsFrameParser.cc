// ============================================================================
//  AdtsFrameParser.cc — see .h for the bit layout and status ladder.
// ============================================================================

#include "AdtsFrameParser.h"

namespace streamify::audio {

namespace {
constexpr int32_t kSampleRates[16] = {
    96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050,
    16000, 12000, 11025, 8000,  7350,  0,     0,     0,
};
}  // namespace

int32_t AdtsFrameParser::sampleRateForIndex(int32_t index) {
    if (index < 0 || index > 15) {
        return 0;
    }
    return kSampleRates[static_cast<size_t>(index)];
}

ParseStatus AdtsFrameParser::parse(const uint8_t* data, size_t len,
                                   bool strictLadderRates,
                                   AdtsFrameInfo* outInfo) {
    if (outInfo == nullptr) {
        return ParseStatus::kInvalidArgument;
    }
    *outInfo = AdtsFrameInfo{};
    if (data == nullptr && len != 0) {
        return ParseStatus::kInvalidArgument;
    }
    if (len < static_cast<size_t>(kHeaderBytesNoCrc)) {
        return ParseStatus::kTooSmall;
    }

    // Syncword: 12 bits, 0xFFE.
    if (data[0] != 0xFF || (data[1] & 0xF0) != 0xF0) {
        return ParseStatus::kBadSync;
    }
    // Layer MUST be 00b (bits 4..3 of byte 1).
    if (((data[1] >> 1) & 0x3) != 0) {
        return ParseStatus::kBadHeader;
    }
    const bool protectionAbsent = (data[1] & 0x01) != 0;

    const int32_t profile = (data[2] >> 6) & 0x3;
    const int32_t srIndex = (data[2] >> 2) & 0xF;
    const int32_t channels =
        (((data[2] & 0x1) << 2) | ((data[3] >> 6) & 0x3));
    if (srIndex >= 13) {
        return ParseStatus::kBadHeader;  // reserved indices
    }
    const int32_t sampleRate =
        kSampleRates[static_cast<size_t>(srIndex)];
    if (strictLadderRates && sampleRate != 44100 && sampleRate != 48000) {
        return ParseStatus::kUnsupportedRate;
    }

    const int32_t headerBytes =
        protectionAbsent ? kHeaderBytesNoCrc : kHeaderBytesWithCrc;
    const int32_t frameLength =
        ((static_cast<int32_t>(data[3] & 0x03)) << 11) |
        (static_cast<int32_t>(data[4]) << 3) |
        (static_cast<int32_t>(data[5] >> 5) & 0x7);
    if (frameLength < headerBytes) {
        return ParseStatus::kBadHeader;  // header longer than the frame
    }
    if (static_cast<size_t>(frameLength) > len) {
        return ParseStatus::kTruncated;  // declared more than available
    }

    outInfo->profile = profile;
    outInfo->sampleRateIndex = srIndex;
    outInfo->sampleRateHz = sampleRate;
    outInfo->channels = channels;  // 0 = in-band PCE (passed through)
    outInfo->frameLength = frameLength;
    outInfo->headerBytes = headerBytes;
    outInfo->payloadBytes = frameLength - headerBytes;
    outInfo->rawBlocksInFrame = (data[6] & 0x3) + 1;
    outInfo->bufferFullness =
        ((static_cast<int32_t>(data[5] & 0x1F)) << 6) |
        (static_cast<int32_t>(data[6] >> 2) & 0x3F);
    outInfo->hasCrc = !protectionAbsent;
    return ParseStatus::kOk;
}

int64_t AdtsFrameParser::resync(const uint8_t* data, size_t len, size_t from) {
    if (data == nullptr) {
        return -1;
    }
    if (from > len) {
        return -1;
    }
    AdtsFrameInfo info{};
    for (size_t i = from; i + 1 < len; ++i) {
        if (data[i] != 0xFF || (data[i + 1] & 0xF0) != 0xF0) {
            continue;
        }
        // Full structural parse at this candidate (checks layer, rate
        // index, frameLength-vs-remaining in one shot).
        if (parse(data + i, len - i, /*strictLadderRates=*/false, &info) ==
            ParseStatus::kOk) {
            return static_cast<int64_t>(i);
        }
    }
    return -1;
}

void AdtsFrameParser::writeNormalizedHeader(uint8_t out7[7], int32_t profile,
                                            int32_t sampleRateIndex,
                                            int32_t channels,
                                            int32_t frameLength) {
    if (out7 == nullptr) {
        return;
    }
    if (profile < 0 || profile > 3) profile = 1;        // default: LC
    if (sampleRateIndex < 0 || sampleRateIndex > 12) sampleRateIndex = 4;
    if (channels < 0 || channels > 7) channels = 2;
    if (frameLength < kHeaderBytesNoCrc ||
        frameLength > 8191) {  // 13-bit field limit
        frameLength = kHeaderBytesNoCrc;
    }
    out7[0] = 0xFF;
    out7[1] = 0xF1;  // MPEG-4, layer 0, protection absent
    out7[2] = static_cast<uint8_t>((profile << 6) | (sampleRateIndex << 2) |
                                   ((channels >> 2) & 0x1));
    out7[3] = static_cast<uint8_t>(((channels & 0x3) << 6) |
                                   ((frameLength >> 11) & 0x3));
    out7[4] = static_cast<uint8_t>((frameLength >> 3) & 0xFF);
    out7[5] = static_cast<uint8_t>(((frameLength & 0x7) << 5) |
                                   (0x7FF >> 6));  // fullness hi 5 bits
    out7[6] = static_cast<uint8_t>(((0x7FF & 0x3F) << 2) | 0x0);  // 1 raw block
}

}  // namespace streamify::audio
