//! Canonical 48 kHz producer-side SRC — polyphase sinc resampling
//! (multiplatform Phase 3 / MP-C11, master plan invariant 6).
//!
//! Single-point producer SRC: ALL input audio (44.1 kHz, 88.2 kHz, 96 kHz,
//! 192 kHz — any hardware rate in the supported envelope) is resampled to
//! the canonical internal rate — 48 kHz interleaved `f32` stereo — on the
//! PRODUCER decode thread, BEFORE the samples enter the native `AudioSink`
//! ring. Output SRC to the device rate is strictly delegated to the final
//! hardware HAL callback (Phase 4a/4b adapters); this module never runs on
//! the real-time audio thread and is never in the consumer path.
//!
//! Implementation: `rubato::SincFixedOut<f32>` — windowed-sinc polyphase
//! interpolation with fixed output chunks (1024 frames per pass), cubic
//! sub-sample interpolation, 256-tap sinc, 0.95 relative cutoff and a
//! squared Blackman-Harris window: the audiophile-grade default from the
//! rubato cookbook. The resampler state is owned by ONE producer thread
//! (`&mut self` API — no interior locking by design); the HAL-side
//! consumer pulls from the ring, never from here.
//!
//! Passthrough: 48 kHz input bypasses the resampler entirely (bit-exact
//! copy, zero latency, zero filtering) — the "device-native rate" escape
//! hatch from the master plan, applied at the canonical rate itself.
//!
//! Device rate changes tear down and rebuild the chain (`EngineEvent::
//! AudioDeviceChanged`, master plan invariant 6): construct a fresh
//! [`CanonicalSrc`] for the new input rate; there is no in-place rate
//! migration.

use rubato::{
    Resampler, SincFixedOut, SincInterpolationParameters, SincInterpolationType, WindowFunction,
};

use crate::contract::{AudioErrorKind, EngineError};

/// Canonical internal pipeline rate (Hz): the ring buffer, the DSP chain
/// and the producer contract all operate at 48 kHz f32 (MP-DEC-05).
pub const CANONICAL_RATE_HZ: u32 = 48_000;

/// Supported input-rate envelope (mirrors the native `AudioSink` bounds).
pub const SRC_MIN_INPUT_RATE_HZ: u32 = 8_000;
pub const SRC_MAX_INPUT_RATE_HZ: u32 = 192_000;

/// Output chunk granularity of the polyphase engine (frames per pass).
/// One pass consumes ≈ `chunk_size / ratio` input frames; 1024 keeps the
/// per-pass cost trivial on a decode thread while bounding staging
/// latency to ~21 ms of canonical audio.
const OUT_CHUNK_FRAMES: usize = 1024;

/// Polyphase sinc parameters (rubato cookbook audiophile preset):
/// 256-tap windowed sinc, 0.95 relative cutoff, 256x oversampled
/// interpolation table, cubic sub-sample interpolation, squared
/// Blackman-Harris window (slow rolloff, high stopband attenuation).
const SINC_PARAMS: SincInterpolationParameters = SincInterpolationParameters {
    sinc_len: 256,
    f_cutoff: 0.95,
    oversampling_factor: 256,
    interpolation: SincInterpolationType::Cubic,
    window: WindowFunction::BlackmanHarris2,
};

/// Producer-side resampler to the canonical 48 kHz f32 rate.
///
/// Interleaved-in / interleaved-out (the decode thread's native layout);
/// the per-channel deinterleave and staging buffers are allocated once at
/// construction and reused — `process` never allocates on the steady
/// state path.
///
/// Streaming contract (mirrors `AudioSink::WriteFloat`): `process`
/// consumes whole FRAMES only; a trailing partial frame is retained
/// internally and picked up by the next call. Returns the number of
/// input SAMPLES consumed (frames × channels).
pub struct CanonicalSrc {
    /// `None` in passthrough mode (48 kHz input).
    resampler: Option<SincFixedOut<f32>>,
    input_rate_hz: u32,
    channels: usize,
    /// Interleaved leftover frames awaiting the next polyphase pass
    /// (resample mode) or the partial-frame remainder (passthrough).
    pending: Vec<f32>,
    /// Per-channel staging: input (deinterleaved) and output
    /// (re-interleaved after each pass).
    in_bufs: Vec<Vec<f32>>,
    out_bufs: Vec<Vec<f32>>,
}

impl std::fmt::Debug for CanonicalSrc {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        // Manual impl: the rubato kernel doesn't expose its interior —
        // report the geometry + backlog, which is what diagnostics need.
        f.debug_struct("CanonicalSrc")
            .field("input_rate_hz", &self.input_rate_hz)
            .field("channels", &self.channels)
            .field("passthrough", &self.is_passthrough())
            .field("pending_frames", &self.pending_frames())
            .finish()
    }
}

impl CanonicalSrc {
    /// Build the producer SRC stage for one input stream geometry.
    ///
    /// `input_rate_hz` must be a sane hardware rate
    /// ([`SRC_MIN_INPUT_RATE_HZ`..=SRC_MAX_INPUT_RATE_HZ`]); 48 000
    /// selects lossless passthrough. `channels` is 1 (mono) or 2
    /// (stereo, the canonical pipeline geometry).
    pub fn new(input_rate_hz: u32, channels: usize) -> Result<Self, EngineError> {
        if !(SRC_MIN_INPUT_RATE_HZ..=SRC_MAX_INPUT_RATE_HZ).contains(&input_rate_hz) {
            return Err(EngineError::Audio {
                kind: AudioErrorKind::FormatUnsupported,
                device_id: None,
            });
        }
        if !matches!(channels, 1 | 2) {
            return Err(EngineError::Audio {
                kind: AudioErrorKind::FormatUnsupported,
                device_id: None,
            });
        }
        let resampler = if input_rate_hz == CANONICAL_RATE_HZ {
            None // canonical passthrough: bit-exact, zero latency
        } else {
            let ratio = f64::from(CANONICAL_RATE_HZ) / f64::from(input_rate_hz);
            match SincFixedOut::<f32>::new(
                ratio,
                // The ratio is FIXED at construction (rate changes rebuild
                // the chain), so the relative headroom only bounds the
                // internal table; 8.0 covers the full envelope
                // (48000/8000 = 6.0 up, 48000/192000 = 0.25 down).
                8.0,
                SINC_PARAMS,
                OUT_CHUNK_FRAMES,
                channels,
            ) {
                Ok(r) => Some(r),
                Err(e) => {
                    tracing::error!(error = %e, "rubato SincFixedOut construction failed");
                    return Err(EngineError::Audio {
                        kind: AudioErrorKind::Init,
                        device_id: None,
                    });
                }
            }
        };
        let max_in = match &resampler {
            Some(r) => r.input_frames_max(),
            None => 0,
        };
        Ok(Self {
            resampler,
            input_rate_hz,
            channels,
            pending: Vec::new(),
            in_bufs: vec![vec![0.0; max_in.max(1)]; channels],
            out_bufs: vec![vec![0.0; OUT_CHUNK_FRAMES]; channels],
        })
    }

    /// Input rate this stage was built for (Hz).
    pub fn input_rate_hz(&self) -> u32 {
        self.input_rate_hz
    }

    /// Channel count (1 or 2).
    pub fn channels(&self) -> usize {
        self.channels
    }

    /// True when this stage is the lossless 48 kHz passthrough.
    pub fn is_passthrough(&self) -> bool {
        self.resampler.is_none()
    }

    /// Frames buffered internally, awaiting the next polyphase pass (or
    /// the partial-frame remainder in passthrough). Exposed for producer
    /// backpressure telemetry.
    pub fn pending_frames(&self) -> usize {
        self.pending.len() / self.channels
    }

    /// Feed interleaved input samples; append canonical-rate interleaved
    /// frames to `out`. Returns the number of input SAMPLES consumed
    /// (whole frames only; a trailing partial frame stays buffered).
    ///
    /// Allocation-free on the steady-state path (staging buffers are
    /// constructor-sized); `out` grows by however many canonical chunks
    /// the input unblocked.
    pub fn process(&mut self, input: &[f32], out: &mut Vec<f32>) -> usize {
        let ch = self.channels;
        let whole_in = input.len() - input.len() % ch;
        if whole_in == 0 {
            return 0;
        }
        if self.resampler.is_some() {
            self.pending.extend_from_slice(&input[..whole_in]);
            while self.run_pass(out) {}
        } else {
            // Canonical passthrough: bit-exact copy of the whole frames.
            out.extend_from_slice(&input[..whole_in]);
        }
        whole_in
    }

    /// Run ONE polyphase pass if the pending buffer holds enough frames;
    /// returns false when more input is needed. Re-interleaves the
    /// produced chunk into `out`.
    fn run_pass(&mut self, out: &mut Vec<f32>) -> bool {
        let Some(resampler) = self.resampler.as_mut() else {
            return false; // passthrough has no passes
        };
        let ch = self.channels;
        let needed = resampler.input_frames_next();
        let have = self.pending.len() / ch;
        if have < needed {
            return false;
        }
        // Deinterleave exactly `needed` frames into the staging channels.
        for (c, buf) in self.in_bufs.iter_mut().enumerate() {
            buf.clear();
            buf.extend(self.pending[c..needed * ch].iter().step_by(ch));
        }
        let (used, produced) =
            match resampler.process_into_buffer(&self.in_bufs, &mut self.out_bufs, None) {
                Ok(counts) => counts,
                Err(e) => {
                    // Buffer sizing is a construction invariant; a fault here
                    // is an internal bug, not a device condition — surface it
                    // and stop the pass loop.
                    tracing::error!(error = %e, "rubato process fault in canonical SRC");
                    return false;
                }
            };
        debug_assert_eq!(used, needed);
        let _ = used;
        // Re-interleave the produced chunk.
        for f in 0..produced {
            for buf in &self.out_bufs {
                out.push(buf[f]);
            }
        }
        self.pending.drain(..needed * ch);
        true
    }

    /// End-of-stream flush: in resample mode, zero-pads the pending tail
    /// and runs the final polyphase pass(es) so the last real samples
    /// cross the interpolation kernel (the emitted tail is followed by
    /// sinc-decayed zeros — consumers gate track switches on their own
    /// flush, not on this silence). In passthrough mode this is a no-op.
    ///
    /// After `flush`, the stage is reusable for the next track of the
    /// same geometry only after [`Self::reset`]; producers normally
    /// construct a fresh stage per stream.
    pub fn flush(&mut self, out: &mut Vec<f32>) {
        if self.resampler.is_none() {
            return; // passthrough keeps no DSP state
        }
        let ch = self.channels;
        while !self.pending.is_empty() {
            // Pad the tail up to the next pass size, then run passes
            // until the pending buffer is drained. The final pass emits
            // the last real samples followed by kernel-decayed zeros.
            let needed = self
                .resampler
                .as_ref()
                .expect("resample mode")
                .input_frames_next();
            let have = self.pending.len() / ch;
            if have < needed {
                self.pending.resize(needed * ch, 0.0);
            }
            if !self.run_pass(out) {
                break; // faulted pass is logged; stop rather than spin
            }
        }
        // Drop any sub-frame remainder.
        self.pending.clear();
    }

    /// Reset the interpolation state and drop buffered frames (track
    /// switch / seek): the next `process` starts from a pristine kernel.
    pub fn reset(&mut self) {
        if let Some(resampler) = self.resampler.as_mut() {
            resampler.reset();
        }
        self.pending.clear();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sine(frames: usize, channels: usize, rate: u32, freq: f32) -> Vec<f32> {
        let mut out = Vec::with_capacity(frames * channels);
        for f in 0..frames {
            let t = f as f32 / rate as f32;
            let s = (std::f32::consts::TAU * freq * t).sin();
            for _ in 0..channels {
                out.push(s);
            }
        }
        out
    }

    fn rms(x: &[f32]) -> f32 {
        if x.is_empty() {
            return 0.0;
        }
        (x.iter().map(|s| f64::from(*s) * f64::from(*s)).sum::<f64>() / x.len() as f64).sqrt()
            as f32
    }

    #[test]
    fn constructor_validates_geometry() {
        for bad_rate in [0u32, 7_999, 192_001] {
            let err = CanonicalSrc::new(bad_rate, 2).expect_err("bad rate rejected");
            assert!(matches!(
                err,
                EngineError::Audio {
                    kind: AudioErrorKind::FormatUnsupported,
                    ..
                }
            ));
        }
        for bad_ch in [0usize, 3, 9] {
            assert!(CanonicalSrc::new(44_100, bad_ch).is_err());
        }
        // The envelope edges are inclusive; 48k selects passthrough.
        assert!(CanonicalSrc::new(8_000, 1).is_ok());
        assert!(CanonicalSrc::new(192_000, 2).is_ok());
        assert!(CanonicalSrc::new(48_000, 2).unwrap().is_passthrough());
        assert!(!CanonicalSrc::new(44_100, 2).unwrap().is_passthrough());
    }

    #[test]
    fn passthrough_is_bit_exact_and_frame_aligned() {
        let mut src = CanonicalSrc::new(48_000, 2).unwrap();
        let input = sine(5_000, 2, 48_000, 440.0);
        let mut out = Vec::new();
        let consumed = src.process(&input, &mut out);
        assert_eq!(consumed, input.len());
        assert_eq!(out, input, "48k passthrough must be bit-exact");

        // A trailing partial frame is retained, then picked up.
        let mut out2 = Vec::new();
        let consumed2 = src.process(&input[..input.len() - 1], &mut out2);
        assert_eq!(consumed2, input.len() - 2, "whole frames only");
        assert_eq!(out2.len(), input.len() - 2);
        assert_eq!(
            src.pending_frames(),
            0,
            "passthrough retains only sub-frame tails"
        );
        let _ = consumed2;
        // Flush is a no-op in passthrough mode.
        let mut flushed = Vec::new();
        src.flush(&mut flushed);
        assert!(flushed.is_empty());
    }

    #[test]
    fn upsample_44k1_to_48k_preserves_signal_and_length() {
        let mut src = CanonicalSrc::new(44_100, 2).unwrap();
        let input = sine(44_100, 2, 44_100, 997.0); // 1 second, 997 Hz probe tone
        let mut out = Vec::new();
        src.process(&input, &mut out);
        src.flush(&mut out);

        let out_frames = out.len() / 2;
        // 1 s of input → 1 s of canonical audio, within one chunk of
        // scheduling slack.
        assert!(
            (out_frames as i64 - 48_000).abs() <= OUT_CHUNK_FRAMES as i64,
            "output length {out_frames} must be ~48000 frames"
        );
        assert!(out.iter().all(|s| s.is_finite()), "no NaN/inf in output");

        // Steady-state fidelity: RMS over the second half (past the
        // startup transient) within 1 dB of the input RMS.
        let tail = &out[out.len() / 2..];
        let in_rms = rms(&input);
        let out_rms = rms(tail);
        assert!(
            (out_rms / in_rms) > 0.89 && (out_rms / in_rms) < 1.12,
            "997 Hz RMS must survive resampling: in {in_rms:.4} out {out_rms:.4}"
        );
    }

    #[test]
    fn downsample_96k_to_48k_stops_aliasing() {
        // 23.7 kHz at 96 kHz input sits past the sinc's transition band
        // (f_cutoff 0.95 => 22.8 kHz corner) and below the 48 kHz
        // canonical Nyquist: it must be strongly attenuated (>= ~20 dB)
        // on the way down — the anti-alias contract of the polyphase
        // stage. (23.0 kHz measures only ~-9 dB: still inside the
        // transition band, which is correct filter behavior, not a
        // defect.)
        let mut src = CanonicalSrc::new(96_000, 2).unwrap();
        let input = sine(96_000, 2, 96_000, 23_700.0);
        let mut out = Vec::new();
        src.process(&input, &mut out);
        src.flush(&mut out);

        let tail = &out[out.len() / 2..]; // past the startup transient
        let in_rms = rms(&input);
        let out_rms = rms(tail);
        assert!(
            out_rms < in_rms * 0.1,
            "23.7 kHz stopband tone must attenuate >= 20 dB: in {in_rms:.4} out {out_rms:.4}"
        );
    }

    #[test]
    fn ratio_math_88k2_and_192k() {
        for (rate, seconds) in [(88_200u32, 2usize), (192_000, 1)] {
            let mut src = CanonicalSrc::new(rate, 2).unwrap();
            let frames = rate as usize * seconds;
            let input = sine(frames, 2, rate, 440.0);
            let mut out = Vec::new();
            src.process(&input, &mut out);
            src.flush(&mut out);
            let expect = CANONICAL_RATE_HZ as usize * seconds;
            let got = out.len() / 2;
            assert!(
                (got as i64 - expect as i64).abs() <= OUT_CHUNK_FRAMES as i64,
                "{rate} Hz x {seconds}s -> {got} frames, expected ~{expect}"
            );
        }
    }

    #[test]
    fn streaming_chunks_converge_to_batch_output() {
        // Chunked streaming (decoder-sized blocks, like the producer
        // decode thread delivers) must produce the same output as one
        // big batch: the pending buffer is the only state.
        let input = sine(44_100, 2, 44_100, 440.0);

        let mut batch_src = CanonicalSrc::new(44_100, 2).unwrap();
        let mut batch_out = Vec::new();
        batch_src.process(&input, &mut batch_out);
        batch_src.flush(&mut batch_out);

        let mut stream_src = CanonicalSrc::new(44_100, 2).unwrap();
        let mut stream_out = Vec::new();
        // 735-frame blocks = 5 blocks per output chunk-ish: deliberately
        // unaligned with the polyphase pass boundaries.
        let block = 735 * 2;
        let mut off = 0;
        while off < input.len() {
            let end = (off + block).min(input.len());
            stream_src.process(&input[off..end], &mut stream_out);
            off = end;
        }
        stream_src.flush(&mut stream_out);

        assert_eq!(
            batch_out, stream_out,
            "streaming must be state-identical to batch"
        );
    }

    #[test]
    fn reset_clears_interpolation_state() {
        let mut src = CanonicalSrc::new(44_100, 2).unwrap();
        let input = sine(4_410, 2, 44_100, 440.0);
        let mut out = Vec::new();
        src.process(&input, &mut out);
        assert!(src.pending_frames() > 0, "mid-stream state is pending");
        src.reset();
        assert_eq!(src.pending_frames(), 0);
        // After reset the stage behaves like a fresh one for a new track.
        let mut fresh = CanonicalSrc::new(44_100, 2).unwrap();
        let input2 = sine(4_410, 2, 44_100, 880.0);
        let mut out_reset = Vec::new();
        let mut out_fresh = Vec::new();
        src.process(&input2, &mut out_reset);
        src.flush(&mut out_reset);
        fresh.process(&input2, &mut out_fresh);
        fresh.flush(&mut out_fresh);
        assert_eq!(out_reset, out_fresh, "reset stage == fresh stage");
    }

    #[test]
    fn mono_stage_is_supported() {
        let mut src = CanonicalSrc::new(44_100, 1).unwrap();
        let input = sine(44_100, 1, 44_100, 440.0);
        let mut out = Vec::new();
        src.process(&input, &mut out);
        src.flush(&mut out);
        assert!((out.len() as i64 - 48_000).abs() <= OUT_CHUNK_FRAMES as i64);
    }
}
