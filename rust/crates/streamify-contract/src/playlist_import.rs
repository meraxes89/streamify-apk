//! Collaborative playlist import glue (Phase 2, directive C / BEHIND.md
//! gap #35) — composition-layer code by design (Discussion #120): it
//! spans the jam domain (the CRDT replica), the resolver domain (the
//! scraped playlist) and the storage domain (the CAD id hasher), so it
//! lives in streamify-contract beside the engine that orchestrates all
//! three, keeping streamify-resolver free of any P2P dependency.

use streamify_jam::consensus::CollabPlaylistState;
use streamify_resolver::playlist_parser::ParsedPlaylistResult;

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
        let cad = streamify_storage::repository::generate_cad_id_u64(
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
    use streamify_resolver::playlist_parser::ParsedPlaylistTrack;
    use streamify_jam::consensus::PlaylistApplyResult;

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
