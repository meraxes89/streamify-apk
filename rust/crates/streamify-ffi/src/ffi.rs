// Clippy: the C ABI surface is a wall of `unsafe extern "C"` entry points
// that ALL follow the same documented house safety model (catch_unwind
// shields, sentinel returns, no panics across the boundary — see the
// module docs). Per-function `# Safety` boilerplate would add ~60 lines
// of copy-paste; the model is documented once, here and below.
#![allow(clippy::missing_safety_doc)]

use streamify_jam::consensus::ConsensusEngine;
use streamify_resolver::json::InnertubeParser;
use streamify_resolver::lyrics::LyricCompiler;
use streamify_jam::ptp::PtpFilter;
use streamify_resolver::resolver::StreamResolver;
use streamify_resolver::tagger::AudioMetadataEngine;
use std::ffi::{CStr, CString};
use std::os::raw::c_char;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::Mutex;

static GLOBAL_PTP_FILTER: Mutex<Option<PtpFilter>> = Mutex::new(None);

#[no_mangle]
pub unsafe extern "C" fn rust_free_string(s: *mut c_char) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        if !s.is_null() {
            drop(CString::from_raw(s));
        }
    }));
}

#[no_mangle]
pub unsafe extern "C" fn rust_parse_innertube_candidates(
    json_ptr: *const u8,
    json_len: usize,
) -> *mut c_char {
    let result = catch_unwind(AssertUnwindSafe(|| {
        if json_ptr.is_null() || json_len == 0 {
            return std::ptr::null_mut();
        }

        let slice = std::slice::from_raw_parts(json_ptr, json_len);
        let text = match std::str::from_utf8(slice) {
            Ok(t) => t,
            Err(_) => return std::ptr::null_mut(),
        };

        let candidates = InnertubeParser::parse_candidates(text);
        let json_output = match serde_json::to_string(&candidates) {
            Ok(j) => j,
            Err(_) => return std::ptr::null_mut(),
        };

        CString::new(json_output)
            .map(|c| c.into_raw())
            .unwrap_or(std::ptr::null_mut())
    }));

    result.unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub unsafe extern "C" fn rust_resolve_stream_url(video_id_ptr: *const c_char) -> *mut c_char {
    let result = catch_unwind(AssertUnwindSafe(|| {
        if video_id_ptr.is_null() {
            return std::ptr::null_mut();
        }

        let video_id = match CStr::from_ptr(video_id_ptr).to_str() {
            Ok(s) => s,
            Err(_) => return std::ptr::null_mut(),
        };

        match StreamResolver::resolve_stream(video_id) {
            Ok(streams) => {
                let json_str = serde_json::to_string(&streams).unwrap_or_default();
                CString::new(json_str)
                    .map(|c| c.into_raw())
                    .unwrap_or(std::ptr::null_mut())
            }
            Err(_) => std::ptr::null_mut(),
        }
    }));

    result.unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub unsafe extern "C" fn rust_write_audio_metadata(
    file_path_ptr: *const c_char,
    title_ptr: *const c_char,
    artist_ptr: *const c_char,
    album_ptr: *const c_char,
    cover_art_ptr: *const c_char,
    lyrics_ptr: *const c_char,
) -> i32 {
    let result = catch_unwind(AssertUnwindSafe(|| {
        if file_path_ptr.is_null() || title_ptr.is_null() || artist_ptr.is_null() {
            return -1;
        }

        let file_path = match CStr::from_ptr(file_path_ptr).to_str() {
            Ok(s) => s,
            Err(_) => return -2,
        };

        let title = match CStr::from_ptr(title_ptr).to_str() {
            Ok(s) => s,
            Err(_) => return -3,
        };

        let artist = match CStr::from_ptr(artist_ptr).to_str() {
            Ok(s) => s,
            Err(_) => return -4,
        };

        let album = if !album_ptr.is_null() {
            CStr::from_ptr(album_ptr).to_str().unwrap_or("Streamify")
        } else {
            "Streamify"
        };

        let cover_art = if !cover_art_ptr.is_null() {
            CStr::from_ptr(cover_art_ptr).to_str().ok()
        } else {
            None
        };

        let lyrics = if !lyrics_ptr.is_null() {
            CStr::from_ptr(lyrics_ptr).to_str().ok()
        } else {
            None
        };

        match AudioMetadataEngine::write_metadata(
            file_path, title, artist, album, cover_art, lyrics,
        ) {
            Ok(_) => 0,
            Err(_) => -5,
        }
    }));

    result.unwrap_or(-999)
}

#[no_mangle]
pub unsafe extern "C" fn rust_compile_lyrics(lrc_ptr: *const u8, lrc_len: usize) -> *mut c_char {
    let result = catch_unwind(AssertUnwindSafe(|| {
        if lrc_ptr.is_null() || lrc_len == 0 {
            return std::ptr::null_mut();
        }

        let slice = std::slice::from_raw_parts(lrc_ptr, lrc_len);
        let text = match std::str::from_utf8(slice) {
            Ok(t) => t,
            Err(_) => return std::ptr::null_mut(),
        };

        let compiled = LyricCompiler::compile_lrc(text);
        let json_output = match serde_json::to_string(&compiled) {
            Ok(j) => j,
            Err(_) => return std::ptr::null_mut(),
        };

        CString::new(json_output)
            .map(|c| c.into_raw())
            .unwrap_or(std::ptr::null_mut())
    }));

    result.unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub unsafe extern "C" fn rust_generate_proof_of_compute(
    pcm_ptr: *const f32,
    pcm_len: usize,
    nonce_ptr: *const c_char,
) -> *mut c_char {
    let result = catch_unwind(AssertUnwindSafe(|| {
        if pcm_ptr.is_null() || pcm_len == 0 || nonce_ptr.is_null() {
            return std::ptr::null_mut();
        }

        let nonce = match CStr::from_ptr(nonce_ptr).to_str() {
            Ok(s) => s,
            Err(_) => return std::ptr::null_mut(),
        };

        let pcm_slice = std::slice::from_raw_parts(pcm_ptr, pcm_len);
        let proof = ConsensusEngine::generate_proof_of_compute(pcm_slice, nonce);

        CString::new(proof)
            .map(|c| c.into_raw())
            .unwrap_or(std::ptr::null_mut())
    }));

    result.unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub unsafe extern "C" fn rust_process_ptp_timestamps(t0: i64, t1: i64, t2: i64, t3: i64) -> i64 {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let mut guard = match GLOBAL_PTP_FILTER.lock() {
            Ok(g) => g,
            Err(poisoned) => poisoned.into_inner(),
        };
        let filter = guard.get_or_insert_with(|| PtpFilter::new(0.25));
        filter.process_timestamps(t0, t1, t2, t3)
    }));

    result.unwrap_or(0)
}

#[no_mangle]
pub unsafe extern "C" fn rust_compile_to_slyr(
    lrc_ptr: *const u8,
    lrc_len: usize,
    out_len: *mut usize,
) -> *mut u8 {
    let result = catch_unwind(AssertUnwindSafe(|| {
        if lrc_ptr.is_null() || lrc_len == 0 || out_len.is_null() {
            return std::ptr::null_mut();
        }

        let slice = std::slice::from_raw_parts(lrc_ptr, lrc_len);
        let text = match std::str::from_utf8(slice) {
            Ok(t) => t,
            Err(_) => return std::ptr::null_mut(),
        };

        let mut slyr_bytes = LyricCompiler::compile_to_slyr(text);
        *out_len = slyr_bytes.len();
        let ptr = slyr_bytes.as_mut_ptr();
        std::mem::forget(slyr_bytes);
        ptr
    }));

    result.unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub unsafe extern "C" fn rust_free_slyr_buffer(buf: *mut u8, len: usize) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        if !buf.is_null() && len > 0 {
            drop(Vec::from_raw_parts(buf, len, len));
        }
    }));
}

#[no_mangle]
pub unsafe extern "C" fn rust_find_active_slyr_positions(
    slyr_ptr: *const u8,
    slyr_len: usize,
    position_ms: u32,
    out_line_idx: *mut u32,
    out_syl_idx: *mut u32,
) -> i32 {
    let result = catch_unwind(AssertUnwindSafe(|| {
        if slyr_ptr.is_null() || slyr_len == 0 || out_line_idx.is_null() || out_syl_idx.is_null() {
            return -1;
        }

        match LyricCompiler::find_active_positions(slyr_ptr, slyr_len, position_ms) {
            Some((line_idx, syl_idx)) => {
                *out_line_idx = line_idx as u32;
                *out_syl_idx = syl_idx as u32;
                0
            }
            None => -2,
        }
    }));

    result.unwrap_or(-999)
}

use streamify_resolver::downloader::StreamDownloader;
use streamify_audio::dsp::{SpectrumVisualizer, StudioEqualizer};
use streamify_resolver::playlist_parser::PlaylistParser;
use streamify_resolver::search::{FuzzySearchEngine, SearchCandidate};

static GLOBAL_STUDIO_EQ: Mutex<Option<StudioEqualizer>> = Mutex::new(None);

#[no_mangle]
pub unsafe extern "C" fn rust_fuzzy_rank_candidates(
    query_ptr: *const c_char,
    candidates_json_ptr: *const c_char,
) -> *mut c_char {
    let result = catch_unwind(AssertUnwindSafe(|| {
        if query_ptr.is_null() || candidates_json_ptr.is_null() {
            return std::ptr::null_mut();
        }

        let query = match CStr::from_ptr(query_ptr).to_str() {
            Ok(s) => s,
            Err(_) => return std::ptr::null_mut(),
        };

        let candidates_json = match CStr::from_ptr(candidates_json_ptr).to_str() {
            Ok(s) => s,
            Err(_) => return std::ptr::null_mut(),
        };

        let mut candidates: Vec<SearchCandidate> = match serde_json::from_str(candidates_json) {
            Ok(c) => c,
            Err(_) => return std::ptr::null_mut(),
        };

        FuzzySearchEngine::rank_candidates(query, &mut candidates);

        let out_json = match serde_json::to_string(&candidates) {
            Ok(j) => j,
            Err(_) => return std::ptr::null_mut(),
        };

        CString::new(out_json)
            .map(|c| c.into_raw())
            .unwrap_or(std::ptr::null_mut())
    }));

    result.unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub unsafe extern "C" fn rust_calculate_string_similarity(
    s1_ptr: *const c_char,
    s2_ptr: *const c_char,
) -> f32 {
    let result = catch_unwind(AssertUnwindSafe(|| {
        if s1_ptr.is_null() || s2_ptr.is_null() {
            return 0.0;
        }

        let s1 = match CStr::from_ptr(s1_ptr).to_str() {
            Ok(s) => s,
            Err(_) => return 0.0,
        };

        let s2 = match CStr::from_ptr(s2_ptr).to_str() {
            Ok(s) => s,
            Err(_) => return 0.0,
        };

        FuzzySearchEngine::calculate_similarity(s1, s2)
    }));

    result.unwrap_or(0.0)
}

#[no_mangle]
pub unsafe extern "C" fn rust_parse_youtube_playlist(
    json_ptr: *const u8,
    json_len: usize,
) -> *mut c_char {
    let result = catch_unwind(AssertUnwindSafe(|| {
        if json_ptr.is_null() || json_len == 0 {
            return std::ptr::null_mut();
        }

        let slice = std::slice::from_raw_parts(json_ptr, json_len);
        let raw_json = match std::str::from_utf8(slice) {
            Ok(s) => s,
            Err(_) => return std::ptr::null_mut(),
        };

        match PlaylistParser::parse_youtube_playlist(raw_json) {
            Ok(res) => {
                let out_json = serde_json::to_string(&res).unwrap_or_default();
                CString::new(out_json)
                    .map(|c| c.into_raw())
                    .unwrap_or(std::ptr::null_mut())
            }
            Err(_) => std::ptr::null_mut(),
        }
    }));

    result.unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub unsafe extern "C" fn rust_compute_fft_spectrum(
    pcm_ptr: *const f32,
    pcm_len: usize,
    bar_count: usize,
    out_bars_ptr: *mut f32,
) -> i32 {
    let result = catch_unwind(AssertUnwindSafe(|| {
        if pcm_ptr.is_null() || pcm_len == 0 || out_bars_ptr.is_null() || bar_count == 0 {
            return -1;
        }

        let pcm_slice = std::slice::from_raw_parts(pcm_ptr, pcm_len);
        let bars = SpectrumVisualizer::compute_spectrum_bars(pcm_slice, bar_count);

        let out_slice = std::slice::from_raw_parts_mut(out_bars_ptr, bar_count);
        out_slice.copy_from_slice(&bars);
        0
    }));

    result.unwrap_or(-999)
}

#[no_mangle]
pub unsafe extern "C" fn rust_process_equalizer_frame(
    pcm_ptr: *mut f32,
    pcm_len: usize,
    channels: usize,
    gains_ptr: *const f32,
) -> i32 {
    let result = catch_unwind(AssertUnwindSafe(|| {
        if pcm_ptr.is_null() || pcm_len == 0 || channels == 0 {
            return -1;
        }

        let mut guard = match GLOBAL_STUDIO_EQ.lock() {
            Ok(g) => g,
            Err(p) => p.into_inner(),
        };
        let eq = guard.get_or_insert_with(|| StudioEqualizer::new(44100.0));

        if !gains_ptr.is_null() {
            let gains = std::slice::from_raw_parts(gains_ptr, 10);
            for (idx, &gain) in gains.iter().enumerate() {
                eq.set_band_gain(idx, gain as f64);
            }
        }

        let pcm_slice = std::slice::from_raw_parts_mut(pcm_ptr, pcm_len);
        eq.process_buffer_interleaved(pcm_slice, channels);
        0
    }));

    result.unwrap_or(-999)
}

#[no_mangle]
pub unsafe extern "C" fn rust_download_stream_direct(
    stream_url_ptr: *const c_char,
    dest_path_ptr: *const c_char,
) -> *mut c_char {
    let result = catch_unwind(AssertUnwindSafe(|| {
        if stream_url_ptr.is_null() || dest_path_ptr.is_null() {
            return std::ptr::null_mut();
        }

        let stream_url = match CStr::from_ptr(stream_url_ptr).to_str() {
            Ok(s) => s,
            Err(_) => return std::ptr::null_mut(),
        };

        let dest_path = match CStr::from_ptr(dest_path_ptr).to_str() {
            Ok(s) => s,
            Err(_) => return std::ptr::null_mut(),
        };

        match StreamDownloader::download_stream_to_file(stream_url, dest_path, 65536, |_| {}) {
            Ok(sha256_hex) => CString::new(sha256_hex)
                .map(|c| c.into_raw())
                .unwrap_or(std::ptr::null_mut()),
            Err(_) => std::ptr::null_mut(),
        }
    }));

    result.unwrap_or(std::ptr::null_mut())
}

use streamify_storage::backup::BackupArchiveEngine;
use streamify_audio::crossfade::CrossfadeDspEngine;
use streamify_storage::crypto::VaultCryptoEngine;
use streamify_resolver::radio_scorer::{RadioAntiDriftEngine, ScoredCandidate};

#[no_mangle]
pub unsafe extern "C" fn rust_score_and_rank_radio_candidates(
    candidates_json_ptr: *const c_char,
    seed_bpm: f32,
    seed_key_ptr: *const c_char,
    seed_dur_sec: i32,
    seed_sig_ptr: *const c_char,
    queue_json_ptr: *const c_char,
) -> *mut c_char {
    let result = catch_unwind(AssertUnwindSafe(|| {
        if candidates_json_ptr.is_null() {
            return std::ptr::null_mut();
        }

        let candidates_str = match CStr::from_ptr(candidates_json_ptr).to_str() {
            Ok(s) => s,
            Err(_) => return std::ptr::null_mut(),
        };

        let seed_key = if !seed_key_ptr.is_null() {
            CStr::from_ptr(seed_key_ptr).to_str().unwrap_or("")
        } else {
            ""
        };

        let seed_sig = if !seed_sig_ptr.is_null() {
            CStr::from_ptr(seed_sig_ptr).to_str().unwrap_or("")
        } else {
            ""
        };

        let queue_str = if !queue_json_ptr.is_null() {
            CStr::from_ptr(queue_json_ptr).to_str().unwrap_or("[]")
        } else {
            "[]"
        };

        let candidates: Vec<ScoredCandidate> = match serde_json::from_str(candidates_str) {
            Ok(c) => c,
            Err(_) => return std::ptr::null_mut(),
        };

        let queue: Vec<ScoredCandidate> = serde_json::from_str(queue_str).unwrap_or_default();

        let ranked = RadioAntiDriftEngine::filter_and_rank_candidates(
            &candidates,
            seed_bpm,
            seed_key,
            seed_dur_sec,
            seed_sig,
            &queue,
        );

        let out_json = match serde_json::to_string(&ranked) {
            Ok(j) => j,
            Err(_) => return std::ptr::null_mut(),
        };

        CString::new(out_json)
            .map(|c| c.into_raw())
            .unwrap_or(std::ptr::null_mut())
    }));

    result.unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub unsafe extern "C" fn rust_process_crossfade_pcm(
    out_ptr: *const f32,
    in_ptr: *const f32,
    mixed_ptr: *mut f32,
    len: usize,
    progress: f32,
) -> i32 {
    let result = catch_unwind(AssertUnwindSafe(|| {
        if out_ptr.is_null() || in_ptr.is_null() || mixed_ptr.is_null() || len == 0 {
            return -1;
        }

        let out_slice = std::slice::from_raw_parts(out_ptr, len);
        let in_slice = std::slice::from_raw_parts(in_ptr, len);
        let mixed_slice = std::slice::from_raw_parts_mut(mixed_ptr, len);

        CrossfadeDspEngine::process_equal_power_crossfade(
            out_slice,
            in_slice,
            mixed_slice,
            progress,
        );
        0
    }));

    result.unwrap_or(-999)
}

#[no_mangle]
pub unsafe extern "C" fn rust_encrypt_vault_file(
    src_ptr: *const c_char,
    dest_ptr: *const c_char,
    key_ptr: *const u8,
    key_len: usize,
) -> i32 {
    let result = catch_unwind(AssertUnwindSafe(|| {
        if src_ptr.is_null() || dest_ptr.is_null() || key_ptr.is_null() || key_len == 0 {
            return -1;
        }

        let src = match CStr::from_ptr(src_ptr).to_str() {
            Ok(s) => s,
            Err(_) => return -2,
        };

        let dest = match CStr::from_ptr(dest_ptr).to_str() {
            Ok(s) => s,
            Err(_) => return -3,
        };

        let key = std::slice::from_raw_parts(key_ptr, key_len);

        match VaultCryptoEngine::encrypt_file_in_place(src, dest, key) {
            Ok(_) => 0,
            Err(_) => -4,
        }
    }));

    result.unwrap_or(-999)
}

#[no_mangle]
pub unsafe extern "C" fn rust_decrypt_vault_file(
    src_ptr: *const c_char,
    dest_ptr: *const c_char,
    key_ptr: *const u8,
    key_len: usize,
) -> i32 {
    let result = catch_unwind(AssertUnwindSafe(|| {
        if src_ptr.is_null() || dest_ptr.is_null() || key_ptr.is_null() || key_len == 0 {
            return -1;
        }

        let src = match CStr::from_ptr(src_ptr).to_str() {
            Ok(s) => s,
            Err(_) => return -2,
        };

        let dest = match CStr::from_ptr(dest_ptr).to_str() {
            Ok(s) => s,
            Err(_) => return -3,
        };

        let key = std::slice::from_raw_parts(key_ptr, key_len);

        match VaultCryptoEngine::decrypt_file_to_file(src, dest, key) {
            Ok(_) => 0,
            Err(_) => -4,
        }
    }));

    result.unwrap_or(-999)
}

#[no_mangle]
pub unsafe extern "C" fn rust_parse_backup_csv(csv_ptr: *const c_char) -> *mut c_char {
    let result = catch_unwind(AssertUnwindSafe(|| {
        if csv_ptr.is_null() {
            return std::ptr::null_mut();
        }

        let csv = match CStr::from_ptr(csv_ptr).to_str() {
            Ok(s) => s,
            Err(_) => return std::ptr::null_mut(),
        };

        let records = BackupArchiveEngine::parse_csv_dump(csv);
        let out_json = serde_json::to_string(&records).unwrap_or_default();

        CString::new(out_json)
            .map(|c| c.into_raw())
            .unwrap_or(std::ptr::null_mut())
    }));

    result.unwrap_or(std::ptr::null_mut())
}

use streamify_resolver::aligner::LyricAlignerEngine;

#[no_mangle]
pub unsafe extern "C" fn rust_align_and_compile_lyrics(
    raw_lyrics_ptr: *const c_char,
    duration_ms: u32,
    energy_ptr: *const f32,
    energy_len: usize,
) -> *mut c_char {
    let result = catch_unwind(AssertUnwindSafe(|| {
        if raw_lyrics_ptr.is_null() {
            return std::ptr::null_mut();
        }

        let raw_lyrics = match CStr::from_ptr(raw_lyrics_ptr).to_str() {
            Ok(s) => s,
            Err(_) => return std::ptr::null_mut(),
        };

        let energy_slice = if !energy_ptr.is_null() && energy_len > 0 {
            Some(std::slice::from_raw_parts(energy_ptr, energy_len))
        } else {
            None
        };

        let aligned_lines =
            LyricAlignerEngine::align_unsynchronized_lyrics(raw_lyrics, duration_ms, energy_slice);
        let out_json = match serde_json::to_string(&aligned_lines) {
            Ok(j) => j,
            Err(_) => return std::ptr::null_mut(),
        };

        CString::new(out_json)
            .map(|c| c.into_raw())
            .unwrap_or(std::ptr::null_mut())
    }));

    result.unwrap_or(std::ptr::null_mut())
}

use streamify_resolver::neuro_queue::{BrainState, NeuroCandidate, NeuroQueueEngine};

#[no_mangle]
pub unsafe extern "C" fn rust_generate_neuro_queue(
    seed_json_ptr: *const c_char,
    candidates_json_ptr: *const c_char,
    brain_state_u8: u8,
    now_sec: u64,
    hour_of_day: u32,
    target_count: usize,
) -> *mut c_char {
    let result = catch_unwind(AssertUnwindSafe(|| {
        if seed_json_ptr.is_null() || candidates_json_ptr.is_null() {
            return std::ptr::null_mut();
        }

        let seed_str = match CStr::from_ptr(seed_json_ptr).to_str() {
            Ok(s) => s,
            Err(_) => return std::ptr::null_mut(),
        };

        let candidates_str = match CStr::from_ptr(candidates_json_ptr).to_str() {
            Ok(s) => s,
            Err(_) => return std::ptr::null_mut(),
        };

        let seed: NeuroCandidate = match serde_json::from_str(seed_str) {
            Ok(s) => s,
            Err(_) => return std::ptr::null_mut(),
        };

        let candidates: Vec<NeuroCandidate> = match serde_json::from_str(candidates_str) {
            Ok(c) => c,
            Err(_) => return std::ptr::null_mut(),
        };

        let state = match brain_state_u8 {
            0 => BrainState::Flow,
            1 => BrainState::Distress,
            2 => BrainState::Hypnosis,
            3 => BrainState::Impatience,
            4 => BrainState::Obsession,
            _ => BrainState::Flow,
        };

        let queue = NeuroQueueEngine::generate_neuro_queue(
            &seed,
            &candidates,
            state,
            now_sec,
            hour_of_day,
            target_count,
        );

        let out_json = match serde_json::to_string(&queue) {
            Ok(j) => j,
            Err(_) => return std::ptr::null_mut(),
        };

        CString::new(out_json)
            .map(|c| c.into_raw())
            .unwrap_or(std::ptr::null_mut())
    }));

    result.unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub unsafe extern "C" fn rust_step_airdrop_physics(
    buf_ptr: *mut f32,
    buf_len: usize,
    target_x: f32,
    target_y: f32,
    initial_dist: f32,
    dt: f32,
    particle_budget: u32,
) -> i32 {
    let result = catch_unwind(AssertUnwindSafe(|| {
        if buf_ptr.is_null() || buf_len < 13 {
            return -1;
        }

        let slice = std::slice::from_raw_parts_mut(buf_ptr, buf_len);
        let mut state = streamify_jam::airdrop::AirdropState {
            pos: [slice[0], slice[1], slice[2]],
            vel: [slice[3], slice[4], slice[5]],
            stretch_parallel: slice[6],
            stretch_perp: slice[7],
            rotation_rad: slice[8],
            pitch_deg: slice[9],
            roll_deg: slice[10],
            impact_progress: slice[11],
            is_docked: slice[12] > 0.5,
            is_ready_to_dock: if buf_len >= 14 { slice[13] > 0.5 } else { true },
            ..Default::default()
        };

        streamify_jam::airdrop::AirdropPhysicsEngine::step(
            &mut state,
            target_x,
            target_y,
            initial_dist,
            dt,
            particle_budget,
        );

        slice[0] = state.pos[0];
        slice[1] = state.pos[1];
        slice[2] = state.pos[2];
        slice[3] = state.vel[0];
        slice[4] = state.vel[1];
        slice[5] = state.vel[2];
        slice[6] = state.stretch_parallel;
        slice[7] = state.stretch_perp;
        slice[8] = state.rotation_rad;
        slice[9] = state.pitch_deg;
        slice[10] = state.roll_deg;
        slice[11] = state.impact_progress;
        slice[12] = if state.is_docked { 1.0 } else { 0.0 };
        if buf_len >= 14 {
            slice[13] = if state.is_ready_to_dock { 1.0 } else { 0.0 };
        }

        0
    }));

    result.unwrap_or(-2)
}
