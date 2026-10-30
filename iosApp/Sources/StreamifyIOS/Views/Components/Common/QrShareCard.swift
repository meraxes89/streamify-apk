import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2 (Common wave B) — the wave QR share card.
//
// Port of ui/components/QrShareCard.kt: the Spotify-style visual share card
// as sheet content — gradient header (title + artist), a decorative
// pseudo-QR grid drawn on Canvas from a deterministic seeded pattern, the
// seeded AudioWaveStrip, and the copy-link / share action row. NOTE: the
// Android card encodes a real zxing QR; this port renders a DECORATIVE
// QR-LIKE grid (a seeded pattern, NOT a real encoder) — the deep link rides
// in the copy/share actions. Fixtures: PreviewCatalog.tracks.
// ─────────────────────────────────────────────────────────────────────────────

public struct QrShareCard: View {
    public let title: String
    public let artist: String
    public let link: String
    public var onCopyLink: (() -> Void)? = nil
    public var onShare: (() -> Void)? = nil
    public var onDismiss: (() -> Void)? = nil

    public init(
        title: String,
        artist: String,
        link: String,
        onCopyLink: (() -> Void)? = nil,
        onShare: (() -> Void)? = nil,
        onDismiss: (() -> Void)? = nil
    ) {
        self.title = title
        self.artist = artist
        self.link = link
        self.onCopyLink = onCopyLink
        self.onShare = onShare
        self.onDismiss = onDismiss
    }

    public var body: some View {
        VStack(spacing: StreamifyDimens.spaceLG) {
            headerPlate
            PseudoQrPlate(seed: link)
                .frame(width: 168, height: 168)
            Text("Scan to listen")
                .font(StreamifyType.caption)
                .foregroundStyle(StreamifyColors.textTertiary)
            AudioWaveStrip(seed: link)
                .frame(height: 30)
                .padding(.horizontal, StreamifyDimens.spaceMD)
            actionRow
        }
        .padding(StreamifyDimens.spaceLG)
        .background(StreamifyColors.bgCard, in: RoundedRectangle(cornerRadius: StreamifyRadius.xxl, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: StreamifyRadius.xxl, style: .continuous)
                .strokeBorder(StreamifyColors.glassHairline, lineWidth: 1)
        )
        .frame(maxWidth: 360)
    }

    private var headerPlate: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text("TRACK")
                    .font(.system(size: 10, weight: .heavy))
                    .kerning(1)
                    .foregroundStyle(StreamifyColors.primary)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 3)
                    .background(StreamifyColors.primary.opacity(0.16), in: StreamifyShapes.chip)
                Spacer()
                Button {
                    onDismiss?()
                } label: {
                    Image(systemName: StreamifyIcon.close)
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(StreamifyColors.textSecondary)
                        .frame(width: 26, height: 26)
                }
                .buttonStyle(.plain)
            }
            Text(title)
                .font(StreamifyType.headlineSmall)
                .foregroundStyle(StreamifyColors.textMain)
                .lineLimit(1)
            Text(artist)
                .font(StreamifyType.bodySmall)
                .foregroundStyle(StreamifyColors.textSecondary)
                .lineLimit(1)
        }
        .padding(StreamifyDimens.spaceMD)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            StreamifyGradients.linear(StreamifyGradients.preset(forSeed: title)),
            in: UnevenRoundedRectangle(
                topLeadingRadius: StreamifyRadius.xl,
                bottomTrailingRadius: StreamifyRadius.xl,
                style: .continuous
            )
        )
    }

    private var actionRow: some View {
        HStack(spacing: StreamifyDimens.spaceSM) {
            Button {
                Haptics.play(.queueGrab)
                onCopyLink?()
            } label: {
                Label("Copy Link", systemImage: StreamifyIcon.doc)
                    .font(StreamifyType.chipText)
                    .foregroundStyle(StreamifyColors.textSecondary)
                    .lineLimit(1)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 8)
                    .background(StreamifyColors.bgChipInactive, in: StreamifyShapes.searchBar)
            }
            .buttonStyle(.pressableCard)

            Button {
                Haptics.play(.queueDrop)
                onShare?()
            } label: {
                Label("Share", systemImage: StreamifyIcon.share)
                    .font(StreamifyType.chipText.weight(.bold))
                    .foregroundStyle(StreamifyColors.textOnActiveChip)
                    .lineLimit(1)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 8)
                    .background(StreamifyColors.primary, in: StreamifyShapes.searchBar)
            }
            .buttonStyle(.pressableCard)
        }
    }
}

/// Decorative pseudo-QR: a 21×21 module grid from a deterministic seeded
/// pattern (djb2 → splitmix stream), with the three real-QR finder squares
/// drawn for the recognizable silhouette. Purely decorative — NOT scannable.
private struct PseudoQrPlate: View {
    let seed: String

    var body: some View {
        Canvas { context, size in
            let modules = 21.0
            let cell = size.width / modules
            let ink = StreamifyColors.textMain
            let plate = StreamifyColors.bgSurfaceElevated
            context.fill(Path(CGRect(origin: .zero, size: size)), with: .color(plate))

            var state = seedHash(seed)
            for row in 0..<Int(modules) {
                for column in 0..<Int(modules) {
                    guard !isFinderZone(row, column, Int(modules)) else { continue }
                    state = splitmix(&state)
                    if state & 0x3 > 1 {
                        let rect = CGRect(
                            x: Double(column) * cell,
                            y: Double(row) * cell,
                            width: cell,
                            height: cell
                        )
                        context.fill(Path(rect), with: .color(ink))
                    }
                }
            }
            drawFinder(context, at: (0, 0), cell: cell, ink: ink)
            drawFinder(context, at: (Int(modules) - 7, 0), cell: cell, ink: ink)
            drawFinder(context, at: (0, Int(modules) - 7), cell: cell, ink: ink)
        }
        .clipShape(RoundedRectangle(cornerRadius: StreamifyRadius.lg, style: .continuous))
    }

    private func drawFinder(
        _ context: GraphicsContext,
        at origin: (Int, Int),
        cell: CGFloat,
        ink: Color
    ) {
        func moduleRect(_ row: Int, _ column: Int) -> CGRect {
            CGRect(
                x: Double(origin.1 + column) * Double(cell),
                y: Double(origin.0 + row) * Double(cell),
                width: Double(cell),
                height: Double(cell)
            )
        }
        for row in 0..<7 {
            for column in 0..<7 {
                let onEdge = row == 0 || row == 6 || column == 0 || column == 6
                let onCore = (2...4).contains(row) && (2...4).contains(column)
                if onEdge || onCore {
                    context.fill(Path(moduleRect(row, column)), with: .color(ink))
                }
            }
        }
    }

    private func isFinderZone(_ row: Int, _ column: Int, _ modules: Int) -> Bool {
        let inTopLeading = row < 8 && column < 8
        let inTopTrailing = row < 8 && column >= modules - 8
        let inBottomLeading = row >= modules - 8 && column < 8
        return inTopLeading || inTopTrailing || inBottomLeading
    }

    private func seedHash(_ seed: String) -> UInt64 {
        var hash: UInt64 = 5381
        for scalar in seed.unicodeScalars {
            hash = (hash &<< 5) &+ hash &+ UInt64(scalar.value)
        }
        return hash
    }

    private func splitmix(_ state: inout UInt64) -> UInt64 {
        state &+= 0x9E3779B97F4A7C15
        var z = state
        z = (z ^ (z &>> 30)) &* 0xBF58476D1CE4E5B9
        z = (z ^ (z &>> 27)) &* 0x94D049BB133111EB
        return z ^ (z &>> 31)
    }
}

/// Decorative audio-waveform strip: 24 rounded bars from a seeded sine mix
/// (the "wave" identity of the card — static, allocation-free draw).
private struct AudioWaveStrip: View {
    let seed: String

    var body: some View {
        Canvas { context, size in
            let bars = 24.0
            let gap = size.width / (bars * 1.6)
            let barWidth = (size.width - gap * (bars - 1)) / bars
            let seedValue = Double(seedHash(seed) % 97) / 97.0
            for i in 0..<Int(bars) {
                let mix = abs(
                    sin(Double(i) * 1.7 + seedValue * 6.28)
                    + 0.5 * sin(Double(i) * 0.6 + seedValue * 3.1)
                ) / 1.5
                let height = size.height * (0.22 + 0.62 * mix)
                let x = Double(i) * Double(barWidth + gap)
                let rect = CGRect(
                    x: x,
                    y: (size.height - height) / 2,
                    width: Double(barWidth),
                    height: height
                )
                context.fill(
                    Path(roundedRect: rect, cornerRadius: barWidth / 2),
                    with: .color(StreamifyColors.primary.opacity(0.85))
                )
            }
        }
    }

    private func seedHash(_ seed: String) -> UInt64 {
        var hash: UInt64 = 5381
        for scalar in seed.unicodeScalars {
            hash = (hash &<< 5) &+ hash &+ UInt64(scalar.value)
        }
        return hash
    }
}

#Preview {
    QrShareCard(
        title: PreviewCatalog.tracks[0].title,
        artist: PreviewCatalog.tracks[0].artist,
        link: "https://open.streamify.app/track/obsidian-sunrise",
        onCopyLink: {},
        onShare: {}
    )
    .padding(StreamifyDimens.spaceLG)
    .background(StreamifyColors.bgBase)
}
