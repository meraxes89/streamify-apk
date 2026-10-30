import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2 (Common wave B) — the custom neon pull-to-refresh wrapper.
//
// Port of ui/components/StreamifyPullToRefreshContainer.kt: the M3
// pull-to-refresh scaffold re-expressed with the component owning a
// ScrollView — a GeometryReader marker tracks the pull distance; during the
// pull PullArcIndicator's arc grows (trim 0…progress) around a neon dot,
// crossing the threshold arms the gesture with the .queueGrab haptic, and
// while the refresh runs NeonOrbitalIndicator spins (orbiting dot + glow
// shadow). onRefresh receives a completion callback it must call when done.
// Fixtures: PreviewCatalog.tracks.
// ─────────────────────────────────────────────────────────────────────────────

public struct StreamifyPullToRefreshContainer<Content: View>: View {
    /// Pull travel needed to arm a refresh (pt).
    public static let defaultThreshold: CGFloat = 72

    public let threshold: CGFloat
    public let onRefresh: (_ completion: @escaping () -> Void) -> Void
    public let content: Content

    @State private var pull: CGFloat = 0
    @State private var isRefreshing = false
    @State private var armed = false
    @State private var restOffset: CGFloat?

    public init(
        threshold: CGFloat = StreamifyPullToRefreshContainer.defaultThreshold,
        onRefresh: @escaping (_ completion: @escaping () -> Void) -> Void,
        @ViewBuilder content: () -> Content
    ) {
        self.threshold = threshold
        self.onRefresh = onRefresh
        self.content = content()
    }

    public var body: some View {
        ScrollView {
            // Zero-height marker: its minY in the scroll space measures the
            // overscroll (positive while the content is pulled past the top).
            GeometryReader { proxy in
                Color.clear
                    .frame(height: 0)
                    .preference(
                        key: PullOffsetKey.self,
                        value: proxy.frame(in: .named("pullSpace")).minY
                    )
            }
            .frame(height: 0)
            LazyVStack(spacing: 0) {
                content
            }
        }
        .coordinateSpace(name: "pullSpace")
        .onPreferenceChange(PullOffsetKey.self) { rawOffset in
            guard let restOffset else {
                restOffset = rawOffset
                return
            }
            trackPull(max(0, rawOffset - restOffset))
        }
        .overlay(alignment: .top) { indicator }
        .simultaneousGesture(
            DragGesture().onEnded { _ in commitOrReset() }
        )
    }

    private func trackPull(_ distance: CGFloat) {
        pull = distance
        guard !isRefreshing else { return }
        if distance >= threshold, !armed {
            armed = true
            Haptics.play(.queueGrab)
        } else if armed, distance < threshold * 0.75 {
            // Retreated most of the way back: disarm, don't surprise-fire.
            armed = false
        }
    }

    private func commitOrReset() {
        if armed, !isRefreshing {
            armed = false
            isRefreshing = true
            onRefresh { isRefreshing = false }
        } else {
            armed = false
        }
    }

    @ViewBuilder
    private var indicator: some View {
        if isRefreshing || pull > 0 {
            NeonOrbitalIndicator(
                progress: min(pull / threshold, 1),
                isRefreshing: isRefreshing
            )
            .padding(.top, 10)
            .frame(maxWidth: .infinity)
            .opacity(isRefreshing ? 1 : min(pull / (threshold * 0.4), 1))
            .allowsHitTesting(false)
        }
    }
}

/// The top indicator: the kinetic pull arc while dragging, the orbiting
/// neon spinner while the refresh runs.
public struct NeonOrbitalIndicator: View {
    public var progress: Double
    public var isRefreshing: Bool

    public init(progress: Double, isRefreshing: Bool) {
        self.progress = progress
        self.isRefreshing = isRefreshing
    }

    public var body: some View {
        if isRefreshing {
            RefreshingOrbitalSpinner()
        } else {
            PullArcIndicator(progress: progress)
        }
    }
}

/// The pull-driven arc: grows 0°→360° with the gesture (trim 0…progress,
/// -90° origin) around a neon dot with a glow shadow. No animation loop —
/// pure function of the pull.
public struct PullArcIndicator: View {
    public var progress: Double

    public init(progress: Double) {
        self.progress = progress
    }

    public var body: some View {
        ZStack {
            Circle()
                .stroke(StreamifyColors.textMain.opacity(0.08), style: StrokeStyle(lineWidth: 4))
            Circle()
                .trim(from: 0, to: min(max(progress, 0), 1))
                .stroke(
                    StreamifyColors.primary,
                    style: StrokeStyle(lineWidth: 4, lineCap: .round)
                )
                .rotationEffect(.degrees(-90))
            Circle()
                .fill(StreamifyColors.activeControl)
                .frame(width: 7, height: 7)
                .shadow(color: StreamifyColors.primary.opacity(0.8), radius: 4)
        }
        .frame(width: 42, height: 42)
    }
}

/// The continuous spinner: one orbiting neon dot with a pulsing radial glow
/// — the TimelineView runs only while composed (i.e. while refreshing).
private struct RefreshingOrbitalSpinner: View {
    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 60.0)) { context in
            let t = context.date.timeIntervalSinceReferenceDate
            let angle = t * 2 * .pi / 0.75
            let pulse = 0.85 + 0.15 * sin(t * 2 * .pi / 0.6)
            ZStack {
                Circle()
                    .stroke(StreamifyColors.textMain.opacity(0.08), style: StrokeStyle(lineWidth: 4))
                Circle()
                    .fill(
                        RadialGradient(
                            colors: [
                                StreamifyColors.activeControl.opacity(0.45),
                                StreamifyColors.primary.opacity(0.15),
                                .clear
                            ],
                            center: .center,
                            startRadius: 1,
                            endRadius: 21 * pulse
                        )
                    )
                Circle()
                    .fill(StreamifyColors.primary)
                    .frame(width: 8, height: 8)
                    .shadow(color: StreamifyColors.activeControl, radius: 5)
                    .offset(x: 15 * cos(angle), y: 15 * sin(angle))
            }
            .frame(width: 42, height: 42)
            .scaleEffect(pulse)
        }
    }
}

private struct PullOffsetKey: PreferenceKey {
    static var defaultValue: CGFloat = 0

    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        value = max(value, nextValue())
    }
}

#Preview {
    StreamifyPullToRefreshContainer(
        onRefresh: { completion in
            Task { @MainActor in
                try? await Task.sleep(for: .seconds(1.2))
                completion()
            }
        }
    ) {
        ForEach(PreviewCatalog.tracks) { track in
            HStack(spacing: StreamifyDimens.spaceMD) {
                Image(systemName: StreamifyIcon.musicNote)
                    .foregroundStyle(StreamifyColors.primary)
                Text(track.title)
                    .font(StreamifyType.songTitle)
                    .foregroundStyle(StreamifyColors.textMain)
                Spacer()
                Text(track.artist)
                    .font(StreamifyType.songArtist)
                    .foregroundStyle(StreamifyColors.textSecondary)
            }
            .padding(.horizontal, StreamifyDimens.spaceLG)
            .padding(.vertical, 10)
        }
    }
    .background(StreamifyColors.bgBase)
}
