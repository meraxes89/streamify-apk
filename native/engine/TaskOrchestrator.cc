#include "TaskOrchestrator.h"
#include <unistd.h>
#include <thread>
#include <algorithm>
#include <fstream>
#include "../util/stlog.h"

#if defined(__linux__) || defined(__ANDROID__)
#include <sched.h>
#endif

// ==========================================
// 1. Hardware State Monitor Implementation
// ==========================================

HardwareStateMonitor& HardwareStateMonitor::getInstance() {
    static HardwareStateMonitor instance;
    return instance;
}

HardwareStateMonitor::HardwareStateMonitor() {
    m_monitorThread = std::thread(&HardwareStateMonitor::monitorLoop, this);
}

HardwareStateMonitor::~HardwareStateMonitor() {
    m_isRunning.store(false);
    if (m_monitorThread.joinable()) {
        m_monitorThread.join();
    }
}

void HardwareStateMonitor::monitorLoop() {
    while (m_isRunning.load()) {
        // Read sysfs thermal zone on Android SoC
        int readTemp = -1;
        for (int zone = 0; zone < 5; ++zone) {
            std::string path = "/sys/class/thermal/thermal_zone" + std::to_string(zone) + "/temp";
            std::ifstream file(path);
            if (file.is_open()) {
                int raw = 0;
                if (file >> raw && raw > 0) {
                    // Standard Android kernel writes temperature in millidegrees C (e.g. 42000 = 42C)
                    int deg = (raw > 1000) ? (raw / 1000) : raw;
                    if (deg > 10 && deg < 120) {
                        readTemp = deg;
                        break;
                    }
                }
            }
        }

        if (readTemp > 0) {
            m_cpuTempCelcius.store(readTemp, std::memory_order_relaxed);
        }

        std::this_thread::sleep_for(std::chrono::seconds(4));
    }
}

int HardwareStateMonitor::getCpuTemperature() {
    return m_cpuTempCelcius.load(std::memory_order_relaxed);
}

bool HardwareStateMonitor::isThermallyThrottled() {
    return m_cpuTempCelcius.load(std::memory_order_relaxed) >= 46;
}

void HardwareStateMonitor::setBatterySaverActive(bool active) {
    m_isBatterySaver.store(active, std::memory_order_relaxed);
}

bool HardwareStateMonitor::isBatterySaverActive() const {
    return m_isBatterySaver.load(std::memory_order_relaxed);
}

int HardwareStateMonitor::getDynamicYieldMs(TaskPriority priority) {
    if (priority == TaskPriority::QOS_IMMEDIATE_NOW_PLAYING) {
        // Instant priority: zero yield unless critically overheated
        return (m_cpuTempCelcius.load() > 58) ? 5 : 0;
    }

    if (priority == TaskPriority::QOS_SESSION_UP_NEXT) {
        if (m_cpuTempCelcius.load() > 50) return 30;
        if (m_cpuTempCelcius.load() > 42) return 15;
        return 5;
    }

    // QOS_BACKGROUND_BATCH
    int temp = m_cpuTempCelcius.load();
    if (temp > 50) return 60; // Heavy thermal backoff
    if (temp > 42) return 40; // Moderate thermal backoff
    if (m_isBatterySaver.load()) return 45; // Battery saver mode
    return 15; // Normal background yield
}

// ==========================================
// 2. Task Orchestrator Implementation
// ==========================================

TaskOrchestrator& TaskOrchestrator::getInstance() {
    static TaskOrchestrator instance;
    return instance;
}

TaskOrchestrator::TaskOrchestrator() {
    m_lastHighPriorityTime = std::chrono::steady_clock::now();

    int numWorkers = getOptimalWorkerThreads();
    for (int i = 0; i < numWorkers; ++i) {
        m_workerThreads.emplace_back(&TaskOrchestrator::workerLoop, this, i);
    }
}

TaskOrchestrator::~TaskOrchestrator() {
    m_isPoolRunning.store(false);
    m_cv.notify_all();
    for (auto& t : m_workerThreads) {
        if (t.joinable()) {
            t.join();
        }
    }
}

void TaskOrchestrator::setHighPriorityActive(bool active) {
    m_highPriorityActive.store(active, std::memory_order_release);
    if (active) {
        m_lastHighPriorityTime = std::chrono::steady_clock::now();
    }
}

bool TaskOrchestrator::isHighPriorityActive() const {
    if (m_highPriorityActive.load(std::memory_order_acquire)) {
        return true;
    }
    auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - m_lastHighPriorityTime
    ).count();
    return elapsed < 1500;
}

void TaskOrchestrator::setBatterySaverActive(bool active) {
    HardwareStateMonitor::getInstance().setBatterySaverActive(active);
}

bool TaskOrchestrator::shouldYield(TaskPriority priority) {
    if (priority == TaskPriority::QOS_IMMEDIATE_NOW_PLAYING) {
        return false;
    }
    return isHighPriorityActive() || HardwareStateMonitor::getInstance().isThermallyThrottled();
}

void TaskOrchestrator::cooperativeYield(TaskPriority priority) {
    if (priority == TaskPriority::QOS_IMMEDIATE_NOW_PLAYING) {
        std::this_thread::yield();
        return;
    }

    if (shouldYield(priority)) {
        int sleepMs = HardwareStateMonitor::getInstance().getDynamicYieldMs(priority);
        std::this_thread::sleep_for(std::chrono::milliseconds(sleepMs));
    } else {
        std::this_thread::yield();
    }
}

int TaskOrchestrator::submitTask(const std::string& name, std::function<void()> taskFunc, TaskPriority priority, std::shared_ptr<std::atomic<bool>> isCancelled) {
    int taskId = m_nextTaskId.fetch_add(1, std::memory_order_relaxed);
    Task t{
        taskId,
        name,
        taskFunc,
        priority,
        isCancelled
    };

    int qIdx = static_cast<int>(priority);
    if (qIdx < 0 || qIdx > 2) qIdx = 2;

    {
        std::lock_guard<std::mutex> lock(m_queueMutex);
        m_queues[qIdx].push(t);
    }

    m_activeAiTasks.fetch_add(1, std::memory_order_relaxed);
    m_cv.notify_one();
    return taskId;
}

void TaskOrchestrator::cancelTask(int /*taskId*/) {
    // Flagged tasks are checked at pop time and immediately dropped
}

void TaskOrchestrator::workerLoop(int workerId) {
    // Pin background workers to efficiency LITTLE cores (0-3) on ARM architectures
    pinThreadToLittleCores();

    while (m_isPoolRunning.load()) {
        Task currentTask;
        bool found = false;

        {
            std::unique_lock<std::mutex> lock(m_queueMutex);
            m_cv.wait(lock, [this]() {
                return !m_isPoolRunning.load() ||
                       !m_queues[0].empty() ||
                       !m_queues[1].empty() ||
                       !m_queues[2].empty();
            });

            if (!m_isPoolRunning.load()) break;

            // 1. Strict QoS Priority Drain: 0 (Immediate) -> 1 (Session) -> 2 (Background)
            for (int i = 0; i < 3; ++i) {
                if (!m_queues[i].empty()) {
                    currentTask = m_queues[i].front();
                    m_queues[i].pop();
                    found = true;
                    break;
                }
            }
        }

        if (found) {
            // Check cancellation flag
            if (currentTask.isCancelled && currentTask.isCancelled->load()) {
                m_activeAiTasks.fetch_sub(1, std::memory_order_relaxed);
                continue;
            }

            {
                std::lock_guard<std::mutex> lock(m_statusMutex);
                m_currentAction = "Processing (" + std::to_string(workerId + 1) + "): " + currentTask.name;
            }

            if (currentTask.func) {
                currentTask.func();
            }

            m_activeAiTasks.fetch_sub(1, std::memory_order_relaxed);
            m_completedAiTasks.fetch_add(1, std::memory_order_relaxed);

            {
                std::lock_guard<std::mutex> lock(m_statusMutex);
                if (m_activeAiTasks.load() <= 0) {
                    m_currentAction = "Idle (Neural Store Synchronized)";
                }
            }
        }
    }
}

bool TaskOrchestrator::pinCurrentThreadToLittleCores() {
#if defined(__linux__) || defined(__ANDROID__)
    cpu_set_t cpuset;
    CPU_ZERO(&cpuset);
    CPU_SET(0, &cpuset);
    CPU_SET(1, &cpuset);
    CPU_SET(2, &cpuset);
    CPU_SET(3, &cpuset);
    return sched_setaffinity(0, sizeof(cpu_set_t), &cpuset) == 0;
#else
    return false;
#endif
}

void TaskOrchestrator::pinThreadToLittleCores() {
    pinCurrentThreadToLittleCores();
}

void TaskOrchestrator::pinThreadToBigCores() {
#if defined(__linux__) || defined(__ANDROID__)
    cpu_set_t cpuset;
    CPU_ZERO(&cpuset);
    CPU_SET(4, &cpuset);
    CPU_SET(5, &cpuset);
    CPU_SET(6, &cpuset);
    CPU_SET(7, &cpuset);
    sched_setaffinity(0, sizeof(cpu_set_t), &cpuset);
#endif
}

void TaskOrchestrator::notifyAiTaskStarted(const std::string& trackName) {
    m_activeAiTasks.fetch_add(1, std::memory_order_relaxed);
    std::lock_guard<std::mutex> lock(m_statusMutex);
    m_currentAction = "Processing: " + trackName;
}

void TaskOrchestrator::notifyAiTaskCompleted() {
    m_activeAiTasks.fetch_sub(1, std::memory_order_relaxed);
    m_completedAiTasks.fetch_add(1, std::memory_order_relaxed);
    std::lock_guard<std::mutex> lock(m_statusMutex);
    if (m_activeAiTasks.load() <= 0) {
        m_currentAction = "Idle (Neural Store Synchronized)";
    }
}

void TaskOrchestrator::setTotalAiTasks(int total) {
    m_totalAiTasks.store(total, std::memory_order_relaxed);
}

int TaskOrchestrator::getOptimalWorkerThreads() {
    long numCores = sysconf(_SC_NPROCESSORS_ONLN);
    if (numCores <= 0) {
        numCores = std::thread::hardware_concurrency();
    }
    if (numCores <= 2) return 1;
    if (numCores <= 4) return 1;
    // On 8-core Big.LITTLE architectures, reserve at most 2 cores for AI ingestion
    return std::min(2, static_cast<int>(numCores / 4));
}

OrchestratorMetrics TaskOrchestrator::getMetrics() {
    OrchestratorMetrics m;
    auto& hw = HardwareStateMonitor::getInstance();

    m.activeAiTasks = m_activeAiTasks.load(std::memory_order_relaxed);
    m.completedAiTasks = m_completedAiTasks.load(std::memory_order_relaxed);
    m.totalAiTasks = m_totalAiTasks.load(std::memory_order_relaxed);
    m.isThrottled = isHighPriorityActive();
    m.cpuTemp = hw.getCpuTemperature();
    m.isThermallyThrottled = hw.isThermallyThrottled();
    m.isBatterySaver = hw.isBatterySaverActive();

    if (m.isThermallyThrottled) {
        m.cpuCoreBudget = 15;
    } else if (m.isThrottled) {
        m.cpuCoreBudget = 25;
    } else if (m.isBatterySaver) {
        m.cpuCoreBudget = 35;
    } else {
        m.cpuCoreBudget = 60;
    }

    m.activeThreads = m.activeAiTasks > 0 ? getOptimalWorkerThreads() : 0;

    {
        std::lock_guard<std::mutex> lock(m_statusMutex);
        m.currentAction = m_currentAction;
    }

    if (m.activeAiTasks > 0) {
        if (m.isThermallyThrottled) {
            m.state = "THERMAL_THROTTLED";
        } else if (m.isThrottled) {
            m.state = "THROTTLED_UI_PRIORITY";
        } else {
            m.state = "AI_EMBEDDING_ACTIVE";
        }
    } else {
        m.state = "IDLE";
        m.currentAction = "Idle (Low Power Mode)";
    }

    return m;
}
