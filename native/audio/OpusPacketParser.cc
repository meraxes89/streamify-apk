// ============================================================================
//  OpusPacketParser.cc — RFC 6716 header accounting (see .h for the spec
//  ladder). Every read is bounds-checked before it happens.
// ============================================================================

#include "OpusPacketParser.h"

namespace streamify::audio {

// Per-config frame duration in ms (RFC 6716 §3.1 Table 2) and the 48 kHz
// sample equivalents (duration * 48). Index = TOC config.
namespace {
constexpr float kConfigMs[32] = {
    // SILK NB / MB / WB (10, 20, 40, 60 ms each)
    10.0f, 20.0f, 40.0f, 60.0f, 10.0f, 20.0f, 40.0f, 60.0f,
    10.0f, 20.0f, 40.0f, 60.0f,
    // Hybrid SWB / FB (10, 20 ms)
    10.0f, 20.0f, 10.0f, 20.0f,
    // CELT NB / WB / SWB / FB (2.5, 5, 10, 20 ms each)
    2.5f, 5.0f, 10.0f, 20.0f, 2.5f, 5.0f, 10.0f, 20.0f,
    2.5f, 5.0f, 10.0f, 20.0f, 2.5f, 5.0f, 10.0f, 20.0f,
};
constexpr int32_t kConfigSamples[32] = {
    480, 960, 1920, 2880, 480, 960, 1920, 2880, 480, 960, 1920, 2880,
    480, 960, 480, 960,
    120, 240, 480, 960, 120, 240, 480, 960,
    120, 240, 480, 960, 120, 240, 480, 960,
};
}  // namespace

float OpusPacketParser::configFrameSizeMs(int32_t config) {
    if (config < 0 || config > 31) {
        return 0.0f;
    }
    return kConfigMs[static_cast<size_t>(config)];
}

int32_t OpusPacketParser::configSamplesAt48k(int32_t config) {
    if (config < 0 || config > 31) {
        return 0;
    }
    return kConfigSamples[static_cast<size_t>(config)];
}

int32_t OpusPacketParser::tocChannels(const uint8_t* data, size_t len) {
    if (data == nullptr || len == 0) {
        return 1;
    }
    return ((data[0] >> 2) & 0x1) != 0 ? 2 : 1;
}

ParseStatus OpusPacketParser::parse(const uint8_t* data, size_t len,
                                    OpusFrameInfo* outInfo) {
    if (outInfo == nullptr) {
        return ParseStatus::kInvalidArgument;
    }
    *outInfo = OpusFrameInfo{};
    if (data == nullptr && len != 0) {
        return ParseStatus::kInvalidArgument;
    }
    if (len == 0) {
        return ParseStatus::kTooSmall;
    }

    const uint8_t toc = data[0];
    const int32_t config = (toc >> 3) & 0x1F;
    const bool stereo = ((toc >> 2) & 0x1) != 0;
    const int code = toc & 0x3;

    outInfo->config = config;
    outInfo->frameSizeMs = configFrameSizeMs(config);
    outInfo->channels = stereo ? 2 : 1;

    int32_t frames = 0;
    int32_t largest = 0;

    switch (code) {
    case 0: {
        // One frame occupying the rest of the packet.
        frames = 1;
        const int32_t size = static_cast<int32_t>(len) - 1;
        if (size < 0) {
            return ParseStatus::kBadFrameLength;
        }
        if (size > OpusPacketParser::kMaxOpusFrameBytes) {
            return ParseStatus::kBadFrameLength;
        }
        largest = size;
        break;
    }
    case 1: {
        // Two VBR frames: 1 size byte.
        if (len < 3) {
            return ParseStatus::kTooSmall;  // TOC + size + at least 1 payload
        }
        frames = 2;
        outInfo->vbr = true;
        const int32_t size1 = data[1];
        const int32_t size2 = static_cast<int32_t>(len) - 2 - size1;
        if (size2 < 0) {
            return ParseStatus::kBadFrameLength;  // size1 exceeds payload
        }
        // Only size2 needs the frame-ceiling bound: size1 is a single
        // length byte (<= 255) and can never exceed the 1275-byte Opus
        // frame ceiling. (The dead size1 disjunct tripped CodeQL.)
        if (size2 > OpusPacketParser::kMaxOpusFrameBytes) {
            return ParseStatus::kBadFrameLength;
        }
        largest = size1 > size2 ? size1 : size2;
        break;
    }
    case 2: {
        // Two CBR frames: payload must halve evenly.
        if (len < 2) {
            return ParseStatus::kTooSmall;
        }
        frames = 2;
        const int32_t payload = static_cast<int32_t>(len) - 1;
        if ((payload & 1) != 0) {
            return ParseStatus::kBadFrameLength;  // odd payload cannot halve
        }
        const int32_t half = payload / 2;
        if (half > OpusPacketParser::kMaxOpusFrameBytes) {
            return ParseStatus::kBadFrameLength;
        }
        largest = half;
        break;
    }
    default: {
        // Code 3: count byte (+ optional padding run, + optional VBR run).
        if (len < 2) {
            return ParseStatus::kTooSmall;
        }
        const uint8_t countByte = data[1];
        const int32_t count = countByte & 0x3F;
        const bool padding = (countByte & 0x40) != 0;
        const bool vbr = (countByte & 0x80) != 0;
        if (count < 1 || count > OpusPacketParser::kMaxFramesPerPacket) {
            return ParseStatus::kBadHeader;
        }
        frames = count;
        outInfo->vbr = vbr;
        outInfo->hasPadding = padding;

        size_t pos = 2;
        int32_t paddingLen = 0;
        if (padding) {
            // 255/254 extension run, exactly as libopus reads it.
            for (;;) {
                if (pos >= len) {
                    return ParseStatus::kBadFrameLength;
                }
                const uint8_t b = data[pos++];
                paddingLen += (b == 255) ? 254 : b;
                if (b != 255) {
                    break;
                }
            }
            outInfo->paddingBytes = paddingLen;
        }

        const int64_t remaining =
            static_cast<int64_t>(len) - static_cast<int64_t>(pos) -
            static_cast<int64_t>(paddingLen);
        if (remaining < 0) {
            return ParseStatus::kBadFrameLength;  // padding overruns packet
        }

        if (vbr) {
            int64_t sum = 0;
            for (int32_t i = 0; i < count - 1; ++i) {
                int32_t size = 0;
                for (;;) {
                    if (pos >= len) {
                        return ParseStatus::kBadFrameLength;
                    }
                    const uint8_t b = data[pos++];
                    size += (b == 255) ? 254 : b;
                    if (b != 255) {
                        break;
                    }
                }
                if (size > OpusPacketParser::kMaxOpusFrameBytes) {
                    return ParseStatus::kBadFrameLength;
                }
                if (size > largest) {
                    largest = size;
                }
                sum += size;
            }
            const int64_t last = remaining - sum;
            if (last < 0) {
                return ParseStatus::kBadFrameLength;  // sizes exceed payload
            }
            if (last > OpusPacketParser::kMaxOpusFrameBytes) {
                return ParseStatus::kBadFrameLength;
            }
            if (static_cast<int32_t>(last) > largest) {
                largest = static_cast<int32_t>(last);
            }
        } else {
            // CBR: remainder must divide evenly into `count` frames.
            if (remaining % count != 0) {
                return ParseStatus::kBadFrameLength;
            }
            const int64_t per = remaining / count;
            if (per > OpusPacketParser::kMaxOpusFrameBytes) {
                return ParseStatus::kBadFrameLength;
            }
            largest = static_cast<int32_t>(per);
        }
        break;
    }
    }

    outInfo->frameCount = frames;
    outInfo->largestFrameBytes = largest;
    outInfo->totalDurationMs =
        static_cast<float>(frames) * outInfo->frameSizeMs;
    outInfo->samplesAt48k = frames * configSamplesAt48k(config);
    return ParseStatus::kOk;
}

int64_t OpusPacketParser::resyncToc(const uint8_t* data, size_t len,
                                    size_t from) {
    if (data == nullptr) {
        return -1;
    }
    if (from > len) {
        return -1;
    }
    OpusFrameInfo info{};
    for (size_t i = from; i < len; ++i) {
        // Cheap pre-filter: config must be valid (always is) — the real
        // check is the structural parse at this offset.
        if (parse(data + i, len - i, &info) == ParseStatus::kOk) {
            return static_cast<int64_t>(i);
        }
    }
    return -1;
}

}  // namespace streamify::audio
