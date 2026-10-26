use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BackupRecord {
    pub title: String,
    pub artist: String,
    pub album: String,
    pub duration_sec: i32,
    #[serde(default)]
    pub spotify_id: String,
    #[serde(default)]
    pub is_liked: bool,
}

pub struct BackupArchiveEngine;

impl BackupArchiveEngine {
    /// Parses Spotify Exportify / Soundiiz CSV dumps at gigabytes per second
    pub fn parse_csv_dump(csv_content: &str) -> Vec<BackupRecord> {
        let mut records = Vec::with_capacity(256);
        let mut lines = csv_content.lines();

        let header = match lines.next() {
            Some(h) => h.to_lowercase(),
            None => return records,
        };

        // Determine column indices
        let cols: Vec<&str> = header
            .split(',')
            .map(|c| c.trim().trim_matches('"'))
            .collect();
        let mut title_idx = 1usize;
        let mut artist_idx = 2usize;
        let mut album_idx = 3usize;
        let mut dur_idx = 4usize;
        let mut spotify_id_idx = 0usize;

        for (i, &col) in cols.iter().enumerate() {
            match col {
                "track name" | "title" | "track" => title_idx = i,
                "artist name(s)" | "artist name" | "artist" | "artists" => artist_idx = i,
                "album name" | "album" => album_idx = i,
                "duration (ms)" | "duration" | "duration_ms" => dur_idx = i,
                "track uri" | "spotify uri" | "id" | "spotify id" => spotify_id_idx = i,
                _ => {}
            }
        }

        for line in lines {
            if line.trim().is_empty() {
                continue;
            }
            let fields = Self::parse_csv_line(line);
            if fields.len() > title_idx.max(artist_idx) {
                let title = fields.get(title_idx).unwrap_or(&"").to_string();
                let artist = fields.get(artist_idx).unwrap_or(&"").to_string();
                let album = fields.get(album_idx).unwrap_or(&"").to_string();
                let dur_raw = fields.get(dur_idx).unwrap_or(&"0");
                let spotify_raw = fields.get(spotify_id_idx).unwrap_or(&"").to_string();

                let duration_sec = if let Ok(ms) = dur_raw.parse::<i32>() {
                    if ms > 1000 {
                        ms / 1000
                    } else {
                        ms
                    }
                } else {
                    0
                };

                if !title.is_empty() && !artist.is_empty() {
                    records.push(BackupRecord {
                        title,
                        artist,
                        album,
                        duration_sec,
                        spotify_id: spotify_raw,
                        is_liked: false,
                    });
                }
            }
        }

        records
    }

    fn parse_csv_line(line: &str) -> Vec<&str> {
        let mut fields = Vec::with_capacity(8);
        let mut in_quotes = false;
        let mut start = 0usize;
        let bytes = line.as_bytes();

        for (i, &b) in bytes.iter().enumerate() {
            if b == b'"' {
                in_quotes = !in_quotes;
            } else if b == b',' && !in_quotes {
                let slice = line[start..i].trim().trim_matches('"');
                fields.push(slice);
                start = i + 1;
            }
        }

        if start < line.len() {
            let slice = line[start..].trim().trim_matches('"');
            fields.push(slice);
        }

        fields
    }
}
