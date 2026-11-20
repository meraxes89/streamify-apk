#ifndef STREAMIFY_TASK_ORCHESTRATOR_H
#define STREAMIFY_TASK_ORCHESTRATOR_H

#include <string>
#include <vector>
#include <queue>
#include <functional>
#include <atomic>
#include <mutex>
#include <condition_variable>
#include <thread>
#include <chrono>
#include <memory>

enum class TaskPriority {
    QOS_IMMEDIATE_NOW_PLAYING = 0, // Instant priority: current track analysis (<50ms)
    QOS_SESSION_UP_NEXT = 1,       // High priority: next 3 songs in autoplay queue
    QOS_BACKGROUND_BATCH = 2       // Normal priority: bulk library downloads / scanner
};

struct Task {
    int id;
    std::string name;
    std::function<void()> func;
    TaskPriority priority;
    std::shared_ptr<std::atomic<bool>> isCancelled;
};

struct OrchestratorMetrics {
    std::string state;
    std::string currentAction;
    int activeAiTasks;
    int completedAiTasks;
    int totalAiTasks;
    int cpuCoreBudget;
    int activeThreads;
    bool isThrottled;
    int cpuTemp;
    bool isThermallyThrottled;
    bool isBatterySaver;
};

class HardwareStateMonitor {
public:
    static HardwareStateMonitor& getInstance();

    int getCpuTemperature();
    bool isThermallyThrottled();
    void setBatterySaverActive(bool active);
    bool isBatterySaverActive() const;
    int getDynamicYieldMs(TaskPriority priority);

private:
    HardwareStateMonitor();
    ~HardwareStateMonitor();

    void monitorLoop();

    std::atomic<int> m_cpuTempCelcius{35};
    std::atomic<bool> m_isBatterySaver{false};
    std::atomic<bool> m_isRunning{true};
    std::thread m_monitorThread;
};

class TaskOrchestrator {
public:
    static TaskOrchestrator& getInstance();

    void setHighPriorityActive(bool active);
    bool isHighPriorityActive() const;

    void setBatterySaverActive(bool active);

    bool shouldYield(TaskPriority priority = TaskPriority::QOS_BACKGROUND_BATCH);
    void cooperativeYield(TaskPriority priority = TaskPriority::QOS_BACKGROUND_BATCH);

    int submitTask(const std::string& name, std::function<void()> task, TaskPriority priority = TaskPriority::QOS_BACKGROUND_BATCH, std::shared_ptr<std::atomic<bool>> isCancelled = nullptr);
    void cancelTask(int taskId);

    void notifyAiTaskStarted(const std::string& trackName);
    void notifyAiTaskCompleted();
    void setTotalAiTasks(int total);

    OrchestratorMetrics getMetrics();
    int getOptimalWorkerThreads();

    static bool pinCurrentThreadToLittleCores();
    void pinThreadToLittleCores();
    void pinThreadToBigCores();

private:
    TaskOrchestrator();
    ~TaskOrchestrator();

    void workerLoop(int workerId);

    std::atomic<bool> m_highPriorityActive{false};
    std::atomic<int> m_activeAiTasks{0};
    std::atomic<int> m_completedAiTasks{0};
    std::atomic<int> m_totalAiTasks{0};
    std::atomic<int> m_nextTaskId{1};
    std::atomic<bool> m_isPoolRunning{true};

    // 3 Strict Priority Queues (0: Immediate, 1: Session, 2: Background)
    std::queue<Task> m_queues[3];
    std::mutex m_queueMutex;
    std::condition_variable m_cv;
    std::vector<std::thread> m_workerThreads;

    std::mutex m_statusMutex;
    std::string m_currentAction{"Idle"};
    std::chrono::steady_clock::time_point m_lastHighPriorityTime;
};

#endif // STREAMIFY_TASK_ORCHESTRATOR_H
