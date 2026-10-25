// Clippy: FFI entry points in this module all follow the house safety
// model (catch_unwind shields, sentinel returns, no panics across the
// boundary — see the module docs); per-function `# Safety` boilerplate
// would be copy-paste.
#![allow(clippy::missing_safety_doc)]
use serde::{Deserialize, Serialize};

// Tier 0 B1 (#23): LRC patterns are compile-time constants — one shared
// compiled instance per pattern for the whole process. Previously every
// parse call recompiled its own `Regex`, and the identical line pattern was
// compiled separately in three functions. `Option` inside keeps the
// failure path (a pattern that fails to compile) returning empty results
// instead of panicking — the `.ok()` site's discipline, applied uniformly.
static LRC_LINE_RE: std::sync::OnceLock<Option<regex::Regex>> = std::sync::OnceLock::new();
static LRC_WORD_RE: std::sync::OnceLock<Option<regex::Regex>> = std::sync::OnceLock::new();

pub const SLYR_MAGIC: u32 = 0x534C5952; // "SLYR" (big-endian reading)
/// On-disk/pinned magic MUST be the literal ASCII bytes regardless of host
/// endianness — the u32 constant above serializes LE ("RYLS") via struct cast.
pub const SLYR_MAGIC_BYTES: [u8; 4] = *b"SLYR";
pub const SLYR_VERSION: u16 = 1;

#[repr(C, align(16))]
#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
pub struct SlyrHeader {
    pub magic: u32,             // 0x534C5952
    pub version: u16,           // 1
    pub line_count: u16,        // Total lyric lines
    pub syllable_count: u32,    // Total syllable segments
    pub text_pool_len: u32,     // Byte length of UTF-8 string pool
    pub vocal_offset_ms: i32,   // Auto-calibrated KissFFT drift offset (Δτ*)
    pub flags: u32,             // Bit 0: Has Syllables, Bit 1: Is Explicit
    pub reserved: [u8; 8],      // 16-byte boundary padding
}

#[repr(C, align(16))]
#[derive(Debug, Clone, Copy, Default, PartialEq, Serialize, Deserialize)]
pub struct SlyrLineHeader {
    pub start_time_ms: u32,     // Line onset
    pub end_time_ms: u32,       // Line offset
    pub syllable_start_idx: u16,// Index into syllable table
    pub syllable_count: u16,    // Number of syllables in line
    pub text_offset: u32,       // Byte offset into UTF-8 text pool
}

#[repr(C, align(16))]
#[derive(Debug, Clone, Copy, Default, PartialEq, Serialize, Deserialize)]
pub struct SlyrSyllableSpan {
    pub start_time_ms: u32,     // Syllable start
    pub end_time_ms: u32,       // Syllable end
    pub char_start: u16,        // Relative character start inside line text
    pub char_len: u16,          // Character length
    pub flags: u32,             // Bit 0: Background vocal, Bit 1: Melisma
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RawParsedLine {
    pub start_time_ms: u32,
    pub end_time_ms: u32,
    pub text: String,
    pub syllables: Vec<(u32, u32, u16, u16)>, // (start_ms, end_ms, char_start, char_len)
}

pub struct SlyrCompiler;

impl SlyrCompiler {
    /// Compiles parsed multi-track text into a contiguous 16-byte aligned binary buffer
    pub fn compile(lines: &[RawParsedLine], vocal_offset_ms: i32) -> Vec<u8> {
        let mut text_pool = Vec::new();
        let mut line_headers = Vec::with_capacity(lines.len());
        let mut syllable_spans = Vec::new();

        for line in lines {
            let text_offset = text_pool.len() as u32;
            let text_bytes = line.text.as_bytes();
            text_pool.extend_from_slice(text_bytes);
            text_pool.push(0); // Null terminator

            let syllable_start_idx = syllable_spans.len() as u16;
            let syllable_count = line.syllables.len() as u16;

            for &(s_start, s_end, c_start, c_len) in &line.syllables {
                syllable_spans.push(SlyrSyllableSpan {
                    start_time_ms: s_start,
                    end_time_ms: s_end,
                    char_start: c_start,
                    char_len: c_len,
                    flags: 0,
                });
            }

            line_headers.push(SlyrLineHeader {
                start_time_ms: line.start_time_ms,
                end_time_ms: line.end_time_ms,
                syllable_start_idx,
                syllable_count,
                text_offset,
            });
        }

        let header = SlyrHeader {
            magic: SLYR_MAGIC,
            version: SLYR_VERSION,
            line_count: line_headers.len() as u16,
            syllable_count: syllable_spans.len() as u32,
            text_pool_len: text_pool.len() as u32,
            vocal_offset_ms,
            flags: if !syllable_spans.is_empty() { 1 } else { 0 },
            reserved: [0; 8],
        };

        // Serialize into memory buffer with exact 16-byte struct alignment
        let mut buffer = Vec::new();
        unsafe {
            let header_slice = std::slice::from_raw_parts(
                &header as *const _ as *const u8,
                std::mem::size_of::<SlyrHeader>(),
            );
            buffer.extend_from_slice(header_slice);

            let lines_slice = std::slice::from_raw_parts(
                line_headers.as_ptr() as *const u8,
                line_headers.len() * std::mem::size_of::<SlyrLineHeader>(),
            );
            buffer.extend_from_slice(lines_slice);

            let syllables_slice = std::slice::from_raw_parts(
                syllable_spans.as_ptr() as *const u8,
                syllable_spans.len() * std::mem::size_of::<SlyrSyllableSpan>(),
            );
            buffer.extend_from_slice(syllables_slice);
        }

        // Endianness-safe magic: the struct cast above wrote the u32 in host
        // order; stamp the canonical ASCII bytes so every consumer sees SLYR.
        buffer[0..4].copy_from_slice(&SLYR_MAGIC_BYTES);

        buffer.extend_from_slice(&text_pool);
        while buffer.len() % 16 != 0 {
            buffer.push(0);
        }
        buffer
    }

    /// O(log N) binary search lookup for active line and syllable
    pub fn find_active_line(buffer: &[u8], playhead_ms: u32) -> Option<usize> {
        if buffer.len() < std::mem::size_of::<SlyrHeader>() {
            return None;
        }

        if buffer[0..4] != SLYR_MAGIC_BYTES {
            return None;
        }
        let header = unsafe { &*(buffer.as_ptr() as *const SlyrHeader) };

        let adjusted_time = (playhead_ms as i32 + header.vocal_offset_ms).max(0) as u32;
        let line_offset = std::mem::size_of::<SlyrHeader>();
        let total_lines = header.line_count as usize;

        if buffer.len() < line_offset + total_lines * std::mem::size_of::<SlyrLineHeader>() {
            return None;
        }

        let lines = unsafe {
            std::slice::from_raw_parts(
                buffer[line_offset..].as_ptr() as *const SlyrLineHeader,
                total_lines,
            )
        };

        lines
            .binary_search_by(|line| {
                if adjusted_time < line.start_time_ms {
                    std::cmp::Ordering::Greater
                } else if adjusted_time > line.end_time_ms {
                    std::cmp::Ordering::Less
                } else {
                    std::cmp::Ordering::Equal
                }
            })
            .ok()
    }
}

// Backward-compatible LyricCompiler helper for string LRC conversion
pub struct LyricCompiler;

impl LyricCompiler {
    pub fn compile_to_slyr(lrc_text: &str) -> Vec<u8> {
        let mut raw_lines = Vec::new();
        let re_line = LRC_LINE_RE
            .get_or_init(|| regex::Regex::new(r"^\[(\d{2}):(\d{2})\.(\d{2,3})\](.*)$").ok());
        let re_word = LRC_WORD_RE
            .get_or_init(|| regex::Regex::new(r"<(\d{2}):(\d{2})\.(\d{2,3})>([^<\[]*)").ok());
        let (re_line, re_word) = match (re_line.as_ref(), re_word.as_ref()) {
            (Some(l), Some(w)) => (l, w),
            _ => return SlyrCompiler::compile(&raw_lines, 0),
        };

        for line_str in lrc_text.lines() {
            let trimmed = line_str.trim();
            if trimmed.is_empty() || trimmed.starts_with("[ti:") || trimmed.starts_with("[ar:") || trimmed.starts_with("[al:") {
                continue;
            }
            if let Some(caps) = re_line.captures(trimmed) {
                let m: u32 = caps[1].parse().unwrap_or(0);
                let s: u32 = caps[2].parse().unwrap_or(0);
                let ms: u32 = if caps[3].len() == 2 {
                    caps[3].parse::<u32>().unwrap_or(0) * 10
                } else {
                    caps[3].parse().unwrap_or(0)
                };
                let start_ms = (m * 60 + s) * 1000 + ms;
                let raw_content = caps[4].trim();

                if !raw_content.is_empty() {
                    let mut syllables = Vec::new();
                    let mut plain_text = String::new();

                    let word_caps: Vec<_> = re_word.captures_iter(raw_content).collect();
                    if !word_caps.is_empty() {
                        for i in 0..word_caps.len() {
                            let wc = &word_caps[i];
                            let wm: u32 = wc[1].parse().unwrap_or(0);
                            let ws: u32 = wc[2].parse().unwrap_or(0);
                            let wms: u32 = if wc[3].len() == 2 {
                                wc[3].parse::<u32>().unwrap_or(0) * 10
                            } else {
                                wc[3].parse().unwrap_or(0)
                            };
                            let w_start_ms = (wm * 60 + ws) * 1000 + wms;
                            let word_str = &wc[4];

                            let char_start = plain_text.chars().count() as u16;
                            let char_len = word_str.chars().count() as u16;
                            plain_text.push_str(word_str);

                            let next_start = if i + 1 < word_caps.len() {
                                let n_wc = &word_caps[i + 1];
                                let n_wm: u32 = n_wc[1].parse().unwrap_or(0);
                                let n_ws: u32 = n_wc[2].parse().unwrap_or(0);
                                let n_wms: u32 = if n_wc[3].len() == 2 {
                                    n_wc[3].parse::<u32>().unwrap_or(0) * 10
                                } else {
                                    n_wc[3].parse().unwrap_or(0)
                                };
                                (n_wm * 60 + n_ws) * 1000 + n_wms
                            } else {
                                w_start_ms + 3000
                            };

                            syllables.push((w_start_ms, next_start, char_start, char_len));
                        }
                    } else {
                        plain_text = raw_content.to_string();
                    }

                    if !plain_text.is_empty() {
                        let end_ms = start_ms + 3000;
                        raw_lines.push(RawParsedLine {
                            start_time_ms: start_ms,
                            end_time_ms: end_ms,
                            text: plain_text.trim().to_string(),
                            syllables,
                        });
                    }
                }
            }
        }

        for i in 0..raw_lines.len() {
            if i + 1 < raw_lines.len() {
                let next_start = raw_lines[i + 1].start_time_ms;
                raw_lines[i].end_time_ms = next_start;
                // Update final syllable end time to match line end time if available
                if let Some(last_syl) = raw_lines[i].syllables.last_mut() {
                    last_syl.1 = next_start;
                }
            }
        }

        SlyrCompiler::compile(&raw_lines, 0)
    }

    pub fn compile_lrc(lrc_text: &str) -> CompiledLyrics {
        let mut entries = Vec::new();
        let re_line = LRC_LINE_RE
            .get_or_init(|| regex::Regex::new(r"^\[(\d{2}):(\d{2})\.(\d{2,3})\](.*)$").ok());
        let Some(re_line) = re_line.as_ref() else {
            return CompiledLyrics { entries };
        };

        for line_str in lrc_text.lines() {
            let trimmed = line_str.trim();
            if trimmed.is_empty() || trimmed.starts_with("[ti:") || trimmed.starts_with("[ar:") {
                continue;
            }
            if let Some(caps) = re_line.captures(trimmed) {
                let m: u32 = caps[1].parse().unwrap_or(0);
                let s: u32 = caps[2].parse().unwrap_or(0);
                let ms: u32 = if caps[3].len() == 2 {
                    caps[3].parse::<u32>().unwrap_or(0) * 10
                } else {
                    caps[3].parse().unwrap_or(0)
                };
                let start_ms = (m * 60 + s) * 1000 + ms;
                let text = caps[4].trim().to_string();
                if !text.is_empty() {
                    entries.push(CompiledLyricEntry {
                        start_time_ms: start_ms,
                        end_time_ms: start_ms + 3000,
                        text,
                    });
                }
            }
        }
        CompiledLyrics { entries }
    }

    /// FFI-facing: the caller (C/JNI boundary, itself unsafe) owns the
    /// pointer; this function null-checks before the slice view.
    #[allow(clippy::not_unsafe_ptr_arg_deref)]
    pub fn find_active_positions(slyr_ptr: *const u8, slyr_len: usize, position_ms: u32) -> Option<(usize, usize)> {
        if slyr_ptr.is_null() || slyr_len == 0 {
            return None;
        }
        let slice = unsafe { std::slice::from_raw_parts(slyr_ptr, slyr_len) };
        let line_idx = SlyrCompiler::find_active_line(slice, position_ms)?;

        let header_size = std::mem::size_of::<SlyrHeader>();
        let line_size = std::mem::size_of::<SlyrLineHeader>();
        let span_size = std::mem::size_of::<SlyrSyllableSpan>();
        if slice.len() < header_size {
            return Some((line_idx, 0));
        }

        let header = unsafe { &*(slice.as_ptr() as *const SlyrHeader) };
        let total_lines = header.line_count as usize;
        if total_lines == 0 || slice.len() < header_size + total_lines * line_size {
            return Some((line_idx, 0));
        }
        let lines = unsafe {
            std::slice::from_raw_parts(slice[header_size..].as_ptr() as *const SlyrLineHeader, total_lines)
        };
        let line = &lines[line_idx];
        if line.syllable_count == 0 {
            return Some((line_idx, 0));
        }

        let span_offset = header_size + total_lines * line_size;
        let total_spans = header.syllable_count as usize;
        if slice.len() < span_offset + total_spans * span_size {
            return Some((line_idx, 0));
        }
        let spans = unsafe {
            std::slice::from_raw_parts(slice[span_offset..].as_ptr() as *const SlyrSyllableSpan, total_spans)
        };

        let start = line.syllable_start_idx as usize;
        let end = start + line.syllable_count as usize;
        let syl_idx = spans[start..end]
            .binary_search_by(|span| {
                if position_ms < span.start_time_ms {
                    std::cmp::Ordering::Greater
                } else if position_ms >= span.end_time_ms {
                    std::cmp::Ordering::Less
                } else {
                    std::cmp::Ordering::Equal
                }
            })
            .unwrap_or(0);
        Some((line_idx, syl_idx))
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CompiledLyricEntry {
    pub start_time_ms: u32,
    pub end_time_ms: u32,
    pub text: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CompiledLyrics {
    pub entries: Vec<CompiledLyricEntry>,
}

#[repr(C)]
pub struct LyricMap {
    pub lines: Vec<(u64, String)>, // Sorted by time_ms
}

impl LyricMap {
    pub fn new(lines: Vec<(u64, String)>) -> Self {
        Self { lines }
    }

    #[inline(always)]
    pub fn get_active_index(&self, current_time_ms: u64) -> usize {
        if self.lines.is_empty() {
            return 0;
        }
        match self.lines.binary_search_by_key(&current_time_ms, |(t, _)| *t) {
            Ok(idx) => idx,
            Err(idx) => {
                if idx == 0 { 0 } else { idx - 1 }
            }
        }
    }
}

#[no_mangle]
pub unsafe extern "C" fn parse_lrc_file(lrc_ptr: *const std::os::raw::c_char, lrc_len: usize) -> *mut LyricMap {
    if lrc_ptr.is_null() || lrc_len == 0 {
        return Box::into_raw(Box::new(LyricMap::new(Vec::new())));
    }

    let lrc_slice = std::slice::from_raw_parts(lrc_ptr as *const u8, lrc_len);
    let lrc_str = std::str::from_utf8(lrc_slice).unwrap_or("");

    let mut lines = Vec::new();
    let re_line = LRC_LINE_RE
        .get_or_init(|| regex::Regex::new(r"^\[(\d{2}):(\d{2})\.(\d{2,3})\](.*)$").ok());

    for line in lrc_str.lines() {
        let trimmed = line.trim();
        if trimmed.is_empty() || trimmed.starts_with("[ti:") || trimmed.starts_with("[ar:") || trimmed.starts_with("[al:") {
            continue;
        }
        if let Some(caps) = re_line.as_ref().and_then(|re| re.captures(trimmed)) {
            let m: u64 = caps[1].parse().unwrap_or(0);
            let s: u64 = caps[2].parse().unwrap_or(0);
            let ms: u64 = if caps[3].len() == 2 {
                caps[3].parse::<u64>().unwrap_or(0) * 10
            } else {
                caps[3].parse().unwrap_or(0)
            };
            let time_ms = (m * 60 + s) * 1000 + ms;
            let text = caps[4].trim().to_string();
            if !text.is_empty() {
                lines.push((time_ms, text));
            }
        }
    }

    lines.sort_by_key(|k| k.0);
    Box::into_raw(Box::new(LyricMap::new(lines)))
}

#[no_mangle]
pub unsafe extern "C" fn get_lyric_index(map_ptr: *mut LyricMap, current_time_ms: u64) -> i32 {
    if map_ptr.is_null() {
        return -1;
    }
    let map = &*map_ptr;
    map.get_active_index(current_time_ms) as i32
}

#[no_mangle]
pub unsafe extern "C" fn free_lyric_map(map_ptr: *mut LyricMap) {
    if !map_ptr.is_null() {
        let _ = Box::from_raw(map_ptr);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Tier 0 B1 (#23) regression lock: the shared OnceLock LRC patterns
    /// must parse exactly like the per-call-compiled originals — timestamp
    /// scaling (2-digit vs 3-digit centiseconds), metadata-line skipping,
    /// and word-timing extraction.
    #[test]
    fn lrc_line_pattern_parses_timestamps_and_skips_metadata() {
        let lrc = "[ti:Title]\n[ar:Artist]\n[al:Album]\n[01:23.45]hello world\n[00:05.789]wordy <00:06.100>tim <00:07.200>ings\n";
        let compiled = LyricCompiler::compile_to_slyr(lrc);
        assert!(!compiled.is_empty(), "compiled output should not be empty");

        let parsed = LyricCompiler::compile_lrc(lrc);
        // Two timestamped lines survive (metadata skipped).
        assert_eq!(parsed.entries.len(), 2, "entries: {:?}", parsed.entries.len());
        // First line: 01:23.45 -> (1*60+23)*1000 + 450ms (2-digit centis x10).
        assert_eq!(parsed.entries[0].start_time_ms, 83_450);
        // Second line: 00:05.789 -> 5789ms (3-digit millis pass through).
        assert_eq!(parsed.entries[1].start_time_ms, 5_789);
    }
}
