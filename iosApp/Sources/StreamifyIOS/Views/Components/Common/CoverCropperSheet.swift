import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2-C04.14 — CoverCropperSheet: the playlist-cover cropper (sheet content).
//
// Port of ui/components/CoverCropperSheet.kt (Phase-3 pick → pan/zoom →
// apply). The Android original decodes a picked bitmap; the native port in
// this wave represents the artwork as a GENERATIVE GRADIENT (the palette
// extraction result, pre-image): drag to pan, pinch to zoom, rotate in 90°
// steps, apply the square. Same gestures, same non-destructive viewport —
// the crop frame stays fixed while the art transforms beneath it (SW-DEC-06).
// ─────────────────────────────────────────────────────────────────────────────

public struct CoverCropperSheet: View {

    public let playlistName: String
    public let onApply: () -> Void
    public let onDismiss: () -> Void

    @State private var dragOffset = CGSize.zero
    @State private var zoomBase: CGFloat = 1
    @State private var zoom: CGFloat = 1
    @State private var rotationDegrees: Double = 0

    public init(
        playlistName: String,
        onApply: @escaping () -> Void,
        onDismiss: @escaping () -> Void
    ) {
        self.playlistName = playlistName
        self.onApply = onApply
        self.onDismiss = onDismiss
    }

    public var body: some View {
        VStack(spacing: StreamifyDimens.spaceLG) {
            header

            cropViewport

            footer
        }
        .padding(StreamifyDimens.spaceXL)
    }

    // MARK: Header

    private var header: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text("Edit Cover")
                    .font(StreamifyType.headlineMedium)
                    .foregroundStyle(StreamifyColors.textMain)
                Text(playlistName)
                    .font(StreamifyType.caption)
                    .foregroundStyle(StreamifyColors.textSecondary)
                    .lineLimit(1)
            }
            Spacer()
            Button(action: onDismiss) {
                Image(systemName: StreamifyIcon.close)
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(StreamifyColors.textSecondary)
                    .frame(width: 30, height: 30)
                    .background(Circle().fill(StreamifyColors.bgChipInactive))
                    .contentShape(Circle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Close cropper")
        }
    }

    // MARK: Crop viewport (fixed square; gestures transform the art beneath)

    private var cropViewport: some View {
        ZStack {
            RoundedRectangle(cornerRadius: StreamifyRadius.xl, style: .continuous)
                .fill(Color.black)

            gradientArt
                .gesture(
                    DragGesture()
                        .onChanged { value in
                            dragOffset = clampedOffset(value.translation)
                        }
                )
                .simultaneousGesture(
                    MagnifyGesture()
                        .onChanged { value in
                            zoom = min(max(zoomBase * value.magnification, 1), 5)
                        }
                        .onEnded { _ in
                            zoomBase = zoom
                        }
                )
                .animation(Motion.snappy, value: rotationDegrees)

            CornerBrackets(arm: 22)
                .stroke(
                    .white.opacity(0.75),
                    style: StrokeStyle(lineWidth: 2.5, lineCap: .round, lineJoin: .round)
                )
                .padding(StreamifyDimens.spaceMD)
                .allowsHitTesting(false)
                .accessibilityHidden(true)
        }
        .aspectRatio(1, contentMode: .fit)
        .clipShape(RoundedRectangle(cornerRadius: StreamifyRadius.xl, style: .continuous))
    }

    /// The artwork stand-in: the seed gradient for this playlist, overfilled
    /// (1.5×) so rotation and pan never expose the viewport edge.
    private var gradientArt: some View {
        Rectangle()
            .fill(
                LinearGradient(
                    colors: StreamifyGradients.preset(forSeed: playlistName),
                    startPoint: .topLeading,
                    endPoint: .bottomTrailing
                )
            )
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .scaleEffect(1.5 * zoom)
            .rotationEffect(.degrees(rotationDegrees))
            .offset(dragOffset)
    }

    private func clampedOffset(_ translation: CGSize) -> CGSize {
        CGSize(
            width: min(max(translation.width, -260), 260),
            height: min(max(translation.height, -260), 260)
        )
    }

    // MARK: Footer actions

    private var footer: some View {
        HStack {
            Button {
                withAnimation(Motion.snappy) {
                    rotationDegrees += 90
                }
            } label: {
                HStack(spacing: 6) {
                    Image(systemName: StreamifyIcon.refresh)
                        .font(.system(size: 13, weight: .semibold))
                    Text("Rotate")
                        .font(StreamifyType.chipText)
                }
                .foregroundStyle(StreamifyColors.textMain)
                .padding(.horizontal, 14)
                .padding(.vertical, 9)
                .background(Capsule().fill(StreamifyColors.bgChipInactive))
            }
            .buttonStyle(.pressableCard)

            Spacer()

            Button(action: onApply) {
                HStack(spacing: 6) {
                    Image(systemName: StreamifyIcon.checkmark)
                        .font(.system(size: 12, weight: .bold))
                    Text("Set Cover")
                        .font(StreamifyType.titleSmall)
                }
                .foregroundStyle(.black)
                .padding(.horizontal, StreamifyDimens.spaceXXL)
                .padding(.vertical, 10)
                .background(Capsule().fill(StreamifyColors.successGreen))
            }
            .buttonStyle(.pressableCard)
        }
    }
}

// MARK: - Crop corner brackets

private struct CornerBrackets: Shape {
    var arm: CGFloat

    func path(in rect: CGRect) -> Path {
        var path = Path()
        let brackets: [(CGPoint, CGPoint)] = [
            // top-left
            (CGPoint(x: rect.minX, y: rect.minY + arm), CGPoint(x: rect.minX, y: rect.minY)),
            (CGPoint(x: rect.minX, y: rect.minY), CGPoint(x: rect.minX + arm, y: rect.minY)),
            // top-right
            (CGPoint(x: rect.maxX - arm, y: rect.minY), CGPoint(x: rect.maxX, y: rect.minY)),
            (CGPoint(x: rect.maxX, y: rect.minY), CGPoint(x: rect.maxX, y: rect.minY + arm)),
            // bottom-right
            (CGPoint(x: rect.maxX, y: rect.maxY - arm), CGPoint(x: rect.maxX, y: rect.maxY)),
            (CGPoint(x: rect.maxX, y: rect.maxY), CGPoint(x: rect.maxX - arm, y: rect.maxY)),
            // bottom-left
            (CGPoint(x: rect.minX + arm, y: rect.maxY), CGPoint(x: rect.minX, y: rect.maxY)),
            (CGPoint(x: rect.minX, y: rect.maxY), CGPoint(x: rect.minX, y: rect.maxY - arm)),
        ]
        for (start, end) in brackets {
            path.move(to: start)
            path.addLine(to: end)
        }
        return path
    }
}

#Preview("Cover cropper sheet") {
    CoverCropperSheet(
        playlistName: PreviewCatalog.communityPlaylists[0].name,
        onApply: {},
        onDismiss: {}
    )
    .glassPanel(StreamifyMaterials.sheet, cornerRadius: StreamifyRadius.xxl)
    .padding(24)
    .frame(maxWidth: 420)
    .frame(maxWidth: .infinity, maxHeight: .infinity)
    .background(StreamifyColors.bgBase)
}
