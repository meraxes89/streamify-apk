package com.streamify.lyrics.slyr

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

actual object BluetoothLatencyTracker {
    private const val BLUETOOTH_A2DP_DEFAULT_DELAY_MS = 140
    private const val SPEAKER_DEFAULT_DELAY_MS = 0

    private val _dynamicDelayMs = MutableStateFlow(SPEAKER_DEFAULT_DELAY_MS)
    actual val dynamicDelayMs: StateFlow<Int> = _dynamicDelayMs.asStateFlow()

    private var isInitialized = false

    actual fun init(context: Any?) {
        val ctx = context as? Context ?: return
        if (isInitialized) return
        isInitialized = true

        try {
            val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            updateLatency(audioManager)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                audioManager.registerAudioDeviceCallback(
                    object : AudioDeviceCallback() {
                        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                            updateLatency(audioManager)
                        }

                        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                            updateLatency(audioManager)
                        }
                    },
                    null
                )
            }
        } catch (_: Exception) {}
    }

    private fun updateLatency(audioManager: AudioManager) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                val isBluetooth = devices.any { device ->
                    when (device.type) {
                        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                        AudioDeviceInfo.TYPE_BLE_HEADSET,
                        AudioDeviceInfo.TYPE_BLE_SPEAKER -> true
                        else -> false
                    }
                }
                _dynamicDelayMs.value = if (isBluetooth) BLUETOOTH_A2DP_DEFAULT_DELAY_MS else SPEAKER_DEFAULT_DELAY_MS
            } else {
                @Suppress("DEPRECATION")
                val isBt = audioManager.isBluetoothA2dpOn
                _dynamicDelayMs.value = if (isBt) BLUETOOTH_A2DP_DEFAULT_DELAY_MS else SPEAKER_DEFAULT_DELAY_MS
            }
        } catch (_: Exception) {
            _dynamicDelayMs.value = SPEAKER_DEFAULT_DELAY_MS
        }
    }

    actual fun getDelayMs(): Int = _dynamicDelayMs.value
}
