import Foundation
import Observation

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG4-03 (Engineer 4): the synced karaoke lyrics engine — the Swift
// counterpart of `data/models/LyricsData.kt` (LRC parsing) +
// `media/lyrics/LyricPlaybackController.kt` (VSYNC-paced interpolation).
//
// PARSING (tier-2 Kotlin parity, total — never traps):
//   • `[offset: ±ms]` global header offset
//   • `[mm:ss.xx(.xxx)]` line timestamps (2- or 3-digit fraction)
//   • `<mm:ss.xx(.xxx)>word` word/syllable tags (enhanced LRC); the last
//     syllable of a line ends +3500 ms when the next tag is absent
//   • plain non-tagged text lines are kept as UNSYNCED lyrics (never discarded)
//   • lines sort by time; inter-line duration calculus clamps to >= 1200 ms,
//     the final line gets 4000 ms; a line without syllables gets a single
//     whole-line syllable spanning its duration
//
// PACING: the pacer maps a playback position in ms onto (active line,
// syllable, syllable fill fraction). The playback position comes from the
// pushed DacPresentationClock — via PlaybackViewModel.progress — so line
// switches land on the exact millisecond boundaries of the hardware
// presentation clock, and the view drives per-frame reads through SwiftUI's
// display-synced TimelineView (no timers, no polling loops).
// ─────────────────────────────────────────────────────────────────────────────

// MARK: - Models (Swift mirrors of Syllable / LyricsLine / LyricsData)

struct LyricSyllable: Equatable, Sendable {
    let text: String
    let startMs: Int
    let endMs: Int

    /// Fill fraction in [0, 1] at playback position `t` (ms).
    func fillFraction(atMs t: Int) -> Double {
        guard endMs > startMs else { return t >= endMs ? 1 : 0 }
        let clamped = min(max(Double(t - startMs), 0), Double(endMs - startMs))
        return clamped / Double(endMs - startMs)
    }
}

struct LyricLine: Equatable, Sendable, Identifiable {
    let timeMs: Int
    let text: String
    let syllables: [LyricSyllable]
    let durationMs: Int

    var id: Int { timeMs }

    /// True when the line carries real word/syllable timing (enhanced LRC);
    /// false for whole-line timing (the parser's single-syllable fallback).
    var isKaraoke: Bool {
        if syllables.count > 1 { return true }
        guard let only = syllables.first else { return false }
        return only.text != text
    }
}

struct LyricsDocument: Equatable, Sendable {
    let lines: [LyricLine]
    let isSynced: Bool
    let globalOffsetMs: Int

    static let empty = LyricsDocument(lines: [], isSynced: false, globalOffsetMs: 0)

    /// The active line index at playback position `t` (ms): the last line
    /// whose start is <= t; -1 before the first line.
    func activeLineIndex(atMs t: Int) -> Int {
        // Binary search over sorted starts — exact millisecond boundaries.
        var low = 0
        var high = lines.count - 1
        var found = -1
        while low <= high {
            let mid = (low + high) / 2
            if lines[mid].timeMs <= t {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        // A line only stays "active" until its end (start + duration); after
        // that the instrumental gap highlights nothing.
        if found >= 0, t > lines[found].timeMs + lines[found].durationMs {
            // Keep the line highlighted only while a syllable may still fill;
            // inter-line gaps leave the previous line dimmed-but-current on
            // Android, so we keep `found` (the next line simply takes over).
        }
        return found
    }
}

// MARK: - Parser (tier-2 Kotlin parity)

enum LrcParser {

    private static let offsetPattern = try? NSRegularExpression(
        pattern: #"\[offset:\s*(-?\d+)\]"#,
        options: [.caseInsensitive]
    )
    private static let linePattern = try? NSRegularExpression(
        pattern: #"\[(\d{2}):(\d{2})\.(\d{2,3})\](.*)"#,
        options: []
    )
    private static let wordPattern = try? NSRegularExpression(
        pattern: #"<(\d{2}):(\d{2})\.(\d{2,3})>([^<\[]*)"#,
        options: []
    )

    /// Parses LRC content into a sorted document (total — never traps).
    static func parse(_ lrcContent: String) -> LyricsDocument {
        if lrcContent.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return .empty
        }

        // 1. Extract the global header offset.
        var offsetMs = 0
        let full = NSRange(lrcContent.startIndex..., in: lrcContent)
        if let offsetPattern,
           let m = offsetPattern.firstMatch(in: lrcContent, options: [], range: full),
           let range = Range(m.range(at: 1), in: lrcContent),
           let value = Int(lrcContent[range]) {
            offsetMs = value
        }

        var syncedLines: [LyricLine] = []
        var unsyncedLines: [LyricLine] = []

        for rawLine in lrcContent.split(separator: "\n", omittingEmptySubsequences: false) {
            let trimmed = rawLine.trimmingCharacters(in: .whitespaces)
            if trimmed.isEmpty { continue }

            guard let linePattern else { continue }
            let lineRange = NSRange(trimmed.startIndex..., in: trimmed)
            guard let lineMatch = linePattern.firstMatch(in: trimmed, options: [], range: lineRange) else {
                // Plain unsynced text line (never discard).
                if !trimmed.hasPrefix("[") {
                    unsyncedLines.append(LyricLine(timeMs: 0, text: trimmed, syllables: [], durationMs: 0))
                }
                continue
            }

            func group(_ i: Int) -> String? {
                guard let r = Range(lineMatch.range(at: i), in: trimmed) else { return nil }
                return String(trimmed[r])
            }
            guard let minStr = group(1), let secStr = group(2), let msStr = group(3),
                  let minutes = Int(minStr), let seconds = Int(secStr)
            else { continue }

            let ms = msStr.count == 2 ? (Int(msStr) ?? 0) * 10 : (Int(msStr) ?? 0)
            let lineStartMs = minutes * 60_000 + seconds * 1_000 + ms + offsetMs

            guard let content = group(4), !content.isEmpty else { continue }

            // Word/syllable tags (enhanced LRC).
            if let wordPattern {
                let contentRange = NSRange(content.startIndex..., in: content)
                let matches = wordPattern.matches(in: content, options: [], range: contentRange)
                if !matches.isEmpty {
                    var syllables: [LyricSyllable] = []
                    var plainText = ""
                    for (i, wordMatch) in matches.enumerated() {
                        func wgroup(_ j: Int) -> String? {
                            guard let r = Range(wordMatch.range(at: j), in: content) else { return nil }
                            return String(content[r])
                        }
                        guard let wMin = wgroup(1), let wSec = wgroup(2), let wMsStr = wgroup(3),
                              let wMinutes = Int(wMin), let wSeconds = Int(wSec)
                        else { continue }
                        let wMs = wMsStr.count == 2 ? (Int(wMsStr) ?? 0) * 10 : (Int(wMsStr) ?? 0)
                        let wStartMs = wMinutes * 60_000 + wSeconds * 1_000 + wMs + offsetMs
                        guard let wordText = wgroup(4) else { continue }
                        plainText += wordText

                        var nextStartMs = wStartMs + 3_500
                        if i < matches.count - 1 {
                            guard let n = Range(matches[i + 1].range(at: 1), in: content),
                                  let n2 = Range(matches[i + 1].range(at: 2), in: content),
                                  let n3 = Range(matches[i + 1].range(at: 3), in: content),
                                  let nMin = Int(content[n]), let nSec = Int(content[n2])
                            else { continue }
                            let nMs = String(content[n3]).count == 2
                                ? (Int(String(content[n3])) ?? 0) * 10
                                : (Int(String(content[n3])) ?? 0)
                            nextStartMs = nMin * 60_000 + nSec * 1_000 + nMs + offsetMs
                        }
                        syllables.append(LyricSyllable(text: wordText, startMs: wStartMs, endMs: nextStartMs))
                    }
                    let text = plainText.trimmingCharacters(in: .whitespaces)
                    syncedLines.append(LyricLine(timeMs: lineStartMs, text: text, syllables: syllables, durationMs: 0))
                    continue
                }
            }

            // Standard line-level LRC.
            syncedLines.append(LyricLine(timeMs: lineStartMs, text: content, syllables: [], durationMs: 0))
        }

        if !syncedLines.isEmpty {
            let sorted = syncedLines.sorted { $0.timeMs < $1.timeMs }
            var withDurations: [LyricLine] = []
            withDurations.reserveCapacity(sorted.count)
            for (i, line) in sorted.enumerated() {
                let duration: Int
                if i < sorted.count - 1 {
                    duration = max(sorted[i + 1].timeMs - line.timeMs, 1_200)
                } else {
                    duration = 4_000
                }
                let nextLineTimeMs = line.timeMs + duration
                let syllables = line.syllables.isEmpty
                    ? [LyricSyllable(text: line.text, startMs: line.timeMs, endMs: nextLineTimeMs)]
                    : line.syllables
                withDurations.append(
                    LyricLine(timeMs: line.timeMs, text: line.text, syllables: syllables, durationMs: duration)
                )
            }
            return LyricsDocument(lines: withDurations, isSynced: true, globalOffsetMs: offsetMs)
        }

        if !unsyncedLines.isEmpty {
            return LyricsDocument(lines: unsyncedLines, isSynced: false, globalOffsetMs: 0)
        }
        return .empty
    }

    /// Re-serializes a document to LRC (the "save my offset" round trip).
    static func formatLrc(_ lines: [LyricLine], offsetMs: Int = 0) -> String {
        var out = ""
        for line in lines {
            var row = tag(max(line.timeMs + offsetMs, 0))
            if line.isKaraoke {
                for syl in line.syllables {
                    row += "<" + timeString(max(syl.startMs + offsetMs, 0)) + ">" + syl.text
                }
            } else {
                row += line.text
            }
            out += row + "\n"
        }
        return out.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func tag(_ ms: Int) -> String {
        "[" + timeString(ms) + "]"
    }

    private static func timeString(_ ms: Int) -> String {
        let total = max(ms, 0)
        let minutes = total / 60_000
        let seconds = (total % 60_000) / 1_000
        let centis = (total % 1_000) / 10
        return String(format: "%02d:%02d.%02d", minutes, seconds, centis)
    }
}

// MARK: - Pacer (the stateful karaoke cursor)

/// Tracks the active karaoke cursor for one bound track: the document, the
/// user's persisted sync offset, and the derived cursor state. Feed it the
/// (interpolated) playback position; the view reads the cursor per frame.
@Observable
@MainActor
final class LyricPacerService {

    // ── Observable cursor state (drives karaoke highlight + auto-scroll) ──

    private(set) var document: LyricsDocument = .empty
    private(set) var trackKey: String = ""
    private(set) var userOffsetMs = 0

    /// The last position the cursor was evaluated at (ms, including offset).
    private(set) var lastPositionMs = 0

    // ── Constants (Kotlin parity: gap snap + offset nudge step) ───────────

    static let offsetNudgeStepMs = 250

    private let settings: SettingsStore

    init(settings: SettingsStore? = nil) {
        self.settings = settings ?? AppServices.settings
    }

    // MARK: - Bind / parse

    /// Binds a track: parses the LRC content and restores the persisted
    /// per-track sync offset (the `LyricOffsetStore` behavior).
    func bindTrack(id: String, title: String?, artist: String?, lrcContent: String?) {
        trackKey = SettingsStore.lyricKey(title: title, artist: artist)
        if let lrcContent {
            document = LrcParser.parse(lrcContent)
        } else {
            document = .empty
        }
        userOffsetMs = id.isEmpty ? 0 : settings.lyricOffset(forKey: trackKey)
        lastPositionMs = 0
    }

    /// Replaces the document (e.g. after the user submits a corrected LRC).
    func replaceDocument(lrcContent: String) {
        document = LrcParser.parse(lrcContent)
    }

    // MARK: - User offset

    /// Nudges the user sync offset (persisted per track).
    func adjustOffset(deltaMs: Int) {
        userOffsetMs += deltaMs
        settings.setLyricOffset(userOffsetMs, forKey: trackKey)
    }

    /// Resets the user sync offset to zero (persisted).
    func resetOffset() {
        userOffsetMs = 0
        settings.setLyricOffset(0, forKey: trackKey)
    }

    // MARK: - Cursor evaluation (exact millisecond boundaries)

    /// Evaluates the cursor at a raw playback position (ms). Returns the
    /// active line index (or nil when no line is active).
    @discardableResult
    func update(atPositionMs positionMs: Int) -> Int? {
        lastPositionMs = positionMs + userOffsetMs
        guard document.isSynced else { return nil }
        let index = document.activeLineIndex(atMs: lastPositionMs)
        return index >= 0 ? index : nil
    }

    /// The effective playback position (raw + user offset), for frame reads.
    var effectivePositionMs: Int { lastPositionMs }

    /// Karaoke fill states for one line at the last evaluated position.
    /// Each tuple is (fill fraction in [0,1], hasStarted).
    func syllableFills(lineIndex: Int, atPositionMs positionMs: Int) -> [(fraction: Double, started: Bool)] {
        guard document.lines.indices.contains(lineIndex) else { return [] }
        let t = positionMs + userOffsetMs
        return document.lines[lineIndex].syllables.map {
            let fraction = $0.fillFraction(atMs: t)
            return (fraction, t >= $0.startMs)
        }
    }

    /// Whole-line fill fraction (0 before, 1 after) for a line at `positionMs`.
    func lineFill(lineIndex: Int, atPositionMs positionMs: Int) -> Double {
        guard document.lines.indices.contains(lineIndex) else { return 0 }
        let line = document.lines[lineIndex]
        let t = positionMs + userOffsetMs
        let syllable = LyricSyllable(text: line.text, startMs: line.timeMs, endMs: line.timeMs + line.durationMs)
        return syllable.fillFraction(atMs: t)
    }

    var hasLyrics: Bool { !document.lines.isEmpty }
    var isSynced: Bool { document.isSynced }
}
