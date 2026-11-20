//! jam_track_codec.rs — the Kotlin `JamTrackCodec` port
//! (`app/src/main/java/com/streamify/app/jam/JamTrackCodec.kt`), previously
//! Kotlin-only (org.json). WS-W engine facade breadth: the codec is the
//! session wire format for jam queue entries — one source of truth in the
//! engine, exposed to JS through the wasm facade; the TS mirror
//! (`front-end/web/src/lib/jamEngine.ts`) calls THIS via the bridge rather
//! than re-implementing the field set.
//!
//! Wire shape (camelCase, mirroring the Kotlin JSONObject keys exactly):
//! `id` (i32), `title`, `artist`, `album` (defaults to "Streamify Jam"),
//! `filepath`, `coverArtPath` (always present, "" when none), `durationSec`,
//! optional `ytmVideoId` / `isrc` (blank → absent), optional `addedBy`
//! (blank → absent). `bpm`/`key` are READ on decode (the Kotlin Track
//! carries them) but never WRITTEN — the Kotlin encoder omits them too.

use serde::{Deserialize, Serialize};

/// Kotlin `String.isBlank()` parity: only-whitespace (the codec's blank
/// rules are Kotlin semantics — blank ≠ empty).
fn is_blank(s: &str) -> bool {
    s.trim().is_empty()
}

/// Java `String.hashCode` — the Kotlin `title.hashCode()` parity the id
/// fallback needs: h = 31·h + c over UTF-16 code units, wrapping i32.
/// The TS mirror implements the identical loop.
pub fn java_string_hash(s: &str) -> i32 {
    let mut h: i32 = 0;
    for cu in s.encode_utf16() {
        h = h.wrapping_mul(31).wrapping_add(i32::from(cu));
    }
    h
}

/// One jam queue entry (the Kotlin `Track` as carried on the session wire).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct JamTrack {
    pub id: i32,
    pub title: String,
    pub artist: String,
    #[serde(default)]
    pub album: String,
    #[serde(default)]
    pub filepath: String,
    /// "" is the wire sentinel for "none" (the Kotlin codec always puts the
    /// field; blank decodes to the null Track field).
    #[serde(default)]
    pub cover_art_path: String,
    #[serde(default)]
    pub duration_sec: i32,
    #[serde(default, skip_serializing)]
    pub bpm: f32,
    /// Read on decode (the Kotlin Track carries it), never written — the
    /// Kotlin encoder omits it (serde `skip_serializing`).
    #[serde(default, skip_serializing)]
    pub key: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub ytm_video_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub isrc: Option<String>,
    /// Encode-only convenience (the Kotlin `addedBy` put parameter); decode
    /// ignores it, exactly like the Kotlin from_json.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub added_by: Option<String>,
}

/// `jamTrackToJson`: the queue-entry JSON for the session wire.
/// Optional identity fields (ytmVideoId / isrc) and a blank `addedBy` are
/// omitted, mirroring the Kotlin `?.let { put(...) }` / `if (isNotBlank)`.
pub fn jam_track_to_json(track: &JamTrack) -> String {
    let mut t = track.clone();
    if t.added_by.as_deref().map(is_blank).unwrap_or(true) {
        t.added_by = None;
    }
    if t.ytm_video_id.as_deref().map(is_blank).unwrap_or(true) {
        t.ytm_video_id = None;
    }
    if t.isrc.as_deref().map(is_blank).unwrap_or(true) {
        t.isrc = None;
    }
    serde_json::to_string(&t).unwrap_or_else(|_| "{}".to_string())
}

/// `jamTrackFromJson`: decode one queue entry. Returns `None` when the title
/// is missing/blank (a titleless row must never enter the jam queue) — the
/// Kotlin rule. A missing `id` falls back to `-(title.hashCode())` (Java
/// hash parity); a blank `album` defaults to "Streamify Jam".
pub fn jam_track_from_json(raw: &str) -> Option<JamTrack> {
    let v: serde_json::Value = serde_json::from_str(raw).ok()?;
    let title = v.get("title").and_then(|t| t.as_str()).unwrap_or("");
    if title.trim().is_empty() {
        return None;
    }
    let id = match v.get("id").and_then(|i| i.as_i64()) {
        Some(i) => i as i32,
        None => java_string_hash(title).wrapping_neg(),
    };
    let str_or = |key: &str| -> String {
        v.get(key).and_then(|x| x.as_str()).unwrap_or("").to_string()
    };
    let blank_to_none = |s: String| if is_blank(&s) { None } else { Some(s) };
    Some(JamTrack {
        id,
        title: title.to_string(),
        artist: str_or("artist"),
        album: {
            let a = str_or("album");
            if is_blank(&a) {
                "Streamify Jam".to_string()
            } else {
                a
            }
        },
        filepath: str_or("filepath"),
        cover_art_path: str_or("coverArtPath"),
        duration_sec: v.get("durationSec").and_then(|d| d.as_i64()).unwrap_or(0) as i32,
        bpm: v.get("bpm").and_then(|b| b.as_f64()).unwrap_or(0.0) as f32,
        key: str_or("key"),
        ytm_video_id: blank_to_none(str_or("ytmVideoId")),
        isrc: blank_to_none(str_or("isrc")),
        added_by: None, // decode ignores addedBy (the Kotlin rule)
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The canonical Java-hash pins: "" → 0, "a" → 97, "hello" → 99162322.
    #[test]
    fn java_hash_parity() {
        assert_eq!(java_string_hash(""), 0);
        assert_eq!(java_string_hash("a"), 97);
        assert_eq!(java_string_hash("hello"), 99162322);
    }

    #[test]
    fn round_trip_preserves_the_wire_shape() {
        let track = JamTrack {
            id: 42,
            title: "Shape of You".into(),
            artist: "Ed Sheeran".into(),
            album: "÷ (Divide)".into(),
            filepath: "ytm://dQw4w9WgXcQ".into(),
            cover_art_path: "https://i.ytimg.com/x.jpg".into(),
            duration_sec: 232,
            bpm: 96.0,
            key: "8B".into(),
            ytm_video_id: Some("dQw4w9WgXcQ".into()),
            isrc: None,
            added_by: Some("web:alice".into()),
        };
        let json = jam_track_to_json(&track);
        // Encode-only fields present on the wire; bpm/key absent (Kotlin
        // encoder omits them); isrc/blank addedBy omitted.
        assert!(json.contains("\"addedBy\":\"web:alice\""));
        assert!(json.contains("\"ytmVideoId\":\"dQw4w9WgXcQ\""));
        assert!(!json.contains("\"isrc\""));
        assert!(!json.contains("\"bpm\""));
        assert!(!json.contains("\"key\":"));
        let back = jam_track_from_json(&json).expect("round-trip decodes");
        assert_eq!(back.title, track.title);
        assert_eq!(back.id, 42);
        assert_eq!(back.album, "÷ (Divide)");
        assert_eq!(back.ytm_video_id.as_deref(), Some("dQw4w9WgXcQ"));
        assert_eq!(back.isrc, None);
    }

    #[test]
    fn blank_title_rejects_and_id_falls_back_to_java_hash() {
        // A titleless row must never enter the jam queue (the Kotlin rule).
        assert!(jam_track_from_json("{\"title\":\"  \"}").is_none());
        assert!(jam_track_from_json("{}").is_none());
        // Missing id: -(title.hashCode()) — "hello" → -(99162322).
        let t = jam_track_from_json("{\"title\":\"hello\",\"artist\":\"a\"}")
            .expect("decodes");
        assert_eq!(t.id, -99162322);
        // Blank album defaults; blank optionals become None.
        assert_eq!(t.album, "Streamify Jam");
        assert_eq!(t.ytm_video_id, None);
        assert_eq!(t.isrc, None);
    }
}
