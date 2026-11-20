//! The pre-allocated, capacity-preserving staging buffer (RFC §5.3).
//!
//! Every producer stage (decode → resample → loudness → ring commit)
//! reads and writes [`FrameBuf`] layers that were sized once at session
//! construction. [`FrameBuf::clear`] drops the logical length but keeps
//! the allocation — the steady-state path never allocates, which the
//! ring suite proves with a counting allocator.

use streamify_core::{AudioErrorKind, EngineError};

/// One pre-allocated staging layer of interleaved `f32` samples.
///
/// A `FrameBuf` is ALWAYS interleaved and always frames-of-`channels`
/// aligned: [`FrameBuf::push_interleaved`] takes whole frames only and a
/// trailing partial frame is rejected (the decode stage emits whole
/// frames by construction; a partial frame crossing a staging boundary
/// would be a producer bug, not a network condition).
#[derive(Debug)]
pub struct FrameBuf {
    samples: Vec<f32>,
    channels: usize,
}

impl FrameBuf {
    /// Allocate a layer holding `frames` frames of `channels` samples
    /// each. `channels` must be 1 or 2 (the canonical pipeline geometry,
    /// mirroring [`streamify_audio::CanonicalSrc`]).
    pub fn with_capacity(frames: usize, channels: usize) -> Result<Self, EngineError> {
        if !matches!(channels, 1 | 2) {
            return Err(EngineError::Audio {
                kind: AudioErrorKind::FormatUnsupported,
                device_id: None,
            });
        }
        Ok(Self {
            samples: Vec::with_capacity(frames * channels),
            channels,
        })
    }

    /// Logical length 0, allocation kept (the zero-alloc staging
    /// discipline: clear + refill, never reallocate).
    pub fn clear(&mut self) {
        self.samples.clear();
    }

    /// Append whole interleaved frames. Returns the number of SAMPLES
    /// appended; a trailing partial frame is rejected as an audio
    /// format error (producer-side invariant violation).
    pub fn push_interleaved(&mut self, samples: &[f32]) -> Result<usize, EngineError> {
        if !samples.len().is_multiple_of(self.channels) {
            return Err(EngineError::Audio {
                kind: AudioErrorKind::FormatUnsupported,
                device_id: None,
            });
        }
        self.samples.extend_from_slice(samples);
        Ok(samples.len())
    }

    /// The interleaved samples staged so far.
    pub fn as_slice(&self) -> &[f32] {
        &self.samples
    }

    /// Mutable access (the loudness stage normalizes in place).
    pub fn as_mut_slice(&mut self) -> &mut [f32] {
        &mut self.samples
    }

    /// Number of staged samples (interleaved).
    pub fn len_samples(&self) -> usize {
        self.samples.len()
    }

    /// Number of staged FRAMES.
    pub fn len_frames(&self) -> usize {
        self.samples.len() / self.channels
    }

    /// True when no frames are staged.
    pub fn is_empty(&self) -> bool {
        self.samples.is_empty()
    }

    /// Channel geometry of this layer.
    pub fn channels(&self) -> usize {
        self.channels
    }

    /// Total staging capacity in samples (the allocation made at
    /// construction; diagnostics for sizing audits).
    pub fn capacity_samples(&self) -> usize {
        self.samples.capacity()
    }

    /// Remaining capacity in frames before the next push reallocates
    /// (diagnostics: a producer that regularly exhausts this sized its
    /// staging wrong).
    pub fn spare_frames(&self) -> usize {
        self.samples.capacity() / self.channels - self.len_frames()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn clear_keeps_capacity_while_dropping_length() {
        let mut buf = FrameBuf::with_capacity(128, 2).unwrap();
        buf.push_interleaved(&[0.5; 256]).unwrap();
        assert_eq!(buf.len_frames(), 128);
        assert!(buf.capacity_samples() >= 256);
        let cap_before = buf.capacity_samples();
        buf.clear();
        assert!(buf.is_empty());
        assert_eq!(buf.capacity_samples(), cap_before);
        // Refill after clear does not grow the allocation.
        buf.push_interleaved(&[0.25; 256]).unwrap();
        assert_eq!(buf.capacity_samples(), cap_before);
        assert_eq!(buf.len_samples(), 256);
    }

    #[test]
    fn partial_frames_are_rejected() {
        let mut buf = FrameBuf::with_capacity(16, 2).unwrap();
        assert!(buf.push_interleaved(&[1.0]).is_err());
        assert!(buf.push_interleaved(&[1.0, 2.0, 3.0]).is_err());
        assert!(buf.is_empty());
        assert_eq!(buf.push_interleaved(&[1.0, 2.0]).unwrap(), 2);
        assert_eq!(buf.channels(), 2);
        assert_eq!(buf.spare_frames(), 15);
        assert!(FrameBuf::with_capacity(16, 3).is_err());
    }
}
