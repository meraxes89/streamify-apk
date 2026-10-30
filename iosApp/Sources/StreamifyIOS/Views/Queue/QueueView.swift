import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2-06 — the native queue surface with drag-and-drop reordering.
//
// The QueueScreen/QueueList port: a native SwiftUI List — `.onMove` drag
// reordering with the `.queueGrab` haptic, swipe-to-delete with the boundary
// tick, and the AUTO-MIX "infinite radio" switch (Android's
// OnlineRadioEngine toggle — when the tail is reached, the engine keeps
// seeding candidates; here it is a value the shell owns and lowers onto the
// engine's radio plane).
//
// DATA-SOURCE DISCIPLINE (unchanged from the Phase-5 scaffold): the engine
// pushes playback SESSION snapshots — it does not own the play queue. The
// host lowers its queue truth onto [QueueListState]; previews use the
// deterministic catalog fixtures.
//
// The Phase-5 types [QueueEntry] / [QueueListState] move here VERBATIM (the
// existing XCTest suites reference them) plus pure reorder/remove helpers
// the tests can verify without a device.
// ─────────────────────────────────────────────────────────────────────────────

/// One indexed queue row (indices are host queue indices — tap callbacks
/// report them back so the host can lower play-at-index onto its surface).
public struct QueueEntry: Equatable, Sendable {
    public let index: Int
    public let track: PresentationTrack

    public init(index: Int, track: PresentationTrack) {
        self.index = index
        self.track = track
    }
}

/// The queue projection the view renders (Swift mirror of the shared
/// `QueueListState`) + the SW-ENG2-06 mutation helpers.
public struct QueueListState: Equatable, Sendable {
    public let tracks: [PresentationTrack]
    public let currentIndex: Int
    public let isPlaying: Bool

    public init(tracks: [PresentationTrack] = [], currentIndex: Int = -1, isPlaying: Bool = false) {
        self.tracks = tracks
        self.currentIndex = currentIndex
        self.isPlaying = isPlaying
    }

    public var current: PresentationTrack? {
        tracks.indices.contains(currentIndex) ? tracks[currentIndex] : nil
    }

    public var upNext: [QueueEntry] {
        tracks.enumerated()
            .filter { $0.offset > currentIndex }
            .map { QueueEntry(index: $0.offset, track: $0.element) }
    }

    public var played: [QueueEntry] {
        guard currentIndex >= 0 else { return [] }
        return tracks.enumerated()
            .filter { $0.offset < currentIndex }
            .map { QueueEntry(index: $0.offset, track: $0.element) }
    }

    public static let empty = QueueListState()

    // ─ SW-ENG2-06: pure reorder / removal projections ────────────────────

    /// Reorder projection for `.onMove`. List reports `IndexSet → Int` in
    /// SECTION-LOCAL coordinates (the "Up next" ForEach iterates the
    /// `upNext` projection, not the full array); this helper performs the
    /// move on the projection and rebuilds the full array around the
    /// now-playing track, which stays pinned to its track.
    public func movingUpNext(from offsets: IndexSet, to destination: Int) -> QueueListState {
        var upcoming = upNext.map(\.track)
        guard !upcoming.isEmpty else { return self }
        upcoming.move(fromOffsets: offsets, toOffset: destination)

        var rebuilt: [PresentationTrack] = []
        if currentIndex >= 0 {
            rebuilt.append(contentsOf: tracks[0...currentIndex])
        }
        rebuilt.append(contentsOf: upcoming)

        let newCurrent = current.map { track in
            rebuilt.firstIndex(where: { $0.id == track.id }) ?? currentIndex
        }
        return QueueListState(
            tracks: rebuilt,
            currentIndex: newCurrent ?? currentIndex,
            isPlaying: isPlaying
        )
    }

    /// Swipe-to-delete projection for a SECTION-LOCAL index (a row of the
    /// "Up next" list): removing an entry before the current one is
    /// impossible (the section starts after it); removing past it keeps the
    /// cursor stable.
    public func removingUpNext(at sectionIndex: Int) -> QueueListState {
        let fullIndex = sectionIndex + max(currentIndex + 1, 0)
        return removing(at: fullIndex)
    }

    /// Full-array removal: removing an index before the current one shifts
    /// the cursor left; removing the CURRENT track advances to the next
    /// (the engine's skip semantics); removing past it is index-neutral.
    public func removing(at index: Int) -> QueueListState {
        guard tracks.indices.contains(index) else { return self }
        var updated = tracks
        updated.remove(at: index)
        var newIndex = currentIndex
        if index < currentIndex {
            newIndex = currentIndex - 1
        } else if index == currentIndex {
            newIndex = min(currentIndex, updated.count - 1)
        }
        return QueueListState(tracks: updated, currentIndex: newIndex, isPlaying: isPlaying)
    }
}

// MARK: - The screen

public struct QueueView: View {

    @Binding public var state: QueueListState
    /// Auto-mix "infinite radio": keep seeding beyond the queue tail.
    @Binding public var autoMix: Bool
    public var onTrackClick: ((Int) -> Void)? = nil
    /// Section-local coordinates over the "Up next" rows — apply with
    /// `QueueListState.movingUpNext(from:to:)`.
    public var onReorder: ((IndexSet, Int) -> Void)? = nil
    /// Section-local index of a swiped "Up next" row — apply with
    /// `QueueListState.removingUpNext(at:)`.
    public var onDelete: ((Int) -> Void)? = nil

    public init(
        state: Binding<QueueListState>,
        autoMix: Binding<Bool>,
        onTrackClick: ((Int) -> Void)? = nil,
        onReorder: ((IndexSet, Int) -> Void)? = nil,
        onDelete: ((Int) -> Void)? = nil
    ) {
        _state = state
        _autoMix = autoMix
        self.onTrackClick = onTrackClick
        self.onReorder = onReorder
        self.onDelete = onDelete
    }

    public var body: some View {
        Group {
            if state.tracks.isEmpty {
                emptyState
            } else {
                list
            }
        }
        .background(StreamifyColors.bgBase)
        .navigationTitle("Queue")
#if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
#endif
    }

    private var list: some View {
        List {
            Section {
                YtQueueHeader(
                    title: state.current == nil ? "Queue" : "Up next",
                    sourceName: state.current?.artist ?? "",
                    isShuffled: .constant(false),
                    isInfiniteRadio: $autoMix
                )
                .listRowBackground(Color.clear)
                .listRowSeparator(.hidden)
                .listRowInsets(EdgeInsets())
            }

            if let current = state.current {
                Section("Now playing") {
                    QueueRow(
                        track: current,
                        isCurrent: true,
                        isPlaying: state.isPlaying,
                        onTap: onTrackClick.map { callback in
                            { callback(state.currentIndex) }
                        }
                    )
                    .listRowBackground(StreamifyColors.bgCard)
                    .swipeActions(edge: .trailing, allowsFullSwipe: false) {}
                }
            }

            Section("Up next") {
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
                    .listRowBackground(StreamifyColors.bgCard)
                    .swipeActions(edge: .trailing) {
                        Button(role: .destructive) {
                            Haptics.play(.queueBoundaryTick)
                            onDelete?(entry.index - max(state.currentIndex + 1, 0))
                        } label: {
                            Label("Remove", systemImage: StreamifyIcon.trash)
                        }
                    }
                }
                .onMove { offsets, destination in
                    Haptics.play(.queueGrab)
                    onReorder?(offsets, destination)
                }
                .onDelete { offsets in
                    // Section-local offsets — reversed so earlier indices in
                    // the batch stay valid while the batch lands.
                    for offset in offsets.reversed() {
                        Haptics.play(.queueBoundaryTick)
                        onDelete?(offset)
                    }
                }

                if autoMix {
                    Label {
                        Text("Auto-mix keeps seeding the radio past this point")
                            .font(StreamifyType.caption)
                            .foregroundStyle(StreamifyColors.textTertiary)
                    } icon: {
                        Image(systemName: StreamifyIcon.radio)
                            .foregroundStyle(StreamifyColors.accentBlue)
                    }
                    .listRowBackground(Color.clear)
                    .listRowSeparator(.hidden)
                }
            }
        }
#if os(iOS)
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .environment(\.editMode, .constant(.inactive))
#else
        .listStyle(.inset)
        .scrollContentBackground(.hidden)
#endif
    }

    private var emptyState: some View {
        EmptyStateView(
            symbol: StreamifyIcon.queue,
            title: "Queue is empty",
            message: "The queue lights up once a platform queue source is connected — the Rust core pushes session snapshots, not queues."
        )
    }
}

// MARK: - Row

struct QueueRow: View {
    let track: PresentationTrack
    let isCurrent: Bool
    let isPlaying: Bool
    var index: Int? = nil
    var onTap: (() -> Void)? = nil

    var body: some View {
        Button {
            onTap?()
        } label: {
            HStack(spacing: StreamifyDimens.spaceMD) {
                if let index {
                    Text("\(index + 1)")
                        .font(StreamifyType.caption.monospacedDigit())
                        .foregroundStyle(StreamifyColors.textTertiary)
                        .frame(width: 24, alignment: .trailing)
                }

                ZStack {
                    Circle()
                        .fill(StreamifyColors.bgChipInactive)
                    Image(systemName: isCurrent && isPlaying ? StreamifyIcon.wave : StreamifyIcon.musicNote)
                        .font(.system(size: 13))
                        .foregroundStyle(
                            isCurrent && isPlaying
                                ? StreamifyColors.primary
                                : StreamifyColors.textTertiary
                        )
                }
                .frame(width: 34, height: 34)

                VStack(alignment: .leading, spacing: 2) {
                    Text(track.title)
                        .font(isCurrent ? StreamifyType.songTitle : StreamifyType.bodyLarge)
                        .foregroundStyle(
                            isCurrent ? StreamifyColors.textMain : StreamifyColors.textMain
                        )
                        .lineLimit(1)
                    Text(track.artist)
                        .font(StreamifyType.songArtist)
                        .foregroundStyle(isCurrent ? StreamifyColors.textSecondary : StreamifyColors.textTertiary)
                        .lineLimit(1)
                }

                Spacer(minLength: 8)

                if let durationMs = track.durationMs {
                    Text(PlaybackFormatting.formatPlaybackTime(durationMs))
                        .font(StreamifyType.caption.monospacedDigit())
                        .foregroundStyle(StreamifyColors.textTertiary)
                }

                Image(systemName: "line.3.horizontal")
                    .font(.system(size: 12))
                    .foregroundStyle(StreamifyColors.textTertiary)
                    .opacity(isCurrent ? 0 : 1)
            }
            .padding(.vertical, 2)
        }
        .buttonStyle(.plain)
    }
}

#Preview("Reorderable queue") {
    NavigationStack {
        QueueView(
            state: .constant(PreviewCatalog.queueState),
            autoMix: .constant(true),
            onTrackClick: { _ in },
            onReorder: { _, _ in },
            onDelete: { _ in }
        )
    }
    .preferredColorScheme(.dark)
}

#Preview("Empty") {
    NavigationStack {
        QueueView(state: .constant(.empty), autoMix: .constant(false))
    }
    .preferredColorScheme(.dark)
}
