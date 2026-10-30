import SwiftUI

#if os(iOS)
import UIKit
#endif

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2-08 — DiagnosticsView: the live SLog stream.
//
// SwiftUI port of ui/screens/AdminTerminalScreen.kt: the level filter chips
// (All / Info / Debug / Warn / Error), the follow auto-scroll toggle, and the
// copy (UIPasteboard, iOS) / share (ShareLink) / clear actions over the
// joined buffer. The body is a ScrollViewReader + LazyVStack of monospaced
// log rows — grey timestamp, 4 pt level dot, tag, message — auto-scrolled to
// the tail on every new line while follow is on (Motion.snappy).
// ─────────────────────────────────────────────────────────────────────────────

/// Level filter for the stream (All shows every severity).
private enum LogFilter: String, CaseIterable, Identifiable {
    case all
    case info
    case debug
    case warn
    case error

    var id: String { rawValue }

    var label: String {
        switch self {
        case .all: return "All"
        case .info: return "Info"
        case .debug: return "Debug"
        case .warn: return "Warn"
        case .error: return "Error"
        }
    }

    func matches(_ level: SlogLevel) -> Bool {
        switch self {
        case .all: return true
        case .info: return level == .info
        case .debug: return level == .debug
        case .warn: return level == .warn
        case .error: return level == .error
        }
    }
}

/// One SLog line: grey timestamp caption, 4 pt level dot, monospaced tag and
/// message (Android AdminTerminalScreen annotated line, tokenized).
private struct LogRow: View {
    let line: LogLine

    private var levelColor: Color {
        switch line.level {
        case .debug: return StreamifyColors.textTertiary
        case .info: return StreamifyColors.accentBlue
        case .warn: return StreamifyColors.warningAmber
        case .error: return StreamifyColors.errorRed
        }
    }

    private var timestamp: Date {
        Date(timeIntervalSince1970: TimeInterval(line.timestampMs) / 1_000)
    }

    var body: some View {
        HStack(alignment: .top, spacing: StreamifyDimens.spaceSM) {
            Text(timestamp, style: .time)
                .font(StreamifyType.caption.monospacedDigit())
                .foregroundStyle(StreamifyColors.textTertiary)
                .lineLimit(1)
            Circle()
                .fill(levelColor)
                .frame(width: 4, height: 4)
                .padding(.top, 4)
            Text(line.tag)
                .font(.system(size: 11, weight: .semibold, design: .monospaced))
                .foregroundStyle(StreamifyColors.textSecondary)
                .lineLimit(1)
            Text(line.message)
                .font(.system(size: 11, design: .monospaced))
                .foregroundStyle(StreamifyColors.textMain)
                .lineLimit(2)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
    }
}

public struct DiagnosticsView: View {
    public var lines: [LogLine]
    public var onClear: () -> Void

    @State private var follow = true
    @State private var levelFilter: LogFilter = .all

    public init(
        lines: [LogLine] = PreviewCatalog.slogTail,
        onClear: @escaping () -> Void = {}
    ) {
        self.lines = lines
        self.onClear = onClear
    }

    private var visibleLines: [LogLine] {
        lines.filter { levelFilter.matches($0.level) }
    }

    private var joinedText: String {
        lines.map { line in
            "\(line.timestampMs) [\(line.level.rawValue.uppercased())] \(line.tag): \(line.message)"
        }
        .joined(separator: "\n")
    }

    public var body: some View {
        VStack(spacing: 0) {
            toolbar
            filterRow
            if visibleLines.isEmpty {
                EmptyStateView(
                    symbol: StreamifyIcon.terminal,
                    title: "No log lines",
                    message: "Diagnostics events from the sovereign core will stream here."
                )
            } else {
                logBody
            }
        }
        .background(StreamifyColors.bgBase)
    }

    // MARK: - Toolbar (copy / share / clear)

    private var toolbar: some View {
        HStack(spacing: StreamifyDimens.spaceMD) {
            Text("Diagnostics")
                .font(StreamifyType.headlineSmall)
                .foregroundStyle(StreamifyColors.textMain)
            Spacer(minLength: 0)
            #if os(iOS)
            Button {
                UIPasteboard.general.string = joinedText
                Haptics.play(.scrubTick)
            } label: {
                Image(systemName: "doc.on.doc")
                    .font(.system(size: 13, weight: .medium))
                    .foregroundStyle(StreamifyColors.textMain)
                    .frame(width: 30, height: 30)
                    .background(StreamifyColors.bgChipInactive, in: Circle())
            }
            .buttonStyle(.pressableCard)
            #endif
            ShareLink(item: joinedText) {
                Image(systemName: StreamifyIcon.share)
                    .font(.system(size: 13, weight: .medium))
                    .foregroundStyle(StreamifyColors.textMain)
                    .frame(width: 30, height: 30)
                    .background(StreamifyColors.bgChipInactive, in: Circle())
            }
            .buttonStyle(.pressableCard)
            Button {
                Haptics.play(.scrubTick)
                onClear()
            } label: {
                Image(systemName: StreamifyIcon.trash)
                    .font(.system(size: 13, weight: .medium))
                    .foregroundStyle(StreamifyColors.errorRed)
                    .frame(width: 30, height: 30)
                    .background(StreamifyColors.errorBg, in: Circle())
            }
            .buttonStyle(.pressableCard)
        }
        .padding(.horizontal, StreamifyDimens.spaceLG)
        .padding(.vertical, StreamifyDimens.spaceMD)
    }

    private var filterRow: some View {
        HStack(spacing: StreamifyDimens.spaceSM) {
            ForEach(LogFilter.allCases) { candidate in
                Button {
                    Haptics.play(.scrubTick)
                    withAnimation(Motion.snappy) { levelFilter = candidate }
                } label: {
                    Text(candidate.label)
                        .font(StreamifyType.chipText)
                        .foregroundStyle(
                            levelFilter == candidate
                                ? StreamifyColors.textOnActiveChip
                                : StreamifyColors.textMain
                        )
                        .padding(.horizontal, StreamifyDimens.spaceMD)
                        .frame(height: StreamifyDimens.filterChipHeight)
                        .chipSurface(active: levelFilter == candidate)
                }
                .buttonStyle(.pressableCard)
            }
            Spacer(minLength: 0)
            Toggle("Follow", isOn: $follow)
                .tint(StreamifyColors.primary)
                .font(StreamifyType.caption)
                .fixedSize()
        }
        .padding(.horizontal, StreamifyDimens.spaceLG)
        .padding(.bottom, StreamifyDimens.spaceSM)
    }

    // MARK: - Stream (auto-scroll while follow is on)

    private var logBody: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 3) {
                    ForEach(visibleLines) { line in
                        LogRow(line: line)
                            .id(line.id)
                    }
                }
                .padding(.horizontal, StreamifyDimens.spaceLG)
                .padding(.vertical, StreamifyDimens.spaceMD)
            }
            .onAppear {
                guard follow else { return }
                scrollToEnd(proxy)
            }
            .onChange(of: visibleLines.count) { _, _ in
                guard follow else { return }
                withAnimation(Motion.snappy) {
                    scrollToEnd(proxy)
                }
            }
        }
    }

    private func scrollToEnd(_ proxy: ScrollViewProxy) {
        guard let last = visibleLines.last else { return }
        proxy.scrollTo(last.id, anchor: .bottom)
    }
}

#Preview("Diagnostics") {
    DiagnosticsView()
}
