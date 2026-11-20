//! SW-ENG1-03 (Swift migration, Engineer 1): the typed catalog vocabulary
//! the Apple shells consume — the fully typed `Track`, the playlist and
//! search result records, the playback queue item, the parametric
//! equalizer band and the Jam room state mirror.
//!
//! Pure leaf data by design, exactly like [`crate::snapshot`]: no engine
//! behavior lives here, no dependency beyond `std`, and every consumer —
//! the UniFFI contract (streamify-contract re-exports these at its root
//! where the scaffolding binds them by bare name), the Swift shells and
//! the in-workspace domain crates — shares one definition.
//!
//! RELATION to the existing records: [`crate::snapshot::TrackMetadata`]
//! stays the minimal playback-lifecycle metadata currency (it is what
//! the frozen `EngineSnapshot` pipeline was built around); `Track` below
//! is the RICHER library/search currency the SwiftUI surfaces render
//! (album, artwork, explicit flag, lazily resolved stream URL). The two
//! deliberately coexist: the snapshot lane stays frozen, the typed
//! catalog lane is additive (MP-C05: additive contract growth, no
//! version bump).

use crate::error::EngineError;

/// Fully typed playable track — the search/library currency of the typed
/// Apple lane (SW-ENG1-03).
///
/// Field semantics:
///  * `id` — the YouTube video id (the resolver's currency everywhere);
///  * `duration_ms` — the video-level duration in milliseconds;
///  * `thumbnail_url` — best-effort artwork; `None` when the upstream
///    row carried none (empty strings are normalized to `None` at the
///    boundary so the UI never renders a broken image);
///  * `is_explicit` — the upstream explicit flag; YouTube Music search
///    rows do not always carry it, so `false` means "not flagged", not
///    "provably clean";
///  * `resolved_stream_url` — the playable CDN URL once resolved; the
///    typed search lane leaves it `None` on purpose (resolution is an
///    explicit, cache-backed step via `resolve_stream_formats`), so a
///    track row is cheap and never pins a de-throttled URL that would
///    rot in a list.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Track {
    pub id: String,
    pub title: String,
    pub artist: String,
    pub album: String,
    pub duration_ms: u64,
    pub thumbnail_url: Option<String>,
    pub is_explicit: bool,
    pub resolved_stream_url: Option<String>,
}

/// One playlist row in a search result page.
///
/// The search surface today returns track rows; playlist rows arrive the
/// moment the resolver's playlist tier crosses this contract, so the
/// record ships now to keep the SearchResult shape stable (the Swift
/// side can already render the rail with an empty list).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PlaylistSummary {
    pub id: String,
    pub title: String,
    pub thumbnail_url: Option<String>,
    pub track_count: u32,
}

/// One typed search response (SW-ENG1-03).
///
/// `tracks` preserves the upstream relevance order; `continuation_token`
/// is the paging cursor when the backend provides one (`None` on the
/// current single-page mapping).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SearchResult {
    pub tracks: Vec<Track>,
    pub playlists: Vec<PlaylistSummary>,
    pub continuation_token: Option<String>,
}

/// One row of the engine's playback queue, as read back through the
/// typed lane. `index` is the row's stable position at read time (the
/// queue is read-snapshotted under one lock, so a whole page is always
/// internally consistent).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct QueueItem {
    pub index: u32,
    pub track: Track,
    pub enqueued_by: String,
}

/// One parametric equalizer band (SW-ENG1-03).
///
/// The band vocabulary mirrors `streamify_audio::StudioEqualizer`: ten
/// bands, octave-spaced, low shelf on band 0 and high shelf on band 9.
/// The frequencies and the reference Q below are the SAME constants the
/// DSP configures its biquads with, so a UI round-trip through this
/// record lands on the filters the audio crate actually runs.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct EqualizerBand {
    /// Band position, 0..[`EQUALIZER_BAND_COUNT`).
    pub band_index: u8,
    /// Center (shelf corner) frequency in Hz.
    pub frequency_hz: f32,
    /// Gain in dB (positive boosts, negative cuts).
    pub gain_db: f32,
    /// Filter Q factor (1.414 ≡ Butterworth by default).
    pub q_factor: f32,
}

/// The equalizer band count pinned by the DSP crate's `StudioEqualizer`.
pub const EQUALIZER_BAND_COUNT: usize = 10;

impl EqualizerBand {
    /// The octave-spaced band centers, identical to
    /// `streamify_audio::dsp::StudioEqualizer::FREQUENCIES` (kept as a
    /// local constant because the audio crate is an RT leaf the contract
    /// deliberately does not depend on; a drift here is caught by the
    /// parity test that compares the two tables).
    pub const FREQUENCIES_HZ: [f32; EQUALIZER_BAND_COUNT] = [
        31.25, 62.5, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0,
    ];

    /// The Butterworth reference Q the DSP configures its shelves/peaks
    /// with (0.707 of an octave, the studio default).
    pub const DEFAULT_Q: f32 = 1.414;

    /// Flat band at `band_index` (0 dB, reference Q) — the neutral
    /// preset every surface starts from.
    pub fn flat(band_index: u8) -> Self {
        let idx = (band_index as usize).min(EQUALIZER_BAND_COUNT - 1);
        Self {
            band_index: idx as u8,
            frequency_hz: Self::FREQUENCIES_HZ[idx],
            gain_db: 0.0,
            q_factor: Self::DEFAULT_Q,
        }
    }

    /// The default ten-band flat set, band 0 (31.25 Hz low shelf)
    /// through band 9 (16 kHz high shelf).
    pub fn default_set() -> Vec<Self> {
        (0..EQUALIZER_BAND_COUNT as u8).map(Self::flat).collect()
    }

    /// Boundary validation shared by every entry point that accepts a
    /// band from foreign code: the index must address a real band, and
    /// every float must be finite (a NaN/inf gain or Q is rejected as
    /// `EngineError::Internal` exactly like malformed telemetry — the
    /// house rule for numeric ingress).
    pub fn validate(&self) -> Result<(), EngineError> {
        if self.band_index as usize >= EQUALIZER_BAND_COUNT {
            return Err(EngineError::Internal {
                error_message: format!(
                    "invalid state: equalizer band index {} outside 0..{}",
                    self.band_index, EQUALIZER_BAND_COUNT
                ),
                backtrace: None,
            });
        }
        if !self.gain_db.is_finite() || !self.frequency_hz.is_finite() || !self.q_factor.is_finite()
        {
            return Err(EngineError::Internal {
                error_message: "invalid state: non-finite equalizer band rejected at the boundary"
                    .to_string(),
                backtrace: None,
            });
        }
        if self.q_factor <= 0.0 {
            return Err(EngineError::Internal {
                error_message: "invalid state: non-positive equalizer Q rejected at the boundary"
                    .to_string(),
                backtrace: None,
            });
        }
        Ok(())
    }
}

/// Live Jam room state as the SwiftUI jam surfaces render it
/// (SW-ENG1-03).
///
/// This is the PRESENTATION mirror of the engine's active Jam replica
/// (the interior `JamSession` stays private; the FFI lane sees handles
/// and this record). `sync_offset_ms` is the local replica's PTP
/// presentation offset — 0.0 while the room is a fresh local mirror;
/// the jam crate's Kalman-PLL offset lands on this field the moment the
/// PTP lane crosses the typed contract.
#[derive(Debug, Clone, PartialEq)]
pub struct JamRoomState {
    /// The room's human code (the session name the host minted).
    pub room_code: String,
    /// Whether THIS device's replica minted the room (the host).
    pub is_host: bool,
    /// PTP presentation offset in milliseconds (0.0 pre-PLL).
    pub sync_offset_ms: f64,
    /// Known participant labels; starts with the local replica.
    pub participants: Vec<String>,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn default_set_matches_the_dsp_band_plan() {
        let set = EqualizerBand::default_set();
        assert_eq!(set.len(), EQUALIZER_BAND_COUNT);
        for (i, band) in set.iter().enumerate() {
            assert_eq!(band.band_index as usize, i);
            assert_eq!(band.frequency_hz, EqualizerBand::FREQUENCIES_HZ[i]);
            assert_eq!(band.gain_db, 0.0);
            assert_eq!(band.q_factor, EqualizerBand::DEFAULT_Q);
        }
    }

    #[test]
    fn band_validation_rejects_hostile_input() {
        let ok = EqualizerBand::flat(4);
        assert!(ok.validate().is_ok());

        let out_of_range = EqualizerBand {
            band_index: EQUALIZER_BAND_COUNT as u8,
            ..EqualizerBand::flat(0)
        };
        assert!(matches!(out_of_range.validate(), Err(EngineError::Internal { .. })));

        let nan_gain = EqualizerBand {
            gain_db: f32::NAN,
            ..EqualizerBand::flat(1)
        };
        assert!(matches!(nan_gain.validate(), Err(EngineError::Internal { .. })));

        let zero_q = EqualizerBand {
            q_factor: 0.0,
            ..EqualizerBand::flat(1)
        };
        assert!(matches!(zero_q.validate(), Err(EngineError::Internal { .. })));
    }

    #[test]
    fn flat_clamps_out_of_range_index_to_the_last_band() {
        // A defensive constructor must never panic on hostile input.
        let band = EqualizerBand::flat(200);
        assert_eq!(band.band_index, EQUALIZER_BAND_COUNT as u8 - 1);
    }
}
