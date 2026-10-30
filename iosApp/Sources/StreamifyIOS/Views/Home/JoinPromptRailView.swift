import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2-03 — JoinPromptRailView: the zero-friction "Join the session" rail.
//
// Native port of ui/screens/JoinPromptSheet.kt (JoinPromptRail — BEHOLD.md
// Gap #12): one card per Join Fabric link type (BLE proximity tap, LAN room
// announce, mesh beacon 0x09, speaker connect), each with its transport
// glyph, the link-type label, a "Tap to join" hint and the live session-code
// chip when a sighting carries one. Android's permission launcher and the
// JoinFabric discovery jobs stay on the engine lane — this surface is pure:
// codes in through props, one-tap joins out through onJoin(linkType, code).
// ─────────────────────────────────────────────────────────────────────────────

public struct JoinPromptRailView: View {

    /// Live session code per link type, when a sighting exists.
    public var sessionCodes: [JamLinkType: String]
    public var onJoin: (JamLinkType, String?) -> Void

    public init(
        sessionCodes: [JamLinkType: String] = [:],
        onJoin: @escaping (JamLinkType, String?) -> Void = { _, _ in }
    ) {
        self.sessionCodes = sessionCodes
        self.onJoin = onJoin
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: StreamifyDimens.spaceMD) {
            HStack(spacing: StreamifyDimens.spaceSM) {
                Image(systemName: StreamifyIcon.jam)
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(StreamifyColors.primary)
                Text("Join the session")
                    .font(StreamifyType.headlineSmall)
                    .foregroundStyle(StreamifyColors.textMain)
                Spacer(minLength: StreamifyDimens.spaceSM)
                Text("Live rooms around you")
                    .font(StreamifyType.caption)
                    .foregroundStyle(StreamifyColors.textTertiary)
            }
            .padding(.horizontal, StreamifyDimens.spaceLG)

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: StreamifyDimens.spaceMD) {
                    ForEach(JamLinkType.allCases, id: \.self) { linkType in
                        JoinPromptCard(
                            linkType: linkType,
                            sessionCode: sessionCodes[linkType],
                            onJoin: onJoin
                        )
                        .frame(width: 250)
                    }
                }
                .padding(.horizontal, StreamifyDimens.spaceLG)
                .padding(.vertical, StreamifyDimens.spaceXXS)
            }
        }
        .padding(.vertical, StreamifyDimens.spaceMD)
    }
}

/// One link-type join card (Android: the sighting Surface row).
private struct JoinPromptCard: View {
    let linkType: JamLinkType
    let sessionCode: String?
    let onJoin: (JamLinkType, String?) -> Void

    var body: some View {
        Button {
            Haptics.play(.queueGrab)
            onJoin(linkType, sessionCode)
        } label: {
            HStack(spacing: StreamifyDimens.spaceMD) {
                iconPlate
                VStack(alignment: .leading, spacing: StreamifyDimens.spaceXXS) {
                    Text(linkType.label)
                        .font(StreamifyType.cardTitle)
                        .foregroundStyle(StreamifyColors.textMain)
                        .lineLimit(1)
                    Text("Tap to join")
                        .font(StreamifyType.cardSubtitle)
                        .foregroundStyle(StreamifyColors.textSecondary)
                        .lineLimit(1)
                    if let sessionCode {
                        codeChip(sessionCode)
                    }
                }
                Spacer(minLength: 0)
                Text("JOIN")
                    .font(StreamifyType.caption.weight(.bold))
                    .foregroundStyle(Color.white)
                    .padding(.horizontal, StreamifyDimens.spaceMD)
                    .padding(.vertical, StreamifyDimens.spaceXXS)
                    .background(Capsule().fill(StreamifyColors.primary))
            }
            .padding(StreamifyDimens.spaceMD)
            .cardSurface(hoverable: false)
        }
        .buttonStyle(.pressableCard)
        .accessibilityLabel("Join via \(linkType.label)")
    }

    /// Transport glyph per Join Fabric rail, mapped onto the SF Symbol
    /// catalog: ble → broadcast ping, lan → network, mesh → radio beacon,
    /// speaker → speaker output.
    private var symbol: String {
        switch linkType {
        case .ble: return StreamifyIcon.broadcast
        case .lan: return StreamifyIcon.network
        case .mesh: return StreamifyIcon.radio
        case .speaker: return StreamifyIcon.speaker
        }
    }

    private var iconPlate: some View {
        Image(systemName: symbol)
            .font(.system(size: 18, weight: .semibold))
            .foregroundStyle(StreamifyColors.primary)
            .frame(width: 38, height: 38)
            .background(
                RoundedRectangle(cornerRadius: StreamifyRadius.lg, style: .continuous)
                    .fill(StreamifyColors.primary.opacity(0.15))
            )
    }

    private func codeChip(_ code: String) -> some View {
        HStack(spacing: StreamifyDimens.spaceXXS) {
            Image(systemName: StreamifyIcon.qr)
                .font(.system(size: 9, weight: .bold))
            Text(code)
                .font(StreamifyType.caption.weight(.bold))
                .lineLimit(1)
                .monospacedDigit()
        }
        .foregroundStyle(StreamifyColors.primary)
        .padding(.horizontal, StreamifyDimens.spaceSM)
        .padding(.vertical, StreamifyDimens.spaceXXS)
        .background(StreamifyColors.primary.opacity(0.16), in: StreamifyShapes.chip)
    }
}

#Preview("Join prompt rail") {
    JoinPromptRailView(sessionCodes: [.ble: "JAM-4471", .lan: "JAM-8802"])
        .padding()
        .background(StreamifyColors.bgBase)
        .preferredColorScheme(.dark)
}
