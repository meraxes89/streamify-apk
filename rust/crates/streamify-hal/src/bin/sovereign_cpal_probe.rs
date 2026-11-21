//! Sovereign HAL Phase 1 (D5 diagnostic): a MINIMAL cpal-only output
//! callback counter — no engine, no ring, no clock. Isolates whether
//! the host delivers sustained callbacks on a given device (the
//! loopback legs' one-callback mystery: RustAudio/cpal#487-adjacent).
//!
//! Usage: sovereign-cpal-probe [--device NAME] [--seconds N]
//! Prints one JSON line: {"callbacks": N, "seconds": T}

use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};

fn main() {
    let mut args = std::env::args().skip(1);
    let mut device_name: Option<String> = None;
    let mut seconds = 3.0f64;
    while let Some(a) = args.next() {
        match a.as_str() {
            "--device" => device_name = args.next(),
            "--seconds" => {
                if let Some(s) = args.next() {
                    if let Ok(v) = s.parse::<f64>() {
                        seconds = v.clamp(0.5, 30.0);
                    }
                }
            }
            _ => {}
        }
    }
    let host = cpal::default_host();
    let device = match device_name.as_deref() {
        Some(name) => host
            .output_devices()
            .ok()
            .and_then(|mut ds| {
                ds.find(|d| {
                    cpal::traits::DeviceTrait::description(d)
                        .map(|desc| desc.name().contains(name))
                        .unwrap_or(false)
                })
            })
            .expect("device not found"),
        None => host.default_output_device().expect("no default output device"),
    };
    let cfg = cpal::traits::DeviceTrait::default_output_config(&device)
        .expect("no default config");
    let config = cpal::StreamConfig {
        channels: cfg.channels(),
        sample_rate: cfg.sample_rate(),
        buffer_size: cpal::BufferSize::Default,
    };
    eprintln!(
        "probe: device {:?} -> {:?} ch {} rate {}",
        device_name.as_deref().unwrap_or("<default>"),
        config.buffer_size,
        config.channels,
        config.sample_rate
    );
    let count = Arc::new(AtomicU64::new(0));
    let c2 = Arc::clone(&count);
    let stream = device
        .build_output_stream::<f32, _, _>(
            config,
            move |data: &mut [f32], _: &cpal::OutputCallbackInfo| {
                c2.fetch_add(1, Ordering::Relaxed);
                data.fill(0.0);
            },
            |err| eprintln!("probe stream error: {err}"),
            None,
        )
        .expect("build_output_stream failed");
    stream.play().expect("play failed");
    let deadline = Instant::now() + Duration::from_secs_f64(seconds);
    while Instant::now() < deadline {
        std::thread::sleep(Duration::from_millis(100));
    }
    println!(
        "{{\"callbacks\": {}, \"seconds\": {}}}",
        count.load(Ordering::Relaxed),
        seconds
    );
}
