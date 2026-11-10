import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2-04 / WS-B B2 — Search: the unified local + online search surface.
//
// Native port of ui/screens/SearchScreen.kt: the YtSearchOmnibar becomes the
// mandated native `.searchable` integration (navigation-bar drawer) and the
// result stack ports 1:1: recents chips, YtSearchFilterChips type filtering,
// the YtTopResultCard top result, "Songs" TrackListItem rows and online
// result rows.
//
// WS-B B2 (de-fixtured production path): the view OWNS its SearchViewModel —
// `.task(id: query)` (task-id semantics cancel the previous keystroke's
// task) drives `submit(query:)`, whose 300 ms structured-concurrency debounce
// lowers the surviving query onto `searchTracks` over the LIVE
// FeatureLibrary lane (UniFFI when the xcframework is linked, preview
// otherwise). Results arrive as SearchRows and bridge onto the row
// components' currency (VirtualShelfTrack / OnlineSearchResult) at render
// time; the shell only routes taps (play / push destinations) through the
// two callbacks.
//
// SW-ENG2-04 history: this screen previously received PreviewCatalog props
// (recentSearches / localResults / onlineResults) and slept 300 ms inside
// the view before lowering onto onSearch — that fixture path is gone; only
// component #Previews still touch PreviewCatalog.
// ─────────────────────────────────────────────────────────────────────────────

@MainActor
public struct SearchView: View {

    // MARK: Callbacks out — the shell owns push and the play command
    //
    // The two members below stay INTERNAL on purpose: SearchRow is the
    // module-internal resolver currency, and a public member cannot be
    // defined in terms of a less-visible type. Only the same-module shell
    // constructs this view, so nothing outside the module needs them.

    /// A tapped song row: the shell resolves and plays it.
    var onPlayTrack: (SearchRow) -> Void = { _ in }
    /// A tapped non-song result (video / artist / album / playlist): the
    /// shell routes it to its destination or plays it.
    var onOpenResult: (SearchRow) -> Void = { _ in }

    // The owned view model (WS-B B2): bare construction rides the live lane.
    @State private var model = SearchViewModel()

    // Ephemeral UI state: the live query + the active type filter (nil = All).
    @State private var query = ""
    @State private var filter: SearchResultType?

    // Same-module entry point (the shell's construction site); internal
    // for the same access-level reason as the callbacks above.
    init(
        onPlayTrack: @escaping (SearchRow) -> Void = { _ in },
        onOpenResult: @escaping (SearchRow) -> Void = { _ in }
    ) {
        self.onPlayTrack = onPlayTrack
        self.onOpenResult = onOpenResult
    }

    public var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: StreamifyDimens.spaceLG) {
                if query.isEmpty {
                    recentsSection
                } else {
                    resultsSection
                }
            }
            .padding(.vertical, StreamifyDimens.spaceMD)
        }
        .background(StreamifyColors.bgBase)
        .navigationTitle("Search")
#if os(iOS)
        // THE native integration — the search field in the navigation bar
        // drawer (macOS has no navigation bar: automatic placement there).
        .searchable(
            text: $query,
            placement: .navigationBarDrawer(displayMode: .automatic),
            prompt: "Search songs, artists, albums…"
        )
#else
        .searchable(text: $query, placement: .automatic, prompt: "Search songs, artists, albums…")
#endif
        .submitLabel(.search)
        .task(id: query) {
            // THE keystroke driver: task-id semantics cancel the previous
            // keystroke's task; the view model debounces 300 ms internally
            // and lowers the surviving query onto searchTracks over the
            // live FFI lane — the view no longer sleeps (WS-B B2).
            model.submit(query: query)
        }
        .onChange(of: filter) { _, selectedFilter in
            // A chip tap re-queries with the new result type over the same
            // debounced lane (the keystroke driver handles query changes).
            model.setFilter(Self.modelFilter(for: selectedFilter))
            model.submit(query: query)
        }
    }

    // MARK: Recent searches (query empty)

    /// The persisted history (newest first, cap 10) lowered onto the chip
    /// currency — the store keeps plain strings, no per-entry timestamps.
    private var recentEntries: [RecentSearchEntry] {
        model.searchHistory.map { RecentSearchEntry(query: $0, timestampMs: 0) }
    }

    @ViewBuilder
    private var recentsSection: some View {
        if recentEntries.isEmpty {
            EmptyStateView(
                symbol: StreamifyIcon.search,
                title: "Find your sound",
                message: "Search songs, artists and albums across your library and YouTube Music."
            )
        } else {
            HStack {
                sectionLabel("Recent searches")
                Spacer(minLength: StreamifyDimens.spaceMD)
                Button(action: { model.clearHistory() }) {
                    Text("Clear all")
                        .font(StreamifyType.chipText)
                        .foregroundStyle(StreamifyColors.primary)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Clear all recent searches")
            }
            .padding(.horizontal, StreamifyDimens.spaceLG)
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 120))], spacing: 8) {
                ForEach(recentEntries) { entry in
                    recentChip(entry)
                }
            }
            .padding(.horizontal, StreamifyDimens.spaceLG)
        }
    }

    /// One recents chip: clock + query + trailing X micro-button (the
    /// Android chip's per-entry clear affordance).
    private func recentChip(_ entry: RecentSearchEntry) -> some View {
        Button {
            // Filling the omnibar is the whole action: the .task(id: query)
            // driver submits the new query through the debounced model.
            query = entry.query
        } label: {
            HStack(spacing: StreamifyDimens.spaceXS) {
                Image(systemName: StreamifyIcon.clock)
                    .font(.system(size: 12))
                    .foregroundStyle(StreamifyColors.textTertiary)
                Text(entry.query)
                    .font(StreamifyType.chipText)
                    .foregroundStyle(StreamifyColors.textMain)
                    .lineLimit(1)
                Button {
                    // The history store is clear-all only (Kotlin parity) —
                    // there is no per-entry removal lane to route through
                    // yet, so the chip's X stays a documented no-op rather
                    // than lying with a fake removal.
                } label: {
                    Image(systemName: StreamifyIcon.close)
                        .font(.system(size: 10, weight: .bold))
                        .foregroundStyle(StreamifyColors.textTertiary)
                        .frame(width: 18, height: 18)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Remove \(entry.query)")
            }
            .padding(.horizontal, StreamifyDimens.spaceMD)
            .frame(height: StreamifyDimens.filterChipHeight)
            .frame(maxWidth: .infinity, alignment: .leading)
            .chipSurface(active: false)
            .contentShape(StreamifyShapes.chip)
        }
        .buttonStyle(.pressableCard)
        .accessibilityLabel("Search for \(entry.query)")
    }

    // MARK: Results (query non-empty)

    @ViewBuilder
    private var resultsSection: some View {
        YtSearchFilterChips(selected: $filter)
        if model.isLoading {
            skeletonRows
        } else if case .error(let message) = model.state {
            EmptyStateView(
                symbol: StreamifyIcon.search,
                title: "Search failed",
                message: message
            )
        } else if filteredResults.isEmpty {
            EmptyStateView(
                symbol: StreamifyIcon.search,
                title: "No results",
                message: "Nothing matched \"\(query)\"."
            )
        } else {
            if let topRow = filteredResults.first {
                YtTopResultCard(result: OnlineSearchResult(row: topRow)) {
                    open(topRow)
                }
            }
            if !songRows.isEmpty {
                sectionLabel("Songs").padding(.horizontal, StreamifyDimens.spaceLG)
                LazyVStack(spacing: StreamifyDimens.spaceXS) {
                    ForEach(songRows) { row in
                        TrackListItem(track: VirtualShelfTrack(presentationTrack: row.asPresentationTrack)) {
                            play(row)
                        }
                        .padding(.horizontal, StreamifyDimens.spaceLG)
                    }
                }
            }
            if !onlineRows.isEmpty {
                sectionLabel("Online").padding(.horizontal, StreamifyDimens.spaceLG)
                LazyVStack(spacing: StreamifyDimens.spaceXS) {
                    ForEach(onlineRows) { row in
                        onlineRow(row)
                    }
                }
            }
        }
    }

    /// The model.isLoading state — redacted placeholder rows.
    private var skeletonRows: some View {
        LazyVStack(spacing: StreamifyDimens.spaceMD) {
            ForEach(0..<5, id: \.self) { _ in
                HStack(spacing: StreamifyDimens.spaceMD) {
                    RoundedRectangle(cornerRadius: StreamifyRadius.md)
                        .fill(StreamifyColors.bgChipInactive)
                        .frame(width: StreamifyDimens.trackRowArt, height: StreamifyDimens.trackRowArt)
                    VStack(alignment: .leading, spacing: StreamifyDimens.spaceSM) {
                        RoundedRectangle(cornerRadius: StreamifyRadius.sm)
                            .fill(StreamifyColors.bgChipInactive)
                            .frame(height: 12)
                        RoundedRectangle(cornerRadius: StreamifyRadius.sm)
                            .fill(StreamifyColors.bgChipInactive)
                            .frame(width: 140, height: 10)
                    }
                    Spacer(minLength: 0)
                }
                .padding(.horizontal, StreamifyDimens.spaceLG)
            }
        }
        .redacted(reason: .placeholder)
        .accessibilityLabel("Searching")
    }

    /// One online result row: thumbnail art + title + uploader + type badge +
    /// duration + verified checkmark (Android OnlineResultRow).
    private func onlineRow(_ row: SearchRow) -> some View {
        let result = OnlineSearchResult(row: row)
        return Button {
            open(row)
        } label: {
            HStack(spacing: StreamifyDimens.spaceMD) {
                YtThumbnail(url: result.thumbnailUrl, seed: result.title, size: .small, label: result.title)
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: StreamifyDimens.spaceXS) {
                        Text(result.title)
                            .font(StreamifyType.songTitle)
                            .foregroundStyle(StreamifyColors.textMain)
                            .lineLimit(1)
                        if result.isVerified {
                            Image(systemName: StreamifyIcon.checkmarkCircle)
                                .font(.system(size: 11))
                                .foregroundStyle(StreamifyColors.accentBlue)
                        }
                    }
                    Text(result.uploader)
                        .font(StreamifyType.songArtist)
                        .foregroundStyle(StreamifyColors.textSecondary)
                        .lineLimit(1)
                }
                Spacer(minLength: StreamifyDimens.spaceSM)
                Text(result.type.label.uppercased())
                    .font(StreamifyType.caption.weight(.bold))
                    .tracking(0.6)
                    .foregroundStyle(StreamifyColors.textTertiary)
                if result.durationSec > 0 {
                    Text(PlaybackFormatting.formatPlaybackTime(result.durationSec * 1_000))
                        .font(StreamifyType.seekbarTime.monospacedDigit())
                        .foregroundStyle(StreamifyColors.textTertiary)
                }
            }
            .frame(minHeight: StreamifyDimens.trackRowHeight)
            .contentShape(Rectangle())
        }
        .buttonStyle(.pressableCard)
        .padding(.horizontal, StreamifyDimens.spaceLG)
    }

    // MARK: Tap routing

    /// A tapped playable row: flag the resolve, hand the row to the shell's
    /// play path, clear the flag.
    private func play(_ row: SearchRow) {
        model.beginResolving(row)
        onPlayTrack(row)
        model.endResolving()
    }

    /// A tapped navigable result (top-result card / online row): the shell
    /// routes artists, albums and playlists and plays songs and videos.
    private func open(_ row: SearchRow) {
        model.beginResolving(row)
        onOpenResult(row)
        model.endResolving()
    }

    // MARK: Derivations

    /// The current results filtered by the active type chip (nil = All) —
    /// the instant projection while the filtered re-query is in flight.
    private var filteredResults: [SearchRow] {
        guard let selectedType = filter else { return model.currentResults }
        return model.currentResults.filter { $0.kind.rawValue == selectedType.rawValue }
    }

    /// Song rows render as "Songs" TrackListItem rows and play through
    /// onPlayTrack.
    private var songRows: [SearchRow] {
        filteredResults.filter { $0.kind == .song }
    }

    /// Every non-song row (video / artist / album / playlist) renders as an
    /// online result row routed through onOpenResult.
    private var onlineRows: [SearchRow] {
        filteredResults.filter { $0.kind != .song }
    }

    /// The UI chip type onto the view model's filter currency (the two
    /// enums mirror each other across the view/VM seam).
    private static func modelFilter(for type: SearchResultType?) -> SearchResultKind? {
        type.flatMap { SearchResultKind(rawValue: $0.rawValue) }
    }

    private func sectionLabel(_ title: String) -> some View {
        Text(title).font(StreamifyType.headlineSmall).foregroundStyle(StreamifyColors.textMain)
    }
}

#Preview("Search") {
    NavigationStack {
        SearchView()
    }
}
