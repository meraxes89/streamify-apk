import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2 (Jam wave) — JamWidgetsView: the live Jam instruments block.
//
// Native port of ui/screens/JamSessionWidgets.kt — three instruments in an
// adaptive layout (radar + gauge side-by-side via ViewThatFits, QR beneath):
//  • JamMeshRadar — 160 pt Canvas: concentric RTT rings, peer dots placed by a
//    stable hash of their id and ranged by measured RTT, a sweeping radar line
//    (rotationEffect driven by a TimelineView phase, paused when idle) and
//    the self node at the center.
//  • JamSyncGauge — a 270° sweep gauge of clock-sync confidence with the
//    "±N µs" readout, the PTP lock badge and the Sinc-resampler rate caption.
//  • JamPairingQr — a deterministic pseudo-QR (13×13 modules, 3 finder
//    squares) seeded from the session code — no network, no zxing.
// Fixtures: PreviewCatalog.
// ─────────────────────────────────────────────────────────────────────────────

private let radarSide: CGFloat = 160
private let radarSweepLength: CGFloat = 72

public struct JamWidgetsView: View {

    public var sessionCode: String
    public var peers: [JamPeer]
    public var driftUs: Double
    public var locked: Bool

    public init(
        sessionCode: String, peers: [JamPeer],
        driftUs: Double = 12, locked: Bool = true
    ) {
        self.sessionCode = sessionCode
        self.peers = peers
        self.driftUs = driftUs
        self.locked = locked
    }

    public var body: some View {
        VStack(spacing: StreamifyDimens.spaceMD) {
            ViewThatFits(in: .horizontal) {
                HStack(spacing: StreamifyDimens.spaceMD) { radarCard; gaugeCard }
                VStack(spacing: StreamifyDimens.spaceMD) { radarCard; gaugeCard }
            }
            pairingCard
        }
    }

    private func instrumentCard(_ caption: String, @ViewBuilder content: () -> some View) -> some View {
        VStack(spacing: StreamifyDimens.spaceSM) {
            Text(caption)
                .font(StreamifyType.caption.weight(.bold)).tracking(1)
                .foregroundStyle(StreamifyColors.textSecondary)
            content()
        }
        .frame(maxWidth: .infinity)
        .padding(StreamifyDimens.spaceMD)
        .cardSurface(hoverable: false)
    }

    private var radarCard: some View {
        instrumentCard("MESH TOPOLOGY") { JamMeshRadar(peers: peers, isActive: true) }
    }

    private var gaugeCard: some View {
        instrumentCard("ACOUSTIC SYNC") { JamSyncGauge(driftUs: driftUs, locked: locked) }
    }

    private var pairingCard: some View {
        instrumentCard("OFFLINE PAIRING") {
            JamPairingQr(sessionCode: sessionCode)
            Text(sessionCode)
                .font(.system(size: 22, weight: .bold, design: .monospaced))
                .foregroundStyle(StreamifyColors.textMain)
            Text("Scan to join")
                .font(StreamifyType.caption)
                .foregroundStyle(StreamifyColors.textTertiary)
        }
    }
}

// MARK: - P2P mesh radar (Android JamMeshRadar)

private struct JamMeshRadar: View {

    let peers: [JamPeer]
    var isActive: Bool

    private static func stableHash(_ seed: String) -> UInt64 {
        var hash: UInt64 = 5381
        for scalar in seed.unicodeScalars { hash = (hash &<< 5) &+ hash &+ UInt64(scalar.value) }
        return hash
    }

    /// Wi-Fi Direct / LAN / mesh transport link palette (Android parity).
    private static func linkColor(_ link: JamLinkType) -> Color {
        switch link {
        case .ble: return Color(streamifyHex: 0x38BDF8)
        case .lan: return Color(streamifyHex: 0x34D399)
        case .mesh: return Color(streamifyHex: 0xA78BFA)
        case .speaker: return Color(streamifyHex: 0xF59E0B)
        }
    }

    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 60.0, paused: !isActive)) { timeline in
            let phase = timeline.date.timeIntervalSinceReferenceDate
                .truncatingRemainder(dividingBy: 4.0) / 4.0
            ZStack {
                Canvas { context, size in
                    let center = CGPoint(x: size.width / 2, y: size.height / 2)
                    let maxR = min(size.width, size.height) / 2 - 4
                    for fraction in [CGFloat(0.36), 0.68, 1.0] {
                        let radius = maxR * fraction
                        let ring = CGRect(x: center.x - radius, y: center.y - radius, width: radius * 2, height: radius * 2)
                        context.stroke(Path(ellipseIn: ring), with: .color(StreamifyColors.borderSubtle), lineWidth: 1)
                    }
                    let count = max(peers.count, 1)
                    for (index, peer) in peers.enumerated() {
                        let angle = Double(Self.stableHash(peer.id) % 360) + Double(index) * 7.0 / Double(count)
                        let radians = angle * .pi / 180
                        let fraction: CGFloat = peer.rttMs <= 50 ? 0.36 : (peer.rttMs <= 150 ? 0.68 : 0.94)
                        let px = center.x + maxR * fraction * CGFloat(cos(radians))
                        let py = center.y + maxR * fraction * CGFloat(sin(radians))
                        let color = Self.linkColor(peer.linkType)
                        let halo = CGRect(x: px - 11, y: py - 11, width: 22, height: 22)
                        let dot = CGRect(x: px - 5, y: py - 5, width: 10, height: 10)
                        context.fill(Path(ellipseIn: halo), with: .color(color.opacity(0.22)))
                        context.fill(Path(ellipseIn: dot), with: .color(color))
                        if peer.isHost {
                            let crown = CGRect(x: px - 8, y: py - 8, width: 16, height: 16)
                            context.stroke(Path(ellipseIn: crown), with: .color(Color(streamifyHex: 0xFACC15)), lineWidth: 2)
                        }
                    }
                    let halo = CGRect(x: center.x - 9, y: center.y - 9, width: 18, height: 18)
                    let dot = CGRect(x: center.x - 5, y: center.y - 5, width: 10, height: 10)
                    context.fill(Path(ellipseIn: halo), with: .color(StreamifyColors.primary.opacity(0.35)))
                    context.fill(Path(ellipseIn: dot), with: .color(StreamifyColors.primary))
                }
                RadarSweep(angle: phase * 360)
            }
        }
        .frame(width: radarSide, height: radarSide)
    }
}

/// The liveness beam: a hairline whose bottom end pivots at the radar center (TimelineView phase drives the rotation).
private struct RadarSweep: View {

    var angle: Double

    var body: some View {
        Rectangle()
            .fill(Color(streamifyHex: 0x34D399).opacity(0.55))
            .frame(width: 1.5, height: radarSweepLength)
            .offset(y: -radarSweepLength / 2)
            .frame(width: radarSide, height: radarSide)
            .rotationEffect(.degrees(angle))
    }
}

// MARK: - Acoustic sync gauge (Android JamSyncGauge)

private struct JamSyncGauge: View {

    let driftUs: Double
    let locked: Bool

    /// Clock-sync confidence: 1 − drift/100, clamped.
    private var confidence: Double { min(max(1.0 - driftUs / 100.0, 0.0), 1.0) }
    private var tint: Color { locked ? StreamifyColors.successGreen : StreamifyColors.warningAmber }

    private var sincRateLabel: String {
        let rate = ((100.0 + driftUs / 400.0) * 100).rounded() / 100
        return "Sinc rate \(rate)%"
    }

    var body: some View {
        ZStack {
            Circle()
                .trim(from: 0, to: 0.75)
                .stroke(StreamifyColors.bgChipInactive, style: StrokeStyle(lineWidth: 8, lineCap: .round))
                .rotationEffect(.degrees(135))
            Circle()
                .trim(from: 0, to: 0.75 * confidence)
                .stroke(tint, style: StrokeStyle(lineWidth: 8, lineCap: .round))
                .rotationEffect(.degrees(135))
            VStack(spacing: 3) {
                Text("±\(Int(abs(driftUs).rounded())) µs")
                    .font(StreamifyType.headlineMedium)
                    .foregroundStyle(StreamifyColors.textMain)
                    .monospacedDigit()
                Text(locked ? "PTP LOCKED" : "PTP UNLOCKED")
                    .font(.system(size: 9, weight: .heavy))
                    .tracking(1)
                    .foregroundStyle(tint)
                    .padding(.horizontal, 6).padding(.vertical, 2)
                    .background(tint.opacity(0.14), in: StreamifyShapes.chip)
                Text(sincRateLabel)
                    .font(StreamifyType.caption)
                    .foregroundStyle(StreamifyColors.textTertiary)
            }
        }
        .frame(width: 128, height: 128)
    }
}

// MARK: - Offline pairing pseudo-QR (Android JamPairingQr, seeded stand-in)

private struct JamPairingQr: View {

    let sessionCode: String
    var side: CGFloat = 128
    private static let modules = 13

    private static func seed(for code: String) -> UInt64 {
        var hash: UInt64 = 5381
        for scalar in code.unicodeScalars { hash = (hash &<< 5) &+ hash &+ UInt64(scalar.value) }
        return hash
    }

    /// The three 7×7 finder footprints (plus one module of separation).
    private static func isFinderZone(x: Int, y: Int) -> Bool {
        (x < 8 && y < 8) || (x > 4 && y < 8) || (x < 8 && y > 4)
    }

    var body: some View {
        Canvas { context, size in
            let cell = min(size.width, size.height) / CGFloat(Self.modules)
            var state = Self.seed(for: sessionCode)
            for y in 0..<Self.modules {
                for x in 0..<Self.modules {
                    guard !Self.isFinderZone(x: x, y: y) else { continue }
                    state = state &* 6_364_136_223_846_793_005 &+ 1_442_695_040_888_963_407
                    if (state >> 33) & 1 == 1 {
                        let rect = CGRect(x: CGFloat(x) * cell, y: CGFloat(y) * cell, width: cell + 0.6, height: cell + 0.6)
                        context.fill(Path(rect), with: .color(Color.black))
                    }
                }
            }
            for origin in [(x: 0, y: 0), (x: Self.modules - 7, y: 0), (x: 0, y: Self.modules - 7)] {
                let outer = CGRect(x: CGFloat(origin.x) * cell, y: CGFloat(origin.y) * cell, width: cell * 7, height: cell * 7)
                context.fill(Path(outer), with: .color(Color.black))
                context.fill(Path(outer.insetBy(dx: cell, dy: cell)), with: .color(Color.white))
                context.fill(Path(outer.insetBy(dx: cell * 2, dy: cell * 2)), with: .color(Color.black))
            }
        }
        .padding(6)
        .background(Color.white, in: RoundedRectangle(cornerRadius: StreamifyRadius.md, style: .continuous))
        .frame(width: side, height: side)
        .accessibilityLabel("Pairing code \(sessionCode)")
    }
}

#Preview("Jam widgets") {
    JamWidgetsView(sessionCode: "JAM-4471", peers: PreviewCatalog.jamPeers)
        .padding(StreamifyDimens.spaceLG)
        .background(StreamifyColors.bgBase)
}
