//! streamify-engine-wasm — WS-W P2 / W2.1: the engine CONTROL plane on wasm.
//!
//! # What this mirrors
//!
//! The Android app's engine surface is three layers deep (Kotlin `EnginePort`
//! → UniFFI UDL `StreamifyEngine` → `contract.rs` implementation). The WEB
//! mirror binds the control semantics of that stack onto the single-threaded
//! wasm runtime, with `streamify-core` (std-only, W1.7-proven) supplying the
//! vocabulary types:
//!
//! | UDL / Android semantic | Web lowering |
//! |---|---|
//! | dispatch thread pushes coalesced snapshots (MP-C02) | synchronous callback after every committed mutation (the JS event loop IS the dispatcher — one thread, so coalescing degenerates to "call once per mutation") |
//! | `rev` starts at 1, strictly monotonic; debounced duplicates bump NOTHING; listener registration bumps (fresh listener sees a full snapshot) | identical (the JS wrapper keeps the Android `rev <= lastSeen` drop-guard) |
//! | `on_media_command` 50–150 ms debounce via `media_command_key` | identical, clock = `js_sys::Date::now()` (the house wasm clock — `SystemTime`/`Instant` are banned on this target) |
//! | one-way `Idle→Buffering` initialize; `Stop→Idle`; Error terminal | identical |
//! | transport commands never touch lifecycle (Next while Paused stays Paused) | identical |
//! | empty-queue Next/Previous = acknowledged no-op (returns APPLIED, bumps rev) | identical |
//! | Seek clamps to the row duration; no row = acknowledged no-op | identical |
//! | `play_track` requires a staged row carrying `resolved_stream_url` | identical (`stage_stream_url` is the web's `resolve_stream_formats` landing) |
//! | producer thread consumes `PlayIntent`s off an mpsc channel | `playIntent` latched into the snapshot; the JS sink consumes + acknowledges via `take_play_intent()` |
//! | DAC clock ingest (`record_dac_clock`, 8k–192k validated) | identical — the element/spine sinks report `currentTime`/worklet position as frame counters |
//!
//! # Web extensions (documented deviations, none touch the frozen shapes)
//!
//! * `report_sink_state` — the sink mirrors transport reality (element
//!   `waiting`/`playing`/`pause` events) into the engine the same way the
//!   Android bridge fuses Media3 state into its own StateFlow. Narrowing
//!   only: may set Buffering/Playing/Paused, never Idle/Error (lifecycle
//!   authority stays with commands — `stop()` is the only way back to Idle).
//! * `report_track_ended` — natural end-of-track auto-advance (the Android
//!   equivalent is the Media3 `onPlaybackStateChanged(ENDED)` → next).
//!   Unlike manual Next it does NOT wrap: queue exhausted → Idle.
//! * `resolve` intents — a queue row without a staged URL emits
//!   `{"kind":"resolve"}` so the async web resolver can fetch it, then
//!   confirm with `play_track`.
//! * JSON-string bridge — the established house convention (serde_wasm_bindgen
//!   maps Values to JS Maps; `JSON.stringify(Map) === "{}"` — W0.2 landmine).
//!
//! # What deliberately does NOT live here
//!
//! The data plane. `streamify-playback`'s producer thread + SPSC ring +
//! `Instant`-based parking cannot exist on wasm32 (no threads, no
//! SharedArrayBuffer on GitHub Pages). The web data plane is the TS mirror
//! stack — AudioWorklet ring with the byte-exact 96-byte `SinkStats` wire
//! (`statsWire.ts`), credit backpressure, epoch flush (MP-C10) — driven by
//! this engine's PlayIntents. See `front-end/decisions/W2.0-engine-egress.md`.

use std::collections::HashMap;

use wasm_bindgen::prelude::*;

use streamify_core::{
    media_command_key, MediaCommand, PlaybackState, DAC_CLOCK_MAX_SAMPLE_RATE,
    DAC_CLOCK_MIN_SAMPLE_RATE, MEDIA_COMMAND_DEBOUNCE_MS,
};

// ---------------------------------------------------------------------------
// DTOs (serde layer over the core vocabulary — core is std-only by design)
// ---------------------------------------------------------------------------

#[derive(serde::Deserialize)]
#[serde(rename_all = "camelCase")]
struct TrackDto {
    id: String,
    #[serde(default)]
    title: String,
    #[serde(default)]
    artist: String,
    #[serde(default)]
    album: String,
    #[serde(default)]
    duration_ms: u64,
    #[serde(default)]
    thumbnail_url: Option<String>,
    #[serde(default)]
    is_explicit: bool,
    #[serde(default)]
    resolved_stream_url: Option<String>,
}

#[derive(serde::Serialize)]
#[serde(rename_all = "camelCase")]
struct TrackDtoOut<'a> {
    id: &'a str,
    title: &'a str,
    artist: &'a str,
    album: &'a str,
    duration_ms: u64,
    thumbnail_url: &'a Option<String>,
    is_explicit: bool,
}

#[derive(serde::Deserialize)]
#[serde(tag = "type", rename_all = "camelCase")]
enum CommandDto {
    #[serde(rename = "play")]
    Play,
    #[serde(rename = "pause")]
    Pause,
    #[serde(rename = "toggle")]
    Toggle,
    #[serde(rename = "next")]
    Next,
    #[serde(rename = "previous")]
    Previous,
    #[serde(rename = "seek", rename_all = "camelCase")]
    Seek {
        #[serde(rename = "positionMs")]
        position_ms: u64,
    },
    #[serde(rename = "setVolume")]
    SetVolume { volume: f32 },
    #[serde(rename = "stop")]
    Stop,
}

impl CommandDto {
    fn to_core(&self) -> MediaCommand {
        match self {
            CommandDto::Play => MediaCommand::Play,
            CommandDto::Pause => MediaCommand::Pause,
            CommandDto::Toggle => MediaCommand::TogglePlayPause,
            CommandDto::Next => MediaCommand::Next,
            CommandDto::Previous => MediaCommand::Previous,
            CommandDto::Seek { position_ms } => MediaCommand::Seek {
                position_ms: *position_ms,
            },
            CommandDto::SetVolume { volume } => MediaCommand::SetVolume { volume: *volume },
            CommandDto::Stop => MediaCommand::Stop,
        }
    }
}

#[derive(serde::Serialize, Clone)]
#[serde(rename_all = "camelCase")]
struct BufferStatsDto {
    buffered_duration_ms: u64,
    underrun_count: u32,
    fill_ratio: f32,
}

#[derive(serde::Serialize, Clone)]
#[serde(rename_all = "camelCase")]
struct ClockDto {
    dac_frame_position: u64,
    sample_rate: u32,
    monotonic_ns: u64,
}

/// The web PlayIntent — the latched producer command the JS sink consumes.
#[derive(serde::Serialize, Clone, PartialEq)]
#[serde(rename_all = "camelCase")]
struct PlayIntentDto {
    /// `play` (streamUrl staged), `resolve` (async resolve first),
    /// `seek` (position within current row), `stop` (release the pipeline).
    kind: &'static str,
    #[serde(skip_serializing_if = "Option::is_none")]
    track_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    stream_url: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    start_position_ms: Option<u64>,
}

#[derive(serde::Serialize)]
#[serde(rename_all = "camelCase")]
struct SnapshotDto<'a> {
    rev: u64,
    status: &'static str,
    volume: f32,
    buffer: BufferStatsDto,
    clock: Option<ClockDto>,
    /// Rendered position [ms] derived strictly from the DAC clock sample
    /// (frames ÷ rate) — 0 until a clock exists. Never wall-clock.
    position_ms: u64,
    current_track: Option<TrackDtoOut<'a>>,
    queue_length: usize,
    cursor: Option<usize>,
    /// The transport's position target (A2): the last accepted Seek.
    position_target_ms: u64,
    /// Web extension: the latched producer intent for the JS sink
    /// (consumed + acked via `take_play_intent`).
    play_intent: Option<PlayIntentDto>,
}

// ---------------------------------------------------------------------------
// Engine
// ---------------------------------------------------------------------------

/// Queue cap mirroring the UDL `QueueLane` (`MAX_QUEUE_ROWS`).
const MAX_QUEUE_ROWS: usize = 10_000;

struct QueueRow {
    track: TrackDto,
    enqueued_by: String,
}

fn state_name(s: PlaybackState) -> &'static str {
    match s {
        PlaybackState::Idle => "Idle",
        PlaybackState::Buffering => "Buffering",
        PlaybackState::Playing => "Playing",
        PlaybackState::Paused => "Paused",
        PlaybackState::Error => "Error",
    }
}

fn clamp_volume(v: f32) -> f32 {
    if v.is_nan() {
        0.0
    } else {
        v.clamp(0.0, 1.0)
    }
}

/// The wasm engine control plane. See the crate docs for the semantic map.
#[wasm_bindgen]
pub struct WasmEngine {
    rev: u64,
    state: PlaybackState,
    volume: f32,
    buffer: streamify_core::BufferStats,
    clock: Option<streamify_core::DacPresentationClock>,
    initialized: bool,
    queue: Vec<QueueRow>,
    by_id: HashMap<String, usize>,
    cursor: Option<usize>,
    position_target_ms: u64,
    intent: Option<PlayIntentDto>,
    // Debounce state (MP-C03): last command identity + wall time (ms).
    last_cmd_key: Option<(u8, u64)>,
    last_cmd_at_ms: f64,
    // Listener registry (UDL: add-only, registration bumps rev).
    listeners: Vec<Option<js_sys::Function>>,
}

#[wasm_bindgen]
impl WasmEngine {
    #[wasm_bindgen(constructor)]
    pub fn new() -> WasmEngine {
        WasmEngine {
            rev: 1,
            state: PlaybackState::Idle,
            volume: 1.0,
            buffer: streamify_core::BufferStats::default(),
            clock: None,
            initialized: false,
            queue: Vec::new(),
            by_id: HashMap::new(),
            cursor: None,
            position_target_ms: 0,
            intent: None,
            last_cmd_key: None,
            last_cmd_at_ms: 0.0,
            listeners: Vec::new(),
        }
    }

    // -- lifecycle ----------------------------------------------------------

    /// One-way `Idle→Buffering`, idempotent (second call: no bump, no event).
    pub fn initialize(&mut self) {
        if self.initialized || self.state == PlaybackState::Error {
            return;
        }
        self.initialized = true;
        self.state = PlaybackState::Buffering;
        self.commit();
    }

    /// Direct volume set (the slider path — bypasses the debounce, exactly
    /// like DesktopEnginePort documents for SetVolume).
    pub fn set_volume(&mut self, volume: f32) {
        let clamped = clamp_volume(volume);
        if clamped == self.volume {
            return;
        }
        self.volume = clamped;
        self.commit();
    }

    /// One-shot diagnostics read of the lifecycle state name.
    pub fn get_playback_state(&self) -> String {
        state_name(self.state).to_string()
    }

    /// One-shot cold-start read (the sanctioned one-shot snapshot).
    pub fn get_engine_snapshot_json(&self) -> String {
        self.snapshot_json()
    }

    // -- listener lane ------------------------------------------------------

    /// Register a snapshot listener; returns its id. Registration bumps the
    /// rev and fires the listener immediately with a full snapshot (the UDL
    /// "fresh listener gets a full snapshot next pass" guarantee, hoisted to
    /// registration time on this single-threaded runtime).
    pub fn add_event_listener(&mut self, cb: &js_sys::Function) -> u32 {
        let id = self.listeners.len() as u32;
        self.listeners.push(Some(cb.clone()));
        self.rev += 1;
        // Fire JUST this listener (the registration event), not the fan-out —
        // mirrors the dispatcher publishing to the new listener on its next
        // pass rather than re-broadcasting to everyone.
        let snap = self.snapshot_json();
        let _ = cb.call1(&JsValue::NULL, &JsValue::from_str(&snap));
        id
    }

    // -- telemetry ingest ---------------------------------------------------

    /// Buffer health ingest (the sinks' periodic telemetry).
    pub fn record_buffer_stats(
        &mut self,
        buffered_duration_ms: f64,
        underrun_count: f64,
        fill_ratio: f32,
    ) {
        self.buffer = streamify_core::BufferStats {
            buffered_duration_ms: buffered_duration_ms.max(0.0) as u64,
            underrun_count: underrun_count.max(0.0) as u32,
            fill_ratio: if fill_ratio.is_nan() {
                0.0
            } else {
                fill_ratio.clamp(0.0, 1.0)
            },
        };
        self.commit();
    }

    /// DAC clock ingest — rate validated against the 8k–192k envelope
    /// (invalid samples are dropped, mirroring the UDL validation).
    pub fn record_dac_clock(&mut self, dac_frame_position: f64, sample_rate: f64, monotonic_ns: f64) {
        let rate = sample_rate as u32;
        if (DAC_CLOCK_MIN_SAMPLE_RATE..=DAC_CLOCK_MAX_SAMPLE_RATE).contains(&rate) {
            self.clock = Some(streamify_core::DacPresentationClock {
                dac_frame_position: dac_frame_position.max(0.0) as u64,
                sample_rate: rate,
                monotonic_ns: monotonic_ns.max(0.0) as u64,
            });
            self.commit();
        }
    }

    /// Web extension: the sink mirrors transport reality (element
    /// waiting/playing/pause). Narrowing only — never Idle, never Error;
    /// lifecycle authority stays with the command plane.
    pub fn report_sink_state(&mut self, state: &str) {
        let reported = match state {
            "Buffering" => Some(PlaybackState::Buffering),
            "Playing" => Some(PlaybackState::Playing),
            "Paused" => Some(PlaybackState::Paused),
            _ => None,
        };
        let Some(reported) = reported else { return };
        if matches!(self.state, PlaybackState::Idle | PlaybackState::Error) {
            return; // no session — the sink has nothing to say
        }
        if reported == self.state {
            return;
        }
        self.state = reported;
        self.commit();
    }

    // -- command plane ------------------------------------------------------

    /// The debounced media-command entry (APPLIED=true / DEBOUNCED=false).
    /// Duplicate identity within the 100 ms window is swallowed WITHOUT
    /// bumping the rev — exactly the UDL contract.
    pub fn on_media_command(&mut self, cmd_json: &str) -> bool {
        let Ok(cmd) = serde_json::from_str::<CommandDto>(cmd_json) else {
            return false;
        };

        // Debounce (MP-C03): identity + 100 ms window.
        let key = media_command_key(&cmd.to_core());
        let now = now_ms();
        if let Some(last) = self.last_cmd_key {
            if last == key && (now - self.last_cmd_at_ms) < MEDIA_COMMAND_DEBOUNCE_MS as f64 {
                return false; // swallowed, NO rev bump
            }
        }
        self.last_cmd_key = Some(key);
        self.last_cmd_at_ms = now;

        self.apply_command(&cmd);
        true
    }

    fn apply_command(&mut self, cmd: &CommandDto) {
        match cmd {
            CommandDto::Play => {
                if matches!(
                    self.state,
                    PlaybackState::Buffering | PlaybackState::Playing | PlaybackState::Paused
                ) {
                    self.state = PlaybackState::Playing;
                    self.push_intent(PlayIntentDto {
                        kind: "play",
                        track_id: self.selected_track_id(),
                        stream_url: self.selected_stream_url(),
                        start_position_ms: Some(self.position_target_ms),
                    });
                }
                // From Idle: acknowledged no-op (nothing staged to play).
            }
            CommandDto::Pause => {
                if self.state == PlaybackState::Playing {
                    self.state = PlaybackState::Paused;
                }
            }
            CommandDto::Toggle => match self.state {
                PlaybackState::Playing => self.state = PlaybackState::Paused,
                PlaybackState::Buffering | PlaybackState::Paused => {
                    self.state = PlaybackState::Playing;
                    self.push_intent(PlayIntentDto {
                        kind: "play",
                        track_id: self.selected_track_id(),
                        stream_url: self.selected_stream_url(),
                        start_position_ms: Some(self.position_target_ms),
                    });
                }
                _ => {}
            },
            CommandDto::Next => self.advance_cursor(1, true),
            CommandDto::Previous => self.advance_cursor(-1, true),
            CommandDto::Seek { position_ms } => {
                if let Some(idx) = self.cursor {
                    let target = if let Some(row) = self.queue.get(idx) {
                        if row.track.duration_ms > 0 {
                            (*position_ms).min(row.track.duration_ms)
                        } else {
                            *position_ms
                        }
                    } else {
                        *position_ms
                    };
                    self.position_target_ms = target;
                    self.push_intent(PlayIntentDto {
                        kind: "seek",
                        track_id: self.selected_track_id(),
                        stream_url: None,
                        start_position_ms: Some(target),
                    });
                }
                // No row selected: acknowledged no-op (position_target stays).
            }
            CommandDto::SetVolume { volume } => {
                let clamped = clamp_volume(*volume);
                if clamped != self.volume {
                    self.volume = clamped;
                }
            }
            CommandDto::Stop => self.stop_pipeline(),
        }
        self.commit();
    }

    // -- queue lane (the typed data lane, EngineGateway-shaped) --------------

    /// Enqueue a track row; returns its queue index. Duplicated ids are
    /// allowed (the UDL queue holds rows, not a set) up to MAX_QUEUE_ROWS;
    /// beyond the cap the row is refused (returns u32::MAX).
    pub fn enqueue_track(&mut self, track_json: &str, enqueued_by: &str) -> u32 {
        let Ok(track) = serde_json::from_str::<TrackDto>(track_json) else {
            return u32::MAX;
        };
        if self.queue.len() >= MAX_QUEUE_ROWS || track.id.is_empty() {
            return u32::MAX;
        }
        let index = self.queue.len();
        self.by_id.insert(track.id.clone(), index);
        self.queue.push(QueueRow {
            track,
            enqueued_by: enqueued_by.to_string(),
        });
        self.commit();
        index as u32
    }

    /// Attach the resolved stream URL to a staged row (the web landing of
    /// `resolve_stream_formats`). Returns false when the id is unknown.
    pub fn stage_stream_url(&mut self, track_id: &str, url: &str) -> bool {
        let Some(&idx) = self.by_id.get(track_id) else {
            return false;
        };
        if let Some(row) = self.queue.get_mut(idx) {
            row.track.resolved_stream_url = Some(url.to_string());
            self.commit();
            true
        } else {
            false
        }
    }

    /// Read the whole queue (one consistent snapshot).
    pub fn get_queue_json(&self) -> String {
        #[derive(serde::Serialize)]
        #[serde(rename_all = "camelCase")]
        struct QueueDtoOut<'a> {
            rows: Vec<QueueRowDto<'a>>,
            cursor: Option<usize>,
        }
        #[derive(serde::Serialize)]
        #[serde(rename_all = "camelCase")]
        struct QueueRowDto<'a> {
            index: u32,
            enqueued_by: &'a str,
            track: TrackDtoOut<'a>,
        }
        let out = QueueDtoOut {
            rows: self
                .queue
                .iter()
                .enumerate()
                .map(|(i, row)| QueueRowDto {
                    index: i as u32,
                    enqueued_by: &row.enqueued_by,
                    track: track_out(&row.track),
                })
                .collect(),
            cursor: self.cursor,
        };
        serde_json::to_string(&out).unwrap_or_else(|_| "{\"rows\":[],\"cursor\":null}".into())
    }

    pub fn clear_queue(&mut self) {
        self.queue.clear();
        self.by_id.clear();
        self.cursor = None;
        if !matches!(self.state, PlaybackState::Idle | PlaybackState::Error) {
            self.stop_pipeline();
        }
        self.commit();
    }

    /// `play_track`: the row must be staged AND carry a resolved URL —
    /// returns false (FAILED) otherwise, exactly the UDL precondition.
    /// On success: cursor selects the row, position target resets, state →
    /// Playing, play intent emitted.
    pub fn play_track(&mut self, track_id: &str) -> bool {
        let Some(&idx) = self.by_id.get(track_id) else {
            return false;
        };
        let Some(url) = self
            .queue
            .get(idx)
            .and_then(|row| row.track.resolved_stream_url.clone())
        else {
            return false;
        };
        self.cursor = Some(idx);
        self.position_target_ms = 0;
        self.state = PlaybackState::Playing;
        self.push_intent(PlayIntentDto {
            kind: "play",
            track_id: Some(track_id.to_string()),
            stream_url: Some(url),
            start_position_ms: Some(0),
        });
        self.commit();
        true
    }

    /// Web extension: natural end-of-track. Advances WITHOUT wrap-around;
    /// queue exhausted → Idle (pipeline released, queue retained).
    pub fn report_track_ended(&mut self) {
        if self.cursor.is_none() {
            return;
        }
        match self.cursor.map(|c| c + 1) {
            Some(next) if next < self.queue.len() => {
                self.select_row(next);
            }
            _ => {
                self.stop_pipeline();
            }
        }
        self.commit();
    }

    /// Consume + clear the latched play intent (the JS sink's ack).
    pub fn take_play_intent_json(&mut self) -> Option<String> {
        self.intent
            .take()
            .and_then(|i| serde_json::to_string(&i).ok())
    }

    /// Housekeeping hook for the JS wrapper (no-op here; the UDL has no
    /// listener removal and the engine outlives the page — documented).
    pub fn close(&self) {}
}

impl WasmEngine {
    // -- internals -----------------------------------------------------------

    fn selected_track_id(&self) -> Option<String> {
        self.cursor
            .and_then(|i| self.queue.get(i))
            .map(|r| r.track.id.clone())
    }

    fn selected_stream_url(&self) -> Option<String> {
        self.cursor
            .and_then(|i| self.queue.get(i))
            .and_then(|r| r.track.resolved_stream_url.clone())
    }

    /// Advance the cursor by `delta` rows with wrap-around (manual Next /
    /// Previous). Empty queue: acknowledged no-op. Row without URL: state →
    /// Buffering + `resolve` intent (the async resolver confirms via
    /// `play_track`). Transport NEVER touches lifecycle state: Paused stays
    /// Paused, Idle stays Idle.
    fn advance_cursor(&mut self, delta: i64, wrap: bool) {
        let len = self.queue.len() as i64;
        if len == 0 {
            return; // acknowledged no-op
        }
        let next = match self.cursor {
            None => 0i64, // nothing selected → start at the top
            Some(c) => {
                let c = c as i64 + delta;
                if wrap {
                    c.rem_euclid(len)
                } else {
                    c.clamp(0, len - 1)
                }
            }
        };
        self.select_row(next as usize);
    }

    /// Select a row for playback: cursor set, position target reset, play
    /// intent emitted (or a `resolve` intent when the row has no URL yet).
    /// Lifecycle: Paused/Buffering/Playing stay what they are unless a URL
    /// is present AND we were Playing (advance during active playback keeps
    /// playing); from Idle we enter Buffering (acquiring first data).
    fn select_row(&mut self, idx: usize) {
        self.cursor = Some(idx);
        self.position_target_ms = 0;
        let row_url = self.queue.get(idx).and_then(|r| r.track.resolved_stream_url.clone());
        let track_id = self.queue.get(idx).map(|r| r.track.id.clone());
        match row_url {
            Some(url) => {
                if self.state == PlaybackState::Playing {
                    self.push_intent(PlayIntentDto {
                        kind: "play",
                        track_id,
                        stream_url: Some(url),
                        start_position_ms: Some(0),
                    });
                }
                // Paused/Buffering with a ready URL: row staged, playback
                // starts on the next Play command. No intent (nothing to do).
            }
            None => {
                if self.state == PlaybackState::Playing {
                    self.state = PlaybackState::Buffering;
                }
                self.push_intent(PlayIntentDto {
                    kind: "resolve",
                    track_id,
                    stream_url: None,
                    start_position_ms: Some(0),
                });
            }
        }
    }

    /// Stop the pipeline: → Idle, reset the transport position target and
    /// the underrun tally (the Android bridge's "entering Idle resets
    /// tally"), emit a stop intent so the sink releases resources.
    /// The queue is RETAINED (clear_queue is a separate, explicit verb).
    fn stop_pipeline(&mut self) {
        self.state = PlaybackState::Idle;
        self.position_target_ms = 0;
        self.buffer = streamify_core::BufferStats::default();
        self.push_intent(PlayIntentDto {
            kind: "stop",
            track_id: None,
            stream_url: None,
            start_position_ms: None,
        });
    }

    fn push_intent(&mut self, intent: PlayIntentDto) {
        self.intent = Some(intent);
    }

    /// The dispatcher: bump rev, then fan the snapshot out to every live
    /// listener synchronously (post-commit — listeners never observe
    /// mid-mutation state; a throwing listener cannot corrupt the engine,
    /// its error is swallowed).
    fn commit(&mut self) {
        self.rev += 1;
        let snap = self.snapshot_json();
        let js = JsValue::from_str(&snap);
        for cb in self.listeners.iter().flatten() {
            let _ = cb.call1(&JsValue::NULL, &js);
        }
    }

    fn position_ms(&self) -> u64 {
        self.clock
            .as_ref()
            .map(|c| {
                if c.sample_rate == 0 {
                    0
                } else {
                    c.dac_frame_position / c.sample_rate as u64
                }
            })
            .unwrap_or(0)
            * 1000
    }

    fn snapshot_json(&self) -> String {
        let dto = SnapshotDto {
            rev: self.rev,
            status: state_name(self.state),
            volume: self.volume,
            buffer: BufferStatsDto {
                buffered_duration_ms: self.buffer.buffered_duration_ms,
                underrun_count: self.buffer.underrun_count,
                fill_ratio: self.buffer.fill_ratio,
            },
            clock: self.clock.as_ref().map(|c| ClockDto {
                dac_frame_position: c.dac_frame_position,
                sample_rate: c.sample_rate,
                monotonic_ns: c.monotonic_ns,
            }),
            position_ms: self.position_ms(),
            current_track: self
                .cursor
                .and_then(|i| self.queue.get(i))
                .map(|r| track_out(&r.track)),
            queue_length: self.queue.len(),
            cursor: self.cursor,
            position_target_ms: self.position_target_ms,
            play_intent: self.intent.clone(),
        };
        serde_json::to_string(&dto).unwrap_or_else(|_| "{}".into())
    }
}

fn track_out(t: &TrackDto) -> TrackDtoOut<'_> {
    TrackDtoOut {
        id: &t.id,
        title: &t.title,
        artist: &t.artist,
        album: &t.album,
        duration_ms: t.duration_ms,
        thumbnail_url: &t.thumbnail_url,
        is_explicit: t.is_explicit,
    }
}

/// The house wasm clock (SystemTime/Instant are banned on this target —
/// the vendored rustypipe uses exactly this).
fn now_ms() -> f64 {
    js_sys::Date::now()
}

// ---------------------------------------------------------------------------
// Tests (wasm-bindgen-test, headless Chrome — the W1.7 lane)
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;
    use wasm_bindgen_test::wasm_bindgen_test;

    fn engine() -> WasmEngine {
        WasmEngine::new()
    }

    fn track_json(id: &str, url: Option<&str>) -> String {
        serde_json::json!({
            "id": id,
            "title": format!("Track {id}"),
            "artist": "Artist",
            "album": "Album",
            "durationMs": 213_000,
            "resolvedStreamUrl": url,
        })
        .to_string()
    }

    fn cmd(kind: &str) -> String {
        format!("{{\"type\":\"{kind}\"}}")
    }

    #[wasm_bindgen_test]
    fn rev_starts_at_one_and_bumps_on_mutations() {
        let mut e = engine();
        assert_eq!(e.rev, 1);
        assert_eq!(e.get_playback_state(), "Idle");
        e.set_volume(0.5);
        assert_eq!(e.rev, 2);
        // Same volume: no bump (no accepted mutation).
        e.set_volume(0.5);
        assert_eq!(e.rev, 2);
    }

    #[wasm_bindgen_test]
    fn initialize_is_one_way_and_idempotent() {
        let mut e = engine();
        e.initialize();
        assert_eq!(e.get_playback_state(), "Buffering");
        assert_eq!(e.rev, 2);
        e.initialize();
        assert_eq!(e.rev, 2); // idempotent — no bump
    }

    #[wasm_bindgen_test]
    fn duplicate_commands_debounce_without_rev_bump() {
        let mut e = engine();
        e.initialize();
        let rev = e.rev;
        assert!(e.on_media_command(&cmd("play")));
        let after = e.rev;
        assert!(after > rev);
        // Identical command within the window: swallowed, NO bump.
        assert!(!e.on_media_command(&cmd("play")));
        assert_eq!(e.rev, after);
        // Different variant: applied.
        assert!(e.on_media_command(&cmd("pause")));
        assert!(e.rev > after);
    }

    #[wasm_bindgen_test]
    fn stop_returns_to_idle_and_resets_underruns() {
        let mut e = engine();
        e.initialize();
        assert!(e.on_media_command(&cmd("play")));
        assert_eq!(e.get_playback_state(), "Playing");
        e.record_buffer_stats(1000.0, 5.0, 0.5);
        assert!(e.on_media_command(&cmd("stop")));
        assert_eq!(e.get_playback_state(), "Idle");
        let snap = serde_json::from_str::<serde_json::Value>(&e.get_engine_snapshot_json())
            .unwrap();
        assert_eq!(snap["buffer"]["underrunCount"], 0);
    }

    #[wasm_bindgen_test]
    fn transport_never_touches_lifecycle() {
        let mut e = engine();
        e.enqueue_track(&track_json("a", Some("https://x/a")), "t");
        e.play_track("a");
        assert_eq!(e.get_playback_state(), "Playing");
        assert!(e.on_media_command(&cmd("pause")));
        assert_eq!(e.get_playback_state(), "Paused");
        // Next while Paused stays Paused (transport ≠ lifecycle).
        e.enqueue_track(&track_json("b", Some("https://x/b")), "t");
        assert!(e.on_media_command(&cmd("next")));
        assert_eq!(e.get_playback_state(), "Paused");
        // ...but the cursor moved.
        let snap: serde_json::Value =
            serde_json::from_str(&e.get_engine_snapshot_json()).unwrap();
        assert_eq!(snap["currentTrack"]["id"], "b");
    }

    #[wasm_bindgen_test]
    fn empty_queue_next_is_acknowledged_noop() {
        let mut e = engine();
        e.initialize();
        let rev = e.rev;
        assert!(e.on_media_command(&cmd("next"))); // APPLIED, not FAILED
        assert_eq!(e.rev, rev + 1); // acknowledged: bumps
        assert_eq!(e.get_playback_state(), "Buffering"); // untouched
    }

    #[wasm_bindgen_test]
    fn seek_clamps_to_row_duration() {
        let mut e = engine();
        e.enqueue_track(&track_json("a", Some("https://x/a")), "t");
        e.play_track("a");
        assert!(e.on_media_command(
            "{\"type\":\"seek\",\"positionMs\":999999999}"
        ));
        let intent = e.take_play_intent_json().expect("seek intent");
        let v: serde_json::Value = serde_json::from_str(&intent).unwrap();
        assert_eq!(v["kind"], "seek");
        assert_eq!(v["startPositionMs"], 213_000); // clamped
    }

    #[wasm_bindgen_test]
    fn seek_without_row_is_acknowledged_noop() {
        let mut e = engine();
        let rev = e.rev;
        assert!(e.on_media_command("{\"type\":\"seek\",\"positionMs\":1000}"));
        assert_eq!(e.rev, rev + 1); // acknowledged
        assert!(e.take_play_intent_json().is_none()); // but no intent
    }

    #[wasm_bindgen_test]
    fn play_track_requires_staged_url() {
        let mut e = engine();
        e.enqueue_track(&track_json("a", None), "t"); // no URL staged
        assert!(!e.play_track("a")); // FAILED — UDL precondition
        assert!(e.stage_stream_url("a", "https://x/a"));
        assert!(e.play_track("a"));
        assert_eq!(e.get_playback_state(), "Playing");
        let intent = e.take_play_intent_json().unwrap();
        let v: serde_json::Value = serde_json::from_str(&intent).unwrap();
        assert_eq!(v["kind"], "play");
        assert_eq!(v["streamUrl"], "https://x/a");
        assert_eq!(v["startPositionMs"], 0);
    }

    #[wasm_bindgen_test]
    fn next_wraps_around_and_previous_wraps_back() {
        let mut e = engine();
        e.enqueue_track(&track_json("a", Some("https://x/a")), "t");
        e.enqueue_track(&track_json("b", Some("https://x/b")), "t");
        e.enqueue_track(&track_json("c", Some("https://x/c")), "t");
        e.play_track("a");
        assert!(e.on_media_command(&cmd("previous"))); // wraps to last
        let snap: serde_json::Value =
            serde_json::from_str(&e.get_engine_snapshot_json()).unwrap();
        assert_eq!(snap["currentTrack"]["id"], "c");
        assert!(e.on_media_command(&cmd("next"))); // wraps to first
        let snap: serde_json::Value =
            serde_json::from_str(&e.get_engine_snapshot_json()).unwrap();
        assert_eq!(snap["currentTrack"]["id"], "a");
    }

    #[wasm_bindgen_test]
    fn track_ended_advances_without_wrap_and_finishes_idle() {
        let mut e = engine();
        e.enqueue_track(&track_json("a", Some("https://x/a")), "t");
        e.enqueue_track(&track_json("b", Some("https://x/b")), "t");
        e.play_track("a");
        e.report_track_ended();
        let snap: serde_json::Value =
            serde_json::from_str(&e.get_engine_snapshot_json()).unwrap();
        assert_eq!(snap["currentTrack"]["id"], "b");
        assert_eq!(snap["status"], "Playing");
        e.report_track_ended(); // queue exhausted
        assert_eq!(e.get_playback_state(), "Idle");
        // Queue retained.
        let q: serde_json::Value = serde_json::from_str(&e.get_queue_json()).unwrap();
        assert_eq!(q["rows"].as_array().unwrap().len(), 2);
    }

    #[wasm_bindgen_test]
    fn resolve_intent_for_url_less_rows_during_playback() {
        let mut e = engine();
        e.enqueue_track(&track_json("a", Some("https://x/a")), "t");
        e.play_track("a");
        assert_eq!(e.get_playback_state(), "Playing");
        // Enqueue an unresolved row and advance into it mid-session.
        e.enqueue_track(&track_json("b", None), "t");
        assert!(e.on_media_command(&cmd("next")));
        assert_eq!(e.get_playback_state(), "Buffering"); // acquiring data
        let intent = e.take_play_intent_json().expect("resolve intent");
        let v: serde_json::Value = serde_json::from_str(&intent).unwrap();
        assert_eq!(v["kind"], "resolve");
        assert_eq!(v["trackId"], "b");
        // The async resolver lands: stage + confirm.
        assert!(e.stage_stream_url("b", "https://x/b"));
        assert!(e.play_track("b"));
        assert_eq!(e.get_playback_state(), "Playing");
        let intent = e.take_play_intent_json().unwrap();
        let v: serde_json::Value = serde_json::from_str(&intent).unwrap();
        assert_eq!(v["kind"], "play");
        assert_eq!(v["streamUrl"], "https://x/b");
    }

    #[wasm_bindgen_test]
    fn volume_clamps_and_nan_fails_safe_to_mute() {
        let mut e = engine();
        e.set_volume(2.0);
        let snap: serde_json::Value =
            serde_json::from_str(&e.get_engine_snapshot_json()).unwrap();
        assert_eq!(snap["volume"], 1.0);
        e.set_volume(f32::NAN);
        let snap: serde_json::Value =
            serde_json::from_str(&e.get_engine_snapshot_json()).unwrap();
        assert_eq!(snap["volume"], 0.0);
    }

    #[wasm_bindgen_test]
    fn dac_clock_validates_rate_envelope() {
        let mut e = engine();
        e.record_dac_clock(48_000.0, 1_000.0, 1.0); // below 8k: dropped
        let snap: serde_json::Value =
            serde_json::from_str(&e.get_engine_snapshot_json()).unwrap();
        assert!(snap["clock"].is_null());
        e.record_dac_clock(96_000.0, 48_000.0, 2.0);
        let snap: serde_json::Value =
            serde_json::from_str(&e.get_engine_snapshot_json()).unwrap();
        assert_eq!(snap["clock"]["sampleRate"], 48_000);
        assert_eq!(snap["positionMs"], 2_000); // 96000 frames / 48000 = 2s
    }

    #[wasm_bindgen_test]
    fn sink_state_is_narrowing_only() {
        let mut e = engine();
        e.report_sink_state("Playing"); // Idle: ignored
        assert_eq!(e.get_playback_state(), "Idle");
        e.initialize();
        e.report_sink_state("Playing");
        assert_eq!(e.get_playback_state(), "Playing");
        e.report_sink_state("Buffering"); // rebuffer
        assert_eq!(e.get_playback_state(), "Buffering");
        e.report_sink_state("Idle"); // forbidden from the sink
        assert_eq!(e.get_playback_state(), "Buffering");
        e.report_sink_state("Bogus"); // unknown names ignored
        assert_eq!(e.get_playback_state(), "Buffering");
    }

    #[wasm_bindgen_test]
    fn listener_registration_bumps_and_fires() {
        let mut e = engine();
        let rev0 = e.rev;
        let cb = js_sys::Function::new_no_args("return 0;");
        e.add_event_listener(&cb);
        assert_eq!(e.rev, rev0 + 1); // registration bumps
    }

    #[wasm_bindgen_test]
    fn queue_caps_at_ten_thousand_rows() {
        let mut e = engine();
        let t = track_json("a", None);
        for i in 0..10_000 {
            let tj = serde_json::json!({
                "id": format!("v{i}"),
                "title": "t",
                "artist": "a",
                "album": "al",
                "durationMs": 1000,
            })
            .to_string();
            assert!(e.enqueue_track(&tj, "t") != u32::MAX);
        }
        assert_eq!(e.enqueue_track(&t, "overflow"), u32::MAX); // refused
        let q: serde_json::Value = serde_json::from_str(&e.get_queue_json()).unwrap();
        assert_eq!(q["rows"].as_array().unwrap().len(), 10_000);
    }
}
