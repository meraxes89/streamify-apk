package com.streamify.app.data.models

data class OrchestratorStatusNative(
    val state: String,
    val currentAction: String,
    val activeAiTasks: Int,
    val completedAiTasks: Int,
    val totalAiTasks: Int,
    val cpuCoreBudget: Int,
    val activeThreads: Int,
    val isThrottled: Boolean,
    val cpuTemp: Int,
    val isThermallyThrottled: Boolean,
    val isBatterySaver: Boolean
)

data class OrchestratorStatus(
    val state: String = "IDLE",
    val currentAction: String = "Idle (Low Power Mode)",
    val activeAiTasks: Int = 0,
    val completedAiTasks: Int = 0,
    val totalAiTasks: Int = 0,
    val cpuCoreBudget: Int = 100,
    val activeThreads: Int = 0,
    val isThrottled: Boolean = false,
    val cpuTemp: Int = 35,
    val isThermallyThrottled: Boolean = false,
    val isBatterySaver: Boolean = false
) {
    val progress: Float
        get() = if (totalAiTasks > 0) (completedAiTasks.toFloat() / totalAiTasks.toFloat()).coerceIn(0f, 1f) else 1f
}
