import SwiftUI
import Observation

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG4-06 (Engineer 4): the participant pill — one room member with their
// role badge, connection-health ring (RTT-graded) and live output level meter.
// The SwiftUI counterpart of `RosterStripAvatar` + the ConnectionHealthChip.
//
// The volume meter renders the participant's pushed output level; the ring
// color grades the member's round-trip exactly like the Kotlin roster ring
// (< 80 ms green, < 200 ms amber, else red; negative = unknown/grey).
// ─────────────────────────────────────────────────────────────────────────────

struct ParticipantPillView: View {
    let participant: JamParticipant

    private var initials: String {
        let words = participant.name.split(separator: " ").prefix(2)
        return words.compactMap(\.first).map(String.init).joined().uppercased()
    }

    /// Roster-ring grade (Kotlin parity: <80 green, <200 amber, else red;
    /// negative = unknown).
    private var ringColor: Color {
        switch participant.rttMs {
        case ..<0: return StreamifyTheme.textTertiary.opacity(0.35)
        case ..<80: return JamPalette.ok
        case ..<200: return JamPalette.warn
        default: return JamPalette.danger
        }
    }

    private var roleBadge: (symbol: String, color: Color)? {
        if participant.isHost { return ("star.fill", JamPalette.warn) }
        if participant.isCoHost { return ("music.note", StreamifyTheme.accentBlue) }
        return nil
    }

    var body: some View {
        VStack(spacing: 8) {
            ZStack {
                Circle()
                    .stroke(ringColor, lineWidth: 2)
                    .frame(width: 46, height: 46)
                Circle()
                    .fill(StreamifyTheme.bgSurfaceElevated)
                    .frame(width: 40, height: 40)
                Text(initials.isEmpty ? "?" : initials)
                    .font(.system(size: 14, weight: .bold))
                    .foregroundColor(StreamifyTheme.textMain)
                if let badge = roleBadge {
                    Image(systemName: badge.symbol)
                        .font(.system(size: 8, weight: .bold))
                        .foregroundColor(badge.color)
                        .padding(3)
                        .background(StreamifyTheme.bgCard, in: Circle())
                        .offset(x: 15, y: 15)
                }
            }

            VolumeMeter(level: participant.level)
                .frame(width: 34)

            Text(participant.name.isEmpty ? "Listener" : participant.name)
                .font(.system(size: 11, weight: .medium))
                .foregroundColor(StreamifyTheme.textMain)
                .lineLimit(1)
                .frame(maxWidth: 64)

            Text(participant.rttMs >= 0 ? String(format: "%.0f ms", participant.rttMs) : "—")
                .font(.system(size: 9).monospacedDigit())
                .foregroundColor(StreamifyTheme.textTertiary)
        }
        .padding(10)
        .background(StreamifyTheme.bgSurfaceElevated, in: RoundedRectangle(cornerRadius: 14))
        .animation(.spring(response: 0.3, dampingFraction: 0.7), value: participant.level)
    }
}

/// The live output-level meter (five bars driven by the pushed level).
struct VolumeMeter: View {
    let level: Double

    private var litBars: Int {
        Int((min(max(level, 0), 1) * 5).rounded(.up))
    }

    var body: some View {
        HStack(spacing: 2, alignment: .bottom) {
            ForEach(0..<5, id: \.self) { bar in
                RoundedRectangle(cornerRadius: 1)
                    .fill(bar < litBars ? StreamifyTheme.primary : StreamifyTheme.bgCard)
                    .frame(width: 4, height: 5 + CGFloat(bar) * 2.5)
            }
        }
    }
}
