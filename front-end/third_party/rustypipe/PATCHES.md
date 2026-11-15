# rustypipe 0.11.4 (vendored web copy) — patch ledger

Source: crates.io `rustypipe 0.11.4` (ThetaDev, GPL-3.0), copied verbatim
into this directory and patched for `wasm32-unknown-unknown` — WS-W P0
(task W0.2). Every change below is tagged `WS-W patch` in the source.
The frozen engine (`../../rust`) keeps consuming the crates.io crate via
its own lockfile — this copy resolves only through the
`[patch.crates-io]` table in `front-end/Cargo.toml`.

This copy is **wasm-only**. Native builds are not supported (use the
crates.io crate — the engine does).

## Why a vendored copy at all

The W0.1 spike found the P0 blockers, and W0.2 confirmed them against the
published manifest:

1. **`rquickjs` is an *unconditional* dependency of rustypipe 0.11.4**
   (`[dependencies.rquickjs] version = "0.9.0"` — no feature gate).
   `default-features = false` does NOT remove it (the default feature
   only toggles reqwest TLS). `rquickjs-sys` embeds the QuickJS C
   interpreter: no libc on wasm32-unknown-unknown, and no shipped
   bindings for the triple → cannot compile. This is exactly the
   "rustypipe-botguard companion" landmine from W0.1.
2. **tokio `time`/`process` features** — no clock driver and no
   `std::process` on wasm32-unknown-unknown.
3. **`std::time::SystemTime::now()` / `Instant::now()` trap at runtime**
   on wasm32-unknown-unknown (W0.1 landmine #4).

## Patch list

| # | File | Change |
|---|------|--------|
| 1 | `Cargo.toml` | `rquickjs` optional behind new `js` feature (default OFF). Everything QuickJS-dependent cfg-gates out. |
| 2 | `Cargo.toml` | tokio: `["sync","macros"]` only (dropped `time`, `process`). |
| 3 | `Cargo.toml` | reqwest: `["json"]` only (dropped `gzip`, `brotli`, TLS — the browser fetch layer provides TLS + decompression on wasm). |
| 4 | `Cargo.toml` | wasm-only deps: `js-sys`, `wasm-bindgen`, `wasm-bindgen-futures`; `getrandom` with the JS backend (`wasm_js`) so `rand` compiles. |
| 5 | `Cargo.toml` | dev-dependencies stripped. |
| 6 | `src/deobfuscate.rs` | `Deobfuscator` (QuickJS runtime) cfg-gated on `js`; no-`js` arms return `DeobfError::Other("QuickJS not compiled in (web build)")`. `call_fn`, `verify_fn`, and the variable-resolution section of `extract_js_fn` gate out too. `DeobfData::extract_fns` (pure-Rust `ress` tokenizing) still compiles. |
| 7 | `src/error.rs` | `DeobfError::JavaScript` variant cfg-gated on `js`. |
| 8 | `src/client/mod.rs` | **Web relay hook**: `set_relay_base_url(base)` (pub) + `WEB_RELAY_BASE` + `web_relay_target(url)` helper; the innertube request in `execute_request_attempt` AND `http_request` rewrite every outgoing URL to `{relay}/r?u={percent-encoded original}` on wasm. |
| 9 | `src/client/mod.rs` | `get_po_tokens` cfg'd out on wasm (botguard companion binary = process spawning) — stub returns `ExtractionError::Botguard("not available on wasm")`. The PO-free client ladder is the web strategy. |
| 10 | `src/client/mod.rs` | `detect_botguard_bin` (`std::process::Command`) cfg'd out on wasm — stub always errs, so `RustyPipeBuilder` botguard detection stays `None`. |
| 11 | `src/client/mod.rs` | `build_with_client`: `.gzip(true).brotli(true).redirect(Policy::none())` + `.timeout()` cfg'd to non-wasm (features dropped; wasm fetch follows redirects — the relay answers upstream 3xx). |
| 12 | `src/client/mod.rs` + `src/util/mod.rs` | `tokio::time::sleep` sites → `util::sleep` (JS `setTimeout` via `js_sys::eval` + `JsFuture` on wasm). |
| 13 | `src/util/mod.rs` | `util::now_utc()` — `js_sys::Date::now()` on wasm, `OffsetDateTime::now_utc()` otherwise. All `OffsetDateTime::now_utc()` call sites (`util/date.rs`, `util/timeago.rs`, `util/visitor_data.rs`, `client/player.rs`, `client/mod.rs`) route through it — W0.1 landmine #4. |
| 14 | `src/util/visitor_data.rs` | `tokio::spawn` (background visitor-data refresh) cfg'd out on wasm — no tokio runtime in the browser; cache refills lazily on the next `get()`. |
| 15 | `src/util/visitor_data.rs` | **Upstream bug**: `new_visitor_data` had a bare `.unwrap()` on the visitor-data fetch — any failure ABORTED the whole wasm instance (panic = abort in the release profile). Now propagates with `?`. |
| 16 | `src/util/visitor_data.rs` | `fetch_visitor_data` relay-wraps the `music.youtube.com` homepage scrape on wasm (raw-client fetch — bypassed the relay). |
| 17 | `src/client/mod.rs` | **Visitor-data degradation on wasm**: the homepage scrape is structurally degraded in the browser (cookies are a forbidden fetch header; datacenter IPs get consent-walled pages without the visitorData marker) — and innertube answers requests WITHOUT visitor data (verified by the W0.4 curl matrix). `execute_request_attempt` degrades to empty instead of failing; `ClientInfo.visitor_data` gained `skip_serializing_if = "str::is_empty"`; the `X-Goog-EOM-Visitor-Id` header filters empties (an EMPTY header value drew the soft-block "The page needs to be reloaded"). |
| 18 | `src/client/mod.rs` | **Hardcoded client versions refreshed** to the probe-matrix values (yt-dlp master 2026-08-24, verified live in the W0.4 curl matrix): ANDROID 19.44.38→**21.26.364**, IOS 20.03.02→**21.26.4** (+ device/build strings), TV 7.20241211.14.00→**7.20260707.07.00**. The 0.11.4-vintage constants drew "Precondition check failed" from the current innertube. Same rot-cadence as `tools/probe-clients.sh` — re-verify monthly. |
| 19 | `src/client/player.rs` | **Deobf fallback on wasm**: the 0.11.4 extraction patterns no longer match the CURRENT player.js ("could not extract sig fn name") — and QuickJS is compiled out anyway. `player_from_client` uses a static `DeobfData` (sts = the W0.1 probe value 19850) for deobf-requiring clients; `StreamsMapper::new` tolerates the unavailable `Deobfuscator` (None). |
| 20 | `src/client/player.rs` | **nsig non-fatal on wasm**: an undeciphered `n` param yields a THROTTLED but playable CDN URL — `deobf_nsig` returns Ok with the raw n instead of failing the whole format. |
| 21 | `src/client/player.rs` | **SABR-era acceptance**: the current innertube answers ANDROID/IOS with URL-less adaptive descriptors (server-side ABR) + a direct URL on the muxed formats. Upstream's "no adaptive stream URLs → retriable failure" walks the whole ladder into the Tv client's UNPLAYABLE "The page needs to be reloaded" wall — cfg'd out on wasm; the facade's muxed fallback serves the audio path. |
| 22 | `src/deobfuscate.rs` | Relay-wrap the `iframe_api` + `player.js` downloads on wasm (raw-client fetches that bypassed the relay — the deobf prefetch for Android/Tv clients). |

## Web-build consequences (documented for W1)

* No signature/n-parameter deobfuscation → **Desktop/WEB clients whose
  URLs are signature-ciphered are unusable on the web**. The PO-free
  client ladder (ANDROID_VR / IOS / VISIONOS-style clients whose stream
  URLs are returned unciphered) is the web strategy — same degradation
  the engine ships on iOS today.
* No PO tokens → licensed/SABR-era content may be bot-walled from the
  browser; the canary (W0.1b) measures exactly this from residential IPs.
* `Instant::now()` in `get_po_tokens` is gone with the fn (wasm stub).
* reqwest on wasm bridges to the browser `fetch` API: the User-Agent
  header is browser-controlled (a forbidden header — `fetch` filters it);
  the `X-YouTube-Client-*` headers carry the client identity through the
  relay. The canary records this deviation per row.
