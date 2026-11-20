//! The Opus decode stage (Sovereign HAL Phase 1 / D3).
//!
//! Closes the AAC-only gap (RFC §5.2's documented reversible decision):
//! YouTube Music's PRIMARY audio lane is Opus (`itag 251`, 48 kHz
//! stereo ~130 kbps VBR); AAC-LC (`itag 140`, 44.1 kHz ~128 kbps)
//! rides second. This module implements the [`TrackDecoder`] trait for
//! both Opus paths the app ingests:
//!
//! 1. **Standard Ogg-Opus** — `OpusHead` (19-byte RFC 7845 ID header)
//!    + `OpusTags` + Ogg pages of Opus packets (the `.opus` file shape
//!    and the `webm`-ripped Ogg remux).
//! 2. **The server's raw framing** (#225 EBML extractor) — the raw
//!    19-byte `OpusHead` (from the WebM `CodecPrivate`) followed by
//!    LENGTH-PREFIXED raw Opus packets (the room broadcaster's 16-byte
//!    header + UInt16BE length-table batches, stripped of transport).
//!
//! # Decoder tiers (both exercised in CI)
//!
//! * **Tier::Primary** — the `opus` crate (libopus built statically via
//!   `cc`, BSD-3-Clause; MIT OR Apache-2.0 binding). NOT `audiopus`
//!   (abandoned upstream).
//! * **Tier::Fallback** — `symphonia-opus` 0.1.3 (pure Rust,
//!   `#![forbid(unsafe_code)]`, zero per-packet heap allocation,
//!   120/120 IETF Opus conformance vectors).
//!
//! Both tiers decode through the SAME packet-framing front end below,
//! so the framing logic is tier-independent (CI decodes every fixture
//! through BOTH tiers and asserts sample-level agreement within the
//! codec's inter-tier tolerance).
//!
//! # Output geometry
//!
//! Opus ALWAYS decodes at 48 kHz (the codec's mandated rate) —
//! `itag 251` therefore feeds the canonical 48 kHz pipeline through
//! `CanonicalSrc`'s lossless passthrough (rubato never runs); 44.1 kHz
//! AAC-LC (`itag 140`) is the only lane that resamples.

use symphonia::core::audio::Audio;
use streamify_core::{AudioErrorKind, EngineError};

use crate::decode::{DecoderGeometry, SymphoniaDecoder};
use crate::frame_buf::FrameBuf;
use crate::TrackDecoder;

/// Which decode tier to instantiate.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum OpusTier {
    /// libopus via the `opus` crate (the production path).
    #[default]
    Primary,
    /// symphonia-opus (the pure-Rust fallback, CI-exercised).
    Fallback,
}

/// The minimum OpusHead length (RFC 7845 §5.1: magic 8 + version 1 +
/// channels 1 + pre-skip 2 + rate 4 + gain 2 + mapping 1 = 19).
pub const OPUS_HEAD_MIN_LEN: usize = 19;

/// The OpusHead magic ("OpusHead").
const OPUS_HEAD_MAGIC: &[u8; 8] = b"OpusHead";

/// Parse the 19-byte RFC 7845 OpusHead. Returns (channels, pre_skip,
/// input_rate_declared). The DECLARED rate is informational — Opus
/// output is ALWAYS 48 kHz (RFC 6716 §2); the decoder ignores the
/// field's advice per the Ogg Opus spec.
pub fn parse_opus_head(head: &[u8]) -> Result<(usize, u16, u32), EngineError> {
    if head.len() < OPUS_HEAD_MIN_LEN {
        return Err(EngineError::Audio {
            kind: AudioErrorKind::FormatUnsupported,
            device_id: Some("OpusHead shorter than 19 bytes".to_string()),
        });
    }
    if &head[0..8] != OPUS_HEAD_MAGIC {
        return Err(EngineError::Audio {
            kind: AudioErrorKind::FormatUnsupported,
            device_id: Some("OpusHead magic mismatch".to_string()),
        });
    }
    if head[8] != 1 {
        return Err(EngineError::Audio {
            kind: AudioErrorKind::FormatUnsupported,
            device_id: Some(format!("OpusHead version {} (only 1 supported)", head[8])),
        });
    }
    let channels = head[9] as usize;
    if !(1..=2).contains(&channels) {
        // RFC 7845 allows up to 255 with channel mapping family ≥1; our
        // canonical pipeline is mono/stereo and YouTube Music is stereo.
        return Err(EngineError::Audio {
            kind: AudioErrorKind::FormatUnsupported,
            device_id: Some(format!("OpusHead channels {channels} outside 1..=2")),
        });
    }
    let pre_skip = u16::from_le_bytes([head[10], head[11]]);
    let input_rate = u32::from_le_bytes([head[12], head[13], head[14], head[15]]);
    Ok((channels, pre_skip, input_rate))
}

/// The raw-Opus framing detector: true when the byte stream begins
/// with a bare OpusHead (the server's #225 extractor framing) rather
/// than an Ogg page header (`OggS`).
pub fn is_raw_opus_head_framing(bytes: &[u8]) -> bool {
    bytes.len() >= OPUS_HEAD_MIN_LEN && &bytes[0..8] == OPUS_HEAD_MAGIC
}

/// The frame-length prefix width of the server's raw framing:
/// UInt16BE per packet (broadcaster.js `lenTable`).
pub const RAW_FRAME_PREFIX: usize = 2;

// ─────────────────────────────────────────────────────────────────────────────
// Tier::Primary — libopus via the `opus` crate
// ─────────────────────────────────────────────────────────────────────────────

/// The packet-backend for the raw framing scanner: which engine
/// decodes one framed Opus packet.
enum PacketBackend {
    /// libopus via the `opus` crate (Tier::Primary).
    LibOpus(opus::Decoder),
    /// symphonia-opus (Tier::Fallback), instantiated through its
    /// registry and driven packet-by-packet via the AudioDecoder trait.
    SymphoniaOpus(Box<dyn symphonia::core::codecs::audio::AudioDecoder>),
}

impl PacketBackend {
    /// Decode one packet into interleaved f32 (the staging buffer),
    /// returning the FRAME count.
    fn decode(&mut self, packet: &[u8], pcm: &mut [f32], channels: usize) -> Result<usize, String> {
        match self {
            PacketBackend::LibOpus(dec) => {
                // opus 0.4: decode_float(&[u8], &mut [f32], fec) — the
                // f32 output the canonical pipeline speaks natively.
                let frames = dec
                    .decode_float(packet, pcm, false)
                    .map_err(|e| format!("libopus decode failed: {e}"))?;
                Ok(frames)
            }
            PacketBackend::SymphoniaOpus(dec) => {
                // The public packet path: core::packet::Packet
                // (formats re-imports it privately — the round-3 E0603).
                // decode() takes the owned packet.
                let pkt = symphonia::core::packet::Packet::new(
                    0,
                    symphonia::core::units::Timestamp::from(0u32),
                    symphonia::core::units::Duration::from(960u64),
                    packet.to_vec(),
                );
                let decoded = dec
                    .decode(&pkt)
                    .map_err(|e| format!("symphonia-opus decode failed: {e}"))?;
                // The canonical interleaved conversion (decode.rs's own
                // pattern): copy_to_vec_interleaved handles every buffer
                // flavor; F32 is the Opus decoder's native output.
                let mut scratch: Vec<f32> = Vec::new();
                let frames = match &decoded {
                    symphonia::core::audio::GenericAudioBufferRef::F32(b) => {
                        b.copy_to_vec_interleaved(&mut scratch);
                        b.frames()
                    }
                    symphonia::core::audio::GenericAudioBufferRef::S16(b) => {
                        b.copy_to_vec_interleaved(&mut scratch);
                        b.frames()
                    }
                    _ => return Err("unexpected symphonia-opus buffer flavor".into()),
                };
                let n = scratch.len();
                if n > pcm.len() {
                    return Err("pcm staging too small".into());
                }
                pcm[..n].copy_from_slice(&scratch);
                let _ = channels;
                Ok(frames)
            }
        }
    }
}

/// The incremental raw-Opus framing scanner + decoder (tier-dispatched).
///
/// Scans LENGTH-PREFIXED raw Opus packets (the server's UInt16BE
/// framing) and decodes each through the chosen backend at 48 kHz into
/// interleaved f32.
pub struct LibOpusDecoder {
    backend: PacketBackend,
    channels: usize,
    pre_skip: usize,
    skipped: usize,
    /// Byte-level scan position for the next packet's length prefix.
    scan: usize,
    /// Partial length-prefix bytes accumulated.
    prefix_buf: [u8; RAW_FRAME_PREFIX],
    prefix_have: usize,
    /// The current packet under accumulation.
    packet: Vec<u8>,
    packet_need: usize,
    have_packet_len: bool,
    /// The decode staging buffer (reused; packet-time allocation only
    /// on first packet — never per sample).
    pcm: Vec<f32>,
}

impl LibOpusDecoder {
    /// Build the scanner + the tier's backend from the parsed OpusHead
    /// fields.
    pub fn new(tier: OpusTier, channels: usize, pre_skip: usize) -> Result<Self, EngineError> {
        let mode = match channels {
            1 => opus::Channels::Mono,
            2 => opus::Channels::Stereo,
            _ => {
                return Err(EngineError::Audio {
                    kind: AudioErrorKind::FormatUnsupported,
                    device_id: Some(format!("Opus channels {channels} outside 1..=2")),
                })
            }
        };
        let backend = match (tier, channels) {
            (OpusTier::Primary, _) => PacketBackend::LibOpus(
                // opus 0.4: Decoder::new(sample_rate: u32, channels) —
                // Opus ALWAYS decodes at 48 kHz (RFC 6716 §2).
                opus::Decoder::new(48_000, mode).map_err(|e| EngineError::Audio {
                    kind: AudioErrorKind::Init,
                    device_id: Some(format!("libopus init failed: {e}")),
                })?,
            ),
            (OpusTier::Fallback, _) => {
                // symphonia-opus is registry-mediated (RegisterableAudio
                // Decoder::try_registry_new): build the OpusHead extra
                // data, register at Tier::Fallback, instantiate through
                // the registry — exactly the documented integration.
                let mut head = Vec::with_capacity(OPUS_HEAD_MIN_LEN);
                head.extend_from_slice(b"OpusHead");
                head.push(1u8); // version
                head.push(channels as u8);
                head.extend_from_slice(&(pre_skip as u16).to_le_bytes());
                head.extend_from_slice(&48_000u32.to_le_bytes()); // declared rate (informational)
                head.extend_from_slice(&0i16.to_le_bytes()); // gain
                head.push(0); // mapping family 0 (mono/stereo)
                let mut params = symphonia::core::codecs::audio::AudioCodecParameters::new();
                params
                    .for_codec(symphonia::core::codecs::audio::well_known::CODEC_ID_OPUS)
                    .with_extra_data(head.into_boxed_slice());
                let mut registry =
                    symphonia::core::codecs::registry::CodecRegistry::new();
                registry.register_audio_decoder_at_tier::<symphonia_opus::OpusDecoder>(
                    symphonia::core::common::Tier::Fallback,
                );
                let dec = registry
                    .make_audio_decoder(&params, &Default::default())
                    .map_err(|e| EngineError::Audio {
                        kind: AudioErrorKind::Init,
                        device_id: Some(format!("symphonia-opus init failed: {e}")),
                    })?;
                PacketBackend::SymphoniaOpus(dec)
            }
        };
        Ok(Self {
            backend,
            channels,
            pre_skip,
            skipped: 0,
            scan: 0,
            prefix_buf: [0; RAW_FRAME_PREFIX],
            prefix_have: 0,
            packet: Vec::with_capacity(1280),
            packet_need: 0,
            have_packet_len: false,
            pcm: vec![0.0f32; 5760 * 2], // max Opus packet: 120 ms @ 48 kHz stereo
        })
    }

    /// Decode one framed packet into `out` with RFC 7845 §4.2 pre-skip
    /// trimming (the encoder's lookahead samples drop).
    fn decode_packet(&mut self, packet: &[u8], out: &mut FrameBuf) -> Result<usize, EngineError> {
        // Split borrows: the backend decodes into `pcm` first, then the
        // staging slice hands off to `out` (no simultaneous field borrows).
        let channels = self.channels;
        let decoded = {
            let pcm = &mut self.pcm;
            self.backend.decode(packet, pcm, channels)
        }
        .map_err(|why| EngineError::Audio {
            kind: AudioErrorKind::FormatUnsupported,
            device_id: Some(why),
        })?;
        let mut samples = decoded * channels;
        let start = if self.skipped < self.pre_skip {
            let skip = (self.pre_skip - self.skipped).min(samples);
            self.skipped += skip;
            skip
        } else {
            0
        };
        samples -= start;
        out.push_interleaved(&self.pcm[start..start + samples])?;
        Ok(samples / channels.max(1))
    }

    /// Feed raw framed bytes incrementally (the server framing: UInt16BE
    /// length + packet, repeated). Accumulates and decodes every
    /// complete packet; partial tails wait for the next range.
    pub fn feed_raw_framed(&mut self, bytes: &[u8], out: &mut FrameBuf) -> Result<(), EngineError> {
        self.scan = 0;
        while self.scan < bytes.len() {
            if !self.have_packet_len {
                // accumulate prefix bytes
                while self.prefix_have < RAW_FRAME_PREFIX && self.scan < bytes.len() {
                    self.prefix_buf[self.prefix_have] = bytes[self.scan];
                    self.prefix_have += 1;
                    self.scan += 1;
                }
                if self.prefix_have == RAW_FRAME_PREFIX {
                    self.packet_need = u16::from_be_bytes([self.prefix_buf[0], self.prefix_buf[1]])
                        as usize;
                    self.prefix_have = 0;
                    self.have_packet_len = true;
                    self.packet.clear();
                    if self.packet_need == 0 {
                        // zero-length packet: a decode-with-null signals
                        // PLC/DTTO in some framings — treat as an empty
                        // packet event and move on (defensive; the
                        // server never emits one).
                        self.have_packet_len = false;
                    }
                } else {
                    break; // need more prefix bytes
                }
            } else {
                // accumulate packet bytes
                let want = self.packet_need - self.packet.len();
                let avail = bytes.len() - self.scan;
                let take = want.min(avail);
                self.packet.extend_from_slice(&bytes[self.scan..self.scan + take]);
                self.scan += take;
                if self.packet.len() == self.packet_need {
                    // Take the completed packet OUT (avoids the split
                    // borrow: decode_packet takes &mut self).
                    let pkt = std::mem::take(&mut self.packet);
                    self.decode_packet(&pkt, out)?;
                    self.have_packet_len = false;
                } else {
                    break; // need more packet bytes
                }
            }
        }
        Ok(())
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// The unified Opus TrackDecoder (both framings, both tiers)
// ─────────────────────────────────────────────────────────────────────────────

/// The Opus backend implementing [`TrackDecoder`].
///
/// Framing auto-detect on the first bytes: `OggS` magic → the Ogg
/// demuxer path (symphonia `ogg` + `symphonia-opus`); bare `OpusHead` →
/// the raw length-prefixed path (libopus direct; symphonia-opus fed
/// packet-by-packet for the Fallback tier).
pub struct OpusTrackDecoder {
    tier: OpusTier,
    /// The Ogg path's session (OggS framing).
    ogg_session: Option<crate::decode::SymphoniaDecoder>,
    /// The raw path's libopus decoder (OpusHead framing).
    raw: Option<LibOpusDecoder>,
    /// Byte stream mode once detected.
    framing: Option<Framing>,
    /// Accumulated bytes for the raw path.
    pending: Vec<u8>,
    /// True once the raw OpusHead has been consumed (19 bytes).
    head_consumed: usize,
    /// Detected geometry (48 kHz, 1–2 ch).
    geometry: Option<DecoderGeometry>,
    eos: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Framing {
    Ogg,
    RawHead,
}

impl OpusTrackDecoder {
    /// Build the decoder for a tier (Primary = libopus raw path +
    /// symphonia Ogg path; Fallback = symphonia-opus both ways).
    pub fn new(tier: OpusTier) -> Self {
        Self {
            tier,
            ogg_session: None,
            raw: None,
            framing: None,
            pending: Vec::new(),
            head_consumed: 0,
            geometry: None,
            eos: false,
        }
    }
}

impl TrackDecoder for OpusTrackDecoder {
    fn push_range(&mut self, bytes: &[u8], out: &mut FrameBuf) -> Result<(), EngineError> {
        // Framing detection on the very first bytes.
        if self.framing.is_none() {
            if bytes.len() >= 4 && &bytes[0..4] == b"OggS" {
                self.framing = Some(Framing::Ogg);
                // The Ogg demuxer rides symphonia; the Opus CODEC rides
                // symphonia-opus at Tier::Fallback in BOTH tiers (the
                // Ogg framing has no libopus involvement — libopus has
                // no Ogg demuxer; tier divergence is exercised on the
                // RAW framing, pinned by CI on shared fixtures).
                self.ogg_session = Some(crate::decode::SymphoniaDecoder::with_opus_fallback()?);
            } else if is_raw_opus_head_framing(bytes) {
                self.framing = Some(Framing::RawHead);
            } else {
                return Err(EngineError::Audio {
                    kind: AudioErrorKind::FormatUnsupported,
                    device_id: Some("not Opus (neither OggS nor OpusHead framing)".to_string()),
                });
            }
        }
        match self.framing.unwrap() {
            Framing::Ogg => {
                // The Ogg path always rides symphonia (ogg demuxer +
                // symphonia-opus as the registered Opus CODEC — the
                // Fallback tier's pure-Rust path; the Primary tier on
                // Ogg ALSO uses it because libopus has no Ogg demuxer —
                // tier divergence is exercised on the RAW framing both
                // ways and pinned by CI on shared fixtures).
                let session = self.ogg_session.as_mut().unwrap();
                session.push_range(bytes, out)?;
                if self.geometry.is_none() {
                    if let Some(g) = session.geometry() {
                        self.geometry = Some(g);
                    }
                }
                Ok(())
            }
            Framing::RawHead => {
                // Consume the OpusHead from the pending stream first.
                self.pending.extend_from_slice(bytes);
                if self.head_consumed < OPUS_HEAD_MIN_LEN {
                    let have = self.pending.len();
                    if have < OPUS_HEAD_MIN_LEN {
                        return Ok(()); // wait for the full head
                    }
                    let (channels, pre_skip, _declared) =
                        parse_opus_head(&self.pending[..OPUS_HEAD_MIN_LEN])?;
                    self.geometry = Some(DecoderGeometry {
                        sample_rate_hz: 48_000, // Opus ALWAYS outputs 48 kHz
                        channels,
                    });
                    self.raw = Some(LibOpusDecoder::new(self.tier, channels, pre_skip as usize)?);
                    let rest = self.pending.split_off(OPUS_HEAD_MIN_LEN);
                    self.pending = rest;
                    self.head_consumed = OPUS_HEAD_MIN_LEN;
                }
                let raw = self.raw.as_mut().expect("raw decoder built after head");
                // Feed the remaining length-prefixed packets.
                let pending = std::mem::take(&mut self.pending);
                raw.feed_raw_framed(&pending, out)?;
                Ok(())
            }
        }
    }

    fn finish(&mut self, out: &mut FrameBuf) -> Result<(), EngineError> {
        match self.framing {
            Some(Framing::Ogg) => {
                let session = self.ogg_session.as_mut().expect("ogg session");
                session.finish(out)?;
                self.eos = session.is_eos();
            }
            Some(Framing::RawHead) => {
                // Flush any complete pending packet; a partial tail is
                // dropped (the server's batches are packet-aligned).
                if let Some(raw) = self.raw.as_mut() {
                    let pending = std::mem::take(&mut self.pending);
                    raw.feed_raw_framed(&pending, out)?;
                }
                self.eos = true;
            }
            None => {
                self.eos = true; // empty source: nothing to decode
            }
        }
        Ok(())
    }

    fn is_eos(&self) -> bool {
        self.eos
    }

    fn geometry(&self) -> Option<DecoderGeometry> {
        self.geometry
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn opus_head_parses_rfc7845_shape() {
        // Stereo, pre-skip 312, declared 48000 (the itag 251 shape).
        let mut head = [0u8; 19];
        head[0..8].copy_from_slice(b"OpusHead");
        head[8] = 1;
        head[9] = 2;
        head[10..12].copy_from_slice(&312u16.to_le_bytes());
        head[12..16].copy_from_slice(&48000u32.to_le_bytes());
        let (ch, skip, rate) = parse_opus_head(&head).unwrap();
        assert_eq!((ch, skip, rate), (2, 312, 48000));
    }

    #[test]
    fn opus_head_rejects_bad_magic_and_version() {
        let mut head = [0u8; 19];
        head[0..8].copy_from_slice(b"OpusHead");
        head[8] = 2; // unsupported version
        assert!(parse_opus_head(&head).is_err());
        head[8] = 1;
        head[0..8].copy_from_slice(b"OpusTail!");
        assert!(parse_opus_head(&head).is_err());
    }

    #[test]
    fn framing_detector_discriminates() {
        let ogg = b"OggS\x00\x02rest-of-page";
        assert!(!is_raw_opus_head_framing(ogg));
        let raw = [b"OpusHead" as &[u8], &[1u8, 2, 0, 1, 64, 0, 0, 0, 0, 0, 0]].concat();
        assert!(is_raw_opus_head_framing(&raw));
    }
}
