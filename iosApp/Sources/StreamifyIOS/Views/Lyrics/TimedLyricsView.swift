import SwiftUI
import Observation

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG4-03 (Engineer 4): the synced karaoke lyrics surface — the SwiftUI
// counterpart of `LyricsScreen.kt` + the `LyricPlaybackController` VSYNC loop.
//
// FRAME DISCIPLINE (120 Hz without whole-surface redraws): the per-frame
// cursor evaluation — active line index, syllable fills — happens INSIDE
// display-synced `TimelineView(.animation)` leaves against the pushed DAC
// clock (PlaybackViewModel.progress). Exactly TWO timeline leaves exist at
// any moment: a zero-size cursor tracker and the ACTIVE line's karaoke
// fill. Observable state writes happen ONLY on line boundaries (the
// auto-scroll anchor) — there is no Timer, no sleep loop, no engine getter
// anywhere in this file.
//
// AUTO-SCROLL: spring-interpolated `scrollTo(id, anchor: .center)` on every
// active-line change (response 0.45, damping 0.8 — the Compose
// spring(0.8f, 500f) cadence). Karaoke lines fill syllable-by-syllable via
// enhanced-LRC word timestamps; +/- nudges shift the user sync offset
// (persisted per track by LyricPacerService).
// ─────────────────────────────────────────────────────────────────────────────

@MainActor
struct TimedLyricsView: View {
    @Environment(PlaybackViewModel.self) private var playback
    @State private var lyricsModel: LyricsViewModel?

    var body: some View {
        NavigationStack {
            Group {
                if let lyricsModel {
                    LyricsSurface(
                        lyricsModel: lyricsModel,
                        progress: playback.progress
                    )
                } else {
                    emptyState
                }
            }
            .background(StreamifyTheme.bgBase)
            .navigationTitle("Lyrics")
            .navigationBarTitleDisplayMode(.inline)
        }
        .onAppear {
            if lyricsModel == nil {
                lyricsModel = LyricsViewModel()
            }
            lyricsModel?.loadLyrics(for: playback.currentTrack)
        }
    }

    private var emptyState: some View {
        VStack(spacing: 12) {
            Spacer()
            Image(systemName: "text.quote")
                .font(.system(size: 34))
                .foregroundColor(StreamifyTheme.textTertiary)
            Text("Play a track with synced lyrics and they'll light up here — paced by the hardware presentation clock.")
                .font(.system(size: 12))
                .foregroundColor(StreamifyTheme.textSecondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 40)
            Spacer()
        }
    }
}

// MARK: - The scrollable lyrics surface

@MainActor
private struct LyricsSurface: View {
    let lyricsModel: LyricsViewModel
    let progress: PlaybackProgressClock
    @State private var activeLineIndex = -1

    var body: some View {
        Group {
            if lyricsModel.pacer.hasLyrics {
                lyricsList
            } else {
                LoadingOrMissingState(lyricsModel: lyricsModel)
            }
        }
        .safeAreaInset(edge: .bottom) { offsetBar }
    }

    private var lyricsList: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 22) {
                    // Top inset so the first active line can center.
                    Color.clear.frame(height: 140)

                    ForEach(Array(lyricsModel.pacer.document.lines.enumerated()), id: \.offset) { index, line in
                        LyricLineView(
                            line: line,
                            isActive: index == activeLineIndex,
                            isSynced: lyricsModel.isSynced,
                            positionReader: { cursorPosition() },
                            userOffset: lyricsModel.pacer.userOffsetMs
                        )
                        .id(index)
                    }

                    Color.clear.frame(height: 180)
                }
                .padding(.horizontal, 24)
            }
            .background(cursorTracker(proxy: proxy))
        }
    }

    /// The zero-size cursor tracker: evaluates the active line on every
    /// display frame (pushed DAC clock) and writes state ONLY when the line
    /// index actually changes, triggering the spring auto-scroll.
    private func cursorTracker(proxy: ScrollViewProxy) -> some View {
        TimelineView(.animation) { _ in
            let newIndex = effectiveIndex()
            Color.clear
                .frame(height: 0)
                .onChange(of: newIndex) { _, new in
                    guard new != activeLineIndex else { return }
                    activeLineIndex = new
                    guard new >= 0, lyricsModel.pacer.document.lines.indices.contains(new) else { return }
                    withAnimation(.spring(response: 0.45, dampingFraction: 0.8)) {
                        proxy.scrollTo(new, anchor: .center)
                    }
                }
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }

    private func cursorPosition() -> Int {
        progress.positionMs(atUptimeNs: PlaybackViewModel.hostUptimeNs) ?? 0
    }

    private func effectiveIndex() -> Int {
        let position = cursorPosition() + lyricsModel.pacer.userOffsetMs
        return lyricsModel.pacer.document.activeLineIndex(atMs: position)
    }

    private var offsetBar: some View {
        HStack(spacing: 14) {
            Button {
                lyricsModel.nudgeOffsetBackward()
            } label: {
                Image(systemName: "minus.circle")
            }
            Text(offsetLabel)
                .font(.system(size: 11).monospacedDigit())
                .foregroundColor(StreamifyTheme.textSecondary)
            Button {
                lyricsModel.nudgeOffsetForward()
            } label: {
                Image(systemName: "plus.circle")
            }
            Divider().frame(height: 14)
            Button("Reset") {
                lyricsModel.resetOffset()
            }
            .font(.system(size: 11))
        }
        .buttonStyle(.plain)
        .foregroundColor(StreamifyTheme.textSecondary)
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .background(.ultraThinMaterial, in: Capsule())
        .padding(.bottom, 8)
    }

    private var offsetLabel: String {
        let ms = lyricsModel.pacer.userOffsetMs
        return ms == 0 ? "sync on beat" : String(format: "%+d ms", ms)
    }
}

private struct LoadingOrMissingState: View {
    let lyricsModel: LyricsViewModel

    var body: some View {
        VStack(spacing: 12) {
            Spacer()
            if lyricsModel.isLoading {
                ProgressView().tint(StreamifyTheme.textMain)
                Text("Syncing lyrics…")
                    .font(.system(size: 12))
                    .foregroundColor(StreamifyTheme.textSecondary)
            } else {
                Image(systemName: "music.quarternote.3")
                    .font(.system(size: 30))
                    .foregroundColor(StreamifyTheme.textTertiary)
                Text(lyricsModel.lastError ?? "No lyrics for this track")
                    .font(.system(size: 12))
                    .foregroundColor(StreamifyTheme.textSecondary)
            }
            Spacer()
        }
    }
}

// MARK: - One line (static when inactive; per-frame fill when active)

private struct LyricLineView: View {
    let line: LyricLine
    let isActive: Bool
    let isSynced: Bool
    /// Reads the pushed DAC clock position at display-frame cadence.
    let positionReader: () -> Int
    let userOffset: Int

    var body: some View {
        Group {
            if isActive && isSynced {
                // The ACTIVE line is the only per-frame timeline leaf.
                TimelineView(.animation) { _ in
                    let effective = positionReader() + userOffset
                    if line.isKaraoke {
                        KaraokeLineView(line: line, positionMs: effective)
                    } else {
                        PlainLineFillView(line: line, positionMs: effective)
                    }
                }
            } else {
                Text(line.text)
                    .font(.system(size: 22, weight: .regular))
                    .foregroundColor(
                        isSynced
                            ? StreamifyTheme.textTertiary
                            : StreamifyTheme.textSecondary
                    )
            }
        }
        .animation(.spring(response: 0.4, dampingFraction: 0.85), value: isActive)
    }
}

/// Whole-line wipe fill for synced lines without word timing.
private struct PlainLineFillView: View {
    let line: LyricLine
    let positionMs: Int

    private var fraction: Double {
        guard line.durationMs > 0 else { return positionMs >= line.timeMs ? 1 : 0 }
        let clamped = min(max(Double(positionMs - line.timeMs), 0), Double(line.durationMs))
        return clamped / Double(line.durationMs)
    }

    var body: some View {
        ZStack(alignment: .leading) {
            Text(line.text)
                .foregroundColor(StreamifyTheme.textTertiary)
            Text(line.text)
                .foregroundColor(StreamifyTheme.textMain)
                .mask(GeometryReader { geo in
                    Rectangle().frame(width: geo.size.width * fraction)
                })
        }
        .font(.system(size: 22, weight: .semibold))
    }
}

/// Syllable-by-syllable karaoke fill (enhanced-LRC word timing).
private struct KaraokeLineView: View {
    let line: LyricLine
    let positionMs: Int

    var body: some View {
        FlowLayout(spacing: 4) {
            ForEach(Array(line.syllables.enumerated()), id: \.offset) { _, syllable in
                let fraction = syllable.fillFraction(atMs: positionMs)
                ZStack(alignment: .leading) {
                    Text(syllable.text)
                        .foregroundColor(StreamifyTheme.textTertiary)
                    Text(syllable.text)
                        .foregroundColor(StreamifyTheme.primary)
                        .mask(GeometryReader { geo in
                            Rectangle().frame(width: geo.size.width * fraction)
                        })
                }
                .font(.system(size: 22, weight: .semibold))
            }
        }
    }
}

// MARK: - Flow layout (syllable wrapping)

/// A minimal wrapping layout for syllable runs (SwiftUI `Layout` protocol).
struct FlowLayout: Layout {
    var spacing: CGFloat = 4

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let maxWidth = proposal.width ?? .infinity
        var x: CGFloat = 0
        var y: CGFloat = 0
        var rowHeight: CGFloat = 0
        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            if x > 0, x + size.width > maxWidth {
                x = 0
                y += rowHeight + spacing
                rowHeight = 0
            }
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
        }
        return CGSize(width: proposal.width ?? x, height: y + rowHeight)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let maxWidth = bounds.width
        var x = bounds.minX
        var y = bounds.minY
        var rowHeight: CGFloat = 0
        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            if x > bounds.minX, x + size.width - bounds.minX > maxWidth {
                x = bounds.minX
                y += rowHeight + spacing
                rowHeight = 0
            }
            subview.place(
                at: CGPoint(x: x, y: y),
                anchor: .topLeading,
                proposal: ProposedViewSize(size)
            )
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
        }
    }
}
