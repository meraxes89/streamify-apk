package com.streamify.app.media.audio
import android.content.Context
import android.content.SharedPreferences
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.streamify.app.util.SLog

object EqualizerManager {
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var prefs: SharedPreferences? = null

    data class EqBand(val centerFreqHz: Int, val level: Short, val lowerHz: Int, val upperHz: Int)

    data class PresetProfile(
        val name: String,
        val bandDbs: List<Int>, // dB adjustments normalized across available bands
        val bassStrength: Short,
        val virtualizerStrength: Short
    )

    val PRESET_PROFILES = listOf(
        PresetProfile("Flat", listOf(0, 0, 0, 0, 0), 0, 0),
        PresetProfile("Bass Booster", listOf(8, 5, 2, 0, 0), 700, 0),
        PresetProfile("Vocal Booster", listOf(-2, 0, 4, 6, 3), 0, 100),
        PresetProfile("Rock", listOf(5, 3, -1, 4, 6), 400, 300),
        PresetProfile("Pop", listOf(2, 4, 5, 3, 1), 300, 200),
        PresetProfile("Hip-Hop", listOf(7, 6, 0, 2, 4), 800, 250),
        PresetProfile("Electronic", listOf(6, 4, 0, 3, 7), 600, 500),
        PresetProfile("Jazz", listOf(3, 2, 1, 3, 4), 200, 400),
        PresetProfile("Classical", listOf(4, 3, -1, 3, 5), 100, 600),
        PresetProfile("Acoustic", listOf(3, 3, 2, 4, 3), 150, 300),
        PresetProfile("Deep", listOf(8, 4, -2, -2, -4), 900, 100)
    )

    private val _activePresetName = MutableStateFlow("Flat")
    val activePresetName: StateFlow<String> = _activePresetName.asStateFlow()

    private val _isEqEnabled = MutableStateFlow(false)
    val isEqEnabled: StateFlow<Boolean> = _isEqEnabled.asStateFlow()

    private val _isLoudnessNormalizationEnabled = MutableStateFlow(true)
    val isLoudnessNormalizationEnabled: StateFlow<Boolean> = _isLoudnessNormalizationEnabled.asStateFlow()

    private val _bands = MutableStateFlow<List<EqBand>>(emptyList())
    val bands: StateFlow<List<EqBand>> = _bands.asStateFlow()

    private val _bassStrength = MutableStateFlow<Short>(0)
    val bassStrength: StateFlow<Short> = _bassStrength.asStateFlow()

    private val _virtualizerStrength = MutableStateFlow<Short>(0)
    val virtualizerStrength: StateFlow<Short> = _virtualizerStrength.asStateFlow()

    var minEqLevel: Short = -1500
    var maxEqLevel: Short = 1500

    fun init(context: Context, audioSessionId: Int) {
        prefs = context.getSharedPreferences("streamify_eq_prefs", Context.MODE_PRIVATE)
        _isEqEnabled.value = prefs?.getBoolean("eq_enabled", false) ?: false
        _activePresetName.value = prefs?.getString("active_preset", "Flat") ?: "Flat"
        _bassStrength.value = (prefs?.getInt("bass_strength", 0) ?: 0).toShort()
        _virtualizerStrength.value = (prefs?.getInt("virtualizer_strength", 0) ?: 0).toShort()
        _isLoudnessNormalizationEnabled.value = prefs?.getBoolean("loudness_enabled", true) ?: true

        initSession(audioSessionId)
    }

    @Synchronized
    fun initSession(audioSessionId: Int) {
        release()
        if (audioSessionId <= 0) return
        try {
            equalizer = Equalizer(0, audioSessionId)
            bassBoost = BassBoost(0, audioSessionId)
            virtualizer = Virtualizer(0, audioSessionId)
            loudnessEnhancer = LoudnessEnhancer(audioSessionId)

            equalizer?.enabled = _isEqEnabled.value
            bassBoost?.enabled = _isEqEnabled.value
            virtualizer?.enabled = _isEqEnabled.value
            
            loudnessEnhancer?.enabled = _isLoudnessNormalizationEnabled.value
            if (_isLoudnessNormalizationEnabled.value) {
                loudnessEnhancer?.setTargetGain(150)
            }

            val eq = equalizer ?: return
            val bandRange = eq.bandLevelRange
            minEqLevel = bandRange[0]
            maxEqLevel = bandRange[1]

            val numBands = eq.numberOfBands
            val newBands = mutableListOf<EqBand>()
            for (i in 0 until numBands) {
                val freq = eq.getCenterFreq(i.toShort()) / 1000
                val range = eq.getBandFreqRange(i.toShort())
                val level = eq.getBandLevel(i.toShort())
                newBands.add(EqBand(freq, level, range[0] / 1000, range[1] / 1000))
            }
            _bands.value = newBands

            // Apply active preset if saved
            if (_activePresetName.value != "Custom") {
                applyPresetByNameInternal(_activePresetName.value)
            }

            bassBoost?.setStrength(_bassStrength.value)
            virtualizer?.setStrength(_virtualizerStrength.value)
        } catch (e: Exception) {
            SLog.st("EqualizerManager", "EqualizerManager.initSession failed", e)
        }
    }

    fun applyPresetByName(name: String) {
        _activePresetName.value = name
        prefs?.edit()?.putString("active_preset", name)?.apply()
        applyPresetByNameInternal(name)
    }

    private fun applyPresetByNameInternal(name: String) {
        val preset = PRESET_PROFILES.find { it.name.equals(name, ignoreCase = true) } ?: return
        val currentBands = _bands.value.toMutableList()
        if (currentBands.isEmpty()) return

        for (i in currentBands.indices) {
            val db = if (i < preset.bandDbs.size) preset.bandDbs[i] else 0
            val levelMb = (db * 100).toShort().coerceIn(minEqLevel, maxEqLevel)
            currentBands[i] = currentBands[i].copy(level = levelMb)
        }
        _bands.value = currentBands
        applyBandGains(currentBands)

        setBassStrength(preset.bassStrength)
        setVirtualizerStrength(preset.virtualizerStrength)
    }

    fun setLoudnessNormalization(enabled: Boolean) {
        _isLoudnessNormalizationEnabled.value = enabled
        prefs?.edit()?.putBoolean("loudness_enabled", enabled)?.apply()
        try {
            loudnessEnhancer?.enabled = enabled
            if (enabled) {
                loudnessEnhancer?.setTargetGain(150)
            }
        } catch (e: Exception) {
            SLog.st("EqualizerManager", "EqualizerManager.setLoudnessNormalization failed", e)
        }
    }

    fun setEqEnabled(enabled: Boolean) {
        _isEqEnabled.value = enabled
        prefs?.edit()?.putBoolean("eq_enabled", enabled)?.apply()
        equalizer?.enabled = enabled
        bassBoost?.enabled = enabled
        virtualizer?.enabled = enabled
    }

    /**
     * PHASE 2 EQ ENGINE ROUTING: on 44.1 kHz streams the Rust float parametric
     * biquads own equalization (gains published to the render processor, and
     * the system effect is bypassed to prevent double-EQ). Every other sample
     * rate keeps the legacy system-Equalizer path unchanged.
     */
    private fun applyBandGains(currentBands: List<EqBand>) {
        val rustOwns = StreamifyAudioProcessor.activeEqEngine == "RUST"
        for (i in currentBands.indices) {
            val levelMb = currentBands[i].level
            if (rustOwns) continue // published wholesale below
            try { equalizer?.setBandLevel(i.toShort(), levelMb) } catch (_: Exception) {}
        }
        if (rustOwns && currentBands.isNotEmpty()) {
            val gains = FloatArray(currentBands.size.coerceAtMost(10)) { i ->
                currentBands[i].level / 100f   // milliBel → dB
            }
            StreamifyAudioProcessor.eqBandGainsDb = if (gains.size == 10) gains else gains.copyOf(10)
        }
    }

    fun setBandLevel(bandIndex: Short, level: Short) {
        try {
            _activePresetName.value = "Custom"
            prefs?.edit()?.putString("active_preset", "Custom")?.apply()
            val rustOwns = StreamifyAudioProcessor.activeEqEngine == "RUST"
            if (!rustOwns) {
                try { equalizer?.setBandLevel(bandIndex, level) } catch (_: Exception) {}
            }
            val current = _bands.value.toMutableList()
            if (bandIndex < current.size) {
                current[bandIndex.toInt()] = current[bandIndex.toInt()].copy(level = level)
                _bands.value = current
                applyBandGains(current)
            }
        } catch (e: Exception) { SLog.st("EqualizerManager", "EqualizerManager.setBandLevel failed", e) }
    }

    fun setBassStrength(strength: Short) {
        _bassStrength.value = strength
        prefs?.edit()?.putInt("bass_strength", strength.toInt())?.apply()
        try { bassBoost?.setStrength(strength) } catch (e: Exception) {}
    }

    fun setVirtualizerStrength(strength: Short) {
        _virtualizerStrength.value = strength
        prefs?.edit()?.putInt("virtualizer_strength", strength.toInt())?.apply()
        try { virtualizer?.setStrength(strength) } catch (e: Exception) {}
    }

    @Synchronized
    fun release() {
        val effects = listOf(equalizer, bassBoost, virtualizer, loudnessEnhancer)
        effects.forEach { effect ->
            runCatching {
                if (effect?.enabled == true) {
                    effect.enabled = false
                }
                effect?.release()
            }
        }
        equalizer = null
        bassBoost = null
        virtualizer = null
        loudnessEnhancer = null
    }
}
