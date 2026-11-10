//! streamify-playback — the producer-side audio data plane (WS-A A3,
//! issue #151).
//!
//! This crate owns everything between "a play intent" and
//! "canonical-geometry PCM committed at the sink write edge", per the
//! A1 RFC (`docs/architecture/engines/13_playback_dataplane.md`):
//!
//! * [`sink`] — the Rust-side mirror of the frozen C++ sink ABI
//!   (`native/include/streamify_audio_sinks.h`): the [`sink::AudioSink`]
//!   trait, the [`sink::SpscAudioRing`] (cacheline-aligned single-producer
//!   / single-consumer ring with the cooperative `RequestFlush`
//!   generation handshake) and the [`sink::SinkStats`] 96-byte-wire
//!   image. This is the WRITE EDGE: zero allocation, zero locks.
//! * [`frame_buf`] — [`frame_buf::FrameBuf`], the pre-allocated,
//!   capacity-preserving staging layers every stage reuses (allocation
//!   happens at session construction, never on the steady-state path).
//! * [`decode`] — the [`decode::TrackDecoder`] trait (RFC §5.3) and the
//!   symphonia 0.6 backend. Codec-agnostic by design: the Opus decision
//!   (no Opus decoder exists in symphonia — see the RFC §5.2 decision
//!   record) stays reversible behind the trait.
//! * [`fetch`] — the [`fetch::RangeFetcher`] abstraction over the
//!   segmented-range source. The production HTTP lane plugs in from
//!   `streamify-contract` (reusing `streamify-resolver`'s downloader
//!   discipline); hermetic tests drive fixture sources with scripted
//!   loss/jitter.
//! * [`producer`] — [`producer::StreamProducer`]: the dedicated producer
//!   thread executing fetch → decode → [`streamify_audio::CanonicalSrc`]
//!   resample (rubato `SincFixedOut<f32>` → 48 kHz interleaved stereo) →
//!   the golden-pinned BS.1770-4 [`streamify_audio::lufs`] chain → sink
//!   write edge. Track skips are observed cooperatively at stage
//!   boundaries via the ring's `RequestFlush`.
//!
//! # RT-safety contract
//!
//! The audio CALLBACK thread never enters this crate. The write edge
//! ([`sink::SpscAudioRing::write_frames`] / [`sink::SpscAudioRing::read_frames_into`]
//! / flush / stats) is lock-free and allocation-free; the fetch and decode
//! stages run on the producer thread and may allocate (network buffers,
//! decoder internals), staged through constructor-preallocated
//! [`frame_buf::FrameBuf`] layers before every ring commit. The crate's
//! dependency list is the compile-time boundary for that promise: no async
//! runtime, no database, no HTTP client — the network edge is a trait.

pub mod consumer_lease;
pub mod decode;
pub mod fetch;
pub mod frame_buf;
pub mod producer;
pub mod sink;

pub use decode::{DecoderGeometry, SymphoniaDecoder, TrackDecoder};
pub use fetch::{LossyFixtureFetcher, MemoryRangeFetcher, RangeFetcher};
pub use frame_buf::FrameBuf;
pub use producer::{
    BufferProgressCore, PlayIntent, ProducerCommand, ProducerConfig, ProducerHandle,
    ProgressSink, StreamProducer, DEFAULT_SEGMENT_BYTES, MAX_SOURCE_BYTES,
};
pub use sink::{
    AudioSink, RingSink, SinkStats, SINK_STATS_WIRE_BYTES, SINK_STATS_WIRE_MAGIC, SpscAudioRing,
};
