//! device_registry.rs — Connect device registry, capability matrix &
//! presence-lease state machine (Phase 4, directive §2.1).
//!
//! MISSION: one authoritative table of every Connect-enabled target the
//! gateway can see — Smart Speakers, TVs, Android Auto heads, Wearables,
//! Tablets, other Phones — with a per-device capability matrix and a
//! sliding-TTL presence lease so dead devices evaporate on their own.
//!
//! DESIGN:
//!   • Capability matrix — each discovered device carries
//!     [`DeviceCaps`] (codec bitmask, volume step resolution, direct
//!     stream rendering). Callers negotiate the intersection with the
//!     local caps before dispatching playback; unknown codec bits are
//!     PRESERVED (append-only registry, forward compatible).
//!   • Lease management — a lease is `last_heartbeat + ttl`; the
//!     directive budget is 5 s heartbeat / 15 s timeout (3 missed
//!     heartbeats). [`DeviceRegistry::tick`] evicts expired leases and
//!     reports them so the gateway can raise `DeviceLost` events.
//!   • Pure logic — no I/O, no wall clock: every mutation takes an
//!     injected `now_ms`. The transport layers (LAN beacon listener /
//!     cloud relay listener in `connect_gateway.rs`) feed it; tests
//!     drive it with synthetic clocks.
//!   • Hostile-input discipline — the registry itself only accepts
//!     pre-validated [`DiscoveredDevice`] rows; wire parsing and its
//!     bounds checks live one layer up in `connect_gateway.rs`.

use std::collections::HashMap;
use std::fmt;

/// Presence heartbeat interval (directive §2.1: 5 s).
pub const HEARTBEAT_INTERVAL_MS: u64 = 5_000;
/// Lease TTL: three missed heartbeats evict the device (directive: 15 s).
pub const LEASE_TTL_MS: u64 = 15_000;
/// Registry capacity — bounds memory against hostile beacon floods. A
/// real home / car cabin lands two orders of magnitude below this.
pub const MAX_REGISTRY_DEVICES: usize = 256;
/// Device display-name budget (matches the SCNX beacon name field).
pub const MAX_DEVICE_NAME_LEN: usize = 64;

// ─────────────────────────────────────────────── device taxonomy & caps

/// Connect target taxonomy (directive §2.1). Wire codes are frozen:
/// `DeviceType::from_u8` maps unknown codes to `None`, never panics.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
#[repr(u8)]
pub enum DeviceType {
    Phone = 0,
    Tablet = 1,
    Speaker = 2,
    Tv = 3,
    Car = 4,
    Watch = 5,
}

impl DeviceType {
    pub fn from_u8(code: u8) -> Option<Self> {
        match code {
            0 => Some(DeviceType::Phone),
            1 => Some(DeviceType::Tablet),
            2 => Some(DeviceType::Speaker),
            3 => Some(DeviceType::Tv),
            4 => Some(DeviceType::Car),
            5 => Some(DeviceType::Watch),
            _ => None,
        }
    }

    pub fn as_u8(self) -> u8 {
        self as u8
    }
}

impl fmt::Display for DeviceType {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let s = match self {
            DeviceType::Phone => "phone",
            DeviceType::Tablet => "tablet",
            DeviceType::Speaker => "speaker",
            DeviceType::Tv => "tv",
            DeviceType::Car => "car",
            DeviceType::Watch => "watch",
        };
        f.write_str(s)
    }
}

/// Codec registry (bitmask, append-only — unknown bits round-trip).
pub const CODEC_SBC: u16 = 1 << 0;
pub const CODEC_AAC: u16 = 1 << 1;
pub const CODEC_MP3: u16 = 1 << 2;
pub const CODEC_OPUS: u16 = 1 << 3;
pub const CODEC_FLAC: u16 = 1 << 4;
pub const CODEC_PCM16: u16 = 1 << 5;
pub const CODEC_APTX: u16 = 1 << 6;
pub const CODEC_LDAC: u16 = 1 << 7;

/// Human-readable codec names in bit order (capability negotiation logs).
pub fn codec_names(codecs: u16) -> Vec<&'static str> {
    let table: [(u16, &str); 8] = [
        (CODEC_SBC, "sbc"),
        (CODEC_AAC, "aac"),
        (CODEC_MP3, "mp3"),
        (CODEC_OPUS, "opus"),
        (CODEC_FLAC, "flac"),
        (CODEC_PCM16, "pcm16"),
        (CODEC_APTX, "aptx"),
        (CODEC_LDAC, "ldac"),
    ];
    table
        .into_iter()
        .filter(|(bit, _)| codecs & bit != 0)
        .map(|(_, name)| name)
        .collect()
}

/// Per-device capability matrix (directive §2.1). Default = the
/// zero-matrix (codec negotiation later, continuous volume, no direct
/// render) — what an unadvertised device is assumed to have.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct DeviceCaps {
    /// Codec bitmask ([`CODEC_SBC`]…); zero = "codec negotiation later".
    pub codecs: u16,
    /// Volume step resolution: 0 = continuous, else the device's step
    /// count (1..=100). Remote `SetVolume` intents are snapped to steps.
    pub volume_steps: u8,
    /// Can render an audio stream directly (speaker/TV/head unit), or
    /// only mirror metadata/controls (some watches, tablets in remote-
    /// control mode).
    pub direct_render: bool,
}

impl DeviceCaps {
    /// Sensible defaults per device class — the JNI `nativeInitGateway`
    /// surface only carries `deviceType`, so class defaults seed the
    /// matrix; advert frames refine it as devices introduce themselves.
    pub fn typical(device_type: DeviceType) -> Self {
        match device_type {
            DeviceType::Speaker => DeviceCaps {
                codecs: CODEC_SBC | CODEC_AAC | CODEC_MP3,
                volume_steps: 32,
                direct_render: true,
            },
            DeviceType::Tv => DeviceCaps {
                codecs: CODEC_AAC | CODEC_PCM16 | CODEC_OPUS,
                volume_steps: 100,
                direct_render: true,
            },
            DeviceType::Car => DeviceCaps {
                codecs: CODEC_SBC | CODEC_AAC | CODEC_OPUS,
                volume_steps: 16,
                direct_render: true,
            },
            DeviceType::Watch => DeviceCaps {
                codecs: CODEC_OPUS | CODEC_AAC,
                volume_steps: 10,
                direct_render: false,
            },
            DeviceType::Tablet | DeviceType::Phone => DeviceCaps {
                codecs: CODEC_AAC | CODEC_OPUS | CODEC_PCM16 | CODEC_FLAC,
                volume_steps: 25,
                direct_render: true,
            },
        }
    }

    /// Codec negotiation: codec bits both sides speak.
    pub fn intersect(self, other: DeviceCaps) -> u16 {
        self.codecs & other.codecs
    }

    /// Snap a 0..=100 volume to this device's step grid (0 = continuous).
    pub fn snap_volume(self, volume_pct: u8) -> u8 {
        if self.volume_steps == 0 || self.volume_steps >= 100 {
            return volume_pct.min(100);
        }
        let steps = self.volume_steps as u16;
        let pct = volume_pct.min(100) as u16;
        // Nearest grid point in percent space.
        let snapped = ((pct * steps + 50) / 100).min(steps);
        ((snapped * 100 + steps / 2) / steps).min(100) as u8
    }
}

/// How the gateway heard about the device.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DiscoveryOrigin {
    /// mDNS / UDP SCNX beacon on the LAN.
    LanBeacon,
    /// Cloud relay presence record (device behind NAT / remote).
    CloudRelay,
}

impl fmt::Display for DiscoveryOrigin {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(match self {
            DiscoveryOrigin::LanBeacon => "lan",
            DiscoveryOrigin::CloudRelay => "relay",
        })
    }
}

/// One registry row.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DiscoveredDevice {
    pub device_id: u64,
    pub name: String,
    pub device_type: DeviceType,
    pub caps: DeviceCaps,
    pub origin: DiscoveryOrigin,
    /// Logical clock ms of first registration.
    pub first_seen_ms: u64,
    /// Logical clock ms of the last heartbeat / advert.
    pub last_heartbeat_ms: u64,
    /// `last_heartbeat_ms + lease_ttl_ms` — the eviction deadline.
    pub lease_expires_ms: u64,
}

/// Read-only view handed to callers (registry rows are internal).
pub type DeviceInfo = DiscoveredDevice;

/// A device evicted by lease expiry.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LostDevice {
    pub device_id: u64,
    pub name: String,
    pub device_type: DeviceType,
    pub origin: DiscoveryOrigin,
}

/// Outcome of one registry mutation (the gateway maps these to events).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RegistryChange {
    /// New device registered (lease opened).
    Discovered { device_id: u64 },
    /// Known device re-advertised: lease extended, nothing observable changed.
    Refreshed { device_id: u64 },
    /// Known device changed name / type / caps / origin.
    Updated { device_id: u64 },
    /// Heartbeat arrived for an unknown device (caller re-advertises).
    UnknownHeartbeat { device_id: u64 },
    /// Registry at capacity — new device refused, existing unaffected.
    CapacityFull,
}

/// Why a heartbeat was refused.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum HeartbeatError {
    /// No such device (stale heartbeat for an already-evicted lease).
    UnknownDevice,
    /// The heartbeat timestamp went backwards (logical clock misuse or
    /// hostile frame) — refused, lease untouched.
    NonMonotonic,
}

impl fmt::Display for HeartbeatError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            HeartbeatError::UnknownDevice => write!(f, "heartbeat for unknown device"),
            HeartbeatError::NonMonotonic => write!(f, "heartbeat timestamp regressed"),
        }
    }
}

impl std::error::Error for HeartbeatError {}

/// Registry counters (telemetry / tests).
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct RegistryStats {
    pub devices_registered: u64,
    pub devices_evicted: u64,
    pub heartbeats_refreshed: u64,
    pub updates: u64,
    pub capacity_rejections: u64,
}

// ─────────────────────────────────────────────────────────── registry

/// Sliding-TTL presence registry. All timestamps are LOGICAL ms supplied
/// by the caller — the engine is deterministic and instantly testable.
#[derive(Debug, Clone)]
pub struct DeviceRegistry {
    heartbeat_interval_ms: u64,
    lease_ttl_ms: u64,
    devices: HashMap<u64, DiscoveredDevice>,
    stats: RegistryStats,
}

impl DeviceRegistry {
    /// Production cadence: 5 s heartbeat / 15 s lease (directive §2.1).
    pub fn new(heartbeat_interval_ms: u64, lease_ttl_ms: u64) -> Self {
        debug_assert!(heartbeat_interval_ms > 0, "heartbeat must tick");
        debug_assert!(
            lease_ttl_ms >= heartbeat_interval_ms,
            "lease shorter than one heartbeat evicts live peers"
        );
        DeviceRegistry {
            heartbeat_interval_ms,
            lease_ttl_ms,
            devices: HashMap::new(),
            stats: RegistryStats::default(),
        }
    }

    pub fn heartbeat_interval_ms(&self) -> u64 {
        self.heartbeat_interval_ms
    }

    pub fn lease_ttl_ms(&self) -> u64 {
        self.lease_ttl_ms
    }

    /// Number of live devices right now.
    pub fn len(&self) -> usize {
        self.devices.len()
    }

    pub fn is_empty(&self) -> bool {
        self.devices.is_empty()
    }

    pub fn stats(&self) -> RegistryStats {
        self.stats
    }

    /// Register (or re-register) a device row from a parsed advert.
    /// `now_ms` opens/extends the lease. Unknown codec bits and unusual
    /// volume resolutions round-trip untouched — the matrix is data, not
    /// policy.
    pub fn register_or_refresh(
        &mut self,
        device_id: u64,
        name: &str,
        device_type: DeviceType,
        caps: DeviceCaps,
        origin: DiscoveryOrigin,
        now_ms: u64,
    ) -> RegistryChange {
        let mut name = name.trim().to_string();
        name.truncate(MAX_DEVICE_NAME_LEN);
        if name.is_empty() {
            name = format!("connect-{device_id:016x}");
        }
        if let Some(existing) = self.devices.get_mut(&device_id) {
            let changed = existing.name != name
                || existing.device_type != device_type
                || existing.caps != caps
                || existing.origin != origin;
            existing.name = name;
            existing.device_type = device_type;
            existing.caps = caps;
            existing.origin = origin;
            let refreshed = now_ms.max(existing.last_heartbeat_ms);
            existing.last_heartbeat_ms = refreshed;
            existing.lease_expires_ms = refreshed + self.lease_ttl_ms;
            if changed {
                self.stats.updates += 1;
                RegistryChange::Updated { device_id }
            } else {
                self.stats.heartbeats_refreshed += 1;
                RegistryChange::Refreshed { device_id }
            }
        } else if self.devices.len() >= MAX_REGISTRY_DEVICES {
            self.stats.capacity_rejections += 1;
            RegistryChange::CapacityFull
        } else {
            self.stats.devices_registered += 1;
            self.devices.insert(
                device_id,
                DiscoveredDevice {
                    device_id,
                    name,
                    device_type,
                    caps,
                    origin,
                    first_seen_ms: now_ms,
                    last_heartbeat_ms: now_ms,
                    lease_expires_ms: now_ms + self.lease_ttl_ms,
                },
            );
            RegistryChange::Discovered { device_id }
        }
    }

    /// Bare heartbeat: extend the lease of a KNOWN device. Adverts carry
    /// full descriptors and go through [`Self::register_or_refresh`];
    /// heartbeats only prove liveness.
    pub fn on_heartbeat(&mut self, device_id: u64, now_ms: u64) -> Result<u64, HeartbeatError> {
        let Some(dev) = self.devices.get_mut(&device_id) else {
            return Err(HeartbeatError::UnknownDevice);
        };
        if now_ms < dev.last_heartbeat_ms {
            return Err(HeartbeatError::NonMonotonic);
        }
        dev.last_heartbeat_ms = now_ms;
        dev.lease_expires_ms = now_ms + self.lease_ttl_ms;
        self.stats.heartbeats_refreshed += 1;
        Ok(dev.lease_expires_ms)
    }

    /// Evict expired leases; returns the evicted rows (gateway raises
    /// `DeviceLost` per row). Idempotent, O(live devices).
    pub fn tick(&mut self, now_ms: u64) -> Vec<LostDevice> {
        let expired: Vec<u64> = self
            .devices
            .iter()
            .filter(|(_, d)| now_ms >= d.lease_expires_ms)
            .map(|(id, _)| *id)
            .collect();
        expired
            .into_iter()
            .filter_map(|id| {
                self.devices.remove(&id).map(|d| {
                    self.stats.devices_evicted += 1;
                    LostDevice {
                        device_id: d.device_id,
                        name: d.name,
                        device_type: d.device_type,
                        origin: d.origin,
                    }
                })
            })
            .collect()
    }

    /// Copy-on-read view of one device.
    pub fn get(&self, device_id: u64) -> Option<DeviceInfo> {
        self.devices.get(&device_id).cloned()
    }

    /// Snapshot sorted by device_id — deterministic iteration for
    /// telemetry and tests.
    pub fn list(&self) -> Vec<DeviceInfo> {
        let mut rows: Vec<DeviceInfo> = self.devices.values().cloned().collect();
        rows.sort_by_key(|d| d.device_id);
        rows
    }

    /// Devices that can render audio directly (handoff candidates).
    pub fn render_capable(&self) -> Vec<DeviceInfo> {
        let mut rows: Vec<DeviceInfo> = self
            .devices
            .values()
            .filter(|d| d.caps.direct_render)
            .cloned()
            .collect();
        rows.sort_by_key(|d| d.device_id);
        rows
    }
}

impl Default for DeviceRegistry {
    fn default() -> Self {
        Self::new(HEARTBEAT_INTERVAL_MS, LEASE_TTL_MS)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const TV_ID: u64 = 0xA11C_E001;

    fn tv_caps() -> DeviceCaps {
        DeviceCaps {
            codecs: CODEC_AAC | CODEC_PCM16,
            volume_steps: 100,
            direct_render: true,
        }
    }

    #[test]
    fn lease_expires_after_three_missed_heartbeats() {
        let mut reg = DeviceRegistry::new(5_000, 15_000);
        assert_eq!(
            reg.register_or_refresh(
                TV_ID,
                "Living Room TV",
                DeviceType::Tv,
                tv_caps(),
                DiscoveryOrigin::LanBeacon,
                1_000
            ),
            RegistryChange::Discovered { device_id: TV_ID }
        );
        // Heartbeats at 1s..11s keep the lease alive indefinitely.
        for t in [1_000u64, 6_000, 11_000] {
            reg.on_heartbeat(TV_ID, t).expect("live lease");
        }
        assert_eq!(reg.tick(11_000 + 14_999).len(), 0, "lease still warm");
        // 15 s after the LAST heartbeat → evicted.
        let lost = reg.tick(11_000 + 15_000);
        assert_eq!(lost.len(), 1);
        assert_eq!(lost[0].device_id, TV_ID);
        assert_eq!(lost[0].device_type, DeviceType::Tv);
        assert!(reg.get(TV_ID).is_none());
        // Heartbeat for the evicted device is now a hard error.
        assert_eq!(
            reg.on_heartbeat(TV_ID, 40_000),
            Err(HeartbeatError::UnknownDevice)
        );
    }

    #[test]
    fn heartbeat_refresh_slides_the_lease() {
        let mut reg = DeviceRegistry::default();
        reg.register_or_refresh(
            TV_ID,
            "TV",
            DeviceType::Tv,
            tv_caps(),
            DiscoveryOrigin::LanBeacon,
            0,
        );
        // 60 s of 5 s heartbeats: never evicted.
        let mut t = 0u64;
        while t < 60_000 {
            t += 5_000;
            reg.on_heartbeat(TV_ID, t).expect("refresh");
        }
        assert!(reg.tick(t).is_empty());
        assert_eq!(reg.stats().devices_evicted, 0);
    }

    #[test]
    fn non_monotonic_heartbeat_refused() {
        let mut reg = DeviceRegistry::default();
        reg.register_or_refresh(
            TV_ID,
            "TV",
            DeviceType::Tv,
            tv_caps(),
            DiscoveryOrigin::LanBeacon,
            10_000,
        );
        assert_eq!(
            reg.on_heartbeat(TV_ID, 9_999),
            Err(HeartbeatError::NonMonotonic)
        );
        // Lease untouched by the refused frame.
        assert_eq!(reg.get(TV_ID).unwrap().lease_expires_ms, 25_000);
    }

    #[test]
    fn capability_matrix_roundtrips_and_negotiates() {
        let tv = tv_caps();
        let phone = DeviceCaps::typical(DeviceType::Phone);
        // Negotiation = intersection.
        assert_eq!(tv.intersect(phone), CODEC_AAC | CODEC_PCM16);
        // Unknown codec bit round-trips through the registry.
        let mut reg = DeviceRegistry::default();
        let exotic = DeviceCaps {
            codecs: tv.codecs | 0x8000,
            volume_steps: 7,
            direct_render: false,
        };
        reg.register_or_refresh(
            TV_ID,
            "X",
            DeviceType::Speaker,
            exotic,
            DiscoveryOrigin::CloudRelay,
            0,
        );
        assert_eq!(reg.get(TV_ID).unwrap().caps, exotic);
        assert_eq!(reg.get(TV_ID).unwrap().origin, DiscoveryOrigin::CloudRelay);
    }

    #[test]
    fn volume_snapping_hits_the_step_grid() {
        let car = DeviceCaps {
            codecs: CODEC_SBC,
            volume_steps: 16,
            direct_render: true,
        };
        // 50% on a 16-step grid snaps to 50 (exact grid point).
        assert_eq!(car.snap_volume(50), 50);
        // 33% snaps to the nearest 6.25%-multiple (31 or 38 — nearest is 31).
        let snapped = car.snap_volume(33);
        assert!(snapped == 31 || snapped == 38, "snapped={snapped}");
        // Continuous device passes through; >100 clamps.
        let cont = DeviceCaps::default();
        assert_eq!(cont.snap_volume(77), 77);
        assert_eq!(cont.snap_volume(255), 100);
    }

    #[test]
    fn registry_capacity_bounds_hostile_floods() {
        let mut reg = DeviceRegistry::new(1, 1);
        for i in 0..MAX_REGISTRY_DEVICES as u64 {
            assert_eq!(
                reg.register_or_refresh(
                    i,
                    "d",
                    DeviceType::Speaker,
                    DeviceCaps::typical(DeviceType::Speaker),
                    DiscoveryOrigin::LanBeacon,
                    0
                ),
                RegistryChange::Discovered { device_id: i }
            );
        }
        assert_eq!(reg.len(), MAX_REGISTRY_DEVICES);
        // One beyond capacity: refused…
        assert_eq!(
            reg.register_or_refresh(
                9_999,
                "overflow",
                DeviceType::Speaker,
                DeviceCaps::typical(DeviceType::Speaker),
                DiscoveryOrigin::LanBeacon,
                0
            ),
            RegistryChange::CapacityFull
        );
        // …but an EXISTING device still refreshes.
        assert_eq!(
            reg.register_or_refresh(
                0,
                "d",
                DeviceType::Speaker,
                DeviceCaps::typical(DeviceType::Speaker),
                DiscoveryOrigin::LanBeacon,
                0
            ),
            RegistryChange::Refreshed { device_id: 0 }
        );
        // Eviction frees every slot again (TTL elapsed).
        reg.tick(10);
        assert!(reg.is_empty(), "1 ms leases all expired at t=10");
        assert_eq!(
            reg.register_or_refresh(
                9_999,
                "reborn",
                DeviceType::Speaker,
                DeviceCaps::typical(DeviceType::Speaker),
                DiscoveryOrigin::LanBeacon,
                10
            ),
            RegistryChange::Discovered { device_id: 9_999 }
        );
    }

    #[test]
    fn update_vs_refresh_classification() {
        let mut reg = DeviceRegistry::default();
        let caps = DeviceCaps::typical(DeviceType::Speaker);
        reg.register_or_refresh(
            TV_ID,
            "Speaker",
            DeviceType::Speaker,
            caps,
            DiscoveryOrigin::LanBeacon,
            0,
        );
        // Same row again → Refreshed.
        assert_eq!(
            reg.register_or_refresh(
                TV_ID,
                "Speaker",
                DeviceType::Speaker,
                caps,
                DiscoveryOrigin::LanBeacon,
                5_000
            ),
            RegistryChange::Refreshed { device_id: TV_ID }
        );
        // Renamed → Updated.
        assert_eq!(
            reg.register_or_refresh(
                TV_ID,
                "Kitchen Speaker",
                DeviceType::Speaker,
                caps,
                DiscoveryOrigin::LanBeacon,
                6_000
            ),
            RegistryChange::Updated { device_id: TV_ID }
        );
        // Roamed LAN → relay → Updated.
        assert_eq!(
            reg.register_or_refresh(
                TV_ID,
                "Kitchen Speaker",
                DeviceType::Speaker,
                caps,
                DiscoveryOrigin::CloudRelay,
                7_000
            ),
            RegistryChange::Updated { device_id: TV_ID }
        );
        assert_eq!(reg.stats().updates, 2);
    }

    #[test]
    fn device_type_wire_codes_are_total_and_frozen() {
        for code in 0u8..=5 {
            assert!(DeviceType::from_u8(code).is_some());
        }
        assert_eq!(DeviceType::from_u8(6), None);
        assert_eq!(DeviceType::from_u8(255), None);
        assert_eq!(DeviceType::Watch.as_u8(), 5);
    }

    #[test]
    fn names_are_sanitized_and_deduped_to_defaults() {
        let mut reg = DeviceRegistry::default();
        // Over-long name truncated; whitespace trimmed.
        let long = "X".repeat(300);
        reg.register_or_refresh(
            1,
            &long,
            DeviceType::Tv,
            DeviceCaps::default(),
            DiscoveryOrigin::LanBeacon,
            0,
        );
        assert_eq!(reg.get(1).unwrap().name.len(), MAX_DEVICE_NAME_LEN);
        // Blank name falls back to a stable synthetic name.
        reg.register_or_refresh(
            2,
            "   ",
            DeviceType::Watch,
            DeviceCaps::default(),
            DiscoveryOrigin::LanBeacon,
            0,
        );
        assert_eq!(reg.get(2).unwrap().name, "connect-0000000000000002");
    }
}
