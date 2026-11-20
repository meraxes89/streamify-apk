#!/usr/bin/env python3
"""Round-7 fixes: cpal 0.18.2 real API (SampleRate=u32, description().name())
across streamify-hal, plus fixture notes."""
from pathlib import Path

# ── device.rs: .0 removals + name via description() ──
p = Path("rust/crates/streamify-hal/src/device.rs")
t = p.read_text()

t = t.replace(
    """    let default_name = host
        .default_output_device()
        .and_then(|d| cpal::traits::DeviceTrait::name(&d).ok());""",
    """    let default_name = host
        .default_output_device()
        .and_then(|d| {
            cpal::traits::DeviceTrait::description(&d)
                .ok()
                .map(|desc| desc.name().to_string())
        });""",
)
t = t.replace(
    "            let Ok(name) = cpal::traits::DeviceTrait::name(&device) else { continue };",
    """            let Ok(name) = cpal::traits::DeviceTrait::description(&device)
                .map(|desc| desc.name().to_string())
            else {
                continue;
            };""",
)
t = t.replace(
    """            let supports_48k_stereo = cpal::traits::DeviceTrait::supported_output_configs(&device)
                .map(|cfgs| {
                    cfgs.any(|c| {
                        c.channels() == 2
                            && c.min_sample_rate().0 <= 48_000
                            && c.max_sample_rate().0 >= 48_000
                    })
                })
                .unwrap_or(false);""",
    """            let supports_48k_stereo = cpal::traits::DeviceTrait::supported_output_configs(&device)
                .map(|cfgs| {
                    // cpal 0.18: SampleRate is a plain u32 alias.
                    cfgs.any(|c| {
                        c.channels() == 2
                            && c.min_sample_rate() <= 48_000
                            && c.max_sample_rate() >= 48_000
                    })
                })
                .unwrap_or(false);""",
)
p.write_text(t)
print("device.rs fixed")

# ── engine.rs: same ──
p = Path("rust/crates/streamify-hal/src/engine.rs")
t = p.read_text()
t = t.replace(
    "        let name = cpal::traits::DeviceTrait::name(&device).map_err(|e| e.to_string())?;",
    """        let name = cpal::traits::DeviceTrait::description(&device)
            .map_err(|e| e.to_string())?
            .name()
            .to_string();""",
)
t = t.replace(
    "                .find(|d| cpal::traits::DeviceTrait::name(d).map(|n| n == *name).unwrap_or(false))",
    """                .find(|d| {
                    cpal::traits::DeviceTrait::description(d)
                        .map(|desc| desc.name() == name.as_str())
                        .unwrap_or(false)
                })""",
)
t = t.replace(
    "                    .find(|dev| cpal::traits::DeviceTrait::name(dev).map(|n| n == d.name).unwrap_or(false))",
    """                    .find(|dev| {
                        cpal::traits::DeviceTrait::description(dev)
                            .map(|desc| desc.name() == d.name)
                            .unwrap_or(false)
                    })""",
)
t = t.replace(
    """            .find(|c| {
                c.channels() == CANONICAL_CHANNELS
                    && c.min_sample_rate().0 <= CANONICAL_RATE_HZ
                    && c.max_sample_rate().0 >= CANONICAL_RATE_HZ
            })""",
    """            .find(|c| {
                // cpal 0.18: SampleRate is a plain u32 alias.
                c.channels() == CANONICAL_CHANNELS
                    && c.min_sample_rate() <= CANONICAL_RATE_HZ
                    && c.max_sample_rate() >= CANONICAL_RATE_HZ
            })""",
)
p.write_text(t)
print("engine.rs fixed")

# ── dac_clock.rs: config.sample_rate.0 → u32 ──
p = Path("rust/crates/streamify-hal/src/dac_clock.rs")
t = p.read_text()
t = t.replace(
    """#[inline]
pub fn config_rate(config: &StreamConfig) -> u32 {
    config.sample_rate.0
}""",
    """#[inline]
pub fn config_rate(config: &StreamConfig) -> u32 {
    // cpal 0.18: SampleRate is a plain u32 alias.
    config.sample_rate
}""",
)
p.write_text(t)
print("dac_clock.rs fixed")

# ── engine.rs: SampleRate(CANONICAL_RATE_HZ) construction — u32 now ──
p = Path("rust/crates/streamify-hal/src/engine.rs")
t = p.read_text()
t = t.replace(
    "            sample_rate: SampleRate(CANONICAL_RATE_HZ),",
    "            sample_rate: CANONICAL_RATE_HZ, // cpal 0.18: plain u32",
)
# drop the now-unused SampleRate import
t = t.replace(
    "use cpal::{BufferSize, SampleRate, Stream, StreamConfig};",
    "use cpal::{BufferSize, Stream, StreamConfig};",
)
p.write_text(t)
print("engine.rs SampleRate construction fixed")
