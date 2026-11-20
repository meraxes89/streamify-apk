//! Output device enumeration + hot-swap recovery inputs (Phase 1 D1).
//!
//! [`enumerate_outputs`] lists the default + every named output device
//! (what the desktop `AppleMusicOutputSheet` device picker shows and
//! what `AudioRoutePolicy` watches for USB DAC / Bluetooth unplug).
//!
//! Linux sound-server hygiene: [`OutputRoute::preferred`] ranks
//! PipeWire/Pulse bridge device names ABOVE the raw `default` ALSA node
//! when a sound server is present, so the engine never fights a sound
//! server over an exclusively-held card.

use cpal::traits::{DeviceTrait, HostTrait};
use cpal::{default_host, HostId};

/// One output endpoint as the routing layer sees it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct OutputDevice {
    /// cpal device name (host-specific string).
    pub name: String,
    /// True when this is the host's default output device.
    pub is_default: bool,
    /// Preferred 48 kHz stereo config if the device supports it (the
    /// engine's request; `None` = device lacks 48 kHz — the route policy
    /// falls back to libmpv for that endpoint).
    pub supports_48k_stereo: bool,
}

/// The route decision for one enumeration pass.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum OutputRoute {
    /// Open `device` (the chosen endpoint).
    Device(OutputDevice),
    /// No usable output endpoint — the policy's libmpv fallback arm.
    NoEndpoint,
}

/// Sound-server bridge prefixes/devices on the ALSA host (the PipeWire
/// and PulseAudio compatibility pipes; a running sound server holds the
/// raw card, so these are PREFERRED over `default`/`hw:`).
const SOUND_SERVER_DEVICES: [&str; 4] = ["pipewire", "pulse", "pipewire-0", "pulse-server"];

/// Enumerate every output device of the best host for this platform,
/// tagged with default-ness and 48 kHz stereo support.
pub fn enumerate_outputs() -> Vec<OutputDevice> {
    let host = default_host();
    let mut out = Vec::new();
    let default_name = host
        .default_output_device()
        .and_then(|d| {
            cpal::traits::DeviceTrait::description(&d)
                .ok()
                .map(|desc| desc.name().to_string())
        });
    if let Ok(devices) = host.output_devices() {
        for device in devices {
            let Ok(name) = cpal::traits::DeviceTrait::description(&device)
                .map(|desc| desc.name().to_string())
            else {
                continue;
            };
            let supports_48k_stereo = cpal::traits::DeviceTrait::supported_output_configs(&device)
                .map(|mut cfgs| {
                    // cpal 0.18: SampleRate is a plain u32 alias.
                    cfgs.any(|c| {
                        c.channels() == 2
                            && c.min_sample_rate() <= 48_000
                            && c.max_sample_rate() >= 48_000
                    })
                })
                .unwrap_or(false);
            out.push(OutputDevice {
                name: name.clone(),
                is_default: Some(name) == default_name,
                supports_48k_stereo,
            });
        }
    }
    out
}

/// The Linux sound-server ranking: prefer PipeWire/Pulse bridges over
/// the raw default when both exist (the ALSA hygiene — the engine must
/// not hold a card a sound server owns). On other platforms the default
/// device wins as-is.
pub fn prefer_sound_server(devices: &[OutputDevice]) -> OutputRoute {
    // Any sound-server pipe present AND 48 kHz-capable → route to it.
    if cfg!(target_os = "linux") {
        for name in SOUND_SERVER_DEVICES {
            if let Some(d) = devices
                .iter()
                .find(|d| d.name.to_ascii_lowercase().contains(name) && d.supports_48k_stereo)
            {
                return OutputRoute::Device(d.clone());
            }
        }
    }
    // Default device (48 kHz-capable) next.
    if let Some(d) = devices
        .iter()
        .find(|d| d.is_default && d.supports_48k_stereo)
    {
        return OutputRoute::Device(d.clone());
    }
    // Any capable endpoint.
    if let Some(d) = devices.iter().find(|d| d.supports_48k_stereo) {
        return OutputRoute::Device(d.clone());
    }
    // Last resort: the DEFAULT endpoint even without an advertised
    // 48 kHz range (Windows RDP endpoints accept 48 kHz streams —
    // the shared-mode mix format resamples; the engine's sanity check
    // only requires that a default output config exists).
    if let Some(d) = devices.iter().find(|d| d.is_default) {
        return OutputRoute::Device(d.clone());
    }
    OutputRoute::NoEndpoint
}

/// The host id cpal selected (diagnostics; the JVM bridge surfaces it).
pub fn host_id() -> Option<HostId> {
    Some(default_host().id())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sound_server_pipe_preferred_on_linux() {
        // On the Linux CI runner (PipeWire virtual sink present), the
        // pipe must outrank the default when both are 48k-capable.
        let devices = vec![
            OutputDevice { name: "default".into(), is_default: true, supports_48k_stereo: true },
            OutputDevice { name: "pipewire".into(), is_default: false, supports_48k_stereo: true },
        ];
        let route = prefer_sound_server(&devices);
        match route {
            OutputRoute::Device(d) => assert!(d.name.contains("pipewire")),
            OutputRoute::NoEndpoint => panic!("expected a device route"),
        }
    }

    #[test]
    fn no_capable_device_is_no_endpoint() {
        let devices = vec![OutputDevice { name: "mic-only".into(), is_default: true, supports_48k_stereo: false }];
        assert_eq!(prefer_sound_server(&devices), OutputRoute::NoEndpoint);
    }

    #[test]
    fn default_wins_without_sound_server() {
        let devices = vec![
            OutputDevice { name: "hw:PCH".into(), is_default: false, supports_48k_stereo: true },
            OutputDevice { name: "speakers".into(), is_default: true, supports_48k_stereo: true },
        ];
        match prefer_sound_server(&devices) {
            OutputRoute::Device(d) => assert_eq!(d.name, "speakers"),
            OutputRoute::NoEndpoint => panic!("expected a device route"),
        }
    }
}
