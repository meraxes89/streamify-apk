import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// Phase 5 (MP-UI01 / Beta): the native queue surface — the SwiftUI
// counterpart of the shared CMP `QueueList` component.
//
// DATA-SOURCE DISCIPLINE (same as the shared component): the engine pushes
// playback SESSION snapshots — it does not own the play queue. The host
// lowers its platform queue truth onto [QueueListState]; with no queue
// source connected yet, the view renders the honest empty state, never a
// fabricated list.
// ─────────────────────────────────────────────────────────────────────────────

/// One indexed queue row (indices are host queue indices — tap callbacks
/// report them back so the host can lower play-at-index onto its surface).
struct QueueEntry: Equatable, Sendable {
    let index: Int
    let track: PresentationTrack
}

/// The queue projection the view renders (Swift mirror of the shared
/// `QueueListState`).
struct QueueListState: Equatable, Sendable {
    let tracks: [PresentationTrack]
    let currentIndex: Int
    let isPlaying: Bool

    init(tracks: [PresentationTrack] = [], currentIndex: Int = -1, isPlaying: Bool = false) {
        self.tracks = tracks
        self.currentIndex = currentIndex
        self.isPlaying = isPlaying
    }

    var current: PresentationTrack? {
        tracks.indices.contains(currentIndex) ? tracks[currentIndex] : nil
    }

    var upNext: [QueueEntry] {
        tracks.enumerated()
            .filter { $0.offset > currentIndex }
            .map { QueueEntry(index: $0.offset, track: $0.element) }
    }

    var played: [QueueEntry] {
        guard currentIndex >= 0 else { return [] }
        return tracks.enumerated()
            .filter { $0.offset < currentIndex }
            .map { QueueEntry(index: $0.offset, track: $0.element) }
    }

    static let empty = QueueListState()
}

/// The "Now playing / Up next" list — presented as a sheet from the
/// now-playing surface.
struct QueueView: View {
    let state: QueueListState
    var onTrackClick: ((Int) -> Void)? = nil

    var body: some View {
        Group {
            if state.tracks.isEmpty {
                emptyState
            } else {
                list
            }
        }
        .background(StreamifyTheme.bgBase)
        .navigationTitle("Queue")
        .navigationBarTitleDisplayMode(.inline)
    }

    private var list: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 2) {
                if let current = state.current {
                    sectionLabel("Now playing")
                    QueueRow(
                        track: current,
                        isCurrent: true,
                        isPlaying: state.isPlaying,
                        onTap: onTrackClick.map { callback in
                            { callback(state.currentIndex) }
                        }
                    )
                }
                if !state.upNext.isEmpty {
                    sectionLabel("Up next")
                    ForEach(state.upNext, id: \.index) { entry in
                        QueueRow(
                            track: entry.track,
                            isCurrent: false,
                            isPlaying: false,
                            index: entry.index,
                            onTap: onTrackClick.map { callback in
                                { callback(entry.index) }
                            }
                        )
                    }
                }
            }
            .padding(.vertical, 8)
        }
    }

    private func sectionLabel(_ text: String) -> some View {
        HStack(spacing: 6) {
            Image(systemName: "music.note.list")
                .font(.system(size: 11))
                .foregroundColor(StreamifyTheme.textTertiary)
            Text(text)
                .font(.system(size: 11, weight: .semibold))
                .foregroundColor(StreamifyTheme.textTertiary)
        }
        .padding(.horizontal, 20)
        .padding(.top, 10)
    }

    private var emptyState: some View {
        VStack(spacing: 12) {
            Spacer()
            Image(systemName: "music.note.list")
                .font(.system(size: 34))
                .foregroundColor(StreamifyTheme.textTertiary)
            Text("The queue lights up once a platform queue source is connected — the Rust core pushes session snapshots, not queues.")
                .font(.system(size: 12))
                .foregroundColor(StreamifyTheme.textSecondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 40)
            Spacer()
        }
    }
}

/// One queue row (the SwiftUI counterpart of the shared `TrackListItem`).
private struct QueueRow: View {
    let track: PresentationTrack
    let isCurrent: Bool
    let isPlaying: Bool
    var index: Int? = nil
    var onTap: (() -> Void)? = nil

    var body: some View {
        Button {
            onTap?()
        } label: {
            HStack(spacing: 12) {
                if let index {
                    Text("\(index + 1)")
                        .font(.system(size: 11).monospacedDigit())
                        .foregroundColor(StreamifyTheme.textTertiary)
                        .frame(width: 24, alignment: .trailing)
                }

                ZStack {
                    Circle()
                        .fill(StreamifyTheme.bgCard)
                    Image(systemName: isCurrent && isPlaying ? "waveform" : "music.note")
                        .font(.system(size: 13))
                        .foregroundColor(
                            isCurrent && isPlaying
                                ? StreamifyTheme.primary
                                : StreamifyTheme.textTertiary
                        )
                }
                .frame(width: 34, height: 34)

                VStack(alignment: .leading, spacing: 2) {
                    Text(track.title)
                        .font(.system(size: 14, weight: isCurrent ? .semibold : .regular))
                        .foregroundColor(isCurrent ? StreamifyTheme.textMain : StreamifyTheme.textMain)
                        .lineLimit(1)
                    Text(track.artist)
                        .font(.system(size: 11))
                        .foregroundColor(isCurrent ? StreamifyTheme.textSecondary : StreamifyTheme.textTertiary)
                        .lineLimit(1)
                }

                Spacer(minLength: 8)

                if let durationMs = track.durationMs {
                    Text(PlaybackFormatting.formatPlaybackTime(durationMs))
                        .font(.system(size: 11).monospacedDigit())
                        .foregroundColor(StreamifyTheme.textTertiary)
                }
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 8)
        }
        .buttonStyle(.plain)
    }
}
