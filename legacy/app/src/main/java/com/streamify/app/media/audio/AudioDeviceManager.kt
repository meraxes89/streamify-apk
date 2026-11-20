package com.streamify.app.media.audio
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.streamify.app.util.SLog

enum class AudioDeviceType {
    BLUETOOTH_CAR,
    WIRED_DAC,
    SPEAKER,
    BLUETOOTH_HEADPHONES
}

data class AudioOutputDevice(
    val name: String,
    val isBluetooth: Boolean = false,
    val isHeadphones: Boolean = false,
    val isSpeaker: Boolean = false
)

object AudioDeviceManager {

    private val _currentDevice = MutableStateFlow(AudioOutputDevice("Phone Speaker", isSpeaker = true))
    val currentDevice: StateFlow<AudioOutputDevice> = _currentDevice.asStateFlow()

    private val audioTimestamp = AudioTimestamp()
    var onHeadsetDisconnectedListener: (() -> Unit)? = null

    fun getCurrentDeviceType(): AudioDeviceType {
        val dev = _currentDevice.value
        val nameLower = dev.name.lowercase()
        return when {
            nameLower.contains("car") || nameLower.contains("auto") -> AudioDeviceType.BLUETOOTH_CAR
            dev.isBluetooth -> AudioDeviceType.BLUETOOTH_HEADPHONES
            dev.isHeadphones || nameLower.contains("dac") || nameLower.contains("usb") -> AudioDeviceType.WIRED_DAC
            else -> AudioDeviceType.SPEAKER
        }
    }

    private val isRegistered = java.util.concurrent.atomic.AtomicBoolean(false)

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var routePending = false

    private val audioReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action
            if (action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                onHeadsetDisconnectedListener?.invoke()
            }
            // COALESCED ROUTING: BT reconnect wobble + wired plug bounce fire
            // several broadcasts within milliseconds; each used to run prefs
            // reads + up to 7 audiofx binder calls ON THE MAIN THREAD. Now one
            // update per 500ms window.
            context ?: return
            if (routePending) return
            routePending = true
            mainHandler.postDelayed({
                routePending = false
                try {
                    updateCurrentDevice(context)
                    autoRoutePreset(context)
                } catch (_: Exception) { }
            }, 500)
        }
    }

    fun init(context: Context) {
        val appContext = context.applicationContext
        updateCurrentDevice(appContext)
        if (isRegistered.compareAndSet(false, true)) {
            try {
                val filter = IntentFilter().apply {
                    addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
                    addAction(AudioManager.ACTION_HEADSET_PLUG)
                    addAction("android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED")
                    addAction("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED")
                }
                appContext.registerReceiver(audioReceiver, filter)
            } catch (e: Exception) {
                isRegistered.set(false)
                SLog.st("AudioDeviceManager", "AudioDeviceManager.init failed", e)
            }
        }
    }

    fun release(context: Context?) {
        if (isRegistered.compareAndSet(true, false)) {
            try {
                context?.applicationContext?.unregisterReceiver(audioReceiver)
            } catch (e: Exception) {
                // Receiver was not registered or already removed
            }
        }
    }

    private fun autoRoutePreset(context: Context) {
        val prefs = context.getSharedPreferences("streamify_eq_prefs", Context.MODE_PRIVATE)
        val autoRoutingEnabled = prefs.getBoolean("auto_routing_preset", true)
        if (!autoRoutingEnabled) return

        val device = _currentDevice.value
        val targetPreset = when {
            device.isBluetooth -> prefs.getString("preset_bluetooth", "Bass Booster") ?: "Bass Booster"
            device.isHeadphones -> prefs.getString("preset_headphones", "Acoustic") ?: "Acoustic"
            else -> prefs.getString("preset_speaker", "Vocal Booster") ?: "Vocal Booster"
        }

        EqualizerManager.applyPresetByName(targetPreset)
    }

    fun updateCurrentDevice(context: Context) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            for (device in devices) {
                when (device.type) {
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    AudioDeviceInfo.TYPE_BLE_HEADSET,
                    AudioDeviceInfo.TYPE_BLE_SPEAKER -> {
                        val deviceName = device.productName?.toString() ?: "Bluetooth Audio"
                        _currentDevice.value = AudioOutputDevice(
                            name = deviceName,
                            isBluetooth = true
                        )
                        return
                    }
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    AudioDeviceInfo.TYPE_USB_HEADSET,
                    AudioDeviceInfo.TYPE_USB_DEVICE -> {
                        _currentDevice.value = AudioOutputDevice(
                            name = "Headphones / DAC",
                            isHeadphones = true
                        )
                        return
                    }
                }
            }
        }

        // Fallback or default
        _currentDevice.value = AudioOutputDevice(
            name = "This Phone",
            isSpeaker = true
        )
    }

    /**
     * Extracts exact hardware latency in milliseconds from the physical DAC / Bluetooth pipeline via AudioTimestamp.
     */
    fun computePhysicalOutputLatencyMs(context: Context, audioTrack: AudioTrack?): Long {
        if (audioTrack != null && audioTrack.state == AudioTrack.STATE_INITIALIZED) {
            if (audioTrack.getTimestamp(audioTimestamp)) {
                val nanoTime = audioTimestamp.nanoTime
                val systemNanoTime = System.nanoTime()
                val dacLatencyNs = (systemNanoTime - nanoTime)
                val dacLatencyMs = dacLatencyNs / 1_000_000L
                if (dacLatencyMs in 0L..500L) {
                    return dacLatencyMs
                }
            }
        }

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return 25L
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val isBluetoothA2dp = devices.any {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER
            }
            return if (isBluetoothA2dp) 140L else 20L
        }
        return 20L
    }

    fun openSystemAudioSettings(context: Context) {
        try {
            val intent = Intent(android.provider.Settings.ACTION_SOUND_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            try {
                val intent = Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            } catch (ex: Exception) {
                SLog.st("AudioDeviceManager", "AudioDeviceManager.openSystemAudioSettings failed", ex)
            }
        }
    }
}
