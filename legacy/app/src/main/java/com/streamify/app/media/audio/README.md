# `media/audio/` — DSP & Audio Devices

The audible signal chain after decoding.

## Files

| File | What it is |
|---|---|
| `StreamifyAudioProcessor.kt` | Main-chain audio processor (LUFS/limiter taps). |
| `SyncAudioProcessor.kt` | Sync-chain processor (mesh PCM tap, drift measurement). |
| `CrossfadeAudioProcessor.kt` | Equal-power crossfade between tracks. |
| `DolbySpatialManager.kt` | Spatial audio toggling. |
| `EqualizerManager.kt` | System equalizer integration (YtActiveEqualizer UI). |
| `AudioDeviceManager.kt` | Device discovery/routing (BT, wired, speaker). |

Native DSP (KissFFT STFT, EBU R128, soft-knee limiter) is C++ in
`native/dsp/` — these Kotlin managers own lifecycle + configuration
and bridge results to the UI layer.
