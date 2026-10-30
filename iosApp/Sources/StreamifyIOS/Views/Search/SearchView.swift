import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2-04 — Search: the unified local + online search surface.
//
// Native port of ui/screens/SearchScreen.kt: the YtSearchOmnibar becomes the
// mandated native `.searchable` integration (navigation-bar drawer), the
// Compose debounce LaunchedEffect becomes a restartable `.task(id: query)`
// — task-id semantics cancel the previous keystroke's task — and the result
// stack ports 1:1: recents chips, YtSearchFilterChips type filtering, the
// YtTopResultCard top result, local "Songs" TrackListItem rows and
// OnlineSearchResult rows. Data flows in via props; the shell owns push.
// ─────────────────────────────────────────────────────────────────────────────

public struct SearchView: View {

    // Data in, callbacks out — the search graph's lowered currency.
    public var recentSearches: [RecentSearchEntry] = PreviewCatalog.recentSearches
    public var localResults: [VirtualShelfTrack] = PreviewCatalog.tracks
    public var onlineResults: [OnlineSearchResult] = PreviewCatalog.onlineResults
    public var isSearching: Bool = false
    public var onSearch: (String) -> Void = { _ in }
    public var onSubmit: (String) -> Void = { _ in }
    public var onPlayTrack: (VirtualShelfTrack) -> Void = { _ in }
    public var onOpenResult: (OnlineSearchResult) -> Void = { _ in }
    public var onRemoveRecent: (String) -> Void = { _ in }
    public var onClearRecents: () -> Void = {}

    // Ephemeral UI state: the live query + the active type filter (nil = All).
    @State private var query = ""
    @State private var filter: SearchResultType?

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
        .onSubmit(of: .search) { onSubmit(query) }
        .task(id: query) {
            // Instant-query debounce: the task id cancels the previous
            // keystroke's task; 300 ms of quiet lowers the query onto onSearch.
            try? await Task.sleep(for: .milliseconds(300))
            if Task.isCancelled { return }
            onSearch(query)
        }
    }

    // MARK: Recent searches (query empty)

    @ViewBuilder
    private var recentsSection: some View {
        if recentSearches.isEmpty {
            EmptyStateView(
                symbol: StreamifyIcon.search,
                title: "Find your sound",
                message: "Search songs, artists and albums across your library and YouTube Music."
            )
        } else {
            HStack {
                sectionLabel("Recent searches")
                Spacer(minLength: StreamifyDimens.spaceMD)
                Button(action: onClearRecents) {
                    Text("Clear all")
                        .font(StreamifyType.chipText)
                        .foregroundStyle(StreamifyColors.primary)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Clear all recent searches")
            }
            .padding(.horizontal, StreamifyDimens.spaceLG)
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 120))], spacing: 8) {
                ForEach(recentSearches) { entry in
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
            query = entry.query
            onSearch(entry.query)
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
                    onRemoveRecent(entry.query)
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
        if isSearching {
            skeletonRows
        } else if filteredOnline.isEmpty && localResults.isEmpty {
            EmptyStateView(
                symbol: StreamifyIcon.search,
                title: "No results",
                message: "Nothing matched \"\(query)\"."
            )
        } else {
            if let topResult = filteredOnline.first {
                YtTopResultCard(result: topResult) { onOpenResult(topResult) }
            }
            if !localResults.isEmpty {
                sectionLabel("Songs").padding(.horizontal, StreamifyDimens.spaceLG)
                LazyVStack(spacing: StreamifyDimens.spaceXS) {
                    ForEach(localResults) { track in
                        TrackListItem(track: track) { onPlayTrack(track) }
                            .padding(.horizontal, StreamifyDimens.spaceLG)
                    }
                }
            }
            if !filteredOnline.isEmpty {
                sectionLabel("Online").padding(.horizontal, StreamifyDimens.spaceLG)
                LazyVStack(spacing: StreamifyDimens.spaceXS) {
                    ForEach(filteredOnline) { result in onlineRow(result) }
                }
            }
        }
    }

    /// The isSearching loading state — redacted placeholder rows.
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
    private func onlineRow(_ result: OnlineSearchResult) -> some View {
        Button {
            onOpenResult(result)
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

    // MARK: Derivations

    /// The online list filtered by the active type chip (nil = All).
    private var filteredOnline: [OnlineSearchResult] {
        guard let selectedType = filter else { return onlineResults }
        return onlineResults.filter { $0.type == selectedType }
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
