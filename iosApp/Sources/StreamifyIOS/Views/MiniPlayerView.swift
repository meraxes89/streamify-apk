import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// Phase 5 (MP-UI01 / Beta): the docked mini player + volume control — the
// SwiftUI counterparts of the shared CMP components (MiniPlayerBar /
// VolumeSlider). State comes from the pushed engine snapshots; intent goes
// out through the same command plane as the NowPlaying transport, the
// audio-session reactions and the lockscreen remote commands. Elapsed-time
// text renders the 1 Hz-quantized display position.
// ─────────────────────────────────────────────────────────────────────────────

struct MiniPlayerView: View {
    @EnvironmentObject private var model: PlaybackViewModel

    var body: some View {
        HStack(spacing: 12) {
            // Brand glyph placeholder (artwork loading is a follow-up: the
            // shared component's badge contract).
            ZStack {
                RoundedRectangle(cornerRadius: 8)
                    .fill(StreamifyTheme.bgCard)
                Image(systemName: "music.note")
                    .font(.system(size: 16))
                    .foregroundColor(StreamifyTheme.textSecondary)
            }
            .frame(width: 44, height: 44)

            VStack(alignment: .leading, spacing: 2) {
                Text(model.state.currentTrack?.title ?? "Control plane live")
                    .font(.system(size: 14, weight: .medium))
                    .foregroundColor(StreamifyTheme.textMain)
                    .lineLimit(1)
                Text(subtitle)
                    .font(.system(size: 11))
                    .foregroundColor(StreamifyTheme.textSecondary)
                    .lineLimit(1)
            }

            Spacer(minLength: 8)

            Button { model.previous() } label: {
                Image(systemName: "backward.end.fill")
                    .font(.system(size: 16))
                    .foregroundColor(StreamifyTheme.textMain)
            }
            .buttonStyle(.plain)

            Button { model.togglePlayPause() } label: {
                ZStack {
                    if model.state.isBuffering {
                        ProgressView()
                            .tint(StreamifyTheme.textMain)
                    } else {
                        Image(systemName: model.state.isPlaying ? "pause.fill" : "play.fill")
                            .font(.system(size: 20))
                            .foregroundColor(StreamifyTheme.textMain)
                    }
                }
                .frame(width: 36, height: 36)
            }
            .buttonStyle(.plain)

            Button { model.next() } label: {
                Image(systemName: "forward.end.fill")
                    .font(.system(size: 16))
                    .foregroundColor(StreamifyTheme.textMain)
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
        .background(RoundedRectangle(cornerRadius: 12).fill(StreamifyTheme.bgSurfaceElevated))
        .overlay(alignment: .bottom) {
            // Micro-progress strip: determinate when position AND duration
            // are known (DAC-clock derived, MP-DEC-03).
            GeometryReader { proxy in
                ZStack(alignment: .leading) {
                    Rectangle().fill(StreamifyTheme.borderSubtle)
                    if let fraction = model.state.progressFraction {
                        Rectangle()
                            .fill(Color.white)
                            .frame(width: proxy.size.width * fraction)
                    }
                }
            }
            .frame(height: 2)
        }
    }

    private var subtitle: String {
        // 1 Hz-quantized display position (see PlaybackUiState.
        // positionForDisplayMs) — the readout advances at most once per
        // second of content, never per push.
        let position = model.state.positionForDisplayMs.map(PlaybackFormatting.formatPlaybackTime)
        let duration = model.state.durationMs.map(PlaybackFormatting.formatPlaybackTime)
        let track = model.state.currentTrack
        switch (track, position, duration) {
        case (let t?, let p?, let d?):
            return "\(t.artist) · \(p) / \(d)"
        case (let t?, let p?, nil):
            return "\(t.artist) · \(p)"
        case (let t?, nil, _):
            return t.artist
        default:
            return "Rust core engine · snapshots pushed on change"
        }
    }
}

// VolumeSliderView now lives in Components/Common/MiniPlayerBar.swift (the
// SW-ENG2-09 port); this legacy view is replaced by MiniPlayerBar when the
// adaptive shell (SW-ENG2-02) lands.
