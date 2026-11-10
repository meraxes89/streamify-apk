//! The decode stage (RFC §5.2/§5.3): the codec-agnostic [`TrackDecoder`]
//! trait and its symphonia 0.6 backend.
//!
//! # The dependency decision (audit record)
//!
//! symphonia ships **no Opus decoder in any released version** (0.5.x and
//! 0.6.x carry `ogg` as a container only; upstream issue
//! pdeljanov/Symphonia#8). The RFC §5.2 fallback therefore applies: the
//! trait is the boundary, the backend ships AAC (+ PCM/WAV/OGG/ISO-MP4
//! demuxing) today — the resolver's documented format ordering already
//! prioritizes m4a (AAC) over webm/opus rows — and Opus remains a
//! reversible decision (pure-Rust options do not exist yet; a libopus
//! binding would violate the pure-Rust profile).
//!
//! # The incremental-feed protocol
//!
//! symphonia is a pull parser over a `MediaSourceStream` (mss). Feeding
//! it byte ranges incrementally means its reads hit the fed-data
//! frontier mid-packet; a naive retry would resume at a misaligned
//! position and corrupt every later packet. The backend therefore runs
//! a checkpoint protocol built on facts verified against the symphonia
//! 0.6 sources:
//!
//! 1. The RIFF/ADTS/OGG demuxers derive each packet from
//!    `reader.pos()` statelessly (`riff::common::next_packet`), so a
//!    packet boundary lost to a frontier hit is recoverable by
//!    repositioning the reader.
//! 2. `MediaSourceStream::read_buf` converts a dry source into
//!    `UnexpectedEof`, so a frontier hit surfaces as
//!    `Error::IoError(UnexpectedEof)` — never a silent short packet.
//! 3. `MediaSourceStream::seek` resets the internal ring, and
//!    `FormatReader::into_inner` hands the mss back, so the reader can
//!    be repositioned by the session that owns it.
//!
//! On a frontier hit the session rewinds: `into_inner` → seek to 0 →
//! re-probe (headers re-parsed from the accumulated buffer) →
//! `FormatReader::seek` to the timestamp of the frames already emitted
//! (packet-boundary aligned by the demuxers' own `get_actual_ts`
//! discipline). The decode loop then continues exactly where it left
//! off once more input arrives. `chunked_feed_matches_whole_file_decode`
//! in `tests/test_decode.rs` pins the protocol against both fixtures.

use std::io::{Read, Seek, SeekFrom};
use std::sync::{Arc, Mutex};

use symphonia::core::audio::{Audio, GenericAudioBufferRef};
use symphonia::core::codecs::audio::{AudioDecoder, AudioDecoderOptions};
use symphonia::core::codecs::CodecParameters;
use symphonia::core::errors::Error as SymphoniaError;
use symphonia::core::formats::probe::Hint;
use symphonia::core::formats::{FormatOptions, FormatReader, SeekMode, SeekTo};
use symphonia::core::io::{MediaSource, MediaSourceStream, MediaSourceStreamOptions};
use symphonia::core::meta::MetadataOptions;
use symphonia::core::units::Timestamp;

use streamify_core::{AudioErrorKind, EngineError};

use crate::frame_buf::FrameBuf;

/// Discovered geometry of the decode session.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct DecoderGeometry {
    /// Input sample rate in Hz (the resample stage canonicalizes).
    pub sample_rate_hz: u32,
    /// Input channel count (1 or 2 — the canonical pipeline geometry).
    pub channels: usize,
}

/// One decode session (RFC §5.3). Implemented by the symphonia backend
/// and by test doubles; the §5.2 dependency decision stays reversible
/// behind this boundary.
///
/// The decoder consumes the compressed source INCREMENTALLY: ranges
/// arrive via [`TrackDecoder::push_range`] and whole interleaved input-
/// geometry `f32` frames are appended to `out`. The producer owns the
/// decoder exclusively (single decode thread by construction).
pub trait TrackDecoder: Send {
    /// Feed one byte range of the source; append every frame that
    /// completes under the bytes accumulated so far. A frontier hit
    /// (more input needed mid-packet) is NOT an error: the session
    /// checkpoints and returns `Ok`.
    fn push_range(&mut self, bytes: &[u8], out: &mut FrameBuf) -> Result<(), EngineError>;
    /// Signal source exhaustion: pump the remaining packets; afterwards
    /// [`TrackDecoder::is_eos`] is `true`.
    fn finish(&mut self, out: &mut FrameBuf) -> Result<(), EngineError>;
    /// True once the container-declared end or the finished source tail
    /// has been fully decoded.
    fn is_eos(&self) -> bool;
    /// Discovered geometry; `None` until the container probe succeeds
    /// (the first ranges may carry only part of the header).
    fn geometry(&self) -> Option<DecoderGeometry>;
}

/// The typed error for an unusable container/codec (RFC §5.2 attack-
/// surface note: untrusted container parsing fails closed).
fn unsupported(why: &str) -> EngineError {
    EngineError::Audio {
        kind: AudioErrorKind::FormatUnsupported,
        device_id: Some(why.to_string()),
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// The shared accumulated source
// ─────────────────────────────────────────────────────────────────────────────

/// Shared state between the feeding side (the producer thread appending
/// ranges) and the symphonia reader (the same thread, behind the mss).
/// The `Mutex` is an ownership necessity of `Box<dyn MediaSource>`, not
/// a lock on any hot path: decode and feed never run concurrently.
#[derive(Debug, Default)]
struct FeedState {
    data: Vec<u8>,
    pos: usize,
    eos: bool,
    /// Set when the reader hit the frontier (needs more input). Cleared
    /// by the session before every pump.
    frontier_hit: bool,
}

impl FeedState {
    fn hit_frontier(&mut self) -> std::io::Error {
        self.frontier_hit = true;
        std::io::Error::new(
            std::io::ErrorKind::WouldBlock,
            "streamify: source frontier reached mid-packet",
        )
    }
}

/// The `MediaSource` view over [`FeedState`]. Reads at the frontier
/// return the WouldBlock frontier error (not a clean EOF) so the pump
/// can distinguish "need more input" from the finished source; reads
/// past a finished source return `Ok(0)` (a true EOF).
#[derive(Clone)]
struct FeedReader {
    state: Arc<Mutex<FeedState>>,
}

impl Read for FeedReader {
    fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
        let mut state = self
            .state
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        let available = state.data.len().saturating_sub(state.pos);
        if available == 0 {
            return if state.eos {
                Ok(0)
            } else {
                Err(state.hit_frontier())
            };
        }
        let take = available.min(buf.len());
        buf[..take].copy_from_slice(&state.data[state.pos..state.pos + take]);
        state.pos += take;
        Ok(take)
    }
}

impl Seek for FeedReader {
    fn seek(&mut self, pos: SeekFrom) -> std::io::Result<u64> {
        let mut state = self
            .state
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        let base = state.pos as i64;
        let target = match pos {
            SeekFrom::Start(offset) => offset as i64,
            SeekFrom::Current(delta) => base + delta,
            SeekFrom::End(delta) => state.data.len() as i64 + delta,
        };
        if target < 0 || target > state.data.len() as i64 {
            return Err(std::io::Error::new(
                std::io::ErrorKind::InvalidInput,
                "streamify: seek outside the accumulated source",
            ));
        }
        state.pos = target as usize;
        Ok(target as u64)
    }
}

impl MediaSource for FeedReader {
    fn is_seekable(&self) -> bool {
        true
    }

    fn byte_len(&self) -> Option<u64> {
        let state = self
            .state
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        Some(state.data.len() as u64)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// The symphonia backend
// ─────────────────────────────────────────────────────────────────────────────

/// The symphonia-backed decode session.
///
/// Construct per track (or per skip — the producer drops and rebuilds
/// sessions at stage boundaries). All state lives on the producer
/// thread; the `Arc<Mutex<FeedState>>` exists only so the boxed
/// `MediaSource` can share the accumulated buffer with the session.
pub struct SymphoniaDecoder {
    feed: Arc<Mutex<FeedState>>,
    format: Option<Box<dyn FormatReader>>,
    decoder: Option<Box<dyn AudioDecoder>>,
    track_id: u32,
    geometry: Option<DecoderGeometry>,
    /// Frames emitted so far — the rewind checkpoint.
    frames_emitted: u64,
    /// Scratch staging for one packet's interleaved conversion (reused;
    /// grows once to the largest packet).
    scratch: Vec<f32>,
    eos: bool,
}

impl std::fmt::Debug for SymphoniaDecoder {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        // Manual impl: the format reader and decoder are trait objects
        // without Debug — report what diagnostics need.
        f.debug_struct("SymphoniaDecoder")
            .field("geometry", &self.geometry)
            .field("frames_emitted", &self.frames_emitted)
            .field("eos", &self.eos)
            .finish_non_exhaustive()
    }
}

impl SymphoniaDecoder {
    /// Start a session over an empty source (ranges arrive via
    /// `push_range`).
    pub fn new() -> Result<Self, EngineError> {
        Ok(Self {
            feed: Arc::new(Mutex::new(FeedState::default())),
            format: None,
            decoder: None,
            track_id: 0,
            geometry: None,
            frames_emitted: 0,
            scratch: Vec::new(),
            eos: false,
        })
    }

    /// True when the accumulated source is complete and drained.
    fn source_finished(&self) -> bool {
        self.feed
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .eos
    }

    /// (Re-)probe the accumulated buffer. Returns Ok(false) when there
    /// is not enough header data yet; a malformed container fails as a
    /// typed error. On success, wires the format reader, the decoder
    /// and the discovered geometry.
    fn try_probe(&mut self) -> Result<bool, EngineError> {
        if self.format.is_some() {
            return Ok(true);
        }
        // Every attempt starts from byte 0: a failed probe may have
        // consumed part of the accumulated buffer, and the RIFF/OGG
        // parsers expect the container head at the stream position.
        {
            let mut feed = self
                .feed
                .lock()
                .unwrap_or_else(|poisoned| poisoned.into_inner());
            feed.pos = 0;
            feed.frontier_hit = false;
        }
        let reader = FeedReader {
            state: self.feed.clone(),
        };
        let mss = MediaSourceStream::new(Box::new(reader), MediaSourceStreamOptions::default());
        let probed = symphonia::default::get_probe().probe(
            &Hint::default(),
            mss,
            FormatOptions::default(),
            MetadataOptions::default(),
        );
        match probed {
            Ok(format) => {
                let track = format
                    .tracks()
                    .iter()
                    .find(|t| t.codec_params.as_ref().is_some_and(CodecParameters::is_audio))
                    .ok_or_else(|| unsupported("no audio track in container"))?;
                self.track_id = track.id;
                let audio_params = match track.codec_params.as_ref() {
                    Some(CodecParameters::Audio(params)) => params,
                    _ => return Err(unsupported("selected track is not audio")),
                };
                let sample_rate = audio_params.sample_rate.ok_or_else(|| {
                    unsupported("container declares no sample rate")
                })?;
                let channels = audio_params
                    .channels
                    .as_ref()
                    .map(|c| c.count())
                    .ok_or_else(|| unsupported("container declares no channels"))?;
                if !(8_000..=192_000).contains(&sample_rate) || !matches!(channels, 1 | 2) {
                    return Err(unsupported(
                        "container geometry outside the canonical pipeline envelope",
                    ));
                }
                let decoder = symphonia::default::get_codecs()
                    .make_audio_decoder(audio_params, &AudioDecoderOptions::default())
                    .map_err(|e| unsupported(&format!("codec unavailable: {e}")))?;
                self.decoder = Some(decoder);
                self.geometry = Some(DecoderGeometry {
                    sample_rate_hz: sample_rate,
                    channels,
                });
                self.format = Some(format);
                Ok(true)
            }
            Err(SymphoniaError::IoError(ref e))
                if e.kind() == std::io::ErrorKind::UnexpectedEof
                    || e.kind() == std::io::ErrorKind::WouldBlock =>
            {
                // Not enough header bytes yet.
                Ok(false)
            }
            Err(e) => Err(unsupported(&format!("container probe failed: {e}"))),
        }
    }

    /// The frontier rewind (see the module docs): recover the mss,
    /// rewind to 0, re-probe, and seek the fresh reader to the frames
    /// already emitted. Safe to call with `frames_emitted == 0`.
    fn rewind_to_checkpoint(&mut self) -> Result<(), EngineError> {
        let Some(format) = self.format.take() else {
            return Ok(());
        };
        let mut mss = format.into_inner();
        mss.seek(SeekFrom::Start(0))
            .map_err(|e| unsupported(&format!("rewind failed: {e}")))?;
        // The probe reads from position 0 of the accumulated buffer.
        {
            let mut feed = self
                .feed
                .lock()
                .unwrap_or_else(|poisoned| poisoned.into_inner());
            feed.pos = 0;
            feed.frontier_hit = false;
        }
        self.try_probe()?;
        // Jump the fresh reader to the checkpoint (packet-boundary
        // aligned by the demuxer's own alignment discipline).
        if self.frames_emitted > 0 {
            let Some(format) = self.format.as_mut() else {
                return Ok(());
            };
            let seek = format.seek(
                SeekMode::Accurate,
                SeekTo::Timestamp {
                    ts: Timestamp::new(self.frames_emitted as i64),
                    track_id: self.track_id,
                },
            );
            if let Err(e) = seek {
                return Err(unsupported(&format!("checkpoint seek failed: {e}")));
            }
        }
        Ok(())
    }

    /// Decode one packet into `out`; returns the frames decoded.
    fn decode_packet(
        &mut self,
        packet: &symphonia::core::packet::Packet,
        out: &mut FrameBuf,
    ) -> Result<u64, EngineError> {
        // Field-level split borrows: the decoder (borrowed mutably for
        // the decode call) and the scratch staging are disjoint fields.
        let Self {
            decoder, scratch, ..
        } = self;
        let Some(decoder) = decoder.as_mut() else {
            return Err(unsupported("decoder not initialized"));
        };
        let decoded = match decoder.decode(packet) {
            Ok(decoded) => decoded,
            Err(SymphoniaError::DecodeError(_)) => {
                // A corrupt packet is skipped (the symphonia
                // recommendation): one lost packet, decoding continues.
                tracing::warn!("corrupt packet skipped");
                return Ok(0);
            }
            Err(SymphoniaError::ResetRequired) => {
                // Rare: the decoder wants re-creation. Re-probe wires a
                // fresh decoder; the current packet is re-fed by the
                // caller's next pump after the rewind.
                return Err(EngineError::Audio {
                    kind: AudioErrorKind::Init,
                    device_id: Some("decoder reset required mid-stream".to_string()),
                });
            }
            Err(e) => return Err(unsupported(&format!("decode failed: {e}"))),
        };
        convert_to_interleaved_f32(&decoded, scratch, out)
    }

    /// Pump: decode every packet the accumulated source completes.
    fn pump(&mut self, out: &mut FrameBuf) -> Result<(), EngineError> {
        if !self.try_probe()? {
            return Ok(()); // header still incomplete
        }
        {
            let mut feed = self
                .feed
                .lock()
                .unwrap_or_else(|poisoned| poisoned.into_inner());
            feed.frontier_hit = false;
        }
        loop {
            let Some(format) = self.format.as_mut() else {
                return Ok(());
            };
            match format.next_packet() {
                Ok(Some(packet)) => {
                    let frames = self.decode_packet(&packet, out)?;
                    self.frames_emitted += frames;
                }
                Ok(None) => {
                    // Container-declared end: cleanly finished.
                    self.eos = true;
                    return Ok(());
                }
                Err(SymphoniaError::IoError(ref e))
                    if e.kind() == std::io::ErrorKind::UnexpectedEof
                        || e.kind() == std::io::ErrorKind::WouldBlock =>
                {
                    let finished = self.source_finished();
                    let frontier = {
                        let feed = self
                            .feed
                            .lock()
                            .unwrap_or_else(|poisoned| poisoned.into_inner());
                        feed.frontier_hit
                    };
                    if finished && !frontier {
                        // The finished source's true tail is decoded.
                        self.eos = true;
                        return Ok(());
                    }
                    // Frontier hit mid-packet: checkpoint and wait for
                    // more input.
                    self.rewind_to_checkpoint()?;
                    return Ok(());
                }
                Err(SymphoniaError::ResetRequired) => {
                    self.rewind_to_checkpoint()?;
                    return Ok(());
                }
                Err(e) => return Err(unsupported(&format!("demux failed: {e}"))),
            }
        }
    }
}

/// Convert one decoded buffer to interleaved `f32` appended to `out`,
/// through the reused scratch layer. Returns frames.
fn convert_to_interleaved_f32(
    decoded: &GenericAudioBufferRef<'_>,
    scratch: &mut Vec<f32>,
    out: &mut FrameBuf,
) -> Result<u64, EngineError> {
    scratch.clear();
    match decoded {
        GenericAudioBufferRef::U8(b) => b.copy_to_vec_interleaved(scratch),
        GenericAudioBufferRef::U16(b) => b.copy_to_vec_interleaved(scratch),
        GenericAudioBufferRef::U24(b) => b.copy_to_vec_interleaved(scratch),
        GenericAudioBufferRef::U32(b) => b.copy_to_vec_interleaved(scratch),
        GenericAudioBufferRef::S8(b) => b.copy_to_vec_interleaved(scratch),
        GenericAudioBufferRef::S16(b) => b.copy_to_vec_interleaved(scratch),
        GenericAudioBufferRef::S24(b) => b.copy_to_vec_interleaved(scratch),
        GenericAudioBufferRef::S32(b) => b.copy_to_vec_interleaved(scratch),
        GenericAudioBufferRef::F32(b) => b.copy_to_vec_interleaved(scratch),
        GenericAudioBufferRef::F64(b) => b.copy_to_vec_interleaved(scratch),
    }
    let frames = (scratch.len() / decoded.spec().channels().count()) as u64;
    out.push_interleaved(scratch)?;
    Ok(frames)
}

impl TrackDecoder for SymphoniaDecoder {
    fn push_range(&mut self, bytes: &[u8], out: &mut FrameBuf) -> Result<(), EngineError> {
        if self.eos {
            return Ok(());
        }
        {
            let mut feed = self
                .feed
                .lock()
                .unwrap_or_else(|poisoned| poisoned.into_inner());
            feed.data.extend_from_slice(bytes);
        }
        self.pump(out)
    }

    fn finish(&mut self, out: &mut FrameBuf) -> Result<(), EngineError> {
        {
            let mut feed = self
                .feed
                .lock()
                .unwrap_or_else(|poisoned| poisoned.into_inner());
            feed.eos = true;
        }
        self.pump(out)
    }

    fn is_eos(&self) -> bool {
        self.eos
    }

    fn geometry(&self) -> Option<DecoderGeometry> {
        self.geometry
    }
}
