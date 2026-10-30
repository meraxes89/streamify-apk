import XCTest
@testable import StreamifyIOS

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG4-03 (Engineer 4): lyrics timing — the acceptance gate requires lines
// to switch at the EXACT millisecond boundaries of the pushed
// DacPresentationClock. These tests pin the parser wire contract (the Kotlin
// tier-2 LRC grammar) and the cursor math at single-millisecond resolution.
// ─────────────────────────────────────────────────────────────────────────────

@MainActor
final class LyricPacerServiceTests: XCTestCase {

    // ── Parser: line + word timestamps ─────────────────────────────────────

    func testParseLineTimestampsWithTwoAndThreeDigitFractions() {
        let lrc = """
        [00:01.00]First line
        [00:03.500]Second line
        [01:02.25]Third line
        """
        let doc = LrcParser.parse(lrc)
        XCTAssertTrue(doc.isSynced)
        XCTAssertEqual(3, doc.lines.count)
        XCTAssertEqual(1_000, doc.lines[0].timeMs)
        XCTAssertEqual(3_500, doc.lines[1].timeMs)
        XCTAssertEqual(62_250, doc.lines[2].timeMs)
    }

    func testParseGlobalOffsetHeaderShiftsAllTimestamps() {
        let lrc = """
        [offset: 250]
        [00:01.00]Shifted
        """
        let doc = LrcParser.parse(lrc)
        XCTAssertEqual(250, doc.globalOffsetMs)
        XCTAssertEqual(1_250, doc.lines[0].timeMs)
    }

    func testParseWordTagsBuildsSyllablesAndPlainText() {
        let lrc = "[00:01.00]<00:01.00>Hel<00:01.40>lo <00:01.80>world"
        let doc = LrcParser.parse(lrc)
        XCTAssertTrue(doc.isSynced)
        XCTAssertEqual(1, doc.lines.count)
        let line = doc.lines[0]
        XCTAssertEqual("Hello world", line.text)
        XCTAssertTrue(line.isKaraoke)
        XCTAssertEqual(3, line.syllables.count)
        XCTAssertEqual("Hel", line.syllables[0].text)
        XCTAssertEqual(1_000, line.syllables[0].startMs)
        XCTAssertEqual(1_400, line.syllables[1].startMs)
        // The final syllable without a successor ends +3500 ms (Kotlin parity).
        XCTAssertEqual(1_800 + 3_500, line.syllables[2].endMs)
    }

    func testParseUnsyncedPlainTextLinesAreKept() {
        let lrc = """
        just a plain unsynced line
        another one
        """
        let doc = LrcParser.parse(lrc)
        XCTAssertFalse(doc.isSynced)
        XCTAssertEqual(2, doc.lines.count)
        XCTAssertEqual("just a plain unsynced line", doc.lines[0].text)
        XCTAssertEqual("another one", doc.lines[1].text)
    }

    func testParseSortsLinesAndComputesDurations() {
        let lrc = """
        [00:05.00]Later
        [00:01.00]Earlier
        [00:01.20]Middle
        """
        let doc = LrcParser.parse(lrc)
        XCTAssertEqual([1_000, 1_200, 5_000], doc.lines.map(\.timeMs))
        // Inter-line duration clamps to >= 1200 ms (Kotlin parity).
        XCTAssertEqual(1_200, doc.lines[0].durationMs)
        XCTAssertEqual(3_800, doc.lines[1].durationMs)
        // The final line gets a flat 4000 ms.
        XCTAssertEqual(4_000, doc.lines[2].durationMs)
    }

    func testParseGivesUntaggedLinesAWholeLineSyllable() {
        let lrc = """
        [00:01.00]Solo
        [00:05.00]Next
        """
        let doc = LrcParser.parse(lrc)
        XCTAssertEqual(1, doc.lines[0].syllables.count)
        XCTAssertEqual("Solo", doc.lines[0].syllables[0].text)
        XCTAssertEqual(1_000, doc.lines[0].syllables[0].startMs)
        // Whole-line syllable spans the line's full duration (4000 ms here).
        XCTAssertEqual(1_000 + 4_000, doc.lines[0].syllables[0].endMs)
        XCTAssertFalse(doc.lines[0].isKaraoke) // whole-line timing, not word tags
    }

    func testParseEmptyAndWhitespaceContentYieldsEmptyDocument() {
        XCTAssertEqual(LyricsDocument.empty, LrcParser.parse(""))
        XCTAssertEqual(LyricsDocument.empty, LrcParser.parse("   \n  \n"))
    }

    func testParseMalformedTagsNeverTrapAndNeverSync() {
        // A line that starts with '[' but matches no tag grammar is discarded
        // (Kotlin parity: only non-tag lines survive as unsynced text).
        let doc = LrcParser.parse("[99:zz.q]gibberish [not a tag] more")
        XCTAssertFalse(doc.isSynced)
        XCTAssertEqual(0, doc.lines.count)

        // Plain text without any tag prefix survives as unsynced lyrics.
        let unsynced = LrcParser.parse("gibberish without a tag")
        XCTAssertFalse(unsynced.isSynced)
        XCTAssertEqual(1, unsynced.lines.count)
    }

    // ── Cursor: exact millisecond line switching ───────────────────────────

    private func syncedDoc() -> LyricsDocument {
        LrcParser.parse("""
        [00:01.000]A
        [00:02.000]B
        [00:03.000]C
        """)
    }

    func testActiveLineSwitchesAtExactMillisecondBoundaries() {
        let doc = syncedDoc()
        // One millisecond BEFORE the second line: still line A.
        XCTAssertEqual(0, doc.activeLineIndex(atMs: 1_999))
        // Exactly AT the boundary: line B takes over.
        XCTAssertEqual(1, doc.activeLineIndex(atMs: 2_000))
        XCTAssertEqual(2, doc.activeLineIndex(atMs: 3_000))
        // Before the first line: no active line.
        XCTAssertEqual(-1, doc.activeLineIndex(atMs: 999))
        XCTAssertEqual(-1, doc.activeLineIndex(atMs: 0))
        // A 3-digit fraction boundary inside a millisecond.
        let fractional = LrcParser.parse("[00:01.001]X")
        XCTAssertEqual(-1, fractional.activeLineIndex(atMs: 1_000))
        XCTAssertEqual(0, fractional.activeLineIndex(atMs: 1_001))
    }

    func testSyllableFillResolvesAtExactMillisecondBoundaries() {
        let doc = LrcParser.parse("[00:01.00]<00:01.00>One <00:02.00>Two")
        let syllables = doc.lines[0].syllables
        XCTAssertEqual(0.0, syllables[0].fillFraction(atMs: 999), accuracy: 1e-9)
        XCTAssertEqual(0.0, syllables[0].fillFraction(atMs: 1_000), accuracy: 1e-9)
        XCTAssertEqual(0.5, syllables[0].fillFraction(atMs: 1_500), accuracy: 1e-9)
        XCTAssertEqual(1.0, syllables[0].fillFraction(atMs: 2_000), accuracy: 1e-9)
        XCTAssertEqual(1.0, syllables[0].fillFraction(atMs: 9_999), accuracy: 1e-9)
    }

    // ── Pacer service (bind, offset persistence, cursor) ───────────────────

    func testBindTrackRestoresPersistedOffset() {
        let settings = SettingsStore.ephemeral()
        settings.setLyricOffset(500, forKey: SettingsStore.lyricKey(title: "Nightcall", artist: "Kavinsky"))

        let pacer = LyricPacerService(settings: settings)
        pacer.bindTrack(
            id: "t1",
            title: "Nightcall",
            artist: "Kavinsky",
            lrcContent: "[00:01.000]A\n[00:02.000]B"
        )
        XCTAssertEqual(500, pacer.userOffsetMs)

        // The offset shifts the cursor: with +500 ms, line B is active at 1600 ms.
        XCTAssertEqual(1, pacer.update(atPositionMs: 1_600) ?? -1)
        // Without the offset it would still be line A.
        XCTAssertEqual(0, syncedDoc().activeLineIndex(atMs: 1_600))
    }

    func testAdjustOffsetPersistsAndResetClears() {
        let settings = SettingsStore.ephemeral()
        let pacer = LyricPacerService(settings: settings)
        pacer.bindTrack(id: "t1", title: "T", artist: "A", lrcContent: "[00:01.000]A")

        pacer.adjustOffset(deltaMs: LyricPacerService.offsetNudgeStepMs)
        XCTAssertEqual(LyricPacerService.offsetNudgeStepMs, pacer.userOffsetMs)
        XCTAssertEqual(
            LyricPacerService.offsetNudgeStepMs,
            settings.lyricOffset(forKey: SettingsStore.lyricKey(title: "T", artist: "A"))
        )

        pacer.resetOffset()
        XCTAssertEqual(0, pacer.userOffsetMs)
        XCTAssertEqual(0, settings.lyricOffset(forKey: SettingsStore.lyricKey(title: "T", artist: "A")))
    }

    func testUnsyncedDocumentsReportNoActiveLine() {
        let pacer = LyricPacerService(settings: SettingsStore.ephemeral())
        pacer.bindTrack(id: "t1", title: "T", artist: "A", lrcContent: "plain text only")
        XCTAssertNil(pacer.update(atPositionMs: 10_000))
        XCTAssertTrue(pacer.hasLyrics)
        XCTAssertFalse(pacer.isSynced)
    }

    // ── LRC re-serialization ───────────────────────────────────────────────

    func testFormatLrcRoundTripsPlainLineTimestamps() {
        let doc = LrcParser.parse("[00:01.00]A\n[01:02.05]B")
        let out = LrcParser.formatLrc(doc.lines)
        let reparsed = LrcParser.parse(out)
        XCTAssertEqual(doc.lines.map(\.timeMs), reparsed.lines.map(\.timeMs))
        XCTAssertEqual(["A", "B"], reparsed.lines.map(\.text))
    }
}
