#ifndef TELEMETRY_ENGINE_H
#define TELEMETRY_ENGINE_H

#include <atomic>
#include <array>
#include <optional>
#include <thread>
#include <chrono>
#include <vector>
#include <cstdint>

enum class TelemetryEventType : int {
    SCRUB_SEEK = 1,
    VOLUME_CHANGE = 2,
    LYRICS_DWELL = 3,
    PLAY_TRANSITION = 4,
    HEARTBEAT = 5
};

struct TelemetryEvent {
    TelemetryEventType type{TelemetryEventType::HEARTBEAT};
    int64_t trackId{0};
    float value{0.0f}; // e.g. seek progress (ms), volume level (0.0-1.0), dwell seconds
    int64_t timestampMs{0};
};

// Dmitry Vyukov Lock-Free Multi-Producer Multi-Consumer (MPMC) Bounded Ring Buffer
template<typename T, size_t Capacity>
class VyukovMPMCQueue {
private:
    struct Cell {
        std::atomic<size_t> sequence;
        T data;
    };

    alignas(64) std::array<Cell, Capacity> buffer_;
    alignas(64) std::atomic<size_t> enqueuePos_{0};
    alignas(64) std::atomic<size_t> dequeuePos_{0};

public:
    VyukovMPMCQueue() {
        for (size_t i = 0; i < Capacity; ++i) {
            buffer_[i].sequence.store(i, std::memory_order_relaxed);
        }
    }

    bool push(const T& item) {
        Cell* cell;
        size_t pos = enqueuePos_.load(std::memory_order_relaxed);
        while (true) {
            cell = &buffer_[pos % Capacity];
            size_t seq = cell->sequence.load(std::memory_order_acquire);
            intptr_t diff = static_cast<intptr_t>(seq) - static_cast<intptr_t>(pos);
            if (diff == 0) {
                if (enqueuePos_.compare_exchange_weak(pos, pos + 1, std::memory_order_relaxed)) {
                    break;
                }
            } else if (diff < 0) {
                return false; // Queue full
            } else {
                pos = enqueuePos_.load(std::memory_order_relaxed);
            }
        }
        cell->data = item;
        cell->sequence.store(pos + 1, std::memory_order_release);
        return true;
    }

    std::optional<T> pop() {
        Cell* cell;
        size_t pos = dequeuePos_.load(std::memory_order_relaxed);
        while (true) {
            cell = &buffer_[pos % Capacity];
            size_t seq = cell->sequence.load(std::memory_order_acquire);
            intptr_t diff = static_cast<intptr_t>(seq) - static_cast<intptr_t>(pos + 1);
            if (diff == 0) {
                if (dequeuePos_.compare_exchange_weak(pos, pos + 1, std::memory_order_relaxed)) {
                    break;
                }
            } else if (diff < 0) {
                return std::nullopt; // Queue empty
            } else {
                pos = dequeuePos_.load(std::memory_order_relaxed);
            }
        }
        T item = cell->data;
        cell->sequence.store(pos + Capacity, std::memory_order_release);
        return item;
    }

    bool isEmpty() const {
        size_t dPos = dequeuePos_.load(std::memory_order_relaxed);
        size_t ePos = enqueuePos_.load(std::memory_order_relaxed);
        return dPos >= ePos;
    }
};

class TelemetryEngine {
public:
    static TelemetryEngine& getInstance();

    void pushEvent(TelemetryEventType type, int64_t trackId, float value);
    void startProcessing();
    void stopProcessing();

    float getDynamicTargetLufs() const;
    std::string generateProofOfCompute(const float* pcm, int length, const std::string& nonce);

private:
    TelemetryEngine();
    ~TelemetryEngine();
    TelemetryEngine(const TelemetryEngine&) = delete;
    TelemetryEngine& operator=(const TelemetryEngine&) = delete;

    void consumerLoop();

    VyukovMPMCQueue<TelemetryEvent, 1024> queue_;
    std::atomic<bool> isRunning_{false};
    std::atomic<float> targetLufs_{-14.0f}; // Default EBU R128 target (-14 LUFS)
    std::thread workerThread_;

    // Drop hunting memory buffer per track
    struct SeekCluster {
        int64_t trackId{0};
        int64_t seekMs{0};
        int count{0};
    };
    std::vector<SeekCluster> recentSeeks_;
};

#endif // TELEMETRY_ENGINE_H
