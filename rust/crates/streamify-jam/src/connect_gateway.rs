//! connect_gateway.rs — Streamify Connect device-discovery gateway &
//! presence heartbeat listener (Phase 4, directive §2.1).
//!
//! MISSION: detect every Connect-enabled target around the user — Smart
//! Speakers, TVs, Android Auto head units, Wearables, Tablets, Phones —
//! over two independent presence channels, and keep the
//! [`DeviceRegistry`] leases warm so handoff candidates are always
//! enumerated and pre-negotiated:
//!   • LAN channel — UDP broadcast / multicast beacons carrying a
//!     self-describing SCNX advert (mDNS-style zeroconf semantics, but a
//!     frozen binary frame so speakers and watches can parse it in
//!     firmware without a DNS-SD stack).
//!   • Cloud relay channel — the same advert bytes arriving via the
//!     relay's presence topic for devices behind NAT (remote TV at the
//!     vacation home, watch left on LTE).
//!
//! ── FROZEN WIRE FORMAT ("SCNX frame family") ──────────────────────────
//! Deliberately a SEPARATE family from the mesh's 0x5354/46-byte header:
//! Connect targets are consumer devices, not Jam mesh peers — they speak
//! Connect, not gossip. Layout (all LE, strictly bounds-checked):
//!
//!   offset  field            len  notes
//!   0       magic u32         4    0x58_4E_43_53 ("SCNX" on the wire)
//!   4       version u8        1    0x01
//!   5       msg_type u8       1    0x01 ADVERT, 0x02 QUERY, 0x03 REPLY,
//!                                      0x04 HEARTBEAT
//!   6       device_id u64     8    truncated Blake3 of the device string
//!   14      device_type u8    1    DeviceType wire code (0..5)
//!   15      codecs u16        2    codec bitmask (append-only bits)
//!   17      volume_steps u8   1    0 = continuous, else step count
//!   18      direct_render u8  1    0/1
//!   19      name_len u8       1    0..=64, UTF-8, no NUL bytes
//!   20      name bytes        n    display name
//!   20+n    checksum_fnv1a u32 4   FNV-1a/32 over [0..20+n)
//!
//! Fixed head = 20 bytes; whole frame = 24..88 bytes — one cheap
//! datagram, trivially parseable by firmware.
//!
//! HOSTILE-INPUT DISCIPLINE (house rules): bad magic/version/msg_type,
//! truncated heads, checksum mismatch, non-UTF-8 or NUL-bearing names,
//! oversize frames → silently dropped with a counter; the listener never
//! panics and never allocates unbounded. Unknown trailing bytes between
//! the name and the checksum are IGNORED (forward compatibility — v2 may
//! grow the descriptor without breaking v1 listeners).
//!
//! PURE LOGIC: this module owns no sockets and no clocks — `now_ms` is
//! injected per call, and the UDP bind/relay subscribe plumbing lives at
//! the Kotlin/networking layer. That keeps the discovery state machine
//! deterministic and chaos-testable (see rust/tests/test_connect_gateway.rs).

use std::collections::VecDeque;

use blake3::Hasher;

use crate::device_registry::{
    DeviceCaps, DeviceRegistry, DeviceType, DiscoveryOrigin, LostDevice, RegistryChange,
    MAX_DEVICE_NAME_LEN,
};

/// SCNX magic: 'S','C','N','X' on the wire (u32 LE = 0x58_4E_43_53).
pub const CONNECT_MAGIC: u32 = 0x584E_4353;
/// Connect wire protocol version.
pub const CONNECT_VERSION: u8 = 0x01;
/// SCNX frame message types (frozen).
pub const MSG_PRESENCE_ADVERT: u8 = 0x01;
pub const MSG_PRESENCE_QUERY: u8 = 0x02;
pub const MSG_PRESENCE_REPLY: u8 = 0x03;
pub const MSG_HEARTBEAT: u8 = 0x04;
/// Fixed descriptor head length (everything before the name bytes).
pub const DESCRIPTOR_LEN: usize = 20;
/// Max whole-frame length: head + name + checksum + slack.
pub const MAX_CONNECT_FRAME: usize = DESCRIPTOR_LEN + MAX_DEVICE_NAME_LEN + 4 + 16;
/// Default LAN presence port (distinct from the mesh's 7777 — Connect
/// beacons and Jam mesh traffic must never collide on one socket).
pub const CONNECT_BEACON_PORT: u16 = 7788;

/// FNV-1a/32 over a slice (house checksum — matches the mesh family's
/// discipline; local copy because the mesh's is private).
fn fnv1a32(data: &[u8]) -> u32 {
    let mut hash: u32 = 0x811c_9dc5;
    for &b in data {
        hash ^= b as u32;
        hash = hash.wrapping_mul(0x0100_0193);
    }
    hash
}

/// Stable 64-bit device id from the platform device-id string (Kotlin
/// supplies e.g. a Settings.Secure.ANDROID_ID or a speaker DUID).
/// Truncated Blake3 — same derivation discipline as the mesh node id.
pub fn device_id_from_string(device_id: &str) -> u64 {
    let mut h = Hasher::new();
    h.update(device_id.as_bytes());
    let out = h.finalize();
    let mut id = [0u8; 8];
    id.copy_from_slice(&out.as_bytes()[..8]);
    u64::from_le_bytes(id)
}

// ─────────────────────────────────────────────── descriptor wire codec

/// A parsed SCNX presence descriptor (the mutable fields of a frame).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PresenceDescriptor {
    pub device_id: u64,
    pub device_type: DeviceType,
    pub caps: DeviceCaps,
    pub name: String,
}

/// Why a raw frame was refused at the boundary.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum FrameReject {
    Truncated,
    BadMagic,
    BadVersion,
    BadMsgType,
    BadDeviceType,
    BadName,
    BadChecksum,
    Oversize,
}

impl std::fmt::Display for FrameReject {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let s = match self {
            FrameReject::Truncated => "frame truncated below minimum length",
            FrameReject::BadMagic => "magic mismatch (not an SCNX frame)",
            FrameReject::BadVersion => "unsupported protocol version",
            FrameReject::BadMsgType => "unknown message type",
            FrameReject::BadDeviceType => "unknown device type code",
            FrameReject::BadName => "name field not clean UTF-8",
            FrameReject::BadChecksum => "FNV-1a checksum mismatch",
            FrameReject::Oversize => "frame exceeds the SCNX budget",
        };
        f.write_str(s)
    }
}

/// Encodes one SCNX frame with the given msg_type + descriptor. Used for
/// ADVERT / QUERY / REPLY / HEARTBEAT alike — the descriptor rides every
/// frame type so a single datagram both proves liveness and refreshes
/// the capability matrix.
pub fn encode_connect_frame(msg_type: u8, d: &PresenceDescriptor) -> Vec<u8> {
    let mut name: Vec<u8> = d.name.as_bytes().to_vec();
    name.truncate(MAX_DEVICE_NAME_LEN);
    let name_len = name.len().min(u8::MAX as usize) as u8;
    let mut buf = Vec::with_capacity(DESCRIPTOR_LEN + name.len() + 4);
    buf.extend_from_slice(&CONNECT_MAGIC.to_le_bytes());
    buf.push(CONNECT_VERSION);
    buf.push(msg_type);
    buf.extend_from_slice(&d.device_id.to_le_bytes());
    buf.push(d.device_type.as_u8());
    buf.extend_from_slice(&d.caps.codecs.to_le_bytes());
    buf.push(d.caps.volume_steps);
    buf.push(u8::from(d.caps.direct_render));
    buf.push(name_len);
    buf.extend_from_slice(&name);
    let sum = fnv1a32(&buf);
    buf.extend_from_slice(&sum.to_le_bytes());
    buf
}

/// Parses + validates one SCNX frame. Unknown trailing bytes between
/// name and checksum are skipped (forward compat). The checksum is
/// verified over the byte span it terminates.
pub fn parse_connect_frame(bytes: &[u8]) -> Result<(u8, PresenceDescriptor), FrameReject> {
    if bytes.len() < DESCRIPTOR_LEN + 4 {
        return Err(FrameReject::Truncated);
    }
    if bytes.len() > MAX_CONNECT_FRAME {
        return Err(FrameReject::Oversize);
    }
    if u32::from_le_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]) != CONNECT_MAGIC {
        return Err(FrameReject::BadMagic);
    }
    if bytes[4] != CONNECT_VERSION {
        return Err(FrameReject::BadVersion);
    }
    let msg_type = bytes[5];
    if !matches!(
        msg_type,
        MSG_PRESENCE_ADVERT | MSG_PRESENCE_QUERY | MSG_PRESENCE_REPLY | MSG_HEARTBEAT
    ) {
        return Err(FrameReject::BadMsgType);
    }
    let device_type = DeviceType::from_u8(bytes[14]).ok_or(FrameReject::BadDeviceType)?;
    let name_len = bytes[19] as usize;
    let name_end = DESCRIPTOR_LEN + name_len;
    if bytes.len() < name_end + 4 {
        return Err(FrameReject::Truncated);
    }
    let name =
        std::str::from_utf8(&bytes[DESCRIPTOR_LEN..name_end]).map_err(|_| FrameReject::BadName)?;
    if name.contains('\0') || name.chars().any(|c| c.is_control()) {
        return Err(FrameReject::BadName);
    }
    // Checksum terminates the frame: last 4 bytes of the valid span.
    let sum_off = bytes.len() - 4;
    let wire_sum = u32::from_le_bytes([
        bytes[sum_off],
        bytes[sum_off + 1],
        bytes[sum_off + 2],
        bytes[sum_off + 3],
    ]);
    if fnv1a32(&bytes[..sum_off]) != wire_sum {
        return Err(FrameReject::BadChecksum);
    }
    Ok((
        msg_type,
        PresenceDescriptor {
            device_id: u64::from_le_bytes([
                bytes[6], bytes[7], bytes[8], bytes[9], bytes[10], bytes[11], bytes[12], bytes[13],
            ]),
            device_type,
            caps: DeviceCaps {
                codecs: u16::from_le_bytes([bytes[15], bytes[16]]),
                volume_steps: bytes[17],
                direct_render: bytes[18] != 0,
            },
            name: name.to_string(),
        },
    ))
}

// ─────────────────────────────────────────────── gateway state machine

/// Lifecycle / discovery events surfaced to the app layer (JNI / tests).
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum GatewayEvent {
    DiscoveryStarted,
    DiscoveryStopped,
    DeviceDiscovered {
        device_id: u64,
        name: String,
        device_type: DeviceType,
        origin: DiscoveryOrigin,
    },
    /// Caps / name / origin changed on re-advert (capability negotiation
    /// may need to re-run).
    DeviceUpdated {
        device_id: u64,
        name: String,
        device_type: DeviceType,
        origin: DiscoveryOrigin,
    },
    /// Lease expired — the device missed 3 heartbeats.
    DeviceLost {
        device_id: u64,
        name: String,
        device_type: DeviceType,
        origin: DiscoveryOrigin,
    },
    /// A presence QUERY arrived (from another phone enumerating targets).
    /// The host answers with [`ConnectGateway::build_presence_reply`].
    PresenceQuery {
        from_device_id: u64,
        name: String,
    },
}

/// Boundary counters (hostile-input observability).
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct GatewayBoundaryStats {
    pub frames_accepted: u64,
    pub frames_rejected: u64,
    pub adverts_lan: u64,
    pub adverts_relay: u64,
    pub heartbeats: u64,
    pub queries_seen: u64,
}

/// The Connect gateway engine: feeds the [`DeviceRegistry`] from the LAN
/// beacon listener and the cloud relay presence listener, and emits the
/// advert/query/reply frames the transport layers put on the wire.
#[derive(Debug, Clone)]
pub struct ConnectGateway {
    self_descriptor: PresenceDescriptor,
    registry: DeviceRegistry,
    discovery_active: bool,
    events: VecDeque<GatewayEvent>,
    boundary: GatewayBoundaryStats,
}

impl ConnectGateway {
    /// Creates the gateway for this host device. `device_id` is the
    /// platform device-id string (hashed to the 64-bit wire id).
    pub fn new(
        device_id: &str,
        device_name: &str,
        device_type: DeviceType,
        caps: DeviceCaps,
        heartbeat_interval_ms: u64,
        lease_ttl_ms: u64,
    ) -> Self {
        ConnectGateway {
            self_descriptor: PresenceDescriptor {
                device_id: device_id_from_string(device_id),
                device_type,
                caps,
                name: device_name.to_string(),
            },
            registry: DeviceRegistry::new(heartbeat_interval_ms, lease_ttl_ms),
            discovery_active: false,
            events: VecDeque::new(),
            boundary: GatewayBoundaryStats::default(),
        }
    }

    pub fn self_device_id(&self) -> u64 {
        self.self_descriptor.device_id
    }

    pub fn self_descriptor(&self) -> &PresenceDescriptor {
        &self.self_descriptor
    }

    pub fn discovery_active(&self) -> bool {
        self.discovery_active
    }

    pub fn registry(&self) -> &DeviceRegistry {
        &self.registry
    }

    pub fn boundary_stats(&self) -> GatewayBoundaryStats {
        self.boundary
    }

    /// Starts listening / advertising. Idempotent: a second start is a
    /// no-op returning false (no spurious DiscoveryStarted events).
    pub fn start_discovery(&mut self) -> bool {
        if self.discovery_active {
            return false;
        }
        self.discovery_active = true;
        self.events.push_back(GatewayEvent::DiscoveryStarted);
        true
    }

    /// Stops listening / advertising. Idempotent; live leases are kept —
    /// they still age out via [`Self::tick`], so a stop/start cycle
    /// never resurrects dead devices.
    pub fn stop_discovery(&mut self) -> bool {
        if !self.discovery_active {
            return false;
        }
        self.discovery_active = false;
        self.events.push_back(GatewayEvent::DiscoveryStopped);
        true
    }

    // ── outbound frame builders (transport layers put these on wire) ──

    /// The LAN beacon body: our full advert. Broadcast every
    /// `heartbeat_interval` while discovery is active.
    pub fn build_presence_advert(&self) -> Vec<u8> {
        encode_connect_frame(MSG_PRESENCE_ADVERT, &self.self_descriptor)
    }

    /// A one-shot enumeration probe (sent on discovery start; speakers
    /// in deep sleep answer with adverts they would otherwise withhold).
    pub fn build_presence_query(&self) -> Vec<u8> {
        encode_connect_frame(MSG_PRESENCE_QUERY, &self.self_descriptor)
    }

    /// Unicast answer to a received QUERY.
    pub fn build_presence_reply(&self) -> Vec<u8> {
        encode_connect_frame(MSG_PRESENCE_REPLY, &self.self_descriptor)
    }

    /// Bare liveness frame (cheaper than a full advert for steady state).
    pub fn build_heartbeat(&self) -> Vec<u8> {
        encode_connect_frame(MSG_HEARTBEAT, &self.self_descriptor)
    }

    // ── inbound presence ingestion (LAN beacon + cloud relay) ──

    fn ingest_frame(&mut self, bytes: &[u8], origin: DiscoveryOrigin, now_ms: u64) -> bool {
        if !self.discovery_active {
            // Frames still accepted while stopped? No: a stopped gateway
            // is off the air; late datagrams are counted, not applied.
            self.boundary.frames_rejected += 1;
            return false;
        }
        let (msg_type, desc) = match parse_connect_frame(bytes) {
            Ok(v) => v,
            Err(_) => {
                self.boundary.frames_rejected += 1;
                return false;
            }
        };
        self.boundary.frames_accepted += 1;
        if desc.device_id == self.self_descriptor.device_id {
            // Our own beacon echoed back by the LAN — count, never register.
            return true;
        }
        match msg_type {
            MSG_PRESENCE_ADVERT | MSG_PRESENCE_REPLY => {
                match origin {
                    DiscoveryOrigin::LanBeacon => self.boundary.adverts_lan += 1,
                    DiscoveryOrigin::CloudRelay => self.boundary.adverts_relay += 1,
                }
                let change = self.registry.register_or_refresh(
                    desc.device_id,
                    &desc.name,
                    desc.device_type,
                    desc.caps,
                    origin,
                    now_ms,
                );
                self.raise(change, &desc, origin);
                true
            }
            MSG_HEARTBEAT => {
                self.boundary.heartbeats += 1;
                if self.registry.on_heartbeat(desc.device_id, now_ms).is_ok() {
                    return true;
                }
                // Unknown device heartbeat → fall back to full
                // registration (a device that rebooted between advert
                // and heartbeat).
                let change = self.registry.register_or_refresh(
                    desc.device_id,
                    &desc.name,
                    desc.device_type,
                    desc.caps,
                    origin,
                    now_ms,
                );
                self.raise(change, &desc, origin);
                true
            }
            MSG_PRESENCE_QUERY => {
                self.boundary.queries_seen += 1;
                // A peer enumerating targets: surface it (the host layer
                // unicasts build_presence_reply back) but ALSO register
                // it — a query proves presence as well as an advert.
                let change = self.registry.register_or_refresh(
                    desc.device_id,
                    &desc.name,
                    desc.device_type,
                    desc.caps,
                    origin,
                    now_ms,
                );
                self.raise(change, &desc, origin);
                self.events.push_back(GatewayEvent::PresenceQuery {
                    from_device_id: desc.device_id,
                    name: desc.name.clone(),
                });
                true
            }
            _ => unreachable!("msg_type validated above"),
        }
    }

    fn raise(
        &mut self,
        change: RegistryChange,
        desc: &PresenceDescriptor,
        origin: DiscoveryOrigin,
    ) {
        match change {
            RegistryChange::Discovered { device_id } => {
                self.events.push_back(GatewayEvent::DeviceDiscovered {
                    device_id,
                    name: desc.name.clone(),
                    device_type: desc.device_type,
                    origin,
                });
            }
            RegistryChange::Updated { device_id } => {
                self.events.push_back(GatewayEvent::DeviceUpdated {
                    device_id,
                    name: desc.name.clone(),
                    device_type: desc.device_type,
                    origin,
                });
            }
            // Refreshes and capacity refusals raise nothing: the device
            // is already known (or refused) — no observable transition.
            RegistryChange::Refreshed { .. }
            | RegistryChange::UnknownHeartbeat { .. }
            | RegistryChange::CapacityFull => {}
        }
    }

    /// LAN beacon arrived (UDP broadcast / multicast listener).
    pub fn on_lan_beacon(&mut self, bytes: &[u8], now_ms: u64) -> bool {
        self.ingest_frame(bytes, DiscoveryOrigin::LanBeacon, now_ms)
    }

    /// Cloud relay presence record arrived (relay subscriber). Same
    /// bytes, different channel — origin is what distinguishes them.
    pub fn on_relay_presence(&mut self, bytes: &[u8], now_ms: u64) -> bool {
        self.ingest_frame(bytes, DiscoveryOrigin::CloudRelay, now_ms)
    }

    /// Lease housekeeping: evicts devices whose heartbeats went silent
    /// (3 × heartbeat interval past their last beat) and queues
    /// `DeviceLost` events. Returns the eviction count (telemetry /
    /// tests). Call on the host's housekeeping cadence.
    pub fn tick(&mut self, now_ms: u64) -> usize {
        let lost: Vec<LostDevice> = self.registry.tick(now_ms);
        let n = lost.len();
        for d in lost {
            self.events.push_back(GatewayEvent::DeviceLost {
                device_id: d.device_id,
                name: d.name,
                device_type: d.device_type,
                origin: d.origin,
            });
        }
        n
    }

    /// Drains pending lifecycle events (JNI fan-out / test assertions).
    pub fn drain_events(&mut self) -> Vec<GatewayEvent> {
        self.events.drain(..).collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::device_registry::{CODEC_AAC, CODEC_SBC, LEASE_TTL_MS};

    fn speaker_desc(id: &str, name: &str) -> PresenceDescriptor {
        PresenceDescriptor {
            device_id: device_id_from_string(id),
            device_type: DeviceType::Speaker,
            caps: DeviceCaps {
                codecs: CODEC_SBC | CODEC_AAC,
                volume_steps: 32,
                direct_render: true,
            },
            name: name.to_string(),
        }
    }

    fn gateway() -> ConnectGateway {
        ConnectGateway::new(
            "host-phone",
            "Zephyr's Phone",
            DeviceType::Phone,
            DeviceCaps::typical(DeviceType::Phone),
            5_000,
            LEASE_TTL_MS,
        )
    }

    #[test]
    fn frame_roundtrip_all_types() {
        for t in [
            MSG_PRESENCE_ADVERT,
            MSG_PRESENCE_QUERY,
            MSG_PRESENCE_REPLY,
            MSG_HEARTBEAT,
        ] {
            let d = speaker_desc("spk-1", "Kitchen Speaker");
            let bytes = encode_connect_frame(t, &d);
            assert!(bytes.len() <= MAX_CONNECT_FRAME);
            let (mt, back) = parse_connect_frame(&bytes).expect("roundtrip");
            assert_eq!(mt, t);
            assert_eq!(back, d);
        }
    }

    #[test]
    fn frame_budget_is_datagram_tiny() {
        let d = PresenceDescriptor {
            device_id: u64::MAX,
            device_type: DeviceType::Watch,
            caps: DeviceCaps::typical(DeviceType::Watch),
            name: "W".repeat(MAX_DEVICE_NAME_LEN),
        };
        let bytes = encode_connect_frame(MSG_PRESENCE_ADVERT, &d);
        assert_eq!(bytes.len(), DESCRIPTOR_LEN + MAX_DEVICE_NAME_LEN + 4);
        assert_eq!(
            parse_connect_frame(&bytes).unwrap().1.name.len(),
            MAX_DEVICE_NAME_LEN
        );
    }

    #[test]
    fn hostile_frames_die_at_the_boundary() {
        let d = speaker_desc("spk-1", "Kitchen Speaker");
        let good = encode_connect_frame(MSG_PRESENCE_ADVERT, &d);

        // Truncated.
        for cut in [0usize, 4, 15, DESCRIPTOR_LEN + 2] {
            assert_eq!(
                parse_connect_frame(&good[..cut]),
                Err(FrameReject::Truncated)
            );
        }
        // Bad magic.
        let mut bad = good.clone();
        bad[0] ^= 0xFF;
        assert_eq!(parse_connect_frame(&bad), Err(FrameReject::BadMagic));
        // Bad version.
        let mut bad = good.clone();
        bad[4] = 0x02;
        assert_eq!(parse_connect_frame(&bad), Err(FrameReject::BadVersion));
        // Unknown msg type.
        let mut bad = good.clone();
        bad[5] = 0x7F;
        assert_eq!(parse_connect_frame(&bad), Err(FrameReject::BadMsgType));
        // Unknown device type code.
        let mut bad = good.clone();
        bad[14] = 6;
        assert_eq!(parse_connect_frame(&bad), Err(FrameReject::BadDeviceType));
        // Bit-flipped in the descriptor body → checksum mismatch. (A
        // name_len flip can legitimately surface as Truncated instead —
        // both are hard rejections, so it stays out of this loop.)
        for bit in [6usize, 7, 13, 15, 22] {
            let mut bad = good.clone();
            bad[bit] ^= 0x40;
            assert_eq!(
                parse_connect_frame(&bad),
                Err(FrameReject::BadChecksum),
                "bit {bit}"
            );
        }
        // Name length lying past the buffer.
        let mut bad = good.clone();
        bad[19] = 60; // claims 60 bytes; actual name is 15
        assert_eq!(parse_connect_frame(&bad), Err(FrameReject::Truncated));
        // Oversize.
        let mut huge = vec![0u8; MAX_CONNECT_FRAME + 1];
        huge[..good.len()].copy_from_slice(&good);
        assert_eq!(parse_connect_frame(&huge), Err(FrameReject::Oversize));
    }

    #[test]
    fn forward_compat_trailing_bytes_are_ignored() {
        let d = speaker_desc("spk-1", "Kitchen Speaker");
        let mut v1 = encode_connect_frame(MSG_PRESENCE_ADVERT, &d);
        // Simulate a v2 sender appending a field BEFORE the checksum —
        // and RECOMPUTING the checksum over the grown span (the sum
        // covers everything it terminates, so v2 fields are protected
        // against corruption too; a v1 listener ignores their meaning).
        v1.truncate(v1.len() - 4); // strip the v1 checksum
        v1.extend_from_slice(&[0xEE, 0x01, 0x02]); // future field
        let news = fnv1a32(&v1).to_le_bytes();
        v1.extend_from_slice(&news);
        let (mt, back) = parse_connect_frame(&v1).expect("v1 listener tolerates v2 sender");
        assert_eq!(mt, MSG_PRESENCE_ADVERT);
        assert_eq!(back, d);
        // A v2 field corrupted in flight still trips the checksum.
        let mut bad = v1.clone();
        let last = bad.len() - 5; // inside the future field
        bad[last] ^= 0x55;
        assert_eq!(parse_connect_frame(&bad), Err(FrameReject::BadChecksum));
    }

    #[test]
    fn discovery_lifecycle_and_lease_events() {
        let mut gw = gateway();
        // Ingest while stopped → refused.
        let advert = encode_connect_frame(
            MSG_PRESENCE_ADVERT,
            &speaker_desc("spk-1", "Kitchen Speaker"),
        );
        assert!(!gw.on_lan_beacon(&advert, 1_000));
        assert_eq!(gw.boundary_stats().frames_rejected, 1);

        assert!(gw.start_discovery());
        assert!(!gw.start_discovery()); // idempotent
        let events = gw.drain_events();
        assert_eq!(events, vec![GatewayEvent::DiscoveryStarted]);

        // LAN advert registers the speaker.
        assert!(gw.on_lan_beacon(&advert, 2_000));
        let spk_id = device_id_from_string("spk-1");
        assert_eq!(
            gw.drain_events(),
            vec![GatewayEvent::DeviceDiscovered {
                device_id: spk_id,
                name: "Kitchen Speaker".into(),
                device_type: DeviceType::Speaker,
                origin: DiscoveryOrigin::LanBeacon,
            }]
        );

        // Cloud relay advert for a remote TV.
        let tv = PresenceDescriptor {
            device_id: device_id_from_string("tv-remote"),
            device_type: DeviceType::Tv,
            caps: DeviceCaps::typical(DeviceType::Tv),
            name: "Vacation TV".into(),
        };
        assert!(gw.on_relay_presence(&encode_connect_frame(MSG_PRESENCE_ADVERT, &tv), 2_500));
        let tv_id = device_id_from_string("tv-remote");
        assert!(gw.drain_events().iter().any(|e| matches!(
            e,
            GatewayEvent::DeviceDiscovered { device_id, origin: DiscoveryOrigin::CloudRelay, .. } if *device_id == tv_id
        )));

        // Own echo: counted, never registered.
        let own = gw.build_presence_advert();
        assert!(gw.on_lan_beacon(&own, 3_000));
        assert_eq!(gw.registry().len(), 2);

        // Steady-state heartbeats keep the speaker alive; the remote TV
        // refreshes via one more relay advert. Then the speaker goes
        // silent and its lease expires (5 s cadence / 15 s TTL).
        let hb = encode_connect_frame(MSG_HEARTBEAT, &speaker_desc("spk-1", "Kitchen Speaker"));
        for t in [5_000u64, 10_000, 15_000] {
            assert!(gw.on_lan_beacon(&hb, t));
        }
        assert!(gw.on_relay_presence(&encode_connect_frame(MSG_PRESENCE_ADVERT, &tv), 16_000));
        // Speaker lease: last hb 15 s → expires 30 s. TV: 16 s → 31 s.
        gw.tick(29_999);
        assert_eq!(gw.registry().len(), 2, "both leases warm");
        gw.tick(30_000);
        let lost = gw.drain_events();
        assert!(lost.iter().any(|e| matches!(
            e,
            GatewayEvent::DeviceLost { device_id, device_type: DeviceType::Speaker, .. } if *device_id == spk_id
        )));
        assert_eq!(gw.registry().len(), 1, "TV lease still warm until 31 s");
        let tv_lost = gw.tick(31_000);
        assert_eq!(tv_lost, 1);
        assert_eq!(gw.registry().len(), 0);
        assert_eq!(
            gw.drain_events(),
            vec![GatewayEvent::DeviceLost {
                device_id: tv_id,
                name: "Vacation TV".into(),
                device_type: DeviceType::Tv,
                origin: DiscoveryOrigin::CloudRelay,
            }]
        );

        assert!(gw.stop_discovery());
        assert!(!gw.stop_discovery()); // idempotent
        assert_eq!(gw.drain_events(), vec![GatewayEvent::DiscoveryStopped]);
    }

    #[test]
    fn presence_query_registers_peer_and_surfaces_event() {
        let mut gw = gateway();
        gw.start_discovery();
        gw.drain_events();
        let q = encode_connect_frame(
            MSG_PRESENCE_QUERY,
            &speaker_desc("other-phone", "Sarah's Phone"),
        );
        assert!(gw.on_lan_beacon(&q, 100));
        let events = gw.drain_events();
        let id = device_id_from_string("other-phone");
        assert!(events.iter().any(|e| matches!(
            e,
            GatewayEvent::PresenceQuery { from_device_id, .. } if *from_device_id == id
        )));
        // The querying phone itself is registered (a query is presence).
        assert!(gw.registry().get(id).is_some());
        assert_eq!(gw.boundary_stats().queries_seen, 1);
    }

    #[test]
    fn rebooted_device_heartbeat_reregisters() {
        let mut gw = gateway();
        gw.start_discovery();
        gw.drain_events();
        let d = speaker_desc("spk-9", "Nursery Speaker");
        let advert = encode_connect_frame(MSG_PRESENCE_ADVERT, &d);
        gw.on_lan_beacon(&advert, 0);
        gw.drain_events();
        // Lease expires while the speaker reboots…
        gw.tick(LEASE_TTL_MS + 1);
        gw.drain_events();
        // …and the FIRST frame after reboot is a bare heartbeat (the
        // advert socket is not up yet). It must re-register the device.
        let hb = encode_connect_frame(MSG_HEARTBEAT, &d);
        assert!(gw.on_lan_beacon(&hb, LEASE_TTL_MS + 2));
        let events = gw.drain_events();
        assert!(
            events.iter().any(|e| matches!(
                e,
                GatewayEvent::DeviceDiscovered { device_id, .. } if *device_id == d.device_id
            )),
            "post-reboot heartbeat must re-register: {events:?}"
        );
        assert!(gw.registry().get(d.device_id).is_some());
    }

    #[test]
    fn device_id_derivation_is_stable_and_distinct() {
        assert_eq!(device_id_from_string("tv-1"), device_id_from_string("tv-1"));
        assert_ne!(device_id_from_string("tv-1"), device_id_from_string("tv-2"));
        assert_ne!(device_id_from_string(""), device_id_from_string("a"));
    }
}
