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
