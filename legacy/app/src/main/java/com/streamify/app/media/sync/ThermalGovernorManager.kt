package com.streamify.app.media.sync
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.streamify.app.data.NativeBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object ThermalGovernorManager {
    private var powerManager: PowerManager? = null
    private var activityManager: ActivityManager? = null

    private val _targetFpsFlow = MutableStateFlow(120)
    val targetFpsFlow: StateFlow<Int> = _targetFpsFlow.asStateFlow()

    private val _currentThermalStatusFlow = MutableStateFlow(0)
    val currentThermalStatusFlow: StateFlow<Int> = _currentThermalStatusFlow.asStateFlow()

    fun init(context: Context) {
        val appCtx = context.applicationContext
        powerManager = appCtx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        activityManager = appCtx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val executor = ContextCompat.getMainExecutor(appCtx)
            try {
                powerManager?.addThermalStatusListener(executor) { status ->
                    _currentThermalStatusFlow.value = status
                    NativeBridge.updateThermalStatus(status)

                    // If severe or critical thermal state, drop Compose target FPS to 60 to prevent throttling
                    if (status >= PowerManager.THERMAL_STATUS_SEVERE) {
                        _targetFpsFlow.value = 60
                    } else {
                        _targetFpsFlow.value = 120
                    }
                }
            } catch (e: Throwable) {
                // Ignore if device does not support listener
            }
        }
    }

    fun isMemoryLow(context: Context): Boolean {
        val actMgr = activityManager ?: (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager) ?: return false
        val memoryInfo = ActivityManager.MemoryInfo()
        actMgr.getMemoryInfo(memoryInfo)
        return memoryInfo.lowMemory || memoryInfo.availMem <= 100 * 1024 * 1024L
    }

    fun handleLowMemory(context: Context) {
        try {
            val dbPath = context.getDatabasePath("streamify_universal.db").absolutePath
            NativeBridge.flushDatabaseWal(dbPath)
        } catch (e: Throwable) {
            // Ignore
        }
    }
}
