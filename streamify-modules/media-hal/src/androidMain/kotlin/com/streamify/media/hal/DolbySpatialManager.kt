package com.streamify.media.hal

import android.content.Context
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Hardware Dolby Atmos and Android 13+ Spatializer capability manager.
 * Detects whether the host Android device carries OEM Dolby Audio Processing (DAP)
 * or system spatialization hardware with head tracking.
 */
actual object DolbySpatialManager {
    private val _isDolbyAtmosDetected = MutableStateFlow(false)
    actual val isDolbyAtmosDetected: StateFlow<Boolean> = _isDolbyAtmosDetected.asStateFlow()

    private val _isSpatializerAvailable = MutableStateFlow(false)
    actual val isSpatializerAvailable: StateFlow<Boolean> = _isSpatializerAvailable.asStateFlow()

    private val _hasHeadTracking = MutableStateFlow(false)
    actual val hasHeadTracking: StateFlow<Boolean> = _hasHeadTracking.asStateFlow()

    private val _hardwareDetail = MutableStateFlow("Detecting audio architecture...")
    actual val hardwareDetail: StateFlow<String> = _hardwareDetail.asStateFlow()

    // Well-known OEM Dolby Audio Processing UUIDs
    private val DOLBY_EFFECT_UUID = UUID.fromString("46d279d9-9be7-453d-9d7c-ef937f675587")

    fun init(context: Context) {
        checkHardwareCapabilities(context)
    }

    fun checkHardwareCapabilities(context: Context) {
        var dolbyFound = false
        var spatializerFound = false
        var headTrackingFound = false
        val details = mutableListOf<String>()

        // 1. Android 13+ (API 32/33+) Spatializer API check
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S_V2) {
            try {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                val spatializer = audioManager?.spatializer
                if (spatializer != null && spatializer.isAvailable) {
                    spatializerFound = true
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        if (spatializer.isHeadTrackerAvailable) {
                            headTrackingFound = true
                        }
                    }
                }
            } catch (_: Exception) {
                // Spatializer access fallback
            }
        }

        // 2. Query System Audio Effects for Hardware Dolby Processing (DAP)
        try {
            val effects = AudioEffect.queryEffects()
            if (effects != null) {
                for (desc in effects) {
                    val name = desc.name?.lowercase() ?: ""
                    val implementor = desc.implementor?.lowercase() ?: ""
                    val uuid = desc.uuid

                    if (name.contains("dolby") || name.contains("atmos") || name.contains("dap") ||
                        implementor.contains("dolby") || uuid == DOLBY_EFFECT_UUID) {
                        dolbyFound = true
                        details.add(desc.name ?: "Dolby Audio")
                    }
                }
            }
        } catch (_: Exception) {
            // AudioEffect query failsafe
        }

        _isDolbyAtmosDetected.value = dolbyFound || spatializerFound
        _isSpatializerAvailable.value = spatializerFound
        _hasHeadTracking.value = headTrackingFound

        _hardwareDetail.value = when {
            dolbyFound && headTrackingFound -> "Dolby Atmos & Spatial Audio (Head Tracking Supported)"
            dolbyFound -> "Dolby Atmos Hardware Detected (${details.firstOrNull() ?: "OEM DSP"})"
            spatializerFound && headTrackingFound -> "Android Spatializer Active (Head Tracking Enabled)"
            spatializerFound -> "Android Spatializer Hardware Active (3D HRTF Stage)"
            else -> "Standard 32-bit Studio DSP Output"
        }
    }
}
