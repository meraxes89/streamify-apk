package com.streamify.media.hal

/**
 * Desktop Hardware Abstraction Layer (HAL) Audio Bridge (Deprecated / Stub).
 * Retired unwired custom C++ HAL in favor of the prebuilt libmpv desktop playback stack.
 */
object DesktopHalAudioBridge {

    private val osName: String = System.getProperty("os.name", "").lowercase()
    val isWindows: Boolean = osName.contains("win")
    val isLinux: Boolean = osName.contains("linux")
    val isMac: Boolean = osName.contains("mac")

    fun initHal(): Boolean = false

    /**
     * Reports active audio backend name.
     */
    fun getBackendName(): String = "Prebuilt libmpv Stack"
}
