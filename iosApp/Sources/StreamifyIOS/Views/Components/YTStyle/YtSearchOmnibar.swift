import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// YtSearchOmnibar — the search omnibar input (Android
// ui/components/yt/YtSearchOmnibar.kt: magnifier + BasicTextField + clear
// glyph over a 48 dp surface). The native port is a plain TextField with a
// search submit label, FocusState-driven focus callbacks and the same 48 pt
// searchBar capsule chrome.
// ─────────────────────────────────────────────────────────────────────────────

public struct YtSearchOmnibar: View {
    @Binding public var query: String
    public var placeholder: String = "Search songs, albums, artists"
    public var onSubmit: (() -> Void)? = nil
    public var onFocusChange: ((Bool) -> Void)? = nil

    @FocusState private var focused: Bool

    public init(
        query: Binding<String>,
        placeholder: String = "Search songs, albums, artists",
        onSubmit: (() -> Void)? = nil,
        onFocusChange: ((Bool) -> Void)? = nil
    ) {
        self._query = query
        self.placeholder = placeholder
        self.onSubmit = onSubmit
        self.onFocusChange = onFocusChange
    }

    public var body: some View {
        HStack(spacing: StreamifyDimens.spaceMD) {
            Image(systemName: StreamifyIcon.search)
                .font(.system(size: 17))
                .foregroundStyle(StreamifyColors.textSecondary)
                .accessibilityHidden(true)

            TextField(placeholder, text: $query)
                .focused($focused)
                .textFieldStyle(.plain)
                .font(StreamifyType.bodyLarge)
                .foregroundStyle(StreamifyColors.textMain)
                .submitLabel(.search)
                .onSubmit { onSubmit?() }

            if !query.isEmpty {
                Button {
                    query = ""
                } label: {
                    Image(systemName: StreamifyIcon.close)
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(StreamifyColors.textSecondary)
                        .padding(5)
                        .background(StreamifyColors.borderSubtle, in: Circle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Clear search")
            }
        }
        .padding(.horizontal, StreamifyDimens.spaceMD)
        .frame(height: StreamifyDimens.searchBarHeight)
        .background(StreamifyColors.bgSearchBar, in: StreamifyShapes.searchBar)
        .onChange(of: focused) { _, isFocused in
            onFocusChange?(isFocused)
        }
    }
}

#Preview("Search omnibar") {
    OmnibarDemo()
        .padding(StreamifyDimens.spaceLG)
        .frame(maxWidth: 520)
        .background(StreamifyColors.bgBase)
}

private struct OmnibarDemo: View {
    @State private var query = PreviewCatalog.recentSearches[0].query
    @State private var submitted: String?

    var body: some View {
        VStack(spacing: StreamifyDimens.spaceMD) {
            YtSearchOmnibar(query: $query, onSubmit: { submitted = query })
            if let submitted {
                Text("Submitted: \(submitted)")
                    .font(StreamifyType.caption)
                    .foregroundStyle(StreamifyColors.textSecondary)
            }
        }
    }
}
