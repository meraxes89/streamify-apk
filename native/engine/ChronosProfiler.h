#ifndef CHRONOS_PROFILER_H
#define CHRONOS_PROFILER_H

#include <array>
#include <vector>
#include <mutex>
#include <cstdint>
#include <string>

class ChronosProfiler {
public:
    static ChronosProfiler& getInstance();

    int getCurrentSlot(int64_t timestampMs) const;
    std::string getSlotName(int slot) const;
    float getTargetBpm(int slot) const;

    void updateTasteProfile(int64_t trackId, const std::vector<float>& trackVector, int64_t timestampMs);
    std::vector<float> getSlotVector(int slot) const;
    std::vector<float> getInterpolatedTasteVector(int64_t timestampMs) const;

    // Hoffman Satiation Decay & Dopamine Recovery Curve
    float calculateSatiationPenalty(int trackId, int64_t currentTimestampMs) const;

private:
    ChronosProfiler();
    ~ChronosProfiler() = default;
    ChronosProfiler(const ChronosProfiler&) = delete;
    ChronosProfiler& operator=(const ChronosProfiler&) = delete;

    // 4 time slots (Morning=0, Afternoon=1, Evening=2, Night=3), each 512-D
    std::array<std::array<float, 512>, 4> circadianVectors_{};
    mutable std::mutex profilerMutex_;
};

#endif // CHRONOS_PROFILER_H
