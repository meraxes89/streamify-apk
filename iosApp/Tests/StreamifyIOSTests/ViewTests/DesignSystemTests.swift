import SwiftUI
import XCTest
@testable import StreamifyIOS

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2-01 — the design system's headless gates: token parity with the
// Android reference (ui/theme/Dimens.kt, Shape.kt), gradient determinism,
// UI-currency mapping invariants and the preview catalog's determinism.
// ─────────────────────────────────────────────────────────────────────────────

final class DesignTokenTests: XCTestCase {

    func testLayoutDimensMatchTheAndroidReference() {
        XCTAssertEqual(StreamifyDimens.bottomNavHeight, 56)
        XCTAssertEqual(StreamifyDimens.miniPlayerHeight, 64)
        XCTAssertEqual(StreamifyDimens.miniPlayerArt, 48)
        XCTAssertEqual(StreamifyDimens.trackRowHeight, 56)
        XCTAssertEqual(StreamifyDimens.trackRowArt, 48)
        XCTAssertEqual(StreamifyDimens.filterChipHeight, 32)
        XCTAssertEqual(StreamifyDimens.cardWidth, 150)
        XCTAssertEqual(StreamifyDimens.playButtonSize, 64)
        XCTAssertEqual(StreamifyDimens.seekBarHeight, 4)
        XCTAssertEqual(StreamifyDimens.seekBarThumb, 12)
        XCTAssertEqual(StreamifyDimens.searchBarHeight, 48)
        XCTAssertEqual(StreamifyDimens.fullPlayerArtSize, 340)
        XCTAssertEqual(StreamifyDimens.largeScreenMaxWidth, 1100)
    }

    func testSpacingAndRadiiMatchTheAndroidReference() {
        XCTAssertEqual(StreamifyDimens.spaceXXS, 2)
        XCTAssertEqual(StreamifyDimens.spaceXS, 4)
        XCTAssertEqual(StreamifyDimens.spaceSM, 8)
        XCTAssertEqual(StreamifyDimens.spaceMD, 12)
        XCTAssertEqual(StreamifyDimens.spaceLG, 16)
        XCTAssertEqual(StreamifyDimens.spaceXL, 20)
        XCTAssertEqual(StreamifyDimens.spaceXXL, 24)
        XCTAssertEqual(StreamifyDimens.spaceHuge, 32)
        XCTAssertEqual(StreamifyDimens.spaceGiant, 48)

        XCTAssertEqual(StreamifyRadius.sm, 4)
        XCTAssertEqual(StreamifyRadius.md, 8)
        XCTAssertEqual(StreamifyRadius.lg, 12)
        XCTAssertEqual(StreamifyRadius.xl, 16)
        XCTAssertEqual(StreamifyRadius.full, 50)
    }

    func testGradientPresetTableHasTheNineFamilies() {
        XCTAssertEqual(StreamifyGradients.all.count, 9)
        XCTAssertTrue(StreamifyGradients.all.allSatisfy { $0.count == 2 })
    }

    func testGradientPresetsAreDeterministicPerSeed() {
        let first = StreamifyGradients.preset(forSeed: "Obsidian Sunrise")
        let second = StreamifyGradients.preset(forSeed: "Obsidian Sunrise")
        XCTAssertEqual(first, second, "same seed must always paint the same gradient")

        XCTAssertEqual(first.count, 2)
    }

    func testStaggerDelayIsCapped() {
        XCTAssertEqual(Motion.staggerDelay(0), 0)
        XCTAssertEqual(Motion.staggerDelay(3), 3 * 0.03)
        XCTAssertEqual(Motion.staggerDelay(100), Motion.staggerDelay(10), "capped at index 10")
        XCTAssertEqual(Motion.staggerDelay(-5), 0, "negative indices clamp to zero")
    }
}

final class UiCurrencyTests: XCTestCase {

    func testShelfTrackMapsOntoThePresentationTrack() {
        let track = VirtualShelfTrack(
            id: "t-1",
            title: "Bit-Perfect Rain",
            artist: "Aurora Nine",
            durationSec: 198,
            isLiked: true,
            platformOrigin: "ytm"
        )
        let presentation = track.presentationTrack
        XCTAssertEqual(presentation.id, "t-1")
        XCTAssertEqual(presentation.title, "Bit-Perfect Rain")
        XCTAssertEqual(presentation.artist, "Aurora Nine")
        XCTAssertEqual(presentation.durationMs, 198_000)
    }

    func testSearchResultIdentityIsItsUrl() {
        let result = OnlineSearchResult(
            title: "Aurora Nine",
            uploader: "Official",
            url: "https://music.example/artist/aurora-nine",
            durationSec: 0,
            type: .artist
        )
        XCTAssertEqual(result.id, result.url)
    }

    func testEqualizerBandFrequencyLabel() {
        // The canonical band type is Engineer 4's (PresentationModels).
        XCTAssertEqual(EqualizerBand(index: 0, centerHz: 60, gainDb: 0, q: 1).frequencyLabel, "60")
        XCTAssertEqual(EqualizerBand(index: 4, centerHz: 16_000, gainDb: 0, q: 1).frequencyLabel, "16k")
    }

    func testDspPreferencesDefaultsMatchTheAudiophileContract() {
        let dsp = DspPreferences()
        XCTAssertTrue(dsp.loudnessNormalization)
        XCTAssertEqual(dsp.targetLufs, -14.0)
        XCTAssertTrue(dsp.gaplessPlayback)
        XCTAssertEqual(dsp.crossfadeSeconds, 0)
        XCTAssertFalse(dsp.monoOutput)
        XCTAssertEqual(dsp.stereoBalance, 0)
        XCTAssertEqual(dsp.truePeakDb, -1.0)
    }

    func testAmbientPaletteResolvesItsHexTokens() {
        let palette = AmbientPalette(dominantHex: 0x212121, accentHex: 0xFF0033, darkMutedHex: 0x0A0A0A)
        XCTAssertEqual(palette.dominant, Color(streamifyHex: 0x212121))
        XCTAssertEqual(palette.accent, Color(streamifyHex: 0xFF0033))
        XCTAssertEqual(palette.darkMuted, Color(streamifyHex: 0x0A0A0A))
        XCTAssertEqual(AmbientPalette.obsidian.accentHex, 0xFF0033)
    }

    func testDaylistHeroGradientUsesItsModelHexes() {
        let hero = DaylistHeroModel(
            title: "Tuesday morning",
            subtitle: "Slow starts",
            bucketLabel: "Morning drive",
            trackTitles: ["a", "b"],
            trackCount: 2,
            gradientStartHex: 0xFF667EEA,
            gradientEndHex: 0xFFF6D365
        )
        XCTAssertEqual(hero.gradient, [
            Color(streamifyHex: 0xFF667EEA), Color(streamifyHex: 0xFFF6D365)
        ])
    }
}

final class PreviewCatalogDeterminismTests: XCTestCase {

    func testPerformanceScrollFixtureHas200Tracks() {
        XCTAssertEqual(PreviewCatalog.performanceTracks.count, 200, "the ProMotion scroll gate fixture")
    }

    func testPaletteDerivationIsDeterministic() {
        let a = PreviewCatalog.palette(forTitle: "Obsidian Sunrise")
        let b = PreviewCatalog.palette(forTitle: "Obsidian Sunrise")
        XCTAssertEqual(a.accentHex, b.accentHex)
        XCTAssertEqual(a.dominantHex, b.dominantHex)

        // And the derivation actually varies across titles (seed-verified:
        // these two land on different accents in the 6-color table).
        let other = PreviewCatalog.palette(forTitle: "48k Meridian")
        XCTAssertNotEqual(a.accentHex, other.accentHex)
    }

    func testQueueFixtureProjection() {
        let state = PreviewCatalog.queueState
        XCTAssertEqual(state.currentIndex, 2)
        XCTAssertNotNil(state.current)
        XCTAssertEqual(state.upNext.count, PreviewCatalog.tracks.count - 3)
        XCTAssertEqual(state.played.count, 2)
        XCTAssertTrue(state.isPlaying)
    }

    func testPlayingSnapshotCarriesTheDacClock() {
        let snapshot = PreviewCatalog.playingSnapshot
        XCTAssertEqual(snapshot.status, .playing)
        XCTAssertEqual(snapshot.clock?.sampleRateHz, 48_000)
        XCTAssertEqual(snapshot.currentTrack?.title, "Obsidian Sunrise")

        let uiState = PlaybackUiState(snapshot)
        XCTAssertEqual(uiState.positionMs, 87_000, "4_176_000 frames @ 48 kHz = 87 s")
        XCTAssertEqual(uiState.progressFraction ?? 0, 87_000.0 / 251_000.0, accuracy: 1e-9)
    }
}
