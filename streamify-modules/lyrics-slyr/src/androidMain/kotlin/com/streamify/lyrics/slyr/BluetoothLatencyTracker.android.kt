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
    var latencyModel: LatencyModel? = null

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
                var delay = SPEAKER_DEFAULT_DELAY_MS
                var confidence = LatencyConfidence.DEFAULT
                var route = "Speaker"

                val bleDevice = devices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET || it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER
                }
                val a2dpDevice = devices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                }
                val scoDevice = devices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                }

                when {
                    bleDevice != null -> {
                        // BLE / LC3 Audio runs ~20-60 ms (comm18: do not over-compensate with 140 ms)
                        delay = LatencyModel.PROFILE_LE_AUDIO_MS.toInt()
                        confidence = LatencyConfidence.CODEC_PROFILE
                        route = "Bluetooth LE Audio"
                    }
                    a2dpDevice != null -> {
                        delay = LatencyModel.PROFILE_A2DP_AAC_MS.toInt()
                        confidence = LatencyConfidence.CODEC_PROFILE
                        route = "Bluetooth A2DP"
                    }
                    scoDevice != null -> {
                        delay = LatencyModel.PROFILE_SCO_MS.toInt()
                        confidence = LatencyConfidence.CODEC_PROFILE
                        route = "Bluetooth SCO"
                    }
                    devices.any {
                        it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                        it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                        it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                        it.type == AudioDeviceInfo.TYPE_USB_HEADSET
                    } -> {
                        delay = LatencyModel.PROFILE_WIRED_SPEAKER_MS.toInt()
                        confidence = LatencyConfidence.MEASURED
                        route = "Wired / USB DAC"
                    }
                }

                _dynamicDelayMs.value = delay
                latencyModel?.updateLatency(
                    halBufferMs = 0L,
                    linkMs = delay.toLong(),
                    confidence = confidence,
                    routeName = route
                )
            } else {
                @Suppress("DEPRECATION")
                val isBt = audioManager.isBluetoothA2dpOn
                val delay = if (isBt) BLUETOOTH_A2DP_DEFAULT_DELAY_MS else SPEAKER_DEFAULT_DELAY_MS
                _dynamicDelayMs.value = delay
                latencyModel?.updateLatency(
                    halBufferMs = 0L,
                    linkMs = delay.toLong(),
                    confidence = LatencyConfidence.DEFAULT,
                    routeName = if (isBt) "Bluetooth Legacy" else "Speaker"
                )
            }
        } catch (_: Exception) {
            _dynamicDelayMs.value = SPEAKER_DEFAULT_DELAY_MS
        }
    }

    actual fun getDelayMs(): Int = _dynamicDelayMs.value
}
