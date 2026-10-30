import XCTest
@testable import StreamifyIOS

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2-06 — the queue reorder/removal projections' headless gates.
//
// The native List reports `.onMove` / `.onDelete` coordinates SECTION-LOCAL
// (relative to the rendered "Up next" rows). These tests pin the
// section-local → full-array translation contract: the now-playing cursor
// must stay pinned to its track through every mutation.
// ─────────────────────────────────────────────────────────────────────────────

final class QueueReorderTests: XCTestCase {

    private let tracks: [PresentationTrack] = [
        PresentationTrack(id: "a", title: "Alpha", artist: "One", durationMs: 1),
        PresentationTrack(id: "b", title: "Beta", artist: "Two", durationMs: 2),
        PresentationTrack(id: "c", title: "Gamma", artist: "Three", durationMs: 3),
        PresentationTrack(id: "d", title: "Delta", artist: "Four", durationMs: 4),
        PresentationTrack(id: "e", title: "Epsilon", artist: "Five", durationMs: 5),
    ]

    private var playingAt2: QueueListState {
        QueueListState(tracks: tracks, currentIndex: 2, isPlaying: true)
    }

    // ── movingUpNext ─────────────────────────────────────────────────────

    func testSingleMoveWithinUpNextKeepsTheCursorPinned() {
        let state = playingAt2
        // upNext = [d, e]; move section index 1 (e) to 0 → [e, d]
        let moved = state.movingUpNext(from: IndexSet(integer: 1), to: 0)
        XCTAssertEqual(moved.tracks.map(\.id), ["a", "b", "c", "e", "d"])
        XCTAssertEqual(moved.current?.id, "c", "the playing row stays pinned to its track")
        XCTAssertEqual(moved.currentIndex, 2)
        XCTAssertTrue(moved.isPlaying)
    }

    func testMultiOffsetMoveLandsAtPreRemovalDestination() {
        let six = tracks + [PresentationTrack(id: "f", title: "Zeta", artist: "Six", durationMs: 6)]
        let state = QueueListState(tracks: six, currentIndex: 1, isPlaying: false)
        // upNext = [c, d, e, f]; move {0, 1} (c, d) to the end (destination 4
        // in pre-removal List coordinates) → [e, f, c, d]
        let moved = state.movingUpNext(from: IndexSet(integersIn: 0..<2), to: 4)
        XCTAssertEqual(moved.tracks.map(\.id), ["a", "b", "e", "f", "c", "d"])
        XCTAssertEqual(moved.current?.id, "b", "the pre-cursor head is untouched")
        XCTAssertEqual(moved.currentIndex, 1)
    }

    func testMoveOnEmptyUpNextIsIdentity() {
        let state = QueueListState(tracks: tracks, currentIndex: 4, isPlaying: true)
        let moved = state.movingUpNext(from: IndexSet(integer: 0), to: 0)
        XCTAssertEqual(moved, state)
    }

    func testMoveWithoutACurrentTrackReordersTheWholeArray() {
        let state = QueueListState(tracks: tracks, currentIndex: -1, isPlaying: false)
        // currentIndex −1: upNext IS the whole array.
        let moved = state.movingUpNext(from: IndexSet(integer: 3), to: 0)
        XCTAssertEqual(moved.tracks.map(\.id), ["d", "a", "b", "c", "e"])
        XCTAssertNil(moved.current)
    }

    // ── removingUpNext / removing ────────────────────────────────────────

    func testSectionLocalRemovalTranslatesIntoTheArrayIndex() {
        let state = playingAt2
        // Section row 0 = full-array index 3 (d).
        let removed = state.removingUpNext(at: 0)
        XCTAssertEqual(removed.tracks.map(\.id), ["a", "b", "c", "e"])
        XCTAssertEqual(removed.currentIndex, 2)
        XCTAssertEqual(removed.current?.id, "c")
    }

    func testRemovingBeforeTheCursorShiftsItLeft() {
        let removed = playingAt2.removing(at: 0)
        XCTAssertEqual(removed.tracks.map(\.id), ["b", "c", "d", "e"])
        XCTAssertEqual(removed.currentIndex, 1)
        XCTAssertEqual(removed.current?.id, "c")
    }

    func testRemovingTheCurrentTrackAdvancesToTheNext() {
        let removed = playingAt2.removing(at: 2)
        XCTAssertEqual(removed.tracks.map(\.id), ["a", "b", "d", "e"])
        XCTAssertEqual(removed.currentIndex, 2)
        XCTAssertEqual(removed.current?.id, "d", "engine skip semantics: the next track takes over")
    }

    func testRemovingTheCurrentTrackAtTheTailClampsTheCursor() {
        let state = QueueListState(tracks: tracks, currentIndex: 4, isPlaying: true)
        let removed = state.removing(at: 4)
        XCTAssertEqual(removed.tracks.map(\.id), ["a", "b", "c", "d"])
        XCTAssertEqual(removed.currentIndex, 3)
        XCTAssertEqual(removed.current?.id, "d")
    }

    func testRemovingAfterTheCursorIsIndexNeutral() {
        let removed = playingAt2.removing(at: 3)
        XCTAssertEqual(removed.tracks.map(\.id), ["a", "b", "c", "e"])
        XCTAssertEqual(removed.currentIndex, 2)
    }

    func testRemovingOutOfBoundsIsIdentity() {
        XCTAssertEqual(playingAt2.removing(at: 9), playingAt2)
        XCTAssertEqual(playingAt2.removing(at: -1), playingAt2)
    }
}
