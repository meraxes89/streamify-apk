import CoreImage
import CoreImage.CIFilterBuiltins
import SwiftUI
import Observation

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG4-06 (Engineer 4): the Jam session surface — the SwiftUI counterpart
// of `JamSessionScreen.kt`: dynamic host/join sheet, QR code sharing, the PTP
// sync badge (green < 5 ms, yellow < 20 ms, orange < 50 ms, red >= 50 ms) and
// the live participant list with volume meters.
//
// STATE: everything renders from JamViewModel's rev-guarded room snapshots
// (push-only — MP-DEC-04). The QR carries the offline pairing URI verbatim
// (JamPairing); CoreImage renders it cross-platform (iOS + macOS).
// ─────────────────────────────────────────────────────────────────────────────

@MainActor
struct JamSessionView: View {
    @State private var jamModel = JamViewModel()
    @State private var roomCodeInput = ""
    @State private var showQrSheet = false
    @State private var showRoster = false

    var body: some View {
        NavigationStack {
            Group {
                if jamModel.status == .active, jamModel.room != nil {
                    activeRoom
                } else {
                    hostJoinSheet
                }
            }
            .background(StreamifyTheme.bgBase)
            .navigationTitle("Jam")
            .navigationBarTitleDisplayMode(.inline)
        }
        .task {
            jamModel.start()
            jamModel.startDiscovery()
        }
        .onDisappear {
            jamModel.stopDiscovery()
            jamModel.stop()
        }
        .sheet(isPresented: $showQrSheet) {
            if let room = jamModel.room {
                JamQrShareSheet(uri: jamModel.pairingUri, sessionCode: room.sessionCode)
            }
        }
    }

    // MARK: - Host / Join sheet (idle state)

    private var hostJoinSheet: some View {
        ScrollView {
            VStack(spacing: 18) {
                if case .failed(let message) = jamModel.status {
                    failureBanner(message)
                }
                if case .ended(let reason) = jamModel.status {
                    endedBanner(reason)
                }

                hostCard
                joinCard
                nearbyRoomsCard

                if jamModel.isBluetoothAvailable == false {
                    Label(
                        "Bluetooth is off — LAN and code joins still work",
                        systemImage: "dot.radiowaves.left.and.right.slash"
                    )
                    .font(.system(size: 11))
                    .foregroundColor(StreamifyTheme.textTertiary)
                    .padding(.top, 4)
                }
            }
            .padding(16)
        }
    }

    private var hostCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            Label("Host a listening room", systemImage: "person.crop.circle.badge.plus")
                .font(.system(size: 15, weight: .semibold))
                .foregroundColor(StreamifyTheme.textMain)
            Text("Everyone stays in lockstep — PTP-synced playback across phones, Macs and desktops on the same clock.")
                .font(.system(size: 12))
                .foregroundColor(StreamifyTheme.textSecondary)
            Button {
                Task { await jamModel.hostRoom() }
            } label: {
                Text(jamModel.status == .joining ? "Opening room…" : "Start a Jam")
                    .font(.system(size: 15, weight: .semibold))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
            }
            .buttonStyle(.borderedProminent)
            .tint(StreamifyTheme.primary)
            .disabled(jamModel.status == .joining)
        }
        .padding(16)
        .background(StreamifyTheme.bgCard, in: RoundedRectangle(cornerRadius: 16))
    }

    private var joinCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            Label("Join with a code", systemImage: "number")
                .font(.system(size: 15, weight: .semibold))
                .foregroundColor(StreamifyTheme.textMain)
            HStack(spacing: 10) {
                TextField("6-character code", text: $roomCodeInput)
                    .font(.system(size: 17, weight: .medium).monospaced())
                    .autocorrectionDisabled()
                #if os(iOS)
                    .textInputAutocapitalization(.characters)
                #endif
                    .frame(maxWidth: 160)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 10)
                    .background(StreamifyTheme.bgSurfaceElevated, in: RoundedRectangle(cornerRadius: 10))
                Button {
                    Task { await jamModel.joinRoom(code: roomCodeInput) }
                } label: {
                    Text("Join")
                        .font(.system(size: 15, weight: .semibold))
                        .padding(.horizontal, 20)
                        .padding(.vertical, 12)
                }
                .buttonStyle(.borderedProminent)
                .tint(StreamifyTheme.accentBlue)
                .disabled(roomCodeInput.trimmingCharacters(in: .whitespaces).count < 6 || jamModel.status == .joining)
            }
            Text("Or tap a nearby room below when two phones come close.")
                .font(.system(size: 11))
                .foregroundColor(StreamifyTheme.textTertiary)
        }
        .padding(16)
        .background(StreamifyTheme.bgCard, in: RoundedRectangle(cornerRadius: 16))
    }

    @ViewBuilder
    private var nearbyRoomsCard: some View {
        let rooms = jamModel.nearbyRooms
        if !rooms.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                Label("Nearby", systemImage: "wave.iphone")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundColor(StreamifyTheme.textMain)
                ForEach(rooms) { room in
                    Button {
                        Task { await jamModel.joinNearbyRoom(room) }
                    } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 3) {
                                Text(room.promptTitle)
                                    .font(.system(size: 14, weight: .semibold))
                                    .foregroundColor(StreamifyTheme.textMain)
                                Text(room.promptSubtitle)
                                    .font(.system(size: 11))
                                    .foregroundColor(StreamifyTheme.textSecondary)
                            }
                            Spacer()
                            SignalBars(rssi: room.rssi)
                        }
                        .padding(12)
                        .background(StreamifyTheme.bgSurfaceElevated, in: RoundedRectangle(cornerRadius: 12))
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(16)
            .background(StreamifyTheme.bgCard, in: RoundedRectangle(cornerRadius: 16))
        }
    }

    private func failureBanner(_ message: String) -> some View {
        Label(message, systemImage: "exclamationmark.triangle")
            .font(.system(size: 12))
            .foregroundColor(StreamifyTheme.textSecondary)
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(JamPalette.warn.opacity(0.12), in: RoundedRectangle(cornerRadius: 12))
    }

    private func endedBanner(_ reason: String) -> some View {
        Label("Jam ended — \(reason)", systemImage: "door.left.hand.open")
            .font(.system(size: 12))
            .foregroundColor(StreamifyTheme.textSecondary)
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(StreamifyTheme.bgCard, in: RoundedRectangle(cornerRadius: 12))
    }

    // MARK: - Active room

    private var activeRoom: some View {
        ScrollView {
            VStack(spacing: 16) {
                roomHeader
                SyncBadge(grade: jamModel.syncGrade, latencyMs: jamModel.syncLatencyMs)
                participantsStrip
                if jamModel.isHost { hostControls }
                roomQueue
                leaveControls
            }
            .padding(16)
        }
    }

    private var roomHeader: some View {
        VStack(spacing: 8) {
            Text(jamModel.isHost ? "HOSTING" : "CONNECTED")
                .font(.system(size: 11, weight: .bold))
                .foregroundColor(jamModel.isHost ? StreamifyTheme.primary : StreamifyTheme.accentBlue)
                .tracking(1.5)
            Text(jamModel.sessionCode.isEmpty ? "------" : jamModel.sessionCode)
                .font(.system(size: 38, weight: .bold).monospaced())
                .foregroundColor(StreamifyTheme.textMain)
                .kerning(4)
            HStack(spacing: 12) {
                Button {
                    JamClipboard.copy(jamModel.sessionCode)
                } label: {
                    Label("Copy", systemImage: "doc.on.doc")
                        .font(.system(size: 12, weight: .medium))
                }
                ShareLink(item: jamModel.inviteShareText) {
                    Label("Invite", systemImage: "square.and.arrow.up")
                        .font(.system(size: 12, weight: .medium))
                }
                Button {
                    showQrSheet = true
                } label: {
                    Label("QR", systemImage: "qrcode")
                        .font(.system(size: 12, weight: .medium))
                }
            }
            .buttonStyle(.bordered)
            .tint(StreamifyTheme.textSecondary)
        }
        .padding(16)
        .frame(maxWidth: .infinity)
        .background(StreamifyTheme.bgCard, in: RoundedRectangle(cornerRadius: 16))
    }

    private var participantsStrip: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Label("In the room", systemImage: "person.2")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundColor(StreamifyTheme.textMain)
                Spacer()
                Text("\(jamModel.participants.count)/32")
                    .font(.system(size: 12).monospacedDigit())
                    .foregroundColor(StreamifyTheme.textTertiary)
            }
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 10) {
                    ForEach(jamModel.participants) { participant in
                        ParticipantPillView(participant: participant)
                    }
                }
                .padding(.vertical, 2)
            }
        }
        .padding(16)
        .background(StreamifyTheme.bgCard, in: RoundedRectangle(cornerRadius: 16))
    }

    private var hostControls: some View {
        VStack(alignment: .leading, spacing: 12) {
            Label("Host controls", systemImage: "crown")
                .font(.system(size: 15, weight: .semibold))
                .foregroundColor(StreamifyTheme.textMain)

            HStack {
                Text("Party mode (one speaker)")
                    .font(.system(size: 12))
                    .foregroundColor(StreamifyTheme.textSecondary)
                Spacer()
                Toggle("", isOn: Binding(
                    get: { jamModel.room?.topology.isPartyMode ?? false },
                    set: { _ in jamModel.toggleTopology() }
                ))
                .labelsHidden()
                .tint(StreamifyTheme.primary)
            }

            HStack {
                Text("Control policy")
                    .font(.system(size: 12))
                    .foregroundColor(StreamifyTheme.textSecondary)
                Spacer()
                Button(jamModel.room?.controlPolicy == .everyone ? "Everyone" : "Host only") {
                    jamModel.cycleControlPolicy()
                }
                .font(.system(size: 12, weight: .semibold))
                .buttonStyle(.bordered)
                .tint(jamModel.room?.controlPolicy == .everyone ? JamPalette.ok : StreamifyTheme.textSecondary)
            }

            if !jamModel.participants.filter({ !$0.isHost }).isEmpty {
                Button {
                    showRoster = true
                } label: {
                    Label("Manage listeners", systemImage: "person.crop.circle.badge.xmark")
                        .font(.system(size: 12, weight: .medium))
                }
                .buttonStyle(.bordered)
                .tint(StreamifyTheme.textSecondary)
            }
        }
        .padding(16)
        .background(StreamifyTheme.bgCard, in: RoundedRectangle(cornerRadius: 16))
        .sheet(isPresented: $showRoster) {
            JamRosterSheet(jamModel: jamModel)
        }
    }

    private var roomQueue: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Label("Shared queue", systemImage: "music.note.list")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundColor(StreamifyTheme.textMain)
                Spacer()
                Text("\(jamModel.queue.count) tracks")
                    .font(.system(size: 12))
                    .foregroundColor(StreamifyTheme.textTertiary)
            }
            if jamModel.queue.isEmpty {
                Text("Nothing queued yet — add a track and everyone hears it on the same beat.")
                    .font(.system(size: 12))
                    .foregroundColor(StreamifyTheme.textSecondary)
                    .padding(.vertical, 8)
            } else {
                ForEach(jamModel.queue) { entry in
                    HStack(spacing: 10) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(entry.track.title)
                                .font(.system(size: 14, weight: .medium))
                                .foregroundColor(StreamifyTheme.textMain)
                                .lineLimit(1)
                            HStack(spacing: 6) {
                                Text(entry.track.artist)
                                if !entry.addedBy.isEmpty {
                                    Text("· added by \(entry.addedBy)")
                                }
                            }
                            .font(.system(size: 11))
                            .foregroundColor(StreamifyTheme.textTertiary)
                            .lineLimit(1)
                        }
                        Spacer()
                        Button {
                            jamModel.castVote(trackId: entry.track.id)
                        } label: {
                            HStack(spacing: 4) {
                                Image(systemName: entry.hasMyVote ? "arrowtriangle.up.fill" : "arrowtriangle.up")
                                Text("\(entry.votes)")
                                    .monospacedDigit()
                            }
                            .font(.system(size: 12, weight: .semibold))
                        }
                        .buttonStyle(.bordered)
                        .tint(entry.hasMyVote ? StreamifyTheme.primary : StreamifyTheme.textTertiary)
                    }
                    .padding(.vertical, 6)
                }
            }
        }
        .padding(16)
        .background(StreamifyTheme.bgCard, in: RoundedRectangle(cornerRadius: 16))
    }

    private var leaveControls: some View {
        Button(role: .destructive) {
            Task { await jamModel.leaveRoom(endForEveryone: jamModel.isHost) }
        } label: {
            Text(jamModel.isHost ? "End Jam for everyone" : "Leave Jam")
                .font(.system(size: 15, weight: .semibold))
                .frame(maxWidth: .infinity)
                .padding(.vertical, 12)
        }
        .buttonStyle(.bordered)
        .tint(JamPalette.danger)
    }
}

// MARK: - Sync palette (SW-ENG4-06 thresholds)

enum JamPalette {
    static let ok = Color(red: 16 / 255, green: 185 / 255, blue: 129 / 255)      // < 5 ms
    static let good = Color(red: 52 / 255, green: 211 / 255, blue: 153 / 255)    // locked
    static let warn = Color(red: 245 / 255, green: 158 / 255, blue: 11 / 255)    // < 20 ms
    static let drift = Color(red: 249 / 255, green: 115 / 255, blue: 22 / 255)   // < 50 ms
    static let danger = Color(red: 239 / 255, green: 68 / 255, blue: 68 / 255)   // >= 50 ms

    static func color(for grade: SyncGrade) -> Color {
        switch grade {
        case .locked: return ok
        case .tight: return warn
        case .drifting: return drift
        case .outOfSync: return danger
        }
    }
}

/// The PTP lockstep badge: grade color + measured worst round-trip.
struct SyncBadge: View {
    let grade: SyncGrade
    let latencyMs: Double

    var body: some View {
        HStack(spacing: 8) {
            Circle()
                .fill(JamPalette.color(for: grade))
                .frame(width: 8, height: 8)
            Text("● \(grade.label)")
                .font(.system(size: 12, weight: .bold))
                .foregroundColor(JamPalette.color(for: grade))
            Text(String(format: "%.1f ms", latencyMs))
                .font(.system(size: 12).monospacedDigit())
                .foregroundColor(StreamifyTheme.textSecondary)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
        .background(StreamifyTheme.bgCard, in: Capsule())
    }
}

// MARK: - Signal strength bars (BLE proximity)

struct SignalBars: View {
    let rssi: Int

    private var level: Int {
        switch rssi {
        case (-50)...: return 3
        case (-65)...(-50): return 2
        case (-80)...(-65): return 1
        default: return 0
        }
    }

    var body: some View {
        HStack(alignment: .bottom, spacing: 2) {
            ForEach(0..<3, id: \.self) { bar in
                RoundedRectangle(cornerRadius: 1)
                    .fill(bar < level ? StreamifyTheme.accentBlue : StreamifyTheme.bgSurfaceElevated)
                    .frame(width: 3, height: 4 + CGFloat(bar) * 3)
            }
        }
    }
}

// MARK: - Roster sheet (kick / ACL)

private struct JamRosterSheet: View {
    let jamModel: JamViewModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                ForEach(jamModel.participants.filter { !$0.isHost }) { participant in
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(participant.name)
                                .font(.system(size: 14, weight: .medium))
                            Text(participant.isCoHost ? "Co-host" : "Listener")
                                .font(.system(size: 11))
                                .foregroundColor(StreamifyTheme.textTertiary)
                        }
                        Spacer()
                        Button("Kick", role: .destructive) {
                            jamModel.kickMember(nonce: participant.nonce)
                        }
                        .font(.system(size: 12))
                        .buttonStyle(.bordered)
                    }
                }
            }
            .navigationTitle("Manage listeners")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}

// MARK: - QR share sheet

struct JamQrShareSheet: View {
    let uri: String
    let sessionCode: String
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(spacing: 18) {
            Capsule().fill(StreamifyTheme.bgSurfaceElevated).frame(width: 40, height: 5).padding(.top, 10)
            Text("Scan to join")
                .font(.system(size: 17, weight: .bold))
                .foregroundColor(StreamifyTheme.textMain)
            QrCodeView(payload: uri, scale: 8)
                .frame(width: 210, height: 210)
            Text(sessionCode)
                .font(.system(size: 22, weight: .bold).monospaced())
                .kerning(3)
                .foregroundColor(StreamifyTheme.textMain)
            Text("The pairing key never leaves this QR — it expires with the room.")
                .font(.system(size: 11))
                .foregroundColor(StreamifyTheme.textTertiary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 30)
            ShareLink(item: uri) {
                Text("Share invite")
                    .font(.system(size: 14, weight: .semibold))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 10)
            }
            .buttonStyle(.borderedProminent)
            .tint(StreamifyTheme.primary)
            .padding(.horizontal, 24)
            Button("Done") { dismiss() }
                .font(.system(size: 13))
                .padding(.bottom, 12)
        }
        .background(StreamifyTheme.bgBase)
        .presentationDetents([.medium])
    }
}

/// Cross-platform QR renderer (CoreImage CIQRCodeGenerator — iOS + macOS).
struct QrCodeView: View {
    let payload: String
    var scale: CGFloat = 6

    var body: some View {
        if let image = JamQrCode.generate(payload: payload, scale: scale) {
            Image(image, scale: 1, label: Text("Jam pairing QR"))
                .interpolation(.none)
                .resizable()
                .scaledToFit()
                .background(Color.white)
                .cornerRadius(8)
        } else {
            Image(systemName: "qrcode")
                .font(.system(size: 40))
                .foregroundColor(StreamifyTheme.textTertiary)
        }
    }
}

enum JamQrCode {
    static func generate(payload: String, scale: CGFloat) -> CGImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(payload.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage else { return nil }
        let sized = output.transformed(
            by: CGAffineTransform(scaleX: scale, y: scale)
        )
        let context = CIContext()
        return context.createCGImage(sized, from: sized.extent)
    }
}

/// Cross-platform clipboard (UIPasteboard / NSPasteboard).
enum JamClipboard {
    static func copy(_ text: String) {
        #if canImport(UIKit)
        UIPasteboard.general.string = text
        #elseif canImport(AppKit)
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(text, forType: .string)
        #endif
    }
}
