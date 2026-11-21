use streamify_core_rs::consensus::ConsensusEngine;
use streamify_core_rs::json::InnertubeParser;
use streamify_core_rs::lyrics::LyricCompiler;
use streamify_core_rs::ptp::PtpFilter;

#[test]
fn test_duration_parser() {
    assert_eq!(InnertubeParser::parse_duration_str("3:45"), 225);
    assert_eq!(InnertubeParser::parse_duration_str("1:02:15"), 3735);
    assert_eq!(InnertubeParser::parse_duration_str("45"), 45);
}

#[test]
fn test_json_innertube_candidates() {
    let mock_json = r#"{
        "contents": {
            "sectionListRenderer": {
                "contents": [{
                    "playlistPanelVideoRenderer": {
                        "videoId": "dQw4w9WgXcQ",
                        "title": { "runs": [{ "text": "Never Gonna Give You Up" }] },
                        "longBylineText": { "runs": [{ "text": "Rick Astley" }] },
                        "lengthText": { "simpleText": "3:32" },
                        "thumbnail": {
                            "thumbnails": [{ "url": "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg" }]
                        }
                    }
                }]
            }
        }
    }"#;

    let candidates = InnertubeParser::parse_candidates(mock_json);
    assert_eq!(candidates.len(), 1);
    assert_eq!(candidates[0].id, "dQw4w9WgXcQ");
    assert_eq!(candidates[0].title, "Never Gonna Give You Up");
    assert_eq!(candidates[0].artist, "Rick Astley");
    assert_eq!(candidates[0].duration_sec, 212);
}

#[test]
fn test_lyrics_precompiler() {
    let lrc = r#"
[00:12.34] We're no strangers to love
[00:16.89] You know the rules and so do I
[00:21.00] A full commitment's what I'm thinking of
"#;

    let compiled = LyricCompiler::compile_lrc(lrc);
    assert_eq!(compiled.entries.len(), 3);
    assert_eq!(compiled.entries[0].start_time_ms, 12340);
    assert_eq!(compiled.entries[1].start_time_ms, 16890);
    assert_eq!(compiled.entries[2].start_time_ms, 21000);

    // Active-line lookup in production goes through the compiled SLYR binary
    // (O(log N) binary search over raw memory).
    let slyr = LyricCompiler::compile_to_slyr(lrc);
    assert!(!slyr.is_empty());
    {
        // Before the first line: no active line.
        assert_eq!(
            LyricCompiler::find_active_positions(slyr.as_ptr(), slyr.len(), 5000),
            None
        );
        // Inside line 0 (12.34s - 16.89s).
        assert_eq!(
            LyricCompiler::find_active_positions(slyr.as_ptr(), slyr.len(), 14000),
            Some((0, 0))
        );
        // Inside line 1 (16.89s - 21.00s).
        assert_eq!(
            LyricCompiler::find_active_positions(slyr.as_ptr(), slyr.len(), 17000),
            Some((1, 0))
        );
    }
}

#[test]
fn test_proof_of_compute_and_byzantine() {
    let pcm = vec![0.1f32, -0.2f32, 0.5f32, -0.8f32, 0.9f32];
    let proof = ConsensusEngine::generate_proof_of_compute(&pcm, "streamify_test_nonce");
    assert!(!proof.is_empty());
    assert_eq!(proof.len(), 64); // SHA-256 hex string length

    // Test Byzantine invariants
    let vec1 = vec![0.1f32, 0.5f32, 0.8f32, 0.2f32];
    let vec2 = vec![0.1f32, 0.5f32, 0.82f32, 0.19f32];
    let is_valid =
        ConsensusEngine::verify_byzantine_consensus(-14.1, -14.2, "8B", "8B", &vec1, &vec2);
    assert!(is_valid);

    // Test mismatched key rejection
    let is_invalid_key =
        ConsensusEngine::verify_byzantine_consensus(-14.1, -14.2, "8B", "11A", &vec1, &vec2);
    assert!(!is_invalid_key);
}

#[test]
fn test_ptp_kalman_filter() {
    let mut filter = PtpFilter::new(0.5);

    // Simulate ping pong packet: t0=1000, t1=1050, t2=1060, t3=1110
    // Offset = ((1050 - 1000) + (1060 - 1110)) / 2 = (50 - 50) / 2 = 0
    // RTT = (1110 - 1000) - (1060 - 1050) = 110 - 10 = 100
    let offset = filter.process_timestamps(1000, 1050, 1060, 1110);
    assert_eq!(offset, 0);
    assert_eq!(filter.get_rtt_nanos(), 100);
}

#[test]
fn test_slyr_compilation_and_alignment() {
    let lrc = r#"
[00:10.00]<00:10.00>Hel<00:10.50>lo <00:11.00>world
[00:15.00]<00:15.00>Sing<00:15.80>ing <00:16.40>in <00:17.00>the <00:17.60>rain
"#;

    let slyr_bytes = LyricCompiler::compile_to_slyr(lrc);
    assert!(!slyr_bytes.is_empty());
    // Invariant: Total length must be 16-byte aligned
    assert_eq!(slyr_bytes.len() % 16, 0);

    // Invariant: Header magic must be 'SLYR'
    assert_eq!(&slyr_bytes[0..4], b"SLYR");

    // Test O(log N) binary search lookup from raw memory
    {
        let pos_10_2s =
            LyricCompiler::find_active_positions(slyr_bytes.as_ptr(), slyr_bytes.len(), 10200);
        assert!(pos_10_2s.is_some());
        let (line_idx, syl_idx) = pos_10_2s.unwrap();
        assert_eq!(line_idx, 0);
        assert_eq!(syl_idx, 0); // "Hel"

        let pos_15_9s =
            LyricCompiler::find_active_positions(slyr_bytes.as_ptr(), slyr_bytes.len(), 15900);
        assert!(pos_15_9s.is_some());
        let (line_idx_2, syl_idx_2) = pos_15_9s.unwrap();
        assert_eq!(line_idx_2, 1);
        assert_eq!(syl_idx_2, 1); // "ing " (15.80s - 16.40s)
    }
}

#[test]
fn test_lyric_drift_consensus() {
    assert!(ConsensusEngine::verify_lyric_drift_consensus(120, 130)); // 10ms delta <= 15ms
    assert!(!ConsensusEngine::verify_lyric_drift_consensus(120, 160)); // 40ms delta > 15ms (rejected)
}
