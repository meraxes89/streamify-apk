//! test_connect_gateway.rs — Phase 4 Connect gateway integration suite
//! (directive §2.5).
//!
//! Proves the discovery layer end-to-end at the ENGINE level (both
//! gateways exchanging real SCNX frames through each other's boundary):
//!   • lease expiration after three missed heartbeats (multi-device,
//!     staggered evictions, DeviceLost events)
//!   • heartbeat refresh keeps devices alive indefinitely
//!   • capability negotiation: codec intersection, unknown-bit
//!     preservation across the wire, volume grid snapping
//!   • LAN beacon discovery + cloud relay presence + query/reply flows
//!     between two full gateways
//!   • boundary hostility: corrupt frames counted, never registered

use streamify_jam::connect_gateway::{
    encode_connect_frame, parse_connect_frame, ConnectGateway, GatewayEvent, PresenceDescriptor,
    MSG_HEARTBEAT, MSG_PRESENCE_ADVERT, MSG_PRESENCE_QUERY, MSG_PRESENCE_REPLY,
};
use streamify_jam::device_registry::{
    DeviceCaps, DeviceType, DiscoveryOrigin, CODEC_AAC, CODEC_APTX, CODEC_OPUS, CODEC_PCM16,
    CODEC_SBC, HEARTBEAT_INTERVAL_MS, LEASE_TTL_MS,
};

fn phone_gw() -> ConnectGateway {
    ConnectGateway::new(
        "phone-a",
        "Zephyr's Phone",
        DeviceType::Phone,
        DeviceCaps::typical(DeviceType::Phone),
        HEARTBEAT_INTERVAL_MS,
        LEASE_TTL_MS,
    )
}

fn tv_desc() -> PresenceDescriptor {
    PresenceDescriptor {
        device_id: streamify_jam::connect_gateway::device_id_from_string("tv-living"),
        device_type: DeviceType::Tv,
        caps: DeviceCaps::typical(DeviceType::Tv),
        name: "Living Room TV".into(),
    }
}

fn speaker_desc(id: &str, name: &str) -> PresenceDescriptor {
    PresenceDescriptor {
        device_id: streamify_jam::connect_gateway::device_id_from_string(id),
        device_type: DeviceType::Speaker,
        caps: DeviceCaps {
            codecs: CODEC_SBC | CODEC_AAC | CODEC_OPUS,
            volume_steps: 32,
            direct_render: true,
        },
        name: name.into(),
    }
}

#[test]
fn lease_expiration_after_three_missed_heartbeats_multi_device() {
    let mut gw = phone_gw();
    gw.start_discovery();
    gw.drain_events();

    // Three devices register at t=1s.
    for d in [
        speaker_desc("spk-1", "Kitchen"),
        speaker_desc("spk-2", "Bedroom"),
        tv_desc(),
    ] {
        assert!(gw.on_lan_beacon(&encode_connect_frame(MSG_PRESENCE_ADVERT, &d), 1_000));
    }
    assert_eq!(gw.registry().len(), 3);

    // Only the Kitchen speaker keeps heart-beating (5 s cadence).
    let kitchen = speaker_desc("spk-1", "Kitchen");
    let hb = encode_connect_frame(MSG_HEARTBEAT, &kitchen);
    for t in [6_000u64, 11_000, 16_000, 21_000] {
        assert!(gw.on_lan_beacon(&hb, t));
    }
    // At t=16s (their 1s + 15s deadline), Bedroom + TV evict; Kitchen
    // survives (last hb 16s → deadline 31s).
    let lost = gw.tick(16_001);
    assert_eq!(lost, 2, "Bedroom + TV must be evicted on lease expiry");
    let events = gw.drain_events();
    assert!(events.iter().any(|e| matches!(
        e,
        GatewayEvent::DeviceLost { name, .. } if name == "Bedroom"
    )));
    assert!(events.iter().any(|e| matches!(
        e,
        GatewayEvent::DeviceLost {
            device_type: DeviceType::Tv,
            ..
        }
    )));
    assert_eq!(gw.registry().len(), 1);

    // The survivor's lease keeps sliding (last hb 21 s → expiry 36 s).
    assert_eq!(gw.tick(30_999), 0);
    assert_eq!(gw.tick(35_999), 0);
    assert_eq!(gw.tick(36_000), 1, "Kitchen finally silent too");
    assert!(gw.registry().is_empty());
}

#[test]
fn heartbeat_refresh_slides_forever() {
    let mut gw = phone_gw();
    gw.start_discovery();
    let spk = speaker_desc("spk-main", "Main");
    assert!(gw.on_lan_beacon(&encode_connect_frame(MSG_PRESENCE_ADVERT, &spk), 0));
    // Two hours of 5 s heartbeats — never evicted, no DeviceLost events.
    let hb = encode_connect_frame(MSG_HEARTBEAT, &spk);
    let mut t = 0u64;
    let mut lost_events = 0;
    while t < 7_200_000 {
        t += HEARTBEAT_INTERVAL_MS;
        assert!(gw.on_lan_beacon(&hb, t));
        gw.tick(t);
        lost_events += gw
            .drain_events()
            .into_iter()
            .filter(|e| matches!(e, GatewayEvent::DeviceLost { .. }))
            .count();
    }
    assert_eq!(lost_events, 0);
    assert_eq!(gw.registry().len(), 1);
}

#[test]
fn capability_negotiation_across_the_wire() {
    let mut gw = phone_gw();
    gw.start_discovery();
    // A speaker with an unknown future codec bit (0x8000) + APTX.
    let mut exotic = speaker_desc("spk-x", "Future Speaker");
    exotic.caps.codecs |= 0x8000 | CODEC_APTX;
    let advert = encode_connect_frame(MSG_PRESENCE_ADVERT, &exotic);
    assert!(gw.on_lan_beacon(&advert, 0));

    let id = streamify_jam::connect_gateway::device_id_from_string("spk-x");
    let caps = gw.registry().get(id).expect("registered").caps;

    // Unknown bit + APTX survive the round-trip (append-only matrix).
    assert_eq!(caps.codecs, exotic.caps.codecs);

    // Negotiation: intersection with the phone's typical codec set.
    let phone = DeviceCaps::typical(DeviceType::Phone);
    let shared = phone.intersect(caps);
    assert_eq!(
        shared,
        CODEC_AAC | CODEC_OPUS,
        "SBC/APTX/unknown not shared; AAC+Opus shared"
    );
    // A phone and a TV share PCM16 + AAC.
    let tv = DeviceCaps::typical(DeviceType::Tv);
    assert_eq!(phone.intersect(tv), CODEC_AAC | CODEC_PCM16 | CODEC_OPUS);
    // Watch cannot direct-render: not a handoff target.
    let watch = DeviceCaps::typical(DeviceType::Watch);
    assert!(!watch.direct_render);
    // Volume snapping lands on the device's grid.
    assert_eq!(tv.snap_volume(43), 43, "100-step TV grid is identity");
    let snapped = caps.snap_volume(43);
    assert_eq!(caps.volume_steps, 32);
    assert!(
        snapped == 41 || snapped == 44,
        "32-step grid snaps near 43: {snapped}"
    );
}

#[test]
fn two_gateways_discover_each_other_via_lan_and_relay() {
    let mut phone = phone_gw();
    let mut tv = ConnectGateway::new(
        "tv-living",
        "Living Room TV",
        DeviceType::Tv,
        DeviceCaps::typical(DeviceType::Tv),
        HEARTBEAT_INTERVAL_MS,
        LEASE_TTL_MS,
    );
    phone.start_discovery();
    tv.start_discovery();
    phone.drain_events();
    tv.drain_events();

    // The phone broadcasts an advert; the TV ingests it.
    assert!(tv.on_lan_beacon(&phone.build_presence_advert(), 1_000));
    assert!(tv.drain_events().iter().any(|e| matches!(
        e,
        GatewayEvent::DeviceDiscovered { name, device_type: DeviceType::Phone, origin: DiscoveryOrigin::LanBeacon, .. }
            if name == "Zephyr's Phone"
    )));

    // The TV replies to the phone's query probe; the phone registers the
    // TV from the REPLY (a reply is presence).
    let query = phone.build_presence_query();
    assert!(tv.on_lan_beacon(&query, 2_000));
    assert!(tv
        .drain_events()
        .iter()
        .any(|e| matches!(e, GatewayEvent::PresenceQuery { .. })));
    let reply = tv.build_presence_reply();
    assert!(phone.on_lan_beacon(&reply, 2_100));
    assert!(phone.drain_events().iter().any(|e| matches!(
        e,
        GatewayEvent::DeviceDiscovered {
            device_type: DeviceType::Tv,
            origin: DiscoveryOrigin::LanBeacon,
            ..
        }
    )));

    // A remote watch behind NAT appears via the cloud relay channel.
    let mut watch = ConnectGateway::new(
        "watch-far",
        "Pixel Watch",
        DeviceType::Watch,
        DeviceCaps::typical(DeviceType::Watch),
        HEARTBEAT_INTERVAL_MS,
        LEASE_TTL_MS,
    );
    watch.start_discovery();
    let _ = watch.drain_events();
    assert!(phone.on_relay_presence(&watch.build_presence_advert(), 3_000));
    assert!(phone.drain_events().iter().any(|e| matches!(
        e,
        GatewayEvent::DeviceDiscovered { name, device_type: DeviceType::Watch, origin: DiscoveryOrigin::CloudRelay, .. }
            if name == "Pixel Watch"
    )));

    // The registry snapshot carries the negotiated matrix for the app.
    let devices = phone.registry().list();
    assert_eq!(devices.len(), 2);
    let tv_row = devices
        .iter()
        .find(|d| d.device_type == DeviceType::Tv)
        .expect("tv registered");
    assert!(tv_row.caps.direct_render);
    // Only direct-render devices are handoff candidates.
    assert_eq!(phone.registry().render_capable().len(), 1);

    // Own beacons echoed back are counted, never self-registered.
    assert!(phone.on_lan_beacon(&phone.build_presence_advert(), 4_000));
    assert_eq!(phone.registry().len(), 2);
}

#[test]
fn corrupt_frames_counted_and_never_registered() {
    let mut gw = phone_gw();
    gw.start_discovery();
    gw.drain_events();
    let good = encode_connect_frame(MSG_PRESENCE_ADVERT, &tv_desc());
    let rejected_before = gw.boundary_stats().frames_rejected;

    // Truncated, bad magic, bad checksum — all refused.
    let mut cases: Vec<Vec<u8>> = vec![good[..20].to_vec(), good.clone()];
    cases[1][0] ^= 0xFF;
    let mut bitflip = good.clone();
    bitflip[17] ^= 0x10; // volume_steps field → checksum catches
    cases.push(bitflip);
    for bytes in &cases {
        assert!(!gw.on_lan_beacon(bytes, 0));
    }
    assert!(gw.registry().is_empty());
    assert_eq!(
        gw.boundary_stats().frames_rejected,
        rejected_before + cases.len() as u64
    );

    // The pristine frame still registers fine afterwards.
    assert!(gw.on_lan_beacon(&good, 1_000));
    assert_eq!(gw.registry().len(), 1);
}

#[test]
fn stopped_gateway_ignores_late_datagrams() {
    let mut gw = phone_gw();
    gw.start_discovery();
    gw.drain_events();
    gw.stop_discovery();
    gw.drain_events();
    // A late LAN datagram after stop: counted, not applied.
    assert!(!gw.on_lan_beacon(
        &encode_connect_frame(MSG_PRESENCE_ADVERT, &tv_desc()),
        1_000
    ));
    assert!(gw.registry().is_empty());
    assert!(gw.boundary_stats().frames_rejected >= 1);
    // Restart admits a fresh advert.
    gw.start_discovery();
    gw.drain_events();
    assert!(gw.on_lan_beacon(
        &encode_connect_frame(MSG_PRESENCE_ADVERT, &tv_desc()),
        2_000
    ));
    assert_eq!(gw.registry().len(), 1);
}

#[test]
fn parse_roundtrip_all_frame_types_and_origins() {
    // Codec-level sanity across every msg type and device class.
    for (dt, _) in [
        (DeviceType::Phone, 0),
        (DeviceType::Tablet, 1),
        (DeviceType::Speaker, 2),
        (DeviceType::Tv, 3),
        (DeviceType::Car, 4),
        (DeviceType::Watch, 5),
    ] {
        let d = PresenceDescriptor {
            device_id: 0xABCD_1234_5678_9ABC,
            device_type: dt,
            caps: DeviceCaps::typical(dt),
            name: format!("Dev-{dt}"),
        };
        for t in [
            MSG_PRESENCE_ADVERT,
            MSG_PRESENCE_QUERY,
            MSG_PRESENCE_REPLY,
            MSG_HEARTBEAT,
        ] {
            let bytes = encode_connect_frame(t, &d);
            let (mt, back) = parse_connect_frame(&bytes).expect("roundtrip");
            assert_eq!(mt, t);
            assert_eq!(back, d);
        }
    }
}
