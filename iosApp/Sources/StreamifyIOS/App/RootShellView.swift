import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// Phase 5 (MP-UI01 / Beta): the root shell — TabView navigation (Home /
// Search / Library / Jam Sessions) with a persistent MiniPlayerView docked
// above the tab bar, mirroring the desktop shell's adaptive rail sections
// (the iOS idiom for the same destinations).
// ─────────────────────────────────────────────────────────────────────────────

struct RootShellView: View {
    @EnvironmentObject private var model: PlaybackViewModel

    var body: some View {
        ZStack {
            TabView {
                NowPlayingView()
                    .tabItem { Label("Home", systemImage: "house.fill") }

                SearchView()
                    .tabItem { Label("Search", systemImage: "magnifyingglass") }

                LibraryView()
                    .tabItem { Label("Library", systemImage: "music.note.list") }

                JamSessionsView()
                    .tabItem { Label("Jam Sessions", systemImage: "person.3.fill") }
            }
            .preferredColorScheme(StreamifyTheme.shellColorScheme)
            .tint(StreamifyTheme.primary)

            // The docked mini player rides ABOVE the tab bar (the iOS
            // idiom for the desktop shell's MiniPlayerBar dock).
            VStack(spacing: 0) {
                Spacer()
                MiniPlayerView()
                    .padding(.horizontal, 12)
                    .padding(.bottom, 58)
            }
        }
        .background(StreamifyTheme.bgBase.ignoresSafeArea())
    }
}

// MARK: - Now Playing

/// The now-playing surface (the directive's NowPlayingView): transport,
/// progress, volume, engine health — plus the queue sheet entry point.
struct NowPlayingView: View {
    @EnvironmentObject private var model: PlaybackViewModel
    @State private var queuePresented = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 20) {
                    header
                    transportCard
                    volumeCard
                    engineHealthCard
                }
                .padding(20)
            }
            .background(StreamifyTheme.bgBase)
            .navigationTitle("Now Playing")
            #if os(iOS)
            // .navigationBar is an iOS-only ToolbarPlacement; on macOS the
            // window chrome follows the shell-wide .preferredColorScheme.
            .toolbarColorScheme(.dark, for: .navigationBar)
            #endif
            .toolbar {
                ToolbarItem(placement: .shellTrailing) {
                    Button {
                        queuePresented = true
                    } label: {
                        Image(systemName: "music.note.list")
                    }
                }
            }
            .sheet(isPresented: $queuePresented) {
                NavigationStack {
                    QueueView(
                        state: QueueListState.empty,
                        onTrackClick: nil
                    )
                    .toolbar {
                        ToolbarItem(placement: .shellTrailing) {
                            Button("Done") { queuePresented = false }
                        }
                    }
                }
            }
        }
    }

    private var header: some View {
        VStack(spacing: 4) {
            Text(model.state.currentTrack?.title ?? "Control plane live — no track loaded")
                .font(.system(size: 17, weight: .semibold))
                .foregroundColor(StreamifyTheme.textMain)
                .lineLimit(1)
            Text(model.state.status.displayName)
                .font(.system(size: 13))
                .foregroundColor(StreamifyTheme.textSecondary)
        }
        .frame(maxWidth: .infinity)
    }

    private var transportCard: some View {
        VStack(spacing: 12) {
            HStack(spacing: 0) {
                positionLabel
                ProgressView(value: model.state.progressFraction ?? 0)
                    .tint(StreamifyTheme.primary)
                    .padding(.horizontal, 8)
                durationLabel
            }

            HStack(spacing: 44) {
                Button { model.previous() } label: {
                    Image(systemName: "backward.end.fill")
                        .font(.system(size: 26))
                        .foregroundColor(StreamifyTheme.textMain)
                }
                Button { model.togglePlayPause() } label: {
                    ZStack {
                        Circle()
                            .fill(StreamifyTheme.primary)
                            .frame(width: 64, height: 64)
                        Image(systemName: model.state.isPlaying ? "pause.fill" : "play.fill")
                            .font(.system(size: 26))
                            .foregroundColor(StreamifyTheme.bgBase)
                    }
                }
                Button { model.next() } label: {
                    Image(systemName: "forward.end.fill")
                        .font(.system(size: 26))
                        .foregroundColor(StreamifyTheme.textMain)
                }
            }
            .buttonStyle(.plain)
        }
        .padding(16)
        .background(RoundedRectangle(cornerRadius: 14).fill(StreamifyTheme.bgCard))
    }

    private var positionLabel: some View {
        // 1 Hz-quantized display position (positionForDisplayMs): the
        // readout advances at most once per second of content.
        Text(model.state.positionForDisplayMs.map(PlaybackFormatting.formatPlaybackTime) ?? "--:--")
            .font(.system(size: 11).monospacedDigit())
            .foregroundColor(StreamifyTheme.textTertiary)
    }

    private var durationLabel: some View {
        Text(model.state.durationMs.map(PlaybackFormatting.formatPlaybackTime) ?? "--:--")
            .font(.system(size: 11).monospacedDigit())
            .foregroundColor(StreamifyTheme.textTertiary)
    }

    private var volumeCard: some View {
        VolumeSliderView(
            volume: model.state.volume,
            onVolumeChange: { model.setVolume($0) }
        )
        .padding(16)
        .background(RoundedRectangle(cornerRadius: 14).fill(StreamifyTheme.bgCard))
    }

    private var engineHealthCard: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Engine health")
                .font(.system(size: 15, weight: .semibold))
                .foregroundColor(StreamifyTheme.textMain)
            metric("revision", "rev \(model.state.snapshot.rev)")
            metric(
                "buffer",
                model.state.snapshot.buffer.map {
                    "\($0.bufferedDurationMs / 1000)s buffered · \($0.underrunCount) underruns · \(Int($0.fillRatio * 100))% fill"
                } ?? "no buffer stats yet"
            )
            metric(
                "dac clock",
                model.state.snapshot.clock.map {
                    "\($0.framePosition) frames @ \($0.sampleRateHz) Hz"
                } ?? "no hardware sample yet"
            )
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(RoundedRectangle(cornerRadius: 14).fill(StreamifyTheme.bgCard))
    }

    private func metric(_ label: String, _ value: String) -> some View {
        HStack(alignment: .top) {
            Text(label)
                .font(.system(size: 11))
                .foregroundColor(StreamifyTheme.textTertiary)
                .frame(width: 90, alignment: .leading)
            Text(value)
                .font(.system(size: 12).monospaced())
                .foregroundColor(StreamifyTheme.textSecondary)
        }
    }
}

// MARK: - Search / Library (honest placeholders — same contract notes as desktop)

struct SearchView: View {
    @State private var query = ""
    @FocusState private var queryFocused: Bool

    var body: some View {
        NavigationStack {
            VStack(spacing: 16) {
                TextField("Search tracks, artists, sessions…", text: $query)
                    .focused($queryFocused)
                    .submitLabel(.search)
                    .autocorrectionDisabled()
                    .padding(12)
                    .background(RoundedRectangle(cornerRadius: 10).fill(StreamifyTheme.bgSurfaceElevated))
                    .padding(.horizontal, 20)
                Spacer()
                Image(systemName: "waveform")
                    .font(.system(size: 36))
                    .foregroundColor(StreamifyTheme.textTertiary)
                Text("Catalog search lands with the storage contract")
                    .font(.system(size: 14))
                    .foregroundColor(StreamifyTheme.textSecondary)
                Text("The engine control plane (playback, jam sessions) is live; track search over the local encrypted library is the next contract surface.")
                    .font(.system(size: 11))
                    .foregroundColor(StreamifyTheme.textTertiary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 40)
                Spacer()
            }
            .background(StreamifyTheme.bgBase)
            .navigationTitle("Search")
        }
    }
}

struct LibraryView: View {
    var body: some View {
        NavigationStack {
            VStack(spacing: 12) {
                Spacer()
                Text("Your library mirror renders here once the device-local encrypted storage contract ships.")
                    .font(.system(size: 13))
                    .foregroundColor(StreamifyTheme.textSecondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 40)
                Spacer()
            }
            .background(StreamifyTheme.bgBase)
            .navigationTitle("Library")
        }
    }
}

// MARK: - Jam Sessions

struct JamSessionsView: View {
    @EnvironmentObject private var model: PlaybackViewModel
    @State private var sessionName = "Pocket listening room"
    @State private var sessionInfo: String?
    @State private var busy = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    Text("The engine's local CRDT mirror — create a session, then inspect its deterministic BLAKE3 state hash (the Merkle reconciliation currency).")
                        .font(.system(size: 13))
                        .foregroundColor(StreamifyTheme.textSecondary)
                        .padding(.horizontal, 8)

                    TextField("Session name", text: $sessionName)
                        .padding(12)
                        .background(RoundedRectangle(cornerRadius: 10).fill(StreamifyTheme.bgSurfaceElevated))

                    Button {
                        busy = true
                        Task {
                            let created = await model.createJamSession(name: sessionName)
                            let hash = await model.jamStateHash()
                            sessionInfo = created == nil
                                ? "Session rejected (blank or oversized name)"
                                : "Session '\(created!)' live — state hash \(hash ?? "unavailable")"
                            busy = false
                        }
                    } label: {
                        HStack {
                            Image(systemName: "person.3.fill")
                            Text(busy ? "Creating…" : "Create local session")
                        }
                        .font(.system(size: 15, weight: .medium))
                        .foregroundColor(StreamifyTheme.bgBase)
                        .padding(.horizontal, 22)
                        .padding(.vertical, 12)
                        .background(RoundedRectangle(cornerRadius: 12).fill(StreamifyTheme.primary))
                    }
                    .disabled(busy)

                    if let sessionInfo {
                        Text(sessionInfo)
                            .font(.system(size: 12).monospaced())
                            .foregroundColor(StreamifyTheme.textSecondary)
                            .padding(14)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(RoundedRectangle(cornerRadius: 10).fill(StreamifyTheme.bgCard))
                    }
                }
                .padding(20)
            }
            .background(StreamifyTheme.bgBase)
            .navigationTitle("Jam Sessions")
        }
    }
}
