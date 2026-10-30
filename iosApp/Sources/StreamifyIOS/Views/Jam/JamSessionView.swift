import SwiftUI

#if os(iOS)
import UIKit
#endif

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2 (Jam wave) — JamSessionView: the group-listening room.
//
// Native port of ui/screens/JamSessionScreen.kt (Active branch): the session
// header (name + code chip + copy — UIPasteboard on iOS — plus the red Leave
// chip), the JamWidgetsView instruments, the 4-column peer grid, the host
// governance pills (open the JamRosterView sheet), the queue voting list
// (upvote fires HeartBurstView + .jamUpvoteRising) and up-next chips. Pure
// props — the shell owns PlaybackViewModel.createJamSession.
// ─────────────────────────────────────────────────────────────────────────────

private let jamPeerColumns = Array(repeating: GridItem(.flexible(), spacing: StreamifyDimens.spaceMD), count: 4)

public struct JamSessionView: View {

    public var sessionName: String
    public var sessionCode: String
    public var isHost: Bool
    public var peers: [JamPeer]
    public var queueTracks: [VirtualShelfTrack]
    public var onLeave: () -> Void
    public var onInvite: () -> Void
    public var onUpvote: (String) -> Void
    public var onGovernate: (String, String) -> Void
    public var onPlayTrack: (VirtualShelfTrack) -> Void

    @State private var showRoster = false

    public init(
        sessionName: String = "Pocket listening room", sessionCode: String = "JAM-4471",
        isHost: Bool = true, peers: [JamPeer] = PreviewCatalog.jamPeers,
        queueTracks: [VirtualShelfTrack] = Array(PreviewCatalog.tracks[0..<5]),
        onLeave: @escaping () -> Void = {}, onInvite: @escaping () -> Void = {},
        onUpvote: @escaping (String) -> Void = {}, onGovernate: @escaping (String, String) -> Void = {},
        onPlayTrack: @escaping (VirtualShelfTrack) -> Void
    ) {
        self.sessionName = sessionName; self.sessionCode = sessionCode; self.isHost = isHost
        self.peers = peers; self.queueTracks = queueTracks; self.onLeave = onLeave
        self.onInvite = onInvite; self.onUpvote = onUpvote; self.onGovernate = onGovernate
        self.onPlayTrack = onPlayTrack
    }

    public var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: StreamifyDimens.spaceLG) {
                sessionHeader
                JamWidgetsView(sessionCode: sessionCode, peers: peers)
                rosterHeader
                LazyVGrid(columns: jamPeerColumns, spacing: StreamifyDimens.spaceMD) {
                    ForEach(peers) { peer in JamPeerTile(peer: peer) }
                }
                if isHost { governancePills }
                sectionHeader("QUEUE VOTING · \(queueTracks.count)")
                ForEach(queueTracks) { track in
                    JamVoteRow(track: track, onPlay: { onPlayTrack(track) }, onUpvote: { onUpvote(track.id) })
                }
                upNextChips
            }
            .padding(StreamifyDimens.spaceLG)
        }
        .background(StreamifyColors.bgBase)
        .sheet(isPresented: $showRoster) {
            JamRosterView(peers: peers, isHost: isHost, onDone: { showRoster = false }, onGovernate: onGovernate)
        }
    }

    private func sectionHeader(_ title: String) -> some View {
        Text(title).font(StreamifyType.caption.weight(.bold)).tracking(1).foregroundStyle(StreamifyColors.textSecondary)
    }

    private var sessionHeader: some View {
        HStack(alignment: .top, spacing: StreamifyDimens.spaceMD) {
            VStack(alignment: .leading, spacing: StreamifyDimens.spaceSM) {
                Text(sessionName).font(StreamifyType.headlineMedium).foregroundStyle(StreamifyColors.textMain).lineLimit(1)
                HStack(spacing: StreamifyDimens.spaceSM) {
                    Text(sessionCode).font(StreamifyType.chipText.monospaced()).foregroundStyle(StreamifyColors.textMain)
                        .padding(.horizontal, 10).padding(.vertical, 4).background(StreamifyColors.bgChipInactive, in: StreamifyShapes.chip)
                    Button(action: copySessionCode) {
                        Image(systemName: "doc.on.doc").font(.system(size: 12, weight: .semibold))
                            .foregroundStyle(StreamifyColors.textSecondary).frame(width: 26, height: 26)
                    }
                    .buttonStyle(.plain).accessibilityLabel("Copy session code")
                }
            }
            Spacer(minLength: 0)
            Button {
                Haptics.play(.skipTrigger)
                onLeave()
            } label: {
                Text("Leave").font(StreamifyType.chipText.weight(.semibold)).foregroundStyle(StreamifyColors.errorRed)
                    .padding(.horizontal, 14).padding(.vertical, 7).background(StreamifyColors.errorBg, in: Capsule())
            }
            .buttonStyle(.pressableCard)
        }
    }

    private func copySessionCode() {
        #if os(iOS)
        UIPasteboard.general.string = sessionCode
        #else
        Haptics.play(.scrubTick)
        #endif
    }

    private var rosterHeader: some View {
        HStack(spacing: StreamifyDimens.spaceMD) {
            Text("IN THE ROOM · \(peers.count)")
                .font(StreamifyType.caption.weight(.bold)).tracking(1).foregroundStyle(StreamifyColors.textSecondary)
            Spacer(minLength: 0)
            Button {
                Haptics.play(.queueGrab)
                onInvite()
            } label: {
                HStack(spacing: StreamifyDimens.spaceXS) {
                    Image(systemName: StreamifyIcon.personPlus).font(.system(size: 11, weight: .semibold))
                    Text("Invite").font(StreamifyType.chipText)
                }
                .foregroundStyle(StreamifyColors.textOnActiveChip).padding(.horizontal, 12).padding(.vertical, 6)
                .background(StreamifyColors.activeControl, in: Capsule())
            }
            .buttonStyle(.pressableCard)
        }
    }

    private var governancePills: some View {
        HStack(spacing: StreamifyDimens.spaceSM) {
            governancePill("Kick", systemImage: "person.crop.circle.badge.xmark", tint: StreamifyColors.errorRed)
            governancePill("Ban", systemImage: "nosign", tint: StreamifyColors.warningAmber)
            governancePill("Co-host", systemImage: StreamifyIcon.crown, tint: StreamifyColors.accentBlue)
            governancePill("Report", systemImage: "flag", tint: StreamifyColors.textSecondary)
        }
    }

    private func governancePill(_ title: String, systemImage: String, tint: Color) -> some View {
        Button {
            Haptics.play(.queueGrab)
            showRoster = true
        } label: {
            HStack(spacing: 6) {
                Image(systemName: systemImage).font(.system(size: 12, weight: .semibold)).foregroundStyle(tint)
                Text(title).font(StreamifyType.chipText).foregroundStyle(StreamifyColors.textMain)
            }
            .frame(maxWidth: .infinity).padding(.vertical, 8).chipSurface(active: false)
        }
        .buttonStyle(.pressableCard)
    }

    @ViewBuilder
    private var upNextChips: some View {
        if queueTracks.count > 1 {
            VStack(alignment: .leading, spacing: StreamifyDimens.spaceSM) {
                sectionHeader("UP NEXT")
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: StreamifyDimens.spaceSM) {
                        ForEach(queueTracks.dropFirst()) { track in
                            HStack(spacing: 6) {
                                Image(systemName: StreamifyIcon.musicNote).font(.system(size: 10))
                                Text(track.title).font(StreamifyType.chipText).lineLimit(1)
                            }
                            .foregroundStyle(StreamifyColors.textMain).padding(.horizontal, 12).padding(.vertical, 6)
                            .chipSurface(active: false)
                        }
                    }
                }
            }
        }
    }
}

// Peer tile (Android RosterStripAvatar, native initials tile)
private struct JamPeerTile: View {
    let peer: JamPeer
    var body: some View {
        VStack(spacing: StreamifyDimens.spaceXS) {
            ZStack {
                Circle().fill(StreamifyGradients.linear(StreamifyGradients.preset(forSeed: peer.name)))
                Text(String(peer.name.prefix(1)).uppercased()).font(.system(size: 16, weight: .bold)).foregroundStyle(.white)
            }
            .frame(width: 44, height: 44)
            .overlay(alignment: .topTrailing) {
                if peer.isHost {
                    Image(systemName: StreamifyIcon.crown).font(.system(size: 10)).foregroundStyle(StreamifyColors.warningAmber)
                }
            }
            Text(peer.name).font(StreamifyType.caption).foregroundStyle(StreamifyColors.textMain).lineLimit(1)
            Text("\(peer.rttMs) ms").font(.system(size: 9, weight: .semibold).monospacedDigit())
                .foregroundStyle(StreamifyColors.textTertiary)
                .padding(.horizontal, 6).padding(.vertical, 2).background(StreamifyColors.bgChipInactive, in: StreamifyShapes.chip)
            if peer.isCoHost {
                Text("CO-HOST").font(.system(size: 8, weight: .heavy)).tracking(0.5).foregroundStyle(StreamifyColors.accentBlue)
                    .padding(.horizontal, 6).padding(.vertical, 2).background(StreamifyColors.accentBlue.opacity(0.14), in: StreamifyShapes.chip)
            }
        }
        .frame(maxWidth: .infinity)
    }
}

// Queue voting row (upvote burst + .jamUpvoteRising haptic)
private struct JamVoteRow: View {
    let track: VirtualShelfTrack
    let onPlay: () -> Void
    let onUpvote: () -> Void
    @State private var votes: Int
    @State private var hasVoted = false
    @State private var burstTrigger = 0
    init(track: VirtualShelfTrack, onPlay: @escaping () -> Void, onUpvote: @escaping () -> Void) {
        self.track = track; self.onPlay = onPlay; self.onUpvote = onUpvote
        // Deterministic live-tally seed (the shell owns the true CRDT tally).
        var hash: UInt64 = 5381
        for scalar in track.id.unicodeScalars { hash = (hash &<< 5) &+ hash &+ UInt64(scalar.value) }
        _votes = State(initialValue: Int(hash % 7) + 2)
    }

    var body: some View {
        HStack(spacing: StreamifyDimens.spaceMD) {
            ZStack {
                RoundedRectangle(cornerRadius: StreamifyRadius.md, style: .continuous)
                    .fill(StreamifyGradients.linear(StreamifyGradients.preset(forSeed: track.title)))
                Image(systemName: StreamifyIcon.musicNote).font(.system(size: 14)).foregroundStyle(Color.white.opacity(0.9))
            }
            .frame(width: 44, height: 44)
            VStack(alignment: .leading, spacing: 2) {
                Text(track.title).font(StreamifyType.songTitle).foregroundStyle(StreamifyColors.textMain).lineLimit(1)
                Text(track.artist).font(StreamifyType.songArtist).foregroundStyle(StreamifyColors.textSecondary).lineLimit(1)
            }
            Spacer(minLength: 0)
            Button {
                Haptics.play(.jamUpvoteRising)
                burstTrigger += 1; votes += 1; hasVoted = true
                onUpvote()
            } label: {
                VStack(spacing: 1) {
                    Image(systemName: "arrow.up").font(.system(size: 13, weight: .bold))
                        .foregroundStyle(hasVoted ? StreamifyColors.activeControl : StreamifyColors.textTertiary)
                    Text("\(votes)").font(.system(size: 10, weight: .semibold).monospacedDigit())
                        .foregroundStyle(hasVoted ? StreamifyColors.textMain : StreamifyColors.textTertiary)
                }
                .frame(width: 38).contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .overlay(HeartBurstView(trigger: burstTrigger)).accessibilityLabel("Upvote \(track.title)")
        }
        .padding(.horizontal, StreamifyDimens.spaceMD).padding(.vertical, StreamifyDimens.spaceSM)
        .background(StreamifyColors.bgCard, in: StreamifyShapes.card)
        .overlay(StreamifyShapes.card.strokeBorder(StreamifyColors.borderSubtle, lineWidth: 1))
        .contentShape(Rectangle()).onTapGesture { onPlay() }
    }
}

#Preview("Jam session") {
    JamSessionView(onPlayTrack: { _ in })
}
