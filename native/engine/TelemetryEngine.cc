#include "TelemetryEngine.h"
#include "StreamifyDB.h"
#include "../util/stlog.h"
#include <algorithm>
#include <cmath>

#define LOG_TAG "TelemetryEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

TelemetryEngine& TelemetryEngine::getInstance() {
    static TelemetryEngine instance;
    return instance;
}

TelemetryEngine::TelemetryEngine() {
    startProcessing();
}

TelemetryEngine::~TelemetryEngine() {
    stopProcessing();
}

void TelemetryEngine::startProcessing() {
    if (!isRunning_.exchange(true)) {
        workerThread_ = std::thread(&TelemetryEngine::consumerLoop, this);
    }
}

void TelemetryEngine::stopProcessing() {
    if (isRunning_.exchange(false)) {
        if (workerThread_.joinable()) {
            workerThread_.join();
        }
    }
}

void TelemetryEngine::pushEvent(TelemetryEventType type, int64_t trackId, float value) {
    int64_t now = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::system_clock::now().time_since_epoch()
    ).count();

    TelemetryEvent event{type, trackId, value, now};
    queue_.push(event);
}

void TelemetryEngine::consumerLoop() {
    while (isRunning_.load(std::memory_order_relaxed)) {
        bool processedAny = false;

        while (auto optEvent = queue_.pop()) {
            processedAny = true;
            const auto& ev = *optEvent;

            switch (ev.type) {
                case TelemetryEventType::SCRUB_SEEK: {
                    int64_t seekMs = static_cast<int64_t>(ev.value);
                    if (ev.trackId > 0 && seekMs > 5000) {
                        // Drop hunting: check if user repeatedly seeks to a cluster within +/- 4000ms
                        bool foundCluster = false;
                        for (auto& cluster : recentSeeks_) {
                            if (cluster.trackId == ev.trackId && std::abs(cluster.seekMs - seekMs) < 4000) {
                                cluster.count++;
                                cluster.seekMs = (cluster.seekMs + seekMs) / 2; // Refine hook center
                                if (cluster.count >= 2) {
                                    StreamifyDB::getInstance().logHookTelemetry(static_cast<int>(ev.trackId), cluster.seekMs, 0, 0);
                                }
                                foundCluster = true;
                                break;
                            }
                        }

                        if (!foundCluster) {
                            recentSeeks_.push_back({ev.trackId, seekMs, 1});
                            if (recentSeeks_.size() > 50) {
                                recentSeeks_.erase(recentSeeks_.begin());
                            }
                            StreamifyDB::getInstance().logHookTelemetry(static_cast<int>(ev.trackId), seekMs, 0, 0);
                        }
                    }
                    break;
                }
                case TelemetryEventType::VOLUME_CHANGE: {
                    if (ev.trackId > 0 && ev.value > 0.85f) {
                        // Volume flare emotional spike
                        StreamifyDB::getInstance().logHookTelemetry(static_cast<int>(ev.trackId), 0, 0, 1);
                        
                        // Adaptive target loudness adjustment: user wants louder output
                        float curLufs = targetLufs_.load(std::memory_order_relaxed);
                        float nextLufs = std::min(-10.0f, curLufs + 1.0f); // Boost target loudness up to -10 LUFS
                        targetLufs_.store(nextLufs, std::memory_order_relaxed);
                    }
                    break;
                }
                case TelemetryEventType::LYRICS_DWELL: {
                    int dwellSec = static_cast<int>(ev.value);
                    if (ev.trackId > 0 && dwellSec > 0) {
                        StreamifyDB::getInstance().logHookTelemetry(static_cast<int>(ev.trackId), 0, dwellSec, 0);
                    }
                    break;
                }
                case TelemetryEventType::PLAY_TRANSITION: {
                    int fromTrack = static_cast<int>(ev.trackId);
                    int toTrack = static_cast<int>(ev.value);
                    if (fromTrack > 0 && toTrack > 0 && fromTrack != toTrack) {
                        StreamifyDB::getInstance().recordTrackCooccurrence(fromTrack, toTrack);
                    }
                    break;
                }
                case TelemetryEventType::HEARTBEAT:
                default:
                    break;
            }
        }

        if (!processedAny) {
            std::this_thread::sleep_for(std::chrono::milliseconds(50));
        }
    }
}

float TelemetryEngine::getDynamicTargetLufs() const {
    return targetLufs_.load(std::memory_order_relaxed);
}

// Compact, self-contained FIPS 180-2 SHA-256 implementation for Proof-of-Compute
namespace {
    inline uint32_t rotr(uint32_t x, uint32_t n) { return (x >> n) | (x << (32 - n)); }
    inline uint32_t ch(uint32_t x, uint32_t y, uint32_t z) { return (x & y) ^ (~x & z); }
    inline uint32_t maj(uint32_t x, uint32_t y, uint32_t z) { return (x & y) ^ (x & z) ^ (y & z); }
    inline uint32_t sigma0(uint32_t x) { return rotr(x, 2) ^ rotr(x, 13) ^ rotr(x, 22); }
    inline uint32_t sigma1(uint32_t x) { return rotr(x, 6) ^ rotr(x, 11) ^ rotr(x, 25); }
    inline uint32_t gamma0(uint32_t x) { return rotr(x, 7) ^ rotr(x, 18) ^ (x >> 3); }
    inline uint32_t gamma1(uint32_t x) { return rotr(x, 17) ^ rotr(x, 19) ^ (x >> 10); }

    static const uint32_t K[64] = {
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
        0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
        0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
        0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
    };

    void sha256_process_block(uint32_t state[8], const uint8_t block[64]) {
        uint32_t W[64];
        for (int t = 0; t < 16; ++t) {
            W[t] = (static_cast<uint32_t>(block[t * 4]) << 24) |
                   (static_cast<uint32_t>(block[t * 4 + 1]) << 16) |
                   (static_cast<uint32_t>(block[t * 4 + 2]) << 8) |
                   (static_cast<uint32_t>(block[t * 4 + 3]));
        }
        for (int t = 16; t < 64; ++t) {
            W[t] = gamma1(W[t - 2]) + W[t - 7] + gamma0(W[t - 15]) + W[t - 16];
        }

        uint32_t a = state[0], b = state[1], c = state[2], d = state[3];
        uint32_t e = state[4], f = state[5], g = state[6], h = state[7];

        for (int t = 0; t < 64; ++t) {
            uint32_t T1 = h + sigma1(e) + ch(e, f, g) + K[t] + W[t];
            uint32_t T2 = sigma0(a) + maj(a, b, c);
            h = g; g = f; f = e; e = d + T1;
            d = c; c = b; b = a; a = T1 + T2;
        }

        state[0] += a; state[1] += b; state[2] += c; state[3] += d;
        state[4] += e; state[5] += f; state[6] += g; state[7] += h;
    }
}

std::string TelemetryEngine::generateProofOfCompute(const float* pcm, int length, const std::string& nonce) {
    if (!pcm || length <= 0) return "";

    // 1. Pack PCM sample bytes (up to 1024 floats = 4096 bytes) + nonce
    int sampleCount = std::min(length, 1024);
    std::vector<uint8_t> payload;
    payload.reserve((sampleCount * sizeof(float)) + nonce.size());

    const uint8_t* pcmBytes = reinterpret_cast<const uint8_t*>(pcm);
    payload.insert(payload.end(), pcmBytes, pcmBytes + (sampleCount * sizeof(float)));
    payload.insert(payload.end(), nonce.begin(), nonce.end());

    // 2. Pad to 512-bit block boundary
    uint64_t bitLength = payload.size() * 8ULL;
    payload.push_back(0x80);
    while ((payload.size() % 64) != 56) {
        payload.push_back(0x00);
    }
    for (int i = 7; i >= 0; --i) {
        payload.push_back(static_cast<uint8_t>((bitLength >> (i * 8)) & 0xFF));
    }

    // 3. Compute SHA-256 state
    uint32_t state[8] = {
        0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
        0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19
    };

    for (size_t offset = 0; offset < payload.size(); offset += 64) {
        sha256_process_block(state, &payload[offset]);
    }

    // 4. Format hex output
    char hexOutput[65];
    for (int i = 0; i < 8; ++i) {
        snprintf(hexOutput + (i * 8), 9, "%08x", state[i]);
    }
    hexOutput[64] = '\0';
    return std::string(hexOutput);
}
