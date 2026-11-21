#include "ChronosProfiler.h"
#include "StreamifyDB.h"
#include <cmath>
#include <ctime>
#include <algorithm>

#if defined(__ARM_NEON) || defined(__aarch64__)
#include <arm_neon.h>
#endif

ChronosProfiler& ChronosProfiler::getInstance() {
    static ChronosProfiler instance;
    return instance;
}

ChronosProfiler::ChronosProfiler() {
    std::lock_guard<std::mutex> lock(profilerMutex_);
    for (int s = 0; s < 4; ++s) {
        circadianVectors_[s].fill(0.0f);
    }
}

int ChronosProfiler::getCurrentSlot(int64_t timestampMs) const {
    std::time_t rawTime = static_cast<std::time_t>(timestampMs / 1000);
    std::tm* timeInfo = std::localtime(&rawTime);
    int hour = timeInfo ? timeInfo->tm_hour : 12;

    if (hour >= 6 && hour < 11) return 0;  // Morning
    if (hour >= 11 && hour < 17) return 1; // Afternoon
    if (hour >= 17 && hour < 22) return 2; // Evening
    return 3; // Night
}

std::string ChronosProfiler::getSlotName(int slot) const {
    switch (slot) {
        case 0: return "MORNING";
        case 1: return "AFTERNOON";
        case 2: return "EVENING";
        default: return "NIGHT";
    }
}

float ChronosProfiler::getTargetBpm(int slot) const {
    switch (slot) {
        case 0: return 130.0f; // High-energy morning
        case 1: return 85.0f;  // Lo-Fi focus afternoon
        case 2: return 118.0f; // Upbeat golden hour evening
        default: return 95.0f; // Deep chill night
    }
}

void ChronosProfiler::updateTasteProfile(int64_t /* trackId */, const std::vector<float>& trackVector, int64_t timestampMs) {
    if (trackVector.size() < 512) return;

    int slot = getCurrentSlot(timestampMs);
    float alpha = 0.08f; // Learning rate

    std::lock_guard<std::mutex> lock(profilerMutex_);

#if defined(__ARM_NEON) || defined(__aarch64__)
    // ARM NEON SIMD accelerated vector EMA
    for (int i = 0; i < 512; i += 4) {
        float32x4_t v_track = vld1q_f32(&trackVector[i]);
        float32x4_t v_slot = vld1q_f32(&circadianVectors_[slot][i]);
        float32x4_t v_decayed = vmulq_n_f32(v_slot, 1.0f - alpha);
        float32x4_t v_new = vmlaq_n_f32(v_decayed, v_track, alpha);
        vst1q_f32(&circadianVectors_[slot][i], v_new);
    }
#else
    for (int i = 0; i < 512; ++i) {
        circadianVectors_[slot][i] = (circadianVectors_[slot][i] * (1.0f - alpha)) + (trackVector[i] * alpha);
    }
#endif

    // Re-normalize vector centroid
    float normSq = 0.0f;
    for (int i = 0; i < 512; ++i) {
        normSq += circadianVectors_[slot][i] * circadianVectors_[slot][i];
    }
    if (normSq > 1e-7f) {
        float invNorm = 1.0f / std::sqrt(normSq);
        for (int i = 0; i < 512; ++i) {
            circadianVectors_[slot][i] *= invNorm;
        }
    }
}

std::vector<float> ChronosProfiler::getSlotVector(int slot) const {
    std::lock_guard<std::mutex> lock(profilerMutex_);
    int s = std::clamp(slot, 0, 3);
    return std::vector<float>(circadianVectors_[s].begin(), circadianVectors_[s].end());
}

std::vector<float> ChronosProfiler::getInterpolatedTasteVector(int64_t timestampMs) const {
    std::time_t rawTime = static_cast<std::time_t>(timestampMs / 1000);
    std::tm* timeInfo = std::localtime(&rawTime);
    int hour = timeInfo ? timeInfo->tm_hour : 12;
    int minute = timeInfo ? timeInfo->tm_min : 0;

    int currentSlot = getCurrentSlot(timestampMs);
    int nextSlot = (currentSlot + 1) % 4;

    struct Boundary { int endHour; int slotIdx; };
    Boundary boundaries[] = {
        {11, 0}, // Morning -> Afternoon
        {17, 1}, // Afternoon -> Evening
        {22, 2}, // Evening -> Night
        {6,  3}  // Night -> Morning
    };

    int transitionHour = boundaries[currentSlot].endHour;
    float weight = 0.0f;

    // Check if within 30-minute crossfade window (starts 30 mins before boundary)
    int checkHour = (transitionHour == 0) ? 23 : (transitionHour - 1);
    if (hour == checkHour && minute >= 30) {
        float t = (minute - 30.0f) / 30.0f; // 0.0 to 1.0
        float sinVal = std::sin(t * (3.14159265358979323846f / 2.0f));
        weight = sinVal * sinVal; // sin^2(theta * pi/2)
    }

    std::lock_guard<std::mutex> lock(profilerMutex_);
    std::vector<float> result(512, 0.0f);

    if (weight > 0.0f) {
        float wA = 1.0f - weight;
        float wB = weight;
#if defined(__ARM_NEON) || defined(__aarch64__)
        for (int i = 0; i < 512; i += 4) {
            float32x4_t vA = vld1q_f32(&circadianVectors_[currentSlot][i]);
            float32x4_t vB = vld1q_f32(&circadianVectors_[nextSlot][i]);
            float32x4_t v_res = vmlaq_n_f32(vmulq_n_f32(vA, wA), vB, wB);
            vst1q_f32(&result[i], v_res);
        }
#else
        for (int i = 0; i < 512; ++i) {
            result[i] = (circadianVectors_[currentSlot][i] * wA) + (circadianVectors_[nextSlot][i] * wB);
        }
#endif
    } else {
        std::copy(circadianVectors_[currentSlot].begin(), circadianVectors_[currentSlot].end(), result.begin());
    }

    return result;
}

float ChronosProfiler::calculateSatiationPenalty(int trackId, int64_t currentTimestampMs) const {
    if (trackId <= 0) return 0.0f;

    // Direct SQLite check: count plays in last 72 hours
    float basePenalty = StreamifyDB::getInstance().getTrackSatiationPenalty(trackId);
    if (basePenalty > 0.0f) {
        return basePenalty;
    }

    return 0.0f;
}
