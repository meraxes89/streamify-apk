import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2 (Common wave B) — the timed track-comments sheet content.
//
// Native port of ui/components/CommentsSheet.kt: the Compose ModalBottomSheet
// body expressed as plain sheet CONTENT (the host presents it inside a sheet).
// Comment rows anchor to the playhead — the timestamp chip seeks the player
// (onSeekTo) and renders through the shared PlaybackFormatting clock
// formatter — and the input bar posts at the current playhead (onPost).
// Fixtures: PreviewCatalog.comments.
// ─────────────────────────────────────────────────────────────────────────────

public struct CommentsSheet: View {
    public let comments: [CommentModel]
    public let currentPosMs: Int
    public var onSeekTo: ((Int) -> Void)? = nil
    public var onPost: ((String) -> Void)? = nil
    public var onDismiss: (() -> Void)? = nil

    @State private var draft = ""

    public init(
        comments: [CommentModel],
        currentPosMs: Int,
        onSeekTo: ((Int) -> Void)? = nil,
        onPost: ((String) -> Void)? = nil,
        onDismiss: (() -> Void)? = nil
    ) {
        self.comments = comments
        self.currentPosMs = currentPosMs
        self.onSeekTo = onSeekTo
        self.onPost = onPost
        self.onDismiss = onDismiss
    }

    public var body: some View {
        VStack(spacing: StreamifyDimens.spaceMD) {
            header
            if comments.isEmpty {
                emptyState
            } else {
                ScrollView {
                    LazyVStack(spacing: StreamifyDimens.spaceSM) {
                        ForEach(comments) { comment in
                            CommentThread(
                                comment: comment,
                                isNearPlayhead: abs(comment.timeMs - currentPosMs) < 3_000,
                                onTimestampTap: onSeekTo
                            )
                        }
                    }
                }
            }
            inputBar
        }
        .padding(.horizontal, StreamifyDimens.spaceMD)
        .padding(.bottom, StreamifyDimens.spaceLG)
        .frame(maxWidth: .infinity)
    }

    private var header: some View {
        HStack(alignment: .firstTextBaseline) {
            VStack(alignment: .leading, spacing: 2) {
                Text("Live Song Reactions")
                    .font(StreamifyType.headlineMedium)
                    .foregroundStyle(StreamifyColors.textMain)
                Text("\(comments.count) comments on this track")
                    .font(StreamifyType.caption)
                    .foregroundStyle(StreamifyColors.textSecondary)
            }
            Spacer(minLength: StreamifyDimens.spaceMD)
            Button {
                onDismiss?()
            } label: {
                Image(systemName: StreamifyIcon.close)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(StreamifyColors.textSecondary)
                    .frame(width: 32, height: 32)
            }
            .buttonStyle(.plain)
        }
    }

    private var emptyState: some View {
        VStack(spacing: StreamifyDimens.spaceSM) {
            Image(systemName: StreamifyIcon.comment)
                .font(.system(size: 34))
                .foregroundStyle(StreamifyColors.primary)
            Text("No comments yet")
                .font(StreamifyType.bodyLarge)
                .foregroundStyle(StreamifyColors.textMain)
            Text("Be the first to react at \(PlaybackFormatting.formatPlaybackTime(currentPosMs))")
                .font(StreamifyType.caption)
                .foregroundStyle(StreamifyColors.textSecondary)
        }
        .frame(maxWidth: .infinity, minHeight: 160)
    }

    private var inputBar: some View {
        HStack(spacing: StreamifyDimens.spaceSM) {
            Text(PlaybackFormatting.formatPlaybackTime(currentPosMs))
                .font(StreamifyType.caption.weight(.bold))
                .monospacedDigit()
                .foregroundStyle(StreamifyColors.primary)
                .padding(.horizontal, 8)
                .padding(.vertical, 4)
                .background(
                    StreamifyColors.primary.opacity(0.18),
                    in: StreamifyShapes.chip
                )
            TextField(
                "Add reaction at \(PlaybackFormatting.formatPlaybackTime(currentPosMs))…",
                text: $draft
            )
            .font(StreamifyType.bodyMedium)
            .foregroundStyle(StreamifyColors.textMain)
            .textFieldStyle(.plain)
            Button {
                let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !text.isEmpty else { return }
                Haptics.play(.queueDrop)
                onPost?(text)
                draft = ""
            } label: {
                Image(systemName: StreamifyIcon.paperplane)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(draft.isEmpty ? StreamifyColors.textTertiary : StreamifyColors.primary)
                    .frame(width: 34, height: 34)
            }
            .buttonStyle(.plain)
            .disabled(draft.isEmpty)
        }
        .padding(.horizontal, StreamifyDimens.spaceSM)
        .padding(.vertical, 6)
        .background(StreamifyColors.bgCard, in: StreamifyShapes.searchBar)
    }
}

/// One row: near-playhead highlight + the seek-through-timestamp affordance.
private struct CommentThread: View {
    let comment: CommentModel
    let isNearPlayhead: Bool
    var onTimestampTap: ((Int) -> Void)? = nil

    var body: some View {
        CommentItem(comment: comment)
            .padding(StreamifyDimens.spaceMD)
            .background(
                isNearPlayhead
                    ? StreamifyColors.primary.opacity(0.12)
                    : StreamifyColors.bgCard,
                in: StreamifyShapes.card
            )
            .overlay {
                // Clickable timestamp chip — one tap seeks the player there.
                HStack {
                    Spacer()
                    Button {
                        Haptics.play(.scrubTick)
                        onTimestampTap?(comment.timeMs)
                    } label: {
                        Text(PlaybackFormatting.formatPlaybackTime(comment.timeMs))
                            .font(StreamifyType.caption.weight(.bold))
                            .monospacedDigit()
                            .foregroundStyle(StreamifyColors.primary)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(StreamifyColors.bgSurfaceElevated, in: StreamifyShapes.chip)
                    }
                    .buttonStyle(.plain)
                }
                .padding(.top, StreamifyDimens.spaceSM)
                .padding(.trailing, StreamifyDimens.spaceSM)
            }
    }
}

/// The row interior: avatar (image or initials), author, body, like count.
private struct CommentItem: View {
    let comment: CommentModel

    private var initials: String {
        let first = comment.author.prefix(1).uppercased()
        return first.isEmpty ? "?" : first
    }

    var body: some View {
        HStack(alignment: .top, spacing: StreamifyDimens.spaceSM) {
            ZStack {
                if let avatarUrl = comment.avatarUrl, let url = URL(string: avatarUrl) {
                    AsyncImage(url: url) { phase in
                        switch phase {
                        case .success(let image):
                            image.resizable().aspectRatio(contentMode: .fill)
                        default:
                            initialsPlate
                        }
                    }
                } else {
                    initialsPlate
                }
            }
            .frame(width: 32, height: 32)
            .clipShape(Circle())

            VStack(alignment: .leading, spacing: 3) {
                Text(comment.author)
                    .font(StreamifyType.bodySmall.weight(.bold))
                    .foregroundStyle(StreamifyColors.textMain)
                    .lineLimit(1)
                Text(comment.text)
                    .font(StreamifyType.bodyMedium)
                    .foregroundStyle(StreamifyColors.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                HStack(spacing: 4) {
                    Image(systemName: StreamifyIcon.heartFill)
                        .font(.system(size: 11))
                        .foregroundStyle(StreamifyColors.primary)
                    Text(comment.likes > 0 ? "\(comment.likes)" : "Like")
                        .font(StreamifyType.caption)
                        .foregroundStyle(StreamifyColors.textTertiary)
                }
            }
            Spacer(minLength: 0)
        }
    }

    private var initialsPlate: some View {
        ZStack {
            Circle().fill(StreamifyColors.primaryDark)
            Text(initials)
                .font(StreamifyType.caption.weight(.bold))
                .foregroundStyle(StreamifyColors.textMain)
        }
    }
}

#Preview {
    CommentsSheet(
        comments: PreviewCatalog.comments,
        currentPosMs: 132_000,
        onSeekTo: { _ in },
        onPost: { _ in }
    )
    .background(StreamifyColors.bgBase)
}
