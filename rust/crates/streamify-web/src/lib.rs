//! streamify-web — the wasm32 browser producer surface (Sovereign HAL
//! Phase 2 / Step 3).
//!
//! Runs inside the DEDICATED WEB WORKER (the main thread never sees the
//! producer; only the output bracket lives there — AudioWorkletNode or
//! the AudioBufferSourceNode scheduler). The surface is deliberately
//! tiny:
//!
//!   * `decode_opus(bytes) -> Vec<f32>` — the real codec path (the
//!     symphonia-opus tier; libopus cannot cross-build for wasm32 —
//!     see the playback crate's target gates). Raw #225 framing AND
//!     Ogg-Opus both accepted, exactly like the native players.
//!   * `decode_aac(bytes) -> Vec<f32>` — the symphonia AAC path
//!     (itag 140's container rides `isomp4`).
//!   * the output transport (the SAB SPSC ring / the pooled
//!     MessagePort chunks) stays in JS by design: SharedArrayBuffer
//!     views cannot be shared into wasm32 linear memory, and keeping
//!     the RT pull path wasm-free holds the hard-realtime discipline
//!     (see the ring note at the bottom of this file).
//!
//! The output bracket itself (AudioWorklet vs AudioBufferSourceNode +
//! pooled MessagePort transfers, the getOutputTimestamp clock,
//! navigator.mediaSession) is browser JS/Kotlin: it lives in the
//! harness and the wasmJsMain lane, calling INTO this surface for the
//! data plane.

#![cfg(target_arch = "wasm32")]

use wasm_bindgen::prelude::*;

use streamify_playback::{
    FrameBuf, OpusTier, OpusTrackDecoder, SpscAudioRing, SymphoniaDecoder, TrackDecoder,
};

/// Decode a raw-#225-framed or Ogg-Opus fixture to interleaved f32 PCM
/// (the real codec path; the Worker feeds this to the SAB ring).
#[wasm_bindgen]
pub fn decode_opus(bytes: &[u8]) -> Vec<f32> {
    let mut out = match FrameBuf::with_capacity(65_536, 2) {
        Ok(o) => o,
        Err(_) => return Vec::new(),
    };
    // wasm32 has no libopus tier: OpusTier::Primary resolves to the
    // symphonia-opus backend inside the playback crate (the target
    // gate); the tier argument keeps the native surface identical.
    let mut dec = OpusTrackDecoder::new(OpusTier::Primary);
    let ok = dec
        .push_range(bytes, &mut out)
        .and_then(|_| dec.finish(&mut out))
        .is_ok();
    if ok {
        out.as_slice().to_vec()
    } else {
        Vec::new()
    }
}

/// Decode an AAC-LC (ADTS or M4A) fixture to interleaved f32 PCM.
#[wasm_bindgen]
pub fn decode_aac(bytes: &[u8]) -> Vec<f32> {
    let mut out = match FrameBuf::with_capacity(65_536, 2) {
        Ok(o) => o,
        Err(_) => return Vec::new(),
    };
    let mut dec = match SymphoniaDecoder::new() {
        Ok(d) => d,
        Err(_) => return Vec::new(),
    };
    let ok = dec
        .push_range(bytes, &mut out)
        .and_then(|_| dec.finish(&mut out))
        .is_ok();
    if ok {
        out.as_slice().to_vec()
    } else {
        Vec::new()
    }
}

// ── The ring lives in JS, deliberately ───────────────────────────────────────
//
// A SharedArrayBuffer CANNOT be shared into wasm32-unknown-unknown
// memory (wasm-bindgen copies ArrayBufferView arguments into linear
// memory — the JS side's Atomics view would never see wasm writes).
// The SAB SPSC ring therefore stays ENTIRELY in JS — the harness's
// Phase 0 worklet already speaks the full release/acquire cursor
// protocol — and this crate carries the EXPENSIVE half only: the
// codec decode (Opus/AAC → interleaved f32), returned to the Worker
// as a plain Vec which JS copies into the SAB (or posts through the
// pooled MessagePort lane). The split also keeps the RT path 100%
// JS-side (no wasm calls on the audio thread at all — the worklet
// pulls from the SAB directly, zero wasm boundary crossings in the
// callback; the hard-realtime discipline holds by construction).
