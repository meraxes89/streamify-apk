//! tick_matrix.rs — Lossless sequence matrix + gap-repair engine (Phase 1.2).
//!
//! Host ticks carry a monotonically increasing `seq_id`. Guests feed every
//! received tick into the matrix; when a sequence jump is detected, the
//! missing ticks are SYNTHESIZED by linear interpolation of position over the
//! observed inter-tick interval, so the PLL loop never sees a discontinuity it
//! cannot distinguish from a stall.
//!
//! Wire frame layout (documented contract; transport is JSON so this struct is
//! the canonical in-memory/native representation):
//!
//! ```text
//! magic  u32   0x4A414D54 ("JAMT")
//! seq    u32   monotonic sequence id (wraps at u32::MAX, handled below)
//! pos    i64   host position in ms (synced-clock domain)
//! state  u8    0 = PLAYING, 1 = PAUSED, 2 = LOADING
//! policy u8    0 = HOST_ONLY, 1 = EVERYONE
//! rsv    [u8;6] alignment padding → 24 bytes total, repr(C, packed)
//! ```

use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Mutex;

pub const TICK_MAGIC: u32 = 0x4A41_4D54;

pub const STATE_PLAYING: u8 = 0;
pub const STATE_PAUSED: u8 = 1;
pub const STATE_LOADING: u8 = 2;

pub const POLICY_HOST_ONLY: u8 = 0;
pub const POLICY_EVERYONE: u8 = 1;

/// Ring capacity: >10 minutes of steady-state 1s ticks or ~30s of 50ms
/// fast-ticks. Power of two for cheap masking.
const RING_SLOTS: usize = 2048;
const RING_MASK: usize = RING_SLOTS - 1;

#[derive(Debug, Clone, Copy, Default)]
#[repr(C, packed)]
pub struct JamTick {
    pub magic: u32,
    pub seq: u32,
    pub pos_ms: i64,
    pub host_mono_ms: i64,
    pub state: u8,
    pub policy: u8,
    pub reserved: [u8; 6],
}

impl JamTick {
    pub fn new(seq: u32, pos_ms: i64, host_mono_ms: i64, state: u8, policy: u8) -> Self {
        JamTick {
            magic: TICK_MAGIC,
            seq,
            pos_ms,
            host_mono_ms,
            state,
            policy,
            reserved: [0u8; 6],
        }
    }

    #[inline]
    pub fn is_valid(&self) -> bool {
        self.magic == TICK_MAGIC
    }

    /// Pack into one i64 for cheap FFI return: high 32 bits = seq, low 31 bits
    /// = position ms clamped to [0, 2^31) (~24.8 days — far beyond any track).
    #[inline]
    pub fn pack(&self) -> i64 {
        let pos = self.pos_ms.clamp(0, (1i64 << 31) - 1);
        ((self.seq as i64) << 32) | pos
    }

    #[inline]
    pub fn unpack_seq(packed: i64) -> u32 {
        (packed >> 32) as u32
    }

    #[inline]
    pub fn unpack_pos(packed: i64) -> i64 {
        packed & 0x7FFF_FFFF
    }
}

struct MatrixState {
    ring: Vec<JamTick>,
    head: usize, // next write slot
    last_seq: Option<u32>,
    /// Observed steady-state inter-tick interval, ms (EMA).
    tick_interval_ms: f64,
}

impl MatrixState {
    fn new() -> Self {
        Self {
            ring: vec![JamTick::default(); RING_SLOTS],
            head: 0,
            last_seq: None,
            tick_interval_ms: 1_000.0,
        }
    }
}

static MATRIX: Mutex<Option<MatrixState>> = Mutex::new(None);
/// Total synthesized (gap-filled) ticks since reset — diagnostics.
static SYNTH_COUNT: AtomicU64 = AtomicU64::new(0);
/// Largest contiguous gap seen since reset — diagnostics.
static MAX_GAP_SEEN: AtomicU64 = AtomicU64::new(0);

pub struct TickMatrix;

impl TickMatrix {
    pub fn reset() {
        if let Ok(mut guard) = MATRIX.lock() {
            *guard = Some(MatrixState::new());
        }
        SYNTH_COUNT.store(0, Ordering::Release);
        MAX_GAP_SEEN.store(0, Ordering::Release);
    }

    /// Ingest one received tick. Returns the number of entries appended into
    /// `out_packed` (synthesized gap-fills FIRST in ascending order, then the
    /// real tick itself). Each entry is a JamTick::pack() value carrying
    /// seq+pos; synthesized entries inherit the new tick's state/policy.
    ///
    /// Sequence wrap handling: a fresh seq that is LOWER than last by more
    /// than half the u32 space is treated as a wrap (accepted without
    /// synthesis); anything else regressive is dropped as a duplicate/replay.
    pub fn ingest(
        seq: u32,
        pos_ms: i64,
        host_mono_ms: i64,
        state: u8,
        _policy: u8,
        out_packed: &mut [i64],
    ) -> usize {
        if out_packed.is_empty() {
            return 0;
        }
        let Ok(mut guard) = MATRIX.lock() else {
            return 0;
        };
        let st = guard.get_or_insert_with(MatrixState::new);

        let mut written = 0usize;
        match st.last_seq {
            None => {
                // First observation after (re)start: adopt, no synthesis.
                st.push(JamTick::new(
                    seq,
                    pos_ms,
                    host_mono_ms,
                    state,
                    POLICY_EVERYONE,
                ));
                out_packed[0] = st.ring[(st.head + RING_SLOTS - 1) & RING_MASK].pack();
                written = 1;
            }
            Some(last) => {
                let forward = seq.wrapping_sub(last);
                if forward == 0 || forward > 0x8000_0000 {
                    // Duplicate or replayed packet: ignore entirely.
                    return 0;
                }

                let gap = if seq < last && forward <= 0x8000_0000 {
                    0u32 // wrap boundary
                } else {
                    forward - 1
                };

                if gap > 0 {
                    MAX_GAP_SEEN.fetch_max(gap as u64, Ordering::AcqRel);
                    // Synthesize missing ticks via linear interpolation between
                    // the previous real tick and this one.
                    if let Some(prev) =
                        st.ring[(st.head + RING_SLOTS - 1) & RING_MASK].non_default_if(last)
                    {
                        let span_ticks = (gap + 1) as f64;
                        let dpos = (pos_ms - prev.pos_ms) as f64 / span_ticks;
                        let dmono = (host_mono_ms - prev.host_mono_ms) as f64 / span_ticks;
                        for k in 1..=gap {
                            if written >= out_packed.len() {
                                break;
                            }
                            let s = prev.seq.wrapping_add(k);
                            let t = JamTick::new(
                                s,
                                prev.pos_ms + (dpos * k as f64).round() as i64,
                                prev.host_mono_ms + (dmono * k as f64).round() as i64,
                                state,
                                POLICY_EVERYONE,
                            );
                            st.push(t);
                            out_packed[written] = t.pack();
                            written += 1;
                        }
                        SYNTH_COUNT.fetch_add(gap as u64, Ordering::AcqRel);
                    }
                }

                // Interval EMA (only meaningful on consecutive ticks).
                if gap == 0 {
                    if let Some(prev) =
                        st.ring[(st.head + RING_SLOTS - 1) & RING_MASK].non_default_if(last)
                    {
                        let dt = (host_mono_ms - prev.host_mono_ms).max(0) as f64;
                        if dt > 5.0 && dt < 10_000.0 {
                            st.tick_interval_ms = if st.tick_interval_ms == 1_000.0 && st.is_fresh()
                            {
                                dt
                            } else {
                                st.tick_interval_ms * 0.8 + dt * 0.2
                            };
                        }
                    }
                }

                st.push(JamTick::new(
                    seq,
                    pos_ms,
                    host_mono_ms,
                    state,
                    POLICY_EVERYONE,
                ));
                if written < out_packed.len() {
                    out_packed[written] =
                        JamTick::new(seq, pos_ms, host_mono_ms, state, POLICY_EVERYONE).pack();
                    written += 1;
                }
            }
        }
        written
    }

    /// Adaptive host-side tick interval per Phase 1.2:
    ///   steady state ............ 1000 ms
    ///   convergence burst ....... 250 ms for 2s after any regime change
    ///   final 15s of a track ..... 50 ms (flawless transition alignment)
    pub fn host_interval_ms(pos_ms: i64, duration_ms: i64, ms_since_regime_change: i64) -> i64 {
        if duration_ms > 0 && pos_ms >= 0 && duration_ms - pos_ms <= 15_000 {
            return 50;
        }
        if ms_since_regime_change < 2_000 {
            return 250;
        }
        1_000
    }

    /// Diagnostics snapshot: [synthesized_total, max_gap_seen, ema_interval_ms]
    pub fn diagnostics() -> [i64; 3] {
        let interval = MATRIX
            .lock()
            .ok()
            .and_then(|g| g.as_ref().map(|s| s.tick_interval_ms as i64))
            .unwrap_or(-1);
        [
            SYNTH_COUNT.load(Ordering::Acquire) as i64,
            MAX_GAP_SEEN.load(Ordering::Acquire) as i64,
            interval,
        ]
    }
}

impl MatrixState {
    #[inline]
    fn push(&mut self, tick: JamTick) {
        self.ring[self.head] = tick;
        self.head = (self.head + 1) & RING_MASK;
        self.last_seq = Some(tick.seq);
    }

    #[inline]
    fn is_fresh(&self) -> bool {
        self.head == 0
    }
}

impl JamTick {
    /// The default-initialized slot zero is indistinguishable from seq=0 data;
    /// callers only read the PREVIOUS slot when last_seq was already Some, and
    /// the very first push after reset lands at index 0 — so a default slot is
    /// never read as data. This helper exists purely to document intent.
    #[inline]
    fn non_default_if(&self, expected_seq: u32) -> Option<JamTick> {
        if self.magic == TICK_MAGIC && self.seq == expected_seq {
            Some(*self)
        } else {
            None
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    // All tests share the process-global MATRIX singleton, so they MUST run
    // inside one #[test] to stay deterministic under cargo's parallel runner.
    #[test]
    fn matrix_full_lifecycle() {
        // ── consecutive ticks: no synthesis ──
        TickMatrix::reset();
        let mut out = [0i64; 16];
        assert_eq!(
            TickMatrix::ingest(1, 1000, 5000, STATE_PLAYING, 0, &mut out),
            1
        );
        // Each ingest call writes from index 0.
        assert_eq!(
            TickMatrix::ingest(2, 2000, 6000, STATE_PLAYING, 0, &mut out),
            1
        );
        assert_eq!(JamTick::unpack_seq(out[0]), 2);
        assert_eq!(JamTick::unpack_pos(out[0]), 2000);
        let diag = TickMatrix::diagnostics();
        assert_eq!(diag[0], 0, "no synthesis on consecutive ticks");

        // ── gap interpolation, in order, then the real tick ──
        TickMatrix::reset();
        let mut out = [0i64; 16];
        TickMatrix::ingest(100, 10_000, 100_000, STATE_PLAYING, 0, &mut out);
        let n = TickMatrix::ingest(103, 13_000, 103_000, STATE_PLAYING, 0, &mut out);
        assert_eq!(n, 3);
        assert_eq!(JamTick::unpack_seq(out[0]), 101);
        assert_eq!(JamTick::unpack_pos(out[0]), 11_000);
        assert_eq!(JamTick::unpack_seq(out[1]), 102);
        assert_eq!(JamTick::unpack_pos(out[1]), 12_000);
        assert_eq!(JamTick::unpack_seq(out[2]), 103);
        assert_eq!(TickMatrix::diagnostics()[1], 2); // max gap seen

        // ── duplicates & replays dropped ──
        TickMatrix::reset();
        let mut out = [0i64; 16];
        TickMatrix::ingest(10, 1_000, 1_000, STATE_PLAYING, 0, &mut out);
        assert_eq!(
            TickMatrix::ingest(10, 1_000, 1_000, STATE_PLAYING, 0, &mut out),
            0
        );
        assert_eq!(
            TickMatrix::ingest(9, 900, 900, STATE_PLAYING, 0, &mut out),
            0
        );

        // ── seq wrap accepted without synthesis ──
        TickMatrix::reset();
        let mut out = [0i64; 16];
        TickMatrix::ingest(u32::MAX - 1, 5_000, 5_000, STATE_PLAYING, 0, &mut out);
        let n = TickMatrix::ingest(1, 6_000, 6_000, STATE_PLAYING, 0, &mut out);
        assert_eq!(n, 1);
        assert_eq!(JamTick::unpack_seq(out[0]), 1);

        // ── pack round-trip ──
        let t = JamTick::new(u32::MAX - 1, 123_456, 999_999, STATE_PAUSED, 1);
        let p = t.pack();
        assert_eq!(JamTick::unpack_seq(p), u32::MAX - 1);
        assert_eq!(JamTick::unpack_pos(p), 123_456);

        // ── adaptive intervals match blueprint ──
        assert_eq!(TickMatrix::host_interval_ms(60_000, 200_000, 10_000), 1_000);
        assert_eq!(TickMatrix::host_interval_ms(60_000, 200_000, 100), 250);
        assert_eq!(TickMatrix::host_interval_ms(190_000, 200_000, 10_000), 50);

        // ── PHASE 4: takeover reset clears the sequence stream ──
        // Old host dies at seq 5000; without reset, a new host's seq=1 is
        // misread as a 4-billion wrap and every tick is dropped.
        TickMatrix::reset();
        let mut out = [0i64; 16];
        let mut last = 0u32;
        for i in 1..=5000u32 {
            TickMatrix::ingest(
                i,
                i as i64 * 100,
                i as i64 * 1000,
                STATE_PLAYING,
                0,
                &mut out,
            );
            last = i;
        }
        assert_eq!(last, 5000);
        TickMatrix::ingest(6000, 6_000_000, 6_000_000, STATE_PLAYING, 0, &mut out);
        // Without reset, seq=1 would be dropped as replay:
        assert_eq!(
            TickMatrix::ingest(1, 1000, 1_000, STATE_PLAYING, 0, &mut out),
            0
        );

        TickMatrix::reset();
        assert_eq!(
            TickMatrix::ingest(1, 1000, 1_000, STATE_PLAYING, 0, &mut out),
            1,
            "fresh seq=1 must be accepted after takeover reset"
        );
        assert_eq!(JamTick::unpack_pos(out[0]), 1000);
        assert_eq!(
            TickMatrix::ingest(2, 2000, 2_000, STATE_PLAYING, 0, &mut out),
            1,
            "subsequent ticks continue normally"
        );
    }
}
