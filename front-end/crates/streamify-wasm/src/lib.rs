//! streamify-wasm — WS-W P0 proof facade (W0.2 / W0.4).
//!
//! Compiles the vendored, wasm-patched rustypipe YouTube Music engine to
//! wasm32-unknown-unknown and exposes a minimal wasm-bindgen surface:
//!
//!   * [`ping`] — the W0.2 alive check (`"pong"`).
//!   * [`set_relay_base_url`] — routes every rustypipe HTTP call through
//!     the streamify-relay Cloudflare Worker (`{relay}/r?u=<encoded>`),
//!     because browsers cannot call youtubei.googleapis.com directly
//!     (CORS 403).
//!   * [`search_music`] — rustypipe `music_search_tracks`, returned as a
//!     JSON STRING. This is the JSON-string bridge: `serde_wasm_bindgen`
//!     maps `serde_json::Value` to JS Maps, and `JSON.stringify(Map)` is
//!     `"{}"` — so we pass a string and parse on the TS side (W0.1
//!     landmine #3).
//!   * [`resolve_stream`] — the best audio format for the 10-second
//!     playback proof (G0.4): m4a first, then webm, bitrate descending —
//!     mirrors the engine's `map_player_to_formats` ordering contract
//!     (rust/crates/streamify-resolver/src/rustypipe_engine.rs).
//!
//! Known web-build deviations (see third_party/rustypipe/PATCHES.md):
//! QuickJS signature deobfuscation and PO-token minting are compiled OUT
//! on wasm — the PO-free client ladder is the web strategy, exactly as the
//! engine already degrades on iOS (rustypipe_engine.rs module docs).
//!
//! W0.1 landmines honored here:
//!   * #2 async extern machinery — all wasm-bindgen async fns ride
//!     wasm-bindgen-futures' promise glue (no `spawn_local` around async
//!     extern "C").
//!   * #4 `SystemTime::now()` traps on wasm32-unknown-unknown — the
//!     vendored crate routes every clock read through
//!     `js_sys::Date::now()`.

use wasm_bindgen::prelude::*;

// P4-A feature split (see Cargo.toml [features] and decisions/p4/): the
// engine facade (this file's historical body, now src/engine.rs) and the
// cipher facade (src/cipher.rs — the feishin downloads chunk cipher) are
// disjoint. A bare build keeps the historical default surface; a
// `--no-default-features --features cipher` build compiles the compact
// cipher-only artifact (no rustypipe, no serde, no js-sys).
#[cfg(feature = "cipher")]
pub mod cipher;
#[cfg(feature = "engine")]
mod engine;

/// W0.2 alive check (both facades carry it).
#[wasm_bindgen]
pub fn ping() -> String {
    "pong".into()
}

// ---------------------------------------------------------------------------
// W0.2 AC: minimal wasm-bindgen-test — ping() must return "pong".
// Run on CI in headless Chrome (`cargo test --target wasm32-unknown-unknown
// --headless`); never run the engine's 429-test suite here — that stays on
// the frozen rust/ workspace's CI.
// ---------------------------------------------------------------------------
#[cfg(all(target_arch = "wasm32", test))]
mod tests {
    use super::*;
    use wasm_bindgen_test::*;

    #[wasm_bindgen_test]
    fn ping_returns_pong() {
        assert_eq!(ping(), "pong");
    }

    /// P4-A cipher facade health probe (runs with
    /// `cargo test --no-default-features --features cipher`; the feishin
    /// side additionally exercises the vendored artifact through vitest).
    #[cfg(feature = "cipher")]
    #[wasm_bindgen_test]
    fn cipher_self_test_passes() {
        assert_eq!(cipher::dl_self_test(), 0);
        assert_eq!(cipher::dl_format_version(), 1);
        assert_eq!(cipher::dl_chunk_size(), 65536);
    }
}
