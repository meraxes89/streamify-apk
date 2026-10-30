import Charts
import SwiftUI
import Observation

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG4-07 (Engineer 4): the Blend surface — the Spotify-style circular
// taste-match percentage gauge built with Swift Charts (SectorMark donut), the
// mutual-artist rail, the blended track list with provenance and mutual-love
// badges, and the shared-playlist bootstrap ("Play Blend").
//
// The gauge reads `BlendViewModel.matchPercent` (engine-computed Jaccard
// window 35–99); the match arc animates with a spring, the split avatars
// mirror the Kotlin entrance choreography (slide-in + overlap).
// ─────────────────────────────────────────────────────────────────────────────

@MainActor
struct BlendView: View {
    @State private var blendModel = BlendViewModel()
    @State private var animatedPercent: Double = 0
    /// Drives the split-avatar entrance choreography.
    @State private var appeared = false

    let friendName: String
    let friendSeeds: [String]
    /// The shell's play hook (the engine command plane owns actual playback).
    var onPlayBlend: (([PresentationTrack]) -> Void)? = nil

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 18) {
                    avatarPair
                    matchGauge
                    mutualArtistsRail
                    blendList
                    playBlendButton
                }
                .padding(16)
            }
            .background(StreamifyTheme.bgBase)
            .navigationTitle("Blend")
            #if os(iOS)
            // navigationBarTitleDisplayMode is iOS-only;
            // macOS titles are inline by construction.
            .navigationBarTitleDisplayMode(.inline)
            #endif
        }
        .task {
            blendModel.generateBlend(friendName: friendName, friendSeeds: friendSeeds)
            withAnimation(.spring(response: 0.7, dampingFraction: 0.8)) {
                appeared = true
            }
            withAnimation(.spring(response: 0.9, dampingFraction: 0.85).delay(0.15)) {
                animatedPercent = Double(blendModel.matchPercent)
            }
        }
        .onChange(of: blendModel.matchPercent) { _, newValue in
            withAnimation(.spring(response: 0.9, dampingFraction: 0.85)) {
                animatedPercent = Double(newValue)
            }
        }
    }

    // MARK: - Split avatars (Kotlin entrance choreography)

    private var avatarPair: some View {
        ZStack {
            // Kotlin parity: both halves slide in from their edges and overlap.
            AvatarCircle(label: "You", color: StreamifyTheme.primary)
                .offset(x: appeared ? -22 : -120)
                .opacity(appeared ? 1 : 0)
            AvatarCircle(label: shortName, color: StreamifyTheme.accentBlue)
                .offset(x: appeared ? 22 : 120)
                .opacity(appeared ? 1 : 0)
        }
        .padding(.top, 8)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("You and \(shortName)")
    }

    private var shortName: String {
        String(blendModel.friendName.prefix(10))
    }

    // MARK: - The circular taste-match gauge (Swift Charts SectorMark)

    private var matchGauge: some View {
        ZStack {
            Chart {
                SectorMark(
                    angle: .value("Taste match", max(animatedPercent, 0.001)),
                    innerRadius: .ratio(0.78),
                    angularInset: 2.5
                )
                .foregroundStyle(
                    LinearGradient(
                        colors: [StreamifyTheme.primary, StreamifyTheme.accentBlue],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    )
                )
                .cornerRadius(10)

                SectorMark(
                    angle: .value("Rest", max(100 - animatedPercent, 0.001)),
                    innerRadius: .ratio(0.78),
                    angularInset: 2.5
                )
                .foregroundStyle(StreamifyTheme.bgSurfaceElevated)
                .cornerRadius(10)
            }
            .frame(width: 190, height: 190)
            .chartLegend(.hidden)
            .chartBackground { _ in
                VStack(spacing: 4) {
                    Text("\(blendModel.matchPercent)%")
                        .font(.system(size: 40, weight: .heavy).monospacedDigit())
                        .foregroundColor(StreamifyTheme.textMain)
                        .contentTransition(.numericText())
                        .animation(
                            .spring(response: 0.9, dampingFraction: 0.85),
                            value: blendModel.matchPercent
                        )
                    Text("TASTE MATCH")
                        .font(.system(size: 10, weight: .bold))
                        .tracking(2)
                        .foregroundColor(StreamifyTheme.textTertiary)
                }
            }

            if blendModel.isLoading {
                ProgressView()
                    .tint(StreamifyTheme.textMain)
                    .offset(y: 70)
            }
        }
    }

    // MARK: - Mutual artists

    @ViewBuilder
    private var mutualArtistsRail: some View {
        if !blendModel.mutualArtists.isEmpty {
            VStack(spacing: 8) {
                Text("You both love")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundColor(StreamifyTheme.textSecondary)
                HStack(spacing: 8) {
                    ForEach(blendModel.mutualArtists.prefix(3), id: \.self) { artist in
                        Text(artist.capitalized)
                            .font(.system(size: 12, weight: .medium))
                            .foregroundColor(StreamifyTheme.textMain)
                            .padding(.horizontal, 12)
                            .padding(.vertical, 6)
                            .background(
                                StreamifyTheme.primary.opacity(0.16),
                                in: Capsule()
                            )
                    }
                }
            }
            .padding(.top, 2)
        }
    }

    // MARK: - The blend list

    @ViewBuilder
    private var blendList: some View {
        if blendModel.isEmpty && !blendModel.isLoading {
            VStack(spacing: 10) {
                Image(systemName: "sparkles")
                    .font(.system(size: 28))
                    .foregroundColor(StreamifyTheme.textTertiary)
                Text("Couldn't cross-pollinate yet — try again in a moment.")
                    .font(.system(size: 12))
                    .foregroundColor(StreamifyTheme.textSecondary)
            }
            .padding(.vertical, 30)
        } else {
            VStack(alignment: .leading, spacing: 4) {
                ForEach(blendModel.tracks) { track in
                    BlendTrackRow(track: track, friendName: shortName)
                }
                Text("\(blendModel.tracks.count) tracks • refreshed daily")
                    .font(.system(size: 10))
                    .foregroundColor(StreamifyTheme.textTertiary)
                    .padding(.top, 6)
            }
            .padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(StreamifyTheme.bgCard, in: RoundedRectangle(cornerRadius: 16))
        }
    }

    private var playBlendButton: some View {
        Button {
            onPlayBlend?(blendModel.playableQueue)
        } label: {
            Label("Play Blend", systemImage: "play.fill")
                .font(.system(size: 15, weight: .semibold))
                .frame(maxWidth: .infinity)
                .padding(.vertical, 12)
        }
        .buttonStyle(.borderedProminent)
        .tint(StreamifyTheme.primary)
        .disabled(blendModel.playableQueue.isEmpty)
    }
}

// MARK: - Pieces

private struct AvatarCircle: View {
    let label: String
    let color: Color

    var body: some View {
        ZStack {
            Circle()
                .fill(color.opacity(0.9))
                .frame(width: 64, height: 64)
            Text(label)
                .font(.system(size: 13, weight: .bold))
                .foregroundColor(.white)
                .lineLimit(1)
        }
        .overlay(Circle().stroke(StreamifyTheme.bgBase, lineWidth: 3))
    }
}

private struct BlendTrackRow: View {
    let track: BlendTrack
    let friendName: String

    var body: some View {
        HStack(spacing: 10) {
            Circle()
                .fill(track.memberIndex == 0 ? StreamifyTheme.primary : StreamifyTheme.accentBlue)
                .frame(width: 8, height: 8)
            VStack(alignment: .leading, spacing: 2) {
                Text(track.title)
                    .font(.system(size: 14, weight: .medium))
                    .foregroundColor(StreamifyTheme.textMain)
                    .lineLimit(1)
                Text(track.artist)
                    .font(.system(size: 11))
                    .foregroundColor(StreamifyTheme.textTertiary)
                    .lineLimit(1)
            }
            Spacer()
            if track.mutual {
                Label("Both of you love this", systemImage: "heart.fill")
                    .font(.system(size: 9, weight: .semibold))
                    .foregroundColor(StreamifyTheme.primary)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .background(StreamifyTheme.primary.opacity(0.16), in: Capsule())
            } else {
                Text(track.memberIndex == 0 ? "You" : friendName)
                    .font(.system(size: 10))
                    .foregroundColor(StreamifyTheme.textTertiary)
            }
        }
        .padding(.vertical, 6)
    }
}
