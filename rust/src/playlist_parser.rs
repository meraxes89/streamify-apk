use serde::{Deserialize, Serialize};
use serde_json::Value;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ParsedPlaylistTrack {
    pub video_id: String,
    pub title: String,
    pub artist: String,
    pub album: String,
    pub duration_sec: i32,
    pub thumbnail_url: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ParsedPlaylistResult {
    pub playlist_id: String,
    pub title: String,
    pub author: String,
    pub track_count: usize,
    pub tracks: Vec<ParsedPlaylistTrack>,
    pub continuation_token: Option<String>,
}

pub struct PlaylistParser;

impl PlaylistParser {
    /// High-throughput zero-copy parser for YouTube Music and YouTube Web playlist JSON payloads
    pub fn parse_youtube_playlist(raw_json: &str) -> Result<ParsedPlaylistResult, String> {
        let root: Value = serde_json::from_str(raw_json)
            .map_err(|e| format!("Failed to parse JSON AST: {}", e))?;

        let mut tracks = Vec::with_capacity(128);
        let mut continuation_token = None;
        let mut playlist_title = String::from("Imported Playlist");
        let mut playlist_author = String::from("YouTube Music");

        // Try extracting playlist header
        if let Some(header) = root.pointer("/header/musicDetailHeaderRenderer") {
            if let Some(t) = header
                .pointer("/title/runs/0/text")
                .and_then(|v| v.as_str())
            {
                playlist_title = t.to_string();
            }
            if let Some(a) = header
                .pointer("/subtitle/runs/0/text")
                .and_then(|v| v.as_str())
            {
                playlist_author = a.to_string();
            }
        }

        // Locate music shelf items or continuation items
        let _contents_path = if root.pointer("/continuationContents").is_some() {
            "/continuationContents/musicPlaylistShelfContinuation"
        } else {
            "/contents/singleColumnBrowseResultsRenderer/tabs/0/tabRenderer/content/sectionListRenderer/contents/0/musicResponsiveListItemRenderer"
        };

        // Fallback traverse all nodes looking for musicResponsiveListItemRenderer
        Self::traverse_and_collect_tracks(&root, &mut tracks, &mut continuation_token);

        Ok(ParsedPlaylistResult {
            playlist_id: String::new(),
            title: playlist_title,
            author: playlist_author,
            track_count: tracks.len(),
            tracks,
            continuation_token,
        })
    }

    fn traverse_and_collect_tracks(
        node: &Value,
        tracks: &mut Vec<ParsedPlaylistTrack>,
        continuation_token: &mut Option<String>,
    ) {
        match node {
            Value::Object(map) => {
                if let Some(item) = map.get("musicResponsiveListItemRenderer") {
                    if let Some(track) = Self::extract_track_from_renderer(item) {
                        tracks.push(track);
                    }
                } else if let Some(item) = map.get("playlistVideoRenderer") {
                    if let Some(track) = Self::extract_track_from_video_renderer(item) {
                        tracks.push(track);
                    }
                } else if let Some(continuation) = map.get("nextContinuationData") {
                    if let Some(token) = continuation.get("continuation").and_then(|v| v.as_str()) {
                        *continuation_token = Some(token.to_string());
                    }
                } else {
                    for v in map.values() {
                        Self::traverse_and_collect_tracks(v, tracks, continuation_token);
                    }
                }
            }
            Value::Array(list) => {
                for item in list {
                    Self::traverse_and_collect_tracks(item, tracks, continuation_token);
                }
            }
            _ => {}
        }
    }

    fn extract_track_from_renderer(renderer: &Value) -> Option<ParsedPlaylistTrack> {
        let video_id = renderer
            .pointer("/playlistItemData/videoId")
            .or_else(|| renderer.pointer("/flexColumns/0/musicResponsiveListItemFlexColumnRenderer/text/runs/0/navigationEndpoint/watchEndpoint/videoId"))
            .and_then(|v| v.as_str())?
            .to_string();

        if video_id.is_empty() {
            return None;
        }

        let title = renderer
            .pointer("/flexColumns/0/musicResponsiveListItemFlexColumnRenderer/text/runs/0/text")
            .and_then(|v| v.as_str())
            .unwrap_or("Unknown Title")
            .to_string();

        let mut artist = String::from("Unknown Artist");
        let mut album = String::from("Single");

        if let Some(runs) = renderer
            .pointer("/flexColumns/1/musicResponsiveListItemFlexColumnRenderer/text/runs")
            .and_then(|v| v.as_array())
        {
            if let Some(first_run) = runs
                .first()
                .and_then(|r| r.get("text"))
                .and_then(|t| t.as_str())
            {
                artist = first_run.to_string();
            }
            if runs.len() >= 3 {
                if let Some(album_run) = runs
                    .get(2)
                    .and_then(|r| r.get("text"))
                    .and_then(|t| t.as_str())
                {
                    album = album_run.to_string();
                }
            }
        }

        let mut duration_sec = 0;
        if let Some(fixed_cols) = renderer
            .pointer("/fixedColumns/0/musicResponsiveListItemFixedColumnRenderer/text/runs/0/text")
            .and_then(|v| v.as_str())
        {
            duration_sec = Self::parse_duration_string(fixed_cols);
        }

        let thumbnail_url = renderer
            .pointer("/thumbnail/musicThumbnailRenderer/thumbnail/thumbnails/0/url")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string();

        Some(ParsedPlaylistTrack {
            video_id,
            title,
            artist,
            album,
            duration_sec,
            thumbnail_url,
        })
    }

    fn extract_track_from_video_renderer(renderer: &Value) -> Option<ParsedPlaylistTrack> {
        let video_id = renderer.get("videoId")?.as_str()?.to_string();
        let title = renderer
            .pointer("/title/runs/0/text")
            .and_then(|v| v.as_str())
            .unwrap_or("Unknown Title")
            .to_string();

        let artist = renderer
            .pointer("/shortBylineText/runs/0/text")
            .and_then(|v| v.as_str())
            .unwrap_or("Unknown Artist")
            .to_string();

        let duration_sec = renderer
            .get("lengthSeconds")
            .and_then(|v| v.as_str())
            .and_then(|s| s.parse::<i32>().ok())
            .unwrap_or(0);

        let thumbnail_url = renderer
            .pointer("/thumbnail/thumbnails/0/url")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string();

        Some(ParsedPlaylistTrack {
            video_id,
            title,
            artist,
            album: String::from("Streamify"),
            duration_sec,
            thumbnail_url,
        })
    }

    fn parse_duration_string(dur_str: &str) -> i32 {
        let parts: Vec<&str> = dur_str.split(':').collect();
        match parts.len() {
            2 => {
                let min = parts[0].trim().parse::<i32>().unwrap_or(0);
                let sec = parts[1].trim().parse::<i32>().unwrap_or(0);
                min * 60 + sec
            }
            3 => {
                let hr = parts[0].trim().parse::<i32>().unwrap_or(0);
                let min = parts[1].trim().parse::<i32>().unwrap_or(0);
                let sec = parts[2].trim().parse::<i32>().unwrap_or(0);
                hr * 3600 + min * 60 + sec
            }
            _ => 0,
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// PHASE 2 (feat/phase2-rust-crdt-blend-voting) — Scraped playlist →
// collaborative playlist import flow (directive C / BEHIND.md gap #35).
//
// Turns a freshly scraped YouTube (Music) playlist into the seed op set
// of a `CollabPlaylistState`: every parsed track becomes a canonical
// fractional-index Add (identity-parity CAD ids via the repository
// hasher) and the scraped playlist title seeds the LWW rename register —
// the collaborative session then starts fully synced from the importer's
// op log alone.
// ═══════════════════════════════════════════════════════════════════════

use crate::consensus::CollabPlaylistState;

/// Imports a scraped playlist into a collaborative replica (Admin path:
/// the importing author mints every Add). Returns the number of items
/// actually added (malformed rows — empty title/artist — are skipped).
pub fn import_parsed_playlist(
    engine: &mut CollabPlaylistState,
    parsed: &ParsedPlaylistResult,
) -> usize {
    // Title register: scraped title wins over the default.
    if !parsed.title.trim().is_empty() {
        let _ = engine.build_rename(parsed.title.trim());
    }

    let n = parsed.tracks.len().max(1);
    let mut added = 0usize;
    for (i, track) in parsed.tracks.iter().enumerate() {
        if track.title.trim().is_empty() || track.artist.trim().is_empty() {
            continue; // hostile/empty scrape rows never enter the CRDT
        }
        let cad = crate::repository::generate_cad_id_u64(
            track.title.trim(),
            track.artist.trim(),
            track.duration_sec.max(0) as u32,
        );
        // Fractional spread over (0, 1): preserves scrape order exactly,
        // leaves gaps for concurrent collaborative inserts.
        let frac = (i + 1) as f64 / (n + 1) as f64;
        if engine.build_add(cad, frac).is_some() {
            added += 1;
        }
    }
    added
}

#[cfg(test)]
mod collab_import_tests {
    use super::*;
    use crate::consensus::{CollabPlaylistState, PlaylistApplyResult};

    #[test]
    fn scraped_playlist_seeds_collab_session() {
        let parsed = ParsedPlaylistResult {
            playlist_id: "PL123".into(),
            title: "  Yacht Rock Essentials  ".into(),
            author: "scraper".into(),
            track_count: 3,
            tracks: vec![
                ParsedPlaylistTrack {
                    video_id: "v1".into(),
                    title: "Sailing".into(),
                    artist: "Christopher Cross".into(),
                    album: "s/t".into(),
                    duration_sec: 254,
                    thumbnail_url: String::new(),
                },
                ParsedPlaylistTrack {
                    video_id: "v2".into(),
                    title: "Africa".into(),
                    artist: "Toto".into(),
                    album: "Toto IV".into(),
                    duration_sec: 295,
                    thumbnail_url: String::new(),
                },
                ParsedPlaylistTrack {
                    video_id: "v3".into(),
                    title: "".into(), // hostile row: skipped
                    artist: "Ghost".into(),
                    album: String::new(),
                    duration_sec: 100,
                    thumbnail_url: String::new(),
                },
            ],
            continuation_token: None,
        };

        let mut host = CollabPlaylistState::new(1);
        let added = import_parsed_playlist(&mut host, &parsed);
        assert_eq!(added, 2);
        assert_eq!(host.title(), "Yacht Rock Essentials");

        let rows = host.snapshot_items();
        assert_eq!(rows.len(), 2);
        // Scrape order preserved via fractional spread.
        assert!(rows[0].frac < rows[1].frac);

        // A second replica syncs from the importer's op log alone and
        // lands on the identical state — the collaborative session starts
        // fully converged.
        let mut guest = CollabPlaylistState::new(2);
        for op in host.export_delta_since_clock(&std::collections::HashMap::new()) {
            assert_eq!(guest.apply_op(&op), PlaylistApplyResult::Applied);
        }
        assert_eq!(guest.snapshot_items(), rows);
        assert_eq!(guest.title(), host.title());

        // Guests keep editing: fractional gaps absorb the insert cleanly.
        let _ = guest.build_add(999, (rows[0].frac + rows[1].frac) / 2.0);
        assert_eq!(guest.snapshot_items().len(), 3);
    }
}
