import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2 (Jam wave) — JamRosterView: the 32-member roster & governance sheet.
//
// Native port of ui/screens/JamRosterSheet.kt's CONTENT (the sheet chrome is
// owned by the presenter): its own "Roster · N listening" header with the
// Done chip, the virtualized LazyVStack of member rows (avatar, name, link
// chip, live RTT, host crown, co-host badge), the per-row ellipsis context
// menu (Make co-host / Kick / Ban / Report → onGovernate(peerId, action)) and
// the "32-member capacity · virtualized roster" footer caption.
// Fixtures: PreviewCatalog (12-peer inline fixture in the preview).
// ─────────────────────────────────────────────────────────────────────────────

public struct JamRosterView: View {

    public let peers: [JamPeer]
    public var isHost: Bool
    public var onDone: () -> Void
    public var onGovernate: (String, String) -> Void

    public init(
        peers: [JamPeer],
        isHost: Bool = true,
        onDone: @escaping () -> Void = {},
        onGovernate: @escaping (String, String) -> Void = {}
    ) {
        self.peers = peers
        self.isHost = isHost
        self.onDone = onDone
        self.onGovernate = onGovernate
    }

    public var body: some View {
        VStack(spacing: StreamifyDimens.spaceMD) {
            header
            ScrollView {
                LazyVStack(spacing: StreamifyDimens.spaceSM) {
                    ForEach(peers) { peer in
                        RosterRow(peer: peer, isHost: isHost, onGovernate: onGovernate)
                    }
                    Text("32-member capacity · virtualized roster")
                        .font(StreamifyType.caption)
                        .foregroundStyle(StreamifyColors.textTertiary)
                        .frame(maxWidth: .infinity)
                        .padding(.top, StreamifyDimens.spaceSM)
                }
                .padding(.horizontal, StreamifyDimens.spaceLG)
                .padding(.bottom, StreamifyDimens.spaceXL)
            }
        }
        .background(StreamifyColors.bgBase)
    }

    private var header: some View {
        HStack(alignment: .center, spacing: StreamifyDimens.spaceMD) {
            VStack(alignment: .leading, spacing: 2) {
                Text("Roster · \(peers.count) listening")
                    .font(StreamifyType.headlineMedium)
                    .foregroundStyle(StreamifyColors.textMain)
                Text(isHost ? "Tap the ellipsis to manage a member" : "The host manages member permissions")
                    .font(StreamifyType.caption)
                    .foregroundStyle(StreamifyColors.textTertiary)
            }
            Spacer(minLength: 0)
            Button {
                Haptics.play(.queueDrop)
                onDone()
            } label: {
                Text("Done")
                    .font(StreamifyType.chipText.weight(.semibold))
                    .foregroundStyle(StreamifyColors.textOnActiveChip)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 7)
                    .background(StreamifyColors.activeControl, in: Capsule())
            }
            .buttonStyle(.pressableCard)
        }
        .padding(.horizontal, StreamifyDimens.spaceLG)
        .padding(.top, StreamifyDimens.spaceLG)
    }
}

// MARK: - One roster row (Android RosterRow, native context menu)

private struct RosterRow: View {

    let peer: JamPeer
    let isHost: Bool
    let onGovernate: (String, String) -> Void

    var body: some View {
        HStack(spacing: StreamifyDimens.spaceMD) {
            ZStack {
                Circle().fill(
                    StreamifyGradients.linear(StreamifyGradients.preset(forSeed: peer.name))
                )
                Text(String(peer.name.prefix(1)).uppercased())
                    .font(.system(size: 15, weight: .bold))
                    .foregroundStyle(Color.white)
            }
            .frame(width: 40, height: 40)
            .overlay {
                if peer.isHost {
                    Circle().strokeBorder(StreamifyColors.primary, lineWidth: 2)
                }
            }
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(peer.name)
                        .font(StreamifyType.titleSmall)
                        .foregroundStyle(StreamifyColors.textMain)
                        .lineLimit(1)
                    if peer.isHost {
                        Image(systemName: StreamifyIcon.crown)
                            .font(.system(size: 10))
                            .foregroundStyle(StreamifyColors.warningAmber)
                    }
                    if peer.isCoHost {
                        Text("CO-HOST")
                            .font(.system(size: 8, weight: .heavy))
                            .tracking(0.5)
                            .foregroundStyle(StreamifyColors.accentBlue)
                            .padding(.horizontal, 5)
                            .padding(.vertical, 1)
                            .background(StreamifyColors.accentBlue.opacity(0.14), in: StreamifyShapes.chip)
                    }
                }
                HStack(spacing: StreamifyDimens.spaceSM) {
                    Text(peer.linkType.label)
                        .font(StreamifyType.caption)
                        .foregroundStyle(StreamifyColors.textSecondary)
                    Text("\(peer.rttMs) ms")
                        .font(.system(size: 10).monospacedDigit())
                        .foregroundStyle(StreamifyColors.textTertiary)
                }
            }
            Spacer(minLength: 0)
            Image(systemName: StreamifyIcon.ellipsisCircle)
                .font(.system(size: 15))
                .foregroundStyle(StreamifyColors.textTertiary)
                .frame(width: 30, height: 30)
                .contentShape(Rectangle())
                .contextMenu {
                    if isHost {
                        Button {
                            onGovernate(peer.id, "cohost")
                        } label: {
                            Label("Make co-host", systemImage: StreamifyIcon.crown)
                        }
                        Button {
                            onGovernate(peer.id, "kick")
                        } label: {
                            Label("Kick from jam", systemImage: "person.crop.circle.badge.xmark")
                        }
                        Button {
                            onGovernate(peer.id, "ban")
                        } label: {
                            Label("Ban from room", systemImage: "nosign")
                        }
                    }
                    Button {
                        onGovernate(peer.id, "report")
                    } label: {
                        Label("Report member", systemImage: "flag")
                    }
                }
                .accessibilityLabel("Member actions for \(peer.name)")
        }
        .padding(StreamifyDimens.spaceMD)
        .cardSurface(hoverable: false)
    }
}

#Preview("Virtualized roster") {
    JamRosterView(
        peers: (0..<12).map { index in
            let base = PreviewCatalog.jamPeers[index % PreviewCatalog.jamPeers.count]
            return JamPeer(
                id: "roster-\(index)",
                name: base.name,
                isHost: index == 0,
                isCoHost: index == 1,
                linkType: base.linkType,
                rttMs: 6 + index * 9
            )
        },
        isHost: true
    )
}
