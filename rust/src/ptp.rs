use std::sync::atomic::{AtomicI64, Ordering};
use std::time::{SystemTime, UNIX_EPOCH};

#[repr(C, packed)]
#[derive(Debug, Clone, Copy)]
pub struct PtpPacket {
    pub sequence_id: u32,
    pub t0_origin_send: u64,    // Client departure
    pub t1_host_receive: u64,   // Host arrival
    pub t2_host_transmit: u64,  // Host departure
    pub t3_client_receive: u64, // Client arrival
}

pub struct PtpEngine;

impl PtpEngine {
    /// Computes hardware network delay (delta) and clock offset (theta) in microseconds
    pub fn calculate_offset_and_delay(packet: &PtpPacket) -> (i64, i64) {
        let t0 = packet.t0_origin_send as i64;
        let t1 = packet.t1_host_receive as i64;
        let t2 = packet.t2_host_transmit as i64;
        let t3 = packet.t3_client_receive as i64;

        let theta_offset_us = ((t1 - t0) + (t2 - t3)) / 2;
        let delta_delay_us = ((t3 - t0) - (t2 - t1)) / 2;

        (theta_offset_us, delta_delay_us)
    }

    pub fn get_system_micros() -> u64 {
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.as_micros() as u64)
            .unwrap_or(0)
    }
}

pub struct PtpFilter {
    clock_offset_nanos: AtomicI64,
    rtt_nanos: AtomicI64,
    initialized: bool,
    ema_offset: f64,
    ema_rtt: f64,
    alpha: f64,
}

impl PtpFilter {
    pub fn new(alpha: f64) -> Self {
        Self {
            clock_offset_nanos: AtomicI64::new(0),
            rtt_nanos: AtomicI64::new(0),
            initialized: false,
            ema_offset: 0.0,
            ema_rtt: 0.0,
            alpha: alpha.clamp(0.01, 1.0),
        }
    }

    /// Processes IEEE 1588 four timestamps (t0, t1, t2, t3) in nanoseconds.
    /// Offset = ((t1 - t0) + (t2 - t3)) / 2
    /// Delay (RTT) = (t3 - t0) - (t2 - t1)
    pub fn process_timestamps(&mut self, t0: i64, t1: i64, t2: i64, t3: i64) -> i64 {
        let raw_offset = ((t1 - t0) + (t2 - t3)) as f64 / 2.0;
        let raw_rtt = ((t3 - t0) - (t2 - t1)).max(0) as f64;

        if !self.initialized {
            self.ema_offset = raw_offset;
            self.ema_rtt = raw_rtt;
            self.initialized = true;
        } else {
            // Adaptive Kalman-like EMA filtering
            self.ema_offset = self.alpha * raw_offset + (1.0 - self.alpha) * self.ema_offset;
            self.ema_rtt = self.alpha * raw_rtt + (1.0 - self.alpha) * self.ema_rtt;
        }

        let final_offset = self.ema_offset as i64;
        let final_rtt = self.ema_rtt as i64;

        self.clock_offset_nanos
            .store(final_offset, Ordering::Release);
        self.rtt_nanos.store(final_rtt, Ordering::Release);

        final_offset
    }

    pub fn get_clock_offset_nanos(&self) -> i64 {
        self.clock_offset_nanos.load(Ordering::Acquire)
    }

    pub fn get_rtt_nanos(&self) -> i64 {
        self.rtt_nanos.load(Ordering::Acquire)
    }

    pub fn reset(&mut self) {
        self.clock_offset_nanos.store(0, Ordering::Release);
        self.rtt_nanos.store(0, Ordering::Release);
        self.initialized = false;
        self.ema_offset = 0.0;
        self.ema_rtt = 0.0;
    }
}
