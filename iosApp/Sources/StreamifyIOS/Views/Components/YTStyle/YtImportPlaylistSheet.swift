import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// YtImportPlaylistSheet — sheet CONTENT for YTM / Exportify playlist import.
// Port of ui/components/yt/YtImportPlaylistSheet.kt: the ModalBottomSheet shell
// stays with the presenter; this is the content — title bar + Done, URL field
// with live platform tinting, source picker chips, primary import button, and
// a status row (spinner while resolving / status + icon afterwards).
// ─────────────────────────────────────────────────────────────────────────────

/// Import source (Android inferred the platform from the URL; here it is a
/// first-class picker, Exportify being the CSV route).
public enum YtImportSource: String, CaseIterable, Identifiable {
    case youtubeMusic = "YouTube Music"
    case exportify = "Exportify"

    public var id: String { rawValue }
}

public struct YtImportPlaylistSheet: View {
    public var isImporting: Bool
    public var statusText: String?
    public var onImport: (_ url: String, _ source: YtImportSource) -> Void
    public var onDone: () -> Void

    public init(
        isImporting: Bool,
        statusText: String? = nil,
        onImport: @escaping (String, YtImportSource) -> Void,
        onDone: @escaping () -> Void
    ) {
        self.isImporting = isImporting
        self.statusText = statusText
        self.onImport = onImport
        self.onDone = onDone
    }

    @State private var urlString = ""
    @State private var source: YtImportSource = .youtubeMusic

    private var canImport: Bool {
        !urlString.trimmingCharacters(in: .whitespaces).isEmpty && !isImporting
    }

    /// Live platform tint (Android animateColorAsState over URL heuristics).
    private var linkTint: Color {
        let lowered = urlString.lowercased()
        if lowered.contains("spotify") { return StreamifyColors.successGreen }
        if lowered.contains("youtube") || lowered.contains("youtu.be") { return StreamifyColors.primary }
        return source == .youtubeMusic ? StreamifyColors.primary : StreamifyColors.accentBlue
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: StreamifyDimens.spaceLG) {
            titleBar
            urlField
            sourceChips
            importButton
            statusRow
        }
        .padding(.horizontal, StreamifyDimens.spaceXL)
        .padding(.vertical, StreamifyDimens.spaceLG)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(StreamifyColors.bgSurfaceElevated)
    }

    private var titleBar: some View {
        HStack {
            HStack(spacing: StreamifyDimens.spaceSM) {
                Image(systemName: "link").font(.system(size: 18))
                    .foregroundStyle(StreamifyColors.primary)
                Text("Import Playlist").font(StreamifyType.headlineMedium)
                    .foregroundStyle(StreamifyColors.textMain)
            }
            Spacer()
            Button("Done", action: onDone).font(StreamifyType.titleMedium)
                .foregroundStyle(StreamifyColors.accentBlue).buttonStyle(.plain)
        }
    }

    private var urlField: some View {
        TextField("Paste a YouTube Music or Exportify link", text: $urlString)
            .font(StreamifyType.bodyLarge).foregroundStyle(StreamifyColors.textMain)
            .padding(StreamifyDimens.spaceMD)
            .background(StreamifyColors.bgCard, in: RoundedRectangle(cornerRadius: StreamifyRadius.lg))
            .overlay(
                RoundedRectangle(cornerRadius: StreamifyRadius.lg)
                    .strokeBorder(linkTint, lineWidth: 1)
            )
            .animation(Motion.snappy, value: linkTint)
    }

    private var sourceChips: some View {
        HStack(spacing: StreamifyDimens.spaceSM) {
            ForEach(YtImportSource.allCases) { candidate in
                let active = candidate == source
                Button { source = candidate } label: {
                    Text(candidate.rawValue).font(StreamifyType.chipText)
                        .foregroundStyle(active ? StreamifyColors.textOnActiveChip : StreamifyColors.textMain)
                        .padding(.horizontal, StreamifyDimens.spaceMD)
                        .frame(height: StreamifyDimens.filterChipHeight)
                        .chipSurface(active: active)
                }
                .buttonStyle(.plain)
            }
            Spacer(minLength: 0)
        }
    }

    private var importButton: some View {
        Button {
            onImport(urlString, source)
        } label: {
            Text("Resolve & Import Tracks")
                .font(StreamifyType.titleSmall).frame(maxWidth: .infinity).frame(height: 48)
                .background(canImport ? StreamifyColors.activeControl : StreamifyColors.bgCard, in: Capsule())
                .foregroundStyle(canImport ? StreamifyColors.textOnActiveChip : StreamifyColors.textTertiary)
        }
        .buttonStyle(.plain)
        .disabled(!canImport)
        .animation(Motion.snappy, value: canImport)
    }

    private var statusRow: some View {
        HStack(spacing: StreamifyDimens.spaceSM) {
            if isImporting {
                ProgressView().tint(StreamifyColors.primary)
                Text(statusText ?? "Resolving tracks…").font(StreamifyType.bodyMedium)
                    .foregroundStyle(StreamifyColors.textSecondary)
            } else if let statusText {
                Image(systemName: StreamifyIcon.download).font(.system(size: 14))
                    .foregroundStyle(linkTint)
                Text(statusText).font(StreamifyType.bodyMedium)
                    .foregroundStyle(StreamifyColors.textMain)
            }
        }
        .animation(Motion.snappy, value: isImporting)
    }
}

#Preview("Import playlist sheet") {
    YtImportPlaylistSheet(
        isImporting: true,
        statusText: "Resolving 42 of 64 tracks",
        onImport: { _, _ in },
        onDone: {}
    )
    .padding(StreamifyDimens.spaceLG)
    .background(StreamifyColors.bgBase)
}
