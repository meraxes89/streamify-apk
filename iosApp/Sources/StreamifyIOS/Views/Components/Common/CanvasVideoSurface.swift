import SwiftUI
import AVFoundation
import Observation
#if os(macOS)
import AppKit
#else
import UIKit
#endif

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2 (Common wave B) — the YTM "Canvas" loop-video surface.
//
// Port of ui/components/CanvasVideoSurface.kt: the Android ExoPlayer-backed
// full-bleed loop becomes a native AVPlayerLayer surface (layerClass override
// on iOS, makeBackingLayer on macOS — AVPlayerLayer exists on macOS via
// AVFoundation, so the same representable contract serves both). player = nil
// renders the poster state (gradient plate + play glyph); a tap toggles
// play/pause on the real player and the CanvasToggleChip drives mute.
// CanvasBackdrop is the ambient blurred gradient plate behind the video
// (from an AmbientPalette seed), and CanvasLoopController is the
// rememberCanvasLoop equivalent — isLooping + startedAt, no timers inside
// (the view drives the clock).
// ─────────────────────────────────────────────────────────────────────────────

/// Resolved loop state for the current track.
public enum CanvasLoopState {
    case none
    case loading
    case ready(AVPlayer)
}

/// The loop state holder (Android rememberCanvasLoop): loop on/off + the
/// session start timestamp — view-driven, zero timers inside.
@MainActor
@Observable
public final class CanvasLoopController {
    public var isLooping: Bool
    public var startedAt: Date?

    public init(isLooping: Bool = false, startedAt: Date? = nil) {
        self.isLooping = isLooping
        self.startedAt = startedAt
    }

    public func start() {
        isLooping = true
        if startedAt == nil { startedAt = Date() }
    }

    public func stop() {
        isLooping = false
    }
}

// MARK: - The player-layer representable (per-platform, kept minimal)

#if os(macOS)
private struct PlayerLayerRep: NSViewRepresentable {
    let player: AVPlayer
    let cornerRadius: CGFloat
    let gravity: AVLayerVideoGravity

    final class HostView: NSView {
        override func makeBackingLayer() -> CALayer { AVPlayerLayer() }

        override init(frame frameRect: NSRect) {
            super.init(frame: frameRect)
            wantsLayer = true
        }

        required init?(coder: NSCoder) {
            super.init(coder: coder)
            wantsLayer = true
        }
    }

    func makeNSView(context: Context) -> HostView {
        let view = HostView(frame: .zero)
        configure(view)
        return view
    }

    func updateNSView(_ view: HostView, context: Context) {
        configure(view)
    }

    private func configure(_ view: HostView) {
        view.layer?.backgroundColor = NSColor.black.cgColor
        if let playerLayer = view.layer as? AVPlayerLayer {
            playerLayer.player = player
            playerLayer.videoGravity = gravity
            playerLayer.cornerRadius = cornerRadius
            playerLayer.masksToBounds = true
        }
    }
}
#else
private struct PlayerLayerRep: UIViewRepresentable {
    let player: AVPlayer
    let cornerRadius: CGFloat
    let gravity: AVLayerVideoGravity

    final class HostView: UIView {
        override class var layerClass: AnyClass { AVPlayerLayer.self }
    }

    func makeUIView(context: Context) -> HostView {
        let view = HostView(frame: .zero)
        view.backgroundColor = .clear
        configure(view)
        return view
    }

    func updateUIView(_ view: HostView, context: Context) {
        configure(view)
    }

    private func configure(_ view: HostView) {
        if let playerLayer = view.layer as? AVPlayerLayer {
            playerLayer.player = player
            playerLayer.videoGravity = gravity
            playerLayer.cornerRadius = cornerRadius
            playerLayer.masksToBounds = true
        }
    }
}
#endif

// MARK: - The surface

public struct CanvasVideoSurface: View {
    public var player: AVPlayer?
    public var cornerRadius: CGFloat
    public var videoGravity: AVLayerVideoGravity
    /// Seeds the poster-state gradient plate when no player is attached.
    public var seed: String

    @State private var isMuted = false

    public init(
        player: AVPlayer?,
        cornerRadius: CGFloat = StreamifyRadius.lg,
        videoGravity: AVLayerVideoGravity = .resizeAspectFill,
        seed: String = "streamify"
    ) {
        self.player = player
        self.cornerRadius = cornerRadius
        self.videoGravity = videoGravity
        self.seed = seed
    }

    public var body: some View {
        ZStack {
            if let player {
                PlayerLayerRep(
                    player: player,
                    cornerRadius: cornerRadius,
                    gravity: videoGravity
                )
                CanvasToggleChip(
                    title: isMuted ? "Muted" : "Sound",
                    icon: isMuted ? StreamifyIcon.speakerMuted : StreamifyIcon.speaker,
                    isOn: isMuted
                ) { muted in
                    isMuted = muted
                    player.isMuted = muted
                }
                .padding(10)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
            } else {
                posterPlate
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
        .contentShape(RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
        .onTapGesture { togglePlayPause() }
    }

    private func togglePlayPause() {
        guard let player else { return }
        let currentlyPlaying = player.timeControlStatus == .playing
        if currentlyPlaying {
            player.pause()
        } else {
            player.play()
        }
        Haptics.play(.playPauseClick)
    }

    private var posterPlate: some View {
        ZStack {
            Rectangle().fill(
                StreamifyGradients.linear(StreamifyGradients.preset(forSeed: seed))
            )
            VStack(spacing: 6) {
                Image(systemName: StreamifyIcon.play)
                    .font(.system(size: 30, weight: .semibold))
                    .foregroundStyle(Color.white.opacity(0.95))
                Text("Canvas loop")
                    .font(StreamifyType.caption)
                    .foregroundStyle(Color.white.opacity(0.75))
            }
        }
    }
}

// MARK: - The ambient backdrop + the toggle chip

/// Ambient blurred gradient plate behind the loop (from an artwork palette).
public struct CanvasBackdrop: View {
    public var palette: AmbientPalette

    public init(palette: AmbientPalette) {
        self.palette = palette
    }

    public var body: some View {
        ZStack {
            LinearGradient(
                colors: [palette.dominant, palette.accent, palette.darkMuted],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
            LinearGradient(
                colors: [.clear, .clear, palette.darkMuted.opacity(0.75)],
                startPoint: .top,
                endPoint: .bottom
            )
        }
        .blur(radius: 44)
        .overlay(StreamifyMaterials.panel, in: Rectangle())
    }
}

/// The Canvas toggle chip (Android CanvasToggleChip): tinted when on, dim
/// when off — here it serves as the surface's mute affordance.
public struct CanvasToggleChip: View {
    public var title: String
    public var icon: String
    public var isOn: Bool
    public var action: (Bool) -> Void

    public init(
        title: String,
        icon: String,
        isOn: Bool,
        action: @escaping (Bool) -> Void
    ) {
        self.title = title
        self.icon = icon
        self.isOn = isOn
        self.action = action
    }

    public var body: some View {
        Button {
            Haptics.play(.scrubTick)
            action(!isOn)
        } label: {
            HStack(spacing: 5) {
                Image(systemName: icon)
                    .font(.system(size: 12, weight: .semibold))
                Text(title)
                    .font(.system(size: 11, weight: .semibold))
                    .lineLimit(1)
            }
            .foregroundStyle(isOn ? StreamifyColors.primary : StreamifyColors.textSecondary)
            .padding(.horizontal, 10)
            .padding(.vertical, 5)
            .background(
                isOn
                    ? StreamifyColors.primary.opacity(0.16)
                    : StreamifyColors.bgChipInactive,
                in: Capsule()
            )
            .overlay(
                Capsule().strokeBorder(
                    isOn ? StreamifyColors.primary.opacity(0.55) : StreamifyColors.borderChip,
                    lineWidth: 1
                )
            )
        }
        .buttonStyle(.pressableCard)
    }
}

#Preview("Poster state + backdrop") {
    VStack(spacing: 20) {
        CanvasVideoSurface(
            player: nil,
            cornerRadius: 16,
            seed: PreviewCatalog.tracks[0].title
        )
        .frame(height: 200)
        CanvasBackdrop(palette: PreviewCatalog.palette(forTitle: PreviewCatalog.tracks[0].title))
            .frame(height: 120)
            .overlay(alignment: .bottomLeading) {
                CanvasToggleChip(
                    title: "Canvas",
                    icon: StreamifyIcon.wave,
                    isOn: true
                ) { _ in }
                .padding(10)
            }
    }
    .padding()
    .background(StreamifyColors.bgBase)
}

#Preview("Loop controller") {
    CanvasLoopDemo()
        .padding()
        .background(StreamifyColors.bgBase)
}

private struct CanvasLoopDemo: View {
    @State private var loop = CanvasLoopController(isLooping: true, startedAt: .now)

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { context in
            VStack(alignment: .leading, spacing: 6) {
                Text(loop.isLooping ? "Canvas looping" : "Canvas paused")
                    .font(StreamifyType.titleMedium)
                    .foregroundStyle(StreamifyColors.textMain)
                Text(
                    loop.startedAt.map {
                        "started \(Int(context.date.timeIntervalSince($0)))s ago"
                    } ?? "not started"
                )
                .font(StreamifyType.caption)
                .foregroundStyle(StreamifyColors.textSecondary)
                Button(loop.isLooping ? "Stop" : "Start") {
                    loop.isLooping ? loop.stop() : loop.start()
                }
                .buttonStyle(.bordered)
            }
        }
    }
}
