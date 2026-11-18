package com.streamify.media.hal

import java.io.File

/**
 * Desktop Hardware Abstraction Layer (HAL) Audio Bridge.
 * Binds the loadable C++ HAL library (`streamify_desktop_hal.dll` on Windows,
 * `streamify_desktop_hal.so` on Linux) to replace the external libmpv process.
 */
object DesktopHalAudioBridge {

    @Volatile
    private var isLoaded: Boolean = false

    private val osName: String = System.getProperty("os.name", "").lowercase()
    val isWindows: Boolean = osName.contains("win")
    val isLinux: Boolean = osName.contains("linux")
    val isMac: Boolean = osName.contains("mac")

    fun initHal(): Boolean {
        if (isLoaded) return true

        val libName = when {
            isWindows -> "streamify_desktop_hal.dll"
            isMac -> "libstreamify_desktop_hal.dylib"
            else -> "libstreamify_desktop_hal.so"
        }

        return try {
            System.loadLibrary("streamify_desktop_hal")
            isLoaded = true
            true
        } catch (_: Throwable) {
            // Failsafe fallback
            isLoaded = false
            false
        }
    }

    /**
     * Reports active audio backend name.
     */
    fun getBackendName(): String = when {
        isWindows -> "Windows WASAPI Audio Client (Pull Mode)"
        isLinux -> "Linux PipeWire Low-Latency Driver"
        isMac -> "Apple CoreAudio AudioUnit HAL"
        else -> "Generic JVM Sound HAL"
    }
}
